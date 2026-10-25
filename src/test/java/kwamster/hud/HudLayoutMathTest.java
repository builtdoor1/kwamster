package kwamster.hud;

import kwamster.core.config.Anchor;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.stats.StatFormat;
import kwamster.hud.HudLayoutMath.Rect;
import kwamster.hud.HudLayoutMath.Snap;
import kwamster.hud.HudLayoutMath.Span;
import kwamster.hud.PreviewStrings.Cell;
import kwamster.hud.PreviewStrings.Header;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToIntFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §7.2 anchor model, snapping and scale steps, plus the §7.0 default-layout validation and the §7.4/§7.9 fit tests. */
final class HudLayoutMathTest {
	private static final ToIntFunction<String> PLAIN = AsciiWidths::width;
	private static final ToIntFunction<String> BOLD = AsciiWidths::widthBold;
	private static final int[][] SIZES = {{427, 240}, {480, 270}, {640, 360}, {960, 540}};

	// ---- anchors ------------------------------------------------------------------------------------

	@Test
	void positionsForAllNineAnchors() {
		// W = 480, H = 270, element 100 × 50, offset (5, 6): x = round(fx·480 + 5 − fx·100), y likewise.
		int[][] expected = {
				{5, 6}, {195, 6}, {385, 6},
				{5, 116}, {195, 116}, {385, 116},
				{5, 226}, {195, 226}, {385, 226}};
		Anchor[] anchors = Anchor.values();
		for (int i = 0; i < anchors.length; i++) {
			Anchor a = anchors[i];
			assertEquals(expected[i][0], HudLayoutMath.x(a, 5, 100, 480), a.name());
			assertEquals(expected[i][1], HudLayoutMath.y(a, 6, 50, 270), a.name());
			// Drawn rects are clamped on screen: the right and bottom anchors with a positive offset hit the edge.
			Rect r = HudLayoutMath.rect(new HudLayout(a, 5, 6, 1f), 100, 50, 480, 270);
			assertEquals(new Rect(Math.min(expected[i][0], 380), Math.min(expected[i][1], 220), 100, 50), r, a.name());
		}
	}

	@Test
	void clampingKeepsElementsOnScreen() {
		assertEquals(380, HudLayoutMath.rect(new HudLayout(Anchor.TOP_RIGHT, 50, 0, 1f), 100, 50, 480, 270).x());
		assertEquals(0, HudLayoutMath.rect(new HudLayout(Anchor.TOP_LEFT, -40, -9, 1f), 100, 50, 480, 270).x());
		assertEquals(0, HudLayoutMath.rect(new HudLayout(Anchor.TOP_LEFT, -40, -9, 1f), 100, 50, 480, 270).y());
		assertEquals(220, HudLayoutMath.rect(new HudLayout(Anchor.BOTTOM_LEFT, 0, 30, 1f), 100, 50, 480, 270).y());
		assertEquals(0, HudLayoutMath.clampPos(50, 600, 480), "an element larger than the screen sits at 0");
		// Scale applies to the size: 100 × 50 at 1.5 → 150 × 75.
		Rect scaled = HudLayoutMath.rect(new HudLayout(Anchor.CENTER, 0, 0, 1.5f), 100, 50, 480, 270);
		assertEquals(new Rect(165, 98, 150, 75), scaled);
	}

	@Test
	void autoAnchorOnDropPreservesTheTopLeft() {
		int[][] sizes = {{128, 101}, {105, 11}, {30, 26}, {10, 26}, {244, 56}, {77, 33}};
		for (int[] screen : SIZES) {
			int w0 = screen[0];
			int h0 = screen[1];
			for (int[] s : sizes) {
				for (int x = 0; x <= w0 - s[0]; x += 7) {
					for (int y = 0; y <= h0 - s[1]; y += 11) {
						Rect r = new Rect(x, y, s[0], s[1]);
						HudLayout l = HudLayoutMath.autoAnchor(r, 1f, w0, h0);
						String where = w0 + "x" + h0 + " " + r + " → " + l;
						assertEquals(x, HudLayoutMath.x(l.anchor, l.offsetX, s[0], w0), where);
						assertEquals(y, HudLayoutMath.y(l.anchor, l.offsetY, s[1], h0), where);
					}
				}
			}
		}
		assertEquals(Anchor.TOP_LEFT, HudLayoutMath.autoAnchor(new Rect(10, 10, 50, 20), 1f, 480, 270).anchor);
		assertEquals(Anchor.CENTER, HudLayoutMath.autoAnchor(new Rect(215, 125, 50, 20), 1f, 480, 270).anchor);
		assertEquals(Anchor.BOTTOM_RIGHT, HudLayoutMath.autoAnchor(new Rect(420, 240, 50, 20), 1f, 480, 270).anchor);
		assertEquals(Anchor.TOP_CENTER, HudLayoutMath.autoAnchor(new Rect(180, 0, 120, 20), 1f, 480, 270).anchor);
	}

	@Test
	void guiScaleChangeKeepsTheAnchoredDistance() {
		HudLayout br = new HudLayout(Anchor.BOTTOM_RIGHT, -6, -6, 1f);
		Rect big = HudLayoutMath.rect(br, 100, 40, 960, 540);
		Rect small = HudLayoutMath.rect(br, 100, 40, 480, 270);
		assertEquals(6, 960 - big.right());
		assertEquals(6, 480 - small.right());
		assertEquals(6, 540 - big.bottom());
		assertEquals(6, 270 - small.bottom());
		HudLayout below = HudLayout.defaultFor(HudElementId.HIT_INDICATOR); // CENTER (0, 26)
		Rect a = HudLayoutMath.rect(below, 105, 11, 960, 540);
		Rect b = HudLayoutMath.rect(below, 105, 11, 480, 270);
		assertEquals(a.y() - 270, b.y() - 135, "same distance below the screen centre");
		assertEquals(a.x() - 480, b.x() - 240);
	}

	// ---- snapping ---------------------------------------------------------------------------------------

	@Test
	void snapping() {
		List<Span> none = List.of();
		assertEquals(new Snap(4, 4, true), HudLayoutMath.snapAxis(6, 50, 480, none, false), "left margin");
		assertEquals(new Snap(9, 0, false), HudLayoutMath.snapAxis(9, 50, 480, none, false), "5 px away: no snap");
		assertEquals(new Snap(426, 476, true), HudLayoutMath.snapAxis(424, 50, 480, none, false), "right margin");
		assertEquals(new Snap(215, 240, true), HudLayoutMath.snapAxis(213, 50, 480, none, false), "centre line");
		List<Span> other = List.of(new Span(100, 180));
		assertEquals(180, HudLayoutMath.snapAxis(178, 50, 480, other, false).pos(), "start on the other's end");
		assertEquals(182, HudLayoutMath.snapAxis(183, 50, 480, other, false).pos(), "stacked with a 2 px gap");
		assertEquals(130, HudLayoutMath.snapAxis(129, 50, 480, other, false).pos(), "right edges aligned");
		assertEquals(48, HudLayoutMath.snapAxis(47, 50, 480, other, false).pos(), "stacked before it");
		assertEquals(new Snap(183, 0, false), HudLayoutMath.snapAxis(183, 50, 480, other, true), "Shift disables snapping");
		assertEquals(new Snap(430, 0, false), HudLayoutMath.snapAxis(900, 50, 480, none, true), "still clamped");
	}

	// ---- scale --------------------------------------------------------------------------------------------

	@Test
	void scaleSteps() {
		assertEquals(1.5f, HudLayoutMath.scaleStep(1f, 1, 2, false));
		assertEquals(0.5f, HudLayoutMath.scaleStep(1f, -1, 2, false));
		assertEquals(1.25f, HudLayoutMath.scaleStep(1f, 1, 4, false));
		assertEquals(1.025f, HudLayoutMath.scaleStep(1f, 1, 2, true));
		assertEquals(1.5f, HudLayoutMath.scaleStep(1.025f, 1, 2, false), "a coarse step lands on the next crisp ratio");
		assertEquals(3.0f, HudLayoutMath.scaleStep(3.0f, 1, 2, false));
		assertEquals(0.5f, HudLayoutMath.scaleStep(0.5f, -1, 4, false));
		assertTrue(HudLayoutMath.isCrisp(1.5f, 2));
		assertFalse(HudLayoutMath.isCrisp(1.25f, 2));
		assertTrue(HudLayoutMath.isCrisp(1.25f, 4));
		assertEquals(1f, HudLayoutMath.clampScale(Float.NaN));
	}

	/**
	 * Coarse steps reach every multiple of 1/guiScale from 0.5 to 3.0 and back at every GUI scale, one
	 * multiple per step. Regression: at GUI 3, 6 and 7 a 3-decimal rounding (4/3 → 1.333) made the next
	 * step land on the same value again, so scrolling stopped after one step.
	 */
	@Test
	void coarseScaleStepsWalkTheWholeRangeAtEveryGuiScale() {
		for (int gs = 1; gs <= 9; gs++) {
			// 0.5 is floor(gs/2) units; 3.0 is 3·gs units: one step per multiple in between, either way.
			int expectedSteps = 3 * gs - gs / 2;
			float s = HudLayout.MIN_SCALE;
			int steps = 0;
			while (s < HudLayout.MAX_SCALE) {
				float next = HudLayoutMath.scaleStep(s, 1, gs, false);
				String at = "GUI " + gs + " up from " + s;
				assertTrue(next > s, at + ": stuck");
				assertTrue(HudLayoutMath.isCrisp(next, gs), at + ": " + next + " is not crisp");
				s = next;
				assertTrue(++steps <= expectedSteps, at + ": more steps than multiples");
			}
			assertEquals(HudLayout.MAX_SCALE, s);
			assertEquals(expectedSteps, steps, "GUI " + gs + " up");
			steps = 0;
			while (s > HudLayout.MIN_SCALE) {
				float next = HudLayoutMath.scaleStep(s, -1, gs, false);
				String at = "GUI " + gs + " down from " + s;
				assertTrue(next < s, at + ": stuck");
				assertTrue(next == HudLayout.MIN_SCALE || HudLayoutMath.isCrisp(next, gs), at + ": " + next + " is not crisp");
				s = next;
				assertTrue(++steps <= expectedSteps, at + ": more steps than multiples");
			}
			assertEquals(HudLayout.MIN_SCALE, s);
			assertEquals(expectedSteps, steps, "GUI " + gs + " down");
		}
	}

	@Test
	void scalesSavedWithThreeDecimalsStillStepAndCountAsCrisp() {
		assertEquals(5f / 3f, HudLayoutMath.scaleStep(4f / 3f, 1, 3, false), 1e-6f);
		assertEquals(5f / 3f, HudLayoutMath.scaleStep(1.333f, 1, 3, false), 1e-6f, "1.333 at GUI 3 moves on");
		assertEquals(1f, HudLayoutMath.scaleStep(1.333f, -1, 3, false), 1e-6f);
		assertEquals(1f, HudLayoutMath.scaleStep(0.667f, 1, 3, false), 1e-6f);
		assertEquals(0.5f, HudLayoutMath.scaleStep(0.667f, -1, 3, false), "1/3 is clamped to the 0.5 minimum");
		assertEquals(4f / 3f, HudLayoutMath.scaleStep(1.167f, 1, 6, false), 1e-6f);
		assertEquals(1f, HudLayoutMath.scaleStep(1.167f, -1, 6, false), 1e-6f);
		assertEquals(12f / 7f, HudLayoutMath.scaleStep(1.571f, 1, 7, false), 1e-6f);
		assertEquals(10f / 7f, HudLayoutMath.scaleStep(1.571f, -1, 7, false), 1e-6f);
		for (float saved : new float[] {1.333f, 0.667f, 2.667f}) {
			assertTrue(HudLayoutMath.isCrisp(saved, 3), saved + " at GUI 3");
		}
		assertTrue(HudLayoutMath.isCrisp(1.167f, 6));
		assertTrue(HudLayoutMath.isCrisp(1.571f, 7));
		assertTrue(HudLayoutMath.isCrisp(4f / 3f, 3));
		assertTrue(HudLayoutMath.isCrisp(12f / 7f, 7));
		// One fine step off a crisp value is not crisp, and a coarse step from it goes to the next multiple.
		float fine = HudLayoutMath.scaleStep(4f / 3f, 1, 3, true);
		assertEquals(1.358f, fine);
		assertFalse(HudLayoutMath.isCrisp(fine, 3));
		assertEquals(5f / 3f, HudLayoutMath.scaleStep(fine, 1, 3, false), 1e-6f);
		assertEquals(4f / 3f, HudLayoutMath.scaleStep(fine, -1, 3, false), 1e-6f, "down from just above a multiple lands on it");
		float fineDown = HudLayoutMath.scaleStep(4f / 3f, -1, 3, true);
		assertEquals(1.308f, fineDown);
		assertEquals(4f / 3f, HudLayoutMath.scaleStep(fineDown, 1, 3, false), 1e-6f, "up from just below a multiple lands on it");
		assertFalse(HudLayoutMath.isCrisp(1.025f, 2));
		assertFalse(HudLayoutMath.isCrisp(0.5f, 3));
	}

	@Test
	void comboNumberStaysCentredInsideItsBox() {
		// A digit is 5 glyph px at 1× (10 at 2×), so is the "×".
		assertEquals(10, HudLayoutMath.comboNumberX(5, 0), "one digit: (30 − 10) / 2");
		assertEquals(4, HudLayoutMath.comboNumberX(11, 0), "two digits: (30 − 22) / 2");
		assertEquals(4, HudLayoutMath.comboNumberX(5, 5), "broken: \"4×\" centred as one group, (30 − 22) / 2");
		assertEquals(-2, HudLayoutMath.comboNumberX(11, 5), "\"12×\" is 34 px: 2 px overhang each side");
		// The live number and its 1 px outline fit the box for up to two digits.
		for (int glyphs : new int[] {5, 11}) {
			int x = HudLayoutMath.comboNumberX(glyphs, 0);
			assertTrue(x - 1 >= 0, "outline left " + (x - 1));
			assertTrue(x + HudLayoutMath.COMBO_NUMBER_SCALE * glyphs + 1 <= HudLayoutMath.COMBO_W, "outline right");
		}
		// Digits are 7 rows: the 2× number plus outline spans [0, 16), clear of the caption, which fits below.
		int outlineBottom = HudLayoutMath.COMBO_NUMBER_Y + HudLayoutMath.COMBO_NUMBER_SCALE * 7 + 1;
		assertTrue(HudLayoutMath.COMBO_NUMBER_Y - 1 >= 0);
		assertTrue(outlineBottom < HudLayoutMath.COMBO_CAPTION_Y, "outline ends at " + outlineBottom);
		assertTrue(HudLayoutMath.COMBO_CAPTION_Y + 9 <= HudLayoutMath.COMBO_H, "caption and its shadow fit");
	}

	// ---- element sizes ----------------------------------------------------------------------------------

	@Test
	void elementSizeFormulas() {
		assertEquals(101, HudLayoutMath.panelHeight(8, false), "4 + 10 + 3 + 8·10 + 4");
		assertEquals(18, HudLayoutMath.panelHeight(0, false));
		assertEquals(56, HudLayoutMath.cardHeight(false));
		assertEquals(66, HudLayoutMath.cardHeight(true));
		assertEquals(244, HudLayoutMath.cardWidth(72));
		assertEquals(292, HudLayoutMath.cardWidth(88));
		assertEquals(10, HudLayoutMath.stoplightWidth(KwamsterConfig.StoplightStyle.VERTICAL));
		assertEquals(26, HudLayoutMath.stoplightHeight(KwamsterConfig.StoplightStyle.VERTICAL));
		assertEquals(26, HudLayoutMath.stoplightWidth(KwamsterConfig.StoplightStyle.HORIZONTAL));
		assertEquals(10, HudLayoutMath.stoplightHeight(KwamsterConfig.StoplightStyle.HORIZONTAL));
		assertEquals(10, HudLayoutMath.stoplightWidth(KwamsterConfig.StoplightStyle.SINGLE));
		assertEquals(10, HudLayoutMath.stoplightHeight(KwamsterConfig.StoplightStyle.SINGLE));
		assertEquals(128, HudLayoutMath.panelWidth(10, false));
		assertEquals(168, HudLayoutMath.panelWidth(500, false));
		assertEquals(92, HudLayoutMath.panelWidth(10, true));
		assertEquals(72, HudLayoutMath.cardColWidth(10));
		assertEquals(88, HudLayoutMath.cardColWidth(300));
	}

	// ---- default layout (§7.0) -------------------------------------------------------------------------------

	/** Sizes of the default HUD as the editor previews it: PreviewStrings samples measured with AsciiWidths. */
	static Map<HudElementId, int[]> defaultSizes() {
		KwamsterConfig.PanelRows rows = new KwamsterConfig.PanelRows();
		List<Cell> shown = new ArrayList<>();
		for (Cell c : PreviewStrings.PANEL_ROWS_SAMPLE) {
			if (rowEnabled(rows, c.label())) {
				shown.add(c);
			}
		}
		assertEquals(8, shown.size(), "the default in-fight panel has 8 rows");
		Header header = new Header(PreviewStrings.VS + PreviewStrings.SAMPLE_NAME, false, "", PreviewStrings.SAMPLE_CHIP);
		int panelW = HudLayoutMath.panelWidthFor(header, shown, false, PLAIN, BOLD);
		int colW = HudLayoutMath.cardColWidthFor(PreviewStrings.CARD_CELLS_SAMPLE, PLAIN);
		Map<HudElementId, int[]> m = new EnumMap<>(HudElementId.class);
		m.put(HudElementId.STATS_PANEL, new int[] {panelW, HudLayoutMath.panelHeight(shown.size(), false)});
		m.put(HudElementId.HIT_INDICATOR, new int[] {HudLayoutMath.indicatorWidth(PLAIN.applyAsInt(PreviewStrings.INDICATOR_SAMPLE)), HudLayoutMath.INDICATOR_H});
		m.put(HudElementId.COMBO_COUNTER, new int[] {HudLayoutMath.COMBO_W, HudLayoutMath.COMBO_H});
		m.put(HudElementId.STOPLIGHT, new int[] {HudLayoutMath.stoplightWidth(KwamsterConfig.StoplightStyle.VERTICAL),
				HudLayoutMath.stoplightHeight(KwamsterConfig.StoplightStyle.VERTICAL)});
		// With default settings the chat summary carries the link, so the card footer is hidden: 56 px.
		m.put(HudElementId.SUMMARY_CARD, new int[] {HudLayoutMath.cardWidth(colW), HudLayoutMath.cardHeight(false)});
		return m;
	}

	private static boolean rowEnabled(KwamsterConfig.PanelRows r, String label) {
		return switch (label) {
			case PreviewStrings.HITS -> r.hits;
			case PreviewStrings.REACH -> r.reach;
			case PreviewStrings.COMBO -> r.combo;
			case PreviewStrings.STREAK -> r.streak;
			case PreviewStrings.CRITS -> r.crits;
			case PreviewStrings.SPRINT -> r.sprint;
			case PreviewStrings.THREE_BLOCK -> r.threeBlock;
			case PreviewStrings.RESETS -> r.jumpResets;
			case PreviewStrings.INTERVAL -> r.interval;
			case PreviewStrings.TAKEN -> r.taken;
			case PreviewStrings.AIM -> r.aim;
			case PreviewStrings.TRADES -> r.trades;
			case PreviewStrings.ADVANTAGE -> r.advantage;
			default -> false;
		};
	}

	@Test
	void defaultLayoutHasNoOverlaps() {
		Map<HudElementId, int[]> sizes = defaultSizes();
		assertEquals(128, sizes.get(HudElementId.STATS_PANEL)[0], "the sample panel sits at the 128 px minimum");
		assertEquals(101, sizes.get(HudElementId.STATS_PANEL)[1]);
		assertEquals(244, sizes.get(HudElementId.SUMMARY_CARD)[0]);
		for (int[] screen : SIZES) {
			int w = screen[0];
			int h = screen[1];
			Map<HudElementId, Rect> rects = new EnumMap<>(HudElementId.class);
			for (HudElementId id : HudElementId.values()) {
				int[] s = sizes.get(id);
				rects.put(id, HudLayoutMath.rect(HudLayout.defaultFor(id), s[0], s[1], w, h));
			}
			String at = w + "x" + h + " ";
			HudElementId[] ids = HudElementId.values();
			for (int i = 0; i < ids.length; i++) {
				for (int j = i + 1; j < ids.length; j++) {
					boolean exempt = (ids[i] == HudElementId.STATS_PANEL && ids[j] == HudElementId.SUMMARY_CARD);
					if (!exempt) {
						assertFalse(rects.get(ids[i]).intersects(rects.get(ids[j])),
								at + ids[i] + " " + rects.get(ids[i]) + " overlaps " + ids[j] + " " + rects.get(ids[j]));
					}
				}
			}
			Rect vanilla = HudLayoutMath.vanillaCrosshairRect(w, h);
			for (HudElementId id : ids) {
				assertFalse(rects.get(id).intersects(vanilla), at + id + " covers the vanilla crosshair/indicator " + vanilla);
			}
			Rect title = HudLayoutMath.titleBand(w, h);
			Rect chat = HudLayoutMath.chatBand(w, h);
			for (HudElementId id : new HudElementId[] {HudElementId.STATS_PANEL, HudElementId.SUMMARY_CARD}) {
				assertFalse(rects.get(id).intersects(title), at + id + " " + rects.get(id) + " enters the title band " + title);
				assertFalse(rects.get(id).intersects(chat), at + id + " " + rects.get(id) + " enters the chat band " + chat);
			}
			// The hit indicator's 1.15× pop-in about its centre still clears the vanilla indicator.
			Rect ind = rects.get(HudElementId.HIT_INDICATOR);
			double popTop = ind.y() + ind.h() / 2.0 - ind.h() * 1.15 / 2.0;
			assertTrue(popTop >= vanilla.bottom(), at + "pop-in top " + popTop);
		}
		// Hand-checked anchors at 427×240 (the gametest size).
		Map<HudElementId, int[]> s = sizes;
		assertEquals(new Rect(5, 6, 128, 101), HudLayoutMath.rect(HudLayout.defaultFor(HudElementId.STATS_PANEL), s.get(HudElementId.STATS_PANEL)[0], 101, 427, 240));
		assertEquals(new Rect(92, 20, 244, 56), HudLayoutMath.rect(HudLayout.defaultFor(HudElementId.SUMMARY_CARD), 244, 56, 427, 240));
		assertEquals(new Rect(229, 107, 30, 26), HudLayoutMath.rect(HudLayout.defaultFor(HudElementId.COMBO_COUNTER), 30, 26, 427, 240));
		assertEquals(new Rect(179, 107, 10, 26), HudLayoutMath.rect(HudLayout.defaultFor(HudElementId.STOPLIGHT), 10, 26, 427, 240));
	}

	// ---- fit (§7.4, §7.9) -----------------------------------------------------------------------------------

	@Test
	void everyPanelRowAndHeaderFits() {
		for (boolean compact : new boolean[] {false, true}) {
			int pad = HudLayoutMath.panelPadX(compact);
			int panelW = 0;
			for (Header header : PreviewStrings.PANEL_HEADERS_WORST) {
				panelW = Math.max(panelW, HudLayoutMath.panelWidthFor(header, PreviewStrings.PANEL_ROWS_WORST, compact, PLAIN, BOLD));
			}
			assertTrue(panelW <= HudLayoutMath.PANEL_MAX_W, "panel " + panelW);
			int inner = panelW - 2 * pad;
			for (Cell row : PreviewStrings.PANEL_ROWS_WORST) {
				assertTrue(row.width(PLAIN) <= inner, (compact ? "compact " : "") + row + " needs " + row.width(PLAIN) + " of " + inner);
			}
			for (Header header : PreviewStrings.PANEL_HEADERS_WORST) {
				int chip = header.chipWidth(PLAIN, BOLD);
				if (header.leftBold()) {
					assertTrue(header.width(PLAIN, BOLD) <= inner, header.toString());
					continue;
				}
				String name = header.left().substring(PreviewStrings.VS.length());
				int vs = PLAIN.applyAsInt(PreviewStrings.VS);
				int budget = HudLayoutMath.panelNameBudget(panelW, compact, vs, chip);
				String shown = StatFormat.truncate(name, budget, PLAIN);
				int used = vs + PLAIN.applyAsInt(shown) + (chip > 0 ? PreviewStrings.GAP + chip : 0);
				assertTrue(used <= inner, header + " → " + shown + " uses " + used + " of " + inner);
				assertTrue(shown.length() >= 4, "the name keeps a readable prefix: " + shown);
			}
		}
		// The 16-character name is truncated in the DRAW header at 168 px.
		assertEquals(168, HudLayoutMath.panelWidthFor(PreviewStrings.PANEL_HEADERS_WORST.get(1), PreviewStrings.PANEL_ROWS_WORST, false, PLAIN, BOLD));
	}

	@Test
	void everyCardCellAndHeaderFitsAndTheFooterEndsBeforeTheProgressLine() {
		int colW = HudLayoutMath.cardColWidthFor(PreviewStrings.CARD_CELLS_WORST, PLAIN);
		assertTrue(colW <= HudLayoutMath.CARD_COL_MAX, "colW " + colW);
		for (Cell c : PreviewStrings.CARD_CELLS_WORST) {
			assertTrue(c.width(PLAIN) <= colW, c + " needs " + c.width(PLAIN));
		}
		for (List<Cell> cells : List.of(PreviewStrings.CARD_CELLS_WORST, PreviewStrings.CARD_CELLS_SAMPLE)) {
			int w = HudLayoutMath.cardWidth(HudLayoutMath.cardColWidthFor(cells, PLAIN));
			int nameBudget = HudLayoutMath.cardNameBudget(w, BOLD.applyAsInt(PreviewStrings.CARD_RESULT_WORST),
					PLAIN.applyAsInt(PreviewStrings.VS), PLAIN.applyAsInt(PreviewStrings.CARD_DURATION_WORST));
			assertTrue(PLAIN.applyAsInt(StatFormat.truncate(PreviewStrings.WORST_NAME, nameBudget, PLAIN)) <= nameBudget);
			int budget = HudLayoutMath.cardFooterBudget(w);
			String footer = StatFormat.truncate(PreviewStrings.CARD_FOOTER_WORST, budget, PLAIN);
			int footerEnd = HudLayoutMath.CARD_PAD_X + PLAIN.applyAsInt(footer);
			int progressStart = w - HudLayoutMath.CARD_PAD_X - HudLayoutMath.CARD_PROGRESS_W;
			assertTrue(footerEnd <= progressStart - PreviewStrings.GAP, "footer ends at " + footerEnd + ", line starts at " + progressStart);
		}
		// At the 244 px default card the worst footer has to be cut.
		int sampleW = HudLayoutMath.cardWidth(HudLayoutMath.cardColWidthFor(PreviewStrings.CARD_CELLS_SAMPLE, PLAIN));
		assertTrue(StatFormat.truncate(PreviewStrings.CARD_FOOTER_WORST, HudLayoutMath.cardFooterBudget(sampleW), PLAIN).endsWith("..."));
	}

	@Test
	void stoplightLabelGoesAwayFromTheCentre() {
		assertTrue(HudLayoutMath.stoplightLabelLeft(178, 10, 70, 427), "left of the centre → label on the left");
		assertFalse(HudLayoutMath.stoplightLabelLeft(240, 10, 70, 427));
		assertFalse(HudLayoutMath.stoplightLabelLeft(10, 10, 70, 427), "no room on the left: flipped");
		assertTrue(HudLayoutMath.stoplightLabelLeft(410, 10, 70, 427), "no room on the right: flipped");
	}
}
