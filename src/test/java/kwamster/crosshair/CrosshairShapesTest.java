package kwamster.crosshair;

import kwamster.core.config.KwamsterConfig.CrosshairShape;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §7.8 crosshair geometry in physical pixels. {@code (cx, cy) = (W/2, H/2)} with integer division is a
 * pixel-edge coordinate; the true screen centre is {@code (W/2.0, H/2.0)}.
 */
final class CrosshairShapesTest {
	private static final int[][] FRAMEBUFFERS = {{854, 480}, {855, 481}, {1920, 1080}, {1921, 1079}};
	private static final int[] THICKNESS = {1, 2, 3};
	private static final int[] DOTS = {0, 2, 3};
	private static final int[] OUTLINES = {0, 1, 2};

	/** Pixel key. */
	private static long key(int x, int y) {
		return ((long) x << 32) ^ (y & 0xFFFFFFFFL);
	}

	/** Every pixel of a span list; fails if any pixel is painted twice. */
	private static Set<Long> pixels(List<int[]> spans, Set<Long> alreadyPainted, String where) {
		Set<Long> out = new HashSet<>();
		for (int[] r : spans) {
			assertEquals(4, r.length);
			assertTrue(r[0] < r[2] && r[1] < r[3], where + " empty span");
			for (int y = r[1]; y < r[3]; y++) {
				for (int x = r[0]; x < r[2]; x++) {
					long k = key(x, y);
					assertTrue(out.add(k) && !alreadyPainted.contains(k), where + " pixel painted twice at " + x + "," + y);
				}
			}
		}
		return out;
	}

	/** {minX, minY, maxX(excl), maxY(excl)} of the spans. */
	private static int[] bbox(List<int[]> spans) {
		int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
		for (int[] r : spans) {
			b[0] = Math.min(b[0], r[0]);
			b[1] = Math.min(b[1], r[1]);
			b[2] = Math.max(b[2], r[2]);
			b[3] = Math.max(b[3], r[3]);
		}
		return b;
	}

	@Test
	void everyShapeIsCentredDisjointAndOutlinedCorrectly() {
		for (CrosshairShape shape : CrosshairShape.values()) {
			for (int[] fb : FRAMEBUFFERS) {
				int cx = fb[0] / 2;
				int cy = fb[1] / 2;
				for (int t : THICKNESS) {
					for (int dot : DOTS) {
						for (int o : OUTLINES) {
							for (int gap : new int[] {0, 3}) {
								int size = shape == CrosshairShape.DOT ? 6 : 7;
								String where = shape + " fb=" + fb[0] + "x" + fb[1] + " t=" + t + " dot=" + dot + " o=" + o + " gap=" + gap;
								CrosshairShapes.Spans s = CrosshairShapes.build(shape, size, gap, t, dot, o, cx, cy);
								check(s, shape, fb, o, where);
							}
						}
					}
				}
			}
		}
	}

	private static void check(CrosshairShapes.Spans s, CrosshairShape shape, int[] fb, int o, String where) {
		assertFalse(s.fill().isEmpty(), where + " has fill");
		Set<Long> fill = pixels(s.fill(), Set.of(), where + " fill");
		Set<Long> outline = pixels(s.outline(), fill, where + " outline");
		int[] b = bbox(s.fill());
		double centreX = (b[0] + b[2]) / 2.0;
		double centreY = (b[1] + b[3]) / 2.0;
		assertTrue(Math.abs(centreX - fb[0] / 2.0) <= 0.5 + 1e-9, where + " x centre " + centreX);
		if (shape != CrosshairShape.T) {
			assertTrue(Math.abs(centreY - fb[1] / 2.0) <= 0.5 + 1e-9, where + " y centre " + centreY);
		}
		if (o == 0) {
			assertTrue(outline.isEmpty(), where + " no outline");
			return;
		}
		// Outline = fill dilated by o (Chebyshev) minus fill: complete and nothing else.
		Set<Long> expected = new HashSet<>();
		for (long k : fill) {
			int x = (int) (k >> 32);
			int y = (int) k;
			for (int dy = -o; dy <= o; dy++) {
				for (int dx = -o; dx <= o; dx++) {
					long n = key(x + dx, y + dy);
					if (!fill.contains(n)) {
						expected.add(n);
					}
				}
			}
		}
		assertEquals(expected, outline, where + " outline mask");
	}

	@Test
	void defaultsAreTwentyPixelsWideWithTheOutlineTwentyTwo() {
		// CROSS, size 6, gap 3, thickness 2, outline 1: 2·(6 + 3) + 2 = 20.
		CrosshairShapes.Spans s = CrosshairShapes.build(CrosshairShape.CROSS, 6, 3, 2, 0, 1, 427, 240);
		int[] fill = bbox(s.fill());
		assertEquals(20, fill[2] - fill[0]);
		assertEquals(20, fill[3] - fill[1]);
		assertEquals(417, fill[0], "symmetric about the pixel edge 427");
		assertEquals(437, fill[2]);
		int[] outline = bbox(s.outline());
		assertEquals(22, outline[2] - outline[0]);
		assertEquals(22, outline[3] - outline[1]);
		// Four arms, each merged into one rect.
		assertEquals(4, s.fill().size());
		assertTrue(s.fill().stream().anyMatch(r -> r[0] == 417 && r[1] == 239 && r[2] == 423 && r[3] == 241), "left arm");
	}

	@Test
	void gapZeroAddsTheCore() {
		CrosshairShapes.Spans gap = CrosshairShapes.build(CrosshairShape.CROSS, 6, 3, 2, 0, 0, 427, 240);
		CrosshairShapes.Spans none = CrosshairShapes.build(CrosshairShape.CROSS, 6, 0, 2, 0, 0, 427, 240);
		Set<Long> withGap = pixels(gap.fill(), Set.of(), "gap");
		Set<Long> solid = pixels(none.fill(), Set.of(), "solid");
		for (int x = 426; x < 428; x++) {
			for (int y = 239; y < 241; y++) {
				assertFalse(withGap.contains(key(x, y)), "core empty with a gap");
				assertTrue(solid.contains(key(x, y)), "core filled with gap 0");
			}
		}
		// Gap 0: one plus-shaped blob of 2·6 + 2 = 14 px per axis.
		int[] b = bbox(none.fill());
		assertEquals(14, b[2] - b[0]);
		for (CrosshairShape xShape : new CrosshairShape[] {CrosshairShape.X}) {
			Set<Long> x0 = pixels(CrosshairShapes.build(xShape, 5, 0, 2, 0, 0, 427, 240).fill(), Set.of(), "x gap 0");
			assertTrue(x0.contains(key(426, 239)) && x0.contains(key(427, 240)), "X with gap 0 covers the core");
		}
	}

	@Test
	void tHasNoTopArm() {
		int t = 2;
		CrosshairShapes.Spans s = CrosshairShapes.build(CrosshairShape.T, 6, 3, t, 0, 0, 427, 240);
		int top = 240 - t / 2;
		int[] b = bbox(s.fill());
		assertEquals(top, b[1], "nothing above the core row");
		assertEquals(3, s.fill().size(), "left, right and bottom arms");
	}

	@Test
	void xIsAPixelAlignedStaircase() {
		int cx = 427;
		int cy = 240;
		int t = 2;
		int gap = 2;
		int size = 5;
		Set<Long> expected = new HashSet<>();
		for (int k = gap; k < gap + size; k++) {
			for (int sx : new int[] {-1, 1}) {
				for (int sy : new int[] {-1, 1}) {
					int x0 = sx > 0 ? cx + k : cx - k - t;
					int y0 = sy > 0 ? cy + k : cy - k - t;
					for (int y = y0; y < y0 + t; y++) {
						for (int x = x0; x < x0 + t; x++) {
							expected.add(key(x, y));
						}
					}
				}
			}
		}
		CrosshairShapes.Spans s = CrosshairShapes.build(CrosshairShape.X, size, gap, t, 0, 0, cx, cy);
		assertEquals(expected, pixels(s.fill(), Set.of(), "X"));
	}

	@Test
	void circleAndDotFollowTheirDistanceRules() {
		int cx = 427;
		int cy = 240;
		Set<Long> ring = pixels(CrosshairShapes.build(CrosshairShape.CIRCLE, 6, 0, 2, 0, 0, cx, cy).fill(), Set.of(), "circle");
		Set<Long> disc = pixels(CrosshairShapes.build(CrosshairShape.DOT, 6, 0, 1, 0, 0, cx, cy).fill(), Set.of(), "dot");
		for (int y = cy - 10; y < cy + 10; y++) {
			for (int x = cx - 10; x < cx + 10; x++) {
				double d = Math.hypot(x + 0.5 - cx, y + 0.5 - cy);
				assertEquals(d >= 4 && d < 6, ring.contains(key(x, y)), "circle at " + x + "," + y);
				assertEquals(d < 3, disc.contains(key(x, y)), "dot at " + x + "," + y);
			}
		}
	}

	@Test
	void centreDotIsCentred() {
		Set<Long> even = pixels(CrosshairShapes.build(CrosshairShape.CROSS, 6, 3, 2, 2, 0, 427, 240).fill(), Set.of(), "dot 2");
		for (int x = 426; x < 428; x++) {
			for (int y = 239; y < 241; y++) {
				assertTrue(even.contains(key(x, y)));
			}
		}
		assertFalse(even.contains(key(425, 240)));
		assertFalse(even.contains(key(428, 240)));
		// Odd dot: [cx − 1, cx + 2), 0.5 px off like an odd thickness.
		Set<Long> odd = pixels(CrosshairShapes.build(CrosshairShape.CIRCLE, 8, 0, 1, 3, 0, 427, 240).fill(), Set.of(), "dot 3");
		int count = 0;
		for (int x = 420; x < 435; x++) {
			for (int y = 233; y < 248; y++) {
				if (odd.contains(key(x, y)) && Math.abs(x + 0.5 - 427.5) < 1.6 && Math.abs(y + 0.5 - 240.5) < 1.6) {
					count++;
				}
			}
		}
		assertEquals(9, count, "a 3 × 3 dot");
	}

	@Test
	void spansAreSortedAndMergedRowRuns() {
		CrosshairShapes.Spans s = CrosshairShapes.build(CrosshairShape.CIRCLE, 12, 0, 3, 0, 2, 960, 540);
		for (List<int[]> list : List.of(s.outline(), s.fill())) {
			for (int i = 1; i < list.size(); i++) {
				int[] a = list.get(i - 1);
				int[] b = list.get(i);
				assertTrue(a[1] < b[1] || (a[1] == b[1] && a[0] < b[0]), "sorted by y0 then x0");
			}
		}
	}
}
