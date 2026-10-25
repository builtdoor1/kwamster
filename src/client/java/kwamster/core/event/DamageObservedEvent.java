package kwamster.core.event;

import java.util.UUID;

/**
 * A health drop observed by CAPTURE's HealthWatch. Only drops of self or of players already in the
 * fight's opponents are recorded (§4.9).
 *
 * @param nanos  the tick the drop was observed
 * @param who    whose health dropped
 * @param self   {@code who} is the local player
 * @param amount health lost, in health points (1.0 = half a heart)
 */
public record DamageObservedEvent(long nanos, UUID who, boolean self, double amount) {

	/** A copy with a different {@link #nanos} (CORE uses this to move the event onto the fight clock). */
	public DamageObservedEvent withNanos(long newNanos) {
		return new DamageObservedEvent(newNanos, this.who, this.self, this.amount);
	}
}
