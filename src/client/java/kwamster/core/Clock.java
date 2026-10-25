package kwamster.core;

import org.jspecify.annotations.Nullable;

import java.util.function.LongSupplier;

/**
 * The time sources every unit uses (R9): {@link #nanos()} for event timestamps
 * ({@code System.nanoTime()}), {@link #epochMs()} for wall-clock anchors, and {@link #ticks()}, the
 * number of client ticks started (incremented at {@code START_CLIENT_TICK}, §5.1).
 *
 * <p>The time sources can be overridden for tests. Minecraft-free.
 */
public final class Clock {
	private static volatile LongSupplier nanosSource = System::nanoTime;
	private static volatile LongSupplier epochSource = System::currentTimeMillis;
	private static volatile long ticks;

	private Clock() {
	}

	/** Monotonic nanoseconds ({@code System.nanoTime()} unless overridden). */
	public static long nanos() {
		return nanosSource.getAsLong();
	}

	/** Wall-clock epoch milliseconds ({@code System.currentTimeMillis()} unless overridden). */
	public static long epochMs() {
		return epochSource.getAsLong();
	}

	/** Client ticks started since launch. A packet processed between ticks gets the index of the last tick that ran. */
	public static long ticks() {
		return ticks;
	}

	/** Called by CORE at {@code START_CLIENT_TICK}, on the client thread. */
	public static void onTickStart() {
		ticks++;
	}

	/** Test hook: replaces the time sources; null restores the system clock. */
	public static void setSources(@Nullable LongSupplier nanos, @Nullable LongSupplier epochMs) {
		nanosSource = nanos == null ? System::nanoTime : nanos;
		epochSource = epochMs == null ? System::currentTimeMillis : epochMs;
	}

	/** Test hook: sets the tick index. */
	public static void setTicks(long value) {
		ticks = value;
	}

	/** Test hook: restores the system clock and resets the tick index to 0. */
	public static void reset() {
		nanosSource = System::nanoTime;
		epochSource = System::currentTimeMillis;
		ticks = 0L;
	}
}
