package kwamster.core.service;

import kwamster.core.event.HitResolvedEvent;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.StatsTotals;
import org.jspecify.annotations.Nullable;

/**
 * Read API over the live fight, the last finished fight and the session totals, served by
 * {@code Kwamster.fights()} (CORE's FightManager). Client thread.
 */
public interface FightService {
	/** A fight is running (it may be in its final ENDING tick). */
	boolean inFight();

	/** The running fight, or null. */
	@Nullable LiveFightView current();

	/** The running fight's current opponent, or null. */
	@Nullable PlayerRef currentOpponent();

	/** The last finished fight this session, or null. */
	@Nullable FightRecord lastRecord();

	/** Its final numbers, or null. */
	@Nullable FightStats lastStats();

	/** {@code Clock.nanos()} when the last fight was finalised; 0 if none this session. */
	long lastEndedNanos();

	/** The most recent resolved swing (landed or missed), or null. */
	@Nullable HitResolvedEvent lastResolved();

	/** Totals of the fights ended this game session with {@code alert == true}. */
	StatsTotals session();

	/** Clears the session totals. */
	void resetSession();
}
