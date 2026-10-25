package kwamster.core.fight;

import kwamster.SampleFights;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.Pos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §5.13 fight clock: wall time minus singleplayer pause time. */
final class FightClockTest {
	private static final long S = 1_000_000_000L;

	@Test
	void aFiveSecondPauseIsRemovedFromTimesAndDuration() {
		FightClock clock = new FightClock();
		long e1 = 1 * S;
		clock.pause(2 * S);
		clock.pause(3 * S); // later paused ticks change nothing
		clock.resume(7 * S);
		clock.resume(8 * S); // already running
		long e2 = 9 * S;
		assertEquals(5 * S, clock.pausedTotalNanos());
		assertEquals(3 * S, clock.toFight(e2) - clock.toFight(e1), "9 − 1 = 8 s of wall time, 3 s of fight time");
		assertEquals(e2 - 5 * S, clock.toFight(e2), "after the last resume: n − pausedTotal");

		FightAccumulator acc = new FightAccumulator(clock.toFight(e1), SampleFights.START_EPOCH_MS, SampleFights.SELF,
				SampleFights.KWAMIX, "", FightRecord.Settings.defaults(), Pos.ZERO);
		FightRecord r = acc.finish(clock.toFight(e2), FightResult.DRAW, FightEndReason.IDLE, "test");
		assertEquals(3000, r.durationMs);
	}

	@Test
	void theClockStandsStillDuringAPause() {
		FightClock clock = new FightClock();
		clock.pause(2 * S);
		assertTrue(clock.paused());
		assertEquals(2 * S, clock.toFight(5 * S));
		assertEquals(2 * S, clock.toFight(2 * S));
		// An event stamped before the pause keeps its time.
		assertEquals(S + S / 2, clock.toFight(S + S / 2));
		clock.resume(7 * S);
		assertFalse(clock.paused());
		assertEquals(S + S / 2, clock.toFight(S + S / 2), "converted after the resume: still before the pause");
		assertEquals(3 * S, clock.toFight(8 * S));
	}

	@Test
	void pausedTimeNeverTriggersAnIdleEnd() {
		KwamsterConfig.Advanced adv = new KwamsterConfig.Advanced();
		FightStateMachine sm = new FightStateMachine(() -> adv);
		FightClock clock = new FightClock();
		sm.onCombat(SampleFights.KWAMIX, clock.toFight(0), true, true);
		OpponentPresence near = new OpponentPresence(true, true, false, 3.0);
		clock.pause(1 * S);
		// FightManager skips end rules while paused; even a tick right at the resume sees 1 s of fight time.
		clock.resume(20 * S);
		assertNull(sm.tick(clock.toFight(20 * S), near, SelfState.ALIVE), "19 s of pause is not idle time");
		assertNull(sm.tick(clock.toFight(27 * S), near, SelfState.ALIVE), "8 s of fight time is not > 8 s");
		assertEquals(FightEndReason.IDLE, sm.tick(clock.toFight(27 * S + 1), near, SelfState.ALIVE));
		assertEquals(0L, sm.endNanos(), "the fight ends at its last combat, 0 s of fight time");
	}

	@Test
	void resetForgetsPauses() {
		FightClock clock = new FightClock();
		clock.pause(S);
		clock.resume(3 * S);
		clock.pause(4 * S);
		clock.reset();
		assertFalse(clock.paused());
		assertEquals(0L, clock.pausedTotalNanos());
		assertEquals(10 * S, clock.toFight(10 * S));
	}

	@Test
	void twoPausesAccumulate() {
		FightClock clock = new FightClock();
		clock.pause(1 * S);
		clock.resume(2 * S);
		clock.pause(5 * S);
		clock.resume(8 * S);
		assertEquals(4 * S, clock.pausedTotalNanos());
		assertEquals(3 * S, clock.toFight(4 * S), "only the first pause lies before 4 s");
		assertEquals(6 * S, clock.toFight(10 * S));
		assertEquals(4 * S, clock.toFight(6 * S), "inside the second pause the clock stands at its start");
	}
}
