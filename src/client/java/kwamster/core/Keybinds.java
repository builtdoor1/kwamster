package kwamster.core;

import com.mojang.blaze3d.platform.InputConstants;
import kwamster.Kwamster;
import kwamster.core.model.FightRecord;
import kwamster.core.model.ShareLink;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;

/**
 * The KWAMSTER key category and keys (§11), and their handling at the end of every client tick.
 *
 * <p>Only {@link #OPEN_SETTINGS} has a default (K, unbound in vanilla 1.21.11). Screens open only
 * when no screen is open, so typing in chat cannot trigger them. Key and category names are
 * translation keys (R5, {@code assets/kwamster/lang/en_us.json}); every other text is literal.
 *
 * <p>While the master switch is off (R4) the keys are still drained, and only {@link #OPEN_SETTINGS}
 * acts, so the mod can be turned back on in game without Mod Menu.
 */
public final class Keybinds {
	/** The "KWAMSTER" category (label key {@code key.category.kwamster.main}). */
	public static final KeyMapping.Category CATEGORY =
			KeyMapping.Category.register(Identifier.fromNamespaceAndPath("kwamster", "main"));

	private static final int UNBOUND = InputConstants.UNKNOWN.getValue();

	public static final KeyMapping OPEN_SETTINGS = new KeyMapping("key.kwamster.open_settings", GLFW.GLFW_KEY_K, CATEGORY);
	public static final KeyMapping EDIT_HUD = new KeyMapping("key.kwamster.edit_hud", UNBOUND, CATEGORY);
	public static final KeyMapping TOGGLE_HUD = new KeyMapping("key.kwamster.toggle_hud", UNBOUND, CATEGORY);
	public static final KeyMapping SAVED_FIGHTS = new KeyMapping("key.kwamster.saved_fights", UNBOUND, CATEGORY);
	/** Its bound key's display name appears in the summary card footer (§7.9). */
	public static final KeyMapping COPY_LAST_LINK = new KeyMapping("key.kwamster.copy_last_link", UNBOUND, CATEGORY);
	public static final KeyMapping TOGGLE_CROSSHAIR = new KeyMapping("key.kwamster.toggle_crosshair", UNBOUND, CATEGORY);

	private static boolean registered;

	private Keybinds() {
	}

	/** Registers every key with Fabric. Called once, from the initializer (§2.1 step 3). */
	public static void register() {
		if (registered) {
			return;
		}
		registered = true;
		KeyBindingHelper.registerKeyBinding(OPEN_SETTINGS);
		KeyBindingHelper.registerKeyBinding(EDIT_HUD);
		KeyBindingHelper.registerKeyBinding(TOGGLE_HUD);
		KeyBindingHelper.registerKeyBinding(SAVED_FIGHTS);
		KeyBindingHelper.registerKeyBinding(COPY_LAST_LINK);
		KeyBindingHelper.registerKeyBinding(TOGGLE_CROSSHAIR);
	}

	/** END_CLIENT_TICK: drains every key (each press sets a flag), then acts once per key. */
	public static void handle(Minecraft mc) {
		boolean openSettings = drain(OPEN_SETTINGS);
		boolean editHud = drain(EDIT_HUD);
		boolean toggleHud = drain(TOGGLE_HUD);
		boolean savedFights = drain(SAVED_FIGHTS);
		boolean copyLink = drain(COPY_LAST_LINK);
		boolean toggleCrosshair = drain(TOGGLE_CROSSHAIR);

		boolean noScreen = mc.screen == null;
		if (openSettings && noScreen) {
			Kwamster.services().screens().open(ScreenRegistry.ScreenId.SETTINGS, null);
			return;
		}
		if (!Kwamster.config().general.enabled) {
			return;
		}
		if (editHud && noScreen) {
			Kwamster.services().screens().open(ScreenRegistry.ScreenId.HUD_EDITOR, null);
			return;
		}
		if (savedFights && noScreen) {
			Kwamster.services().screens().open(ScreenRegistry.ScreenId.SAVED_FIGHTS, null);
			return;
		}
		if (toggleHud) {
			var general = Kwamster.config().general;
			general.hudHidden = !general.hudHidden;
			Kwamster.saveConfig();
			actionBar(mc, general.hudHidden ? "KWAMSTER HUD hidden" : "KWAMSTER HUD shown");
		}
		if (toggleCrosshair) {
			var crosshair = Kwamster.config().crosshair;
			crosshair.enabled = !crosshair.enabled;
			Kwamster.saveConfig();
			actionBar(mc, crosshair.enabled ? "Custom crosshair ON" : "Custom crosshair OFF");
		}
		if (copyLink) {
			copyLastLink(mc);
		}
	}

	private static void copyLastLink(Minecraft mc) {
		FightRecord last = Kwamster.fights().lastRecord();
		if (last == null) {
			actionBar(mc, "No fight to share yet");
			return;
		}
		ShareLink link = Kwamster.services().archive().shareLink(last);
		if (link == null) {
			actionBar(mc, "Share link unavailable (check the viewer URL in settings)");
			return;
		}
		mc.keyboardHandler.setClipboard(link.url());
		actionBar(mc, "Fight link copied (" + link.length() + " chars)");
	}

	private static boolean drain(KeyMapping key) {
		boolean pressed = false;
		while (key.consumeClick()) {
			pressed = true;
		}
		return pressed;
	}

	private static void actionBar(Minecraft mc, String text) {
		if (mc.player != null) {
			mc.player.displayClientMessage(Component.literal(text), true);
		}
	}
}
