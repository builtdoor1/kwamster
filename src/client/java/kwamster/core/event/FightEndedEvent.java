package kwamster.core.event;

import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;

/**
 * A fight was finalised (CORE event). RECORD saves it (regardless of the master switch), FEEDBACK
 * prints the summary and the HUD shows the card when {@code alert} is set.
 *
 * @param record the finished record ({@code record.stats == stats})
 * @param stats  the final numbers
 * @param alert  {@code (swings + melee hits taken) ≥ minFightEvents || result != DRAW}
 */
public record FightEndedEvent(FightRecord record, FightStats stats, boolean alert) {
}
