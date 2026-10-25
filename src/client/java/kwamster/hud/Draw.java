package kwamster.hud;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/**
 * Drawing helpers for the HUD's dark-glass look (§7.0): 1 px rounded boxes (corner pixels left
 * unpainted), borders and highlights that skip the same corners, the outside-only drop shadow, alpha
 * multiplication, and text that is skipped when nearly transparent.
 *
 * <p>Every rect is half-open {@code [x0, x1) × [y0, y1)} in the current pose. Client thread.
 */
public final class Draw {
	/** Label text. */
	public static final int DIM = 0xFF6B7280;
	public static final int GOOD = 0xFF3DDC84;
	public static final int WARN = 0xFFFFC53D;
	public static final int BAD = 0xFFFF5A5F;
	/** Secondary highlight. */
	public static final int ACCENT2 = 0xFF22D3EE;
	/** 1 px line under the top border. */
	public static final int TOP_HIGHLIGHT = 0x14FFFFFF;
	/** Divider lines inside panels and cards. */
	public static final int DIVIDER = 0x1FFFFFFF;
	/** First and second shadow rows under a box. */
	public static final int SHADOW_1 = 0x40000000;
	public static final int SHADOW_2 = 0x20000000;
	/** Text whose alpha is below this is not drawn (§7.0). */
	public static final int MIN_TEXT_ALPHA = 0x08;

	private Draw() {
	}

	// ---- colour maths ---------------------------------------------------------------

	/** {@code argb} with its alpha multiplied by {@code alpha} (clamped to [0, 1]). */
	public static int mul(int argb, float alpha) {
		if (alpha >= 1f) {
			return argb;
		}
		if (!(alpha > 0f)) {
			return argb & 0x00FFFFFF;
		}
		int a = Math.round((argb >>> 24) * alpha);
		return (a << 24) | (argb & 0x00FFFFFF);
	}

	/** {@code argb} with its alpha replaced by {@code a} (0..255). */
	public static int withAlpha(int argb, int a) {
		return (Math.max(0, Math.min(255, a)) << 24) | (argb & 0x00FFFFFF);
	}

	/** Channel-wise interpolation from {@code a} to {@code b} (t clamped to [0, 1]). */
	public static int lerp(int a, int b, float t) {
		float k = Anim.clamp01(t);
		if (k <= 0f) {
			return a;
		}
		if (k >= 1f) {
			return b;
		}
		int aa = a >>> 24, ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
		int ba = b >>> 24, br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
		return (Math.round(aa + (ba - aa) * k) << 24)
				| (Math.round(ar + (br - ar) * k) << 16)
				| (Math.round(ag + (bg - ag) * k) << 8)
				| Math.round(ab + (bb - ab) * k);
	}

	// ---- shapes -----------------------------------------------------------------------

	/** A filled rect; skipped when empty or fully transparent. */
	public static void fill(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
		if (x1 <= x0 || y1 <= y0 || (argb >>> 24) == 0) {
			return;
		}
		g.fill(x0, y0, x1, y1, argb);
	}

	/** A filled box with its four corner pixels left unpainted (1 px rounded corners). */
	public static void roundRect(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
		if (x1 - x0 < 3 || y1 - y0 < 3) {
			fill(g, x0, y0, x1, y1, argb);
			return;
		}
		fill(g, x0 + 1, y0, x1 - 1, y0 + 1, argb);
		fill(g, x0, y0 + 1, x1, y1 - 1, argb);
		fill(g, x0 + 1, y1 - 1, x1 - 1, y1, argb);
	}

	/** A 1 px border inside the box, skipping the four corner pixels (same silhouette as {@link #roundRect}). */
	public static void border(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
		if (x1 - x0 < 3 || y1 - y0 < 3) {
			return;
		}
		fill(g, x0 + 1, y0, x1 - 1, y0 + 1, argb);
		fill(g, x0 + 1, y1 - 1, x1 - 1, y1, argb);
		fill(g, x0, y0 + 1, x0 + 1, y1 - 1, argb);
		fill(g, x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/** The 1 px highlight line directly under the top border, inside the side borders. */
	public static void topHighlight(GuiGraphics g, int x0, int y0, int x1, int argb) {
		fill(g, x0 + 1, y0 + 1, x1 - 1, y0 + 2, argb);
	}

	/**
	 * The outside-only drop shadow (§7.0): a 1 px row directly under the bottom edge {@code y1}, then a
	 * second row under that, both inset by 1 px at each end. Never under the body.
	 */
	public static void shadow(GuiGraphics g, int x0, int y1, int x1, float alpha) {
		fill(g, x0 + 1, y1, x1 - 1, y1 + 1, mul(SHADOW_1, alpha));
		fill(g, x0 + 1, y1 + 1, x1 - 1, y1 + 2, mul(SHADOW_2, alpha));
	}

	/**
	 * A complete dark-glass box: outside shadow, rounded body, border and top highlight (the highlight
	 * is skipped when {@code highlight} is 0).
	 */
	public static void glass(GuiGraphics g, int x0, int y0, int x1, int y1, int bg, int borderArgb, int highlight, float alpha) {
		shadow(g, x0, y1, x1, alpha);
		roundRect(g, x0, y0, x1, y1, mul(bg, alpha));
		border(g, x0, y0, x1, y1, mul(borderArgb, alpha));
		if (highlight != 0) {
			topHighlight(g, x0, y0, x1, mul(highlight, alpha));
		}
	}

	/** A full 1 px rectangle outline (corners included), drawn inside {@code [x0, x1) × [y0, y1)}. */
	public static void outline(GuiGraphics g, int x0, int y0, int x1, int y1, int argb) {
		if (x1 - x0 < 2 || y1 - y0 < 2) {
			fill(g, x0, y0, x1, y1, argb);
			return;
		}
		fill(g, x0, y0, x1, y0 + 1, argb);
		fill(g, x0, y1 - 1, x1, y1, argb);
		fill(g, x0, y0 + 1, x0 + 1, y1 - 1, argb);
		fill(g, x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	// ---- text ---------------------------------------------------------------------------

	/** Draws {@code s} unless its colour is nearly transparent; returns the advance ({@code font.width(s)}). */
	public static int text(GuiGraphics g, Font font, String s, int x, int y, int argb, boolean shadow) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		if ((argb >>> 24) >= MIN_TEXT_ALPHA) {
			g.drawString(font, s, x, y, argb, shadow);
		}
		return font.width(s);
	}

	/** Draws {@code c} unless its colour is nearly transparent; returns the advance ({@code font.width(c)}). */
	public static int text(GuiGraphics g, Font font, Component c, int x, int y, int argb, boolean shadow) {
		if ((argb >>> 24) >= MIN_TEXT_ALPHA) {
			g.drawString(font, c, x, y, argb, shadow);
		}
		return font.width(c);
	}

	/**
	 * Draws {@code s} right-aligned so its last glyph pixel column is {@code right - 1} (the font's
	 * trailing spacing pixel is not counted, so left and right paddings look equal); returns its left x.
	 */
	public static int textRight(GuiGraphics g, Font font, String s, int right, int y, int argb, boolean shadow) {
		int x = rightAligned(font.width(s), right);
		text(g, font, s, x, y, argb, shadow);
		return x;
	}

	/** Left x for text of advance {@code width} whose last glyph pixel column is {@code right - 1}. */
	public static int rightAligned(int width, int right) {
		return width <= 0 ? right : right - width + 1;
	}
}
