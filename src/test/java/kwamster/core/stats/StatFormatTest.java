package kwamster.core.stats;

import org.junit.jupiter.api.Test;

import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Shared number formatting (§4.7 "—" rule, §7.0 truncation). */
final class StatFormatTest {
	private static final ToIntFunction<String> SIX_PX = s -> 6 * s.codePointCount(0, s.length());

	@Test
	void percentages() {
		assertEquals("—", StatFormat.pct(0, 0));
		assertEquals("—", StatFormat.pct(3, -1));
		assertEquals("75%", StatFormat.pct(3, 4));
		assertEquals("67%", StatFormat.pct(2, 3));
		assertEquals("100%", StatFormat.pct(5, 5));
		assertEquals("—", StatFormat.pct(40.0, 0));
		assertEquals("40%", StatFormat.pct(40.0, 3));
		assertEquals("0%", StatFormat.pct(Double.NaN));
	}

	@Test
	void reachHasTwoDecimals() {
		assertEquals("2.80", StatFormat.reach(2.8));
		assertEquals("3.00", StatFormat.reach(3.0));
		assertEquals("2.83", StatFormat.reach(2.836), "rounded down, never up");
		assertEquals("2.84", StatFormat.reach(2.8475));
		assertEquals("—", StatFormat.reach(2.5, 0));
		assertEquals("2.50", StatFormat.reach(2.5, 1));
		assertEquals("0.00", StatFormat.reach(Double.NaN));
		assertEquals("0.00", StatFormat.reach(0.0));
	}

	@Test
	void reachNeverRoundsUpToTheThreeBlockThreshold() {
		assertEquals("2.95", StatFormat.reach(2.95));
		assertEquals("2.95", StatFormat.reach(2.95f), "float widening (2.9500000477) still reads 2.95");
		assertEquals("2.85", StatFormat.reach(2.85f), "float widening (2.8499999046) is inside the epsilon");
		assertEquals("2.95", StatFormat.reach(2.95 - HitGrader.EPSILON / 2), "inside the grading tolerance: 3-block");
		assertEquals("2.94", StatFormat.reach(2.9499));
		assertEquals("2.94", StatFormat.reach(2.947));
		assertEquals("2.94", StatFormat.reach(2.94));
		assertEquals("2.97", StatFormat.reach(2.979));
	}

	@Test
	void overReachNeverReadsAtOrInsideTheRange() {
		assertEquals("3.01", StatFormat.reachVsRange(3.0099, 3.0), "floor would read 3.00 next to (over reach)");
		assertEquals("3.01", StatFormat.reachVsRange(3.0000001, 3.0));
		assertEquals("3.01", StatFormat.reachVsRange(3.01, 3.0));
		assertEquals("3.42", StatFormat.reachVsRange(3.4299, 3.0), "otherwise still rounded down");
		assertEquals("3.00", StatFormat.reachVsRange(3.0, 3.0), "at the range is not over it");
		assertEquals("2.94", StatFormat.reachVsRange(2.9499, 3.0), "inside the range: plain rounding down");
		assertEquals("3.61", StatFormat.reachVsRange(3.6000001, 3.6f), "a float limit that reads 3.60");
		assertEquals("2.70", StatFormat.reachVsRange(2.7, 2.5), "a lowered server range");
		assertEquals("3.00", StatFormat.reachVsRange(3.004, Double.NaN), "no usable range: no comparison");
		assertEquals("3.00", StatFormat.reachVsRange(3.004, 0.0));
		for (int i = 1; i <= 3000; i++) {
			double limit = 2.0 + i * 0.0007;
			double r = limit + 1e-7 * i;
			assertTrue(Double.parseDouble(StatFormat.reachVsRange(r, limit)) > Double.parseDouble(StatFormat.reach(limit)),
					r + " over " + limit);
		}
	}

	/** A shown reach of at least the threshold means the hit graded 3-block, and vice versa (2-decimal thresholds). */
	@Test
	void shownReachAgreesWithTheGrader() {
		for (double threshold : new double[] {2.5, 2.9, 2.95, 3.0}) {
			double shownThreshold = Double.parseDouble(StatFormat.reach(threshold));
			for (int i = -4000; i <= 4000; i++) {
				double reach = threshold + i * 2.5e-6;
				assertAgrees(reach, threshold, shownThreshold);
			}
		}
		// In game both values are floats (FightRecord.Swing.reach, Settings.threeBlockReach). A float threshold
		// is off its decimal by up to 1.2e-7, so one float just below some thresholds is graded 3-block yet shown
		// 0.01 lower; the display may understate by that much but never overstates, for every setting 2.50-3.00.
		for (int cm = 250; cm <= 300; cm++) {
			float threshold = cm / 100f;
			double shownThreshold = Double.parseDouble(StatFormat.reach(threshold));
			assertEquals(cm / 100.0, shownThreshold, 1e-9, "the threshold itself reads as its setting");
			float reach = threshold;
			for (int i = 0; i < 300; i++) {
				reach = Math.nextDown(reach);
			}
			for (int i = 0; i < 600; i++) {
				boolean three = HitGrader.grade(true, false, false, reach, threshold, false).threeBlock();
				if (Double.parseDouble(StatFormat.reach(reach)) >= shownThreshold) {
					assertTrue(three, "reach " + reach + " shown " + StatFormat.reach(reach) + " but not 3-block at " + threshold);
				}
				reach = Math.nextUp(reach);
			}
		}
	}

	private static void assertAgrees(double reach, double threshold, double shownThreshold) {
		boolean three = HitGrader.grade(true, false, false, reach, threshold, false).threeBlock();
		boolean shownAtLeast = Double.parseDouble(StatFormat.reach(reach)) >= shownThreshold;
		assertEquals(three, shownAtLeast, "reach " + reach + " vs threshold " + threshold + " shown " + StatFormat.reach(reach));
	}

	@Test
	void durations() {
		assertEquals("0:42", StatFormat.duration(42_000));
		assertEquals("0:42", StatFormat.duration(42_999));
		assertEquals("1:02", StatFormat.duration(62_000));
		assertEquals("12:05", StatFormat.duration(725_000));
		assertEquals("1:02:03", StatFormat.duration(3_723_000));
		assertEquals("0:00", StatFormat.duration(-5));
	}

	@Test
	void signedValues() {
		assertEquals("+3.1", StatFormat.signed(3.14, 1));
		assertEquals("-2.4", StatFormat.signed(-2.44, 1));
		assertEquals("+0.0", StatFormat.signed(-0.04, 1), "never -0.0");
		assertEquals("+12", StatFormat.signed(12L));
		assertEquals("-3", StatFormat.signed(-3L));
		assertEquals("+0", StatFormat.signed(0L));
	}

	@Test
	void millisecondsAndRatios() {
		assertEquals("548 ms", StatFormat.ms(548.4));
		assertEquals("—", StatFormat.ms(548.4, 0));
		assertEquals("5/7", StatFormat.ratio(5, 7));
	}

	@Test
	void truncate() {
		// 60 px with 6 px glyphs = 10 glyphs: 7 of the name plus "...".
		assertEquals("Opponen...", StatFormat.truncate("Opponent_Name_123", 60, SIX_PX));
		assertEquals(60, SIX_PX.applyAsInt(StatFormat.truncate("Opponent_Name_123", 60, SIX_PX)));
		assertEquals("Opponen...", StatFormat.truncate("Opponent_Name_123", 65, SIX_PX), "the longest prefix that fits");
		assertEquals("Kwamix", StatFormat.truncate("Kwamix", 60, SIX_PX), "a string that fits is unchanged");
		assertEquals("Kwamix", StatFormat.truncate("Kwamix", 36, SIX_PX));
		assertEquals("..", StatFormat.truncate("Kwamix", 12, SIX_PX), "not even the ellipsis fits");
		assertEquals("", StatFormat.truncate("Kwamix", 2, SIX_PX));
		assertEquals("", StatFormat.truncate(null, 60, SIX_PX));
		assertEquals("😀...", StatFormat.truncate("😀😀😀😀😀", 24, SIX_PX),
				"surrogate pairs are never split");
	}
}
