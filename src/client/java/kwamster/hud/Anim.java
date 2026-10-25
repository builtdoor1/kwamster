package kwamster.hud;

/**
 * Easing curves and fade envelopes for the HUD (§7.0). Every time argument is in nanoseconds from
 * {@code Clock.nanos()}; every result is finite and clamped where the doc says so. Minecraft-free (R6).
 */
public final class Anim {
	/** Nanoseconds per millisecond. */
	public static final long MS = 1_000_000L;

	private Anim() {
	}

	/** {@code t} clamped to [0, 1]; NaN counts as 0. */
	public static float clamp01(float t) {
		if (!(t > 0f)) {
			return 0f;
		}
		return t >= 1f ? 1f : t;
	}

	/** {@code 1 - (1 - t)^3} on clamped {@code t}: fast start, soft landing. */
	public static float easeOutCubic(float t) {
		float u = 1f - clamp01(t);
		return 1f - u * u * u;
	}

	/** Quadratic ease-in-out on clamped {@code t}. */
	public static float easeInOutQuad(float t) {
		float c = clamp01(t);
		return c < 0.5f ? 2f * c * c : 1f - (-2f * c + 2f) * (-2f * c + 2f) / 2f;
	}

	/** Linear interpolation {@code a + (b - a)·t} (t not clamped). */
	public static float lerp(float a, float b, float t) {
		return a + (b - a) * t;
	}

	/**
	 * Progress of an animation that started at {@code startNanos} and lasts {@code durationMs}, clamped
	 * to [0, 1]. A non-positive duration is already complete. A "never" start such as
	 * {@link Long#MIN_VALUE} counts as long ago (progress 1): the difference is taken in {@code double},
	 * so it cannot wrap around to a negative age.
	 */
	public static float progress(long nowNanos, long startNanos, long durationMs) {
		if (durationMs <= 0L) {
			return 1f;
		}
		return clamp01((float) (elapsedNanos(nowNanos, startNanos) / (durationMs * (double) MS)));
	}

	/**
	 * Milliseconds from {@code startNanos} to {@code nowNanos} (negative if {@code now} is earlier). Taken
	 * in {@code double}, so a "never" start such as {@link Long#MIN_VALUE} gives a huge positive age
	 * instead of wrapping around.
	 */
	public static double ageMs(long nowNanos, long startNanos) {
		return elapsedNanos(nowNanos, startNanos) / (double) MS;
	}

	/**
	 * {@code nowNanos − startNanos} without long overflow. Exact while both values are within ±2^53 ns
	 * (about 104 days of uptime); beyond that a few ns off, far below the millisecond scale of any HUD
	 * animation.
	 */
	private static double elapsedNanos(long nowNanos, long startNanos) {
		return (double) nowNanos - (double) startNanos;
	}

	/**
	 * A hold-then-fade envelope: 1 until {@code totalMs - fadeOutMs}, then linear to 0 at
	 * {@code totalMs}; 0 before the start and after the end. A {@code fadeInMs > 0} also ramps up
	 * linearly at the start. {@code ageMs} is the time since the start.
	 */
	public static float envelope(double ageMs, double fadeInMs, double totalMs, double fadeOutMs) {
		if (!(ageMs >= 0) || ageMs >= totalMs) {
			return 0f;
		}
		float a = 1f;
		if (fadeInMs > 0 && ageMs < fadeInMs) {
			a = (float) (ageMs / fadeInMs);
		}
		if (fadeOutMs > 0) {
			double remaining = totalMs - ageMs;
			if (remaining < fadeOutMs) {
				a = Math.min(a, (float) (remaining / fadeOutMs));
			}
		}
		return clamp01(a);
	}

	/**
	 * A sine pulse between {@code min} and {@code max} at {@code hz}, phased on {@code nowNanos}, so
	 * every element pulsing at the same rate stays in step.
	 */
	public static float pulse(long nowNanos, double hz, float min, float max) {
		double seconds = (nowNanos % 1_000_000_000_000L) / 1e9;
		double s = 0.5 + 0.5 * Math.sin(seconds * hz * 2.0 * Math.PI);
		return (float) (min + (max - min) * s);
	}

	/**
	 * One step of exponential smoothing toward {@code target} with time constant {@code tauMs}:
	 * {@code current + (target - current)·(1 - e^(-dt/tau))}. A non-positive {@code tauMs} (or a huge
	 * step) snaps to the target; a negative step leaves the value unchanged.
	 */
	public static double approach(double current, double target, long dtNanos, double tauMs) {
		if (!Double.isFinite(current)) {
			return target;
		}
		if (tauMs <= 0 || dtNanos >= 50L * 1_000L * MS) {
			return target;
		}
		if (dtNanos <= 0L) {
			return current;
		}
		double k = 1.0 - Math.exp(-(dtNanos / (double) MS) / tauMs);
		return current + (target - current) * k;
	}

	/**
	 * Moves {@code value} linearly toward {@code target} (each in [0, 1]) so a full 0→1 sweep takes
	 * {@code durationMs}. Used for the panel's card-priority fade.
	 */
	public static float stepToward(float value, float target, long dtNanos, long durationMs) {
		if (durationMs <= 0L || dtNanos >= durationMs * 10L * MS) {
			return target;
		}
		if (dtNanos <= 0L) {
			return value;
		}
		float step = (float) ((double) dtNanos / (durationMs * (double) MS));
		if (value < target) {
			return Math.min(target, value + step);
		}
		return Math.max(target, value - step);
	}
}
