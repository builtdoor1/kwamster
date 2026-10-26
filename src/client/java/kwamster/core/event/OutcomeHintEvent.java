package kwamster.core.event;

import org.jspecify.annotations.Nullable;

/**
 * A server message that names the result of the running fight (§5.13a): a kill message
 * ("Kwamix was slain by builtdoor"), a winner announcement or a VICTORY / DEFEAT title. Posted by CAPTURE;
 * FightManager turns it into a WIN or LOSS when the fight has no death event, as on practice servers
 * that teleport the loser away instead of killing them.
 *
 * @param nanos    hook time
 * @param win      true when the message says you won (or the opponent died), false when you lost
 * @param opponent the opponent the message names as the one who died or lost, as written in the message;
 *                 null when it names nobody ("VICTORY!") or it is your own death
 * @param note     where it came from, for the summary hover, e.g. {@code Read from chat: "Kwamix was slain by builtdoor"}
 */
public record OutcomeHintEvent(long nanos, boolean win, @Nullable String opponent, String note) {
}
