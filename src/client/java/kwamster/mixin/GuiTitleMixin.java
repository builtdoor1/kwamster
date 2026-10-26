package kwamster.mixin;

import kwamster.capture.OutcomeWatch;
import net.minecraft.client.gui.Gui;
import net.minecraft.network.chat.Component;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Titles and subtitles (§5.13a): practice and duel servers announce VICTORY / DEFEAT and kills there.
 * {@code ClientPacketListener.setTitleText} and {@code setSubtitleText} call these two setters. Both
 * injections only read the text and declare {@code require = 0}, so a client that replaces the title
 * overlay still boots and only loses this signal.
 */
@Mixin(Gui.class)
public abstract class GuiTitleMixin {
	@Inject(method = "setTitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
	private void kwamster$title(Component title, CallbackInfo ci) {
		OutcomeWatch.onTitle(title, false);
	}

	@Inject(method = "setSubtitle(Lnet/minecraft/network/chat/Component;)V", at = @At("HEAD"), require = 0)
	private void kwamster$subtitle(Component subtitle, CallbackInfo ci) {
		OutcomeWatch.onTitle(subtitle, true);
	}
}
