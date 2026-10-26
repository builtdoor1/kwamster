package kwamster.core.config;

import kwamster.core.io.Json;
import kwamster.core.model.HitQuality;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §3 config POJO: defaults, clamping, filling, the text-alpha rule, the base-URL check and Gson. */
final class KwamsterConfigTest {
	@Test
	void clampAndFillIsIdempotent() {
		KwamsterConfig c = KwamsterConfig.defaults();
		String once = Json.PRETTY.toJson(c);
		c.clampAndFill();
		assertEquals(once, Json.PRETTY.toJson(c));
		assertEquals(Json.PRETTY.toJson(new KwamsterConfig()), Json.PRETTY.toJson(KwamsterConfig.defaults()),
				"field defaults are already within range");
	}

	@Test
	void outOfRangeValuesClamp() {
		KwamsterConfig c = new KwamsterConfig();
		c.crosshair.size = 999;
		c.crosshair.gap = -5;
		c.crosshair.thickness = 0;
		c.crosshair.outline = 9;
		c.feedback.threeBlockReach = 5.0f;
		c.feedback.indicatorDurationMs = 10;
		c.direction.enterBps = 0f;
		c.direction.smoothingMs = 9999;
		c.direction.radius = 0;
		c.direction.moveThreshold = 9999;
		c.direction.barWidth = 1;
		c.direction.barHeight = 500;
		c.direction.headClearance = -3;
		c.direction.jumpSoundVolume = 900;
		c.direction.jumpSoundPitch = 10;
		c.direction.jumpKnockbackTicks = 99;
		c.alert.cardDurationMs = 100_000;
		c.recording.shareMaxChars = 10;
		c.advanced.fightIdleMs = 1;
		c.advanced.fightMaxDistance = Float.NaN;
		c.display.chatFightLines = 7;
		c.hud.comboCounterMin = 1;
		c.clampAndFill();
		assertEquals(40, c.crosshair.size);
		assertEquals(0, c.crosshair.gap);
		assertEquals(1, c.crosshair.thickness);
		assertEquals(3, c.crosshair.outline);
		assertEquals(3.0f, c.feedback.threeBlockReach);
		assertEquals(300, c.feedback.indicatorDurationMs);
		assertEquals(0.3f, c.direction.enterBps);
		assertEquals(600, c.direction.smoothingMs);
		assertEquals(1, c.direction.radius);
		assertEquals(500, c.direction.moveThreshold);
		assertEquals(5, c.direction.barWidth);
		assertEquals(100, c.direction.barHeight);
		assertEquals(0, c.direction.headClearance);
		assertEquals(200, c.direction.jumpSoundVolume);
		assertEquals(50, c.direction.jumpSoundPitch);
		assertEquals(40, c.direction.jumpKnockbackTicks);
		assertEquals(30_000, c.alert.cardDurationMs);
		assertEquals(600, c.recording.shareMaxChars);
		assertEquals(2000, c.advanced.fightIdleMs);
		assertEquals(16.0f, c.advanced.fightMaxDistance, "NaN falls back to the default");
		assertEquals(3, c.display.chatFightLines);
		assertEquals(2, c.hud.comboCounterMin);
		c.feedback.threeBlockReach = Float.NaN;
		c.clampAndFill();
		assertEquals(2.95f, c.feedback.threeBlockReach);
	}

	@Test
	void jumpWindowsAreOrdered() {
		KwamsterConfig c = new KwamsterConfig();
		c.advanced.jumpWindowMinMs = 90;
		c.advanced.jumpWindowMaxMs = 30;
		c.advanced.jumpAttemptMs = 50;
		c.clampAndFill();
		assertTrue(c.advanced.jumpWindowMinMs <= c.advanced.jumpWindowMaxMs);
		assertTrue(c.advanced.jumpWindowMaxMs <= c.advanced.jumpAttemptMs);
		assertEquals(90, c.advanced.jumpWindowMinMs);
		assertEquals(90, c.advanced.jumpWindowMaxMs);
		assertEquals(100, c.advanced.jumpAttemptMs);
	}

	@Test
	void nullSectionsTiersAndLayoutsAreFilled() {
		KwamsterConfig c = new KwamsterConfig();
		c.general = null;
		c.display = null;
		c.hud = null;
		c.feedback = null;
		c.alert = null;
		c.crosshair = null;
		c.direction = null;
		c.recording = null;
		c.advanced = null;
		c.layouts = null;
		c.clampAndFill();
		assertNotNull(c.general);
		assertNotNull(c.advanced);
		assertEquals(7, c.feedback.tiers.size());
		assertEquals(HudElementId.values().length, c.layouts.size());

		KwamsterConfig partial = new KwamsterConfig();
		partial.feedback.tiers.remove(HitQuality.CRIT.name());
		partial.feedback.tiers.get(HitQuality.WEAK.name()).label = null;
		partial.feedback.tiers.get(HitQuality.NORMAL.name()).soundId = "  ";
		partial.layouts.remove(HudElementId.STOPLIGHT.name());
		partial.layouts.get(HudElementId.STATS_PANEL.name()).anchor = null;
		partial.layouts.get(HudElementId.COMBO_COUNTER.name()).scale = 9f;
		partial.hud.rows = null;
		partial.clampAndFill();
		assertEquals(TierStyle.defaultFor(HitQuality.CRIT), partial.tier(HitQuality.CRIT));
		assertEquals("WEAK", partial.tier(HitQuality.WEAK).label);
		assertEquals("minecraft:block.note_block.snare", partial.tier(HitQuality.NORMAL).soundId);
		assertEquals(HudLayout.defaultFor(HudElementId.STOPLIGHT), partial.layout(HudElementId.STOPLIGHT));
		assertEquals(Anchor.TOP_LEFT, partial.layout(HudElementId.STATS_PANEL).anchor);
		assertEquals(3.0f, partial.layout(HudElementId.COMBO_COUNTER).scale);
		assertNotNull(partial.hud.rows);

		KwamsterConfig bare = new KwamsterConfig();
		bare.feedback.tiers = null;
		bare.layouts = null;
		for (HitQuality q : HitQuality.values()) {
			assertNotNull(bare.tier(q), "tier() never returns null");
		}
		for (HudElementId id : HudElementId.values()) {
			assertNotNull(bare.layout(id), "layout() never returns null");
		}
	}

	@Test
	void unknownEnumStringsBecomeDefaults() {
		String json = "{\"display\":{\"mode\":\"BANANA\",\"chatMode\":\"PER_HIT\"},"
				+ "\"crosshair\":{\"shape\":\"HEXAGON\"},\"direction\":{\"style\":\"diagonal\"},\"unknownKey\":42}";
		KwamsterConfig c = Json.GSON.fromJson(json, KwamsterConfig.class);
		c.clampAndFill();
		assertEquals(KwamsterConfig.DisplayMode.BOTH, c.display.mode);
		assertEquals(KwamsterConfig.ChatMode.PER_HIT, c.display.chatMode, "a known value survives");
		assertEquals(KwamsterConfig.CrosshairShape.CROSS, c.crosshair.shape);
		assertEquals(KwamsterConfig.StoplightStyle.VERTICAL, c.direction.style);
		assertEquals(6, c.crosshair.size, "missing keys keep their field defaults");
	}

	@Test
	void gsonRoundTrip() {
		KwamsterConfig c = KwamsterConfig.defaults();
		c.crosshair.shape = KwamsterConfig.CrosshairShape.X;
		c.layouts.put(HudElementId.STATS_PANEL.name(), new HudLayout(Anchor.BOTTOM_RIGHT, -4, -9, 1.5f));
		c.feedback.tiers.get(HitQuality.SPRINT.name()).label = "W-TAP";
		String json = Json.PRETTY.toJson(c);
		KwamsterConfig back = Json.PRETTY.fromJson(json, KwamsterConfig.class);
		back.clampAndFill();
		assertEquals(json, Json.PRETTY.toJson(back));
		assertEquals(new HudLayout(Anchor.BOTTOM_RIGHT, -4, -9, 1.5f), back.layout(HudElementId.STATS_PANEL));
	}

	@Test
	void transparentTextColoursAreForcedOpaque() {
		KwamsterConfig c = new KwamsterConfig();
		c.hud.textArgb = 0x00123456;
		c.hud.labelArgb = 0x1F654321;
		c.feedback.tiers.get(HitQuality.CRIT.name()).argb = 0x00FF922B;
		c.hud.panelBgArgb = 0x00000000; // not a text colour: kept
		c.clampAndFill();
		assertEquals(0xFF123456, c.hud.textArgb);
		assertEquals(0xFF654321, c.hud.labelArgb);
		assertEquals(0xFFFF922B, c.tier(HitQuality.CRIT).argb);
		assertEquals(0x00000000, c.hud.panelBgArgb);
		KwamsterConfig edge = new KwamsterConfig();
		edge.hud.textArgb = 0x20FFFFFF;
		edge.clampAndFill();
		assertEquals(0x20FFFFFF, edge.hud.textArgb, "alpha 0x20 is allowed");
	}

	@Test
	void invalidShareBaseUrlRevertsToTheDefault() {
		for (String bad : new String[] {"https://a b/", "ftp://x", "", "nope", null}) {
			KwamsterConfig c = new KwamsterConfig();
			c.recording.shareBaseUrl = bad;
			c.clampAndFill();
			assertEquals(KwamsterConfig.DEFAULT_SHARE_BASE_URL, c.recording.shareBaseUrl, String.valueOf(bad));
		}
		KwamsterConfig ok = new KwamsterConfig();
		ok.recording.shareBaseUrl = "  https://viewer.example.org/k  ";
		ok.clampAndFill();
		assertEquals("https://viewer.example.org/k", ok.recording.shareBaseUrl);
		assertTrue(KwamsterConfig.isValidShareBaseUrl(KwamsterConfig.DEFAULT_SHARE_BASE_URL));
	}

	@Test
	void prereleaseViewerUrlMigratesToTheLowerCaseRepo() {
		KwamsterConfig c = new KwamsterConfig();
		c.recording.shareBaseUrl = " https://builtdoor1.github.io/KWAMSTER/ ";
		c.clampAndFill();
		assertEquals("https://builtdoor1.github.io/kwamster/", c.recording.shareBaseUrl);
		KwamsterConfig own = new KwamsterConfig();
		own.recording.shareBaseUrl = "https://example.github.io/KWAMSTER/";
		own.clampAndFill();
		assertEquals("https://example.github.io/KWAMSTER/", own.recording.shareBaseUrl, "a self-hosted viewer is kept");
	}

	@Test
	void legacyStoplightSwitchIsIgnored() {
		// Pre-1.0 configs stored the stoplight switch as direction.enabled (default true); it is gone, so
		// an upgraded config gets the new opt-in default instead of a HUD stoplight beside the head bars.
		KwamsterConfig c = kwamster.core.io.Json.GSON.fromJson("{\"direction\":{\"enabled\":true}}", KwamsterConfig.class);
		c.clampAndFill();
		assertEquals(false, c.direction.stoplightEnabled);
		assertEquals(true, c.direction.headBarEnabled);
	}

	@Test
	void invalidSoundIdsRevertToTheTierDefault() {
		for (String bad : new String[] {"Minecraft:Ding", "minecraft:block note", "a:b:c", "ns/x:path", "  ", null}) {
			KwamsterConfig c = new KwamsterConfig();
			c.feedback.tiers.get(HitQuality.CRIT.name()).soundId = bad;
			c.clampAndFill();
			assertEquals(TierStyle.defaultFor(HitQuality.CRIT).soundId, c.tier(HitQuality.CRIT).soundId, String.valueOf(bad));
			assertFalse(KwamsterConfig.isValidSoundId(bad), String.valueOf(bad));
		}
		// Same acceptance as Identifier.tryParse: an omitted or empty namespace means minecraft.
		for (String good : new String[] {"minecraft:block.note_block.pling", "ding", ":ding", "my_pack:hits/crit-2", "  ui.button.click  "}) {
			KwamsterConfig c = new KwamsterConfig();
			c.feedback.tiers.get(HitQuality.CRIT.name()).soundId = good;
			c.clampAndFill();
			assertEquals(good.trim(), c.tier(HitQuality.CRIT).soundId, good);
			assertTrue(KwamsterConfig.isValidSoundId(good), good);
		}
	}

	@Test
	void noRegImmunityTicksDefaultAndRange() {
		assertEquals(10, KwamsterConfig.defaults().advanced.noRegImmunityTicks, "vanilla hurt immunity");
		KwamsterConfig c = new KwamsterConfig();
		c.advanced.noRegImmunityTicks = -3;
		c.clampAndFill();
		assertEquals(0, c.advanced.noRegImmunityTicks, "0 = detection off");
		c.advanced.noRegImmunityTicks = 99;
		c.clampAndFill();
		assertEquals(20, c.advanced.noRegImmunityTicks);
	}

	@Test
	void outcomeDetectionDefaultsAndWindowRange() {
		KwamsterConfig.Advanced d = KwamsterConfig.defaults().advanced;
		assertTrue(d.inferOutcomes, "practice-server wins are detected out of the box (§5.13a)");
		assertTrue(d.readOutcomeMessages);
		assertEquals(1500, d.outcomeWindowMs);
		KwamsterConfig c = new KwamsterConfig();
		c.advanced.outcomeWindowMs = 100;
		c.clampAndFill();
		assertEquals(500, c.advanced.outcomeWindowMs);
		c.advanced.outcomeWindowMs = 60_000;
		c.clampAndFill();
		assertEquals(5000, c.advanced.outcomeWindowMs);
	}

	@Test
	void everyTierHasADistinctDefaultSound() {
		Set<String> ids = new HashSet<>();
		for (HitQuality q : HitQuality.values()) {
			TierStyle t = TierStyle.defaultFor(q);
			assertTrue(ids.add(t.soundId), "duplicate sound " + t.soundId);
			assertTrue(t.enabled);
			assertTrue(t.soundId.startsWith("minecraft:"));
		}
		assertEquals("minecraft:entity.experience_orb.pickup", TierStyle.defaultFor(HitQuality.THREE_BLOCK_CRIT).soundId);
		assertEquals("minecraft:block.note_block.pling", TierStyle.defaultFor(HitQuality.THREE_BLOCK_SPRINT).soundId);
		assertEquals(false, TierStyle.defaultFor(HitQuality.NORMAL).soundEnabled);
		assertEquals(false, TierStyle.defaultFor(HitQuality.WEAK).soundEnabled);
	}

	@Test
	void defaultsMatchTheContract() {
		KwamsterConfig c = KwamsterConfig.defaults();
		assertEquals(2.95f, c.feedback.threeBlockReach);
		assertEquals(8000, c.advanced.fightIdleMs);
		assertEquals(16.0f, c.advanced.fightMaxDistance);
		assertEquals(0, c.advanced.jumpWindowMinMs);
		assertEquals(80, c.advanced.jumpWindowMaxMs);
		assertEquals(200, c.advanced.jumpAttemptMs);
		assertEquals(1900, c.recording.shareMaxChars);
		assertEquals("https://builtdoor1.github.io/kwamster/", c.recording.shareBaseUrl);
		// Direction Indicator's defaults (§5.11a); the HUD stoplight is opt-in.
		assertEquals(true, c.direction.headBarEnabled);
		assertEquals(false, c.direction.headBarForSelf);
		assertEquals(24, c.direction.radius);
		assertEquals(20, c.direction.moveThreshold);
		assertEquals(0xEB4CD964, c.direction.forwardArgb);
		assertEquals(0xEBE03131, c.direction.backwardArgb);
		assertEquals(0xEBFFD43B, c.direction.idleArgb);
		assertEquals(0x8C000000, c.direction.backdropArgb);
		assertEquals(55, c.direction.barWidth);
		assertEquals(11, c.direction.barHeight);
		assertEquals(30, c.direction.headClearance);
		assertEquals(true, c.direction.jumpSoundEnabled);
		assertEquals(false, c.direction.jumpSoundForSelf);
		assertEquals(60, c.direction.jumpSoundVolume);
		assertEquals(160, c.direction.jumpSoundPitch);
		assertEquals(10, c.direction.jumpKnockbackTicks);
		assertEquals(false, c.direction.stoplightEnabled);
		assertEquals(new HudLayout(Anchor.TOP_LEFT, 5, 6, 1f), c.layout(HudElementId.STATS_PANEL));
		assertEquals(new HudLayout(Anchor.CENTER, 0, 26, 1f), c.layout(HudElementId.HIT_INDICATOR));
		assertEquals(new HudLayout(Anchor.TOP_CENTER, 0, 20, 1f), c.layout(HudElementId.SUMMARY_CARD));
	}
}
