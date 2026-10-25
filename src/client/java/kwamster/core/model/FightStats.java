package kwamster.core.model;

/**
 * Every derived number of one fight (§4.7), produced only by
 * {@code kwamster.core.stats.StatsCalculator.compute}. A Gson POJO.
 *
 * <p>NaN is never stored. When something has no data the value is 0 and the matching count field
 * is 0; formatters print "—" when the count is 0.
 */
public final class FightStats {
	/** Live: {@code max(0, nowRel)}; final: {@code record.durationMs}. */
	public int durationMs;
	// S4
	public int swings, landed, misses;
	public double accuracyPct;
	/** Landed swings flagged NO-REG (included in {@link #landed}). */
	public int immuneHits;
	// S2
	public double reachAvg, reachMax;
	public int reachCount;
	public double missReachAvg, missReachMax;
	public int missReachCount, overReachMisses;
	/**
	 * The attack range of the miss that set {@link #missReachMax} (0 with no misses), so an over-reach
	 * maximum is shown above that range (§7.0, {@code StatFormat.reachVsRange}).
	 */
	public double missReachMaxLimit;
	// S3 (landed only)
	public int aimCenter, aimEdge, aimHigh, aimLow;
	// S5
	public int currentCombo, bestCombo, combosDealt, comboHitsDealt;
	public double avgCombo;
	public int currentComboTaken, bestComboTaken, combosTaken, comboHitsTaken;
	public double avgComboTaken;
	public int currentStreak, bestStreak;
	public double advantage, advYouPct, advThemPct;
	// S6, S7
	public int sprintHits, nonSprintHits;
	public double sprintPct;
	public int crits;
	public double critPct;
	// S8
	public int intervalCount;
	public double intervalAvgMs, intervalSdMs;
	public int threeBlockHits;
	public double threeBlockPct;
	/** Landed swings by {@code HitQuality.code()}. */
	public int[] quality = new int[7];
	// S1 (jumpOpportunities = melee hits taken with onGround, derived)
	public int jumpAttempts, jumpSuccess, jumpEarly, jumpLate, jumpOpportunities;
	/** {@code jumpAvgDeltaMs} is over non-early attempts, in game ms. */
	public double jumpPct, jumpAvgDeltaMs;
	// taken, trades, damage, confirmation
	public int hitsTaken, projectileHitsTaken;
	public double takenReachAvg;
	public int trades, tradesWon, tradesLost, tradesEven;
	public double tradeWinPct, avgMomentumPct;
	public int tradesWithMomentum;
	public boolean damageKnown;
	public double damageDealt, damageTaken;
	public int hitsConfirmed;
	public double confirmPct;
	public int piercingSwings;
}
