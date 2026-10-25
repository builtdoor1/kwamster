package kwamster.capture;

import net.minecraft.tags.ItemTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

/**
 * The flags of one melee swing, transcribed from vanilla {@code Player.attack(Entity)} in 1.21.11
 * (port of combat-report {@code HitClassifier}). Not an approximation:
 *
 * <pre>
 *   float g   = this.getAttackStrengthScale(0.5F);
 *   boolean bl  = g &gt; 0.9F;                              // charged
 *   boolean bl2 = this.isSprinting() &amp;&amp; bl;               // sprint-knockback hit
 *   boolean bl3 = bl &amp;&amp; this.canCriticalAttack(entity);   // crit
 *   boolean bl4 = this.isSweepAttack(bl, bl3, bl2);        // sweep
 * </pre>
 *
 * <p>{@code canCriticalAttack} and {@code isSweepAttack} are private in vanilla, so they are copied
 * below verbatim; every method they call is public. A crit requires {@code !isSprinting()}, so crits
 * and sprint hits never collide.
 *
 * <p><b>Call this before the attack is processed.</b> {@code MultiPlayerGameMode.attack} calls
 * {@code player.attack(entity)} and then {@code resetAttackStrengthTicker()}, so the charge must be read
 * at the head of that method (or earlier, at {@code startAttack} HEAD).
 */
public final class HitClassifier {
	/** Vanilla's "strong attack" threshold on {@code getAttackStrengthScale(0.5F)}. */
	public static final float CHARGED_THRESHOLD = 0.9F;

	private HitClassifier() {
	}

	/**
	 * The swing's flags as {@link kwamster.core.event.SwingEvent} records them.
	 *
	 * @param charge   {@code getAttackStrengthScale(0.5F)}
	 * @param charged  {@code charge > 0.9F}
	 * @param crit     {@code charged && canCriticalAttack(target)}
	 * @param sprint   raw {@code isSprinting()} (a sprint hit is {@code charged && sprint}, §5.6)
	 * @param sweep    vanilla's sweep condition
	 * @param onGround the attacker's on-ground flag
	 */
	public record HitFlags(float charge, boolean charged, boolean crit, boolean sprint, boolean sweep, boolean onGround) {
	}

	/** The charge fraction vanilla itself uses to decide whether a swing is strong. */
	public static float charge(Player player) {
		return player.getAttackStrengthScale(0.5F);
	}

	/** Classifies a swing by {@code player} at {@code target}, exactly as {@code Player.attack} would at this instant. */
	public static HitFlags classify(Player player, Entity target) {
		float g = charge(player);
		boolean bl = g > CHARGED_THRESHOLD;
		boolean sprinting = player.isSprinting();
		boolean bl2 = sprinting && bl;
		boolean bl3 = bl && canCriticalAttack(player, target);
		boolean bl4 = isSweepAttack(player, bl, bl3, bl2);
		return new HitFlags(g, bl, bl3, sprinting, bl4, player.onGround());
	}

	/** Verbatim from {@code Player.canCriticalAttack(Entity)} (Player.java:1030–1038). */
	private static boolean canCriticalAttack(Player player, Entity entity) {
		return player.fallDistance > 0.0
				&& !player.onGround()
				&& !player.onClimbable()
				&& !player.isInWater()
				&& !player.isMobilityRestricted()
				&& !player.isPassenger()
				&& entity instanceof LivingEntity
				&& !player.isSprinting();
	}

	/** Verbatim from {@code Player.isSweepAttack(boolean, boolean, boolean)} (Player.java:1041–1051). */
	private static boolean isSweepAttack(Player player, boolean bl, boolean bl2, boolean bl3) {
		if (bl && !bl2 && !bl3 && player.onGround()) {
			double d = player.getKnownMovement().horizontalDistanceSqr();
			double e = player.getSpeed() * 2.5;
			if (d < Mth.square(e)) {
				return player.getItemInHand(InteractionHand.MAIN_HAND).is(ItemTags.SWORDS);
			}
		}
		return false;
	}
}
