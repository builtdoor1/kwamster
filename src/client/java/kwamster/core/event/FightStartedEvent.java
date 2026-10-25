package kwamster.core.event;

import kwamster.core.model.LiveFightView;

/**
 * A fight opened (CORE event). Dispatched after the capture event that opened it.
 *
 * @param fight the live view of the new fight
 */
public record FightStartedEvent(LiveFightView fight) {
}
