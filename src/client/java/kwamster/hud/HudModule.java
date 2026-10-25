package kwamster.hud;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;
import kwamster.core.ScreenRegistry;
import kwamster.hud.editor.HudEditorScreen;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.resources.Identifier;

/**
 * The HUD unit's module (order 600): registers KWAMSTER's HUD layer with Fabric just before vanilla's
 * boss bar (§6.6, so server boss bars, the sidebar, titles and chat draw over it), the
 * {@code HUD_EDITOR} screen factory and the HUD's bus listener.
 */
public final class HudModule implements KwamsterModule {
	/** The Fabric layer id. */
	public static final Identifier LAYER_ID = Identifier.fromNamespaceAndPath(KwamsterClient.MOD_ID, "hud");

	@Override
	public int order() {
		return 600;
	}

	@Override
	public void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR, LAYER_ID, HudManager::render);
		Kwamster.services().screens().register(ScreenRegistry.ScreenId.HUD_EDITOR, HudEditorScreen::new);
		Kwamster.events().register(HudManager.get().listener());
		KwamsterClient.LOG.debug("KWAMSTER HUD layer registered before {}", VanillaHudElements.BOSS_BAR);
	}
}
