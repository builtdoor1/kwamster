package kwamster.core.stats;

import kwamster.core.model.HitQuality;
import org.junit.jupiter.api.Test;

import static kwamster.core.model.HitQuality.CRIT;
import static kwamster.core.model.HitQuality.NORMAL;
import static kwamster.core.model.HitQuality.SPRINT;
import static kwamster.core.model.HitQuality.THREE_BLOCK;
import static kwamster.core.model.HitQuality.THREE_BLOCK_CRIT;
import static kwamster.core.model.HitQuality.THREE_BLOCK_SPRINT;
import static kwamster.core.model.HitQuality.WEAK;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §5.9 hit grading and the stable {@link HitQuality} codes. */
final class HitGraderTest {
	private static final double DEFAULT = 2.95;

	@Test
	void unchargedIsAlwaysWeak() {
		assertEquals(WEAK, HitGrader.grade(false, false, false, 3.0, DEFAULT, false));
		assertEquals(WEAK, HitGrader.grade(false, true, false, 3.0, DEFAULT, false));
		assertEquals(WEAK, HitGrader.grade(false, false, true, 3.0, DEFAULT, false));
	}

	@Test
	void critAroundTheDefaultThreshold() {
		assertEquals(CRIT, HitGrader.grade(true, true, false, 2.949, DEFAULT, false));
		assertEquals(THREE_BLOCK_CRIT, HitGrader.grade(true, true, false, 2.95, DEFAULT, false));
		assertEquals(THREE_BLOCK_CRIT, HitGrader.grade(true, true, false, 2.951, DEFAULT, false));
	}

	@Test
	void thresholdFromAFloatSnapshotStillIncludesExactlyTwoNinetyFive() {
		// FightManager passes record.settings.threeBlockReach, a float: (double) 2.95f = 2.9500000477.
		// A float reach of 2.95 stored in a record compares equal; the 1e-6 tolerance covers a double 2.95.
		assertEquals(THREE_BLOCK_CRIT, HitGrader.grade(true, true, false, 2.95, 2.95f, false));
		assertEquals(THREE_BLOCK_CRIT, HitGrader.grade(true, true, false, 2.95f, 2.95f, false));
		assertEquals(CRIT, HitGrader.grade(true, true, false, 2.949, 2.95f, false));
	}

	@Test
	void sprintTiers() {
		assertEquals(SPRINT, HitGrader.grade(true, false, true, 2.94, DEFAULT, false));
		assertEquals(THREE_BLOCK_SPRINT, HitGrader.grade(true, false, true, 2.95, DEFAULT, false));
		assertEquals(THREE_BLOCK_SPRINT, HitGrader.grade(true, false, true, 2.99, DEFAULT, false));
	}

	@Test
	void plainAndSweepHits() {
		// A sweep is a plain charged hit (no crit, no sprint) as far as grading goes.
		assertEquals(NORMAL, HitGrader.grade(true, false, false, 2.10, DEFAULT, false));
		assertEquals(NORMAL, HitGrader.grade(true, false, false, 2.949, DEFAULT, false));
		assertEquals(THREE_BLOCK, HitGrader.grade(true, false, false, 2.95, DEFAULT, false));
	}

	@Test
	void customThresholdIsRespected() {
		assertEquals(CRIT, HitGrader.grade(true, true, false, 2.79, 2.80, false));
		assertEquals(THREE_BLOCK_CRIT, HitGrader.grade(true, true, false, 2.80, 2.80, false));
		assertEquals(THREE_BLOCK, HitGrader.grade(true, false, false, 2.85, 2.80, false));
		assertEquals(NORMAL, HitGrader.grade(true, false, false, 2.85, 2.95, false));
	}

	@Test
	void customAttackRangeNeverGradesAsThreeBlock() {
		assertEquals(CRIT, HitGrader.grade(true, true, false, 3.2, DEFAULT, true));
		assertEquals(SPRINT, HitGrader.grade(true, false, true, 3.2, DEFAULT, true));
		assertEquals(NORMAL, HitGrader.grade(true, false, false, 3.2, DEFAULT, true));
		assertEquals(WEAK, HitGrader.grade(false, false, false, 3.2, DEFAULT, true));
	}

	@Test
	void threeBlockOnlyForCodesZeroToTwo() {
		for (HitQuality q : HitQuality.values()) {
			assertEquals(q.code() <= 2, q.threeBlock(), q.name());
		}
		assertTrue(THREE_BLOCK_CRIT.threeBlock());
		assertTrue(THREE_BLOCK.threeBlock());
		assertFalse(CRIT.threeBlock());
	}

	@Test
	void codesAreStableInDeclaredOrder() {
		HitQuality[] expected = {THREE_BLOCK_CRIT, THREE_BLOCK_SPRINT, THREE_BLOCK, CRIT, SPRINT, NORMAL, WEAK};
		assertEquals(expected.length, HitQuality.values().length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(i, expected[i].code());
			assertEquals(expected[i], HitQuality.byCode(i));
		}
		assertThrows(IllegalArgumentException.class, () -> HitQuality.byCode(7));
		assertThrows(IllegalArgumentException.class, () -> HitQuality.byCode(-1));
	}
}
