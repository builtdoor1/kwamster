package kwamster;

import kwamster.core.Services;
import kwamster.core.TestHooks;
import kwamster.core.config.ConfigStore;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.EventBus;
import kwamster.core.fight.FightManager;
import kwamster.core.service.FightService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.Objects;

/**
 * The static facade every unit uses to reach CORE: the config, the event bus, the fight service, the
 * other units' services, the gametest hooks, the mod version and the data folder.
 *
 * <p>Installed by {@link KwamsterClient} at the start of client init (§2.1). Before that, the
 * accessors return inert placeholders (default config, an empty bus), so nothing ever sees null.
 * Client thread, except {@link #config()} reads, which are safe from any thread (every thread but the
 * render thread inside {@link #withPreviewConfig} gets the live config).
 */
public final class Kwamster {
	private static volatile KwamsterConfig config = KwamsterConfig.defaults();
	private static volatile EventBus events = new EventBus();
	private static volatile Services services = new Services();
	private static volatile FightManager fights = new FightManager(events);
	private static volatile TestHooks testHooks = new TestHooks(fights, events);
	private static volatile String modVersion;

	/**
	 * The override of {@link #withPreviewConfig}, set only by the render thread and only while its draw
	 * runs. Plain fields on purpose, because {@link #config()} runs many times a frame: only the thread
	 * recorded in {@link #previewThread} can match, and that is the render thread, which wrote both, so
	 * a stale read on any other thread still falls through to the live config.
	 */
	private static @Nullable KwamsterConfig previewConfig;
	private static @Nullable Thread previewThread;

	private Kwamster() {
	}

	/**
	 * The live config. Fetch it every frame or tick; never cache a field. Inside
	 * {@link #withPreviewConfig} the render thread gets the preview config instead.
	 */
	public static KwamsterConfig config() {
		KwamsterConfig preview = previewConfig;
		if (preview != null && previewThread == Thread.currentThread()) {
			return preview;
		}
		return config;
	}

	/**
	 * Runs {@code draw} with {@link #config()} returning {@code preview} on the render thread, and the
	 * live config everywhere else: on other threads, and on this one again as soon as {@code draw}
	 * returns or throws. This lets the HUD presets screen draw the real HUD elements, which read
	 * {@code config()} while they render, with a look that is not applied yet. Calls nest (the outer
	 * override comes back afterwards). Nothing is saved and the live config is never replaced:
	 * {@link #saveConfig()} still writes the live one.
	 *
	 * @throws IllegalStateException off the render thread, where the override could never be seen
	 */
	public static void withPreviewConfig(KwamsterConfig preview, Runnable draw) {
		Objects.requireNonNull(preview, "preview");
		Objects.requireNonNull(draw, "draw");
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || !mc.isSameThread()) {
			throw new IllegalStateException("Kwamster.withPreviewConfig must run on the render thread");
		}
		KwamsterConfig outerConfig = previewConfig;
		Thread outerThread = previewThread;
		previewConfig = preview;
		previewThread = Thread.currentThread();
		try {
			draw.run();
		} finally {
			previewConfig = outerConfig;
			previewThread = outerThread;
		}
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
