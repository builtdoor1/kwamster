package kwamster.core.model;

import kwamster.core.config.KwamsterConfig;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * A whole fight as it is saved and shared (Gson POJO, §4.6).
 *
 * <p>Every {@code t} is an int in milliseconds on the <b>fight clock</b> relative to the fight start
 * (singleplayer pauses excluded, §5.13). Only a jump just before the opening event can have a
 * negative {@code t}. Positions are blocks relative to the origin, which is the player's own
 * position at fight start; no absolute world coordinates are stored. Every float is finite (the
 * accumulator sanitises, §4.9). Lists are in ascending {@code t} order.
 *
 * <p>Built incrementally by {@code kwamster.core.fight.FightAccumulator}; every derived number comes
 * from {@code kwamster.core.stats.StatsCalculator}.
 */
public final class FightRecord {
	public int schema = 1;
	/** {@code yyyyMMdd-HHmmss-SSS-<Opp>} in UTC (§10). See {@link #baseId(long, String)}. */
	public String id;
	public String modVersion;
	public long startEpochMs;
	/** Fight-clock duration (§5.13). 0 while the fight is live. */
	public int durationMs;
	/** {@code FightResult.name()}. */
	public String result;
	/** {@code FightEndReason.name()}. */
	public String endReason;
	public String playerName, playerUuid;
	/** Primary opponent (§5.14). */
	public String opponentName, opponentUuid;
	/** Names as first seen; index = {@code opp}. Keyed by {@link #opponentUuids}. */
	public List<String> opponents = new ArrayList<>();
	public List<String> opponentUuids = new ArrayList<>();
	/** Parallel to {@link #opponents}: at least one authoritative event (§5.14). */
	public List<Boolean> engaged = new ArrayList<>();
	/** Host without port, "Singleplayer", or "". */
	public String server = "";
	/** Mean of the ping samples. */
	public int pingMs;
	public boolean opponentHealthVisible;
	/** Jump opportunities are derived (§5.1), never stored. */
	public int piercingSwings, projectileHitsTaken;
	/** Snapshot of the detection settings at fight open (§3.9). */
	public Settings settings = new Settings();
	public List<Swing> swings = new ArrayList<>();
	public List<HitTaken> hitsTaken = new ArrayList<>();
	public List<JumpAttempt> jumps = new ArrayList<>();
	public List<Trade> trades = new ArrayList<>();
	public List<DamageDrop> damage = new ArrayList<>();
	/** Filled when the fight is finalised. */
	public FightStats stats;

	private static final DateTimeFormatter ID_TIME =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

	/**
	 * The base fight id from §10: {@code yyyyMMdd-HHmmss-SSS} (UTC) from {@code startEpochMs}, a dash,
	 * and the opponent name reduced to {@code [A-Za-z0-9_]}, at most 16 characters ({@code unknown}
	 * when nothing is left). Collision suffixes ({@code -2}, {@code -3}...) are RECORD's job.
	 */
	public static String baseId(long startEpochMs, @Nullable String opponentName) {
		StringBuilder opp = new StringBuilder(16);
		if (opponentName != null) {
			for (int i = 0; i < opponentName.length() && opp.length() < 16; i++) {
				char c = opponentName.charAt(i);
				if ((c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
					opp.append(c);
				}
			}
		}
		if (opp.isEmpty()) {
			opp.append("unknown");
		}
		return ID_TIME.format(Instant.ofEpochMilli(startEpochMs)) + "-" + opp;
	}

	/** Detection settings copied from the config when the fight opened. Grading and every derived number use these. */
	public static final class Settings {
		public float threeBlockReach;
		public int jumpMinMs, jumpMaxMs, jumpAttemptMs, comboTimeoutMs, advantageWindowMs, intervalMaxGapMs;

		/** The snapshot of §3.9, taken from {@code cfg}. */
		public static Settings from(KwamsterConfig cfg) {
			Settings s = new Settings();
			s.threeBlockReach = cfg.feedback.threeBlockReach;
			s.jumpMinMs = cfg.advanced.jumpWindowMinMs;
			s.jumpMaxMs = cfg.advanced.jumpWindowMaxMs;
			s.jumpAttemptMs = cfg.advanced.jumpAttemptMs;
			s.comboTimeoutMs = cfg.advanced.comboTimeoutMs;
			s.advantageWindowMs = cfg.advanced.advantageWindowMs;
			s.intervalMaxGapMs = cfg.advanced.intervalMaxGapMs;
			return s;
		}

		/** The snapshot a default config would produce. */
		public static Settings defaults() {
			return from(new KwamsterConfig());
		}

		public Settings copy() {
			Settings s = new Settings();
			s.threeBlockReach = this.threeBlockReach;
			s.jumpMinMs = this.jumpMinMs;
			s.jumpMaxMs = this.jumpMaxMs;
			s.jumpAttemptMs = this.jumpAttemptMs;
			s.comboTimeoutMs = this.comboTimeoutMs;
			s.advantageWindowMs = this.advantageWindowMs;
			s.intervalMaxGapMs = this.intervalMaxGapMs;
			return s;
		}
	}

	/** One committed swing at a player, landed or missed. */
	public static final class Swing {
		public int t;
		public int opp;
		public boolean landed;
		/** Hook time minus click press; 0 when {@link #clickTimed} is false. */
		public int clickLeadMs;
		/** NO-REG (landed only). */
		public boolean intoImmunity;
		/** The held item had an ATTACK_RANGE component (never graded as a 3-block hit). */
		public boolean customRange;
		/** {@code rayReach} is -1 when not available. */
		public float reach, rayReach, rangeLimit;
		public float aimH, aimV;
		/** {@code AimZone.name()}; null for misses. */
		public String zone;
		/** {@code HitQuality.name()}; null for misses. */
		public String quality;
		public float charge;
		public boolean charged, crit, sprint, sweep, onGround, clickTimed, confirmed;
		public float sx, sz, ox, oz, yaw;
	}

	/** A melee hit taken. */
	public static final class HitTaken {
		public int t;
		public int opp;
		public float reach;
		public boolean onGround, sprinting;
		public float sx, sz, ox, oz;
	}

	/** A decided jump-reset attempt (§5.1). */
	public static final class JumpAttempt {
		/** Jump time. */
		public int t;
		/** jumpTick - hitTick (≤ 0 = early). */
		public int deltaTicks;
		/** Game time 50·(deltaTicks - 1). */
		public int deltaMs;
		/** Wall clock, informative only. */
		public int wallMs;
		/** As decided live; StatsCalculator recomputes it from deltaMs and the settings snapshot. */
		public boolean success;
	}

	/** A trade: one hit dealt and one melee hit taken between the same two players (§5.10). */
	public static final class Trade {
		public int t;
		public int opp;
		public float dealt, taken, momentumPct;
		public boolean damageKnown, momentumKnown;
	}

	/** An observed health drop. {@code opp} is -1 when {@code self}. */
	public static final class DamageDrop {
		public int t;
		public boolean self;
		public int opp;
		public float amount;
	}
}
