package kwamster.mixin;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import kwamster.crosshair.CrosshairRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Wraps the sprite blits inside vanilla's {@code Gui.renderCrosshair} (§6.5) instead of cancelling
 * the method, so every vanilla gate (first person, spectator rule, 3D-crosshair debug entry, F1,
 * loading screen) still applies. Each call is handed to {@link CrosshairRenderer#onVanillaSprite},
 * which picks the sprite by id, so a reordered vanilla body cannot misroute a call.
 *
 * <ul>
 * <li>4-int overload (Gui.java:432, 446, 449): {@code hud/crosshair} and the full and background
 * attack-indicator sprites.</li>
 * <li>8-int overload (Gui.java:450): the attack-indicator progress sprite.</li>
 * </ul>
 *
 * <p>Both wrappers declare {@code require = 0}: if another client rewrites the vanilla crosshair, the
 * game still boots and vanilla or that mod draws instead.
 */
@Mixin(Gui.class)
public abstract class GuiCrosshairMixin {
	@WrapWithCondition(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
			require = 0)
	private boolean kwamster$sprite(GuiGraphics g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return CrosshairRenderer.onVanillaSprite(g, sprite);
	}

	@WrapWithCondition(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
			at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"),
			require = 0)
	private boolean kwamster$progress(GuiGraphics g, RenderPipeline pipeline, Identifier sprite,
			int tw, int th, int u, int v, int x, int y, int w, int h) {
		return CrosshairRenderer.onVanillaSprite(g, sprite);
	}
}
