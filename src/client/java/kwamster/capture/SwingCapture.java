package kwamster.capture;

import kwamster.core.config.KwamsterConfig;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.AimSample;
import kwamster.core.model.Pos;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.component.AttackRange;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.function.Predicate;

/**
 * The geometry of a swing (port of combat-report {@code SwingCapture}, extended with ray reach and aim):
 * who it was thrown at, how far away they were and where on their hitbox it went. Pure functions over
 * the local player's tick state; client thread.
 *
 * <p>Everything is measured on <b>tick positions</b> ({@code getEyePosition()}, {@code getBoundingBox()}),
 * because that is what vanilla's pick and the server's range check use; mixing an interpolated eye with
 * a tick-position box is worth a quarter of a block at sprint speed.
 */
public final class SwingCapture {
	private SwingCapture() {
	}

	/**
	 * The player a swing is attributed to.
	 *
	 * @param player   the target
	 * @param onTarget vanilla's own pick ({@code mc.hitResult}) found this player
	 */
	public record Target(Player player, boolean onTarget) {
	}

	/** Everything measured about a swing at its target, at {@code startAttack} HEAD. */
	public record Geometry(double reach, double rayReach, double rangeLimit, boolean customRange, AimSample aim,
			Pos selfPos, Pos targetPos, float selfYaw) {
	}

	/**
	 * Target resolution (§5.4 step 8).
	 * <ul>
	 *   <li>{@code hitResult} is an {@link EntityHitResult} on a living {@link Player} other than
	 *       {@code self} that is not an NPC and not a body: that player, on target.</li>
	 *   <li>Any other {@code EntityHitResult} (mobs, crystals, armour stands, NPC players, and dead players:
	 *       a body stays pickable until the server removes it, about a second after the death, so spam
	 *       clicks after a kill would otherwise open a phantom fight against the body): null. Crystal PvP
	 *       must not fill the miss column with clicks that hit exactly what they were aimed at.</li>
	 *   <li>Otherwise (MISS or BLOCK): the {@link #coneTarget cone target}, or null if there is none. A swing
	 *       at empty air with nobody near is not a miss at anything.</li>
	 * </ul>
	 *
	 * @param corpse tests whether a player is a body whose death was just seen (servers that spoof health
	 *               keep a body looking alive); such a player is treated like a dead one
	 */
	public static @Nullable Target resolve(Minecraft mc, LocalPlayer self, KwamsterConfig.Advanced adv,
			Predicate<Player> corpse) {
		HitResult hit = mc.hitResult;
		if (hit instanceof EntityHitResult entityHit) {
			if (entityHit.getEntity() instanceof Player picked && picked != self && isLiving(picked, corpse)
					&& !PlayerRefs.isNpc(picked)) {
				return new Target(picked, true);
			}
			return null;
		}
		Player intended = coneTarget(mc, self, adv.swingTargetRange, adv.swingTargetConeDeg, corpse);
		return intended == null ? null : new Target(intended, false);
	}

	/**
	 * The {@link #isLiving living}, non-spectator, non-NPC player other than {@code self} whose box centre
	 * is within {@code range} of the eye and that has the smallest angle to the look vector below
	 * {@code coneDeg}.
	 */
	public static @Nullable Player coneTarget(Minecraft mc, LocalPlayer self, double range, double coneDeg,
			Predicate<Player> corpse) {
		ClientLevel level = mc.level;
		if (level == null) {
			return null;
		}
		Vec3 eye = self.getEyePosition();
		Vec3 look = self.getViewVector(1.0F);
		Player best = null;
		double bestAngle = coneDeg;
		for (Player other : level.players()) {
			if (other == self || other.isSpectator() || !isLiving(other, corpse)) {
				continue;
			}
			Vec3 toTarget = other.getBoundingBox().getCenter().subtract(eye);
			if (toTarget.length() > range) {
				continue;
			}
			double angle = angleBetween(look, toTarget);
			if (angle < bestAngle && !PlayerRefs.isNpc(other)) {
				bestAngle = angle;
				best = other;
			}
		}
		return best;
	}

	/**
	 * True if {@code p} can still be fought: not removed, synced health not at or below 0
	 * ({@code isDeadOrDying()}; a vanilla body syncs exactly 0), and not a body whose death was just seen.
	 * Deliberately not {@code isAlive()}, whose {@code health > 0} test also rejects a health value a server
	 * has hidden as NaN, which would drop every hit on that server.
	 */
	public static boolean isLiving(Player p, Predicate<Player> corpse) {
		return !p.isRemoved() && !p.isDeadOrDying() && (corpse == null || !corpse.test(p));
	}

	/**
	 * Measures a swing at {@code target}: reach, ray reach, the range the server checks against, and the
	 * aim offsets (no zone yet; {@link AimMath#withOutcome} sets it once the outcome is known).
	 *
	 * @param hitLocation vanilla's ray entry point when the pick found {@code target}, else null (a miss)
	 */
	public static Geometry measure(LocalPlayer self, Entity target, @Nullable Vec3 hitLocation) {
		Vec3 eyeVec = self.getEyePosition();
		Vec3 lookVec = self.getViewVector(1.0F);
		AABB aabb = target.getBoundingBox();
		Pos eye = PlayerRefs.of(eyeVec);
		Pos look = PlayerRefs.of(lookVec);
		AimMath.Box box = new AimMath.Box(aabb.minX, aabb.minY, aabb.minZ, aabb.maxX, aabb.maxY, aabb.maxZ);
		float yaw = self.getYRot();

		double reach = reachTo(self, target);
		double rayReach;
		AimSample aim;
		if (hitLocation != null) {
			aim = AimMath.sample(PlayerRefs.of(hitLocation), box, eye, look, yaw, false);
			// With the eye inside the box vanilla reports the exit point; the entry point is the eye itself.
			rayReach = aim.eyeInside() ? 0.0 : eyeVec.distanceTo(hitLocation);
		} else {
			rayReach = AimMath.rayEntryDistance(box, eye, look);
			aim = AimMath.missed(box, eye, look, yaw);
		}

		AttackRange range = self.entityAttackRange();
		double rangeLimit = range.effectiveMaxRange(self) + range.hitboxMargin();
		boolean customRange = self.getActiveItem().has(DataComponents.ATTACK_RANGE);
		return new Geometry(reach, rayReach, rangeLimit, customRange, aim,
				PlayerRefs.of(self.position()), PlayerRefs.of(target.position()), yaw);
	}

	/**
	 * Eye to the nearest point of the target's hitbox: exactly what the server's attack check measures
	 * ({@code AttackRange.isInRange} on the eye distance to the AABB). Deliberately not the ray entry point,
	 * which is always larger and makes legitimate vanilla hits read above 3.0 blocks.
	 */
	public static double reachTo(Entity from, Entity target) {
		return Math.sqrt(target.getBoundingBox().distanceToSqr(from.getEyePosition()));
	}

	/** Smallest angle in degrees between two directions; 180 for a zero-length vector. */
	private static double angleBetween(Vec3 a, Vec3 b) {
		double lengths = a.length() * b.length();
		if (lengths < 1.0e-6) {
			return 180.0;
		}
		double cos = Math.max(-1.0, Math.min(1.0, a.dot(b) / lengths));
		return Math.toDegrees(Math.acos(cos));
	}
}
