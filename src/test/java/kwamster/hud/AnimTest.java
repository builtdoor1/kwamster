package kwamster.hud;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7.0 animation helpers: progress and age, including the "never" start sentinel. */
final class AnimTest {
	/** A real {@code System.nanoTime()} value (positive on Windows, Linux and macOS). */
	private static final long NOW = 25_955_348_095_400L;

	@Test
	void progressAndAge() {
		long start = NOW;
		assertEquals(0.5f, Anim.progress(start + 75 * Anim.MS, start, 150), 1e-6f);
		assertEquals(0f, Anim.progress(start - Anim.MS, start, 150), "before the start");
		assertEquals(1f, Anim.progress(start + 200 * Anim.MS, start, 150));
		assertEquals(1f, Anim.progress(start, start, 0), "a zero duration is complete");
		assertEquals(75.0, Anim.ageMs(start + 75 * Anim.MS, start), 1e-9);
		assertEquals(-1.0, Anim.ageMs(start - Anim.MS, start), 1e-9);
	}

	/**
	 * Regression: {@code now − Long.MIN_VALUE} overflowed to a negative age, so a never-set pop
	 * timestamp drew the combo counter stuck at its 1.25× pop size.
	 */
	@Test
	void aNeverStartCountsAsLongAgo() {
		assertEquals(1f, Anim.progress(NOW, Long.MIN_VALUE, 150));
		assertEquals(1f, Anim.progress(-5L, Long.MIN_VALUE, 150));
		assertEquals(1f, Anim.progress(Long.MAX_VALUE, Long.MIN_VALUE, 150));
		assertEquals(1f, Anim.lerp(1.25f, 1f, Anim.easeOutCubic(Anim.progress(NOW, Long.MIN_VALUE, 150))), "no pop");
		double age = Anim.ageMs(NOW, Long.MIN_VALUE);
		assertTrue(age > 1e12, "age " + age);
		assertEquals(0f, Anim.envelope(age, 0, 1000, 250), "long expired");
	}
}
