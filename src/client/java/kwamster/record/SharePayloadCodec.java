package kwamster.record;

import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.stats.HitGrader;
import kwamster.core.stats.StatsCalculator;
import org.jspecify.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Share payload version 1 (§9): the binary layout that rides in a share link's fragment, with the
 * encoder used in game and the reference decoder that the viewer's JavaScript mirrors.
 *
 * <p>Pipeline: {@link #content(FightRecord, boolean)} turns a finished record into a {@link Content}
 * (header, the exact summary block and the full event stream in its final order).
 * {@link #encode(Content, List, Options)} writes the bytes of §9.3 for the whole stream or a thinned
 * subset of it ({@link ShareLinks} chooses the subset). {@link #fragment(byte[])} applies the transport
 * of §9.1 (raw deflate, then unpadded base64url); {@link #payloadOf(String)} undoes it, and
 * {@link #decode(byte[])} reads the bytes back into a {@link Decoded}, whose field names the JS decoder
 * reproduces exactly (the golden vectors of §9.6 compare the two).
 *
 * <p>The version byte is the contract: links in the wild must keep decoding forever, so nothing in
 * this layout may change without a new version number. Minecraft-free (R6). Stateless and
 * thread-safe.
 */
public final class SharePayloadCodec {
	/** The layout version written as the first byte (H1). */
	public static final int VERSION = 1;

	/** H2 bit 0: own and opponent positions are encoded on HIT, MISS and TAKEN events. */
	public static final int FLAG_POSITIONS = 1;
	/** H2 bit 1: the event stream was thinned to fit the size budget. */
	public static final int FLAG_DOWNSAMPLED = 1 << 1;
	/** H2 bit 2: damage totals S43 and S44 follow the summary. */
	public static final int FLAG_DAMAGE = 1 << 2;
	/** H2 bit 3: a server name (H5) is present. */
	public static final int FLAG_SERVER = 1 << 3;
	/** H2 bit 4: more than one opponent is named; events carry a table index. */
	public static final int FLAG_MULTI = 1 << 4;

	/** Event kind (tag bits 0–2): a landed swing; arg = quality code. */
	public static final int KIND_HIT = 0;
	/** Event kind: a missed swing; arg bit 0 = over reach. */
	public static final int KIND_MISS = 1;
	/** Event kind: a melee hit taken; arg bit 0 = own feet on the ground. */
	public static final int KIND_TAKEN = 2;
	/** Event kind: a jump-reset attempt; arg bit 0 = success. */
	public static final int KIND_JUMP = 3;
	/** Event kind: the death that decided the fight; arg bit 0 = you died. */
	public static final int KIND_DEATH = 4;
	/** Tag bit 6: the HIT went into hurt immunity (NO-REG). */
	public static final int TAG_NO_REG = 1 << 6;

	/** Byte cap of the player and opponent names. */
	public static final int NAME_CAP_BYTES = 16;
	/** Byte cap of the names in the last-resort budget stage (§9.5 step 4). */
	public static final int SHORT_NAME_CAP_BYTES = 10;
	/** Byte cap of the server name. */
	public static final int SERVER_CAP_BYTES = 32;
	/** Size of the opponent table: the primary plus up to three other engaged opponents. */
	public static final int MAX_OPPONENTS = 4;
	/** Summary fields S1..S42, always present. */
	public static final int SUMMARY_FIELDS = 42;
	/** S43 and S44, present only with {@link #FLAG_DAMAGE}. */
	public static final int DAMAGE_FIELDS = 2;
	/** Index of S38 (avgMomentumPct), the one signed summary field (zigzag, not varInt). */
	public static final int SIGNED_SUMMARY_INDEX = 37;
	/** Largest encodable opponent distance in decimetres (200 blocks). */
	public static final int MAX_DIST_DM = 2000;
	/**
	 * Inflated payloads above this size are rejected: a v1 payload is a few kilobytes (the longest link
	 * the settings allow is 8000 characters). The viewer uses the same cap ({@code MAX_INFLATED_BYTES}).
	 */
	public static final int MAX_PAYLOAD_BYTES = 1 << 16;
	/** Largest start time (H6) a reader accepts: 2^53 − 1, the largest integer the JS decoder holds exactly. */
	public static final long MAX_START_EPOCH_SEC = (1L << 53) - 1;

	/** Fallback attack range for misses whose stored range limit is unusable (same as StatsCalculator). */
	private static final double VANILLA_RANGE = 3.0;
	private static final int RANK_TAKEN = 0;
	private static final int RANK_SWING = 1;
	private static final int RANK_JUMP = 2;
	private static final int RANK_DEATH = 3;

	private SharePayloadCodec() {
	}

	// ---- model ----------------------------------------------------------------

	/**
	 * One event of the stream with exact (unquantised) values. {@code t} is fight-clock ms; {@code opp}
	 * is the opponent-table index (0–3). Values a kind does not carry are ignored when writing.
	 * Positions are blocks relative to the fight origin, as stored in the record.
	 *
	 * <p>Kinds 5–7 are reserved: the encoder writes only {@code dtCs} and the tag for them (this is how
	 * the {@code reserved_kind} golden vector is built) and a v1 reader stops there.
	 */
	public record Event(int t, int kind, int arg, boolean noReg, int opp,
			double reach, double aimH, double aimV, int deltaMs,
			double sx, double sz, double ox, double oz) {

		/** HIT, MISS and TAKEN carry a position block when {@link #FLAG_POSITIONS} is set. */
		public boolean positional() {
			return this.kind >= KIND_HIT && this.kind <= KIND_TAKEN;
		}

		/** A HIT whose quality is a 3-block tier or CRIT (codes 0–3): thinned less aggressively (§9.5). */
		public boolean priority() {
			return this.kind == KIND_HIT && this.arg >= 0 && this.arg <= HitQuality.CRIT.code();
		}

		/** Whether an opponent index byte follows the tag when {@link #FLAG_MULTI} is set. */
		public boolean carriesOpponent() {
			return SharePayloadCodec.carriesOpponent(this.kind, this.arg);
		}
	}

	/**
	 * Everything payload v1 carries for one fight, before any thinning. {@code opponents} is the
	 * opponent table (index 0 = primary, 1–4 names); {@code server} is already filtered (null when it
	 * must not be shared); {@code summary} holds S1..S42 plus S43..S44 when {@code damage};
	 * {@code events} is the full stream in its final order (§9.3).
	 */
	public record Content(String player, List<String> opponents, @Nullable String server,
			long startEpochSec, int durationMs, int result, int endReason, int pingMs,
			int threeBlockCm, int jumpMinMs, int jumpMaxMs, int jumpAttemptMs, int comboTimeoutMs, int advantageWindowMs,
			int[] summary, boolean damage, List<Event> events) {

		public Content {
			player = player == null ? "" : player;
			List<String> table = new ArrayList<>();
			if (opponents != null) {
				for (String name : opponents) {
					table.add(name == null ? "" : name);
				}
			}
			if (table.isEmpty()) {
				table.add("");
			}
			if (table.size() > MAX_OPPONENTS) {
				throw new IllegalArgumentException("At most " + MAX_OPPONENTS + " opponents fit the table, got " + table.size());
			}
			opponents = List.copyOf(table);
			int need = SUMMARY_FIELDS + (damage ? DAMAGE_FIELDS : 0);
			if (summary == null || summary.length < need) {
				throw new IllegalArgumentException("The summary needs " + need + " fields");
			}
			summary = summary.clone();
			events = List.copyOf(events == null ? List.of() : events);
		}

		/** The table has more than one name, so {@link #FLAG_MULTI} is set and events carry {@code opp}. */
		public boolean multi() {
			return this.opponents.size() > 1;
		}
	}

	/**
	 * What to write. {@code server} writes H5 when the content has a shareable server;
	 * {@code nameCapBytes} caps the player and opponent names; {@code downsampled} sets
	 * {@link #FLAG_DOWNSAMPLED}.
	 */
	public record Options(boolean positions, boolean server, int nameCapBytes, boolean downsampled) {
		/** Positions, server and 16-byte names, not downsampled. */
		public static final Options FULL = new Options(true, true, NAME_CAP_BYTES, false);

		public Options {
			nameCapBytes = Math.max(0, nameCapBytes);
		}
	}

	/**
	 * The reference decoder's output (§9.6). The viewer's {@code KwDecoder.decode} returns a plain
	 * object with exactly these field names.
	 *
	 * @param opponents    index = table index
	 * @param summary      S1..S42 (+S43, S44 if DAMAGE) in table order; S38 signed
	 * @param totalEvents  events in the stream before downsampling (E1)
	 * @param truncated    decoding stopped at a reserved kind
	 */
	public record Decoded(int version, int flags, String player, List<String> opponents,
			@Nullable String server, long startEpochSec, int durationMs, int result, int endReason, int pingMs,
			int threeBlockCm, int jumpMinMs, int jumpMaxMs, int jumpAttemptMs, int comboTimeoutMs, int advantageWindowMs,
			int[] summary,
			int totalEvents, List<Ev> events, boolean truncated) {

		/**
		 * One decoded event.
		 *
		 * @param tCs     absolute time in centiseconds
		 * @param opp     table index; 0 unless MULTI (and for kinds without one)
		 * @param reachQ  1/40-block steps, rounded down ({@link SharePayloadCodec#reachQ}); -1 if the kind has none
		 * @param aimH    hundredths; 0 if absent (aimV likewise)
		 * @param deltaMs JUMP game ms, else 0
		 * @param sxDm    own position in decimetres, absolute; 0 when {@code !hasPos} (szDm likewise)
		 * @param bearing 0–255 (256 steps per turn) towards that event's opponent; 0 when {@code !hasPos}
		 * @param distDm  distance to that event's opponent in decimetres; 0 when {@code !hasPos}
		 */
		public record Ev(int tCs, int kind, int arg, boolean noReg, int opp,
				int reachQ, int aimH, int aimV, int deltaMs,
				boolean hasPos, int sxDm, int szDm, int bearing, int distDm) {
		}
	}

	// ---- record -> content ----------------------------------------------------

	/** {@link #content(FightRecord, FightStats, boolean)} with the record's own stats (computed if absent). */
	public static Content content(FightRecord r, boolean includeServer) {
		return content(r, null, includeServer);
	}

	/**
	 * Builds the payload content of a finished record.
	 *
	 * <ul>
	 *   <li>The opponent table is the primary opponent, then up to three other engaged opponents by
	 *       descending (landed hits + melee hits taken), ties to the lower record index (§9.3).</li>
	 *   <li>The summary comes from {@code stats}, else {@code r.stats}, else
	 *       {@code StatsCalculator.compute(r, FINAL)}: always exact, over every event.</li>
	 *   <li>The stream holds swings (HIT/MISS), melee hits taken (TAKEN), jump attempts (JUMP) and one
	 *       DEATH at {@code durationMs} for a WIN or LOSS, minus events against opponents outside the
	 *       table, sorted on the exact ms by (t, rank, record order), rank TAKEN &lt; HIT = MISS &lt; JUMP
	 *       &lt; DEATH.</li>
	 *   <li>Quality, over-reach and jump success use the same rules as StatsCalculator with the record's
	 *       settings snapshot, so the stream agrees with the summary.</li>
	 * </ul>
	 *
	 * @param includeServer {@code recording.shareIncludeServer}; an IP literal is never included
	 */
	public static Content content(FightRecord r, @Nullable FightStats stats, boolean includeServer) {
		Objects.requireNonNull(r, "record");
		FightStats s = stats != null ? stats : (r.stats != null ? r.stats : StatsCalculator.compute(r, StatsCalculator.FINAL));
		FightRecord.Settings st = r.settings != null ? r.settings : FightRecord.Settings.defaults();
		List<String> names = r.opponents == null ? List.of() : r.opponents;
		int n = names.size();

		int[] authoritative = new int[n];
		if (r.swings != null) {
			for (FightRecord.Swing sw : r.swings) {
				if (sw != null && sw.landed && sw.opp >= 0 && sw.opp < n) {
					authoritative[sw.opp]++;
				}
			}
		}
		if (r.hitsTaken != null) {
			for (FightRecord.HitTaken h : r.hitsTaken) {
				if (h != null && h.opp >= 0 && h.opp < n) {
					authoritative[h.opp]++;
				}
			}
		}
		boolean[] engaged = new boolean[n];
		for (int i = 0; i < n; i++) {
			engaged[i] = r.engaged != null && i < r.engaged.size() && Boolean.TRUE.equals(r.engaged.get(i));
		}

		// Opponent table: record index -> table index.
		int[] tableOf = new int[n];
		Arrays.fill(tableOf, -1);
		List<String> table = new ArrayList<>(MAX_OPPONENTS);
		int primary = primaryIndex(r, engaged, authoritative);
		String primaryName = r.opponentName != null ? r.opponentName : (primary >= 0 ? names.get(primary) : "");
		table.add(primaryName == null ? "" : primaryName);
		if (primary >= 0) {
			tableOf[primary] = 0;
			List<Integer> others = new ArrayList<>();
			for (int i = 0; i < n; i++) {
				if (i != primary && engaged[i]) {
					others.add(i);
				}
			}
			others.sort(Comparator.<Integer>comparingInt(i -> -authoritative[i]).thenComparingInt(i -> i));
			for (int i : others) {
				if (table.size() >= MAX_OPPONENTS) {
					break;
				}
				tableOf[i] = table.size();
				String name = names.get(i);
				table.add(name == null ? "" : name);
			}
		}

		FightResult result = parseResult(r.result);
		FightEndReason reason = parseReason(r.endReason);
		boolean damage = s.damageKnown;

		// Event stream.
		List<Keyed> keyed = new ArrayList<>();
		int seq = 0;
		int lastLandedT = Integer.MIN_VALUE;
		int lastLandedOpp = 0;
		if (r.swings != null) {
			for (FightRecord.Swing sw : r.swings) {
				if (sw == null || sw.opp < 0 || sw.opp >= n || tableOf[sw.opp] < 0) {
					continue;
				}
				int opp = tableOf[sw.opp];
				double reach = finite(sw.reach, 0.0);
				Event e;
				if (sw.landed) {
					int quality = qualityOf(sw, st).code();
					e = new Event(sw.t, KIND_HIT, quality, sw.intoImmunity, opp, reach,
							finite(sw.aimH, 0.0), finite(sw.aimV, 0.0), 0,
							finite(sw.sx, 0.0), finite(sw.sz, 0.0), finite(sw.ox, 0.0), finite(sw.oz, 0.0));
					if (sw.t >= lastLandedT) {
						lastLandedT = sw.t;
						lastLandedOpp = opp;
					}
				} else {
					double limit = Float.isFinite(sw.rangeLimit) && sw.rangeLimit > 0f ? sw.rangeLimit : VANILLA_RANGE;
					boolean over = reach > limit;
					e = new Event(sw.t, KIND_MISS, over ? 1 : 0, false, opp, over ? overReachShown(reach, limit) : reach,
							finite(sw.aimH, 0.0), finite(sw.aimV, 0.0), 0,
							finite(sw.sx, 0.0), finite(sw.sz, 0.0), finite(sw.ox, 0.0), finite(sw.oz, 0.0));
				}
				keyed.add(new Keyed(sw.t, RANK_SWING, seq++, e));
			}
		}
		if (r.hitsTaken != null) {
			for (FightRecord.HitTaken h : r.hitsTaken) {
				if (h == null || h.opp < 0 || h.opp >= n || tableOf[h.opp] < 0) {
					continue;
				}
				Event e = new Event(h.t, KIND_TAKEN, h.onGround ? 1 : 0, false, tableOf[h.opp],
						finite(h.reach, 0.0), 0.0, 0.0, 0,
						finite(h.sx, 0.0), finite(h.sz, 0.0), finite(h.ox, 0.0), finite(h.oz, 0.0));
				keyed.add(new Keyed(h.t, RANK_TAKEN, seq++, e));
			}
		}
		if (r.jumps != null) {
			for (FightRecord.JumpAttempt j : r.jumps) {
				if (j == null) {
					continue;
				}
				// Recomputed from the snapshot exactly as StatsCalculator does (S32 agrees with the stream).
				boolean success = j.deltaTicks > 0 && j.deltaMs >= st.jumpMinMs && j.deltaMs <= st.jumpMaxMs;
				Event e = new Event(j.t, KIND_JUMP, success ? 1 : 0, false, 0, 0.0, 0.0, 0.0, j.deltaMs,
						0.0, 0.0, 0.0, 0.0);
				keyed.add(new Keyed(j.t, RANK_JUMP, seq++, e));
			}
		}
		int duration = Math.max(0, r.durationMs);
		if (result == FightResult.WIN || result == FightResult.LOSS) {
			boolean selfDied = result == FightResult.LOSS;
			// The record does not name the victim; the target of the last landed hit is the one that died.
			int victim = selfDied ? 0 : (lastLandedT == Integer.MIN_VALUE ? 0 : lastLandedOpp);
			Event e = new Event(duration, KIND_DEATH, selfDied ? 1 : 0, false, victim, 0.0, 0.0, 0.0, 0,
					0.0, 0.0, 0.0, 0.0);
			keyed.add(new Keyed(duration, RANK_DEATH, seq++, e));
		}
		keyed.sort(Comparator.comparingInt(Keyed::t).thenComparingInt(Keyed::rank).thenComparingInt(Keyed::seq));
		List<Event> events = new ArrayList<>(keyed.size());
		for (Keyed k : keyed) {
			events.add(k.event());
		}

		return new Content(
				r.playerName == null ? "" : r.playerName,
				table,
				shareableServer(r.server, includeServer),
				Math.max(0L, r.startEpochMs) / 1000L,
				duration,
				result.code(),
				reason.code,
				Math.max(0, r.pingMs),
				nonNegative(Math.round((double) st.threeBlockReach * 100.0)),
				Math.max(0, st.jumpMinMs),
				Math.max(0, st.jumpMaxMs),
				Math.max(0, st.jumpAttemptMs),
				Math.max(0, st.comboTimeoutMs),
				Math.max(0, st.advantageWindowMs),
				summaryOf(s, damage),
				damage,
				events);
	}

	/** The S1..S42 (+S43, S44) block of §9.3 from {@code s}. */
	public static int[] summaryOf(FightStats s, boolean damage) {
		Objects.requireNonNull(s, "stats");
		int[] a = new int[SUMMARY_FIELDS + (damage ? DAMAGE_FIELDS : 0)];
		int i = 0;
		// S1-S6
		a[i++] = Math.max(0, s.swings);
		a[i++] = Math.max(0, s.landed);
		a[i++] = Math.max(0, s.crits);
		a[i++] = Math.max(0, s.sprintHits);
		a[i++] = Math.max(0, s.hitsTaken);
		a[i++] = Math.max(0, s.projectileHitsTaken);
		// S7-S11 (reach rounded down, so "max 2.95" is never shown for a fight without a 3-block reach)
		a[i++] = reachCm(s.reachAvg);
		a[i++] = reachCm(s.reachMax);
		a[i++] = reachCm(s.missReachAvg);
		a[i++] = reachCmVsRange(s.missReachMax, s.missReachMaxLimit);
		a[i++] = Math.max(0, s.overReachMisses);
		// S12-S15
		a[i++] = Math.max(0, s.aimCenter);
		a[i++] = Math.max(0, s.aimEdge);
		a[i++] = Math.max(0, s.aimHigh);
		a[i++] = Math.max(0, s.aimLow);
		// S16-S22
		for (int q = 0; q < 7; q++) {
			a[i++] = s.quality != null && q < s.quality.length ? Math.max(0, s.quality[q]) : 0;
		}
		// S23-S27
		a[i++] = Math.max(0, s.bestCombo);
		a[i++] = Math.max(0, s.combosDealt);
		a[i++] = Math.max(0, s.bestComboTaken);
		a[i++] = Math.max(0, s.combosTaken);
		a[i++] = Math.max(0, s.bestStreak);
		// S28-S30
		a[i++] = rounded(s.intervalAvgMs);
		a[i++] = rounded(s.intervalSdMs);
		a[i++] = Math.max(0, s.intervalCount);
		// S31-S34
		a[i++] = Math.max(0, s.jumpAttempts);
		a[i++] = Math.max(0, s.jumpSuccess);
		a[i++] = Math.max(0, s.jumpEarly);
		a[i++] = Math.max(0, s.jumpOpportunities);
		// S35-S37
		a[i++] = Math.max(0, s.trades);
		a[i++] = Math.max(0, s.tradesWon);
		a[i++] = Math.max(0, s.tradesLost);
		// S38 (signed)
		a[i++] = signedRounded(s.avgMomentumPct);
		// S39-S42
		a[i++] = Math.max(0, s.hitsConfirmed);
		a[i++] = Math.max(0, s.immuneHits);
		a[i++] = rounded(s.advYouPct);
		a[i++] = rounded(s.advThemPct);
		// S43-S44
		if (damage) {
			a[i++] = nonNegative(Math.round(finite(s.damageDealt, 0.0) * 10.0));
			a[i] = nonNegative(Math.round(finite(s.damageTaken, 0.0) * 10.0));
		}
		return a;
	}

	// ---- encode ---------------------------------------------------------------

	/** Encodes the whole stream of a record (no thinning): positions as given, server per {@code includeServer}. */
	public static byte[] encode(FightRecord r, boolean includeServer, boolean positions) {
		Content c = content(r, includeServer);
		return encode(c, c.events(), new Options(positions, true, NAME_CAP_BYTES, false));
	}

	/** Encodes every event of {@code c}. */
	public static byte[] encode(Content c, Options o) {
		return encode(c, c.events(), o);
	}

	/**
	 * Writes payload v1 (§9.3) for {@code events}, which must be {@code c.events()} or an
	 * order-preserving subset of it. E1 is always {@code c.events().size()}.
	 *
	 * @throws IllegalArgumentException if {@code events} is not in stream order
	 */
	public static byte[] encode(Content c, List<Event> events, Options o) {
		Objects.requireNonNull(c, "content");
		Objects.requireNonNull(events, "events");
		Objects.requireNonNull(o, "options");
		boolean multi = c.multi();
		boolean server = o.server() && c.server() != null && !c.server().isEmpty();
		int flags = (o.positions() ? FLAG_POSITIONS : 0)
				| (o.downsampled() ? FLAG_DOWNSAMPLED : 0)
				| (c.damage() ? FLAG_DAMAGE : 0)
				| (server ? FLAG_SERVER : 0)
				| (multi ? FLAG_MULTI : 0);

		Writer w = new Writer(64 + events.size() * 9);
		w.u8(VERSION);                                            // H1
		w.u8(flags);                                              // H2
		w.str(c.player(), o.nameCapBytes());                      // H3
		w.str(c.opponents().get(0), o.nameCapBytes());            // H4
		if (multi) {
			int extra = c.opponents().size() - 1;
			w.u8(extra);                                          // H4a
			for (int i = 1; i <= extra; i++) {
				w.str(c.opponents().get(i), o.nameCapBytes());    // H4b
			}
		}
		if (server) {
			w.str(c.server(), SERVER_CAP_BYTES);                  // H5
		}
		w.varLong(Math.max(0L, c.startEpochSec()));               // H6
		w.varInt(Math.max(0, c.durationMs()));                    // H7
		w.u8(((c.result() & 0x3) | (c.endReason() << 2)) & 0xFF); // H8
		w.varInt(Math.max(0, c.pingMs()));                        // H9
		w.varInt(Math.max(0, c.threeBlockCm()));                  // H10
		w.varInt(Math.max(0, c.jumpMinMs()));                     // H11
		w.varInt(Math.max(0, c.jumpMaxMs()));                     // H12
		w.varInt(Math.max(0, c.jumpAttemptMs()));                 // H13
		w.varInt(Math.max(0, c.comboTimeoutMs()));                // H14
		w.varInt(Math.max(0, c.advantageWindowMs()));             // H15

		int summaryCount = SUMMARY_FIELDS + (c.damage() ? DAMAGE_FIELDS : 0);
		for (int i = 0; i < summaryCount; i++) {                  // S1..S44
			if (i == SIGNED_SUMMARY_INDEX) {
				w.zig(c.summary()[i]);
			} else {
				w.varInt(Math.max(0, c.summary()[i]));
			}
		}

		w.varInt(c.events().size());                              // E1
		w.varInt(events.size());                                  // E2
		long prevQt = 0;
		long prevSx = 0;
		long prevSz = 0;
		int lastOpp = c.opponents().size() - 1;
		for (Event e : events) {
			long qt = Math.max(0L, Math.round(e.t() / 10.0));
			if (qt < prevQt) {
				throw new IllegalArgumentException("Events must be in stream order (t " + e.t() + " after " + prevQt * 10 + ")");
			}
			int kind = e.kind() & 0x7;
			int arg = e.arg() & 0x7;
			int tag = kind | (arg << 3) | (kind == KIND_HIT && e.noReg() ? TAG_NO_REG : 0);
			w.varInt((int) Math.min(Integer.MAX_VALUE, qt - prevQt)); // dtCs
			w.u8(tag);
			prevQt = qt;
			if (kind > KIND_DEATH) {
				continue; // reserved: a v1 reader stops at the tag
			}
			if (multi && carriesOpponent(kind, arg)) {
				w.u8(Math.max(0, Math.min(lastOpp, e.opp())));
			}
			switch (kind) {
				case KIND_HIT, KIND_MISS -> {
					w.u8(reachQ(e.reach()));
					w.i8(aimQ(e.aimH()));
					w.i8(aimQ(e.aimV()));
				}
				case KIND_TAKEN -> w.u8(reachQ(e.reach()));
				case KIND_JUMP -> w.zig(e.deltaMs());
				default -> {
					// DEATH: nothing beyond the tag
				}
			}
			if (o.positions() && kind <= KIND_TAKEN) {
				long qsx = Math.round(finite(e.sx(), 0.0) * 10.0);
				long qsz = Math.round(finite(e.sz(), 0.0) * 10.0);
				w.zig(qsx - prevSx);
				w.zig(qsz - prevSz);
				prevSx = qsx;
				prevSz = qsz;
				double dx = finite(e.ox(), 0.0) - finite(e.sx(), 0.0);
				double dz = finite(e.oz(), 0.0) - finite(e.sz(), 0.0);
				w.u8(bearing(dx, dz));
				w.varInt(distDm(dx, dz));
			}
		}
		return w.toByteArray();
	}

	/**
	 * {@code clamp(floor((reach + 1e-6)·40), 0, 255)}: 1/40-block steps, <b>rounded down</b> with
	 * {@link HitGrader#EPSILON} (2.95 → 118, 2.9499 → 117). Like {@code StatFormat.reach}, a decoded
	 * reach never overstates the real one, so a viewer showing 2.95 or more always means a 3-block-eligible
	 * reach (with rounding to nearest, every hit from 2.9375 to 2.95 came back as 2.95).
	 */
	public static int reachQ(double reach) {
		long q = (long) Math.floor((finite(reach, 0.0) + HitGrader.EPSILON) * 40.0);
		return (int) Math.max(0L, Math.min(255L, q));
	}

	/** {@code max(0, floor((blocks + 1e-6)·100))}: a reach in whole centimetres, rounded down like {@link #reachQ}. */
	public static int reachCm(double blocks) {
		return nonNegative((long) Math.floor((finite(blocks, 0.0) + HitGrader.EPSILON) * 100.0));
	}

	/**
	 * {@link #reachCm}, except that a reach over its attack range {@code limit} is at least one centimetre
	 * above {@code reachCm(limit)} (S10 when the longest miss was over reach), like
	 * {@code StatFormat.reachVsRange}: the viewer never shows an over-reach figure at or inside the range.
	 */
	public static int reachCmVsRange(double blocks, double limit) {
		int cm = reachCm(blocks);
		double b = finite(blocks, 0.0);
		if (Double.isFinite(limit) && limit > 0.0 && b > limit) {
			cm = Math.max(cm, reachCm(limit) + 1);
		}
		return cm;
	}

	/**
	 * The reach an over-reach MISS event carries: the real reach, raised to one 1/40 step above
	 * {@code reachQ(limit)} when its rounded-down step would not exceed the range, so the viewer (which shows
	 * {@code floor(q·2.5)} cm) never draws an over-reach miss at or inside the range (a 3.004-block miss
	 * against 3.00 reads 3.02, not 3.00).
	 */
	static double overReachShown(double reach, double limit) {
		int q = reachQ(limit) + 1;
		return reachQ(reach) >= q ? reach : q / 40.0;
	}

	/** {@code clamp(round(offset·100), -127, 127)}. */
	public static int aimQ(double offset) {
		long q = Math.round(finite(offset, 0.0) * 100.0);
		return (int) Math.max(-127L, Math.min(127L, q));
	}

	/** {@code round(atan2(dz, dx)/(2π)·256) mod 256}. */
	public static int bearing(double dx, double dz) {
		long b = Math.round(Math.atan2(dz, dx) / (2.0 * Math.PI) * 256.0);
		return (int) Math.floorMod(b, 256L);
	}

	/** {@code clamp(round(hypot(dx, dz)·10), 0, 2000)}. */
	public static int distDm(double dx, double dz) {
		long d = Math.round(Math.hypot(dx, dz) * 10.0);
		return (int) Math.max(0L, Math.min(MAX_DIST_DM, d));
	}

	// ---- decode ---------------------------------------------------------------

	/**
	 * The reference decoder (§9.6). Stops at a reserved kind (5–7) and reports {@code truncated};
	 * ignores reserved flag and tag bits; ignores trailing bytes.
	 *
	 * <p>Rejected as malformed, exactly as the viewer's {@code KwDecoder.decode} rejects them: a varInt
	 * above 2^31 − 1, a varint longer than 10 bytes or past 64 bits, a start time above
	 * {@link #MAX_START_EPOCH_SEC}, a MULTI opponent count outside 1–3, an event time past 2^31 − 1
	 * centiseconds, and a zigzag field (S38, a jump delta, a position delta) or a running position outside
	 * the int range.
	 *
	 * @throws IllegalArgumentException for an unknown version or malformed bytes
	 */
	public static Decoded decode(byte[] payload) {
		Objects.requireNonNull(payload, "payload");
		Reader r = new Reader(payload);
		int version = r.u8();
		if (version != VERSION) {
			throw new IllegalArgumentException("Unsupported share payload version " + version);
		}
		int flags = r.u8();
		boolean positions = (flags & FLAG_POSITIONS) != 0;
		boolean damage = (flags & FLAG_DAMAGE) != 0;
		boolean multi = (flags & FLAG_MULTI) != 0;
		String player = r.str();
		List<String> opponents = new ArrayList<>(MAX_OPPONENTS);
		opponents.add(r.str());
		if (multi) {
			int extra = r.u8();
			if (extra < 1 || extra > MAX_OPPONENTS - 1) {
				throw new IllegalArgumentException("Bad opponent count " + extra);
			}
			for (int i = 0; i < extra; i++) {
				opponents.add(r.str());
			}
		}
		String server = (flags & FLAG_SERVER) != 0 ? r.str() : null;
		long startEpochSec = r.varLong();
		if (startEpochSec < 0L || startEpochSec > MAX_START_EPOCH_SEC) {
			throw new IllegalArgumentException("Start time out of range: " + Long.toUnsignedString(startEpochSec));
		}
		int durationMs = r.varInt();
		int resultReason = r.u8();
		int pingMs = r.varInt();
		int threeBlockCm = r.varInt();
		int jumpMinMs = r.varInt();
		int jumpMaxMs = r.varInt();
		int jumpAttemptMs = r.varInt();
		int comboTimeoutMs = r.varInt();
		int advantageWindowMs = r.varInt();
		int[] summary = new int[SUMMARY_FIELDS + (damage ? DAMAGE_FIELDS : 0)];
		for (int i = 0; i < summary.length; i++) {
			summary[i] = i == SIGNED_SUMMARY_INDEX ? r.zigInt() : r.varInt();
		}
		int totalEvents = r.varInt();
		int count = r.varInt();
		List<Decoded.Ev> events = new ArrayList<>(Math.min(count, 4096));
		boolean truncated = false;
		long tCs = 0;
		int sx = 0;
		int sz = 0;
		for (int i = 0; i < count; i++) {
			tCs += r.varInt();
			if (tCs > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("Event time overflow");
			}
			int tag = r.u8();
			int kind = tag & 0x7;
			int arg = (tag >>> 3) & 0x7;
			if (kind > KIND_DEATH) {
				truncated = true;
				break;
			}
			boolean noReg = kind == KIND_HIT && (tag & TAG_NO_REG) != 0;
			int opp = multi && carriesOpponent(kind, arg) ? r.u8() : 0;
			int reachQ = -1;
			int aimH = 0;
			int aimV = 0;
			int deltaMs = 0;
			switch (kind) {
				case KIND_HIT, KIND_MISS -> {
					reachQ = r.u8();
					aimH = r.i8();
					aimV = r.i8();
				}
				case KIND_TAKEN -> reachQ = r.u8();
				case KIND_JUMP -> deltaMs = r.zigInt();
				default -> {
					// DEATH
				}
			}
			boolean hasPos = false;
			int bearing = 0;
			int distDm = 0;
			int px = 0;
			int pz = 0;
			if (positions && kind <= KIND_TAKEN) {
				sx = addPosition(sx, r.zigInt());
				sz = addPosition(sz, r.zigInt());
				bearing = r.u8();
				distDm = r.varInt();
				hasPos = true;
				px = sx;
				pz = sz;
			}
			events.add(new Decoded.Ev((int) tCs, kind, arg, noReg, opp, reachQ, aimH, aimV, deltaMs,
					hasPos, px, pz, bearing, distDm));
		}
		return new Decoded(version, flags, player, List.copyOf(opponents), server, startEpochSec, durationMs,
				resultReason & 0x3, resultReason >>> 2, pingMs, threeBlockCm, jumpMinMs, jumpMaxMs, jumpAttemptMs,
				comboTimeoutMs, advantageWindowMs, summary, totalEvents, List.copyOf(events), truncated);
	}

	/** A running decimetre position plus a delta; malformed (not an {@link ArithmeticException}) past the int range. */
	private static int addPosition(int position, int delta) {
		long sum = (long) position + delta;
		if (sum < Integer.MIN_VALUE || sum > Integer.MAX_VALUE) {
			throw new IllegalArgumentException("Position out of range");
		}
		return (int) sum;
	}

	// ---- transport (§9.1) -----------------------------------------------------

	/** {@code base64url_nopad(deflateRaw(payload))}: the link fragment, without {@code #}. */
	public static String fragment(byte[] payload) {
		return base64Url(deflateRaw(payload));
	}

	/**
	 * The payload bytes of a link fragment (a leading {@code #} is accepted).
	 *
	 * @throws IllegalArgumentException if it is not valid base64url or not a raw deflate stream
	 */
	public static byte[] payloadOf(String fragment) {
		Objects.requireNonNull(fragment, "fragment");
		String f = fragment.trim();
		if (f.startsWith("#")) {
			f = f.substring(1);
		}
		return inflateRaw(fromBase64Url(f));
	}

	/** Raw deflate at {@code Deflater.BEST_COMPRESSION} (no zlib header), as the viewer's {@code deflate-raw} expects. */
	public static byte[] deflateRaw(byte[] raw) {
		Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION, true);
		try {
			deflater.setInput(raw);
			deflater.finish();
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length));
			byte[] chunk = new byte[2048];
			while (!deflater.finished()) {
				int n = deflater.deflate(chunk);
				out.write(chunk, 0, n);
			}
			return out.toByteArray();
		} finally {
			deflater.end();
		}
	}

	/**
	 * Inverse of {@link #deflateRaw(byte[])}.
	 *
	 * @throws IllegalArgumentException for a corrupt or truncated stream, or one that inflates past
	 *                                  {@link #MAX_PAYLOAD_BYTES}
	 */
	public static byte[] inflateRaw(byte[] data) {
		Inflater inflater = new Inflater(true);
		try {
			// A trailing dummy byte, as Inflater's documentation asks for in nowrap mode.
			byte[] input = Arrays.copyOf(data, data.length + 1);
			inflater.setInput(input);
			ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 2));
			byte[] chunk = new byte[2048];
			while (!inflater.finished()) {
				int n = inflater.inflate(chunk);
				if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
					throw new IllegalArgumentException("Truncated deflate stream");
				}
				out.write(chunk, 0, n);
				if (out.size() > MAX_PAYLOAD_BYTES) {
					throw new IllegalArgumentException("Payload too large");
				}
			}
			return out.toByteArray();
		} catch (DataFormatException e) {
			throw new IllegalArgumentException("Corrupt deflate stream", e);
		} finally {
			inflater.end();
		}
	}

	/** Unpadded base64url. */
	public static String base64Url(byte[] bytes) {
		return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
	}

	/** Decodes base64url with or without padding. @throws IllegalArgumentException on bad characters */
	public static byte[] fromBase64Url(String text) {
		return Base64.getUrlDecoder().decode(text);
	}

	// ---- helpers --------------------------------------------------------------

	/**
	 * True for a host that is an IP literal and therefore never shared: anything containing {@code :},
	 * {@code [} or {@code ]} (IPv6, or a stray port), and anything made only of digits and dots (a
	 * dotted quad and the shorthand forms such as {@code 127.1}).
	 */
	public static boolean isIpLiteral(@Nullable String host) {
		if (host == null) {
			return false;
		}
		String h = host.trim();
		if (h.isEmpty()) {
			return false;
		}
		if (h.indexOf(':') >= 0 || h.indexOf('[') >= 0 || h.indexOf(']') >= 0) {
			return true;
		}
		boolean digit = false;
		for (int i = 0; i < h.length(); i++) {
			char c = h.charAt(i);
			if (c >= '0' && c <= '9') {
				digit = true;
			} else if (c != '.') {
				return false;
			}
		}
		return digit;
	}

	/** The server label as it may be shared: null when {@code !include}, blank, or an IP literal; else trimmed. */
	public static @Nullable String shareableServer(@Nullable String server, boolean include) {
		if (!include || server == null) {
			return null;
		}
		String s = server.trim();
		if (s.isEmpty() || isIpLiteral(s)) {
			return null;
		}
		return s;
	}

	/** UTF-8 bytes of {@code s}, cut to at most {@code capBytes} on a code-point boundary. Null is "". */
	public static byte[] utf8Capped(@Nullable String s, int capBytes) {
		if (s == null || s.isEmpty() || capBytes <= 0) {
			return new byte[0];
		}
		byte[] all = s.getBytes(StandardCharsets.UTF_8);
		if (all.length <= capBytes) {
			return all;
		}
		int bytes = 0;
		int end = 0;
		while (end < s.length()) {
			int cp = s.codePointAt(end);
			int len = utf8Length(cp);
			if (bytes + len > capBytes) {
				break;
			}
			bytes += len;
			end += Character.charCount(cp);
		}
		return s.substring(0, end).getBytes(StandardCharsets.UTF_8);
	}

	private static int utf8Length(int cp) {
		if (cp < 0x80) {
			return 1;
		}
		if (cp < 0x800) {
			return 2;
		}
		if (cp >= Character.MIN_SURROGATE && cp <= Character.MAX_SURROGATE) {
			return 1; // a lone surrogate is encoded as '?'
		}
		return cp < 0x10000 ? 3 : 4;
	}

	private static boolean carriesOpponent(int kind, int arg) {
		return kind == KIND_HIT || kind == KIND_MISS || kind == KIND_TAKEN || (kind == KIND_DEATH && (arg & 1) == 0);
	}

	/** The primary opponent's record index: the record's own choice by UUID, else the §4.9 rule; -1 with no opponents. */
	private static int primaryIndex(FightRecord r, boolean[] engaged, int[] authoritative) {
		int n = engaged.length;
		if (n == 0) {
			return -1;
		}
		if (r.opponentUuid != null && r.opponentUuids != null) {
			int i = r.opponentUuids.indexOf(r.opponentUuid);
			if (i >= 0 && i < n) {
				return i;
			}
		}
		int best = -1;
		for (int i = 0; i < n; i++) {
			if (engaged[i] && (best < 0 || authoritative[i] > authoritative[best])) {
				best = i;
			}
		}
		return best < 0 ? 0 : best;
	}

	private static HitQuality qualityOf(FightRecord.Swing sw, FightRecord.Settings st) {
		if (sw.quality != null) {
			try {
				return HitQuality.valueOf(sw.quality);
			} catch (IllegalArgumentException ignored) {
				// regrade below
			}
		}
		return HitGrader.grade(sw.charged, sw.crit, sw.sprint, finite(sw.reach, 0.0), st.threeBlockReach, sw.customRange);
	}

	private static FightResult parseResult(@Nullable String s) {
		if (s != null) {
			try {
				return FightResult.valueOf(s);
			} catch (IllegalArgumentException ignored) {
				// fall through
			}
		}
		return FightResult.DRAW;
	}

	private static FightEndReason parseReason(@Nullable String s) {
		if (s != null) {
			try {
				return FightEndReason.valueOf(s);
			} catch (IllegalArgumentException ignored) {
				// fall through
			}
		}
		return FightEndReason.IDLE;
	}

	private static int rounded(double v) {
		return nonNegative(Math.round(finite(v, 0.0)));
	}

	private static int signedRounded(double v) {
		long r = Math.round(finite(v, 0.0));
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, r));
	}

	private static int nonNegative(long v) {
		return (int) Math.max(0L, Math.min(Integer.MAX_VALUE, v));
	}

	private static double finite(double v, double fallback) {
		return Double.isFinite(v) ? v : fallback;
	}

	/** Sort key of one stream event: exact ms, kind rank, record order. */
	private record Keyed(int t, int rank, int seq, Event event) {
	}

	// ---- primitives (§9.2) ----------------------------------------------------

	/**
	 * Byte writer for the primitives of §9.2. {@code varInt}/{@code varLong} are unsigned LEB128 and
	 * reject negative values (the JS reader would see a huge number); {@code zig} is zigzag then
	 * LEB128; {@code str} is a varInt byte length plus UTF-8, cut on a code-point boundary.
	 */
	public static final class Writer {
		private final ByteArrayOutputStream out;

		public Writer() {
			this(64);
		}

		public Writer(int initialCapacity) {
			this.out = new ByteArrayOutputStream(Math.max(16, initialCapacity));
		}

		/** One byte 0–255. */
		public Writer u8(int v) {
			if (v < 0 || v > 0xFF) {
				throw new IllegalArgumentException("u8 out of range: " + v);
			}
			this.out.write(v);
			return this;
		}

		/** One byte, two's complement, -128–127. */
		public Writer i8(int v) {
			if (v < -128 || v > 127) {
				throw new IllegalArgumentException("i8 out of range: " + v);
			}
			this.out.write(v & 0xFF);
			return this;
		}

		/** Unsigned LEB128 of a non-negative int. */
		public Writer varInt(int v) {
			if (v < 0) {
				throw new IllegalArgumentException("varInt is unsigned: " + v);
			}
			unsigned(v);
			return this;
		}

		/** Unsigned LEB128 of a non-negative long. */
		public Writer varLong(long v) {
			if (v < 0) {
				throw new IllegalArgumentException("varLong is unsigned: " + v);
			}
			unsigned(v);
			return this;
		}

		/** Zigzag {@code (n << 1) ^ (n >> 63)}, then LEB128. */
		public Writer zig(long n) {
			unsigned((n << 1) ^ (n >> 63));
			return this;
		}

		/** varInt byte length, then the UTF-8 bytes cut to {@code capBytes} on a code-point boundary. */
		public Writer str(@Nullable String s, int capBytes) {
			byte[] raw = utf8Capped(s, capBytes);
			varInt(raw.length);
			this.out.write(raw, 0, raw.length);
			return this;
		}

		/** Bytes written so far. */
		public int size() {
			return this.out.size();
		}

		public byte[] toByteArray() {
			return this.out.toByteArray();
		}

		private void unsigned(long v) {
			long x = v;
			while ((x & ~0x7FL) != 0L) {
				this.out.write((int) ((x & 0x7FL) | 0x80L));
				x >>>= 7;
			}
			this.out.write((int) x);
		}
	}

	/** Byte reader mirroring {@link Writer}. Every read past the end throws {@link IllegalArgumentException}. */
	public static final class Reader {
		private final byte[] bytes;
		private int pos;

		public Reader(byte[] bytes) {
			this.bytes = Objects.requireNonNull(bytes, "bytes");
		}

		public int u8() {
			need(1);
			return this.bytes[this.pos++] & 0xFF;
		}

		public int i8() {
			need(1);
			return this.bytes[this.pos++];
		}

		/**
		 * Unsigned LEB128, at most 10 bytes and 64 bits: a 10th byte above 1 would silently drop bits, so
		 * it is rejected (the JS reader, which cannot drop them, rejects it too).
		 */
		public long varLong() {
			long v = 0L;
			int shift = 0;
			while (true) {
				int b = u8();
				if (shift == 63 && (b & 0x7F) > 1) {
					throw new IllegalArgumentException("varint wider than 64 bits");
				}
				v |= (long) (b & 0x7F) << shift;
				if ((b & 0x80) == 0) {
					return v;
				}
				shift += 7;
				if (shift >= 64) {
					throw new IllegalArgumentException("varint longer than 10 bytes");
				}
			}
		}

		/** Unsigned LEB128 that must fit a non-negative int. */
		public int varInt() {
			long v = varLong();
			if (v < 0L || v > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("varInt out of range: " + Long.toUnsignedString(v));
			}
			return (int) v;
		}

		/** Zigzag-decoded LEB128. */
		public long zig() {
			long v = varLong();
			return (v >>> 1) ^ -(v & 1L);
		}

		/** {@link #zig()} that must fit an int. */
		public int zigInt() {
			long v = zig();
			if (v < Integer.MIN_VALUE || v > Integer.MAX_VALUE) {
				throw new IllegalArgumentException("zig out of range: " + v);
			}
			return (int) v;
		}

		/** varInt byte length, then UTF-8. */
		public String str() {
			int len = varInt();
			need(len);
			String s = new String(this.bytes, this.pos, len, StandardCharsets.UTF_8);
			this.pos += len;
			return s;
		}

		/** Bytes consumed so far. */
		public int position() {
			return this.pos;
		}

		/** Bytes left. */
		public int remaining() {
			return this.bytes.length - this.pos;
		}

		private void need(int n) {
			if (n < 0 || this.bytes.length - this.pos < n) {
				throw new IllegalArgumentException("Truncated payload");
			}
		}
	}
}
