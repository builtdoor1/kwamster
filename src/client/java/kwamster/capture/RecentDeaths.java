package kwamster.capture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Players whose death (entity event 3) was seen in the last {@value #WINDOW_MS} ms, so clicks on their body
 * are not taken for a new fight. Minecraft-free: the body is kept as an opaque instance and compared by
 * identity, and every time is passed in.
 *
 * <p>A dead player's body stays in the client level, pickable, until the server removes it about a second
 * later ({@code LivingEntity.isPickable()} is only {@code !isRemoved()}), so a spam click right after a kill
 * reaches {@code MultiPlayerGameMode.attack}. Most servers sync health 0 and the alive check catches that,
 * but some spoof other players' health, so the death signal itself is remembered too. A respawn always
 * arrives as a new entity instance (the server re-tracks the new player), so a respawned player is never
 * mistaken for their own body, even inside the window.
 *
 * <p>Not thread-safe; CAPTURE calls it on the client thread only.
 */
public final class RecentDeaths {
	/** How long a death is remembered. */
	public static final long WINDOW_MS = 2000L;
	private static final long WINDOW_NANOS = WINDOW_MS * 1_000_000L;

	/** One remembered death. */
	private record Death(long nanos, Object body) {
	}

	private final Map<UUID, Death> deaths = new HashMap<>();

	/**
	 * Entity event 3 arrived for {@code who}.
	 *
	 * @param who   the player's UUID
	 * @param body  the entity instance that died (compared by identity)
	 * @param nanos {@code Clock.nanos()} at the event
	 */
	public void record(UUID who, Object body, long nanos) {
		if (who == null || body == null) {
			return;
		}
		expire(nanos);
		this.deaths.put(who, new Death(nanos, body));
	}

	/**
	 * True if {@code instance} is the body of {@code who} and the death is younger than {@value #WINDOW_MS} ms.
	 *
	 * @param who      the player's UUID
	 * @param instance the entity instance being clicked
	 * @param nowNanos {@code Clock.nanos()} now
	 */
	public boolean isCorpse(UUID who, Object instance, long nowNanos) {
		if (who == null) {
			return false;
		}
		Death d = this.deaths.get(who);
		if (d == null) {
			return false;
		}
		if (nowNanos - d.nanos() >= WINDOW_NANOS) {
			this.deaths.remove(who);
			return false;
		}
		return d.body() == instance;
	}

	/** Forgets every death (world leave, master switch off). */
	public void reset() {
		this.deaths.clear();
	}

	/** Drops deaths older than the window, so no body instance is held longer than needed. */
	public void expire(long nowNanos) {
		if (this.deaths.isEmpty()) {
			return;
		}
		Iterator<Death> it = this.deaths.values().iterator();
		while (it.hasNext()) {
			if (nowNanos - it.next().nanos() >= WINDOW_NANOS) {
				it.remove();
			}
		}
	}
}
