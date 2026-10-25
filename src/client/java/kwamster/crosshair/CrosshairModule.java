package kwamster.crosshair;

import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;

/**
 * The CROSSHAIR unit's module (order 500). The crosshair needs no registration: it is driven
 * entirely by {@code GuiCrosshairMixin}, which hands vanilla's crosshair sprites to
 * {@link CrosshairRenderer}. The module exists so every unit appears in the module list.
 */
public final class CrosshairModule implements KwamsterModule {
	@Override
	public int order() {
		return 500;
	}

	@Override
	public void init() {
		KwamsterClient.LOG.debug("Crosshair module ready (drawn through GuiCrosshairMixin)");
	}
}
