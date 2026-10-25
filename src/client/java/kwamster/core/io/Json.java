package kwamster.core.io;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

/**
 * Shared Gson instances. {@link #GSON} is compact (fight files), {@link #PRETTY} is indented
 * (config and lifetime totals).
 *
 * <p>Neither enables {@code serializeSpecialFloatingPointValues}, so Gson throws on NaN or Infinity:
 * every float stored in a record is sanitised first (§4.9). HTML escaping is off so URLs and names
 * stay readable in the files. Minecraft-free; both instances are thread-safe.
 */
public final class Json {
	/** Compact output. */
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
	/** Pretty-printed output. */
	public static final Gson PRETTY = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting().create();

	private Json() {
	}
}
