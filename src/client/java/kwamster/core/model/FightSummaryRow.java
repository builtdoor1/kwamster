package kwamster.core.model;

/**
 * One row of the saved-fights index ({@code fights/index.json}, §10). A Gson POJO built and
 * serialised on the client thread by RECORD.
 */
public final class FightSummaryRow {
	/** Fight id, e.g. {@code 20261020-153012-123-Kwamix}. */
	public String id;
	/** File name inside the fights folder. */
	public String file;
	/** Primary opponent name. */
	public String opponent;
	/** {@code FightResult.name()}. */
	public String result;
	/** Server label ("Singleplayer", a host name, or empty). */
	public String server;
	public long startEpochMs, durationMs;
	public int swings, landed, jumpAttempts, jumpSuccess, bestCombo;
	/** Engaged opponents besides the primary ("vs Kwamix +1"). */
	public int extraOpponents;
	public double accuracyPct, reachAvg;
	public boolean pinned;
	/**
	 * The fight did not alert ({@code !FightEndedEvent.alert()}: a draw below {@code minFightEvents}, such
	 * as a single whiff at a bystander) and was saved only because {@code saveTrivialFights} is on. Pruning
	 * deletes these before any real fight (§10). Named for the trivial case so rows written before the
	 * field existed, which Gson reads as {@code false}, count as real fights; rows rebuilt from fight files
	 * are {@code false} too (the file does not record the alert decision).
	 */
	public boolean trivial;
}
