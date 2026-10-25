package kwamster.mixin;

import kwamster.capture.CombatCapture;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Timestamps presses of the mouse button bound to Attack, for frame-accurate S8 click timing (§6.4).
 * {@code MouseHandler.onButton} (client/MouseHandler.java:65) runs from a queue that drains once per frame,
 * so a timestamp taken here is accurate to a frame rather than a tick.
 *
 * <p>{@code require = 0}: click timing is an enhancement. If another client mod reshapes {@code onButton},
 * the mod still loads and falls back to hook timing ({@code clickLeadMs = 0}).
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {
	@Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), require = 0)
	private void kwamster$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
		CombatCapture.onMouseButton((MouseHandler) (Object) this, info, action);
	}
}
