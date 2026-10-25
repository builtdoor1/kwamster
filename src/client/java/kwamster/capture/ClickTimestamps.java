package kwamster.capture;

/**
 * The press time of the most recent Attack click, so S8 hit intervals can be timed from the click
 * rather than from the tick that processed the attack (port of combat-tracker {@code ClickTimestamps}).
 *
 * <p><b>Why.</b> Attacks are processed inside the client tick, so anything timed there is quantised
 * to 50 ms and the natural variation in a human's clicking is rounded away. Mouse buttons and keys
 * arrive through {@code MouseHandler.onButton} / {@code KeyboardHandler.keyPress}, whose queue drains
 * once per frame, so a timestamp taken there is accurate to a frame.
 *
 * <p><b>Why a single slot.</b> A press does not always become an attack (a click while the world
 * loads, on the death screen, as a screen opens). A queue would then pair every later attack with an
 * older and older click. So there is one slot, claimed at most once, and rejected outright when it is
 * older than {@link #MAX_AGE_NANOS}: a stale value cannot accumulate or be reused, and the worst case
 * is one fallback to hook timing ({@code clickLeadMs = 0}).
 *
 * <p>Client thread (both input callbacks and {@code startAttack} run there).
 */
public final class ClickTimestamps {
	/** A press turns into an attack on the same frame or the next one; anything older belongs to an earlier click. */
	public static final long MAX_AGE_NANOS = 200L * 1_000_000L;

	private static boolean pending;
	private static long pressNanos;

	private ClickTimestamps() {
	}

	/** Records an Attack press at {@code nanos} (from the input mixins). */
	public static void record(long nanos) {
		pressNanos = nanos;
		pending = true;
	}

	/**
	 * Claims the press behind the attack being processed at {@code nowNanos}. Claiming clears the slot,
	 * so two attacks in one frame cannot both bill themselves to the same click.
	 *
	 * @return the press time, or {@link Long#MIN_VALUE} when there is no press, or it is older than
	 *         {@link #MAX_AGE_NANOS} or later than {@code nowNanos}
	 */
	public static long claim(long nowNanos) {
		if (!pending) {
			return Long.MIN_VALUE;
		}
		pending = false;
		long age = nowNanos - pressNanos;
		return age < 0 || age > MAX_AGE_NANOS ? Long.MIN_VALUE : pressNanos;
	}

	/** Drops anything pending (world leave, master switch off). */
	public static void clear() {
		pending = false;
	}
}
