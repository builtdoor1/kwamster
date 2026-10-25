package kwamster.core;

/** One per unit, listed under the "kwamster" entrypoint in fabric.mod.json. Public no-arg constructor. */
public interface KwamsterModule {
	/** Lower runs first. CAPTURE 100, RECORD 200, DIRECTION 300, FEEDBACK 400, CROSSHAIR 500, HUD 600, CONFIGUI 700. */
	int order();

	/** Registers the unit's services, listeners and screen factories. Called once, on the client thread, after CORE is ready. */
	void init();
}
