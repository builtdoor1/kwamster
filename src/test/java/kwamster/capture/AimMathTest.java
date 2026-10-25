package kwamster.capture;

import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.3 aim geometry. The target stands at the origin (0.6 × 1.8 box, or 1.5 tall when sneaking); the
 * attacker's eye is 3 blocks away on −z at eye height 1.62, looking toward +z (yaw 0), so the right
 * axis is −x.
 */
final class AimMathTest {
	private static final double EPS = 1e-9;
	private static final AimMath.Box STANDING = new AimMath.Box(-0.3, 0.0, -0.3, 0.3, 1.8, 0.3);
	private static final AimMath.Box SNEAKING = new AimMath.Box(-0.3, 0.0, -0.3, 0.3, 1.5, 0.3);
	private static final Pos EYE = new Pos(0.0, 1.62, -3.0);

	/** The landed sample for a ray from {@link #EYE} through {@code aimAt}, using vanilla's entry point. */
	private static AimSample landedAt(AimMath.Box box, Pos aimAt) {
		return landedFrom(EYE, box, aimAt);
	}

	/** The landed sample for a ray from {@code eye} through {@code aimAt}. */
	private static AimSample landedFrom(Pos eye, AimMath.Box box, Pos aimAt) {
		Pos look = new Pos(aimAt.x() - eye.x(), aimAt.y() - eye.y(), aimAt.z() - eye.z());
		double d = AimMath.rayEntryDistance(box, eye, look);
		double len = Math.sqrt(look.x() * look.x() + look.y() * look.y() + look.z() * look.z());
		Pos hit = new Pos(eye.x() + look.x() / len * d, eye.y() + look.y() / len * d, eye.z() + look.z() / len * d);
		return AimMath.landed(hit, box, eye, look, 0f);
	}

	@Test
	void chestIsCenter() {
		// Entry point (0, 0.95, -0.3): v = (0.95 - 0.9) / 0.9 = 0.0556, h = 0.
		AimSample s = landedAt(STANDING, new Pos(0, 0.95, -0.3));
		assertEquals(0.0, s.h(), 1e-9);
		assertEquals(0.05 / 0.9, s.v(), 1e-9);
		assertEquals(AimZone.CENTER, s.zone());
		assertFalse(s.eyeInside());
	}

	@Test
	void headIsHighAndFeetAreLow() {
		AimSample head = landedAt(STANDING, new Pos(0, 1.7, -0.3));
		assertEquals(0.8 / 0.9, head.v(), 1e-9);
		assertEquals(AimZone.HIGH, head.zone());
		AimSample feet = landedAt(STANDING, new Pos(0, 0.1, -0.3));
		assertEquals(-0.8 / 0.9, feet.v(), 1e-9);
		assertEquals(AimZone.LOW, feet.zone());
	}

	@Test
	void grazingTheRightSideIsEdgeWithPositiveH() {
		// An attacker standing 0.25 to the -x side looks straight along +z, so the right axis is -x and the
		// entry point (-0.25, 0.95, -0.3) is 0.25 right of centre: h = 0.25 / 0.3.
		AimSample s = landedFrom(new Pos(-0.25, 1.62, -3.0), STANDING, new Pos(-0.25, 0.95, -0.3));
		assertEquals(0.25 / 0.3, s.h(), 1e-9);
		assertTrue(s.h() > 0);
		assertEquals(AimZone.EDGE, s.zone());
		AimSample left = landedFrom(new Pos(0.25, 1.62, -3.0), STANDING, new Pos(0.25, 0.95, -0.3));
		assertEquals(-0.25 / 0.3, left.h(), 1e-9);
		assertEquals(AimZone.EDGE, left.zone());
		// From the centred eye the look itself turns: f = normalize(-0.25, 2.7), right = (-f.z, f.x),
		// hw = 0.3·(|right.x| + |right.z|), h = ((P - C)·right) / hw = 0.27659 / 0.32638 = 0.8475.
		AimSample angled = landedAt(STANDING, new Pos(-0.25, 0.95, -0.3));
		double len = Math.hypot(-0.25, 2.7);
		double rx = -2.7 / len;
		double rz = -0.25 / len;
		double expected = (-0.25 * rx + -0.3 * rz) / (0.3 * (Math.abs(rx) + Math.abs(rz)));
		assertEquals(expected, angled.h(), 1e-9);
		assertEquals(0.8475, angled.h(), 1e-4);
		assertEquals(AimZone.EDGE, angled.zone());
	}

	@Test
	void sneakingNormalisesWithTheRealHeight() {
		Pos aim = new Pos(0, 1.05, -0.3);
		AimSample standing = landedAt(STANDING, aim);
		AimSample sneaking = landedAt(SNEAKING, aim);
		// Same world-space entry point: standing v = 0.15/0.9 = 0.167 (CENTER), sneaking v = 0.30/0.75 = 0.4 (HIGH).
		assertEquals(0.15 / 0.9, standing.v(), 1e-9);
		assertEquals(AimZone.CENTER, standing.zone());
		assertEquals(0.30 / 0.75, sneaking.v(), 1e-9);
		assertEquals(AimZone.HIGH, sneaking.zone());
	}

	@Test
	void lookingStraightDownUsesTheYaw() {
		// Eye above the target, looking straight down at the top face point (0.1, 1.8, -0.2).
		Pos eye = new Pos(0.1, 3.0, -0.2);
		Pos look = new Pos(0, -1, 0);
		Pos hit = new Pos(0.1, 1.8, -0.2);
		// yaw 90: facing -x, right = (0, 0, -1); P - C = (0.1, 0.9, -0.2) → h = 0.2 / 0.3.
		AimSample west = AimMath.landed(hit, STANDING, eye, look, 90f);
		assertEquals(0.2 / 0.3, west.h(), 1e-6);
		assertEquals(1.0, west.v(), EPS);
		assertEquals(AimZone.HIGH, west.zone());
		// yaw 0: facing +z, right = (-1, 0, 0) → h = -0.1 / 0.3.
		AimSample south = AimMath.landed(hit, STANDING, eye, look, 0f);
		assertEquals(-0.1 / 0.3, south.h(), 1e-6);
	}

	@Test
	void eyeInsideTheBoxIsCenter() {
		Pos eye = new Pos(0.0, 1.0, 0.0);
		AimSample landed = AimMath.landed(new Pos(0.1, 1.2, 0.3), STANDING, eye, new Pos(0, 0, 1), 0f);
		assertEquals(0.0, landed.h(), EPS);
		assertEquals(0.0, landed.v(), EPS);
		assertEquals(AimZone.CENTER, landed.zone());
		assertTrue(landed.eyeInside());
		AimSample missed = AimMath.missed(STANDING, eye, new Pos(0, 0, 1), 0f);
		assertTrue(missed.eyeInside());
		assertNull(missed.zone());
		// Within the 1e-4 inflation counts as inside too.
		assertTrue(AimMath.landed(new Pos(0, 1, -0.3), STANDING, new Pos(0, 1.0, -0.30005), new Pos(0, 0, 1), 0f).eyeInside());
	}

	@Test
	void missOffsetsMayExceedOne() {
		// Look (1, -0.72, 3): the closest point of the ray to the centre C = (0, 0.9, 0) is
		// P = (0.90493, 0.96845, -0.28522). Horizontal look f = (1, 3)/√10, right = (-3, 1)/√10, so
		// (P - C)·right = -0.94868 and hw = 0.3·4/√10 = 0.37947: h = -2.5 (left of the box, beyond its edge).
		AimSample s = AimMath.missed(STANDING, EYE, new Pos(1.0, -0.72, 3.0), 0f);
		assertNull(s.zone());
		assertEquals(-2.5, s.h(), 1e-4);
		assertTrue(s.h() < -1.0, "h = " + s.h());
		assertFalse(s.eyeInside());
	}

	@Test
	void outcomeRecomputesTheZone() {
		AimSample miss = AimMath.missed(STANDING, EYE, new Pos(0, -0.67, 2.7), 0f);
		assertNull(miss.zone());
		assertEquals(AimZone.CENTER, AimMath.withOutcome(miss, true).zone());
		assertNull(AimMath.withOutcome(AimMath.withOutcome(miss, true), false).zone());
	}

	@Test
	void rayEntryDistance() {
		assertEquals(2.7, AimMath.rayEntryDistance(STANDING, new Pos(0, 1, -3), new Pos(0, 0, 1)), 1e-12);
		assertTrue(Double.isNaN(AimMath.rayEntryDistance(STANDING, new Pos(0, 1, -3), new Pos(0, 0, -1))));
		assertTrue(Double.isNaN(AimMath.rayEntryDistance(STANDING, new Pos(2, 1, -3), new Pos(0, 0, 1))));
		assertEquals(0.0, AimMath.rayEntryDistance(STANDING, new Pos(0, 1, 0), new Pos(0, 0, 1)), 0.0);
	}
}
