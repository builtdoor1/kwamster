package kwamster.configui;

import org.jspecify.annotations.Nullable;

import java.util.Locale;
import java.util.OptionalInt;

/**
 * Minecraft-free colour maths behind the colour picker (R6 style, unit-tested): ARGB channels,
 * clamping, the {@code #AARRGGBB} text form and HSV.
 *
 * <p>Colours are packed ARGB ints with alpha in the top byte, the form every colour in
 * {@code KwamsterConfig} uses. HSV has the hue in degrees ({@code [0, 360]}, where 360 is the same
 * colour as 0) and saturation and value in {@code [0, 1]}. The conversions work in doubles and round
 * once at the very end, so every one of the 2^24 RGB colours survives RGB → HSV → RGB unchanged,
 * greys and black included.
 */
final class ColorMath {
	/** Bit offset of the alpha byte, for {@link #channel} and {@link #withChannel}. */
	static final int ALPHA_SHIFT = 24;
	static final int RED_SHIFT = 16;
	static final int GREEN_SHIFT = 8;
	static final int BLUE_SHIFT = 0;
	/** Opaque black, the bottom of every saturation/value column. */
	static final int BLACK = 0xFF000000;

	private ColorMath() {
	}

	/**
	 * A colour in HSV.
	 *
	 * @param hue        degrees, {@code [0, 360]}
	 * @param saturation {@code [0, 1]}
	 * @param value      {@code [0, 1]}
	 */
	record Hsv(double hue, double saturation, double value) {
	}

	// ---- channels ---------------------------------------------------------------------

	static int alpha(int argb) {
		return argb >>> ALPHA_SHIFT;
	}

	static int red(int argb) {
		return (argb >>> RED_SHIFT) & 0xFF;
	}

	static int green(int argb) {
		return (argb >>> GREEN_SHIFT) & 0xFF;
	}

	static int blue(int argb) {
		return argb & 0xFF;
	}

	/** The 8-bit channel at {@code shift} (one of the {@code *_SHIFT} constants). */
	static int channel(int argb, int shift) {
		return (argb >>> shift) & 0xFF;
	}

	/** {@code argb} with the channel at {@code shift} replaced by {@code value}, clamped to 0..255. */
	static int withChannel(int argb, int shift, int value) {
		return (argb & ~(0xFF << shift)) | (clampChannel(value) << shift);
	}

	/** {@code argb} with its alpha replaced by {@code alpha}, clamped to 0..255. */
	static int withAlpha(int argb, int alpha) {
		return withChannel(argb, ALPHA_SHIFT, alpha);
	}

	/** {@code argb} with its alpha raised to {@code minAlpha} (clamped to 0..255) when it is lower. */
	static int atLeastAlpha(int argb, int minAlpha) {
		int min = clampChannel(minAlpha);
		return alpha(argb) < min ? withAlpha(argb, min) : argb;
	}

	/** Packs four channels, each clamped to 0..255. */
	static int argb(int alpha, int red, int green, int blue) {
		return (clampChannel(alpha) << ALPHA_SHIFT) | (clampChannel(red) << RED_SHIFT)
				| (clampChannel(green) << GREEN_SHIFT) | clampChannel(blue);
	}

	static int clampChannel(int value) {
		return value < 0 ? 0 : Math.min(value, 255);
	}

	/** {@code value} clamped to {@code [0, 1]}; NaN counts as 0. */
	static double clamp01(double value) {
		if (!(value > 0.0)) {
			return 0.0;
		}
		return Math.min(value, 1.0);
	}

	/**
	 * Where {@code position} lies along a control that starts at {@code start} and is {@code length}
	 * long, as a fraction clamped to {@code [0, 1]}: positions before the start read 0 and positions past
	 * the end read 1, so a drag keeps working after the mouse leaves the control. An empty control reads 0.
	 */
	static double fraction(double position, double start, double length) {
		return length > 0.0 ? clamp01((position - start) / length) : 0.0;
	}

	// ---- text -------------------------------------------------------------------------

	/** {@code "#AARRGGBB"} in upper case, always eight digits. */
	static String toHex(int argb) {
		return String.format(Locale.ROOT, "#%08X", argb);
	}

	/**
	 * Parses a typed colour: an optional {@code #} (or {@code 0x}), then six digits ({@code RRGGBB},
	 * which is opaque) or eight ({@code AARRGGBB}). Upper and lower case are both fine and surrounding
	 * spaces are ignored. Anything else (other lengths, non-hex characters, an empty string) is empty.
	 */
	static OptionalInt parseHex(@Nullable String text) {
		if (text == null) {
			return OptionalInt.empty();
		}
		String s = text.strip();
		if (s.startsWith("#")) {
			s = s.substring(1);
		} else if (s.startsWith("0x") || s.startsWith("0X")) {
			s = s.substring(2);
		}
		int digits = s.length();
		if (digits != 6 && digits != 8) {
			return OptionalInt.empty();
		}
		int value = 0;
		for (int i = 0; i < digits; i++) {
			int d = hexDigit(s.charAt(i));
			if (d < 0) {
				return OptionalInt.empty();
			}
			value = (value << 4) | d;
		}
		return OptionalInt.of(digits == 6 ? BLACK | value : value);
	}

	/**
	 * Parses a typed channel value: one to three ASCII digits. The number is returned as typed (it can
	 * be above 255; {@link #withChannel} clamps it). Anything else is empty.
	 */
	static OptionalInt parseChannel(@Nullable String text) {
		if (text == null) {
			return OptionalInt.empty();
		}
		String s = text.strip();
		if (s.isEmpty() || s.length() > 3) {
			return OptionalInt.empty();
		}
		int value = 0;
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return OptionalInt.empty();
			}
			value = value * 10 + (c - '0');
		}
		return OptionalInt.of(value);
	}

	/** True for the characters a hex field may contain while it is being typed. */
	static boolean isHexInputChar(char c) {
		return hexDigit(c) >= 0 || c == '#' || c == 'x' || c == 'X' || c == ' ';
	}

	/** The value of an ASCII hex digit, or -1 (no Unicode digits from other scripts). */
	private static int hexDigit(char c) {
		if (c >= '0' && c <= '9') {
			return c - '0';
		}
		if (c >= 'a' && c <= 'f') {
			return c - 'a' + 10;
		}
		if (c >= 'A' && c <= 'F') {
			return c - 'A' + 10;
		}
		return -1;
	}

	// ---- HSV --------------------------------------------------------------------------

	/** The HSV form of the RGB part of {@code argb} (alpha is ignored). Greys get hue 0. */
	static Hsv toHsv(int argb) {
		int r = red(argb);
		int g = green(argb);
		int b = blue(argb);
		int max = Math.max(r, Math.max(g, b));
		int min = Math.min(r, Math.min(g, b));
		int delta = max - min;
		double value = max / 255.0;
		double saturation = max == 0 ? 0.0 : (double) delta / max;
		double hue;
		if (delta == 0) {
			hue = 0.0;
		} else if (max == r) {
			hue = 60.0 * ((double) (g - b) / delta);
			if (hue < 0.0) {
				hue += 360.0;
			}
		} else if (max == g) {
			hue = 60.0 * ((double) (b - r) / delta + 2.0);
		} else {
			hue = 60.0 * ((double) (r - g) / delta + 4.0);
		}
		return new Hsv(hue, saturation, value);
	}

	/**
	 * Like {@link #toHsv(int)}, but a grey keeps {@code previousHue} and black also keeps
	 * {@code previousSaturation}, so a picker's hue strip and saturation do not jump when the colour
	 * passes through a grey or black. The result still converts back to exactly {@code argb}'s RGB.
	 */
	static Hsv toHsv(int argb, double previousHue, double previousSaturation) {
		Hsv hsv = toHsv(argb);
		if (hsv.value() == 0.0) {
			return new Hsv(clampHue(previousHue), clamp01(previousSaturation), 0.0);
		}
		if (hsv.saturation() == 0.0) {
			return new Hsv(clampHue(previousHue), 0.0, hsv.value());
		}
		return hsv;
	}

	/** {@link #hsvToRgb(double, double, double)} for an {@link Hsv}. */
	static int hsvToRgb(Hsv hsv) {
		return hsvToRgb(hsv.hue(), hsv.saturation(), hsv.value());
	}

	/**
	 * The opaque colour ({@code 0xFFRRGGBB}) for an HSV triple. The hue wraps (360 and -360 are 0);
	 * saturation and value are clamped to {@code [0, 1]}; NaN counts as 0.
	 */
	static int hsvToRgb(double hue, double saturation, double value) {
		double s = clamp01(saturation);
		double v = clamp01(value);
		double h = Double.isFinite(hue) ? hue % 360.0 : 0.0;
		if (h < 0.0) {
			h += 360.0;
		}
		double c = v * s;
		double sector = h / 60.0;
		double x = c * (1.0 - Math.abs(sector % 2.0 - 1.0));
		double m = v - c;
		double r;
		double g;
		double b;
		switch (Math.min(5, (int) sector)) {
			case 0 -> {
				r = c;
				g = x;
				b = 0.0;
			}
			case 1 -> {
				r = x;
				g = c;
				b = 0.0;
			}
			case 2 -> {
				r = 0.0;
				g = c;
				b = x;
			}
			case 3 -> {
				r = 0.0;
				g = x;
				b = c;
			}
			case 4 -> {
				r = x;
				g = 0.0;
				b = c;
			}
			default -> {
				r = c;
				g = 0.0;
				b = x;
			}
		}
		return BLACK | (to8(r + m) << RED_SHIFT) | (to8(g + m) << GREEN_SHIFT) | to8(b + m);
	}

	/** {@code hue} clamped to {@code [0, 360]}; NaN counts as 0. */
	static double clampHue(double hue) {
		if (!(hue > 0.0)) {
			return 0.0;
		}
		return Math.min(hue, 360.0);
	}

	private static int to8(double unit) {
		return clampChannel((int) Math.round(unit * 255.0));
	}
}
