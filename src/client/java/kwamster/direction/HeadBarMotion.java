package kwamster.direction;

/**
 * One player's movement state for the above-head bar and the jump cue (S12, §5.11a), ported from
 * builtdoor's Direction Indicator ({@code DirectionIndicatorClient.track}). {@link HeadBarTracker} feeds
 * it one sample per client tick. Minecraft-free (R6).
 *
 * <p><b>Heading.</b> Remote players never report usable {@code getDeltaMovement()}, so the per-tick
 * position delta is projected onto the player's look yaw ({@code getYRot()}, not body rotation, because
 * movement input is applied relative to where a player looks) and smoothed, so one dropped position
 * packet cannot flicker the colour. Steps longer than 4 blocks are teleports and count as no movement.
 *
 * <p><b>Jumps.</b> Remote positions arrive interpolated over about three ticks, so on the tick
 * {@code onGround} flips a player has risen only about 0.14 of a jump's 0.42. Leaving the ground therefore
 * arms a short confirm window, and the jump counts once the player has gained {@link #JUMP_MIN_RISE}
 * while still airborne; walking off a ledge only ever loses height and never confirms.
 *
 * <p><b>Knockback.</b> A hit launches a grounded player at 0.4 against a jump's 0.42, which interpolation
 * makes indistinguishable, so a fresh hit on a grounded player suppresses the next launch for the
 * configured window. Landing clears the suppression: every real jump after a hit has a landing in
 * between, a knockback launch never does. A jump on the very tick of a hit (a jump reset) is
 * indistinguishable from knockback and is suppressed too.
 */
public final class HeadBarMotion {
	/** Exponential smoothing weight of the newest forward-speed sample. */
	public static final double SMOOTHING = 0.45;
	/** Horizontal steps longer than this (squared, blocks per tick) are teleports, not movement. */
	public static final double TELEPORT_SQR = 16.0;
	/** Height gained while airborne that confirms a jump, in blocks. */
	public static final double JUMP_MIN_RISE = 0.1;
	/** Ticks a jump candidate has to show its height gain before it is discarded as a step off a ledge. */
	public static final int JUMP_CONFIRM_TICKS = 4;
	/** Ticks after a jump before another can count. A jump arc is about 12 ticks, so this only eats flicker. */
	public static final int JUMP_COOLDOWN_TICKS = 6;

	/** Which way the bar reads. */
	public enum Heading { FORWARD, BACKWARD, IDLE }

	private double lastX;
	private double lastY;
	private double lastZ;
	/** The hitbox height at the end of the previous tick, so a pose change can be interpolated. */
	private double lastHeight;
	private boolean onGround;
	/** Smoothed speed along the player's facing, blocks per tick; positive is forward. */
	private double forwardSpeed;
	/** Ticks left to confirm a jump candidate; 0 when nothing is pending. */
	private int confirmTicks;
	private double takeoffY;
	private int cooldown;
	/** Ticks left during which a launch reads as knockback rather than a jump. */
	private int knockbackTicks;
	private int prevHurtTime;

	private HeadBarMotion() {
	}

	/**
	 * The state for a player seen for the first time (join, respawn, back in range). Seeding the baseline
	 * keeps the first comparison from manufacturing a jump or a burst of movement.
	 */
	public static HeadBarMotion seed(double x, double y, double z, double height, boolean onGround, int hurtTime) {
		HeadBarMotion m = new HeadBarMotion();
		m.lastX = x;
		m.lastY = y;
		m.lastZ = z;
		m.lastHeight = height;
		m.onGround = onGround;
		m.prevHurtTime = hurtTime;
		return m;
	}

	/**
	 * Advances one client tick.
	 *
	 * @param yawDeg          {@code getYRot()} in degrees
	 * @param hurtTime        the synced hurt animation timer
	 * @param hurtDuration    the hurt animation length ({@code hurtDuration}, 0 for a never-hit player)
	 * @param knockbackWindow ticks a launch after a fresh grounded hit counts as knockback; 0 disables
	 * @return true when the player started a jump this tick that is neither knockback nor inside the
	 *         cooldown (the caller still applies its own enable, range and self rules)
	 */
	public boolean step(double x, double y, double z, float yawDeg, boolean onGround, int hurtTime, int hurtDuration,
			double height, int knockbackWindow) {
		// Which way are they moving, relative to where they are looking?
		double dx = x - this.lastX;
		double dz = z - this.lastZ;
		if (dx * dx + dz * dz > TELEPORT_SQR) {
			dx = 0.0;
			dz = 0.0;
		}
		double yaw = Math.toRadians(yawDeg);
		double forwardNow = dx * -Math.sin(yaw) + dz * Math.cos(yaw);
		this.forwardSpeed += (forwardNow - this.forwardSpeed) * SMOOTHING;

		// Was a launch caused by a hit rather than a jump? A hit is fresh on the tick it lands, normally a
		// rising edge in hurtTime; 1.8-style servers land hits faster than it decays and pin it at
		// hurtDuration, so that counts as fresh too. Both need hurtTime > 0 (hurtDuration is also 0 on a
		// player who was never hit).
		boolean freshHit = hurtTime > 0 && (hurtTime > this.prevHurtTime || hurtTime >= hurtDuration);
		this.prevHurtTime = hurtTime;

		if (onGround && !this.onGround) {
			// Landed: whatever a hit was going to launch, it already has.
			this.knockbackTicks = 0;
		}
		if (knockbackWindow > 0 && onGround && freshHit) {
			this.knockbackTicks = knockbackWindow;
		} else if (this.knockbackTicks > 0) {
			this.knockbackTicks--;
		}

		// Did they just start a jump?
		if (this.cooldown > 0) {
			this.cooldown--;
		}
		if (onGround) {
			this.confirmTicks = 0;
		} else if (this.onGround) {
			// Left the ground this tick: a jump, or a step off an edge.
			this.confirmTicks = JUMP_CONFIRM_TICKS;
			this.takeoffY = this.lastY;
		}
		boolean jumped = false;
		if (this.confirmTicks > 0) {
			if (y - this.takeoffY >= JUMP_MIN_RISE) {
				jumped = this.cooldown == 0 && this.knockbackTicks == 0;
				this.cooldown = JUMP_COOLDOWN_TICKS;
				this.confirmTicks = 0;
			} else {
				this.confirmTicks--;
			}
		}

		this.onGround = onGround;
		this.lastHeight = height;
		this.lastX = x;
		this.lastY = y;
		this.lastZ = z;
		return jumped;
	}

	/** The bar's reading: idle below {@code thresholdBlocksPerTick}, else forward or backward. */
	public Heading heading(double thresholdBlocksPerTick) {
		return classify(this.forwardSpeed, thresholdBlocksPerTick);
	}

	/** {@link #heading} for a raw speed; a NaN speed reads as idle. */
	public static Heading classify(double forwardSpeed, double thresholdBlocksPerTick) {
		if (!(Math.abs(forwardSpeed) >= thresholdBlocksPerTick)) {
			return Heading.IDLE;
		}
		return forwardSpeed > 0.0 ? Heading.FORWARD : Heading.BACKWARD;
	}

	/**
	 * The hitbox height interpolated across the tick: the synced pose swaps {@code getBbHeight()} in one
	 * step (1.8 to 1.5 on sneak), which would snap the bar while the model's crouch animates smoothly.
	 */
	public double interpolatedHeight(float partialTick, double heightNow) {
		return this.lastHeight + (heightNow - this.lastHeight) * partialTick;
	}

	/** Smoothed speed along the facing, blocks per tick (positive is forward). */
	public double forwardSpeed() {
		return this.forwardSpeed;
	}

	/** Ticks of knockback suppression left. */
	public int knockbackTicks() {
		return this.knockbackTicks;
	}
}
