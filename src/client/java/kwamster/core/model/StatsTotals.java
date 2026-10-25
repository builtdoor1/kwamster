package kwamster.core.model;

import org.jspecify.annotations.Nullable;

import java.util.Arrays;

/**
 * Additive totals over many fights: the session totals (kept in memory by CORE) and the lifetime
 * totals (persisted by RECORD as {@code lifetime.json}). A Gson POJO.
 *
 * <p>{@link #add} folds in one finished fight using its {@link FightStats}, so every figure agrees
 * with the per-fight numbers. Rates are computed on demand and are 0 when their denominator is 0.
 */
public final class StatsTotals {
	public long sinceEpochMs;
	public int fights, wins, losses, draws;
	public long durationMs;
	public int swings, landed, crits, sprintHits, threeBlock, hitsTaken;
	public double reachSum, reachMax;
	public int reachCount;
	public int jumpAttempts, jumpSuccess, bestCombo, bestStreak;
	public double intervalSum;
	public int intervalCount;
	/** Landed hits by {@code AimZone.code()}: CENTER, EDGE, HIGH, LOW. */
	public int[] aim = new int[4];
	/** Landed hits by {@code HitQuality.code()}. */
	public int[] quality = new int[7];
	public int trades, tradesWon, tradesLost;

	/** Adds one finished fight. Ignored when either argument is null. */
	public void add(@Nullable FightRecord r, @Nullable FightStats s) {
		if (r == null || s == null) {
			return;
		}
		ensureArrays();
		if (this.sinceEpochMs == 0L) {
			this.sinceEpochMs = r.startEpochMs;
		}
		this.fights++;
		String result = r.result == null ? "" : r.result;
		if (result.equals(FightResult.WIN.name())) {
			this.wins++;
		} else if (result.equals(FightResult.LOSS.name())) {
			this.losses++;
		} else {
			this.draws++;
		}
		this.durationMs += Math.max(0, s.durationMs);
		this.swings += s.swings;
		this.landed += s.landed;
		this.crits += s.crits;
		this.sprintHits += s.sprintHits;
		this.threeBlock += s.threeBlockHits;
		this.hitsTaken += s.hitsTaken;
		if (s.reachCount > 0) {
			this.reachSum += s.reachAvg * s.reachCount;
			this.reachCount += s.reachCount;
			this.reachMax = Math.max(this.reachMax, s.reachMax);
		}
		this.jumpAttempts += s.jumpAttempts;
		this.jumpSuccess += s.jumpSuccess;
		this.bestCombo = Math.max(this.bestCombo, s.bestCombo);
		this.bestStreak = Math.max(this.bestStreak, s.bestStreak);
		if (s.intervalCount > 0) {
			this.intervalSum += s.intervalAvgMs * s.intervalCount;
			this.intervalCount += s.intervalCount;
		}
		this.aim[AimZone.CENTER.code()] += s.aimCenter;
		this.aim[AimZone.EDGE.code()] += s.aimEdge;
		this.aim[AimZone.HIGH.code()] += s.aimHigh;
		this.aim[AimZone.LOW.code()] += s.aimLow;
		if (s.quality != null) {
			for (int i = 0; i < Math.min(s.quality.length, this.quality.length); i++) {
				this.quality[i] += s.quality[i];
			}
		}
		this.trades += s.trades;
		this.tradesWon += s.tradesWon;
		this.tradesLost += s.tradesLost;
	}

	public double accuracyPct() {
		return pct(this.landed, this.swings);
	}

	public double reachAvg() {
		return this.reachCount > 0 ? this.reachSum / this.reachCount : 0.0;
	}

	public double critPct() {
		return pct(this.crits, this.landed);
	}

	public double sprintPct() {
		return pct(this.sprintHits, this.landed);
	}

	public double threeBlockPct() {
		return pct(this.threeBlock, this.landed);
	}

	public double jumpPct() {
		return pct(this.jumpSuccess, this.jumpAttempts);
	}

	public double intervalAvgMs() {
		return this.intervalCount > 0 ? this.intervalSum / this.intervalCount : 0.0;
	}

	public double tradeWinPct() {
		return pct(this.tradesWon, this.tradesWon + this.tradesLost);
	}

	/** Repairs arrays that a hand-edited or older file left null or short. */
	private void ensureArrays() {
		if (this.aim == null) {
			this.aim = new int[4];
		} else if (this.aim.length < 4) {
			this.aim = Arrays.copyOf(this.aim, 4);
		}
		if (this.quality == null) {
			this.quality = new int[7];
		} else if (this.quality.length < 7) {
			this.quality = Arrays.copyOf(this.quality, 7);
		}
	}

	private static double pct(int part, int whole) {
		return whole <= 0 ? 0.0 : 100.0 * part / whole;
	}
}
