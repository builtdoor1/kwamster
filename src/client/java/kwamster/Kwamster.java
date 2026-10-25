package kwamster;

import kwamster.core.Services;
import kwamster.core.TestHooks;
import kwamster.core.config.ConfigStore;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.EventBus;
import kwamster.core.fight.FightManager;
import kwamster.core.service.FightService;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Path;

/**
 * The static facade every unit uses to reach CORE: the config, the event bus, the fight service, the
 * other units' services, the gametest hooks, the mod version and the data folder.
 *
 * <p>Installed by {@link KwamsterClient} at the start of client init (§2.1). Before that, the
 * accessors return inert placeholders (default config, an empty bus), so nothing ever sees null.
 * Client thread, except {@link #config()} reads, which are safe from any thread.
 */
public final class Kwamster {
	private static volatile KwamsterConfig config = KwamsterConfig.defaults();
	private static volatile EventBus events = new EventBus();
	private static volatile Services services = new Services();
	private static volatile FightManager fights = new FightManager(events);
	private static volatile TestHooks testHooks = new TestHooks(fights, events);
	private static volatile String modVersion;

	private Kwamster() {
	}

	/** The live config. Fetch it every frame or tick; never cache a field. */
	public static KwamsterConfig config() {
		return config;
	}

	/** Writes the current config to disk ({@code clampAndFill()} first). Failures are logged, never thrown. */
	public static void saveConfig() {
		ConfigStore.save(config);
	}

	/** The KWAMSTER event bus. */
	public static EventBus events() {
		return events;
	}

	/** Live and finished fights, and session totals. */
	public static FightService fights() {
		return fights;
	}

	/** Service slots of the other units (each with a CORE no-op default) and the screen registry. */
	public static Services services() {
		return services;
	}

	/** Gametest hooks; every method must be called on the client thread. */
	public static TestHooks testHooks() {
		return testHooks;
	}

	/** This mod's version from {@code fabric.mod.json}, for example {@code 1.0.0+1.21.11}. */
	public static String modVersion() {
		String v = modVersion;
		if (v == null) {
			v = FabricLoader.getInstance().getModContainer(KwamsterClient.MOD_ID)
					.map(c -> c.getMetadata().getVersion().getFriendlyString())
					.orElse("dev");
			modVersion = v;
		}
		return v;
	}

	/** {@code FabricLoader configDir/kwamster}: config, fights and lifetime files (created on demand). */
	public static Path dataDir() {
		return ConfigStore.dir();
	}

	/** Called by {@link KwamsterClient} once the config is loaded (§2.1 step 1). */
	static void installConfig(KwamsterConfig cfg) {
		config = cfg == null ? KwamsterConfig.defaults() : cfg;
	}

	/** Called by {@link KwamsterClient} with the CORE objects of §2.1 step 2. */
	static void install(EventBus bus, Services svc, FightManager fightManager, TestHooks hooks) {
		events = bus;
		services = svc;
		fights = fightManager;
		testHooks = hooks;
	}
}
