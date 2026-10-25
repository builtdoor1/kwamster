package kwamster.core.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kwamster.KwamsterClient;
import kwamster.core.Clock;
import kwamster.core.io.AtomicFiles;
import kwamster.core.io.Json;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Loads and saves {@code config/kwamster/config.json} (§3.11).
 *
 * <ul>
 *   <li>A missing file is created with defaults.</li>
 *   <li>An unparsable file is renamed to {@code config.broken-<epochMs>.json}, a warning is logged and
 *       defaults are used (and saved).</li>
 *   <li>Unknown keys are ignored. Missing keys keep their defaults at every level, including inside
 *       individual tiers and layouts: the file is merged over the default JSON tree before binding.</li>
 *   <li>{@code configVersion} is migrated forward when it changes (v1 needs no migration).</li>
 * </ul>
 */
public final class ConfigStore {
	private static final String FILE_NAME = "config.json";

	private ConfigStore() {
	}

	/** {@code FabricLoader configDir/kwamster}, created on demand. */
	public static Path dir() {
		Path dir = FabricLoader.getInstance().getConfigDir().resolve("kwamster");
		try {
			Files.createDirectories(dir);
		} catch (IOException e) {
			KwamsterClient.LOG.warn("Could not create {}", dir, e);
		}
		return dir;
	}

	/** Reads the config. Never throws: on any failure it returns (and tries to save) defaults. */
	public static KwamsterConfig load() {
		try {
			Path file = dir().resolve(FILE_NAME);
			if (!Files.exists(file)) {
				KwamsterConfig defaults = KwamsterConfig.defaults();
				save(defaults);
				return defaults;
			}
			String text;
			try {
				text = Files.readString(file, StandardCharsets.UTF_8);
			} catch (CharacterCodingException e) {
				return recoverBroken(file, e); // not UTF-8 text: as broken as bad JSON
			} catch (IOException e) {
				// Unreadable right now (locked, permissions): use defaults for this session but leave the file alone.
				KwamsterClient.LOG.warn("config.json could not be read; using defaults without touching the file", e);
				return KwamsterConfig.defaults();
			}
			KwamsterConfig cfg;
			try {
				cfg = parse(text);
			} catch (RuntimeException e) {
				return recoverBroken(file, e);
			}
			boolean migrated = migrate(cfg);
			cfg.clampAndFill();
			if (migrated) {
				save(cfg);
			}
			return cfg;
		} catch (Throwable t) {
			KwamsterClient.LOG.error("Could not load the KWAMSTER config; using defaults", t);
			return KwamsterConfig.defaults();
		}
	}

	/** {@code clampAndFill()} then an atomic pretty-JSON write. Never throws; failures are logged. */
	public static synchronized void save(KwamsterConfig cfg) {
		if (cfg == null) {
			return;
		}
		try {
			cfg.clampAndFill();
			AtomicFiles.writeString(dir().resolve(FILE_NAME), Json.PRETTY.toJson(cfg));
		} catch (Throwable t) {
			KwamsterClient.LOG.error("Could not save the KWAMSTER config", t);
		}
	}

	/**
	 * Parses the file text merged over the default tree.
	 *
	 * @throws RuntimeException if the text is not a JSON object or does not bind
	 */
	private static KwamsterConfig parse(String text) {
		JsonElement user = JsonParser.parseString(text);
		if (user == null || !user.isJsonObject()) {
			throw new IllegalStateException("config.json is not a JSON object");
		}
		JsonObject defaults = Json.PRETTY.toJsonTree(KwamsterConfig.defaults()).getAsJsonObject();
		KwamsterConfig cfg = Json.GSON.fromJson(merge(defaults, user.getAsJsonObject()), KwamsterConfig.class);
		if (cfg == null) {
			throw new IllegalStateException("config.json is empty");
		}
		return cfg;
	}

	private static KwamsterConfig recoverBroken(Path file, Exception cause) {
		try {
			Path backup = AtomicFiles.backupBroken(file, Clock.epochMs());
			KwamsterClient.LOG.warn("config.json could not be read ({}); moved it to {} and restored defaults",
					cause.getMessage(), backup.getFileName());
		} catch (IOException e) {
			KwamsterClient.LOG.warn("config.json could not be read ({}) nor moved aside; restoring defaults", cause.getMessage(), e);
		}
		KwamsterConfig defaults = KwamsterConfig.defaults();
		save(defaults);
		return defaults;
	}

	/** Brings an older file forward. Returns true if anything changed and the file should be rewritten. */
	private static boolean migrate(KwamsterConfig cfg) {
		if (cfg.configVersion > KwamsterConfig.CURRENT_VERSION) {
			KwamsterClient.LOG.warn("config.json was written by a newer KWAMSTER (configVersion {}); reading it as version {}",
					cfg.configVersion, KwamsterConfig.CURRENT_VERSION);
			return false;
		}
		if (cfg.configVersion < KwamsterConfig.CURRENT_VERSION) {
			// No migration steps exist yet: v1 is the first released format.
			cfg.configVersion = KwamsterConfig.CURRENT_VERSION;
			return true;
		}
		return false;
	}

	/** Deep merge: objects merge key by key, anything else in {@code over} replaces {@code base}. */
	private static JsonObject merge(JsonObject base, JsonObject over) {
		JsonObject out = base.deepCopy();
		for (Map.Entry<String, JsonElement> e : over.entrySet()) {
			JsonElement b = out.get(e.getKey());
			JsonElement o = e.getValue();
			if (b != null && b.isJsonObject() && o != null && o.isJsonObject()) {
				out.add(e.getKey(), merge(b.getAsJsonObject(), o.getAsJsonObject()));
			} else {
				out.add(e.getKey(), o == null ? JsonNull.INSTANCE : o.deepCopy());
			}
		}
		return out;
	}
}
