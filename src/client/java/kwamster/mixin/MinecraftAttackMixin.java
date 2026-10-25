package kwamster.mixin;

import kwamster.capture.CombatCapture;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Every left-click attack, including the ones that hit nothing (§6.1). {@code Minecraft.startAttack()}
 * (client/Minecraft.java:1682) is reached only from {@code handleKeybinds}, once per Attack click, whether
 * or not anything was in reach, which is what makes a whiff measurable at all.
 *
 * <p>HEAD opens the swing and RETURN commits it, because what happens in between (whether vanilla reached
 * {@code MultiPlayerGameMode.attack}) is exactly what the record needs. RETURN fires at every exit, so a
 * swing rejected by the guards never lingers. Both injectors are required: the method cannot disappear.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftAttackMixin {
	@Inject(method = "startAttack()Z", at = @At("HEAD"))
	private void kwamster$swingBegin(CallbackInfoReturnable<Boolean> cir) {
		CombatCapture.onStartAttackHead((Minecraft) (Object) this);
	}

	@Inject(method = "startAttack()Z", at = @At("RETURN"))
	private void kwamster$swingCommit(CallbackInfoReturnable<Boolean> cir) {
		CombatCapture.onStartAttackReturn();
	}
}
