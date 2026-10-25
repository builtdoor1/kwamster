package kwamster.hud;

import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import net.minecraft.client.gui.GuiGraphics;

/**
 * One KWAMSTER HUD element (§7.1). {@link HudManager} positions it from its layout and draws it in
 * game; the HUD editor draws it with {@code preview = true} from {@link HudPreviewData}.
 *
 * <p>Not to be confused with Fabric's {@code net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement}:
 * the whole KWAMSTER HUD is one Fabric layer ({@link HudManager#render}). Client thread.
 */
public interface HudElement {
	/** The layout key of this element. */
	HudElementId id();

	/** Unscaled GUI width of the content drawn now (or of the preview content). */
	int width(boolean preview);

	/** Unscaled GUI height of the content drawn now (or of the preview content). */
	int height(boolean preview);

	/** The feature toggle (§7.1 table); the editor shows disabled elements as ghosts. */
	boolean enabled(KwamsterConfig c);

	/** Has something to show right now (in game only). */
	boolean active(long nowNanos);

	/** Draw at (0,0). The pose is already translated and scaled. {@code alpha} multiplies every colour. */
	void render(GuiGraphics g, long nowNanos, float alpha, boolean preview);
}
