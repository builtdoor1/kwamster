package kwamster.crosshair;

import kwamster.core.config.KwamsterConfig.CrosshairShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Crosshair geometry (§7.8): shape parameters to boolean pixel masks to merged row spans.
 *
 * <p>Everything is in <b>physical</b> (framebuffer) pixels. {@code (cx, cy)} is a pixel-edge
 * coordinate: the screen centre lies on the boundary between pixels {@code cx - 1} and {@code cx}.
 * The fill mask is the union of the shape's parts; the outline mask is the fill dilated by
 * {@code outline} pixels (Chebyshev) minus the fill, so outline and fill are disjoint and every
 * pixel is painted exactly once (a translucent outline never stacks into darker bands).
 *
 * <p>Each mask is emitted row by row as maximal horizontal runs, and runs that repeat unchanged on
 * consecutive rows are merged into one rect. No rect overlaps another. Shapes are pixel-aligned
 * (the X is a staircase, never a rotated quad), so they stay crisp at any GUI scale.
 *
 * <p>Minecraft-free (R6): pure integer geometry, safe to unit-test without the game.
 */
public final class CrosshairShapes {
	/**
	 * Upper bound for every size parameter after GUI scaling. The config ranges times the largest
	 * realistic GUI-scale factor stay well below it; it only keeps a corrupt value from allocating a
	 * huge mask.
	 */
	public static final int MAX_PARAM = 512;

	private CrosshairShapes() {
	}

	/**
	 * Half-open rects to paint, each {@code {x0, y0, x1, y1}} in physical pixels. Outline rects are
	 * drawn first (outline colour), then fill rects (mode colour). Both lists are unmodifiable,
	 * sorted by {@code y0} then {@code x0}, and pairwise disjoint (also across the two lists).
	 *
	 * @param outline merged spans of the outline mask (empty when the outline is 0)
	 * @param fill    merged spans of the fill mask
	 */
	public record Spans(List<int[]> outline, List<int[]> fill) {
		public Spans {
			outline = Collections.unmodifiableList(new ArrayList<>(outline));
			fill = Collections.unmodifiableList(new ArrayList<>(fill));
		}
	}

	/**
	 * Builds the crosshair spans.
	 *
	 * @param shape     the shape (null is treated as CROSS)
	 * @param size      arm length (CROSS, T, X), outer radius (CIRCLE) or diameter (DOT), in px
	 * @param gap       empty pixels between the core and each arm (CROSS, T, X); 0 also fills the core
	 * @param thickness arm or ring thickness {@code t}, at least 1
	 * @param centerDot side of the centre dot in px, 0 = none
	 * @param outline   outline width in px, 0 = none
	 * @param cx        pixel-edge centre x (framebuffer width / 2)
	 * @param cy        pixel-edge centre y (framebuffer height / 2)
	 */
	public static Spans build(CrosshairShape shape, int size, int gap, int thickness, int centerDot, int outline, int cx, int cy) {
		CrosshairShape s = shape == null ? CrosshairShape.CROSS : shape;
		int sz = clamp(size, 0, MAX_PARAM);
		int gp = clamp(gap, 0, MAX_PARAM);
		int t = clamp(thickness, 1, MAX_PARAM);
		int dot = clamp(centerDot, 0, MAX_PARAM);
		int o = clamp(outline, 0, MAX_PARAM);

		// Furthest any fill pixel can lie from the centre, plus the outline and one spare pixel.
		int extent = switch (s) {
			case CROSS, T, X -> gp + sz + t;
			case CIRCLE -> sz + 1;
			case DOT -> sz / 2 + 1;
		};
		extent = Math.max(extent, t);
		if (dot > 0) {
			extent = Math.max(extent, dot / 2 + 1);
		}
		int r = extent + o + 1;
		Mask fill = new Mask(cx - r, cy - r, 2 * r);

		int half = t / 2;
		int left = cx - half;
		int top = cy - half;
		int right = left + t;
		int bottom = top + t;
		switch (s) {
			case CROSS, T -> {
				fill.rect(left - gp - sz, top, left - gp, bottom);          // left arm
				fill.rect(right + gp, top, right + gp + sz, bottom);        // right arm
				if (s == CrosshairShape.CROSS) {
					fill.rect(left, top - gp - sz, right, top - gp);        // top arm
				}
				fill.rect(left, bottom + gp, right, bottom + gp + sz);      // bottom arm
				if (gp == 0) {
					fill.rect(left, top, right, bottom);                    // core
				}
			}
			case X -> {
				for (int k = gp; k < gp + sz; k++) {
					for (int sx = -1; sx <= 1; sx += 2) {
						int x0 = sx > 0 ? cx + k : cx - k - t;
						for (int sy = -1; sy <= 1; sy += 2) {
							int y0 = sy > 0 ? cy + k : cy - k - t;
							fill.rect(x0, y0, x0 + t, y0 + t);
						}
					}
				}
				if (gp == 0) {
					fill.rect(left, top, right, bottom);
				}
			}
			case CIRCLE -> {
				// size - t <= d < size, with d the distance of the pixel centre from (cx, cy).
				// Compared on 4·d² (an exact integer) so no pixel flickers on a float boundary.
				long inner = Math.max(0, sz - t);
				fill.disc(cx, cy, 4L * inner * inner, 4L * sz * sz);
			}
			case DOT -> {
				// d < size/2  <=>  4·d² < size²
				fill.disc(cx, cy, 0L, (long) sz * sz);
				if (sz == 1) {
					// A 1 px disc around a pixel-edge centre covers no pixel centre; draw the single
					// pixel the core rule places there instead of nothing.
					fill.rect(cx, cy, cx + 1, cy + 1);
				}
			}
		}
		if (dot > 0) {
			// A dot × dot square placed like the core: exact for even sizes, 0.5 px off for odd ones.
			int dl = cx - dot / 2;
			int dt = cy - dot / 2;
			fill.rect(dl, dt, dl + dot, dt + dot);
		}

		List<int[]> outlineSpans = List.of();
		if (o > 0) {
			Mask ring = fill.dilate(o);
			ring.subtract(fill);
			outlineSpans = ring.spans();
		}
		return new Spans(outlineSpans, fill.spans());
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** A square boolean pixel mask whose cell (0, 0) is the physical pixel {@code (ox, oy)}. */
	private static final class Mask {
		final int ox;
		final int oy;
		final int n;
		final boolean[] px;

		Mask(int ox, int oy, int n) {
			this.ox = ox;
			this.oy = oy;
			this.n = n;
			this.px = new boolean[n * n];
		}

		/** Sets every pixel of the half-open rect {@code [x0, x1) × [y0, y1)}, clipped to the mask. */
		void rect(int x0, int y0, int x1, int y1) {
			int a = Math.max(x0 - this.ox, 0);
			int b = Math.min(x1 - this.ox, this.n);
			int c = Math.max(y0 - this.oy, 0);
			int d = Math.min(y1 - this.oy, this.n);
			for (int y = c; y < d; y++) {
				int row = y * this.n;
				for (int x = a; x < b; x++) {
					this.px[row + x] = true;
				}
			}
		}

		/**
		 * Sets every pixel whose centre {@code (x + 0.5, y + 0.5)} lies at a distance d from
		 * {@code (cx, cy)} with {@code min4 <= 4·d² < max4}.
		 */
		void disc(int cx, int cy, long min4, long max4) {
			for (int y = 0; y < this.n; y++) {
				long dy = 2L * (this.oy + y) + 1 - 2L * cy;
				long dy2 = dy * dy;
				int row = y * this.n;
				for (int x = 0; x < this.n; x++) {
					long dx = 2L * (this.ox + x) + 1 - 2L * cx;
					long d4 = dx * dx + dy2;
					if (d4 >= min4 && d4 < max4) {
						this.px[row + x] = true;
					}
				}
			}
		}

		/** A new mask grown by an {@code o}-pixel square (Chebyshev dilation), as two separable passes. */
		Mask dilate(int o) {
			int size = this.n;
			boolean[] horizontal = new boolean[size * size];
			for (int y = 0; y < size; y++) {
				int row = y * size;
				int count = 0;
				// Sliding window [x - o, x + o]; prime it with [0, o - 1].
				for (int x = 0; x < Math.min(o, size); x++) {
					if (this.px[row + x]) {
						count++;
					}
				}
				for (int x = 0; x < size; x++) {
					int enter = x + o;
					if (enter < size && this.px[row + enter]) {
						count++;
					}
					int leave = x - o - 1;
					if (leave >= 0 && this.px[row + leave]) {
						count--;
					}
					horizontal[row + x] = count > 0;
				}
			}
			Mask out = new Mask(this.ox, this.oy, size);
			for (int x = 0; x < size; x++) {
				int count = 0;
				for (int y = 0; y < Math.min(o, size); y++) {
					if (horizontal[y * size + x]) {
						count++;
					}
				}
				for (int y = 0; y < size; y++) {
					int enter = y + o;
					if (enter < size && horizontal[enter * size + x]) {
						count++;
					}
					int leave = y - o - 1;
					if (leave >= 0 && horizontal[leave * size + x]) {
						count--;
					}
					out.px[y * size + x] = count > 0;
				}
			}
			return out;
		}

		/** Clears every pixel set in {@code other} (same origin and size). */
		void subtract(Mask other) {
			for (int i = 0; i < this.px.length; i++) {
				if (other.px[i]) {
					this.px[i] = false;
				}
			}
		}

		/**
		 * Maximal horizontal runs per row, with runs repeated unchanged on consecutive rows merged
		 * into one rect. Returns absolute {@code {x0, y0, x1, y1}} rects sorted by y0, then x0.
		 */
		List<int[]> spans() {
			List<int[]> closed = new ArrayList<>();
			List<int[]> open = new ArrayList<>();
			List<int[]> runs = new ArrayList<>();
			for (int y = 0; y < this.n; y++) {
				runs.clear();
				int row = y * this.n;
				int x = 0;
				while (x < this.n) {
					if (!this.px[row + x]) {
						x++;
						continue;
					}
					int start = x;
					while (x < this.n && this.px[row + x]) {
						x++;
					}
					runs.add(new int[] {this.ox + start, this.ox + x});
				}
				int absY = this.oy + y;
				// open and runs are both sorted by x0 and internally disjoint: merge them in one pass.
				List<int[]> next = new ArrayList<>(runs.size());
				int i = 0;
				int j = 0;
				while (i < open.size() || j < runs.size()) {
					if (i < open.size() && j < runs.size()) {
						int[] rect = open.get(i);
						int[] run = runs.get(j);
						if (rect[0] == run[0] && rect[2] == run[1]) {
							rect[3] = absY + 1;
							next.add(rect);
							i++;
							j++;
						} else if (rect[0] <= run[0]) {
							closed.add(rect);
							i++;
						} else {
							next.add(new int[] {run[0], absY, run[1], absY + 1});
							j++;
						}
					} else if (i < open.size()) {
						closed.add(open.get(i++));
					} else {
						int[] run = runs.get(j++);
						next.add(new int[] {run[0], absY, run[1], absY + 1});
					}
				}
				open = next;
			}
			closed.addAll(open);
			closed.sort(Comparator.<int[]>comparingInt(a -> a[1]).thenComparingInt(a -> a[0]));
			return closed;
		}
	}
}
