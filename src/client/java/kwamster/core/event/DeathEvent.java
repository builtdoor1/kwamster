package kwamster.core.event;

import kwamster.core.model.PlayerRef;

/**
 * Entity event 3 (death) for a player (§6.3): the authoritative death signal.
 *
 * @param nanos hook time
 * @param who   the player who died
 * @param self  {@code who} is the local player
 */
public record DeathEvent(long nanos, PlayerRef who, boolean self) {
}
