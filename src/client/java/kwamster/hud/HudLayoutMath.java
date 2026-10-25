package kwamster.hud;

import kwamster.core.config.Anchor;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The HUD's geometry (§7.2, §7.4–7.9): anchor ↔ position, drawing-time clamping, auto-anchor on drop,
 * snapping, scale steps, and the size formulas of every element, so the elements, the editor and the
 * fit tests share one set of numbers. All sizes are unscaled GUI pixels unless a method says otherwise.
 * Minecraft-free (R6).
 *
 * <p>Position of an element of drawn size {@code w × h} on a {@code W × H} GUI-scaled screen:
 * <pre>
 * x = round(a.fx·W + offsetX − a.fx·w)      y = round(a.fy·H + offsetY − a.fy·h)
 * x = clamp(x, 0, max(0, W − w))             y = clamp(y, 0, max(0, H − h))   // when drawn only
 * </pre>
 */
public final class HudLayoutMath {
	// ---- snapping (§7.2) ----------------------------------------------------------
	/** An axis snaps when within this many pixels of a candidate. */
	public static final int SNAP_DISTANCE = 4;
	/** Screen margin candidate. */
	public static final int SNAP_MARGIN = 4;
	/** Gap used when stacking next to another element. */
	public static final int STACK_GAP = 2;

	// ---- stats panel (§7.4) -------------------------------------------------------
	public static final int PANEL_PAD_X = 6;
	public static final int PANEL_PAD_Y = 4;
	public static final int PANEL_PAD_X_COMPACT = 4;
	public static final int PANEL_PAD_Y_COMPACT = 3;
	public static final int PANEL_ROW_H = 10;
	public static final int PANEL_HEADER_H = 10;
	/** 1 px margin, 1 px line, 1 px margin. */
	public static final int PANEL_DIVIDER_H = 3;
	public static final int PANEL_MIN_W = 128;
	public static final int PANEL_MIN_W_COMPACT = 92;
	public static final int PANEL_MAX_W = 168;
	public static final int ADV_BAR_W = 32;
	public static final int ADV_BAR_H = 3;
	/** Between the advantage word and its bar. */
	public static final int ADV_BAR_GAP = 4;

	// ---- summary card (§7.9) ------------------------------------------------------
	public static final int CARD_PAD_X = 8;
	public static final int CARD_PAD_Y = 5;
	public static final int CARD_GUTTER = 6;
	public static final int CARD_COL_MIN = 72;
	public static final int CARD_COL_MAX = 88;
	public static final int CARD_BAR_H = 2;
	public static final int CARD_HEADER_H = 9;
	/** 2 px margin, 1 px line, 2 px margin. */
	public static final int CARD_DIVIDER_H = 5;
	public static final int CARD_ROW_H = 10;
	public static final int CARD_ROWS = 3;
	/** 2 + 5 + 9 + 5 + 3·10 + 5. */
	public static final int CARD_HEIGHT = CARD_BAR_H + CARD_PAD_Y + CARD_HEADER_H + CARD_DIVIDER_H + CARD_ROWS * CARD_ROW_H + CARD_PAD_Y;
	public static final int CARD_FOOTER_H = 10;
	public static final int CARD_PROGRESS_W = 24;
	/** Between the bold result word and "vs". */
	public static final int CARD_RESULT_GAP = 5;

	// ---- hit indicator (§7.5) -----------------------------------------------------
	public static final int INDICATOR_H = 11;
	public static final int INDICATOR_BAR_W = 2;
	public static final int INDICATOR_PAD = 4;

	// ---- combo counter (§7.6) -----------------------------------------------------
	public static final int COMBO_W = 30;
	public static final int COMBO_H = 26;
	/** The number is drawn at this integer font scale. */
	public static final int COMBO_NUMBER_SCALE = 2;
	/** Top of the 2× number: its 1 px outline then starts at row 0, inside the box. */
	public static final int COMBO_NUMBER_Y = 1;
	/** Top of the 1× "COMBO" caption. */
	public static final int COMBO_CAPTION_Y = 17;
	/** Between the 2× number's last glyph column and the 2× "×" of a broken combo (the outlines take 1 of these). */
	public static final int COMBO_MARK_GAP = 2;

	// ---- stoplight (§7.7) ---------------------------------------------------------
	public static final int LIGHT = 6;
	public static final int LIGHT_PAD = 2;
	/** Between the housing and its label. */
	public static final int STOPLIGHT_LABEL_GAP = 3;
	/** The label flips sides rather than come closer than this to a screen edge. */
	public static final int STOPLIGHT_LABEL_EDGE = 2;

	private HudLayoutMath() {
	}

	// ---- anchor ↔ position ----------------------------------------------------------

	/** {@code round(base·scale)}, at least 1 for a positive base. */
	public static int scaled(int base, float scale) {
		if (base <= 0) {
			return 0;
		}
		float s = Float.isFinite(scale) ? scale : 1f;
		return Math.max(1, Math.round(base * s));
	}

	/** Unclamped left edge: {@code round(fx·W + offsetX − fx·w)}. */
	public static int x(Anchor a, int offsetX, int w, int screenW) {
		return Math.round(a.fx * screenW + offsetX - a.fx * w);
	}

	/** Unclamped top edge: {@code round(fy·H + offsetY − fy·h)}. */
	public static int y(Anchor a, int offsetY, int h, int screenH) {
		return Math.round(a.fy * screenH + offsetY - a.fy * h);
	}

	/** {@code clamp(pos, 0, max(0, screen − size))}: keeps a drawn element on screen. */
	public static int clampPos(int pos, int size, int screen) {
		return Math.max(0, Math.min(pos, Math.max(0, screen - size)));
	}

	/** The drawn rect of an element with base size {@code baseW × baseH} and layout {@code l}, clamped on screen. */
	public static Rect rect(HudLayout l, int baseW, int baseH, int screenW, int screenH) {
		int w = scaled(baseW, l.scale);
		int h = scaled(baseH, l.scale);
		Anchor a = l.anchor == null ? Anchor.TOP_LEFT : l.anchor;
		int x = clampPos(x(a, l.offsetX, w, screenW), w, screenW);
		int y = clampPos(y(a, l.offsetY, h, screenH), h, screenH);
		return new Rect(x, y, w, h);
	}

	/**
	 * The offset along one axis that puts an element of drawn size {@code size} at {@code pos} for an
	 * anchor fraction {@code f}: {@code pos − f·screen + f·size}, rounded half down so that
	 * {@link #x}/{@link #y} give back exactly {@code pos}.
	 */
	public static int offsetFor(float f, int pos, int size, int screen) {
		double anchorPart = (double) f * screen - (double) f * size;
		return (int) Math.ceil(pos - anchorPart - 0.5);
	}

	/** A copy of {@code l} (same anchor and scale) whose offsets put the element's top-left at {@code (x, y)}. */
	public static HudLayout withPosition(HudLayout l, int x, int y, int w, int h, int screenW, int screenH) {
		Anchor a = l.anchor == null ? Anchor.TOP_LEFT : l.anchor;
		return new HudLayout(a, offsetFor(a.fx, x, w, screenW), offsetFor(a.fy, y, h, screenH), l.scale);
	}

	/**
	 * Auto-anchor on drop (§7.2): picks the anchor from the third of the screen the element's centre is
	 * in ({@code fx = cx < W/3 ? 0 : cx > 2W/3 ? 1 : 0.5}, same for y) and recomputes the offsets so the
	 * element does not move.
	 */
	public static HudLayout autoAnchor(Rect r, float scale, int screenW, int screenH) {
		float fx = anchorFraction(r.x() + r.w() / 2.0, screenW);
		float fy = anchorFraction(r.y() + r.h() / 2.0, screenH);
		Anchor a = Anchor.of(fx, fy);
		return new HudLayout(a, offsetFor(a.fx, r.x(), r.w(), screenW), offsetFor(a.fy, r.y(), r.h(), screenH), scale);
	}

	/** 0, 0.5 or 1 for a centre coordinate in the first, middle or last third of {@code screen}. */
	public static float anchorFraction(double centre, int screen) {
		if (centre < screen / 3.0) {
			return 0f;
		}
		return centre > 2.0 * screen / 3.0 ? 1f : 0.5f;
	}

	// ---- snapping ----------------------------------------------------------------------

	/**
	 * Snaps one axis (§7.2). Candidates, in priority order: the two screen margins ({@code 4} and
	 * {@code L − 4 − size}), the centre line ({@code round((L − size)/2)}, the same place a centre anchor
	 * with offset 0 draws), then for every other element span {@code [a, b)} its edges (start or end
	 * aligned to {@code a} or {@code b}) and the stacking positions {@code b + 2} and {@code a − 2 − size}.
	 * The closest candidate within {@link #SNAP_DISTANCE} wins (ties: the earlier one). The result is
	 * clamped on screen. With {@code disabled} (Shift held) nothing snaps.
	 *
	 * @param pos    the dragged start position (left or top edge)
	 * @param size   the element's drawn size along this axis
	 * @param screen the screen size along this axis
	 * @param others the spans of the other elements along this axis
	 */
	public static Snap snapAxis(int pos, int size, int screen, List<Span> others, boolean disabled) {
		int clamped = clampPos(pos, size, screen);
		if (disabled) {
			return new Snap(clamped, 0, false);
		}
		Snap best = null;
		int bestDist = SNAP_DISTANCE + 1;
		// margins and centre
		int[][] fixed = {
				{SNAP_MARGIN, SNAP_MARGIN},
				{screen - SNAP_MARGIN - size, screen - SNAP_MARGIN},
				{(int) Math.round((screen - size) / 2.0), screen / 2}
		};
		for (int[] c : fixed) {
			int d = Math.abs(c[0] - pos);
			if (d < bestDist) {
				bestDist = d;
				best = new Snap(c[0], c[1], true);
			}
		}
		if (others != null) {
			for (Span o : others) {
				int a = o.start();
				int b = o.end();
				int[][] cands = {
						{a, a}, {b, b}, {a - size, a}, {b - size, b},
						{b + STACK_GAP, b}, {a - STACK_GAP - size, a}
				};
				for (int[] c : cands) {
					int d = Math.abs(c[0] - pos);
					if (d < bestDist) {
						bestDist = d;
						best = new Snap(c[0], c[1], true);
					}
				}
			}
		}
		if (best == null) {
			return new Snap(clamped, 0, false);
		}
		int p = clampPos(best.pos(), size, screen);
		return new Snap(p, best.guide(), p == best.pos());
	}

	// ---- scale (§7.3) --------------------------------------------------------------------

	/** {@code scale} clamped to {@link HudLayout#MIN_SCALE}..{@link HudLayout#MAX_SCALE} (NaN → 1). */
	public static float clampScale(float scale) {
		if (!Float.isFinite(scale)) {
			return 1f;
		}
		return Math.max(HudLayout.MIN_SCALE, Math.min(HudLayout.MAX_SCALE, scale));
	}

	/** One fine (Shift) scroll step of the editor scale. */
	public static final float FINE_SCALE_STEP = 0.025f;

	/**
	 * One scroll step of the editor (§7.3). Coarse steps move to the next multiple of {@code 1/guiScale}
	 * in {@code direction}, so every coarse result is a crisp integer pixel ratio, even after fine steps.
	 * A coarse result is stored unrounded ({@code k/guiScale} as a float): rounding 4/3 to 1.333 would
	 * make the next step at GUI 3 land on 4/3 again. A scale already within {@link #crispTolerance} of a
	 * multiple (for example 1.333 saved by an older version) counts as on it, so it moves on as well.
	 * Fine steps (Shift) move by {@link #FINE_SCALE_STEP} and are rounded to 3 decimals. Clamped to
	 * 0.5–3.0.
	 */
	public static float scaleStep(float scale, int direction, int guiScale, boolean fine) {
		float current = clampScale(scale);
		if (direction == 0) {
			return current;
		}
		int dir = direction > 0 ? 1 : -1;
		if (fine) {
			double next = current + dir * (double) FINE_SCALE_STEP;
			return clampScale((float) (Math.round(next * 1000.0) / 1000.0));
		}
		int gs = Math.max(1, guiScale);
		double units = current * (double) gs;
		// Smaller than one fine step (0.025·gs units), so a scale one fine step off a crisp value is not snapped back onto it.
		double eps = 0.01 + crispTolerance(gs);
		double steps = dir > 0 ? Math.floor(units + eps) + 1 : Math.ceil(units - eps) - 1;
		return clampScale((float) (steps / gs));
	}

	/**
	 * True when {@code scale × guiScale} is an integer, i.e. text drawn at this scale is crisp. Within
	 * {@link #crispTolerance}, so float noise and scales saved with 3 decimals (1.333 at GUI 3) count.
	 */
	public static boolean isCrisp(float scale, int guiScale) {
		int gs = Math.max(1, guiScale);
		double units = (double) scale * gs;
		return Math.abs(units - Math.rint(units)) < crispTolerance(gs);
	}

	/**
	 * How far (in screen pixels per GUI pixel) {@code scale × guiScale} may be from an integer and still
	 * count as one: float noise plus the 3-decimal rounding error ({@code ≤ 0.0005·guiScale}). Always well
	 * below one fine step ({@code 0.025·guiScale}).
	 */
	private static double crispTolerance(int guiScale) {
		return 0.001 + 0.0005 * Math.max(1, guiScale);
	}

	// ---- element sizes -----------------------------------------------------------------------

	/** Horizontal panel padding. */
	public static int panelPadX(boolean compact) {
		return compact ? PANEL_PAD_X_COMPACT : PANEL_PAD_X;
	}

	/** Vertical panel padding. */
	public static int panelPadY(boolean compact) {
		return compact ? PANEL_PAD_Y_COMPACT : PANEL_PAD_Y;
	}

	/** {@code clamp(widestRow + 2·padX, 128, 168)} (compact: {@code clamp(…, 92, 168)}). */
	public static int panelWidth(int widestRowPx, boolean compact) {
		int min = compact ? PANEL_MIN_W_COMPACT : PANEL_MIN_W;
		return Math.max(min, Math.min(PANEL_MAX_W, widestRowPx + 2 * panelPadX(compact)));
	}

	/**
	 * {@code padY + header + divider + rows·10 + padY} (101 for 8 rows, not compact). With no rows the
	 * divider is not drawn either: {@code padY + header + padY}.
	 */
	public static int panelHeight(int rows, boolean compact) {
		int pad = panelPadY(compact);
		if (rows <= 0) {
			return pad + PANEL_HEADER_H + pad;
		}
		return pad + PANEL_HEADER_H + PANEL_DIVIDER_H + rows * PANEL_ROW_H + pad;
	}

	/**
	 * The panel width for a header and rows, measured with the given width functions (font widths in
	 * game, {@link AsciiWidths} in tests). The header is measured with its full name; the name is
	 * truncated to {@link #panelNameBudget} afterwards.
	 */
	public static int panelWidthFor(PreviewStrings.Header header, List<PreviewStrings.Cell> rows, boolean compact,
			ToIntFunction<String> plain, ToIntFunction<String> bold) {
		int widest = header == null ? 0 : header.width(plain, bold);
		for (PreviewStrings.Cell row : rows) {
			widest = Math.max(widest, row.width(plain));
		}
		return panelWidth(widest, compact);
	}

	/** Pixels left for the opponent name in a panel header of width {@code panelW}. */
	public static int panelNameBudget(int panelW, boolean compact, int vsWidth, int chipWidth) {
		int budget = panelW - 2 * panelPadX(compact) - vsWidth;
		if (chipWidth > 0) {
			budget -= PreviewStrings.GAP + chipWidth;
		}
		return Math.max(0, budget);
	}

	/** {@code clamp(widestCell, 72, 88)}. */
	public static int cardColWidth(int widestCellPx) {
		return Math.max(CARD_COL_MIN, Math.min(CARD_COL_MAX, widestCellPx));
	}

	/** The card column width for a set of cells measured with {@code plain}. */
	public static int cardColWidthFor(List<PreviewStrings.Cell> cells, ToIntFunction<String> plain) {
		int widest = 0;
		for (PreviewStrings.Cell c : cells) {
			widest = Math.max(widest, c.width(plain));
		}
		return cardColWidth(widest);
	}

	/** {@code 16 + 3·colW + 12} (244–292). */
	public static int cardWidth(int colW) {
		return 2 * CARD_PAD_X + 3 * colW + 2 * CARD_GUTTER;
	}

	/** 56, or 66 with the footer. */
	public static int cardHeight(boolean footer) {
		return CARD_HEIGHT + (footer ? CARD_FOOTER_H : 0);
	}

	/** Left edge of card column {@code col} (0..2). */
	public static int cardColX(int col, int colW) {
		return CARD_PAD_X + col * (colW + CARD_GUTTER);
	}

	/** Pixels left for the opponent name in a card header. */
	public static int cardNameBudget(int cardW, int resultWidth, int vsWidth, int durationWidth) {
		return Math.max(0, cardW - 2 * CARD_PAD_X - resultWidth - CARD_RESULT_GAP - vsWidth - PreviewStrings.GAP - durationWidth);
	}

	/** Pixels the footer text may use: it ends {@link PreviewStrings#GAP} before the progress line. */
	public static int cardFooterBudget(int cardW) {
		return Math.max(0, cardW - 2 * CARD_PAD_X - CARD_PROGRESS_W - PreviewStrings.GAP);
	}

	/** Pill width for an indicator text of width {@code textW} (which includes the font's trailing pixel). */
	public static int indicatorWidth(int textW) {
		return INDICATOR_BAR_W + INDICATOR_PAD + Math.max(0, textW - 1) + INDICATOR_PAD;
	}

	/**
	 * Left edge of the combo number (§7.6). The number is {@code numberGlyphPx} wide at 1× (its advance
	 * minus the trailing spacing column); a broken combo adds a "×" multiplier sign {@code markGlyphPx}
	 * wide at 1× after a {@link #COMBO_MARK_GAP} gap ({@code markGlyphPx = 0}: no sign). Both are drawn at
	 * {@link #COMBO_NUMBER_SCALE}. The group is centred in the {@link #COMBO_W} box (floor), so "4×" reads
	 * as one unit. A two-digit broken combo is 34 px wide and overhangs the box by 2 px on each side.
	 */
	public static int comboNumberX(int numberGlyphPx, int markGlyphPx) {
		int group = COMBO_NUMBER_SCALE * Math.max(0, numberGlyphPx);
		if (markGlyphPx > 0) {
			group += COMBO_MARK_GAP + COMBO_NUMBER_SCALE * markGlyphPx;
		}
		return Math.floorDiv(COMBO_W - group, 2);
	}

	/** Housing width: 10 (VERTICAL, SINGLE) or 26 (HORIZONTAL). */
	public static int stoplightWidth(KwamsterConfig.StoplightStyle style) {
		return style == KwamsterConfig.StoplightStyle.HORIZONTAL ? 3 * LIGHT + 4 * LIGHT_PAD : LIGHT + 2 * LIGHT_PAD;
	}

	/** Housing height: 26 (VERTICAL) or 10 (HORIZONTAL, SINGLE). */
	public static int stoplightHeight(KwamsterConfig.StoplightStyle style) {
		return style == KwamsterConfig.StoplightStyle.VERTICAL ? 3 * LIGHT + 4 * LIGHT_PAD : LIGHT + 2 * LIGHT_PAD;
	}

	/**
	 * Which side the stoplight label goes (§7.7): away from the screen centre, flipped when that side
	 * would come within {@link #STOPLIGHT_LABEL_EDGE} of the screen edge. Arguments are drawn (scaled)
	 * screen pixels.
	 *
	 * @return true for the left side
	 */
	public static boolean stoplightLabelLeft(double boxX, double boxW, double labelW, double screenW) {
		boolean left = boxX + boxW / 2.0 < screenW / 2.0;
		if (left && boxX - STOPLIGHT_LABEL_GAP - labelW < STOPLIGHT_LABEL_EDGE) {
			left = false;
		} else if (!left && boxX + boxW + STOPLIGHT_LABEL_GAP + labelW > screenW - STOPLIGHT_LABEL_EDGE) {
			left = true;
		}
		return left;
	}

	// ---- vanilla areas used by the default-layout validation (§3.10, §7.0) ------------------------

	/** The vanilla crosshair and attack indicator {@code [W/2−8, H/2−8, W/2+8, H/2+17)}, rounded outward. */
	public static Rect vanillaCrosshairRect(int screenW, int screenH) {
		return outward(screenW / 2.0 - 8, screenH / 2.0 - 8, screenW / 2.0 + 8, screenH / 2.0 + 17);
	}

	/** The vanilla title band {@code [W/2−80, H/2−40, W/2+80, H/2−4)}, rounded outward. */
	public static Rect titleBand(int screenW, int screenH) {
		return outward(screenW / 2.0 - 80, screenH / 2.0 - 40, screenW / 2.0 + 80, screenH / 2.0 - 4);
	}

	/** The full unfocused chat band {@code [0, H−130, 330, H−40)}. */
	public static Rect chatBand(int screenW, int screenH) {
		return new Rect(0, screenH - 130, 330, 90);
	}

	private static Rect outward(double x0, double y0, double x1, double y1) {
		int ix0 = (int) Math.floor(x0);
		int iy0 = (int) Math.floor(y0);
		return new Rect(ix0, iy0, (int) Math.ceil(x1) - ix0, (int) Math.ceil(y1) - iy0);
	}

	// ---- value types ------------------------------------------------------------------------------

	/** A rectangle in GUI pixels: top-left {@code (x, y)}, size {@code w × h} (half-open). */
	public record Rect(int x, int y, int w, int h) {
		public int right() {
			return this.x + this.w;
		}

		public int bottom() {
			return this.y + this.h;
		}

		/** True when the two rects share at least one pixel. */
		public boolean intersects(Rect o) {
			return o != null && this.x < o.right() && o.x < right() && this.y < o.bottom() && o.y < bottom();
		}

		/** True when the point lies inside (half-open). */
		public boolean contains(double px, double py) {
			return px >= this.x && px < right() && py >= this.y && py < bottom();
		}

		/** This rect grown by {@code d} pixels on every side. */
		public Rect inflate(int d) {
			return new Rect(this.x - d, this.y - d, this.w + 2 * d, this.h + 2 * d);
		}

		/** The smallest rect containing both. */
		public Rect union(Rect o) {
			if (o == null) {
				return this;
			}
			int x0 = Math.min(this.x, o.x);
			int y0 = Math.min(this.y, o.y);
			return new Rect(x0, y0, Math.max(right(), o.right()) - x0, Math.max(bottom(), o.bottom()) - y0);
		}

		/** The span along x. */
		public Span xSpan() {
			return new Span(this.x, right());
		}

		/** The span along y. */
		public Span ySpan() {
			return new Span(this.y, bottom());
		}
	}

	/** A half-open interval {@code [start, end)} along one axis. */
	public record Span(int start, int end) {
	}

	/**
	 * The result of {@link #snapAxis}: the position to use, the guide line to draw (a screen
	 * coordinate along this axis) and whether a snap applied.
	 */
	public record Snap(int pos, int guide, boolean snapped) {
	}
}
