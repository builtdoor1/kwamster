package kwamster.core.config;

import kwamster.core.io.Json;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * HUD presets: each one applies cleanly and survives {@code clampAndFill}, keeps text readable, writes
 * only fields a look may change, KWAMSTER is the shipped look, and the matcher recognises every preset.
 */
final class HudPresetTest {
	/** Every field a preset may change (the presets brief), as {@link #flatten} paths. */
	private static final Set<String> ALLOWED = Set.of(
			"hud.accentArgb", "hud.panelBgArgb", "hud.borderArgb", "hud.textArgb", "hud.labelArgb",
			"hud.panelCompact", "hud.comboCounterEnabled", "hud.comboCounterMin", "hud.valueFlash", "hud.panelWhenIdle",
			"hud.rows.hits", "hud.rows.reach", "hud.rows.combo", "hud.rows.streak", "hud.rows.crits", "hud.rows.sprint",
			"hud.rows.threeBlock", "hud.rows.jumpResets", "hud.rows.interval", "hud.rows.taken", "hud.rows.aim",
			"hud.rows.trades", "hud.rows.advantage", "hud.rows.record",
			"feedback.indicatorEnabled", "feedback.indicatorDurationMs", "feedback.indicatorShowReach", "feedback.indicatorShowMisses",
			"alert.cardDurationMs", "alert.cardFadeMs", "alert.cardFooterHint",
			"crosshair.shape", "crosshair.size", "crosshair.gap", "crosshair.thickness", "crosshair.outline",
			"crosshair.outlineArgb", "crosshair.centerDot", "crosshair.scaleWithGui", "crosshair.hitFlash", "crosshair.hitFlashMs",
			"crosshair.defaultArgb", "crosshair.onPlayerArgb", "crosshair.critReadyArgb", "crosshair.critOnPlayerArgb",
			"direction.forwardArgb", "direction.backwardArgb", "direction.idleArgb", "direction.backdropArgb",
			"direction.barWidth", "direction.barHeight");

	@Test
	void everyPresetAppliesCleanlyAndSurvivesClampAndFill() {
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = KwamsterConfig.defaults();
			assertDoesNotThrow(() -> p.apply(c), p.name());
			String applied = Json.PRETTY.toJson(c);
			c.clampAndFill();
			assertEquals(applied, Json.PRETTY.toJson(c), p + " has a value clampAndFill changes");
			assertTrue(p.matches(c), p + " still matches after clampAndFill");

			KwamsterConfig twice = KwamsterConfig.defaults();
			p.apply(twice);
			p.apply(twice);
			assertEquals(applied, Json.PRETTY.toJson(twice), p + " applied twice");
		}
	}

	@Test
	void applyFillsAConfigThatWasNeverClamped() {
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = new KwamsterConfig();
			c.hud.rows = null;
			c.crosshair = null;
			assertDoesNotThrow(() -> p.apply(c), p.name());
			assertTrue(p.matches(c), p.name());
		}
		assertFalse(HudPreset.KWAMSTER.matches(null));
		KwamsterConfig broken = KwamsterConfig.defaults();
		broken.direction = null;
		assertNull(HudPreset.matching(broken), "a config with a missing section matches nothing");
	}

	@Test
	void applyKeepsTheSectionObjects() {
		// Cloth's save consumers and every reader fetch the live sections; a preset must write into them.
		KwamsterConfig c = KwamsterConfig.defaults();
		Object hud = c.hud;
		Object rows = c.hud.rows;
		Object feedback = c.feedback;
		Object tiers = c.feedback.tiers;
		Object alert = c.alert;
		Object crosshair = c.crosshair;
		Object direction = c.direction;
		Object layouts = c.layouts;
		HudPreset.NEON.apply(c);
		assertSame(hud, c.hud);
		assertSame(rows, c.hud.rows);
		assertSame(feedback, c.feedback);
		assertSame(tiers, c.feedback.tiers);
		assertSame(alert, c.alert);
		assertSame(crosshair, c.crosshair);
		assertSame(direction, c.direction);
		assertSame(layouts, c.layouts);
	}

	@Test
	void textColoursStayReadable() {
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = p.sample();
			assertTrue((c.hud.textArgb >>> 24) >= KwamsterConfig.MIN_TEXT_ALPHA, p + " values");
			assertTrue((c.hud.labelArgb >>> 24) >= KwamsterConfig.MIN_TEXT_ALPHA, p + " labels");
			// the accent colours text too (the wordmark, combo numbers 4 and 5)
			assertTrue((c.hud.accentArgb >>> 24) >= KwamsterConfig.MIN_TEXT_ALPHA, p + " accent");
		}
	}

	@Test
	void presetsWriteNoForbiddenField() {
		for (HudPreset p : HudPreset.values()) {
			// From the defaults and from a config where every value differs from its default: a forbidden
			// write shows up in one of the two, whatever value it writes.
			for (KwamsterConfig start : List.of(KwamsterConfig.defaults(), scrambled())) {
				Map<String, Object> before = flatten(start);
				p.apply(start);
				Map<String, Object> after = flatten(start);
				assertEquals(before.keySet(), after.keySet(), p + " changed the shape of the config");
				for (Map.Entry<String, Object> e : before.entrySet()) {
					if (!ALLOWED.contains(e.getKey())) {
						assertEquals(e.getValue(), after.get(e.getKey()), p + " wrote " + e.getKey());
					}
				}
			}
		}
	}

	@Test
	void lookFieldsAreAllowedAndExist() {
		Map<String, Object> real = flatten(KwamsterConfig.defaults());
		for (String allowed : ALLOWED) {
			assertTrue(real.containsKey(allowed), allowed + " is not a KwamsterConfig field (test typo?)");
		}
		List<String> paths = HudPreset.fieldPaths();
		assertEquals(paths.size(), new HashSet<>(paths).size(), "no look field twice");
		for (String path : paths) {
			assertTrue(ALLOWED.contains(path), path + " is not a field a look may change");
			assertTrue(real.containsKey(path), path + " is not a KwamsterConfig field");
		}
		// whole-feature switches stay the player's; so do the GUI-scale crosshair and the idle panel scope
		for (String kept : List.of("general.enabled", "crosshair.enabled", "alert.cardEnabled", "direction.headBarEnabled",
				"direction.stoplightEnabled", "feedback.soundsEnabled", "crosshair.scaleWithGui", "hud.panelWhenIdle")) {
			assertFalse(paths.contains(kept), kept);
		}
	}

	@Test
	void kwamsterIsTheShippedLook() {
		Map<String, Object> defaults = flatten(KwamsterConfig.defaults());
		KwamsterConfig c = scrambled();
		HudPreset.KWAMSTER.apply(c);
		Map<String, Object> after = flatten(c);
		for (String path : HudPreset.fieldPaths()) {
			assertEquals(defaults.get(path), after.get(path), path);
		}
		assertTrue(HudPreset.KWAMSTER.matches(KwamsterConfig.defaults()));
		assertSame(HudPreset.KWAMSTER, HudPreset.matching(KwamsterConfig.defaults()));
		assertSame(HudPreset.KWAMSTER, HudPreset.matching(new KwamsterConfig()));
	}

	@Test
	void matcherFindsEachPresetAfterApplyingIt() {
		for (HudPreset p : HudPreset.values()) {
			for (KwamsterConfig c : List.of(KwamsterConfig.defaults(), scrambled())) {
				p.apply(c);
				assertSame(p, HudPreset.matching(c), p.name());
				for (HudPreset other : HudPreset.values()) {
					assertEquals(other == p, other.matches(c), other + " against " + p);
				}
			}
		}
	}

	@Test
	void presetsAreDistinctAndTheLastOneApplyWins() {
		Set<String> looks = new HashSet<>();
		for (HudPreset p : HudPreset.values()) {
			assertTrue(looks.add(look(p.sample())), p + " repeats another preset's look");
		}
		for (HudPreset a : HudPreset.values()) {
			for (HudPreset b : HudPreset.values()) {
				KwamsterConfig ab = KwamsterConfig.defaults();
				a.apply(ab);
				b.apply(ab);
				assertEquals(Json.PRETTY.toJson(b.sample()), Json.PRETTY.toJson(ab), a + " then " + b);
			}
		}
	}

	@Test
	void aCustomLookMatchesNoPreset() {
		KwamsterConfig c = KwamsterConfig.defaults();
		c.hud.accentArgb = 0xFF123456;
		assertNull(HudPreset.matching(c));
		HudPreset.CLEAN.apply(c);
		c.crosshair.critReadyArgb = 0xFF00FF00;
		assertNull(HudPreset.matching(c));

		// fields outside the look do not matter
		KwamsterConfig d = KwamsterConfig.defaults();
		d.crosshair.scaleWithGui = true;
		d.hud.panelWhenIdle = KwamsterConfig.PanelIdle.SESSION;
		d.layout(HudElementId.STATS_PANEL).offsetX = 40;
		d.feedback.threeBlockReach = 2.8f;
		d.alert.cardEnabled = false;
		assertSame(HudPreset.KWAMSTER, HudPreset.matching(d));
	}

	@Test
	void crosshairStatesAndBarColoursStayApart() {
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = p.sample();
			KwamsterConfig.Crosshair x = c.crosshair;
			assertDistinct(p + " crosshair states", new int[] {x.defaultArgb, x.onPlayerArgb, x.critReadyArgb, x.critOnPlayerArgb});
			KwamsterConfig.Direction d = c.direction;
			assertDistinct(p + " bars", new int[] {d.forwardArgb, d.backwardArgb, d.idleArgb});
		}
	}

	@Test
	void steppedValuesSitOnTheirSliderSteps() {
		// Cloth edits these as scaled sliders (§3.12); an off-step value would show rounded.
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = p.sample();
			assertEquals(0, c.feedback.indicatorDurationMs % 50, p + " indicator duration");
			assertEquals(0, c.alert.cardDurationMs % 100, p + " card duration");
			assertEquals(0, c.alert.cardFadeMs % 50, p + " card fade");
		}
	}

	@Test
	void onlyMinimalSwitchesAnElementOff() {
		for (HudPreset p : HudPreset.values()) {
			KwamsterConfig c = p.sample();
			assertEquals(p != HudPreset.MINIMAL, c.hud.comboCounterEnabled, p + " combo counter");
			assertTrue(c.feedback.indicatorEnabled, p + " hit indicator");
		}
	}

	@Test
	void presetsShowTheirRows() {
		assertRows(HudPreset.CLEAN, "hits", "reach", "combo", "threeBlock", "jumpResets");
		assertRows(HudPreset.MINIMAL, "hits", "reach", "combo");
		assertRows(HudPreset.COMPETITIVE, "hits", "reach", "combo", "streak", "crits", "sprint", "threeBlock",
				"jumpResets", "interval", "advantage");
		assertRows(HudPreset.FULL_STATS, "hits", "reach", "combo", "streak", "crits", "sprint", "threeBlock",
				"jumpResets", "interval", "taken", "aim", "trades", "advantage", "record");
		assertTrue(HudPreset.MINIMAL.sample().hud.panelCompact);
		assertTrue(HudPreset.COMPETITIVE.sample().hud.panelCompact);
		assertEquals(0, HudPreset.CLEAN.sample().hud.borderArgb >>> 24, "Clean has no border");
		assertEquals(KwamsterConfig.CrosshairShape.DOT, HudPreset.MINIMAL.sample().crosshair.shape);
		assertFalse(HudPreset.MINIMAL.sample().feedback.indicatorShowReach);
	}

	@Test
	void namesAndDescriptions() {
		Set<String> names = new HashSet<>();
		for (HudPreset p : HudPreset.values()) {
			assertFalse(p.displayName().isBlank(), p.name());
			assertTrue(names.add(p.displayName().toLowerCase(Locale.ROOT)), p + " repeats a name");
			assertFalse(p.description().isBlank(), p.name());
			assertTrue(p.description().length() <= 72, p + " needs a one-line description");
			assertSame(p, HudPreset.fromName(p.name()));
			assertSame(p, HudPreset.fromName(" " + p.displayName().toLowerCase(Locale.ROOT) + " "));
		}
		assertEquals("KWAMSTER", HudPreset.KWAMSTER.displayName());
		assertEquals("Clean", HudPreset.CLEAN.displayName());
		assertSame(HudPreset.FULL_STATS, HudPreset.fromName("full_stats"));
		assertNull(HudPreset.fromName("nope"));
		assertNull(HudPreset.fromName(null));
		assertNull(HudPreset.fromName("  "));
	}

	@Test
	void sampleIsAFreshConfigEachTime() {
		KwamsterConfig a = HudPreset.NEON.sample();
		KwamsterConfig b = HudPreset.NEON.sample();
		assertNotSame(a, b);
		a.hud.accentArgb = 0xFF000000;
		a.hud.rows.hits = !a.hud.rows.hits;
		assertTrue(HudPreset.NEON.matches(b), "changing one sample leaves the preset alone");
		assertTrue(HudPreset.NEON.matches(HudPreset.NEON.sample()));
	}

	// ---- helpers ---------------------------------------------------------------------------------

	private static void assertRows(HudPreset p, String... rows) {
		Set<String> on = Set.of(rows);
		for (Map.Entry<String, Object> e : flatten(p.sample()).entrySet()) {
			if (e.getKey().startsWith("hud.rows.")) {
				String row = e.getKey().substring("hud.rows.".length());
				assertEquals(on.contains(row), e.getValue(), p + " row " + row);
			}
		}
	}

	/** Every colour mostly opaque, and every pair at least 96 apart in some channel. */
	private static void assertDistinct(String what, int[] colours) {
		for (int i = 0; i < colours.length; i++) {
			assertTrue((colours[i] >>> 24) >= 0xC0, what + ": colour " + i + " is too transparent");
			for (int j = i + 1; j < colours.length; j++) {
				assertTrue(channelGap(colours[i], colours[j]) >= 96, what + ": colours " + i + " and " + j + " are too close ("
						+ Integer.toHexString(colours[i]) + ", " + Integer.toHexString(colours[j]) + ")");
			}
		}
	}

	private static int channelGap(int a, int b) {
		int gap = 0;
		for (int shift = 0; shift <= 16; shift += 8) {
			gap = Math.max(gap, Math.abs(((a >> shift) & 0xFF) - ((b >> shift) & 0xFF)));
		}
		return gap;
	}

	/** The look fields of {@code c} as one string. */
	private static String look(KwamsterConfig c) {
		Map<String, Object> all = flatten(c);
		StringBuilder sb = new StringBuilder();
		for (String path : HudPreset.fieldPaths()) {
			sb.append(path).append('=').append(all.get(path)).append(';');
		}
		return sb.toString();
	}

	/** Every scalar of the config as a {@code section.field} path: maps are walked by key, nested objects by field. */
	private static Map<String, Object> flatten(KwamsterConfig c) {
		Map<String, Object> out = new TreeMap<>();
		flattenFields(c, "", out);
		return out;
	}

	private static void flattenFields(Object obj, String prefix, Map<String, Object> out) {
		for (Field f : obj.getClass().getFields()) {
			if (!Modifier.isStatic(f.getModifiers())) {
				flattenValue(read(f, obj), prefix + f.getName(), out);
			}
		}
	}

	private static void flattenValue(Object v, String path, Map<String, Object> out) {
		if (v == null || v instanceof Number || v instanceof Boolean || v instanceof String || v instanceof Enum<?>) {
			out.put(path, v);
		} else if (v instanceof Map<?, ?> map) {
			for (Map.Entry<?, ?> e : map.entrySet()) {
				flattenValue(e.getValue(), path + "." + e.getKey(), out);
			}
		} else {
			flattenFields(v, path + ".", out);
		}
	}

	/** A filled config in which every scalar differs from its default (booleans flipped, numbers moved, enums advanced). */
	private static KwamsterConfig scrambled() {
		KwamsterConfig c = KwamsterConfig.defaults();
		scramble(c);
		return c;
	}

	private static void scramble(Object obj) {
		for (Field f : obj.getClass().getFields()) {
			int mod = f.getModifiers();
			if (Modifier.isStatic(mod) || Modifier.isFinal(mod)) {
				continue;
			}
			Class<?> t = f.getType();
			try {
				if (t == boolean.class) {
					f.setBoolean(obj, !f.getBoolean(obj));
				} else if (t == int.class) {
					f.setInt(obj, f.getInt(obj) + 7);
				} else if (t == float.class) {
					f.setFloat(obj, f.getFloat(obj) + 0.25f);
				} else if (t == double.class) {
					f.setDouble(obj, f.getDouble(obj) + 0.25);
				} else if (t == long.class) {
					f.setLong(obj, f.getLong(obj) + 7);
				} else if (t == String.class) {
					f.set(obj, f.get(obj) + "-x");
				} else if (t.isEnum()) {
					Object[] all = t.getEnumConstants();
					f.set(obj, all[(((Enum<?>) f.get(obj)).ordinal() + 1) % all.length]);
				} else if (Map.class.isAssignableFrom(t)) {
					for (Object v : ((Map<?, ?>) f.get(obj)).values()) {
						scramble(v);
					}
				} else if (!t.isPrimitive() && f.get(obj) != null) {
					scramble(f.get(obj));
				}
			} catch (IllegalAccessException e) {
				throw new AssertionError(f.getName(), e);
			}
		}
	}

	private static Object read(Field f, Object obj) {
		try {
			return f.get(obj);
		} catch (IllegalAccessException e) {
			throw new AssertionError(f.getName(), e);
		}
	}
}
