package kwamster.hud.elements;

import kwamster.Kwamster;
import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.LiveFightView;
import kwamster.hud.Anim;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudPreviewData;
import kwamster.hud.PreviewStrings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;

/**
 * The combo counter near the crosshair (S5, §7.6): the live combo at 2× with a "COMBO" caption, no
 * backdrop. Base box 30 × 26.
 *
 * <p>The 2× number gets a 1 GUI px dark outline instead of the vanilla shadow: the vanilla shadow is
 * scaled with the glyphs (a 2 px offset in the darkened number colour), which merges with strokes like
 * the diagonal of a "4" into a blob. The outline is drawn at native GUI pixel offsets, so the digits
 * stay crisp at every GUI scale and readable over sky, snow or sand. The 1× caption keeps the vanilla
 * shadow (already 1 GUI px at 1×).
 *
 * <p>Shown from {@code comboCounterMin}. Each increment pops from 1.25× to 1× over 150 ms. When the
 * chain breaks (a hit taken, the timeout, or the fight ending) the last number turns label grey, gets a
 * "×" multiplier sign in the same 2× outlined style on its baseline (number and sign centred as one
 * group, so it reads as the final tally "4× COMBO") and fades over 1000 ms. NO-REG hits never change
 * the number (CORE's combo excludes them). Client thread.
 */
public final class ComboCounterElement implements HudElement {
	private static final long POP_MS = 150L;
	private static final long BREAK_MS = 1000L;
	private static final float POP_FROM = 1.25f;
	/** Without a frame for this long, the state resyncs silently instead of animating stale changes. */
	private static final long STALE_NANOS = 250L * Anim.MS;
	/** "Never": no pop or break has been seen yet. */
	private static final long NEVER = Long.MIN_VALUE;
	private static final int GOLD = 0xFFFFD43B;
	/** The number's outline: near-black, slightly translucent so it reads as an edge, not a box. */
	private static final int OUTLINE_ARGB = 0xE6000000;
	/** 1 GUI px in the four axis directions (no diagonals, so a fade never stacks dark corners). */
	private static final int[][] OUTLINE_OFFSETS = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
	/** The broken-combo multiplier sign. */
	private static final String MARK = "×";
	/** Digit glyph rows in the vanilla font (0–6) and the exclusive bottom row of its "×" glyph (rows 1–5). */
	private static final int DIGIT_ROWS = 7;
	private static final int MARK_BOTTOM_ROW = 6;
	/** The 2× "×" sits on the 2× number's baseline. */
	private static final int MARK_Y = HudLayoutMath.COMBO_NUMBER_Y
			+ HudLayoutMath.COMBO_NUMBER_SCALE * (DIGIT_ROWS - MARK_BOTTOM_ROW);

	private int lastSeen;
	private long lastUpdate = NEVER;
	private long popAt = NEVER;
	private int broken;
	private long brokenAt = NEVER;

	@Override
	public HudElementId id() {
		return HudElementId.COMBO_COUNTER;
	}

	@Override
	public boolean enabled(KwamsterConfig c) {
		return c.hud.comboCounterEnabled;
	}

	@Override
	public boolean active(long nowNanos) {
		int cur = update(nowNanos);
		return cur >= Kwamster.config().hud.comboCounterMin || breakVisible(nowNanos);
	}

	@Override
	public int width(boolean preview) {
		return HudLayoutMath.COMBO_W;
	}

	@Override
	public int height(boolean preview) {
		return HudLayoutMath.COMBO_H;
	}

	@Override
	public void render(GuiGraphics g, long nowNanos, float alpha, boolean preview) {
		KwamsterConfig cfg = Kwamster.config();
		Font font = Minecraft.getInstance().font;
		if (preview) {
			drawCombo(g, font, HudPreviewData.COMBO, numberColor(HudPreviewData.COMBO, cfg), cfg.hud.labelArgb, alpha, 1f, false);
			return;
		}
		int cur = update(nowNanos);
		if (cur >= cfg.hud.comboCounterMin) {
			drawCombo(g, font, cur, numberColor(cur, cfg), cfg.hud.labelArgb, alpha, popScale(nowNanos), false);
		} else if (breakVisible(nowNanos)) {
			float fade = 1f - Anim.progress(nowNanos, this.brokenAt, BREAK_MS);
			drawCombo(g, font, this.broken, cfg.hud.labelArgb, cfg.hud.labelArgb, alpha * fade, 1f, true);
		}
	}

	/**
	 * The pop scale: 1.25 → 1 over 150 ms after an increment, 1 when no increment has been seen yet
	 * (the counter can first appear through a resync, e.g. the HUD turned back on mid-combo).
	 */
	private float popScale(long now) {
		if (this.popAt == NEVER) {
			return 1f;
		}
		return Anim.lerp(POP_FROM, 1f, Anim.easeOutCubic(Anim.progress(now, this.popAt, POP_MS)));
	}

	/** Reads the live combo and records increments (pop) and breaks (fade). Returns the live combo. */
	private int update(long now) {
		LiveFightView fight = Kwamster.fights().current();
		int cur = fight == null ? 0 : Math.max(0, fight.currentCombo(now));
		int min = Kwamster.config().hud.comboCounterMin;
		boolean stale = this.lastUpdate == NEVER || now - this.lastUpdate > STALE_NANOS || now < this.lastUpdate;
		this.lastUpdate = now;
		if (stale) {
			this.lastSeen = cur;
			return cur;
		}
		if (cur != this.lastSeen) {
			if (cur > this.lastSeen && cur >= min) {
				this.popAt = now;
				this.brokenAt = NEVER;
			} else if (cur < this.lastSeen && this.lastSeen >= min) {
				this.broken = this.lastSeen;
				this.brokenAt = now;
			}
			this.lastSeen = cur;
		}
		return cur;
	}

	private boolean breakVisible(long now) {
		return this.brokenAt != NEVER && now >= this.brokenAt && now - this.brokenAt < BREAK_MS * Anim.MS;
	}

	private static int numberColor(int combo, KwamsterConfig cfg) {
		if (combo >= 6) {
			return GOLD;
		}
		return combo >= 4 ? cfg.hud.accentArgb : cfg.hud.textArgb;
	}

	/**
	 * The outlined 2× number (with the baseline "×" when {@code broken}) centred in the 30 px box, and
	 * the caption centred below it.
	 */
	private static void drawCombo(GuiGraphics g, Font font, int value, int numberArgb, int captionArgb,
			float alpha, float pop, boolean broken) {
		if (alpha <= 0f) {
			return;
		}
		int w = HudLayoutMath.COMBO_W;
		int h = HudLayoutMath.COMBO_H;
		Matrix3x2fStack pose = g.pose();
		boolean popping = pop != 1f;
		if (popping) {
			pose.pushMatrix();
			pose.translate(w / 2f, h / 2f);
			pose.scale(pop, pop);
			pose.translate(-w / 2f, -h / 2f);
		}
		try {
			String number = Integer.toString(value);
			int glyphs = glyphWidth(font, number);
			int markGlyphs = broken ? glyphWidth(font, MARK) : 0;
			int numberX = HudLayoutMath.comboNumberX(glyphs, markGlyphs);
			int outline = Draw.mul(OUTLINE_ARGB, alpha);
			drawOutlined(g, font, number, numberX, HudLayoutMath.COMBO_NUMBER_Y, Draw.mul(numberArgb, alpha), outline);
			if (broken) {
				int markX = numberX + HudLayoutMath.COMBO_NUMBER_SCALE * glyphs + HudLayoutMath.COMBO_MARK_GAP;
				drawOutlined(g, font, MARK, markX, MARK_Y, Draw.mul(numberArgb, alpha), outline);
			}
			int captionW = glyphWidth(font, PreviewStrings.COMBO_CAPTION);
			Draw.text(g, font, PreviewStrings.COMBO_CAPTION, (w - captionW) / 2, HudLayoutMath.COMBO_CAPTION_Y,
					Draw.mul(captionArgb, alpha), true);
		} finally {
			if (popping) {
				pose.popMatrix();
			}
		}
	}

	/**
	 * {@code s} at {@link HudLayoutMath#COMBO_NUMBER_SCALE} with its top-left at {@code (x, y)}: four
	 * outline copies offset by 1 GUI px (translated before the scale, so the offset stays one native GUI
	 * pixel), then the text itself, flat. Each string's copies share one glyph texture, so the GUI
	 * renderer's stable sort keeps them in this order.
	 */
	private static void drawOutlined(GuiGraphics g, Font font, String s, int x, int y, int argb, int outlineArgb) {
		Matrix3x2fStack pose = g.pose();
		if ((outlineArgb >>> 24) >= Draw.MIN_TEXT_ALPHA) {
			for (int[] d : OUTLINE_OFFSETS) {
				drawScaled(g, pose, font, s, x + d[0], y + d[1], outlineArgb);
			}
		}
		drawScaled(g, pose, font, s, x, y, argb);
	}

	private static void drawScaled(GuiGraphics g, Matrix3x2fStack pose, Font font, String s, int x, int y, int argb) {
		pose.pushMatrix();
		try {
			pose.translate(x, y);
			pose.scale(HudLayoutMath.COMBO_NUMBER_SCALE, HudLayoutMath.COMBO_NUMBER_SCALE);
			Draw.text(g, font, s, 0, 0, argb, false);
		} finally {
			pose.popMatrix();
		}
	}

	/** The width of the glyph pixels: the advance minus the font's trailing spacing column. */
	private static int glyphWidth(Font font, String s) {
		return Math.max(0, font.width(s) - 1);
	}
}
