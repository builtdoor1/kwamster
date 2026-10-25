package kwamster.core.event;

import kwamster.core.model.Pos;

/**
 * A real jump of the local player ({@code LivingEntity.jumpFromGround}, §6.3).
 *
 * @param nanos hook time
 * @param tick  {@code Clock.ticks()} at the hook
 * @param pos   own position
 */
public record JumpEvent(long nanos, long tick, Pos pos) {
}
