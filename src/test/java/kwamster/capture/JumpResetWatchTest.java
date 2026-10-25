package kwamster.capture;

import kwamster.SampleFights;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.model.PlayerRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.1 jump-reset pairing on client ticks. Every input is a (tick, nanos) pair. Default windows:
 * success 0–80 game ms (first or second tick after the hit), attempt 200 ms (up to five ticks).
 * Two jump inputs: {@code onJump} (a real jumpFromGround, mid-tick) and {@code onJumpPress} (a rising edge
 * of the jump input, sampled at the end of the tick).
 */
final class JumpResetWatchTest {
	private static final int MIN = 0;
	private static final int MAX = 80;
	private static final int ATTEMPT = 200;
	private static final long N = 1000;
	private static final PlayerRef KWAMIX = SampleFights.KWAMIX;

	private JumpResetWatch watch;

	@BeforeEach
	void setUp() {
		this.watch = new JumpResetWatch();
	}

	/** Wall time of a tick start (50 ms ticks) plus an intra-frame offset. */
	private static long nanos(long tick, long offsetMs) {
		return tick * 50_000_000L + offsetMs * 1_000_000L;
	}

	private JumpResetEvent hit(long tick, boolean onGround) {
		return hit(tick, onGround, false);
	}

	/** A hit; {@code jumpHeld} = the jump input was still down at the end of the last tick. */
	private JumpResetEvent hit(long tick, boolean onGround, boolean jumpHeld) {
		return this.watch.onHitTaken(tick, nanos(tick, 30), KWAMIX, onGround, jumpHeld, MIN, MAX, ATTEMPT);
	}

	/** The end of tick {@code tick} (after its jump and its jump-input sample). */
	private JumpResetEvent tickEnd(long tick) {
		return this.watch.onTickEnd(tick);
	}

	private JumpResetEvent jump(long tick) {
		return this.watch.onJump(tick, nanos(tick, 5), MIN, MAX, ATTEMPT);
	}

	/** A rising edge of the jump input, sampled at the end of the tick (after any jumpFromGround in it). */
	private JumpResetEvent press(long tick) {
		return this.watch.onJumpPress(tick, nanos(tick, 8), MIN, MAX, ATTEMPT);
	}

	// ---- jump-input presses (late attempts jumpFromGround cannot see) ------------------

	@Test
	void latePressInTheAirIsALateAttempt() {
		// Knockback lifted you on the first tick after the hit, so a press 3 ticks later calls no jumpFromGround.
		hit(N, true);
		JumpResetEvent e = press(N + 3);
		assertNotNull(e, "a late press is an attempt, not nothing");
		assertFalse(e.success());
		assertEquals(3, e.deltaTicks());
		assertEquals(100, e.deltaMs());
		assertEquals(nanos(N + 3, 8), e.nanos());
	}

	@Test
	void pressOnTheSecondTickIsInsideTheSuccessWindow() {
		hit(N, true);
		JumpResetEvent e = press(N + 2);
		assertNotNull(e);
		assertTrue(e.success(), "0–80 ms covers the second tick after the hit");
		assertEquals(50, e.deltaMs());
	}

	@Test
	void realJumpInsideTheWindowDecidesAndItsOwnPressIsIgnored() {
		hit(N, true);
		JumpResetEvent real = jump(N + 1);
		assertNotNull(real);
		assertTrue(real.success());
		assertNull(press(N + 1), "the press that caused this tick's jump is the same action");
		assertNull(press(N + 3), "the hit is already spent");
	}

	@Test
	void pressInTheSameTickAsARealJumpWithoutAHitIsIgnored() {
		assertNull(jump(N - 2));
		assertNull(press(N - 2));
		JumpResetEvent e = hit(N, false);
		assertNotNull(e, "the real jump is still the early attempt");
		assertEquals(-2, e.deltaTicks());
		assertEquals(nanos(N - 2, 5), e.nanos(), "timed by the real jump, not the press");
	}

	@Test
	void pressesPairOnlyOnce() {
		hit(N, true);
		assertNotNull(press(N + 3));
		assertNull(press(N + 5));
	}

	@Test
	void pressPastTheAttemptWindowIsNothing() {
		hit(N, true);
		assertNull(press(N + 6));
	}

	@Test
	void airPressReleasedBeforeAGroundedHitIsEarly() {
		// Pressed while still in the air (no jump happened), let go, landed, then got hit on the ground.
		assertNull(press(N - 2));
		JumpResetEvent e = hit(N, true, false);
		assertNotNull(e);
		assertFalse(e.success());
		assertEquals(-2, e.deltaTicks());
		assertEquals(-150, e.deltaMs());
		assertEquals(nanos(N - 2, 8), e.nanos(), "timed by the press");
		assertNull(jump(N + 1), "the hit is spent");
		assertNull(tickEnd(N + 1));
	}

	@Test
	void airPressHeldThroughLandingJumpsOnTheFirstTickAndIsASuccess() {
		// Pressed in the air at N-2 and held: vanilla jumps on the first tick on the ground, N+1.
		assertNull(press(N - 2));
		assertNull(hit(N, true, true), "not early yet: the held key is about to jump");
		assertNull(tickEnd(N), "the hit's own tick ended before the hit");
		JumpResetEvent e = jump(N + 1);
		assertNotNull(e);
		assertTrue(e.success(), "the textbook 0 ms reset");
		assertEquals(1, e.deltaTicks());
		assertEquals(0, e.deltaMs());
		assertNull(tickEnd(N + 1), "decided by the jump; the press is not also early");
		assertNull(hit(N + 12, true, true), "the press is spent");
	}

	@Test
	void pressOnTheLandingTickHeldThroughTheHitIsASuccess() {
		// The press came in the tick you landed (the jump check runs before travel lands you): a bare press at N.
		assertNull(press(N));
		assertNull(hit(N, true, true));
		JumpResetEvent e = jump(N + 1);
		assertNotNull(e);
		assertTrue(e.success());
	}

	@Test
	void heldPressWithNoJumpOnTheNextTickBecomesEarlyThen() {
		// Held at the hit, let go before the next tick's aiStep: no jump on N+1.
		assertNull(press(N - 2));
		assertNull(hit(N, true, true));
		JumpResetEvent e = tickEnd(N + 1);
		assertNotNull(e, "the deferred early attempt");
		assertFalse(e.success());
		assertEquals(-2, e.deltaTicks());
		assertEquals(-150, e.deltaMs());
		assertEquals(nanos(N - 2, 8), e.nanos());
		assertEquals(nanos(N, 30), e.hitNanos());
		assertNull(tickEnd(N + 2), "emitted once");
		assertNull(press(N + 3), "the hit is spent, as for an immediate early attempt");
	}

	@Test
	void heldPressOutsideTheEarlyWindowJustOpensTheWindow() {
		// Held for longer than the attempt window in the air: never early, and the landing jump decides.
		assertNull(press(N - 6));
		assertNull(hit(N, true, true));
		assertNull(tickEnd(N + 1), "nothing remembered");
		this.watch.reset();
		assertNull(press(N - 6));
		assertNull(hit(N, true, true));
		assertTrue(jump(N + 1).success());
	}

	@Test
	void aNewerGroundedHitReplacesTheWaitingOne() {
		assertNull(press(N - 1));
		assertNull(hit(N, true, true));
		assertNull(hit(N, true, true), "a second hit in the same frame takes over the window");
		JumpResetEvent e = jump(N + 1);
		assertNotNull(e);
		assertEquals(nanos(N, 30), e.hitNanos());
		assertTrue(e.success());
	}

	@Test
	void realEarlyJumpSurvivesALaterAirPressBeforeAnAirborneHit() {
		// Mashing: real jump at N-3, let go, pressed again in the air at N-1, hit at N while still airborne.
		assertNull(jump(N - 3));
		assertNull(press(N - 1));
		JumpResetEvent e = hit(N, false);
		assertNotNull(e, "the real jump is still the early attempt");
		assertFalse(e.success());
		assertEquals(-3, e.deltaTicks());
		assertEquals(-200, e.deltaMs());
		assertEquals(nanos(N - 3, 5), e.nanos());
		assertNull(hit(N + 12, true), "both are spent");
	}

	@Test
	void anAirPressAfterAnOldRealJumpIsStillNothingBeforeAnAirborneHit() {
		assertNull(jump(N - 8));
		assertNull(press(N - 1));
		assertNull(hit(N, false), "the real jump is outside the early window");
	}

	@Test
	void airPressBeforeAnAirborneHitIsNoAttemptAndIsSpent() {
		assertNull(press(N - 1));
		assertNull(hit(N, false), "you were in the air throughout: no reset opportunity");
		assertNull(hit(N + 1, true), "the press belonged to the airborne exchange, not to the next hit");
		assertNotNull(jump(N + 2), "the grounded hit's window is open as usual");
	}

	@Test
	void realEarlyJumpStillCountsBeforeAnAirborneHit() {
		assertNull(jump(N - 1));
		JumpResetEvent e = hit(N, false);
		assertNotNull(e, "jumping too early is what left you airborne");
		assertEquals(-1, e.deltaTicks());
	}

	@Test
	void holdingJumpThroughTheHitResetsOnTheFirstTick() {
		// Jump held since long before: no new rising edge, but jumpFromGround fires again on the ground.
		assertNull(jump(N - 20));
		assertNull(press(N - 20));
		assertNull(hit(N, true));
		JumpResetEvent e = jump(N + 1);
		assertNotNull(e);
		assertTrue(e.success());
	}

	@Test
	void firstTickAfterTheHitIsASuccessAtZeroMs() {
		assertNull(hit(N, true));
		JumpResetEvent e = jump(N + 1);
		assertNotNull(e);
		assertTrue(e.success());
		assertEquals(1, e.deltaTicks());
		assertEquals(0, e.deltaMs());
		assertEquals(nanos(N + 1, 5), e.nanos());
		assertEquals(nanos(N, 30), e.hitNanos());
		assertEquals(25, e.wallMs());
		assertEquals(KWAMIX, e.attacker());
	}

	@Test
	void secondTickIsStillASuccess() {
		hit(N, true);
		JumpResetEvent e = jump(N + 2);
		assertTrue(e.success());
		assertEquals(50, e.deltaMs());
	}

	@Test
	void thirdTickIsLate() {
		hit(N, true);
		JumpResetEvent e = jump(N + 3);
		assertFalse(e.success());
		assertEquals(3, e.deltaTicks());
		assertEquals(100, e.deltaMs());
	}

	@Test
	void jumpInTheSameTickBeforeTheHitIsEarly() {
		assertNull(jump(N));
		JumpResetEvent e = hit(N, true);
		assertNotNull(e);
		assertFalse(e.success());
		assertEquals(0, e.deltaTicks());
		assertEquals(-50, e.deltaMs());
		assertEquals(nanos(N, 5), e.nanos(), "nanos is the jump time");
		assertEquals(nanos(N, 30), e.hitNanos());
		// Both are spent: a jump right after does not pair again.
		assertNull(jump(N + 1));
	}

	@Test
	void earlyWindowEdges() {
		jump(N - 3); // deltaMs = 50·(−3 − 1) = −200: still an attempt
		assertNotNull(hit(N, true));
		this.watch.reset();
		jump(N - 4); // −250: outside the attempt window
		assertNull(hit(N, true));
	}

	@Test
	void sixTicksLaterIsNoAttempt() {
		hit(N, true);
		assertNull(jump(N + 6), "250 ms game time is outside the 200 ms attempt window");
		this.watch.reset();
		hit(N, true);
		JumpResetEvent fifth = jump(N + 5);
		assertNotNull(fifth, "200 ms is the last attempt tick");
		assertEquals(200, fifth.deltaMs());
		assertFalse(fifth.success());
	}

	@Test
	void pairsWithTheLatestGroundedHit() {
		hit(N, true);
		hit(N + 12, true);
		JumpResetEvent e = jump(N + 13);
		assertNotNull(e);
		assertEquals(nanos(N + 12, 30), e.hitNanos());
		assertEquals(1, e.deltaTicks());
		assertTrue(e.success());
	}

	@Test
	void jumpWithoutAHitDoesNothing() {
		assertNull(jump(N));
		assertNull(jump(N + 10));
	}

	@Test
	void airborneHitOpensNoWindow() {
		assertNull(hit(N, false));
		assertNull(jump(N + 3), "a jump after landing is not a late reset of an airborne hit");
		// It also retires an older grounded window.
		this.watch.reset();
		hit(N, true);
		hit(N + 1, false);
		assertNull(jump(N + 2));
	}

	@Test
	void catchUpTicksAreDecidedByTicks() {
		long t = nanos(N, 10);
		assertNull(this.watch.onHitTaken(N, t, KWAMIX, true, false, MIN, MAX, ATTEMPT));
		// Ticks N+1..N+3 run back to back after a stutter: identical wall time.
		JumpResetEvent e = this.watch.onJump(N + 3, t, MIN, MAX, ATTEMPT);
		assertNotNull(e);
		assertFalse(e.success());
		assertEquals(100, e.deltaMs());
		assertEquals(0, e.wallMs());
	}

	@Test
	void windowSettingsAreRespected() {
		// min 50, max 100, attempt 100
		this.watch.onHitTaken(N, nanos(N, 0), KWAMIX, true, false, 50, 100, 100);
		JumpResetEvent tooSoon = this.watch.onJump(N + 1, nanos(N + 1, 0), 50, 100, 100);
		assertFalse(tooSoon.success(), "first-tick jump is off-window when min is 50");
		this.watch.onHitTaken(N + 20, nanos(N + 20, 0), KWAMIX, true, false, 50, 100, 100);
		assertTrue(this.watch.onJump(N + 23, nanos(N + 23, 0), 50, 100, 100).success());
		this.watch.onHitTaken(N + 40, nanos(N + 40, 0), KWAMIX, true, false, 50, 100, 100);
		assertNull(this.watch.onJump(N + 44, nanos(N + 44, 0), 50, 100, 100), "150 ms is past a 100 ms attempt window");
	}

	@Test
	void resetClearsState() {
		hit(N, true);
		this.watch.reset();
		assertNull(jump(N + 1));
		this.watch.reset();
		jump(N);
		this.watch.reset();
		assertNull(hit(N, true));
		this.watch.reset();
		press(N - 1);
		hit(N, true, true);
		this.watch.reset();
		assertNull(tickEnd(N + 1), "a waiting held press is forgotten too");
	}

	@Test
	void gameMsFormula() {
		assertEquals(0, JumpResetWatch.gameMs(1));
		assertEquals(50, JumpResetWatch.gameMs(2));
		assertEquals(-50, JumpResetWatch.gameMs(0));
		assertEquals(-200, JumpResetWatch.gameMs(-3));
	}
}
