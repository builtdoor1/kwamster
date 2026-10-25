package kwamster.core.model;

import java.util.List;

/**
 * Read-only view of the running fight, served by {@code Kwamster.fights().current()}.
 *
 * <p>All {@code nowNanos} arguments are raw {@code Clock.nanos()}; the view converts them to the
 * fight clock (singleplayer pauses excluded). Every number equals what
 * {@code StatsCalculator.compute(liveRecord, nowRel)} returns (the single-source-of-truth rule, §4.5);
 * {@link #stats(long)} is cached for at most 50 ms. Client thread only.
 */
public interface LiveFightView {
	/** Fight start on the fight clock (for a fresh fight this equals the raw {@code Clock.nanos()} of the opening event). */
	long startNanos();

	long startEpochMs();

	/** The current opponent (provisional until engaged, §5.14). */
	PlayerRef opponent();

	/** Everyone referenced in this fight; index order = {@code FightRecord.opponents}. */
	List<PlayerRef> opponents();

	/** Fight-clock milliseconds since the start, at least 0. */
	long elapsedMs(long nowNanos);

	/** {@code StatsCalculator.compute(record, nowRel)}, cached for at most 50 ms. */
	FightStats stats(long nowNanos);

	/** Equals {@code stats(now).currentCombo} (normative). */
	int currentCombo(long nowNanos);

	/** Equals {@code stats(now).currentComboTaken}. */
	int currentComboTaken(long nowNanos);

	/** The live hit streak; equals {@code stats(now).currentStreak}. */
	int currentStreak();

	/** In [-1, 1]; equals {@code stats(now).advantage}. */
	double advantage(long nowNanos);
}
