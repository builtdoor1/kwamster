package kwamster.mixin;

import kwamster.capture.CombatCapture;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timestamps presses of a keyboard key bound to Attack, for frame-accurate S8 click timing (§6.4).
 * {@code KeyboardHandler.keyPress} (client/KeyboardHandler.java:434) is the GLFW keyboard callback.
 *
 * <p>{@code require = 0}: click timing is an enhancement. If another client mod reshapes {@code keyPress},
 * the mod still loads and falls back to hook timing ({@code clickLeadMs = 0}).
 */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {
	@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), require = 0)
	private void kwamster$keyPress(long window, int action, KeyEvent event, CallbackInfo ci) {
		CombatCapture.onKey(event, action);
	}
}
