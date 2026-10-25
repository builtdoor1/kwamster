package kwamster;

import kwamster.core.model.AimZone;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.HitQuality;
import kwamster.core.model.PlayerRef;
import kwamster.core.stats.AimZones;
import kwamster.core.stats.HitGrader;
import kwamster.core.stats.StatsCalculator;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Deterministic synthetic fights shared by the unit tests (DESIGN.md §12.2). Minecraft-free.
 *
 * <p>Two kinds of fixture live here:
 * <ul>
 *   <li>{@link #script()}: a small builder for hand-written records whose every number the tests
 *       compute by hand.</li>
 *   <li>Generated fights ({@link #example()}, {@link #worked()}, {@link #sixtySeconds()},
 *       {@link #fiveMinutes()}, {@link #multi(int)}): realistic jittered records from a fixed seed. Only
 *       {@link Random} (a specified LCG) and {@link StrictMath} are used, so every JVM produces the same
 *       records, which keeps the {@code example} golden vector stable across machines.</li>
 * </ul>
 */
public final class SampleFights {
	/** The recording player. */
	public static final PlayerRef SELF = ref("builtdoor");
	/** The usual opponent. */
	public static final PlayerRef KWAMIX = ref("Kwamix");
	/** 2026-10-20T15:30:12.123Z, so {@code FightRecord.baseId} gives {@code 20261020-153012-123-Kwamix}. */
	public static final long START_EPOCH_MS = 1_792_510_212_123L;
	/** The mod version written into generated records. */
	public static final String MOD_VERSION = "1.0.0+1.21.11";
	/** The server label of the example fight. */
	public static final String EXAMPLE_SERVER = "duels.example.net";

	private SampleFights() {
	}

	/** A stable player ref: a name-based (version 3) UUID, so it never looks like a server NPC. */
	public static PlayerRef ref(String name) {
		return new PlayerRef(UUID.nameUUIDFromBytes(("kwamster-test:" + name).getBytes(StandardCharsets.UTF_8)), name);
	}

	/** A hand-written record against Kwamix (engaged), default settings snapshot, nothing in it yet. */
	public static Script script() {
		return new Script();
	}

	// ---- hand-written records ---------------------------------------------------------------

	/**
	 * Builder for small hand-written records. Every event is added at an explicit relative time
	 * {@code t}; {@link #build()} sorts each list by {@code t} (stable, so equal times keep insertion
	 * order). Landed hits get a zone from their aim offsets and a quality graded with the record's
	 * settings snapshot unless the tweak already set one.
	 */
	public static final class Script {
		private final FightRecord r = new FightRecord();
		private int nextOpp;

		Script() {
			this.r.id = FightRecord.baseId(START_EPOCH_MS, KWAMIX.name());
			this.r.modVersion = MOD_VERSION;
			this.r.startEpochMs = START_EPOCH_MS;
			this.r.playerName = SELF.name();
			this.r.playerUuid = SELF.uuid().toString();
			this.r.settings = FightRecord.Settings.defaults();
			this.r.result = FightResult.DRAW.name();
			this.r.endReason = FightEndReason.IDLE.name();
			opponent(KWAMIX, true);
		}

		/** Adds an opponent (record index = order of addition; Kwamix is 0). */
		public Script opponent(PlayerRef p, boolean engaged) {
			this.r.opponents.add(p.name());
			this.r.opponentUuids.add(p.uuid().toString());
			this.r.engaged.add(engaged);
			if (this.nextOpp == 0) {
				this.r.opponentName = p.name();
				this.r.opponentUuid = p.uuid().toString();
			}
			this.nextOpp++;
			return this;
		}

		/** Edits the settings snapshot. */
		public Script settings(Consumer<FightRecord.Settings> edit) {
			edit.accept(this.r.settings);
			return this;
		}

		/** A landed, charged, plain hit on opponent 0 (aim centre). */
		public Script hit(int t, double reach) {
			return hit(t, reach, s -> {
			});
		}

		/** A landed hit; {@code tweak} runs before the zone and quality are filled in. */
		public Script hit(int t, double reach, Consumer<FightRecord.Swing> tweak) {
			FightRecord.Swing s = swing(t, true, reach);
			s.charged = true;
			s.charge = 1f;
			s.confirmed = false;
			tweak.accept(s);
			if (s.zone == null) {
				s.zone = AimZones.of(s.aimH, s.aimV).name();
			}
			if (s.quality == null) {
				s.quality = HitGrader.grade(s.charged, s.crit, s.sprint, s.reach, this.r.settings.threeBlockReach, s.customRange).name();
			}
			this.r.swings.add(s);
			return this;
		}

		/** A missed swing at opponent 0 (vanilla range limit 3.0). */
		public Script miss(int t, double reach) {
			return miss(t, reach, s -> {
			});
		}

		/** A missed swing; {@code tweak} may change anything. */
		public Script miss(int t, double reach, Consumer<FightRecord.Swing> tweak) {
			FightRecord.Swing s = swing(t, false, reach);
			tweak.accept(s);
			this.r.swings.add(s);
			return this;
		}

		/** A melee hit taken from opponent 0. */
		public Script taken(int t, boolean onGround) {
			return taken(t, onGround, h -> {
			});
		}

		/** A melee hit taken; {@code tweak} may change anything. */
		public Script taken(int t, boolean onGround, Consumer<FightRecord.HitTaken> tweak) {
			FightRecord.HitTaken h = new FightRecord.HitTaken();
			h.t = t;
			h.opp = 0;
			h.reach = 2.8f;
			h.onGround = onGround;
			h.sx = 0f;
			h.sz = 0f;
			h.ox = 0f;
			h.oz = 3f;
			tweak.accept(h);
			this.r.hitsTaken.add(h);
			return this;
		}

		/** A jump attempt with {@code deltaMs = 50·(deltaTicks − 1)} and the live success rule of the snapshot. */
		public Script jump(int t, int deltaTicks) {
			int deltaMs = 50 * (deltaTicks - 1);
			FightRecord.Settings st = this.r.settings;
			return jump(t, deltaTicks, deltaMs, deltaTicks >= 1 && deltaMs >= st.jumpMinMs && deltaMs <= st.jumpMaxMs);
		}

		/** A jump attempt with an explicit stored {@code success} flag (which StatsCalculator must ignore). */
		public Script jump(int t, int deltaTicks, int deltaMs, boolean storedSuccess) {
			FightRecord.JumpAttempt j = new FightRecord.JumpAttempt();
			j.t = t;
			j.deltaTicks = deltaTicks;
			j.deltaMs = deltaMs;
			j.wallMs = deltaTicks * 50;
			j.success = storedSuccess;
			this.r.jumps.add(j);
			return this;
		}

		/** A trade with opponent 0. */
		public Script trade(int t, double dealt, double taken, boolean damageKnown, double momentumPct, boolean momentumKnown) {
			FightRecord.Trade x = new FightRecord.Trade();
			x.t = t;
			x.opp = 0;
			x.dealt = (float) dealt;
			x.taken = (float) taken;
			x.damageKnown = damageKnown;
			x.momentumPct = (float) momentumPct;
			x.momentumKnown = momentumKnown;
			this.r.trades.add(x);
			return this;
		}

		/** A health drop: {@code self} (opp −1) or opponent {@code opp}; marks opponent health visible for the latter. */
		public Script drop(int t, boolean self, int opp, double amount) {
			FightRecord.DamageDrop d = new FightRecord.DamageDrop();
			d.t = t;
			d.self = self;
			d.opp = self ? -1 : opp;
			d.amount = (float) amount;
			this.r.damage.add(d);
			if (!self) {
				this.r.opponentHealthVisible = true;
			}
			return this;
		}

		/** Sets {@code durationMs}. */
		public Script duration(int ms) {
			this.r.durationMs = ms;
			return this;
		}

		/** Sets result and end reason. */
		public Script result(FightResult result, FightEndReason reason) {
			this.r.result = result.name();
			this.r.endReason = reason.name();
			return this;
		}

		/** Any other edit of the record. */
		public Script with(Consumer<FightRecord> edit) {
			edit.accept(this.r);
			return this;
		}

		/** The record with every list sorted by {@code t} (stable); {@code stats} left null. */
		public FightRecord build() {
			this.r.swings.sort(Comparator.comparingInt(s -> s.t));
			this.r.hitsTaken.sort(Comparator.comparingInt(h -> h.t));
			this.r.jumps.sort(Comparator.comparingInt(j -> j.t));
			this.r.trades.sort(Comparator.comparingInt(x -> x.t));
			this.r.damage.sort(Comparator.comparingInt(d -> d.t));
			return this.r;
		}

		/** {@link #build()} plus {@code stats = StatsCalculator.compute(record, FINAL)}. */
		public FightRecord finish() {
			FightRecord rec = build();
			rec.stats = StatsCalculator.compute(rec, StatsCalculator.FINAL);
			return rec;
		}

		private FightRecord.Swing swing(int t, boolean landed, double reach) {
			FightRecord.Swing s = new FightRecord.Swing();
			s.t = t;
			s.opp = 0;
			s.landed = landed;
			s.reach = (float) reach;
			s.rayReach = landed ? (float) reach : -1f;
			s.rangeLimit = 3.0f;
			s.onGround = true;
			s.sx = 0f;
			s.sz = 0f;
			s.ox = 0f;
			s.oz = 3f;
			return s;
		}
	}

	// ---- generated records ------------------------------------------------------------------

	/**
	 * Shape of a generated fight.
	 *
	 * @param seed       RNG seed
	 * @param durationMs fight length; events are spread over it
	 * @param swings     committed swings
	 * @param landed     how many of them landed
	 * @param taken      melee hits taken
	 * @param jumps      jump-reset attempts
	 * @param trades     trades (summary only)
	 * @param result     WIN or LOSS add a DEATH event to the share stream
	 * @param reason     end reason
	 * @param damage     whether opponent health was visible (damage drops are generated)
	 * @param server     server label
	 */
	public record Spec(long seed, int durationMs, int swings, int landed, int taken, int jumps, int trades,
			FightResult result, FightEndReason reason, boolean damage, String server) {
	}

	/**
	 * The example fight embedded in the viewer (the {@code example} golden vector, §9.6): a 37.7 s WIN
	 * against Kwamix on {@value #EXAMPLE_SERVER}, positions and damage on, 105 stream events.
	 */
	public static FightRecord example() {
		return generate(new Spec(20261020L, 37_738, 70, 46, 24, 10, 6, FightResult.WIN, FightEndReason.OPPONENT_DIED,
				true, EXAMPLE_SERVER));
	}

	/**
	 * The worked synthetic fight of §9.4 / §12.2: 80 swings (55 landed), 40 hits taken and 12 jump
	 * attempts, a DRAW by idle time, so the share stream holds exactly 132 events.
	 */
	public static FightRecord worked() {
		return generate(new Spec(132L, 96_000, 80, 55, 40, 12, 8, FightResult.DRAW, FightEndReason.IDLE,
				true, "pvp.example.org"));
	}

	/** The first row of §9.4: 60 s, 40 swings (28 landed), 25 taken, 6 jump attempts, a WIN (72 stream events). */
	public static FightRecord sixtySeconds() {
		return generate(new Spec(60L, 60_000, 40, 28, 25, 6, 4, FightResult.WIN, FightEndReason.OPPONENT_DIED,
				true, "pvp.example.org"));
	}

	/** The 5 min test fight of §9.4: 330 swings (230 landed), 220 taken, 49 jumps and a WIN: 600 stream events. */
	public static FightRecord fiveMinutes() {
		return generate(new Spec(300L, 300_000, 330, 230, 220, 49, 30, FightResult.WIN, FightEndReason.OPPONENT_DIED,
				true, "pvp.example.org"));
	}

	/**
	 * A 1vN fight: Kwamix plus {@code engagedOthers} more engaged opponents (each with fewer
	 * authoritative events than the one before, so the payload table order is the record order), and
	 * one bystander who was only whiffed at (never engaged). DRAW, positions, no damage.
	 */
	public static FightRecord multi(int engagedOthers) {
		Random rng = new Random(4000L + engagedOthers);
		Script s = script().duration(20_000).with(r -> r.pingMs = 35).with(r -> r.server = "ffa.example.com");
		List<PlayerRef> people = new ArrayList<>();
		people.add(KWAMIX);
		for (int i = 1; i <= engagedOthers; i++) {
			PlayerRef p = ref("Rival" + i);
			people.add(p);
			s.opponent(p, true);
		}
		PlayerRef bystander = ref("Bystander");
		s.opponent(bystander, false);
		int bystanderIndex = people.size();
		int t = 100;
		for (int opp = 0; opp < people.size(); opp++) {
			int count = 2 * (people.size() - opp) + 1;
			for (int k = 0; k < count; k++) {
				final int o = opp;
				final double h = jitter(rng, 0.6);
				final double v = jitter(rng, 0.6);
				if (k % 3 == 2) {
					s.taken(t, rng.nextBoolean(), x -> {
						x.opp = o;
						x.reach = (float) (2.4 + rng.nextDouble() * 0.6);
					});
				} else {
					s.hit(t, 2.3 + rng.nextDouble() * 0.7, x -> {
						x.opp = o;
						x.aimH = (float) h;
						x.aimV = (float) v;
						x.ox = (float) (2.0 + o);
						x.oz = (float) (1.5 - o);
						x.sx = 0.1f * o;
						x.sz = -0.1f * o;
					});
				}
				t += 137 + rng.nextInt(120);
			}
		}
		for (int k = 0; k < 3; k++) {
			s.miss(t, 3.6 + k * 0.1, x -> {
				x.opp = bystanderIndex;
				x.aimH = 1.4f;
			});
			t += 211;
		}
		return s.finish();
	}

	/** Builds a jittered, deterministic record for {@code spec} (stats computed, ready to share). */
	public static FightRecord generate(Spec spec) {
		Random rng = new Random(spec.seed());
		FightRecord r = new FightRecord();
		r.modVersion = MOD_VERSION;
		r.startEpochMs = START_EPOCH_MS + spec.seed() % 1000L;
		r.durationMs = spec.durationMs();
		r.result = spec.result().name();
		r.endReason = spec.reason().name();
		r.playerName = SELF.name();
		r.playerUuid = SELF.uuid().toString();
		r.opponents.add(KWAMIX.name());
		r.opponentUuids.add(KWAMIX.uuid().toString());
		r.engaged.add(Boolean.TRUE);
		r.opponentName = KWAMIX.name();
		r.opponentUuid = KWAMIX.uuid().toString();
		r.server = spec.server();
		r.pingMs = 42;
		r.settings = FightRecord.Settings.defaults();
		r.id = FightRecord.baseId(r.startEpochMs, r.opponentName);

		// Event kinds in a shuffled order, then sorted times spread over the fight.
		int n = spec.swings() + spec.taken() + spec.jumps();
		List<Integer> kinds = new ArrayList<>(n);
		for (int i = 0; i < spec.landed(); i++) {
			kinds.add(0);
		}
		for (int i = spec.landed(); i < spec.swings(); i++) {
			kinds.add(1);
		}
		for (int i = 0; i < spec.taken(); i++) {
			kinds.add(2);
		}
		for (int i = 0; i < spec.jumps(); i++) {
			kinds.add(3);
		}
		for (int i = kinds.size() - 1; i > 0; i--) {
			int j = rng.nextInt(i + 1);
			Integer tmp = kinds.get(i);
			kinds.set(i, kinds.get(j));
			kinds.set(j, tmp);
		}
		int end = Math.max(1, spec.durationMs() - 40);
		int[] times = new int[n];
		for (int i = 0; i < n; i++) {
			times[i] = 20 + rng.nextInt(end);
		}
		Arrays.sort(times);

		double sx = 0.0;
		double sz = 0.0;
		double bearing = rng.nextDouble() * 2.0 * StrictMath.PI;
		FightRecord.Settings st = r.settings;
		for (int i = 0; i < n; i++) {
			int t = times[i];
			// Own position: a slow random walk; opponent 2.3-3.6 blocks away on a drifting bearing.
			sx += jitter(rng, 0.35);
			sz += jitter(rng, 0.35);
			bearing += jitter(rng, 0.45);
			double dist = 2.3 + rng.nextDouble() * 1.3;
			double ox = sx + dist * StrictMath.cos(bearing);
			double oz = sz + dist * StrictMath.sin(bearing);
			int kind = kinds.get(i);
			switch (kind) {
				case 0, 1 -> {
					boolean landed = kind == 0;
					FightRecord.Swing s = new FightRecord.Swing();
					s.t = t;
					s.opp = 0;
					s.landed = landed;
					s.clickTimed = rng.nextInt(10) < 7;
					s.clickLeadMs = s.clickTimed ? 4 + rng.nextInt(60) : 0;
					s.charged = rng.nextInt(100) < 85;
					s.charge = s.charged ? 1f : (float) (0.3 + rng.nextDouble() * 0.6);
					s.crit = s.charged && rng.nextInt(100) < 25;
					s.sprint = !s.crit && rng.nextInt(100) < 45;
					s.sweep = false;
					s.onGround = !s.crit;
					s.rangeLimit = 3.0f;
					if (landed) {
						s.reach = (float) clamp(2.72 + rng.nextGaussian() * 0.22, 1.6, 2.999);
						s.rayReach = (float) (s.reach + 0.05 + rng.nextDouble() * 0.2);
						s.aimH = (float) jitter(rng, 0.95);
						s.aimV = (float) jitter(rng, 0.95);
						s.intoImmunity = rng.nextInt(100) < 6;
						s.confirmed = !s.intoImmunity && rng.nextInt(100) < 85;
						AimZone zone = AimZones.of(s.aimH, s.aimV);
						s.zone = zone.name();
						HitQuality q = HitGrader.grade(s.charged, s.crit, s.sprint, s.reach, st.threeBlockReach, false);
						s.quality = q.name();
					} else {
						s.reach = (float) clamp(3.25 + rng.nextGaussian() * 0.45, 2.6, 5.5);
						s.rayReach = -1f;
						s.aimH = (float) jitter(rng, 1.8);
						s.aimV = (float) jitter(rng, 1.4);
					}
					s.sx = (float) sx;
					s.sz = (float) sz;
					s.ox = (float) ox;
					s.oz = (float) oz;
					s.yaw = (float) StrictMath.toDegrees(StrictMath.atan2(-(ox - sx), oz - sz));
					r.swings.add(s);
				}
				case 2 -> {
					FightRecord.HitTaken h = new FightRecord.HitTaken();
					h.t = t;
					h.opp = 0;
					h.reach = (float) clamp(2.75 + rng.nextGaussian() * 0.2, 1.8, 3.1);
					h.onGround = rng.nextInt(100) < 75;
					h.sprinting = rng.nextBoolean();
					h.sx = (float) sx;
					h.sz = (float) sz;
					h.ox = (float) ox;
					h.oz = (float) oz;
					r.hitsTaken.add(h);
				}
				default -> {
					FightRecord.JumpAttempt j = new FightRecord.JumpAttempt();
					int[] ticks = {-2, 0, 1, 1, 1, 2, 2, 3, 4, 5};
					j.t = t;
					j.deltaTicks = ticks[rng.nextInt(ticks.length)];
					j.deltaMs = 50 * (j.deltaTicks - 1);
					j.wallMs = j.deltaMs + 50 + rng.nextInt(30);
					j.success = j.deltaTicks >= 1 && j.deltaMs >= st.jumpMinMs && j.deltaMs <= st.jumpMaxMs;
					r.jumps.add(j);
				}
			}
		}

		for (int i = 0; i < spec.trades(); i++) {
			FightRecord.Trade x = new FightRecord.Trade();
			x.t = 50 + rng.nextInt(end);
			x.opp = 0;
			x.damageKnown = rng.nextInt(10) < 8;
			x.dealt = x.damageKnown ? (float) (1 + rng.nextInt(7)) : 0f;
			x.taken = x.damageKnown ? (float) (1 + rng.nextInt(7)) : 0f;
			x.momentumKnown = rng.nextInt(10) < 7;
			x.momentumPct = x.momentumKnown ? (float) StrictMath.round(jitter(rng, 80)) : 0f;
			r.trades.add(x);
		}
		r.trades.sort(Comparator.comparingInt(x -> x.t));

		if (spec.damage()) {
			r.opponentHealthVisible = true;
			for (FightRecord.Swing s : r.swings) {
				if (s.landed && !s.intoImmunity && rng.nextInt(10) < 8) {
					r.damage.add(drop(s.t + 30 + rng.nextInt(250), false, 0, 1 + rng.nextInt(6) * 0.5));
				}
			}
			for (FightRecord.HitTaken h : r.hitsTaken) {
				if (rng.nextInt(10) < 8) {
					r.damage.add(drop(h.t + 30 + rng.nextInt(250), true, -1, 1 + rng.nextInt(6) * 0.5));
				}
			}
			r.damage.sort(Comparator.comparingInt(d -> d.t));
		}
		r.stats = StatsCalculator.compute(r, StatsCalculator.FINAL);
		return r;
	}

	private static FightRecord.DamageDrop drop(int t, boolean self, int opp, double amount) {
		FightRecord.DamageDrop d = new FightRecord.DamageDrop();
		d.t = t;
		d.self = self;
		d.opp = self ? -1 : opp;
		d.amount = (float) amount;
		return d;
	}

	private static double jitter(Random rng, double amplitude) {
		return (rng.nextDouble() * 2.0 - 1.0) * amplitude;
	}

	private static double clamp(double v, double lo, double hi) {
		return Math.max(lo, Math.min(hi, v));
	}
}
