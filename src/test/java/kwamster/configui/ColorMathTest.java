package kwamster.configui;

import org.junit.jupiter.api.Test;

import java.util.OptionalInt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The colour picker's Minecraft-free maths: channels, the alpha floor, hex text and exact HSV round trips. */
final class ColorMathTest {
	private static final double EPS = 1e-9;

	@Test
	void channelsUnpackAndPack() {
		int c = 0x80FF4020;
		assertEquals(0x80, ColorMath.alpha(c));
		assertEquals(0xFF, ColorMath.red(c));
		assertEquals(0x40, ColorMath.green(c));
		assertEquals(0x20, ColorMath.blue(c));
		assertEquals(0x80, ColorMath.channel(c, ColorMath.ALPHA_SHIFT));
		assertEquals(0xFF, ColorMath.channel(c, ColorMath.RED_SHIFT));
		assertEquals(0x40, ColorMath.channel(c, ColorMath.GREEN_SHIFT));
		assertEquals(0x20, ColorMath.channel(c, ColorMath.BLUE_SHIFT));
		assertEquals(c, ColorMath.argb(0x80, 0xFF, 0x40, 0x20));
		assertEquals(0xFF00FF00, ColorMath.argb(300, -5, 255, 0), "each channel is clamped to 0..255");
		assertEquals(0xFF, ColorMath.alpha(0xFFFFFFFF), "alpha is unsigned");
	}

	@Test
	void withChannelReplacesOneChannelAndClamps() {
		int c = 0x11223344;
		assertEquals(0xAA223344, ColorMath.withChannel(c, ColorMath.ALPHA_SHIFT, 0xAA));
		assertEquals(0x11AA3344, ColorMath.withChannel(c, ColorMath.RED_SHIFT, 0xAA));
		assertEquals(0x1122AA44, ColorMath.withChannel(c, ColorMath.GREEN_SHIFT, 0xAA));
		assertEquals(0x112233AA, ColorMath.withChannel(c, ColorMath.BLUE_SHIFT, 0xAA));
		assertEquals(0x11FF3344, ColorMath.withChannel(c, ColorMath.RED_SHIFT, 999));
		assertEquals(0x00223344, ColorMath.withAlpha(c, -1));
		assertEquals(0xFF223344, ColorMath.withAlpha(c, 256));
	}

	@Test
	void textAlphaFloorOnlyRaisesLowAlpha() {
		assertEquals(0x20FFFFFF, ColorMath.atLeastAlpha(0x00FFFFFF, 0x20));
		assertEquals(0x20123456, ColorMath.atLeastAlpha(0x1F123456, 0x20));
		assertEquals(0x20123456, ColorMath.atLeastAlpha(0x20123456, 0x20));
		assertEquals(0xFF123456, ColorMath.atLeastAlpha(0xFF123456, 0x20));
		assertEquals(0x00123456, ColorMath.atLeastAlpha(0x00123456, 0), "no floor for non-text colours");
		assertTrue(EntryChecks.textAlphaOk(ColorMath.atLeastAlpha(0x05ABCDEF, 0x20)), "the floor satisfies the entry check");
	}

	@Test
	void hexIsUpperCaseWithEightDigits() {
		assertEquals("#FF8B5CF6", ColorMath.toHex(0xFF8B5CF6));
		assertEquals("#00000000", ColorMath.toHex(0));
		assertEquals("#0000000F", ColorMath.toHex(0xF));
		assertEquals("#FFFFFFFF", ColorMath.toHex(-1));
		assertEquals("#B80E1116", ColorMath.toHex(0xB80E1116));
	}

	@Test
	void hexAcceptsSixOrEightDigitsWithOrWithoutHash() {
		assertParses(0xFF8B5CF6, "#FF8B5CF6");
		assertParses(0xFF8B5CF6, "FF8B5CF6");
		assertParses(0xFF8B5CF6, "#ff8b5cf6");
		assertParses(0xFF8B5CF6, "#8B5CF6");
		assertParses(0xFF8B5CF6, "8b5cf6");
		assertParses(0x268B5CF6, "#268b5CF6");
		assertParses(0x00000000, "#00000000");
		assertParses(0xFF000000, "000000");
		assertParses(0xB80E1116, "  #B80E1116 ");
		assertParses(0xB80E1116, "0xB80E1116");
		assertParses(0xFF0E1116, "0X0E1116");
	}

	@Test
	void hexRejectsEverythingElse() {
		for (String bad : new String[]{null, "", "#", "   ", "#FFF", "#FFFF", "#FFFFF", "#FFFFFFF", "#FFFFFFFFF",
				"##FFFFFF", "#GGGGGG", "#FF FF FF", "FF8B5CF6#", "#-FFFFFF", "0x", "x8B5CF6",
				"００００００" /* full-width digits */, "١٢٣٤٥٦" /* Arabic-Indic */}) {
			assertFalse(ColorMath.parseHex(bad).isPresent(), () -> "should reject: " + bad);
		}
	}

	@Test
	void hexTextRoundTripsForEveryAlphaAndChannelValue() {
		for (int v = 0; v <= 255; v++) {
			for (int c : new int[]{ColorMath.argb(v, 0, 0, 0), ColorMath.argb(0xFF, v, 0, 0), ColorMath.argb(0xFF, 0, v, 0),
					ColorMath.argb(0xFF, 0, 0, v), ColorMath.argb(v, v, v, v), ColorMath.argb(v, 255 - v, v, 255 - v)}) {
				assertParses(c, ColorMath.toHex(c));
				assertParses(c, ColorMath.toHex(c).toLowerCase(java.util.Locale.ROOT));
			}
		}
	}

	@Test
	void channelTextIsOneToThreeDigits() {
		assertEquals(OptionalInt.of(0), ColorMath.parseChannel("0"));
		assertEquals(OptionalInt.of(7), ColorMath.parseChannel("007"));
		assertEquals(OptionalInt.of(255), ColorMath.parseChannel("255"));
		assertEquals(OptionalInt.of(300), ColorMath.parseChannel("300"), "returned as typed; withChannel clamps");
		assertEquals(OptionalInt.of(42), ColorMath.parseChannel(" 42 "));
		for (String bad : new String[]{null, "", " ", "1000", "-1", "+1", "1.5", "ff", "١"}) {
			assertFalse(ColorMath.parseChannel(bad).isPresent(), () -> "should reject: " + bad);
		}
		assertEquals(0x11FF3344, ColorMath.withChannel(0x11223344, ColorMath.RED_SHIFT, ColorMath.parseChannel("300").getAsInt()));
	}

	@Test
	void hexFieldFilterAllowsOnlyHexInput() {
		for (char c : "0123456789abcdefABCDEF#xX ".toCharArray()) {
			assertTrue(ColorMath.isHexInputChar(c), () -> "should allow '" + c + "'");
		}
		for (char c : "gGzZ-+.,;é０".toCharArray()) {
			assertFalse(ColorMath.isHexInputChar(c), () -> "should block '" + c + "'");
		}
	}

	@Test
	void primariesAndSecondariesHaveTheirHues() {
		assertHsv(0xFFFF0000, 0, 1, 1);
		assertHsv(0xFFFFFF00, 60, 1, 1);
		assertHsv(0xFF00FF00, 120, 1, 1);
		assertHsv(0xFF00FFFF, 180, 1, 1);
		assertHsv(0xFF0000FF, 240, 1, 1);
		assertHsv(0xFFFF00FF, 300, 1, 1);
		assertHsv(0xFF000000, 0, 0, 0);
		assertHsv(0xFFFFFFFF, 0, 0, 1);
		assertHsv(0xFF808080, 0, 0, 128 / 255.0);
		assertHsv(0xFF800000, 0, 1, 128 / 255.0);
		assertHsv(0xFFFF8080, 0, 127 / 255.0, 1);

		assertEquals(0xFFFF0000, ColorMath.hsvToRgb(0, 1, 1));
		assertEquals(0xFFFFFF00, ColorMath.hsvToRgb(60, 1, 1));
		assertEquals(0xFF00FF00, ColorMath.hsvToRgb(120, 1, 1));
		assertEquals(0xFF00FFFF, ColorMath.hsvToRgb(180, 1, 1));
		assertEquals(0xFF0000FF, ColorMath.hsvToRgb(240, 1, 1));
		assertEquals(0xFFFF00FF, ColorMath.hsvToRgb(300, 1, 1));
		assertEquals(0xFFFF0000, ColorMath.hsvToRgb(360, 1, 1), "360 degrees is red again");
		assertEquals(0xFFFF8000, ColorMath.hsvToRgb(30, 1, 1));
		assertEquals(0xFFFFFFFF, ColorMath.hsvToRgb(200, 0, 1), "no saturation is grey whatever the hue");
		assertEquals(0xFF000000, ColorMath.hsvToRgb(200, 1, 0), "no value is black whatever the hue");
	}

	@Test
	void everyRgbColourSurvivesHsvAndBackExactly() {
		int failures = 0;
		int firstFailure = 0;
		for (int rgb = 0; rgb <= 0xFFFFFF; rgb++) {
			ColorMath.Hsv hsv = ColorMath.toHsv(rgb);
			boolean inRange = hsv.hue() >= 0.0 && hsv.hue() < 360.0
					&& hsv.saturation() >= 0.0 && hsv.saturation() <= 1.0
					&& hsv.value() >= 0.0 && hsv.value() <= 1.0;
			int back = ColorMath.hsvToRgb(hsv);
			if (!inRange || back != (0xFF000000 | rgb)) {
				if (failures++ == 0) {
					firstFailure = rgb;
				}
			}
		}
		String first = ColorMath.toHex(firstFailure);
		assertEquals(0, failures, () -> "colours that did not survive RGB -> HSV -> RGB, first: " + first);
	}

	@Test
	void hexToHsvToHexIsExactForSaturatedGreyAndTranslucentColours() {
		String[] samples = {"#FF8B5CF6", "#FFFFD43B", "#FF3DDC97", "#FFB794F6", "#FFFF922B", "#FF4DABF7", "#FFE9ECEF",
				"#FF868E96", "#FF3DDC84", "#FFFFC53D", "#FFFF5A5F", "#B80E1116", "#26FFFFFF", "#EB4CD964", "#8C000000",
				"#FF000000", "#FFFFFFFF", "#FF7F7F7F", "#FF010101", "#FFFEFEFE", "#20FFFFFF", "#00000000", "#FFFF0001",
				"#FF0100FF", "#FF00FF01"};
		for (String hex : samples) {
			int c = ColorMath.parseHex(hex).getAsInt();
			int back = ColorMath.withAlpha(ColorMath.hsvToRgb(ColorMath.toHsv(c)), ColorMath.alpha(c));
			assertEquals(hex, ColorMath.toHex(back));
		}
		for (int v = 0; v <= 255; v++) {
			int grey = ColorMath.argb(0xFF, v, v, v);
			assertEquals(grey, ColorMath.hsvToRgb(ColorMath.toHsv(grey)), "grey " + v);
			for (int hue = 0; hue < 360; hue += 60) {
				int pure = ColorMath.hsvToRgb(hue, 1, v / 255.0);
				assertEquals(pure, ColorMath.hsvToRgb(ColorMath.toHsv(pure)), "saturated " + ColorMath.toHex(pure));
			}
		}
	}

	@Test
	void greysKeepThePreviousHueAndBlackAlsoTheSaturation() {
		ColorMath.Hsv grey = ColorMath.toHsv(0xFF808080, 210.0, 0.75);
		assertEquals(210.0, grey.hue(), EPS);
		assertEquals(0.0, grey.saturation(), EPS);
		assertEquals(128 / 255.0, grey.value(), EPS);
		assertEquals(0xFF808080, ColorMath.hsvToRgb(grey));

		ColorMath.Hsv black = ColorMath.toHsv(0xFF000000, 210.0, 0.75);
		assertEquals(210.0, black.hue(), EPS);
		assertEquals(0.75, black.saturation(), EPS);
		assertEquals(0.0, black.value(), EPS);
		assertEquals(0xFF000000, ColorMath.hsvToRgb(black));

		ColorMath.Hsv colour = ColorMath.toHsv(0xFF00FF00, 210.0, 0.75);
		assertEquals(120.0, colour.hue(), EPS, "a real colour brings its own hue");
		assertEquals(1.0, colour.saturation(), EPS);

		ColorMath.Hsv sanitised = ColorMath.toHsv(0xFF000000, Double.NaN, 7.0);
		assertEquals(0.0, sanitised.hue(), EPS);
		assertEquals(1.0, sanitised.saturation(), EPS);

		for (int v = 0; v <= 255; v++) {
			for (double hue : new double[]{0.0, 47.5, 359.9, 360.0}) {
				int g = ColorMath.argb(0xFF, v, v, v);
				assertEquals(g, ColorMath.hsvToRgb(ColorMath.toHsv(g, hue, 0.5)), "grey " + v + " with kept hue " + hue);
			}
		}
	}

	@Test
	void hsvToRgbWrapsHueAndClampsTheRest() {
		assertEquals(ColorMath.hsvToRgb(30, 1, 1), ColorMath.hsvToRgb(390, 1, 1));
		assertEquals(ColorMath.hsvToRgb(330, 1, 1), ColorMath.hsvToRgb(-30, 1, 1));
		assertEquals(0xFFFF0000, ColorMath.hsvToRgb(-360, 1, 1));
		assertEquals(0xFFFF0000, ColorMath.hsvToRgb(0, 5, 3), "saturation and value above 1 count as 1");
		assertEquals(0xFF000000, ColorMath.hsvToRgb(0, 1, -2));
		assertEquals(0xFFFFFFFF, ColorMath.hsvToRgb(Double.NaN, Double.NaN, 1), "NaN hue and saturation count as 0");
		assertEquals(0xFFFF0000, ColorMath.hsvToRgb(Double.POSITIVE_INFINITY, 1, 1));
		assertEquals(0.0, ColorMath.clampHue(-5), EPS);
		assertEquals(360.0, ColorMath.clampHue(400), EPS);
		assertEquals(0.0, ColorMath.clampHue(Double.NaN), EPS);
	}

	@Test
	void fractionClampsOutsideTheControl() {
		assertEquals(0.0, ColorMath.fraction(5, 10, 100), EPS, "left of the control");
		assertEquals(0.0, ColorMath.fraction(10, 10, 100), EPS);
		assertEquals(0.25, ColorMath.fraction(35, 10, 100), EPS);
		assertEquals(1.0, ColorMath.fraction(110, 10, 100), EPS);
		assertEquals(1.0, ColorMath.fraction(500, 10, 100), EPS, "right of the control");
		assertEquals(0.0, ColorMath.fraction(50, 10, 0), EPS, "an empty control");
		assertEquals(0.0, ColorMath.fraction(Double.NaN, 10, 100), EPS);
		assertEquals(0.0, ColorMath.clamp01(-0.5), EPS);
		assertEquals(1.0, ColorMath.clamp01(1.5), EPS);
	}

	private static void assertParses(int expected, String text) {
		OptionalInt parsed = ColorMath.parseHex(text);
		assertTrue(parsed.isPresent(), () -> "should parse: \"" + text + "\"");
		assertEquals(ColorMath.toHex(expected), ColorMath.toHex(parsed.getAsInt()), () -> "parsing \"" + text + "\"");
	}

	private static void assertHsv(int argb, double hue, double saturation, double value) {
		ColorMath.Hsv hsv = ColorMath.toHsv(argb);
		String hex = ColorMath.toHex(argb);
		assertEquals(hue, hsv.hue(), EPS, () -> "hue of " + hex);
		assertEquals(saturation, hsv.saturation(), EPS, () -> "saturation of " + hex);
		assertEquals(value, hsv.value(), EPS, () -> "value of " + hex);
	}
}
