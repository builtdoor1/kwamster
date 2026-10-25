package kwamster.core;

import kwamster.KwamsterClient;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Rate-limited logging for code that can run every frame or tick (R3): each call site logs at most
 * once per {@link #INTERVAL_NANOS}, so a persistent failure cannot flood the log.
 *
 * <p>A call site is identified by any stable string, for example {@code "CombatCapture.onDamage"}.
 * Thread-safe; Minecraft-free apart from the shared logger.
 */
public final class LogLimiter {
	/** Ten seconds. */
	public static final long INTERVAL_NANOS = 10_000_000_000L;

	private static final Map<String, Long> LAST = new ConcurrentHashMap<>();

	private LogLimiter() {
	}

	/** True if {@code site} may log now (and records that it did). */
	public static boolean allow(String site) {
		long now = System.nanoTime();
		boolean[] allowed = {false};
		LAST.compute(site, (k, last) -> {
			if (last == null || now - last >= INTERVAL_NANOS) {
				allowed[0] = true;
				return now;
			}
			return last;
		});
		return allowed[0];
	}

	/** Logs {@code message} with {@code t} at ERROR, at most once per interval for {@code site}. */
	public static void error(String site, String message, Throwable t) {
		if (allow(site)) {
			KwamsterClient.LOG.error("[{}] {}", site, message, t);
		}
	}

	/** Logs {@code message} at WARN, at most once per interval for {@code site}. */
	public static void warn(String site, String message) {
		if (allow(site)) {
			KwamsterClient.LOG.warn("[{}] {}", site, message);
		}
	}
}
