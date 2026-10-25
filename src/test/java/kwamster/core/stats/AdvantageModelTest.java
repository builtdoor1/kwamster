package kwamster.core.stats;

import kwamster.SampleFights;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.5 advantage: {@code A = clamp(0.5·c + 0.5·exch, −1, 1)} with {@code exch = (d − k) / max(d + k, 1)}
 * over the last W = 3000 ms and {@code c = ±min(chain, 3)/3} from the running chains.
 */
final class AdvantageModelTest {
	private static final double EPS = 1e-9;

	@Test
	void noEventsIsZero() {
		assertEquals(0.0, AdvantageModel.value(0, 0, 0, 0), EPS);
		assertEquals(0.0, AdvantageModel.at(SampleFights.script().build(), 0), EPS);
		assertEquals(0.0, AdvantageModel.at(null, 100), EPS);
	}

	@Test
	void threeDealtHoldingComboThreeIsOne() {
		// exch = 3/3 = 1, c = 3/3 = 1 → A = 1.
		assertEquals(1.0, AdvantageModel.value(3, 0, 3, 0), EPS);
		FightRecord r = SampleFights.script().hit(0, 2.5).hit(100, 2.5).hit(200, 2.5).build();
		assertEquals(1.0, AdvantageModel.at(r, 200), EPS);
	}

	@Test
	void twoAndTwoHoldingComboOne() {
		// exch = 0, c = 1/3 → A = 1/6 = 0.1667.
		assertEquals(1.0 / 6.0, AdvantageModel.value(2, 2, 1, 0), EPS);
		FightRecord r = SampleFights.script().hit(0, 2.5).taken(100, true).taken(200, true).hit(300, 2.5).build();
		assertEquals(1.0 / 6.0, AdvantageModel.at(r, 300), EPS);
	}

	@Test
	void takenSideIsSymmetricAndClamped() {
		assertEquals(-1.0, AdvantageModel.value(0, 3, 0, 3), EPS);
		assertEquals(-1.0 / 6.0, AdvantageModel.value(2, 2, 0, 1), EPS);
		// Both chains running cannot happen in a record, but the formula gives c = 0.
		assertEquals(0.0, AdvantageModel.value(1, 1, 1, 1), EPS);
		// A chain longer than 3 counts as 3.
		assertEquals(1.0, AdvantageModel.value(5, 0, 7, 0), EPS);
	}

	@Test
	void eventsOlderThanTheWindowAreIgnored() {
		FightRecord r = SampleFights.script().hit(0, 2.5).hit(100, 2.5).hit(200, 2.5).build();
		// t = 3199: window (199, 3199] holds the hit at 200 (d = 1); the chain timed out (2999 > 2000): A = 0.5.
		assertEquals(0.5, AdvantageModel.at(r, 3199), EPS);
		// t = 3200: window (200, 3200] is empty (t − W < t_i is strict): A = 0.
		assertEquals(0.0, AdvantageModel.at(r, 3200), EPS);
	}

	@Test
	void noRegHitsAreNotInD() {
		FightRecord twoNoReg = SampleFights.script()
				.hit(0, 2.5, s -> s.intoImmunity = true).hit(100, 2.5, s -> s.intoImmunity = true).build();
		assertEquals(0.0, AdvantageModel.at(twoNoReg, 100), EPS);
		// K at 0, NO-REG at 50: d = 0, k = 1, taken chain 1 → A = 0.5·(−1/3) + 0.5·(−1) = −2/3.
		FightRecord r = SampleFights.script().taken(0, true).hit(50, 2.5, s -> s.intoImmunity = true).build();
		assertEquals(-2.0 / 3.0, AdvantageModel.at(r, 50), EPS);
	}

	@Test
	void labelThresholds() {
		assertEquals("YOU", AdvantageModel.label(0.15));
		assertEquals("EVEN", AdvantageModel.label(0.1499));
		assertEquals("EVEN", AdvantageModel.label(0.0));
		assertEquals("EVEN", AdvantageModel.label(-0.1499));
		assertEquals("THEM", AdvantageModel.label(-0.15));
		assertEquals("YOU", AdvantageModel.label(1.0));
		assertEquals("THEM", AdvantageModel.label(-1.0));
	}

	@Test
	void zeroMsFightHasOneSample() {
		FightStats empty = StatsCalculator.compute(SampleFights.script().duration(0).build(), StatsCalculator.FINAL);
		assertEquals(0.0, empty.advYouPct, 0.0);
		assertEquals(0.0, empty.advThemPct, 0.0);
		// One hit at t = 0: the single sample t = 0 has d = 1 and combo 1 → A = 2/3 → YOU = 100 %.
		FightStats one = StatsCalculator.compute(SampleFights.script().hit(0, 2.5).duration(0).build(), StatsCalculator.FINAL);
		assertTrue(Double.isFinite(one.advYouPct) && Double.isFinite(one.advThemPct));
		assertEquals(100.0, one.advYouPct, EPS);
		assertEquals(0.0, one.advThemPct, EPS);
	}

	@Test
	void sampleSharesAreHandComputed() {
		// D 0, K 500, K 600, duration 1000: samples t = 0, 100, ..., 1000 (11).
		//  0..400  d1 k0, combo 1         → A = 1/6 + 1/2 = 0.667   YOU  (5 samples)
		//  500     d1 k1, taken chain 1   → A = −1/6 + 0 = −0.167   THEM
		//  600..1000 d1 k2, taken chain 2 → A = −1/3 − 1/6 = −0.5   THEM (5 samples)
		FightRecord r = SampleFights.script().hit(0, 2.5).taken(500, true).taken(600, true).duration(1000).build();
		FightStats s = StatsCalculator.compute(r, StatsCalculator.FINAL);
		assertEquals(500.0 / 11.0, s.advYouPct, EPS);
		assertEquals(600.0 / 11.0, s.advThemPct, EPS);
		assertEquals(-0.5, s.advantage, EPS);
		// Live at 450 the same record (events after 450 included in the lists) samples 0..400 only.
		FightStats live = StatsCalculator.compute(r, 450);
		assertEquals(100.0, live.advYouPct, EPS);
	}

	@Test
	void finalAdvantageUsesTheChainStateAtDurationMs() {
		// D 0, D 500, D 1000, duration 1000: at 1000 d = 3 and the dealt chain is 3 → A = 1, although the
		// finished stats report currentCombo 0.
		FightRecord r = SampleFights.script().hit(0, 2.5).hit(500, 2.5).hit(1000, 2.5).duration(1000).build();
		FightStats s = StatsCalculator.compute(r, StatsCalculator.FINAL);
		assertEquals(1.0, s.advantage, EPS);
		assertEquals(0, s.currentCombo);
		// Same events, 4000 ms long: window (1000, 4000] is empty and the chain timed out → A = 0.
		r.durationMs = 4000;
		assertEquals(0.0, StatsCalculator.compute(r, StatsCalculator.FINAL).advantage, EPS);
	}
}
