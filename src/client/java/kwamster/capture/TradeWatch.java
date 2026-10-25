package kwamster.capture;

import kwamster.Kwamster;
import kwamster.core.event.TradeEvent;
import kwamster.core.model.PlayerRef;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Pairs a hit dealt with a melee hit taken into a trade, then measures who came out ahead on damage and
 * on momentum, and posts a {@link TradeEvent} (port of combat-report {@code TradeWatch}, §5.10).
 *
 * <ul>
 *   <li><b>Pairing.</b> One hit dealt and one melee hit taken, between the same two players, within
 *       {@code advanced.tradeWindowMs}. Each hit is spent once, so a four-hit combo answered by one hit
 *       is one trade.</li>
 *   <li><b>Momentum</b> is sampled {@value #MOMENTUM_SAMPLE_TICKS} ticks after the pairing: own velocity
 *       ({@link VelocityWatch}) projected on the horizontal you-to-opponent axis, as a percentage of
 *       sprint speed. The reference is {@code 5.612 · (attr > 0 ? attr / 0.1 : 1.0) / (sprinting ? 1.3 : 1.0)}
 *       with {@code attr = MOVEMENT_SPEED}: the {@code > 0} guard is Combat Report's, and the sprint
 *       division fixes Combat Report understating momentum by 1.3× while sprinting. A non-finite result
 *       leaves {@code momentumKnown = false}.</li>
 *   <li><b>Damage</b> is read {@value #DAMAGE_SETTLE_TICKS} ticks later from {@link HealthWatch} (forward
 *       window {@code min(900, 500 + ping)} ms, 100 ms back). Only a trade where the opponent was seen to
 *       lose health can be scored: {@code damageKnown = dealt > 0}.</li>
 *   <li><b>Timestamp.</b> {@code TradeEvent.nanos} is the later hit's time, never the emission time.</li>
 * </ul>
 *
 * <p>Client thread; ticked only on unpaused ticks.
 */
public final class TradeWatch {
	/** Ticks after the pairing at which momentum is sampled: knockback applied, not yet decayed. */
	public static final int MOMENTUM_SAMPLE_TICKS = 3;
	/** Ticks after the pairing at which damage is read: the opponent's health needs a server round trip. */
	public static final int DAMAGE_SETTLE_TICKS = 24;

	private static final double SPRINT_SPEED_BPS = 5.612;
	private static final double BASE_MOVEMENT_SPEED = 0.1;
	private static final double SPRINT_SPEED_FACTOR = 1.3;
	private static final long DAMAGE_LINK_MS = 500L;
	private static final long DAMAGE_LINK_MAX_MS = 900L;
	private static final long DAMAGE_LINK_BACK_MS = 100L;
	private static final long NANOS_PER_MS = 1_000_000L;

	private final HealthWatch health;
	private final VelocityWatch velocity;

	private @Nullable Slot dealt;
	private @Nullable Slot taken;
	private final List<Pending> pending = new ArrayList<>();

	/** The most recent unspent hit in one direction. */
	private record Slot(PlayerRef opponent, long nanos) {
	}

	/** A paired trade waiting for its momentum sample and damage read. */
	private static final class Pending {
		final PlayerRef opponent;
		final long dealtNanos;
		final long takenNanos;
		int momentumIn = MOMENTUM_SAMPLE_TICKS;
		int damageIn = DAMAGE_SETTLE_TICKS;
		boolean momentumDone;
		double momentumPct;
		boolean momentumKnown;

		Pending(PlayerRef opponent, long dealtNanos, long takenNanos) {
			this.opponent = opponent;
			this.dealtNanos = dealtNanos;
			this.takenNanos = takenNanos;
		}

		long laterNanos() {
			return Math.max(this.dealtNanos, this.takenNanos);
		}
	}

	public TradeWatch(HealthWatch health, VelocityWatch velocity) {
		this.health = health;
		this.velocity = velocity;
	}

	/** A landed, registering hit on {@code opponent} at {@code nanos} (the swing's hook time). */
	public void onHitDealt(PlayerRef opponent, long nanos) {
		this.dealt = new Slot(opponent, nanos);
		tryPair();
	}

	/** A melee hit taken from {@code attacker} at {@code nanos}. */
	public void onHitTaken(PlayerRef attacker, long nanos) {
		this.taken = new Slot(attacker, nanos);
		tryPair();
	}

	private void tryPair() {
		Slot d = this.dealt;
		Slot t = this.taken;
		if (d == null || t == null) {
			return;
		}
		long windowNanos = Kwamster.config().advanced.tradeWindowMs * NANOS_PER_MS;
		if (Math.abs(d.nanos() - t.nanos()) > windowNanos) {
			return;
		}
		if (!d.opponent().uuid().equals(t.opponent().uuid())) {
			return;
		}
		this.dealt = null;
		this.taken = null;
		this.pending.add(new Pending(d.opponent(), d.nanos(), t.nanos()));
	}

	/** Advances every pending trade by one tick; posts the ones whose damage read is due. */
	public void tick(Minecraft mc) {
		if (this.pending.isEmpty()) {
			return;
		}
		List<TradeEvent> due = null;
		Iterator<Pending> it = this.pending.iterator();
		while (it.hasNext()) {
			Pending p = it.next();
			if (!p.momentumDone && --p.momentumIn <= 0) {
				sampleMomentum(mc, p);
			}
			if (--p.damageIn > 0) {
				continue;
			}
			it.remove();
			if (due == null) {
				due = new ArrayList<>(1);
			}
			due.add(settle(mc, p));
		}
		if (due != null) {
			for (TradeEvent e : due) {
				Kwamster.events().post(e);
			}
		}
	}

	/**
	 * Settles every pending trade now and posts its {@link TradeEvent} synchronously, then forgets the
	 * half-paired hits so a hit at the end of one fight never pairs with one at the start of the next.
	 * Tolerates {@code mc.level == null} and {@code mc.player == null} (momentum and damage are then unknown).
	 */
	public void flush(Minecraft mc) {
		List<TradeEvent> settled = new ArrayList<>(this.pending.size());
		for (Pending p : this.pending) {
			settled.add(settle(mc, p));
		}
		this.pending.clear();
		clearPairing();
		for (TradeEvent e : settled) {
			Kwamster.events().post(e);
		}
	}

	/** Drops pending trades and half-paired hits without posting anything. */
	public void reset() {
		this.pending.clear();
		clearPairing();
	}

	/** Forgets the half-paired hits (pending trades are kept). */
	public void clearPairing() {
		this.dealt = null;
		this.taken = null;
	}

	/** True while a paired trade is waiting to be posted. */
	public boolean hasPending() {
		return !this.pending.isEmpty();
	}

	/** Samples momentum if still outstanding, reads the damage and builds the event. */
	private TradeEvent settle(Minecraft mc, Pending p) {
		if (!p.momentumDone) {
			sampleMomentum(mc, p);
		}
		double dealtDamage = 0.0;
		double takenDamage = 0.0;
		LocalPlayer self = mc == null ? null : mc.player;
		if (self != null) {
			long forwardMs = Math.min(DAMAGE_LINK_MAX_MS, DAMAGE_LINK_MS + latencyMs(mc, self));
			long back = DAMAGE_LINK_BACK_MS * NANOS_PER_MS;
			long forward = forwardMs * NANOS_PER_MS;
			dealtDamage = this.health.damageAfter(p.opponent.uuid(), p.dealtNanos, back, forward);
			takenDamage = this.health.damageAfter(self.getUUID(), p.takenNanos, back, forward);
		}
		boolean damageKnown = dealtDamage > 0.0 && Double.isFinite(dealtDamage) && Double.isFinite(takenDamage);
		if (!damageKnown) {
			dealtDamage = Double.isFinite(dealtDamage) ? dealtDamage : 0.0;
			takenDamage = Double.isFinite(takenDamage) ? takenDamage : 0.0;
		}
		boolean momentumKnown = p.momentumKnown && Double.isFinite(p.momentumPct);
		return new TradeEvent(p.laterNanos(), p.opponent, dealtDamage, takenDamage, damageKnown,
				momentumKnown ? p.momentumPct : 0.0, momentumKnown);
	}

	private void sampleMomentum(@Nullable Minecraft mc, Pending p) {
		p.momentumDone = true;
		LocalPlayer self = mc == null ? null : mc.player;
		if (self == null) {
			return;
		}
		Player opponent = findPlayer(mc.level, p.opponent.uuid());
		if (opponent == null) {
			return;
		}
		Vec3 toOpponent = opponent.position().subtract(self.position());
		double flatLength = Math.sqrt(toOpponent.x * toOpponent.x + toOpponent.z * toOpponent.z);
		// Standing inside each other leaves no meaningful axis to project onto.
		if (!(flatLength >= 1.0e-4) || !Double.isFinite(flatLength)) {
			return;
		}
		double axisX = toOpponent.x / flatLength;
		double axisZ = toOpponent.z / flatLength;
		Vec3 v = this.velocity.perSecond();
		double along = v.x * axisX + v.z * axisZ;
		double pct = 100.0 * along / sprintReference(self);
		if (Double.isFinite(pct)) {
			p.momentumPct = pct;
			p.momentumKnown = true;
		}
	}

	/** Sprint speed for this player right now, in blocks per second (§5.10 reference with the sprint fix). */
	private static double sprintReference(LocalPlayer self) {
		double attr = self.getAttributeValue(Attributes.MOVEMENT_SPEED);
		double scale = attr > 0.0 ? attr / BASE_MOVEMENT_SPEED : 1.0;
		return SPRINT_SPEED_BPS * scale / (self.isSprinting() ? SPRINT_SPEED_FACTOR : 1.0);
	}

	private static long latencyMs(Minecraft mc, LocalPlayer self) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return 0L;
		}
		PlayerInfo info = connection.getPlayerInfo(self.getUUID());
		return info == null ? 0L : Math.max(0L, info.getLatency());
	}

	private static @Nullable Player findPlayer(@Nullable ClientLevel level, UUID id) {
		if (level == null) {
			return null;
		}
		for (Player p : level.players()) {
			if (p.getUUID().equals(id)) {
				return p;
			}
		}
		return null;
	}
}
