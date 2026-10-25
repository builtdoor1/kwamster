package kwamster.core;

import kwamster.KwamsterClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Maps a {@link ScreenId} to the factory of the unit that owns that screen. Every unit opens
 * screens only through this registry (reached via {@code Kwamster.services().screens()}).
 *
 * <p>The optional {@code arg} means: SETTINGS → the initial Cloth category title (§3.12);
 * HUD_EDITOR → a {@code HudElementId.name()} to preselect; SAVED_FIGHTS → ignored. Factories ignore
 * an unknown arg. Client thread.
 */
public final class ScreenRegistry {
	/** The screens KWAMSTER can open. */
	public enum ScreenId { SETTINGS, HUD_EDITOR, SAVED_FIGHTS }

	/** Builds a screen. */
	@FunctionalInterface
	public interface Factory {
		Screen create(@Nullable Screen parent, @Nullable String arg);
	}

	private final Map<ScreenId, Factory> factories = new EnumMap<>(ScreenId.class);

	/** Registers (or replaces) the factory for {@code id}. */
	public void register(ScreenId id, Factory factory) {
		Objects.requireNonNull(id, "id");
		Objects.requireNonNull(factory, "factory");
		Factory previous = this.factories.put(id, factory);
		if (previous != null && previous != factory) {
			KwamsterClient.LOG.warn("Screen factory for {} replaced", id);
		}
	}

	/** True if a factory is registered for {@code id}. */
	public boolean isRegistered(ScreenId id) {
		return this.factories.containsKey(id);
	}

	/** Builds the screen with no argument; null if not registered or the factory failed. */
	public @Nullable Screen create(ScreenId id, @Nullable Screen parent) {
		return create(id, parent, null);
	}

	/** Builds the screen; null if not registered or the factory failed (the failure is logged). */
	public @Nullable Screen create(ScreenId id, @Nullable Screen parent, @Nullable String arg) {
		Factory factory = this.factories.get(id);
		if (factory == null) {
			return null;
		}
		try {
			return factory.create(parent, arg);
		} catch (Throwable t) {
			LogLimiter.error("ScreenRegistry/" + id, "Could not build the " + id + " screen", t);
			return null;
		}
	}

	/** {@code Minecraft.getInstance().setScreen(create(...))}; returns false if not registered or nothing was built. */
	public boolean open(ScreenId id, @Nullable Screen parent) {
		return open(id, parent, null);
	}

	/**
	 * {@code Minecraft.getInstance().setScreen(create(...))}. Returns false if not registered or the
	 * factory produced nothing; the current screen is then left as it is (never replaced by null).
	 */
	public boolean open(ScreenId id, @Nullable Screen parent, @Nullable String arg) {
		Screen screen = create(id, parent, arg);
		if (screen == null) {
			return false;
		}
		Minecraft.getInstance().setScreen(screen);
		return true;
	}
}
