package kwamster.configui;

import kwamster.core.config.KwamsterConfig.SoundChannel;
import kwamster.core.config.TierStyle;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The settings screen's Minecraft-free checks: tier labels, the text-alpha rule and the "▶ Test" volume warning. */
final class EntryChecksTest {
	@Test
	void labelMustBeNonBlankAndShortOnceTrimmed() {
		assertEquals("The label cannot be empty", EntryChecks.labelProblem(null));
		assertEquals("The label cannot be empty", EntryChecks.labelProblem(""));
		assertEquals("The label cannot be empty", EntryChecks.labelProblem("   "));
		assertNull(EntryChecks.labelProblem("CRIT"));
		assertNull(EntryChecks.labelProblem("  3-BLOCK CRIT  "));

		String max = "x".repeat(TierStyle.MAX_LABEL_LENGTH);
		assertNull(EntryChecks.labelProblem(max));
		assertNull(EntryChecks.labelProblem(" " + max + " "), "surrounding spaces are trimmed before counting");
		assertEquals("At most " + TierStyle.MAX_LABEL_LENGTH + " characters (now " + (TierStyle.MAX_LABEL_LENGTH + 1) + ")",
				EntryChecks.labelProblem(max + "y"));
	}

	@Test
	void textColoursNeedAlphaOfAtLeast0x20() {
		assertTrue(EntryChecks.textAlphaOk(0xFFF1F3F5));
		assertTrue(EntryChecks.textAlphaOk(0x20FFFFFF));
		assertFalse(EntryChecks.textAlphaOk(0x1FFFFFFF));
		assertFalse(EntryChecks.textAlphaOk(0x00FFFFFF));
		assertFalse(EntryChecks.textAlphaOk(0));
	}

	@Test
	void audiblePreviewHasNoWarning() {
		for (SoundChannel c : SoundChannel.values()) {
			assertNull(EntryChecks.previewVolumeProblem(80, c, 1f, 1f), c.name());
			assertNull(EntryChecks.previewVolumeProblem(1, c, 0.01f, 0.01f), c.name());
		}
	}

	@Test
	void tierVolumeZeroIsReportedFirst() {
		assertEquals("Volume is 0%", EntryChecks.previewVolumeProblem(0, SoundChannel.PLAYERS, 0f, 0f));
		assertEquals("Volume is 0%", EntryChecks.previewVolumeProblem(0, SoundChannel.MASTER, 1f, 1f));
	}

	@Test
	void masterVolumeZeroSilencesEveryCategory() {
		for (SoundChannel c : SoundChannel.values()) {
			assertEquals("Master Volume is 0%", EntryChecks.previewVolumeProblem(80, c, 0f, 1f), c.name());
		}
		assertEquals("Master Volume is 0%", EntryChecks.previewVolumeProblem(80, SoundChannel.UI, Float.NaN, 1f), "NaN counts as 0");
	}

	@Test
	void chosenCategoryZeroIsNamedAsInVanilla() {
		assertEquals("Players volume is 0%", EntryChecks.previewVolumeProblem(80, SoundChannel.PLAYERS, 1f, 0f));
		assertEquals("UI volume is 0%", EntryChecks.previewVolumeProblem(80, SoundChannel.UI, 1f, 0f));
		assertEquals("Players volume is 0%", EntryChecks.previewVolumeProblem(80, null, 1f, 0f), "null plays on Players, as in FEEDBACK");
		// The Master category is the master slider itself; a second value is not consulted.
		assertNull(EntryChecks.previewVolumeProblem(80, SoundChannel.MASTER, 1f, 0f));
	}

	@Test
	void everyChannelHasADistinctVanillaName() {
		Set<String> names = new HashSet<>();
		for (SoundChannel c : SoundChannel.values()) {
			assertTrue(names.add(EntryChecks.vanillaName(c)), c.name());
		}
		assertEquals("Master Volume", EntryChecks.vanillaName(SoundChannel.MASTER));
	}
}
