package kwamster.core.event;

import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;

/**
 * A damage event on the local player whose cause is another (non-NPC) player (§6.3).
 *
 * @param nanos          hook time
 * @param tick           {@code Clock.ticks()} at the hook
 * @param attacker       the attacking player
 * @param projectile     the source was indirect (bow, rod, snowball...): keep-alive only
 * @param attackerReach  attacker eye to own AABB (approximate: remote positions are interpolated)
 * @param selfOnGround   own on-ground flag when hit
 * @param selfSprinting  own sprint flag when hit
 * @param selfPos        own position
 * @param attackerPos    attacker position
 */
public record HitTakenEvent(long nanos, long tick, PlayerRef attacker, boolean projectile,
		double attackerReach,
		boolean selfOnGround, boolean selfSprinting, Pos selfPos, Pos attackerPos) {

	/** A copy with a different {@link #nanos} (CORE uses this to move the event onto the fight clock). */
	public HitTakenEvent withNanos(long newNanos) {
		return new HitTakenEvent(newNanos, this.tick, this.attacker, this.projectile, this.attackerReach,
				this.selfOnGround, this.selfSprinting, this.selfPos, this.attackerPos);
	}
}
