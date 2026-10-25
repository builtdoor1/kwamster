package kwamster.mixin;

import kwamster.capture.CombatCapture;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Damage events, jumps and deaths (§6.3).
 * <ul>
 *   <li>{@code handleDamageEvent} (world/entity/LivingEntity.java:1938) is the client-side arrival of a
 *       server damage event; its source is already resolved against the client level, so the attacker is a
 *       real entity, not an id to guess at.</li>
 *   <li>{@code jumpFromGround} (2262) is the real jump call, so knockback that throws you upward is never
 *       mistaken for a jump. It runs only on the ground, so it cannot see a jump pressed after the
 *       knockback has lifted you; those late (and airborne early) presses are sampled once per tick from
 *       the jump input by {@code CombatCapture} instead (§5.1).</li>
 *   <li>{@code handleEntityEvent} (1953) with event 3 is the authoritative death signal; Player and
 *       LocalPlayer pass byte 3 to {@code super}.</li>
 * </ul>
 *
 * <p>In singleplayer these methods also run on the integrated-server thread ({@code ServerPlayer.jumpFromGround}
 * calls {@code super}; every mob jump lands here), so every handler first returns unless the entity's level
 * is client-side, before anything else touches KWAMSTER (R2b).
 */
@Mixin(LivingEntity.class)
public abstract class LivingEntityMixin {
	@Inject(method = "handleDamageEvent(Lnet/minecraft/world/damagesource/DamageSource;)V", at = @At("HEAD"))
	private void kwamster$damage(DamageSource source, CallbackInfo ci) {
		if (!((LivingEntity) (Object) this).level().isClientSide()) {
			return;
		}
		CombatCapture.onDamageEvent((LivingEntity) (Object) this, source);
	}

	@Inject(method = "jumpFromGround()V", at = @At("HEAD"))
	private void kwamster$jump(CallbackInfo ci) {
		if (!((LivingEntity) (Object) this).level().isClientSide()) {
			return;
		}
		CombatCapture.onJump((LivingEntity) (Object) this);
	}

	@Inject(method = "handleEntityEvent(B)V", at = @At("HEAD"))
	private void kwamster$entityEvent(byte id, CallbackInfo ci) {
		if (!((LivingEntity) (Object) this).level().isClientSide()) {
			return;
		}
		CombatCapture.onEntityEvent((LivingEntity) (Object) this, id);
	}
}
