package kwamster.hud;

/**
 * Advance widths of vanilla's default font, for the fit tests only (§7.4, §12.2): the game itself
 * always measures with {@code font.width(...)}. Minecraft-free (R6).
 *
 * <p>The ASCII table (U+0020..U+007E) was measured from {@code assets/minecraft/textures/font/ascii.png}
 * of the 1.21.11 client jar with vanilla's own rule ({@code BitmapProvider.Definition.getActualGlyphWidth}:
 * the rightmost column with a non-transparent pixel, plus one, plus one pixel of spacing), and the space
 * comes from {@code include/space.json} (4). The few non-ASCII glyphs KWAMSTER prints on the HUD
 * (middle dot, em dash, black circle, multiplication sign, up arrow, ellipsis) were measured the same way
 * from {@code nonlatin_european.png}. Bold adds one pixel per glyph ({@code GlyphInfo.getBoldOffset}).
 */
public final class AsciiWidths {
	/** Advance of a glyph this table does not know: the common width of a letter. */
	public static final int UNKNOWN = 6;

	/** Advances of U+0020 (space) .. U+007E (tilde). */
	private static final int[] ASCII = {
			4, 2, 4, 6, 6, 6, 6, 2, 4, 4, 4, 6, 2, 6, 2, 6, // space ! " # $ % & ' ( ) * + , - . /
			6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 2, 2, 5, 6, 5, 6, // 0-9 : ; < = > ?
			7, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, // @ A-O
			6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 6, 4, 6, 4, 6, 6, // P-Z [ \ ] ^ _
			3, 6, 6, 6, 6, 6, 5, 6, 6, 2, 6, 5, 3, 6, 6, 6, // ` a-o
			6, 6, 6, 6, 4, 6, 6, 6, 6, 6, 6, 4, 2, 4, 7     // p-z { | } ~
	};

	private AsciiWidths() {
	}

	/** The advance of one code point (regular weight). Control characters are 0; unknown glyphs are {@link #UNKNOWN}. */
	public static int advance(int codePoint) {
		if (codePoint >= 0x20 && codePoint <= 0x7E) {
			return ASCII[codePoint - 0x20];
		}
		return switch (codePoint) {
			case 0x00B7 -> 2;  // · middle dot
			case 0x2014 -> 9;  // — em dash (StatFormat.DASH)
			case 0x25CF -> 5;  // ● black circle
			case 0x00D7 -> 6;  // × multiplication sign
			case 0x2191 -> 6;  // ↑ up arrow
			case 0x2026 -> 8;  // … ellipsis
			case 0x200C -> 0;  // zero-width non-joiner (space.json)
			default -> codePoint < 0x20 ? 0 : UNKNOWN;
		};
	}

	/** Width of {@code s} in regular weight, as {@code font.width(s)} returns it for vanilla's font. */
	public static int width(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		int w = 0;
		for (int i = 0; i < s.length(); ) {
			int cp = s.codePointAt(i);
			w += advance(cp);
			i += Character.charCount(cp);
		}
		return w;
	}

	/** Width of {@code s} in bold: one extra pixel per glyph. */
	public static int widthBold(String s) {
		if (s == null || s.isEmpty()) {
			return 0;
		}
		return width(s) + s.codePointCount(0, s.length());
	}
}
