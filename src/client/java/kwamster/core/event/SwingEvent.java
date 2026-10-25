package kwamster.core.event;

import kwamster.core.model.AimSample;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;

/**
 * One committed swing at a player, landed or missed (§4.2). Posted by CAPTURE at
 * {@code Minecraft.startAttack} RETURN.
 *
 * @param nanos        startAttack HEAD time (hook time); used for ordering, fight start and keep-alive,
 *                     combos, advantage, trades and confirmation
 * @param clickTimed   a click press no older than 200 ms was claimed
 * @param clickLeadMs  hook minus claimed click press, 0..200; 0 when {@code !clickTimed}. S8 intervals use {@code t - clickLeadMs}
 * @param target       the player the swing was attributed to
 * @param landed       reached {@code MultiPlayerGameMode.attack} as a non-spectator (§6.2)
 * @param intoImmunity landed and the target had {@code invulnerableTime > 10} at the landed hook ("NO-REG", §5.4)
 * @param reach        eye to the nearest point of the target AABB in blocks, tick positions
 * @param rayReach     eye to the ray entry point; NaN if the ray misses the box
 * @param rangeLimit   §5.2: the attack range the server checks against (3.0 vanilla)
 * @param customRange  the held item has {@code DataComponents.ATTACK_RANGE} (3-block grading is skipped, §5.9)
 * @param aim          aim offsets; the zone is non-null only when landed
 * @param charge       {@code getAttackStrengthScale(0.5F)}
 * @param charged      {@code charge > 0.9F}
 * @param crit         charged and {@code canCriticalAttack} (verbatim)
 * @param sprint       raw {@code isSprinting()} at classification
 * @param sweep        vanilla sweep condition
 * @param onGround     own on-ground flag
 * @param selfPos      own position
 * @param targetPos    target position
 * @param selfYaw      own yaw in degrees
 * @param pingMs       own PlayerInfo latency, 0 if unknown
 */
public record SwingEvent(
		long nanos,
		boolean clickTimed,
		int clickLeadMs,
		PlayerRef target,
		boolean landed,
		boolean intoImmunity,
		double reach,
		double rayReach,
		double rangeLimit,
		boolean customRange,
		AimSample aim,
		float charge,
		boolean charged,
		boolean crit,
		boolean sprint,
		boolean sweep,
		boolean onGround,
		Pos selfPos, Pos targetPos,
		float selfYaw,
		int pingMs) {

	/** A copy with a different {@link #nanos} (CORE uses this to move the event onto the fight clock). */
	public SwingEvent withNanos(long newNanos) {
		return new SwingEvent(newNanos, this.clickTimed, this.clickLeadMs, this.target, this.landed, this.intoImmunity,
				this.reach, this.rayReach, this.rangeLimit, this.customRange, this.aim, this.charge, this.charged,
				this.crit, this.sprint, this.sweep, this.onGround, this.selfPos, this.targetPos, this.selfYaw, this.pingMs);
	}
}
