package kwamster.capture;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Decides whether a landed swing went into the server's hurt immunity ("NO-REG", §5.4) without trusting
 * the client's copy of the victim's {@code invulnerableTime} for your own hits. Minecraft-free: every input
 * is a client tick index, a timer value and a ping, and it never reads a clock itself.
 *
 * <p><b>Why not the client copy.</b> The server restarts the victim's timer (to 20) when it handles the
 * attack packet and ignores further hits while it is above 10 (LivingEntity.hurtServer). The client copy is
 * set to 20 only when the damage event comes back, a full round trip later, and counts down after
 * {@code startAttack} in each tick. Compared raw with 10 it is always late in the NO-REG direction: at
 * ordinary ping every charged hit thrown 10–12 ticks after the last one reads as NO-REG although it
 * registered, and spam clicks inside one round trip of a fresh hit read as registered.
 *
 * <p><b>Primary rule (your own hits, latency-independent).</b> Per victim, remember the client tick of
 * your last landed swing that was judged registered. A new landed swing is NO-REG when it comes fewer than
 * {@code immunityTicks} ticks after that one. Both attack packets travel the same path, so the server sees
 * the same gap between them and steady latency cancels out. Two swings in the same tick give a gap of 0
 * and the second one is flagged. Only a swing that is not flagged becomes the new anchor.
 *
 * <p><b>Secondary rule (someone else's damage).</b> When the victim's last damage event was not your own
 * melee hit (another player's hit, your arrow, fall or fire damage, ...), the server restarted the timer at
 * that damage, which the primary rule cannot see. The client copy is then the only evidence; it lags the
 * server by about one round trip plus a tick, so the swing is NO-REG when the estimated server-side age of
 * that damage, {@code (20 - copy) + lagTicks(ping)}, is below {@code immunityTicks}. This rule does not run
 * when the last damage event was your own melee hit coming back from the current anchor, because the
 * primary rule already tracks that hit more precisely than the lagged copy can. Damage that is yours but
 * not the anchor's (a sweep that caught a bystander you never hit, or arrived long after your last
 * registered hit on them) restarted the timer where the primary rule cannot see it, so it counts as
 * someone else's: it must arrive within {@code lagTicks(ping) + }{@value #OWN_DAMAGE_SLACK_TICKS} ticks of
 * the anchor to be the anchor's.
 *
 * <p>{@code immunityTicks <= 0} disables NO-REG detection entirely (servers with a reduced hit delay).
 *
 * <p>Not thread-safe; CAPTURE calls it on the client thread only.
 */
public final class NoRegEstimator {
	/** Vanilla: the server ignores a hit while the timer is above 10, i.e. for 10 ticks after a registered hit. */
	public static final int DEFAULT_IMMUNITY_TICKS = 10;
	/** The value both sides set {@code invulnerableTime} to on damage. */
	public static final int HURT_TICKS = 20;
	/** Game time of one tick. */
	public static final int TICK_MS = 50;
	/** A victim untouched for this many ticks is forgotten (well past any immunity window). */
	static final long FORGET_TICKS = 200L;
	/**
	 * Slack on top of {@link #lagTicks} for your own damage event to come back from the anchor swing: 300 ms,
	 * the confirmation window's slack (§5.9). A registered anchor keeps the victim immune for
	 * {@code immunityTicks}, so no other damage of yours can arrive this soon after it.
	 */
	public static final int OWN_DAMAGE_SLACK_TICKS = 6;

	private final Map<UUID, Victim> victims = new HashMap<>();

	/** What is known about one victim. */
	private static final class Victim {
		/** A registered swing of yours exists ({@link #anchorTick} is valid). */
		boolean hasAnchor;
		/** Client tick of your last landed swing judged registered. */
		long anchorTick;
		/** The most recent damage event on this victim was your own melee hit coming back from the anchor. */
		boolean lastDamageFromAnchor;
		/** Last tick this entry was used (for forgetting). */
		long touched;
	}

	/**
	 * A landed swing on {@code victim}, at the landed hook ({@code MultiPlayerGameMode.attack} HEAD).
	 *
	 * @param victim                 the victim's UUID
	 * @param tick                   {@code Clock.ticks()} at the hook
	 * @param clientInvulnerableTime the client's copy of the victim's {@code invulnerableTime} (0 if not a living entity)
	 * @param pingMs                 own round-trip latency in ms (0 if unknown)
	 * @param immunityTicks          the server's immunity window in ticks ({@value #DEFAULT_IMMUNITY_TICKS} in vanilla); 0 or less disables detection
	 * @return true if the swing is NO-REG
	 */
	public boolean onLandedSwing(UUID victim, long tick, int clientInvulnerableTime, int pingMs, int immunityTicks) {
		if (victim == null || immunityTicks <= 0) {
			return false;
		}
		prune(tick);
		Victim v = this.victims.computeIfAbsent(victim, k -> new Victim());
		v.touched = tick;
		boolean flagged = false;
		if (v.hasAnchor && tick >= v.anchorTick && tick - v.anchorTick < immunityTicks) {
			flagged = true;
		} else if (!v.lastDamageFromAnchor && clientInvulnerableTime > 0) {
			int sinceDamage = Math.max(0, HURT_TICKS - clientInvulnerableTime);
			flagged = (long) sinceDamage + lagTicks(pingMs) < immunityTicks;
		}
		if (!flagged) {
			v.hasAnchor = true;
			v.anchorTick = tick;
		}
		return flagged;
	}

	/**
	 * A server damage event on {@code victim} arrived (any source). It is the anchor's own damage, which the
	 * primary rule already covers, only when it is your melee hit and arrives no later than
	 * {@code lagTicks(ping) + }{@value #OWN_DAMAGE_SLACK_TICKS} ticks after the anchor swing; anything else
	 * (including your sweep on a bystander) restarted the timer unseen and arms the secondary rule.
	 *
	 * @param victim    the victim's UUID
	 * @param ownMelee  it was your own melee hit (sourced, or inferred on a sourceless server); false for
	 *                  anything else, including your projectiles and thorns
	 * @param tick      {@code Clock.ticks()} when it arrived
	 * @param pingMs    own round-trip latency in ms (0 if unknown)
	 */
	public void onDamage(UUID victim, boolean ownMelee, long tick, int pingMs) {
		if (victim == null) {
			return;
		}
		prune(tick);
		Victim v = this.victims.computeIfAbsent(victim, k -> new Victim());
		v.touched = tick;
		v.lastDamageFromAnchor = ownMelee && v.hasAnchor && tick >= v.anchorTick
				&& tick - v.anchorTick <= (long) lagTicks(pingMs) + OWN_DAMAGE_SLACK_TICKS;
	}

	/** Forgets everything (world leave, master switch off). */
	public void reset() {
		this.victims.clear();
	}

	/** Number of victims currently tracked (tests). */
	int tracked() {
		return this.victims.size();
	}

	/**
	 * How many ticks the client copy of a timer lags the server's: one round trip (rounded to ticks) plus
	 * one tick, because the server handles packets at the start of a tick and flushes at its end.
	 */
	public static int lagTicks(int pingMs) {
		return 1 + (int) Math.round(Math.max(0, pingMs) / (double) TICK_MS);
	}

	private void prune(long now) {
		if (this.victims.size() < 8) {
			return;
		}
		Iterator<Victim> it = this.victims.values().iterator();
		while (it.hasNext()) {
			if (now - it.next().touched > FORGET_TICKS) {
				it.remove();
			}
		}
	}
}
