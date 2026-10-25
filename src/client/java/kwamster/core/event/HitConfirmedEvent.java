package kwamster.core.event;

import kwamster.core.model.PlayerRef;

/**
 * A server damage event on another player caused by you ({@code source.getEntity() == self}, never
 * THORNS, §6.3).
 *
 * @param nanos  hook time
 * @param victim the damaged player
 * @param direct melee: confirms a landed swing (§5.9); false = your projectile, keep-alive only
 */
public record HitConfirmedEvent(long nanos, PlayerRef victim, boolean direct) {

	/** A copy with a different {@link #nanos} (CORE uses this to move the event onto the fight clock). */
	public HitConfirmedEvent withNanos(long newNanos) {
		return new HitConfirmedEvent(newNanos, this.victim, this.direct);
	}
}
