package kwamster.capture;

import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Guarded attribution of damage events that name no source at all. Minecraft-free: the caller turns the
 * level into {@link Candidate}s and passes every time and velocity in.
 *
 * <p>Servers older than 1.19.4 have no damage-event packet; they broadcast only "entity hurt" (status 2).
 * ViaVersion (also inside ViaFabricPlus and ViaProxy) rewrites that status into a damage event of type
 * {@code generic} with no cause, no direct entity and no position, so on those servers (most 1.8 practice
 * and duel servers) no hit would ever name its attacker. Modern servers always send real sources and never
 * reach this class: CAPTURE uses it only for events whose cause, direct entity and position are all absent
 * and whose type is {@code generic}.
 *
 * <ul>
 *   <li><b>Hit taken.</b> The attacker is inferred: a player within {@value #MAX_ATTACKER_REACH} blocks
 *       (eye to your hitbox: 3.0 server reach plus interpolation slack) whose arm swing started in the last
 *       {@value #MAX_SWING_AGE_TICKS} ticks. The current fight opponent wins if they qualify; otherwise the
 *       nearest qualifying player. No candidate: the event is dropped (fall, fire and cactus damage arrive
 *       the same way). While you burn or are poisoned or withered, whose periodic ticks arrive exactly like
 *       melee hits, an inferred hit also needs the knockback a melee hit leaves ({@link #showsKnockback}).</li>
 *   <li><b>Confirmation.</b> Damage on another player counts as your melee hit only if a landed swing of
 *       yours on them that was judged registered (not NO-REG) is old enough to have come back (at least
 *       {@link #minConfirmAgeMs}) and at most {@code ping + }{@value #CONFIRM_SLACK_MS} ms old. Each such swing
 *       confirms one event at most ({@link #claimOwnHit} spends it). A NO-REG swing never produces a damage
 *       event, so letting it confirm one would hand you every fire tick, arrow and teammate hit that lands
 *       while you spam-click.</li>
 * </ul>
 *
 * <p>Not thread-safe; CAPTURE calls it on the client thread only.
 */
public final class SourcelessDamage {
	/** Attacker eye to your hitbox, in blocks. */
	public static final double MAX_ATTACKER_REACH = 4.5;
	/** An arm swing at most this many ticks old ({@code swingTime} runs -1, 0, 1, ...) counts as the hit's swing. */
	public static final int MAX_SWING_AGE_TICKS = 2;
	/** Confirmation window slack on top of the ping (the §5.9 confirmation window). */
	public static final long CONFIRM_SLACK_MS = 300L;
	/** One game tick, ms. */
	public static final int TICK_MS = 50;
	/**
	 * Velocity (blocks per tick) a melee knockback leaves away from the attacker: vanilla sets about 0.4 on
	 * top of half the previous motion, so a real hit clears this with a wide margin, while the velocity
	 * packet a fire, poison or wither tick triggers carries only the server's residual motion of you.
	 */
	public static final double KNOCKBACK_MIN_SPEED = 0.15;
	/** A velocity change smaller than this on every axis means no velocity packet arrived. */
	static final double MOTION_EPSILON = 1e-6;
	private static final long NANOS_PER_MS = 1_000_000L;
	/** Registered swings older than this are forgotten. */
	private static final long FORGET_NANOS = 5_000L * NANOS_PER_MS;

	/**
	 * A player who might have dealt a sourceless hit.
	 *
	 * @param uuid      the player
	 * @param reach     their eye to your hitbox, blocks
	 * @param swinging  their arm swing is running ({@code LivingEntity.swinging})
	 * @param swingTime ticks into that swing ({@code LivingEntity.swingTime}; -1 right after it started)
	 */
	public record Candidate(UUID uuid, double reach, boolean swinging, int swingTime) {
	}

	/** Per victim: {@code Clock.nanos()} of your last landed swing judged registered, not yet spent. */
	private final Map<UUID, Long> lastRegistered = new HashMap<>();

	/** True if an arm swing that is this far along started recently enough to be the hit's swing. */
	public static boolean swungRecently(boolean swinging, int swingTime) {
		return swinging && swingTime <= MAX_SWING_AGE_TICKS;
	}

	/**
	 * Picks the attacker of a sourceless hit taken.
	 *
	 * @param candidates every non-NPC, alive, non-spectator player other than you
	 * @param preferred  the current fight opponent, or null
	 * @return the index of the inferred attacker in {@code candidates}, or -1 if nobody qualifies
	 */
	public static int pickAttacker(List<Candidate> candidates, @Nullable UUID preferred) {
		int best = -1;
		double bestReach = Double.POSITIVE_INFINITY;
		for (int i = 0; i < candidates.size(); i++) {
			Candidate c = candidates.get(i);
			if (c == null || !qualifies(c)) {
				continue;
			}
			if (preferred != null && preferred.equals(c.uuid())) {
				return i;
			}
			if (c.reach() < bestReach) {
				bestReach = c.reach();
				best = i;
			}
		}
		return best;
	}

	/**
	 * Whether your velocity shows a melee knockback from the inferred attacker: it changed between the end of
	 * the last tick and now (only packets can change it in between, so a velocity packet arrived; the
	 * status and the velocity may come in either order), and the new horizontal velocity points away from
	 * the attacker by at least {@value #KNOCKBACK_MIN_SPEED} blocks per tick. With the attacker right on top
	 * of you (no usable direction), any horizontal speed of that size counts.
	 *
	 * @param beforeX your velocity at the end of the last tick (x, y, z)
	 * @param afterX  your velocity now
	 * @param awayX   horizontal direction from the attacker to you (x, z), any length
	 */
	public static boolean showsKnockback(double beforeX, double beforeY, double beforeZ,
			double afterX, double afterY, double afterZ, double awayX, double awayZ) {
		if (!Double.isFinite(afterX) || !Double.isFinite(afterY) || !Double.isFinite(afterZ)) {
			return false;
		}
		boolean changed = !(Math.abs(afterX - beforeX) <= MOTION_EPSILON && Math.abs(afterY - beforeY) <= MOTION_EPSILON
				&& Math.abs(afterZ - beforeZ) <= MOTION_EPSILON);
		if (!changed) {
			return false;
		}
		double len = Math.hypot(awayX, awayZ);
		if (!(len >= 1e-3)) {
			return Math.hypot(afterX, afterZ) >= KNOCKBACK_MIN_SPEED;
		}
		return (afterX * awayX + afterZ * awayZ) / len >= KNOCKBACK_MIN_SPEED;
	}

	/**
	 * The youngest a damage event can be and still be your own hit coming back, in ms: the attack has to
	 * reach the server and the event has to come back, about one round trip. Half the tab-list ping, and at
	 * most {@code ping - 50}, so a stale or averaged-high tab-list ping never rejects your own hits.
	 */
	public static long minConfirmAgeMs(int pingMs) {
		long p = Math.max(0, pingMs);
		return Math.max(0L, Math.min(p - TICK_MS, p / 2));
	}

	/**
	 * A landed swing of yours on {@code victim} that the NO-REG estimator judged registered (a swing into
	 * hurt immunity never produces a damage event, so it is not remembered). Replaces any older one.
	 */
	public void onRegisteredSwing(UUID victim, long nanos) {
		if (victim == null) {
			return;
		}
		if (this.lastRegistered.size() >= 16) {
			Iterator<Long> it = this.lastRegistered.values().iterator();
			while (it.hasNext()) {
				if (nanos - it.next() > FORGET_NANOS) {
					it.remove();
				}
			}
		}
		this.lastRegistered.put(victim, nanos);
	}

	/**
	 * True if a sourceless damage event on {@code victim} at {@code nowNanos} can be taken for your melee hit:
	 * your last registered swing on them is between {@link #minConfirmAgeMs} and
	 * {@code pingMs + }{@value #CONFIRM_SLACK_MS} ms old. A match spends that swing, so it confirms no
	 * second event; an event that does not match leaves it for the real one.
	 */
	public boolean claimOwnHit(UUID victim, long nowNanos, int pingMs) {
		Long swing = victim == null ? null : this.lastRegistered.get(victim);
		if (swing == null) {
			return false;
		}
		long age = nowNanos - swing;
		if (age < minConfirmAgeMs(pingMs) * NANOS_PER_MS || age > (Math.max(0, pingMs) + CONFIRM_SLACK_MS) * NANOS_PER_MS) {
			return false;
		}
		this.lastRegistered.remove(victim);
		return true;
	}

	/** Forgets every remembered swing (world leave, master switch off). */
	public void reset() {
		this.lastRegistered.clear();
	}

	private static boolean qualifies(Candidate c) {
		return c.uuid() != null && Double.isFinite(c.reach()) && c.reach() <= MAX_ATTACKER_REACH
				&& swungRecently(c.swinging(), c.swingTime());
	}
}
