package kwamster.capture;

import kwamster.Kwamster;
import kwamster.core.event.DamageObservedEvent;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.PlayerRef;
import kwamster.core.service.FightService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Player;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * How much damage a hit actually did, read off health rather than guessed (port of combat-report
 * {@code HealthWatch}). A damage event says a hit happened but not what it was worth, so the amount is
 * recovered by watching health fall. Health is synced entity data, so this works for opponents too,
 * unless the server hides other players' health; then their health never changes and the damage
 * figures stay unknown.
 *
 * <p>Absorption is added to health before differencing, so a hit soaked by a golden apple still
 * registers. Only falls count: regeneration and respawns are not damage.
 *
 * <p>Each observed drop of the local player or of a player already in the running fight's opponents is
 * also posted as a {@link DamageObservedEvent} (§4.2, §4.9). Drops of anyone else stay internal (they
 * still serve {@link #damageAfter} for trades). Client thread; ticked only on unpaused ticks, and the
 * retention is counted in those ticks so a singleplayer pause does not expire pending samples.
 */
public final class HealthWatch {
	/** Only players this close are sampled. */
	private static final double SAMPLE_RANGE = 32.0;
	/** How long a damage sample stays available to be matched to a hit: 4 s of game ticks. */
	private static final int RETAIN_TICKS = 80;
	/** A drop must exceed this to count (float noise). */
	private static final double MIN_DROP = 0.01;
	/** Anything larger is not a hit (e.g. a reset health value). */
	private static final double MAX_DROP = 1000.0;

	/** One observed drop in someone's health. */
	private record Drop(UUID who, double amount, long nanos, long tick) {
	}

	private final Map<UUID, Double> lastHealth = new HashMap<>();
	private final Set<UUID> seenThisTick = new HashSet<>();
	private final Deque<Drop> recent = new ArrayDeque<>();
	private long ticks;

	/** Forgets every reading and sample. */
	public void reset() {
		this.lastHealth.clear();
		this.seenThisTick.clear();
		this.recent.clear();
	}

	/** Advances the retention clock by one game tick, then samples (see {@link #sampleNow}). */
	public void tick(Minecraft mc, long nowNanos) {
		this.ticks++;
		sample(mc, nowNanos);
	}

	/**
	 * Samples now without advancing the retention clock: an extra reading inside a tick that is (or will
	 * be) sampled by {@link #tick} anyway. CORE finalises a fight on the tick after the death, before
	 * CAPTURE's tick runs, and the killing blow's health drop arrives with the death; sampling here, from
	 * {@code CaptureControl.flushPending()}, posts that drop while the fight can still record it and makes
	 * it visible to the trade flush. A second sample in the same tick finds no further drop.
	 */
	public void sampleNow(Minecraft mc, long nowNanos) {
		sample(mc, nowNanos);
	}

	/** Samples every player within {@link #SAMPLE_RANGE} and records (and posts) new drops. */
	private void sample(Minecraft mc, long nowNanos) {
		LocalPlayer self = mc == null ? null : mc.player;
		if (mc == null || mc.level == null || self == null) {
			pruneOld();
			return;
		}
		UUID selfId = self.getUUID();
		List<Drop> fresh = null;
		this.seenThisTick.clear();
		for (Player p : mc.level.players()) {
			if (p != self && p.distanceToSqr(self) > SAMPLE_RANGE * SAMPLE_RANGE) {
				continue;
			}
			UUID id = p.getUUID();
			this.seenThisTick.add(id);
			double now = (double) p.getHealth() + (double) p.getAbsorptionAmount();
			Double prev = this.lastHealth.put(id, now);
			if (prev == null) {
				continue;
			}
			double drop = prev - now;
			if (drop > MIN_DROP && drop < MAX_DROP) {
				Drop d = new Drop(id, drop, nowNanos, this.ticks);
				this.recent.addLast(d);
				if (fresh == null) {
					fresh = new ArrayList<>(2);
				}
				fresh.add(d);
			}
		}
		// Forget anyone no longer in range or no longer here: someone who walks away at half health and
		// comes back healed must not register the difference as damage when they reappear.
		this.lastHealth.keySet().retainAll(this.seenThisTick);
		pruneOld();
		if (fresh != null) {
			post(fresh, selfId);
		}
	}

	/**
	 * The damage done to {@code who} just after {@code atNanos}: the sum of their drops with
	 * {@code -backNanos ≤ drop − at ≤ forwardNanos}. Health arrives in its own packet, one round trip
	 * after the click, so the window reaches forward and barely back.
	 */
	public double damageAfter(UUID who, long atNanos, long backNanos, long forwardNanos) {
		double total = 0.0;
		for (Drop d : this.recent) {
			if (!d.who().equals(who)) {
				continue;
			}
			long offset = d.nanos() - atNanos;
			if (offset >= -backNanos && offset <= forwardNanos) {
				total += d.amount();
			}
		}
		return total;
	}

	private void pruneOld() {
		while (!this.recent.isEmpty() && this.ticks - this.recent.peekFirst().tick() > RETAIN_TICKS) {
			this.recent.removeFirst();
		}
	}

	/** Posts the drops of self and of players already in the running fight's opponents. */
	private static void post(List<Drop> drops, UUID selfId) {
		FightService fights = Kwamster.fights();
		LiveFightView fight = fights.current();
		if (fight == null) {
			return;
		}
		List<PlayerRef> opponents = fight.opponents();
		for (Drop d : drops) {
			boolean self = d.who().equals(selfId);
			if (self || isOpponent(opponents, d.who())) {
				Kwamster.events().post(new DamageObservedEvent(d.nanos(), d.who(), self, d.amount()));
			}
		}
	}

	private static boolean isOpponent(List<PlayerRef> opponents, UUID who) {
		for (PlayerRef ref : opponents) {
			if (who.equals(ref.uuid())) {
				return true;
			}
		}
		return false;
	}
}
