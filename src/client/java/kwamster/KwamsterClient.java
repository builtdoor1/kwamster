package kwamster;

import kwamster.core.Clock;
import kwamster.core.Keybinds;
import kwamster.core.KwamsterModule;
import kwamster.core.LogLimiter;
import kwamster.core.Services;
import kwamster.core.TestHooks;
import kwamster.core.config.ConfigStore;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.EventBus;
import kwamster.core.fight.FightManager;
import kwamster.core.model.FightEndReason;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.EntrypointContainer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * KWAMSTER's client entrypoint. Runs the init order of §2.1: load the config, create the CORE
 * objects and register CORE's Fabric lifecycle listeners (before any module, so they run first),
 * register the keys, load the unit modules from the {@code "kwamster"} entrypoint (each isolated in
 * its own try/catch), then hook the end of every client tick.
 */
public final class KwamsterClient implements ClientModInitializer {
	public static final String MOD_ID = "kwamster";
	public static final Logger LOG = LoggerFactory.getLogger("KWAMSTER");

	@Override
	public void onInitializeClient() {
		// 1. config
		KwamsterConfig cfg = ConfigStore.load();
		Kwamster.installConfig(cfg);

		// 2. CORE objects, then CORE's lifecycle listeners before any module registers its own
		EventBus bus = new EventBus();
		Services services = new Services();
		FightManager fightManager = new FightManager(bus);
		TestHooks testHooks = new TestHooks(fightManager, bus);
		bus.registerCore(fightManager);
		Kwamster.install(bus, services, fightManager, testHooks);

		ClientTickEvents.START_CLIENT_TICK.register(mc -> Clock.onTickStart());
		// May run on a Netty IO thread (R2a): set a flag and nothing else. No mc.execute hop, because
		// Minecraft.disconnect drops queued tasks right before closing the connection.
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> fightManager.markDisconnectPending());
		// The only CLIENT_STOPPING listener in the mod: finalise synchronously, then drain RECORD's IO.
		ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> {
			try {
				fightManager.forceEnd(FightEndReason.GAME_EXIT);
			} catch (Throwable t) {
				LOG.error("Could not finalise the running fight on exit", t);
			}
			try {
				Kwamster.services().archive().shutdown();
			} catch (Throwable t) {
				LOG.error("Could not shut down the fight archive", t);
			}
		});

		// 3. keys
		Keybinds.register();

		// 4. unit modules
		int modules = loadModules();

		// 5. end of every client tick (also ticks with no level, which finish a fight after a disconnect)
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			try {
				Keybinds.handle(mc);
			} catch (Throwable t) {
				LogLimiter.error("Keybinds.handle", "Key handling failed", t);
			}
			try {
				bus.postTick(mc);
			} catch (Throwable t) {
				LogLimiter.error("EventBus.postTick", "Tick dispatch failed", t);
			}
		});

		// 6.
		LOG.info("KWAMSTER {} ready ({} modules)", Kwamster.modVersion(), modules);
	}

	/**
	 * Instantiates every {@code "kwamster"} entrypoint, sorts by {@link KwamsterModule#order()} and calls
	 * {@code init()} on each. A module that fails to load or initialise is logged and skipped; the rest
	 * continue. Returns the number initialised.
	 */
	private static int loadModules() {
		List<KwamsterModule> loaded = new ArrayList<>();
		List<Integer> orders = new ArrayList<>();
		List<EntrypointContainer<KwamsterModule>> containers;
		try {
			containers = FabricLoader.getInstance().getEntrypointContainers(MOD_ID, KwamsterModule.class);
		} catch (Throwable t) {
			LOG.error("Could not read the KWAMSTER module list", t);
			return 0;
		}
		for (EntrypointContainer<KwamsterModule> container : containers) {
			try {
				KwamsterModule module = container.getEntrypoint();
				int order = module.order();
				loaded.add(module);
				orders.add(order);
			} catch (Throwable t) {
				LOG.error("KWAMSTER module from {} could not be loaded; skipping it",
						container.getProvider().getMetadata().getId(), t);
			}
		}
		List<Integer> indices = new ArrayList<>();
		for (int i = 0; i < loaded.size(); i++) {
			indices.add(i);
		}
		indices.sort(Comparator.comparingInt(orders::get));
		int ok = 0;
		for (int i : indices) {
			KwamsterModule module = loaded.get(i);
			try {
				module.init();
				ok++;
				LOG.debug("KWAMSTER module {} (order {}) initialised", module.getClass().getSimpleName(), orders.get(i));
			} catch (Throwable t) {
				LOG.error("KWAMSTER module {} failed to initialise; skipping it", module.getClass().getName(), t);
			}
		}
		return ok;
	}
}
