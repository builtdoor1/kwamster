package kwamster.direction;

import kwamster.direction.HeadBarMotion.Heading;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.11a head-bar motion, matching Direction Indicator: heading along the look yaw with 0.45 smoothing
 * and a 4-block teleport cut, jumps confirmed by a 0.1-block rise within 4 airborne ticks, a 6-tick
 * cooldown, and the knockback window armed by a fresh hit on a grounded player and cleared on landing.
 * Yaw 0 faces +Z, yaw 90 faces −X (vanilla). One {@code step} is one client tick.
 */
final class HeadBarMotionTest {
	/** Direction Indicator's default threshold, 20/1000 blocks per tick. */
	private static final double THRESHOLD = 0.020;
	private static final double H = 1.8;
	private static final int KB_WINDOW = 10;

	/** A player walking along +Z while looking along +Z reads forward, then backward when reversed. */
	@Test
	void walkingAlongTheLookYawIsForwardAndBackpedallingIsBackward() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		double z = 0;
		for (int i = 0; i < 10; i++) {
			z += 0.216; // walking speed
			m.step(0, 64, z, 0f, true, 0, 0, H, KB_WINDOW);
		}
		assertEquals(Heading.FORWARD, m.heading(THRESHOLD));
		assertEquals(0.216, m.forwardSpeed(), 0.001, "converges on the walking speed");
		for (int i = 0; i < 10; i++) {
			z -= 0.216;
			m.step(0, 64, z, 0f, true, 0, 0, H, KB_WINDOW);
		}
		assertEquals(Heading.BACKWARD, m.heading(THRESHOLD));
	}

	/** The projection follows the yaw: facing −X (yaw 90) and moving −X is forward. */
	@Test
	void headingIsRelativeToTheYawNotTheWorldAxis() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		double x = 0;
		for (int i = 0; i < 8; i++) {
			x -= 0.28;
			m.step(x, 64, 0, 90f, true, 0, 0, H, KB_WINDOW);
		}
		assertEquals(Heading.FORWARD, m.heading(THRESHOLD));
	}

	/** Strafing is sideways to the look, so it reads idle (yellow). */
	@Test
	void strafingReadsIdle() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		double x = 0;
		for (int i = 0; i < 10; i++) {
			x += 0.216;
			m.step(x, 64, 0, 0f, true, 0, 0, H, KB_WINDOW);
		}
		assertEquals(Heading.IDLE, m.heading(THRESHOLD));
	}

	/** One smoothed step: 0.45 of the first sample, then 0.45 of the remaining gap. */
	@Test
	void smoothingIsExponentialWithWeight045() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0.2, 0f, true, 0, 0, H, KB_WINDOW);
		assertEquals(0.09, m.forwardSpeed(), 1e-12);
		m.step(0, 64, 0.4, 0f, true, 0, 0, H, KB_WINDOW);
		assertEquals(0.09 + (0.2 - 0.09) * 0.45, m.forwardSpeed(), 1e-12);
	}

	/** A step longer than 4 blocks is a teleport and counts as standing still. */
	@Test
	void teleportsAreDiscarded() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 4.01, 0f, true, 0, 0, H, KB_WINDOW);
		assertEquals(0.0, m.forwardSpeed(), 1e-12);
		assertEquals(Heading.IDLE, m.heading(THRESHOLD));
		m.step(0, 64, 4.21, 0f, true, 0, 0, H, KB_WINDOW);
		assertEquals(0.2 * HeadBarMotion.SMOOTHING, m.forwardSpeed(), 1e-12, "movement after the teleport counts again");
	}

	/**
	 * A remote jump arrives interpolated: the tick onGround flips shows only ~0.14 of rise, which is
	 * enough here; a slower interpolated rise still confirms within the 4-tick window.
	 */
	@Test
	void interpolatedJumpIsConfirmedOnceTheRiseShows() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0, 0f, true, 0, 0, H, KB_WINDOW);
		assertFalse(m.step(0, 64.04, 0, 0f, false, 0, 0, H, KB_WINDOW), "left the ground, 0.04 up: not yet");
		assertFalse(m.step(0, 64.09, 0, 0f, false, 0, 0, H, KB_WINDOW), "0.09 up: not yet");
		assertTrue(m.step(0, 64.15, 0, 0f, false, 0, 0, H, KB_WINDOW), "0.15 up while airborne: a jump");
		assertFalse(m.step(0, 64.3, 0, 0f, false, 0, 0, H, KB_WINDOW), "fires once per jump");
	}

	/** Walking off a ledge only loses height, so it never confirms. */
	@Test
	void steppingOffALedgeIsNotAJump() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0.2, 0f, true, 0, 0, H, KB_WINDOW);
		double y = 64;
		boolean any = false;
		for (int i = 0; i < 10; i++) {
			y -= 0.08 * (i + 1);
			any |= m.step(0, y, 0.2 * (i + 2), 0f, false, 0, 0, H, KB_WINDOW);
		}
		assertFalse(any);
	}

	/** A candidate that does not show its rise within 4 airborne ticks is dropped. */
	@Test
	void riseAfterTheConfirmWindowDoesNotCount() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		boolean any = false;
		for (int i = 0; i < 4; i++) {
			any |= m.step(0, 64.0, 0, 0f, false, 0, 0, H, KB_WINDOW);
		}
		any |= m.step(0, 64.5, 0, 0f, false, 0, 0, H, KB_WINDOW);
		assertFalse(any);
	}

	/** A fresh hit on a grounded player arms the window; the launch that follows is knockback. */
	@Test
	void knockbackLaunchAfterAGroundedHitIsSuppressed() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0, 0f, true, 10, 10, H, KB_WINDOW); // hurtTime 0 -> 10 while grounded
		assertEquals(KB_WINDOW, m.knockbackTicks());
		assertFalse(m.step(0, 64.2, 0, 0f, false, 9, 10, H, KB_WINDOW), "launched by the hit");
	}

	/** Landing clears the window, so a real jump right after a knockback flight still counts. */
	@Test
	void landingClearsTheKnockbackWindow() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0, 0f, true, 10, 10, H, KB_WINDOW);
		m.step(0, 64.2, 0, 0f, false, 9, 10, H, KB_WINDOW);
		m.step(0, 64.1, 0, 0f, false, 8, 10, H, KB_WINDOW);
		m.step(0, 64.0, 0, 0f, true, 7, 10, H, KB_WINDOW); // landed
		assertEquals(0, m.knockbackTicks());
		// The 6-tick cooldown from the suppressed launch has run out by now.
		for (int i = 0; i < 6; i++) {
			m.step(0, 64.0, 0, 0f, true, 0, 10, H, KB_WINDOW);
		}
		assertFalse(m.step(0, 64.05, 0, 0f, false, 0, 10, H, KB_WINDOW));
		assertTrue(m.step(0, 64.2, 0, 0f, false, 0, 10, H, KB_WINDOW), "their own jump after landing");
	}

	/** A hit on an airborne player cannot launch them, so it arms nothing. */
	@Test
	void hitWhileAirborneArmsNothing() {
		HeadBarMotion m = HeadBarMotion.seed(0, 65, 0, H, false, 0);
		m.step(0, 64.8, 0, 0f, false, 10, 10, H, KB_WINDOW);
		assertEquals(0, m.knockbackTicks());
	}

	/** 1.8-style servers pin hurtTime at hurtDuration; that still counts as a fresh hit. */
	@Test
	void pinnedHurtTimeCountsAsFresh() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 10);
		m.step(0, 64, 0, 0f, true, 10, 10, H, KB_WINDOW); // no rising edge, but pinned at the duration
		assertEquals(KB_WINDOW, m.knockbackTicks());
	}

	/** A never-hit player has hurtDuration 0; hurtTime 0 must not read as a hit. */
	@Test
	void neverHitPlayerIsNotFresh() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0, 0f, true, 0, 0, H, KB_WINDOW);
		assertEquals(0, m.knockbackTicks());
	}

	/** Window 0 turns the knockback filter off: the launch counts as a jump. */
	@Test
	void zeroWindowDisablesTheFilter() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		m.step(0, 64, 0, 0f, true, 10, 10, H, 0);
		assertTrue(m.step(0, 64.2, 0, 0f, false, 9, 10, H, 0));
	}

	/** Two jumps closer than the 6-tick cooldown: only the first fires. */
	@Test
	void cooldownEatsADoubleTrigger() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, H, true, 0);
		assertTrue(m.step(0, 64.2, 0, 0f, false, 0, 0, H, KB_WINDOW));
		m.step(0, 64.0, 0, 0f, true, 0, 0, H, KB_WINDOW);
		assertFalse(m.step(0, 64.2, 0, 0f, false, 0, 0, H, KB_WINDOW), "2 ticks later: inside the cooldown");
	}

	/** A pose change interpolates the height across the tick instead of snapping. */
	@Test
	void heightInterpolatesAcrossTheTick() {
		HeadBarMotion m = HeadBarMotion.seed(0, 64, 0, 1.8, true, 0);
		m.step(0, 64, 0, 0f, true, 0, 0, 1.8, KB_WINDOW);
		assertEquals(1.65, m.interpolatedHeight(0.5f, 1.5), 1e-9, "sneak swap halfway through the frame");
		assertEquals(1.8, m.interpolatedHeight(0f, 1.5), 1e-9);
	}

	/** The colour rule, including Direction Indicator's threshold-0 and NaN edge cases. */
	@Test
	void classifyMatchesDirectionIndicator() {
		assertEquals(Heading.IDLE, HeadBarMotion.classify(0.019, THRESHOLD));
		assertEquals(Heading.FORWARD, HeadBarMotion.classify(0.020, THRESHOLD));
		assertEquals(Heading.BACKWARD, HeadBarMotion.classify(-0.3, THRESHOLD));
		assertEquals(Heading.BACKWARD, HeadBarMotion.classify(0.0, 0.0), "threshold 0: never idle (as in Direction Indicator)");
		assertEquals(Heading.IDLE, HeadBarMotion.classify(Double.NaN, THRESHOLD));
	}
}
