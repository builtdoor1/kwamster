package kwamster.capture;

import kwamster.core.event.JumpResetEvent;
import kwamster.core.model.PlayerRef;
import org.jspecify.annotations.Nullable;

/**
 * Pairs the local player's jumps with melee hits taken and decides each jump-reset attempt live (S1,
 * §5.1). Minecraft-free (R6): every input is a {@code (tick, nanos)} pair plus the three window
 * values, and it never reads a clock itself.
 *
 * <p><b>Timing basis: client ticks.</b> A hit processed between ticks carries the index of the last
 * tick that ran ({@code n}); a jump in the very next tick carries {@code n + 1}. So
 * {@code deltaTicks = jumpTick - hitTick}, and the game-time delta is
 * {@code deltaMs = 50·(deltaTicks - 1)}: 0 ms = jumped on the first tick after the hit, and early jumps
 * give {@code deltaMs ≤ -50}. The windows ({@code minMs}, {@code maxMs}, {@code attemptMs}) are in this
 * game time, so decisions are FPS-independent and immune to catch-up ticks. The wall-clock delta is
 * reported for information only.
 *
 * <p><b>Two jump inputs.</b> {@link #onJump} is a real {@code jumpFromGround}. It alone cannot see a late
 * attempt: a melee hit on a grounded player throws them upward on the first tick after the hit, and
 * {@code jumpFromGround} runs only on the ground, so a jump pressed two or more ticks after the hit never
 * calls it. {@link #onJumpPress} is therefore fed the rising edges of the jump input, sampled once per
 * client tick; a press in the same tick as a {@code jumpFromGround} (or another press) is the same action
 * and is ignored. Both inputs pair with hits by the same rules, so an actual jump inside the success window
 * is still a success, and a press two or more ticks late is now a late attempt instead of nothing.
 *
 * <ul>
 *   <li>{@link #onJump} / {@link #onJumpPress}: an unpaired hit with {@code 0 ≤ deltaMs ≤ attemptMs} makes
 *       an attempt, a success when {@code minMs ≤ deltaMs ≤ maxMs}. Otherwise the jump is remembered.</li>
 *   <li>{@link #onHitTaken}: an unpaired jump with {@code -attemptMs ≤ deltaMs ≤ -50} makes an early
 *       attempt (never a success). A real jump qualifies whatever the hit (jumping too early is exactly
 *       what leaves you airborne when the hit lands); a bare press qualifies only before a grounded hit,
 *       because a press that produced no jump was made in the air, and a hit that finds you still in the
 *       air was no reset opportunity. Such an airborne hit discards the press, and the latest real jump
 *       is then still looked at, so mashing the key after an early jump does not erase it. Otherwise a
 *       grounded hit opens a window, replacing any older one. A hit taken in the air opens none, and also
 *       closes an older open window, so a jump after landing is never scored against the earlier hit.</li>
 *   <li><b>A press still held at a grounded hit</b> is not early yet: vanilla re-reads the held key every
 *       tick ({@code LocalPlayer.applyInput}) and {@code LivingEntity.aiStep} calls {@code jumpFromGround}
 *       on the first tick you are on the ground, which is the first tick after the hit, a 0 ms reset. So
 *       the hit opens its window as usual and remembers the press; the jump decides. Only when the first
 *       tick after the hit ends with no jump (the key was let go, or the jump could not happen) does
 *       {@link #onTickEnd} turn the remembered press into the early attempt it would have been.</li>
 * </ul>
 *
 * <p>Not thread-safe; CAPTURE calls it on the client thread only.
 */
public final class JumpResetWatch {
	/** Game time of one client tick. */
	public static final int TICK_MS = 50;

	private @Nullable Hit lastHit;
	/** The latest jump input of either kind. */
	private @Nullable Jump lastJump;
	/** The latest real {@code jumpFromGround} (often the same object as {@link #lastJump}). */
	private @Nullable Jump lastRealJump;

	/** A melee hit taken that may still pair with a jump. */
	private static final class Hit {
		final long tick;
		final long nanos;
		final PlayerRef attacker;
		boolean paired;
		/**
		 * A bare press, still held when this grounded hit came, that would be an early attempt unless the
		 * held key jumps on the first tick after the hit ({@link #onTickEnd} decides).
		 */
		@Nullable Jump heldPress;

		Hit(long tick, long nanos, PlayerRef attacker, boolean paired) {
			this.tick = tick;
			this.nanos = nanos;
			this.attacker = attacker;
			this.paired = paired;
		}
	}

	/** A jump that may still pair with a later hit (an early attempt). */
	private static final class Jump {
		final long tick;
		final long nanos;
		/** A real {@code jumpFromGround}, as opposed to a press that produced no jump. */
		final boolean real;
		boolean paired;

		Jump(long tick, long nanos, boolean real, boolean paired) {
			this.tick = tick;
			this.nanos = nanos;
			this.real = real;
			this.paired = paired;
		}
	}

	/**
	 * A jump of the local player.
	 *
	 * @param tick      {@code Clock.ticks()} at the jump
	 * @param nanos     {@code Clock.nanos()} at the jump
	 * @param minMs     success window start (game ms)
	 * @param maxMs     success window end (game ms)
	 * @param attemptMs attempt window (game ms)
	 * @return the decided attempt, or null if this jump does not pair with a hit
	 */
	public @Nullable JumpResetEvent onJump(long tick, long nanos, int minMs, int maxMs, int attemptMs) {
		return jump(tick, nanos, true, minMs, maxMs, attemptMs);
	}

	/**
	 * A rising edge of the local player's jump input (pressed this tick, not pressed the tick before),
	 * sampled at the end of the client tick. It pairs exactly like {@link #onJump}, unless a jump
	 * ({@code jumpFromGround} or another press) was already recorded in the same tick: that is the same
	 * action, already counted, and the press is ignored.
	 *
	 * @param tick      {@code Clock.ticks()} of the tick in which the input went down
	 * @param nanos     {@code Clock.nanos()} when it was sampled
	 * @param minMs     success window start (game ms)
	 * @param maxMs     success window end (game ms)
	 * @param attemptMs attempt window (game ms)
	 * @return the decided attempt, or null if this press does not pair with a hit (or was a duplicate)
	 */
	public @Nullable JumpResetEvent onJumpPress(long tick, long nanos, int minMs, int maxMs, int attemptMs) {
		Jump last = this.lastJump;
		if (last != null && last.tick == tick) {
			return null;
		}
		return jump(tick, nanos, false, minMs, maxMs, attemptMs);
	}

	/**
	 * A melee hit taken by the local player (projectile and thorns hits never reach this).
	 *
	 * @param tick         {@code Clock.ticks()} at the hit
	 * @param nanos        {@code Clock.nanos()} at the hit
	 * @param attacker     who hit you
	 * @param selfOnGround whether you were on the ground when hit
	 * @param jumpHeld     whether the jump input was down at the end of the last tick (what the next tick's
	 *                     {@code aiStep} will most likely read)
	 * @param minMs        success window start (game ms; unused for early attempts, kept for symmetry)
	 * @param maxMs        success window end (game ms; unused for early attempts, kept for symmetry)
	 * @param attemptMs    attempt window (game ms)
	 * @return an early attempt, or null if no recent unpaired jump preceded the hit (or a held press is
	 *         waiting for the next tick's jump, see {@link #onTickEnd})
	 */
	public @Nullable JumpResetEvent onHitTaken(long tick, long nanos, PlayerRef attacker, boolean selfOnGround,
			boolean jumpHeld, int minMs, int maxMs, int attemptMs) {
		Jump jump = this.lastJump;
		if (jump != null && !jump.paired && !jump.real && !selfOnGround) {
			// A press made in the air, then a hit that still finds you in the air: no reset opportunity,
			// and the press belongs to that airborne exchange, so it cannot be early for a later hit either.
			// A real jump before it still can be: jumping too early is what left you in the air.
			this.lastJump = null;
			jump = this.lastRealJump;
		}
		if (jump != null && !jump.paired) {
			long deltaTicks = jump.tick - tick;
			if (deltaTicks <= 0) {
				long deltaMs = gameMs(deltaTicks);
				if (deltaMs >= -(long) attemptMs && deltaMs <= -TICK_MS) {
					if (!jump.real && selfOnGround && jumpHeld) {
						// Still held on the ground: the next tick's aiStep jumps, a reset at 0 ms. Open the window
						// and let that jump decide; onTickEnd scores the press as early if it never comes.
						Hit hit = new Hit(tick, nanos, attacker, false);
						hit.heldPress = jump;
						this.lastHit = hit;
						return null;
					}
					jump.paired = true;
					this.lastHit = new Hit(tick, nanos, attacker, true);
					return attempt(jump.nanos, nanos, deltaTicks, deltaMs, false, attacker);
				}
			}
		}
		// A grounded hit opens a window (replacing any older unpaired one). An airborne hit cannot be
		// reset, so it opens none and also retires the older window.
		this.lastHit = selfOnGround ? new Hit(tick, nanos, attacker, false) : null;
		return null;
	}

	/**
	 * The end of an unpaused client tick, after that tick's jump and jump-input sample. If the open hit is
	 * still unpaired on the first tick after it (or later) and remembered a held press, the expected
	 * {@code jumpFromGround} did not happen, so that press is the early attempt it would have been at the
	 * hit (same deltas, timed by the press).
	 *
	 * @param tick {@code Clock.ticks()} of the tick that just ended
	 * @return the deferred early attempt, or null
	 */
	public @Nullable JumpResetEvent onTickEnd(long tick) {
		Hit hit = this.lastHit;
		if (hit == null || hit.paired || hit.heldPress == null || tick <= hit.tick) {
			return null;
		}
		Jump press = hit.heldPress;
		hit.heldPress = null;
		if (press.paired) {
			return null;
		}
		press.paired = true;
		hit.paired = true;
		long deltaTicks = press.tick - hit.tick;
		return attempt(press.nanos, hit.nanos, deltaTicks, gameMs(deltaTicks), false, hit.attacker);
	}

	/** Forgets every pending hit and jump (world leave, fight end, master switch off). */
	public void reset() {
		this.lastHit = null;
		this.lastJump = null;
		this.lastRealJump = null;
	}

	/** Pairs a jump input (real or press) with the open hit window, or remembers it for an early attempt. */
	private @Nullable JumpResetEvent jump(long tick, long nanos, boolean real, int minMs, int maxMs, int attemptMs) {
		Hit hit = this.lastHit;
		if (hit != null && !hit.paired) {
			long deltaTicks = tick - hit.tick;
			if (deltaTicks >= 1) {
				long deltaMs = gameMs(deltaTicks);
				if (deltaMs >= 0 && deltaMs <= attemptMs) {
					hit.paired = true;
					hit.heldPress = null; // the jump decides; a held press is no longer early
					remember(new Jump(tick, nanos, real, true));
					boolean success = deltaMs >= minMs && deltaMs <= maxMs;
					return attempt(nanos, hit.nanos, deltaTicks, deltaMs, success, hit.attacker);
				}
			}
		}
		remember(new Jump(tick, nanos, real, false));
		return null;
	}

	private void remember(Jump j) {
		this.lastJump = j;
		if (j.real) {
			this.lastRealJump = j;
		}
	}

	/** {@code 50·(deltaTicks - 1)}: game milliseconds after the first tick that followed the hit. */
	public static long gameMs(long deltaTicks) {
		return TICK_MS * (deltaTicks - 1);
	}

	private static JumpResetEvent attempt(long jumpNanos, long hitNanos, long deltaTicks, long deltaMs,
			boolean success, PlayerRef attacker) {
		long wall = Math.round((jumpNanos - hitNanos) / 1_000_000.0);
		return new JumpResetEvent(jumpNanos, hitNanos, clampInt(deltaTicks), clampInt(deltaMs), clampInt(wall),
				success, attacker);
	}

	private static int clampInt(long v) {
		return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, v));
	}
}
