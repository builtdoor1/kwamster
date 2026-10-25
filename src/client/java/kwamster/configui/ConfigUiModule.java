package kwamster.configui;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;
import kwamster.core.ScreenRegistry;

/**
 * The CONFIGUI unit's entrypoint (order 700): registers the {@code SETTINGS} screen factory, so the
 * K key, Mod Menu and other units open the Cloth settings screen through
 * {@code Kwamster.services().screens()}. The factory's argument is the initial category title
 * (§3.12, §4.4).
 */
public final class ConfigUiModule implements KwamsterModule {
	@Override
	public int order() {
		return 700;
	}

	@Override
	public void init() {
		Kwamster.services().screens().register(ScreenRegistry.ScreenId.SETTINGS, ConfigScreenBuilder::build);
		KwamsterClient.LOG.debug("KWAMSTER settings screen registered");
	}
}
