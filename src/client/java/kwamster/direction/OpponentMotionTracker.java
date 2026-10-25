package kwamster.direction;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.KwamsterListener;
import kwamster.core.event.WorldLeaveEvent;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.StoplightReading;
import kwamster.core.service.StoplightSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.UUID;

/**
 * DIRECTION's stoplight source (S12, §5.11): once per client tick it picks whose movement to show,
 * samples that player's horizontal position, feeds {@link RadialFilter} and publishes the resulting
 * {@link StoplightReading} for the HUD.
 *
 * <p><b>Target</b>, first match wins:
 * <ol>
 *   <li>the current fight opponent ({@code fights().currentOpponent()}, looked up by UUID in
 *       {@code level.players()}) while they are loaded, alive and not spectating;</li>
 *   <li>with {@code direction.target == FIGHT_OR_AIMED} only: {@code mc.crosshairPickEntity} when it
 *       is a non-NPC player (only non-null within attack reach);</li>
 *   <li>the previously tracked player while they stay roughly in view (within {@link #KEEP_CONE_DEG}
 *       and {@code aimedRange} + {@link #KEEP_RANGE_EXTRA}), so two players inside the cone cannot
 *       steal the light from each other every tick (each switch reseeds the filter);</li>
 *   <li>the nearest alive, non-spectator, non-NPC player within {@code aimedRange} whose box centre is
 *       inside a {@link #CONE_DEG} cone of the look vector.</li>
 * </ol>
 * Otherwise the reading is {@link StoplightReading#NONE}.
 *
 * <p><b>Position source.</b> Remote players move by vanilla interpolation toward the last position the
 * server sent (players are sent every 2 ticks; {@link InterpolationHandler} spreads each update, and
 * any teleport closer than 64 blocks, over 3 ticks). The filter is fed the midpoint of the server
 * positions of this tick and the previous one: with the 2-tick update cadence every per-tick step is
 * one tick of real movement, it lags the server by about one tick instead of the three of the
 * interpolated body, and a teleport is a single jump instead of a multi-tick smear (the smear defeats
 * a per-tick teleport test and leaves a decaying tail in the average). A server-position jump of more
 * than 4 blocks in one tick outside a knockback hold is reported to the filter as a discontinuity.
 *
 * <p>Inert (reading NONE, nothing sampled) while {@code general.enabled} (R4) or
 * {@code direction.stoplightEnabled} is off, while no world is loaded, and after a {@link WorldLeaveEvent}
 * until the next tick finds a target. Frozen while a singleplayer game is paused. Client thread.
 */
public final class OpponentMotionTracker implements StoplightSource, KwamsterListener {
	/** Aimed-target cone half-angle (§5.11). */
	public static final double CONE_DEG = 30.0;
	/** A previously tracked player is kept while within this half-angle. */
	public static final double KEEP_CONE_DEG = 45.0;
	/** A previously tracked player is kept while within {@code aimedRange} plus this many blocks. */
	public static final double KEEP_RANGE_EXTRA = 2.0;

	private static final double COS_CONE = Math.cos(Math.toRadians(CONE_DEG));
	private static final double COS_KEEP_CONE = Math.cos(Math.toRadians(KEEP_CONE_DEG));
	/** A box centre closer to the eye than this (squared) counts as inside any cone. */
	private static final double MIN_DIST_SQR = 1.0e-6;
	private static final float DEFAULT_AIMED_RANGE = new KwamsterConfig.Direction().aimedRange;

	private final RadialFilter filter = new RadialFilter();
	private volatile StoplightReading reading = StoplightReading.NONE;

	private boolean tracking;
	private @Nullable UUID targetUuid;
	private int targetId;
	private @Nullable PlayerRef targetRef;
	private int prevHurtTime;
	private double serverX;
	private double serverZ;

	// ---- StoplightSource ---------------------------------------------------------------------------

	/**
	 * The gametest stoplight override when set, otherwise the last published reading;
	 * {@link StoplightReading#NONE} while KWAMSTER or the stoplight is switched off. Never null.
	 */
	@Override
	public StoplightReading reading() {
		try {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.isSameThread()) {
				StoplightReading override = Kwamster.testHooks().stoplightOverride();
				if (override != null) {
					return override;
				}
			}
			KwamsterConfig cfg = Kwamster.config();
			if (!cfg.general.enabled || !cfg.direction.stoplightEnabled) {
				return StoplightReading.NONE;
			}
			return this.reading;
		} catch (Throwable t) {
			LogLimiter.error("OpponentMotionTracker.reading", "Stoplight reading failed", t);
			return StoplightReading.NONE;
		}
	}

	// ---- KwamsterListener --------------------------------------------------------------------------

	@Override
	public void onClientTick(Minecraft mc) {
		try {
			tick(mc);
		} catch (Throwable t) {
			LogLimiter.error("OpponentMotionTracker.tick", "Stoplight update failed", t);
			clear();
		}
	}

	/** Positions and entity ids from the old world mean nothing in the new one. */
	@Override
	public void onWorldLeave(WorldLeaveEvent e) {
		clear();
	}

	// ---- per tick ----------------------------------------------------------------------------------

	private void tick(Minecraft mc) {
		KwamsterConfig cfg = Kwamster.config();
		if (!cfg.general.enabled || !cfg.direction.stoplightEnabled) {
			clear();
			return;
		}
		LocalPlayer self = mc.player;
		ClientLevel level = mc.level;
		if (self == null || level == null) {
			clear();
			return;
		}
		if (mc.isPaused()) {
			// Nothing moves while a singleplayer game is paused; keep the last reading as it is.
			return;
		}

		KwamsterConfig.Direction dir = cfg.direction;
		AbstractClientPlayer target = fightTarget(level, self);
		if (target == null && dir.target == KwamsterConfig.StoplightTarget.FIGHT_OR_AIMED) {
			target = aimedTarget(mc, level, self, dir.aimedRange);
		}
		if (target == null) {
			clear();
			return;
		}
		sample(target, self, dir);
	}

	/** Feeds one sample of {@code target} to the filter and publishes the reading. */
	private void sample(AbstractClientPlayer target, LocalPlayer self, KwamsterConfig.Direction dir) {
		UUID uuid = target.getUUID();
		int id = target.getId();
		Vec3 server = serverPosition(target);
		int hurtTime = target.hurtTime;

		boolean sameTarget = this.tracking && uuid.equals(this.targetUuid) && id == this.targetId;
		if (!sameTarget) {
			this.tracking = true;
			this.targetUuid = uuid;
			this.targetId = id;
			this.targetRef = PlayerRefs.of(target);
			this.prevHurtTime = hurtTime;
			this.serverX = server.x;
			this.serverZ = server.z;
		}

		double jumpX = server.x - this.serverX;
		double jumpZ = server.z - this.serverZ;
		double ox = (server.x + this.serverX) * 0.5;
		double oz = (server.z + this.serverZ) * 0.5;
		this.serverX = server.x;
		this.serverZ = server.z;

		boolean freshHit = sameTarget && RadialFilter.isFreshHit(hurtTime, this.prevHurtTime, target.hurtDuration);
		this.prevHurtTime = hurtTime;
		// Knockback can launch a player several blocks between two position updates; only a jump
		// outside a hit is treated as a teleport. A teleport during the hold is still caught by the
		// filter's own per-tick test once the midpoint moves more than 4 blocks.
		boolean discontinuity = sameTarget && !freshHit && !this.filter.knockbackHold()
				&& jumpX * jumpX + jumpZ * jumpZ > RadialFilter.TELEPORT_SQR;

		Vec3 me = self.position();
		this.filter.update(uuid, id, ox, oz, me.x, me.z, freshHit, discontinuity, Clock.nanos(), dir);
		this.reading = this.filter.reading(this.targetRef);
	}

	// ---- target selection --------------------------------------------------------------------------

	/** The current fight opponent, if loaded, alive and not spectating; otherwise null. */
	private static @Nullable AbstractClientPlayer fightTarget(ClientLevel level, LocalPlayer self) {
		PlayerRef opponent = Kwamster.fights().currentOpponent();
		if (opponent == null) {
			return null;
		}
		UUID uuid = opponent.uuid();
		for (AbstractClientPlayer p : level.players()) {
			if (p != self && uuid.equals(p.getUUID())) {
				return usable(p, self) ? p : null;
			}
		}
		return null;
	}

	/**
	 * Rules 2–4 of the target list: the crosshair pick, then the previously tracked player (from either
	 * source, so the light stays on when a fight ends with the opponent still in view) while roughly in
	 * view, then the nearest player inside the cone.
	 */
	private @Nullable AbstractClientPlayer aimedTarget(Minecraft mc, ClientLevel level, LocalPlayer self, float aimedRange) {
		if (mc.crosshairPickEntity instanceof AbstractClientPlayer picked && usable(picked, self) && !PlayerRefs.isNpc(picked)) {
			return picked;
		}
		double range = aimedRange > 0f && Float.isFinite(aimedRange) ? aimedRange : DEFAULT_AIMED_RANGE;
		Vec3 eye = self.getEyePosition();
		Vec3 look = self.getLookAngle();

		if (this.tracking) {
			Entity previous = level.getEntity(this.targetId);
			if (previous instanceof AbstractClientPlayer p && p.getUUID().equals(this.targetUuid) && usable(p, self)
					&& coneDistance(p, eye, look, range + KEEP_RANGE_EXTRA, COS_KEEP_CONE) >= 0.0 && !PlayerRefs.isNpc(p)) {
				return p;
			}
		}

		AbstractClientPlayer best = null;
		double bestDist = Double.MAX_VALUE;
		for (AbstractClientPlayer p : level.players()) {
			if (!usable(p, self)) {
				continue;
			}
			double d = coneDistance(p, eye, look, range, COS_CONE);
			// The NPC test (a tab-list lookup) runs only for a player that would win.
			if (d >= 0.0 && d < bestDist && !PlayerRefs.isNpc(p)) {
				best = p;
				bestDist = d;
			}
		}
		return best;
	}

	/**
	 * Not the local player, still in the world, not dying and not spectating. Not {@code isAlive()}: its
	 * {@code health > 0} test rejects players on servers that hide health as NaN.
	 */
	private static boolean usable(@Nullable AbstractClientPlayer p, LocalPlayer self) {
		return p != null && p != self && !p.isRemoved() && !p.isDeadOrDying() && !p.isSpectator();
	}

	/**
	 * Eye-to-box-centre distance of {@code p} when its centre lies within {@code range} and inside the
	 * cone of half-angle {@code acos(cosCone)} around {@code look}; −1 otherwise.
	 */
	private static double coneDistance(AbstractClientPlayer p, Vec3 eye, Vec3 look, double range, double cosCone) {
		Vec3 c = p.getBoundingBox().getCenter();
		double dx = c.x - eye.x;
		double dy = c.y - eye.y;
		double dz = c.z - eye.z;
		double distSqr = dx * dx + dy * dy + dz * dz;
		if (distSqr > range * range) {
			return -1.0;
		}
		if (distSqr < MIN_DIST_SQR) {
			return 0.0;
		}
		double dist = Math.sqrt(distSqr);
		double cos = (dx * look.x + dy * look.y + dz * look.z) / dist;
		return cos >= cosCone ? dist : -1.0;
	}

	/**
	 * The last position the server sent for {@code p} (the interpolation target while vanilla is still
	 * moving the body toward it, otherwise the body's own position, which then equals it).
	 */
	private static Vec3 serverPosition(AbstractClientPlayer p) {
		InterpolationHandler interpolation = p.getInterpolation();
		Vec3 pos = interpolation == null ? null : interpolation.position();
		return pos == null ? p.position() : pos;
	}

	/** Back to nothing tracked: reading NONE, filter reset. */
	private void clear() {
		this.filter.reset();
		this.tracking = false;
		this.targetUuid = null;
		this.targetId = 0;
		this.targetRef = null;
		this.prevHurtTime = 0;
		this.reading = StoplightReading.NONE;
	}
}
