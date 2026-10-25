package kwamster.direction;

import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.StoplightReading;
import kwamster.core.model.StoplightState;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * The S12 direction classifier (§5.11): turns one tracked opponent's per-tick horizontal positions
 * into a stoplight state. GREEN = moving toward you, RED = backing away, YELLOW = holding still or
 * strafing (only the radial component of their motion counts; your own movement is ignored by design).
 *
 * <p>Per {@link #update} (one call per client tick):
 * <ol>
 *   <li><b>Reseed</b> when the target or its entity id changes, when the opponent moved more than
 *       4 blocks horizontally in one tick ({@code |O − O_prev|² > }{@link #TELEPORT_SQR}: pearl,
 *       teleport, respawn), or when the caller reports a discontinuity: {@code ema = 0}, state YELLOW,
 *       {@code sinceNanos = now}, and the next {@link #RESEED_SKIP_TICKS} samples are skipped.</li>
 *   <li><b>Raw speed</b> {@code raw = dot(O − O_prev, normalize(M − O)) · 20} blocks per second
 *       (+ = toward you), limited to ±{@link #MAX_RAW_BPS} so a single outlier sample cannot drive
 *       the average far past the thresholds.</li>
 *   <li><b>Knockback hold.</b> A fresh hit on the opponent ({@link #isFreshHit}) freezes the state and
 *       the average for {@code kbHoldMs}; the first sample after the hold reseeds {@code ema = raw}.</li>
 *   <li><b>Smoothing</b> {@code ema += α·(raw − ema)} with {@code α = 1 − exp(−50 / smoothingMs)}
 *       ({@code α = 1} when {@code smoothingMs == 0}, so the raw value passes through).</li>
 *   <li><b>Hysteresis</b> {@code enter = enterBps}, {@code exit = max(}{@link #MIN_EXIT_BPS}{@code ,
 *       enterBps − hysteresisBps)}: GREEN if {@code ema ≥ enter} or (already GREEN and
 *       {@code ema ≥ exit}); RED symmetrically; YELLOW otherwise.</li>
 *   <li><b>Minimum hold.</b> The state switches to the candidate only when
 *       {@code now − sinceNanos ≥ minHoldMs}; then {@code sinceNanos = now}.</li>
 * </ol>
 *
 * <p>Minecraft-free (R6): positions, identities, hit flags and the clock are passed in, and the
 * thresholds are read from the {@link KwamsterConfig.Direction} passed to each call, so a settings
 * change applies on the next tick. Not thread-safe; DIRECTION drives it from the client tick.
 */
public final class RadialFilter {
	/** Client ticks per second: a per-tick displacement times this is blocks per second. */
	public static final double TICKS_PER_SECOND = 20.0;
	/** Length of one client tick in milliseconds (the smoothing time step). */
	public static final double TICK_MS = 50.0;
	/** A horizontal move longer than 4 blocks in one tick is a teleport, not movement (squared). */
	public static final double TELEPORT_SQR = 16.0;
	/** Samples ignored after a reseed (the rest of vanilla's 3-step position interpolation). */
	public static final int RESEED_SKIP_TICKS = 2;
	/**
	 * Bound on one raw sample, in blocks per second (one block per tick). Faster than any movement on
	 * foot (a Speed II sprint-jump peaks near 11 b/s), so real readings are untouched; it only limits
	 * what a residual interpolation step or a setback can do to the average.
	 */
	public static final double MAX_RAW_BPS = 20.0;
	/**
	 * Floor of the exit threshold. With {@code hysteresisBps ≥ enterBps} the formula's exit would be 0,
	 * and because the average decays geometrically (it never reaches exactly 0) a stopped opponent would
	 * keep a lit GREEN or RED for minutes. Below the smallest configurable {@code enterBps} (0.3).
	 */
	public static final double MIN_EXIT_BPS = 0.05;

	/** Horizontal separations below this (squared, about 1 mm) give no usable radial axis. */
	private static final double MIN_AXIS_SQR = 1.0e-6;
	/** Averages smaller than this are flushed to 0 (avoids denormals after long decays). */
	private static final double FLUSH_BPS = 1.0e-9;
	private static final long NANOS_PER_MS = 1_000_000L;
	private static final KwamsterConfig.Direction DEFAULTS = new KwamsterConfig.Direction();

	private boolean seeded;
	private @Nullable UUID target;
	private int entityId;
	private double prevX;
	private double prevZ;
	private double ema;
	private double raw;
	private StoplightState state = StoplightState.OFF;
	private long sinceNanos;
	private int skipTicks;
	private boolean holding;
	private long holdUntilNanos;
	private boolean reseedOnNextSample;

	/** Forgets everything: state OFF, no target. The next {@link #update} reseeds. */
	public void reset() {
		this.seeded = false;
		this.target = null;
		this.entityId = 0;
		this.prevX = 0.0;
		this.prevZ = 0.0;
		this.ema = 0.0;
		this.raw = 0.0;
		this.state = StoplightState.OFF;
		this.sinceNanos = 0L;
		this.skipTicks = 0;
		this.holding = false;
		this.holdUntilNanos = 0L;
		this.reseedOnNextSample = false;
	}

	/** {@link #update(UUID, int, double, double, double, double, boolean, boolean, long, KwamsterConfig.Direction)} without a caller-detected discontinuity. */
	public StoplightState update(UUID target, int entityId, double ox, double oz, double mx, double mz,
			boolean freshHit, long nowNanos, KwamsterConfig.@Nullable Direction cfg) {
		return update(target, entityId, ox, oz, mx, mz, freshHit, false, nowNanos, cfg);
	}

	/**
	 * Feeds one client tick and returns the resulting state.
	 *
	 * @param target        the tracked player's UUID; a change reseeds
	 * @param entityId      the tracked entity's id; a change (respawn, re-tracking) reseeds
	 * @param ox            opponent x this tick
	 * @param oz            opponent z this tick
	 * @param mx            own x this tick
	 * @param mz            own z this tick
	 * @param freshHit      the opponent was hit this tick ({@link #isFreshHit}): starts the knockback hold
	 * @param discontinuity the caller saw a jump this filter cannot (for example a teleport that vanilla
	 *                      spreads over several interpolation steps): reseeds like a teleport
	 * @param nowNanos      {@code Clock.nanos()}
	 * @param cfg           the direction settings (null = defaults)
	 */
	public StoplightState update(UUID target, int entityId, double ox, double oz, double mx, double mz,
			boolean freshHit, boolean discontinuity, long nowNanos, KwamsterConfig.@Nullable Direction cfg) {
		Objects.requireNonNull(target, "target");
		KwamsterConfig.Direction c = cfg == null ? DEFAULTS : cfg;
		if (!Double.isFinite(ox) || !Double.isFinite(oz) || !Double.isFinite(mx) || !Double.isFinite(mz)) {
			// Unusable sample: drop the baseline so the next good one starts fresh.
			reset();
			return this.state;
		}

		double dx = ox - this.prevX;
		double dz = oz - this.prevZ;
		boolean identityChanged = !this.seeded || !target.equals(this.target) || entityId != this.entityId;
		if (identityChanged || discontinuity || dx * dx + dz * dz > TELEPORT_SQR) {
			reseed(target, entityId, ox, oz, nowNanos);
			return this.state;
		}
		this.prevX = ox;
		this.prevZ = oz;
		this.raw = clamp(radialBps(dx, dz, mx - ox, mz - oz), -MAX_RAW_BPS, MAX_RAW_BPS);

		// Knockback hold: a new hit (re)starts it; it ends on the first sample at or after its deadline.
		long kbHoldNanos = Math.max(0, c.kbHoldMs) * NANOS_PER_MS;
		if (freshHit && kbHoldNanos > 0L) {
			this.holding = true;
			this.holdUntilNanos = nowNanos + kbHoldNanos;
		}
		if (this.holding && nowNanos - this.holdUntilNanos >= 0L) {
			this.holding = false;
			this.reseedOnNextSample = true;
		}

		if (this.skipTicks > 0) {
			this.skipTicks--;
			return this.state;
		}
		if (this.holding) {
			return this.state;
		}
		if (this.reseedOnNextSample) {
			this.reseedOnNextSample = false;
			this.ema = this.raw;
		} else {
			this.ema += alpha(c.smoothingMs) * (this.raw - this.ema);
		}
		if (Math.abs(this.ema) < FLUSH_BPS) {
			this.ema = 0.0;
		}

		StoplightState candidate = classify(this.state, this.ema, c.enterBps, c.hysteresisBps);
		long minHoldNanos = Math.max(0, c.minHoldMs) * NANOS_PER_MS;
		if (candidate != this.state && nowNanos - this.sinceNanos >= minHoldNanos) {
			this.state = candidate;
			this.sinceNanos = nowNanos;
		}
		return this.state;
	}

	/**
	 * The current reading for {@code target} (the ref of the tracked player), or
	 * {@link StoplightReading#NONE} while nothing is tracked.
	 */
	public StoplightReading reading(@Nullable PlayerRef target) {
		if (!this.seeded || this.state == StoplightState.OFF) {
			return StoplightReading.NONE;
		}
		return new StoplightReading(this.state, this.ema, this.holding, target, this.sinceNanos);
	}

	/** The current state; OFF until the first {@link #update} and after {@link #reset}. */
	public StoplightState state() {
		return this.state;
	}

	/** The smoothed radial speed in blocks per second (+ = toward you); 0 right after a reseed. */
	public double approachBps() {
		return this.ema;
	}

	/** The last raw (limited, unsmoothed) radial speed in blocks per second. */
	public double rawBps() {
		return this.raw;
	}

	/** True while a knockback hold freezes the state. */
	public boolean knockbackHold() {
		return this.holding;
	}

	/** {@code nowNanos} of the update that entered the current state (or of the last reseed). */
	public long sinceNanos() {
		return this.sinceNanos;
	}

	/** True while samples are being skipped after a reseed. */
	public boolean settling() {
		return this.skipTicks > 0;
	}

	/** True once a target has been seeded (until {@link #reset}). */
	public boolean seeded() {
		return this.seeded;
	}

	/** The tracked UUID, or null when nothing is seeded. */
	public @Nullable UUID target() {
		return this.target;
	}

	// ---- pure helpers (public for tests) -------------------------------------------------------

	/**
	 * The direction-indicator "fresh hit" test: the hurt animation (re)started this tick. Normally a
	 * rising edge of {@code hurtTime}; servers that land hits faster than it decays pin it at
	 * {@code hurtDuration}, so that counts too. Both need {@code hurtTime > 0}, because
	 * {@code hurtDuration} is 0 on a player who was never hit.
	 */
	public static boolean isFreshHit(int hurtTime, int prevHurtTime, int hurtDuration) {
		return hurtTime > 0 && (hurtTime > prevHurtTime || hurtTime >= hurtDuration);
	}

	/**
	 * Radial speed in blocks per second of a one-tick displacement {@code (dx, dz)}, projected on the
	 * unit vector toward you {@code (toSelfX, toSelfZ)} = {@code M − O}. 0 when the two are within
	 * about a millimetre horizontally (no usable axis).
	 */
	public static double radialBps(double dx, double dz, double toSelfX, double toSelfZ) {
		double lenSqr = toSelfX * toSelfX + toSelfZ * toSelfZ;
		if (!(lenSqr >= MIN_AXIS_SQR)) {
			return 0.0;
		}
		double len = Math.sqrt(lenSqr);
		double r = (dx * toSelfX + dz * toSelfZ) / len * TICKS_PER_SECOND;
		return Double.isFinite(r) ? r : 0.0;
	}

	/** The smoothing factor for one 50 ms tick: {@code 1 − exp(−50 / smoothingMs)}, or 1 when {@code smoothingMs ≤ 0}. */
	public static double alpha(int smoothingMs) {
		return smoothingMs <= 0 ? 1.0 : 1.0 - Math.exp(-TICK_MS / smoothingMs);
	}

	/** The exit threshold {@code max(MIN_EXIT_BPS, enter − hysteresis)}, never above {@code enter}. */
	public static double exitBps(double enterBps, double hysteresisBps) {
		double enter = sanitizeEnter(enterBps);
		double hyst = Double.isFinite(hysteresisBps) ? Math.max(0.0, hysteresisBps) : 0.0;
		return Math.min(enter, Math.max(MIN_EXIT_BPS, enter - hyst));
	}

	/** The hysteresis rule of §5.11 for a smoothed speed {@code ema} and the current state. */
	public static StoplightState classify(StoplightState current, double ema, double enterBps, double hysteresisBps) {
		double enter = sanitizeEnter(enterBps);
		double exit = exitBps(enter, hysteresisBps);
		if (ema >= enter || (current == StoplightState.GREEN && ema >= exit)) {
			return StoplightState.GREEN;
		}
		if (ema <= -enter || (current == StoplightState.RED && ema <= -exit)) {
			return StoplightState.RED;
		}
		return StoplightState.YELLOW;
	}

	// ---- internals --------------------------------------------------------------------------------

	private void reseed(UUID target, int entityId, double ox, double oz, long nowNanos) {
		this.seeded = true;
		this.target = target;
		this.entityId = entityId;
		this.prevX = ox;
		this.prevZ = oz;
		this.ema = 0.0;
		this.raw = 0.0;
		this.state = StoplightState.YELLOW;
		this.sinceNanos = nowNanos;
		this.skipTicks = RESEED_SKIP_TICKS;
		this.holding = false;
		this.holdUntilNanos = 0L;
		this.reseedOnNextSample = false;
	}

	/** {@code enterBps} as a usable positive threshold (the config default for a non-finite or non-positive value). */
	private static double sanitizeEnter(double enterBps) {
		return Double.isFinite(enterBps) && enterBps > 0.0 ? enterBps : DEFAULTS.enterBps;
	}

	private static double clamp(double v, double lo, double hi) {
		return v < lo ? lo : (v > hi ? hi : v);
	}
}
