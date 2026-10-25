package kwamster.config;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import kwamster.configui.ConfigScreenBuilder;

/**
 * Mod Menu's "Config" button opens the KWAMSTER settings screen on its first category. Loaded only
 * when Mod Menu is installed (it reads the {@code modmenu} entrypoint); Cloth Config is a hard
 * dependency, so the screen can always be built.
 */
public final class ModMenuIntegration implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return parent -> ConfigScreenBuilder.build(parent, null);
	}
}
