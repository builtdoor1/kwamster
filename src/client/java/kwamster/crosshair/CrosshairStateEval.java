package kwamster.crosshair;

import kwamster.Kwamster;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.CrosshairMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.jspecify.annotations.Nullable;

/**
 * Computes the crosshair state (S15, §5.12) from the local player, every frame.
 *
 * <ul>
 * <li><b>On target</b>: {@code mc.crosshairPickEntity} is a living, non-spectator, non-NPC player
 * (or, with {@code crosshair.targetMobs}, any living entity). Vanilla only picks an entity inside
 * attack reach ({@code LocalPlayer.filterHitResult}), so this also means "you can hit them now".</li>
 * <li><b>Crit ready</b>: the next attack would be a vanilla critical hit: charged
 * ({@code getAttackStrengthScale(0.5F) > 0.9F}, what {@code Player.attack} reads) and the
 * {@code Player.canCriticalAttack} conditions on the attacker (Player.java:1030-1038). Its last
 * condition, {@code target instanceof LivingEntity}, holds for every on-target entity.</li>
 * </ul>
 *
 * <p>The gametest's {@code TestHooks.crosshairOverride()} wins when set. Client thread.
 */
public final class CrosshairStateEval {
	private CrosshairStateEval() {
	}

	/** The current mode; DEFAULT when there is no local player. */
	public static CrosshairMode evaluate(Minecraft mc) {
		CrosshairMode override = Kwamster.testHooks().crosshairOverride();
		if (override != null) {
			return override;
		}
		LocalPlayer self = mc == null ? null : mc.player;
		if (self == null) {
			return CrosshairMode.DEFAULT;
		}
		boolean onTarget = isTarget(self, mc.crosshairPickEntity, Kwamster.config().crosshair.targetMobs);
		boolean critReady = isCharged(self) && canCrit(self);
		if (critReady) {
			return onTarget ? CrosshairMode.CRIT_READY_ON_PLAYER : CrosshairMode.CRIT_READY;
		}
		return onTarget ? CrosshairMode.ON_PLAYER : CrosshairMode.DEFAULT;
	}

	/** {@code e} counts as a target for the ON_PLAYER colours (§5.12; NPCs are never players, §5.4). */
	static boolean isTarget(LocalPlayer self, @Nullable Entity e, boolean targetMobs) {
		// Not isAlive(): its health > 0 test rejects players on servers that hide health as NaN.
		if (e == null || e == self || e.isRemoved() || (e instanceof LivingEntity living && living.isDeadOrDying())) {
			return false;
		}
		if (e instanceof Player player && !player.isSpectator() && !PlayerRefs.isNpc(player)) {
			return true;
		}
		return targetMobs && e instanceof LivingEntity;
	}

	/** The swing would be charged: {@code Player.attack} computes {@code getAttackStrengthScale(0.5F) > 0.9F}. */
	static boolean isCharged(LocalPlayer self) {
		return self.getAttackStrengthScale(0.5F) > 0.9F;
	}

	/** Verbatim attacker-side {@code Player.canCriticalAttack} conditions (Player.java:1030-1038). */
	static boolean canCrit(LocalPlayer self) {
		return self.fallDistance > 0.0
				&& !self.onGround()
				&& !self.onClimbable()
				&& !self.isInWater()
				&& !self.isMobilityRestricted()
				&& !self.isPassenger()
				&& !self.isSprinting();
	}
}
