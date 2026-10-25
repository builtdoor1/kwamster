package kwamster.core.stats;

import kwamster.SampleFights;
import kwamster.core.event.HitConfirmedEvent;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.fight.FightAccumulator;
import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link StatsCalculator} against hand-computed values (DESIGN.md §5). Default settings snapshot
 * unless a test says otherwise: 3-block 2.95, jump window 0–80 ms, combo timeout 2000 ms, advantage
 * window 3000 ms, interval gap 2000 ms. Every expected number below is worked out in the comments.
 */
final class StatsCalculatorTest {
	private static final double EPS = 1e-6;
	/** Float storage of reach values: compare with a float-sized tolerance. */
	private static final double F_EPS = 1e-5;
	private static final int FINAL = StatsCalculator.FINAL;

	// ---- S2, S3, S4, S6, S7, tiers --------------------------------------------------------------

	/**
	 * Eight swings on Kwamix:
	 * <pre>
	 *  t     outcome  reach  flags              tier        zone
	 *  0     landed   2.50   charged crit       CRIT        HIGH   (v 0.5)
	 *  500   landed   2.96   charged sprint     3B SPRINT   CENTER
	 *  1000  miss     3.40   (limit 3.0)        over reach
	 *  1500  landed   3.00   charged            3B          EDGE   (h 0.6)
	 *  2000  miss     2.80
	 *  2500  landed   2.00   uncharged sprint   WEAK        LOW    (v -0.5)
	 *  3000  landed   2.70   charged sprint     SPRINT      CENTER
	 *  3500  miss     4.20   (limit 4.5, ATTACK_RANGE item: not over)
	 * </pre>
	 */
	private static FightRecord accuracyFight() {
		return SampleFights.script()
				.hit(0, 2.50, s -> {
					s.crit = true;
					s.aimV = 0.5f;
				})
				.hit(500, 2.96, s -> s.sprint = true)
				.miss(1000, 3.40)
				.hit(1500, 3.00, s -> s.aimH = 0.6f)
				.miss(2000, 2.80)
				.hit(2500, 2.00, s -> {
					s.charged = false;
					s.charge = 0.4f;
					s.sprint = true;
					s.aimV = -0.5f;
				})
				.hit(3000, 2.70, s -> s.sprint = true)
				.miss(3500, 4.20, s -> {
					s.rangeLimit = 4.5f;
					s.customRange = true;
				})
				.duration(3500)
				.build();
	}

	@Test
	void accuracyReachAimAndTiers() {
		FightStats s = StatsCalculator.compute(accuracyFight(), FINAL);
		// S4: 5 of 8 landed.
		assertEquals(8, s.swings);
		assertEquals(5, s.landed);
		assertEquals(3, s.misses);
		assertEquals(62.5, s.accuracyPct, EPS);
		// S2 landed: (2.50 + 2.96 + 3.00 + 2.00 + 2.70) / 5 = 13.16 / 5 = 2.632, max 3.00.
		assertEquals(2.632, s.reachAvg, F_EPS);
		assertEquals(3.00, s.reachMax, F_EPS);
		assertEquals(5, s.reachCount);
		// S2 misses: (3.40 + 2.80 + 4.20) / 3 = 3.4667, max 4.20; only 3.40 > its 3.0 limit.
		assertEquals(10.4 / 3.0, s.missReachAvg, F_EPS);
		assertEquals(4.20, s.missReachMax, F_EPS);
		assertEquals(4.5, s.missReachMaxLimit, F_EPS, "the range of the miss that set the maximum");
		assertEquals(3, s.missReachCount);
		assertEquals(1, s.overReachMisses);
		// S3: HIGH 1, CENTER 2, EDGE 1, LOW 1, summing to landed.
		assertEquals(2, s.aimCenter);
		assertEquals(1, s.aimEdge);
		assertEquals(1, s.aimHigh);
		assertEquals(1, s.aimLow);
		assertEquals(s.landed, s.aimCenter + s.aimEdge + s.aimHigh + s.aimLow);
		// Tiers: 3B SPRINT, 3B, CRIT, SPRINT, WEAK one each.
		assertArrayEquals(new int[] {0, 1, 1, 1, 1, 0, 1}, s.quality);
		assertEquals(s.landed, Arrays.stream(s.quality).sum());
		assertEquals(2, s.threeBlockHits);
		assertEquals(40.0, s.threeBlockPct, EPS);
		// S7: one crit over all 5 landed hits (WEAK included) = 20 %.
		assertEquals(1, s.crits);
		assertEquals(20.0, s.critPct, EPS);
		// S6: charged AND sprinting: t=500 and t=3000. The uncharged sprinting WEAK hit is not one.
		assertEquals(2, s.sprintHits);
		assertEquals(3, s.nonSprintHits);
		assertEquals(40.0, s.sprintPct, EPS);
		assertEquals(s.sprintHits, s.quality[HitQuality.THREE_BLOCK_SPRINT.code()] + s.quality[HitQuality.SPRINT.code()]);
		// Streak L L M L M L L M: 1 2 0 1 0 1 2 0 → best 2; the record ends on a miss.
		assertEquals(2, s.bestStreak);
		assertEquals(0, StatsCalculator.compute(accuracyFight(), 3500).currentStreak);
	}

	@Test
	void missingQualityIsRegradedWithTheSettingsSnapshot() {
		// A charged crit at 2.85: CRIT with the default 2.95, 3-BLOCK CRIT under a 2.80 snapshot.
		FightRecord r = SampleFights.script().settings(st -> st.threeBlockReach = 2.80f)
				.hit(0, 2.85, s -> s.crit = true).build();
		assertEquals(HitQuality.THREE_BLOCK_CRIT.name(), r.swings.get(0).quality);
		r.swings.get(0).quality = null;
		r.swings.get(0).zone = null;
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(1, s.quality[HitQuality.THREE_BLOCK_CRIT.code()]);
		assertEquals(1, s.threeBlockHits);
		assertEquals(1, s.aimCenter);
		r.settings.threeBlockReach = 2.95f;
		assertEquals(1, StatsCalculator.compute(r, FINAL).quality[HitQuality.CRIT.code()]);
	}

	// ---- S5 combos ------------------------------------------------------------------------------------

	/** D D K D D D at 0, 300, 600, 900, 1200, 1500. */
	private static FightRecord comboFight() {
		return SampleFights.script()
				.hit(0, 2.5).hit(300, 2.5).taken(600, true).hit(900, 2.5).hit(1200, 2.5).hit(1500, 2.5)
				.duration(1500).build();
	}

	@Test
	void comboSequenceFinal() {
		// Chains: [0, 300] closed by the hit taken (2), then [900, 1200, 1500] closed by the fight end (3).
		FightStats s = StatsCalculator.compute(comboFight(), FINAL);
		assertEquals(3, s.bestCombo);
		assertEquals(2, s.combosDealt);
		assertEquals(5, s.comboHitsDealt);
		assertEquals(2.5, s.avgCombo, EPS);
		// The single hit taken is a chain of 1: not a combo.
		assertEquals(1, s.bestComboTaken);
		assertEquals(0, s.combosTaken);
		assertEquals(0.0, s.avgComboTaken, EPS);
		// Finished fights report the current chains and streak as 0.
		assertEquals(0, s.currentCombo);
		assertEquals(0, s.currentComboTaken);
		assertEquals(0, s.currentStreak);
		assertEquals(1500, s.durationMs);
	}

	@Test
	void comboSequenceLive() {
		FightRecord r = comboFight();
		FightStats live = StatsCalculator.compute(r, 1500);
		assertEquals(3, live.currentCombo);
		assertEquals(0, live.currentComboTaken);
		assertEquals(3, live.bestCombo, "best includes the running chain");
		assertEquals(1, live.combosDealt, "the running chain is not closed yet");
		assertEquals(2, live.comboHitsDealt);
		assertEquals(5, live.currentStreak, "the hit taken does not break the streak");
		// Timeout: 1500 + 2000 = 3500 still holds (strictly greater closes), 3501 does not.
		assertEquals(3, StatsCalculator.compute(r, 3500).currentCombo);
		assertEquals(0, StatsCalculator.compute(r, 3501).currentCombo);
		// The live record's durationMs is 0; live duration is max(0, nowRel).
		r.durationMs = 0;
		assertEquals(1234, StatsCalculator.compute(r, 1234).durationMs);
		assertEquals(0, StatsCalculator.compute(r, -5).durationMs);
	}

	@Test
	void timeoutSplitsAChain() {
		// D 0, D 1000, D 3100 (2100 > 2000 after the last), D 3500 → [0, 1000] and [3100, 3500].
		FightRecord r = SampleFights.script().hit(0, 2.5).hit(1000, 2.5).hit(3100, 2.5).hit(3500, 2.5)
				.duration(3500).build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(2, s.bestCombo);
		assertEquals(2, s.combosDealt);
		assertEquals(4, s.comboHitsDealt);
		// comboTimeoutMs = 0 means never: one chain of 4.
		r.settings.comboTimeoutMs = 0;
		FightStats never = StatsCalculator.compute(r, FINAL);
		assertEquals(4, never.bestCombo);
		assertEquals(1, never.combosDealt);
		assertEquals(4, never.comboHitsDealt);
	}

	@Test
	void takenChainsAreSymmetric() {
		// K K K D K K: taken chains [0,100,200] (closed by D) and [400,500] (closed by the end).
		FightRecord r = SampleFights.script().taken(0, true).taken(100, true).taken(200, false).hit(300, 2.5)
				.taken(400, true).taken(500, true).duration(500).build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(3, s.bestComboTaken);
		assertEquals(2, s.combosTaken);
		assertEquals(5, s.comboHitsTaken);
		assertEquals(2.5, s.avgComboTaken, EPS);
		assertEquals(1, s.bestCombo);
		assertEquals(2, StatsCalculator.compute(r, 500).currentComboTaken);
	}

	// ---- S5 streak ---------------------------------------------------------------------------------------

	@Test
	void missResetsTheStreakButAHitTakenDoesNot() {
		// L L K L M L: 1 2 (K) 3 0 1 → best 3, current 1.
		FightRecord r = SampleFights.script().hit(0, 2.5).hit(100, 2.5).taken(150, true).hit(200, 2.5)
				.miss(300, 3.2).hit(400, 2.5).duration(400).build();
		FightStats live = StatsCalculator.compute(r, 400);
		assertEquals(3, live.bestStreak);
		assertEquals(1, live.currentStreak);
		assertEquals(0, StatsCalculator.compute(r, FINAL).currentStreak);
	}

	// ---- NO-REG --------------------------------------------------------------------------------------------

	@Test
	void noRegHitsCountButNeverMoveMomentum() {
		// D 0, D 100, NO-REG 200, D 300, K 400, NO-REG 500.
		FightRecord r = SampleFights.script()
				.hit(0, 2.5).hit(100, 2.5)
				.hit(200, 2.97, s -> {
					s.intoImmunity = true;
					s.crit = true;
				})
				.hit(300, 2.5)
				.taken(400, true)
				.hit(500, 2.5, s -> s.intoImmunity = true)
				.duration(500).build();
		FightStats s = StatsCalculator.compute(r, 500);
		// Counted in landed, quality and immuneHits (and accuracy, crits).
		assertEquals(5, s.landed);
		assertEquals(2, s.immuneHits);
		assertEquals(5, Arrays.stream(s.quality).sum());
		assertEquals(1, s.quality[HitQuality.THREE_BLOCK_CRIT.code()]);
		assertEquals(1, s.crits);
		// Combos skip them: the dealt chain is 0,100,300 = 3; the NO-REG at 500 does not close the taken chain.
		assertEquals(3, s.bestCombo);
		assertEquals(0, s.currentCombo);
		assertEquals(1, s.currentComboTaken);
		// Streak: NO-REG hits neither count nor break it, and the hit taken does not break it: 0, 100, 300 → 3.
		assertEquals(3, s.bestStreak);
		assertEquals(3, s.currentStreak);
		// Intervals skip them: 0→100 (100) and 100→300 (200): mean 150, population SD 50.
		assertEquals(2, s.intervalCount);
		assertEquals(150.0, s.intervalAvgMs, EPS);
		assertEquals(50.0, s.intervalSdMs, EPS);
	}

	// ---- S8 intervals ------------------------------------------------------------------------------------

	@Test
	void intervalsUseClickTimeAndSkipLongGapsAndOpponentSwitches() {
		PlayerRef rival = SampleFights.ref("Rival");
		// Landed swings (opp, t, clickLead → click time):
		//  a  0  1000  20 → 980
		//  b  0  1600  50 → 1550   Δ 570
		//  c  1  2100   0 → 2100   other opponent: no interval
		//  d  0  2500   0 → 2500   previous (c) is the other opponent: no interval
		//  e  0  3000 100 → 2900   Δ 400
		//  f  0  5500   0 → 5500   Δ 2600 > 2000: dropped
		//  g  0  7000  10 → 6990   Δ 1490
		// A miss at 4000 is ignored. Values 570, 400, 1490: mean 820, SD sqrt(687800 / 3).
		FightRecord r = SampleFights.script().opponent(rival, true)
				.hit(1000, 2.5, s -> lead(s, 20))
				.hit(1600, 2.5, s -> lead(s, 50))
				.hit(2100, 2.5, s -> s.opp = 1)
				.hit(2500, 2.5)
				.hit(3000, 2.5, s -> lead(s, 100))
				.miss(4000, 3.3)
				.hit(5500, 2.5)
				.hit(7000, 2.5, s -> lead(s, 10))
				.duration(7000).build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(3, s.intervalCount);
		assertEquals(820.0, s.intervalAvgMs, EPS);
		assertEquals(Math.sqrt(687800.0 / 3.0), s.intervalSdMs, EPS);
		assertEquals(1490, StatsCalculator.lastIntervalMs(r));
		r.settings.intervalMaxGapMs = 3000;
		assertEquals(4, StatsCalculator.compute(r, FINAL).intervalCount, "2600 counts with a 3000 ms gap");
	}

	private static void lead(FightRecord.Swing s, int ms) {
		s.clickTimed = true;
		s.clickLeadMs = ms;
	}

	// ---- S1 jump resets ---------------------------------------------------------------------------------

	@Test
	void jumpsAreRecomputedFromDeltaMsAndTheSnapshot() {
		// (deltaTicks, deltaMs, stored success):
		//  ( 1,    0, true )  success
		//  ( 2,   50, false)  success (stored flag disagrees)
		//  ( 4,  150, true )  late    (stored flag disagrees)
		//  ( 0,  -50, false)  early
		//  (-2, -150, false)  early
		//  ( 3,  100, false)  late
		FightRecord r = SampleFights.script()
				.jump(100, 1, 0, true).jump(200, 2, 50, false).jump(300, 4, 150, true)
				.jump(400, 0, -50, false).jump(500, -2, -150, false).jump(600, 3, 100, false)
				.taken(50, true).taken(150, true).taken(250, false).taken(350, true).taken(450, false)
				.duration(600).build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(6, s.jumpAttempts);
		assertEquals(2, s.jumpSuccess);
		assertEquals(2, s.jumpEarly);
		assertEquals(2, s.jumpLate);
		assertEquals(s.jumpAttempts, s.jumpSuccess + s.jumpEarly + s.jumpLate);
		assertEquals(100.0 * 2 / 6, s.jumpPct, EPS);
		// Non-early deltas 0, 50, 150, 100: mean 75.
		assertEquals(75.0, s.jumpAvgDeltaMs, EPS);
		// Opportunities: melee hits taken on the ground (3 of 5).
		assertEquals(3, s.jumpOpportunities);
		// A 50–150 ms snapshot: success 50, 150, 100; late 0; early unchanged.
		r.settings.jumpMinMs = 50;
		r.settings.jumpMaxMs = 150;
		FightStats wide = StatsCalculator.compute(r, FINAL);
		assertEquals(3, wide.jumpSuccess);
		assertEquals(1, wide.jumpLate);
		assertEquals(2, wide.jumpEarly);
	}

	// ---- trades -------------------------------------------------------------------------------------------

	@Test
	void tradesScoreOnlyKnownDamage() {
		//  dealt taken known  momentum
		//  4     2     yes    40 known      won
		//  1     3     yes   -10 known      lost
		//  2     2     yes    unknown       even
		//  0     2     no     70 known      not scored
		//  5     1     yes    unknown       won
		FightRecord r = SampleFights.script()
				.trade(100, 4, 2, true, 40, true)
				.trade(200, 1, 3, true, -10, true)
				.trade(300, 2, 2, true, 0, false)
				.trade(400, 0, 2, false, 70, true)
				.trade(500, 5, 1, true, 0, false)
				.build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(5, s.trades);
		assertEquals(2, s.tradesWon);
		assertEquals(1, s.tradesLost);
		assertEquals(1, s.tradesEven);
		assertEquals(200.0 / 3.0, s.tradeWinPct, EPS, "won / (won + lost): evens and unknown excluded");
		assertEquals(3, s.tradesWithMomentum);
		assertEquals(100.0 / 3.0, s.avgMomentumPct, F_EPS);
	}

	// ---- confirmation (§5.9) ------------------------------------------------------------------------------

	@Test
	void confirmationMatchesTheEarliestUnconfirmedSwingInTheWindow() {
		long start = 5_000_000_000L;
		PlayerRef kwamix = SampleFights.KWAMIX;
		FightAccumulator acc = new FightAccumulator(start, SampleFights.START_EPOCH_MS, SampleFights.SELF, kwamix,
				"", FightRecord.Settings.defaults(), Pos.ZERO);
		acc.addPingSample(50); // window = 50 + 300 = 350 ms
		for (int t : new int[] {0, 100, 200, 1000}) {
			acc.addSwing(swing(start, t, kwamix, true), null);
		}
		acc.addSwing(swing(start, 1100, kwamix, false), null);
		// tc 250: window [-100, 250] → the earliest unconfirmed landed swing, t = 0 (not the latest, 200).
		acc.addConfirm(new HitConfirmedEvent(at(start, 250), kwamix, true));
		// tc 260 → t = 100.
		acc.addConfirm(new HitConfirmedEvent(at(start, 260), kwamix, true));
		// tc 2000: window [1650, 2000] holds no landed swing → dropped.
		acc.addConfirm(new HitConfirmedEvent(at(start, 2000), kwamix, true));
		// Indirect (your arrow) never matches: keep-alive only.
		acc.addConfirm(new HitConfirmedEvent(at(start, 1050), kwamix, false));
		// A victim who is not in the fight is dropped and never added as an opponent.
		acc.addConfirm(new HitConfirmedEvent(at(start, 1050), SampleFights.ref("Stranger"), true));
		// tc 1100: window [750, 1100] → the landed swing at 1000 (the miss at 1100 is not eligible).
		acc.addConfirm(new HitConfirmedEvent(at(start, 1100), kwamix, true));
		// (THORNS never reaches the accumulator: CAPTURE drops it at routing, §6.3.)
		FightRecord r = acc.view();
		boolean[] confirmed = new boolean[r.swings.size()];
		for (int i = 0; i < confirmed.length; i++) {
			confirmed[i] = r.swings.get(i).confirmed;
		}
		assertArrayEquals(new boolean[] {true, true, false, true, false}, confirmed);
		assertEquals(1, r.opponents.size());
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertEquals(3, s.hitsConfirmed);
		assertEquals(75.0, s.confirmPct, EPS);
	}

	// ---- damage linking -------------------------------------------------------------------------------

	@Test
	void damageLinksWithinMinus100To900Ms() {
		PlayerRef rival = SampleFights.ref("Rival");
		// Landed on Kwamix at 1000 and 3000, a miss at 5000; hits taken at 2000 and 6000.
		// Opponent drops: 3.0 @ 950 (−50: yes), 2.0 @ 1900 (+900: yes), 1.5 @ 1901 (+901 / −1099: no),
		// 4.0 @ 5050 (only a miss nearby: no), Rival 2.5 @ 1000 (no landed hit on Rival: no).
		// Own drops: 2.0 @ 2100 (yes), 1.0 @ 6950 (+950: no), 3.5 @ 5900 (−100: yes).
		FightRecord r = SampleFights.script().opponent(rival, true)
				.hit(1000, 2.5).hit(3000, 2.5).miss(5000, 3.3).taken(2000, true).taken(6000, true)
				.drop(950, false, 0, 3.0).drop(1900, false, 0, 2.0).drop(1901, false, 0, 1.5)
				.drop(5050, false, 0, 4.0).drop(1000, false, 1, 2.5)
				.drop(2100, true, -1, 2.0).drop(6950, true, -1, 1.0).drop(5900, true, -1, 3.5)
				.duration(7000).build();
		FightStats s = StatsCalculator.compute(r, FINAL);
		assertTrue(s.damageKnown);
		assertEquals(5.0, s.damageDealt, EPS);
		assertEquals(5.5, s.damageTaken, EPS);
		r.opponentHealthVisible = false;
		FightStats hidden = StatsCalculator.compute(r, FINAL);
		assertFalse(hidden.damageKnown);
		assertEquals(0.0, hidden.damageDealt, 0.0);
		assertEquals(0.0, hidden.damageTaken, 0.0);
	}

	// ---- empty -------------------------------------------------------------------------------------------

	@Test
	void emptyRecordIsAllZerosWithoutNaN() throws IllegalAccessException {
		FightRecord empty = SampleFights.script().build();
		assertAllZero(StatsCalculator.compute(empty, FINAL));
		assertAllZero(StatsCalculator.compute(empty, 0));
		assertAllZero(StatsCalculator.compute(null, FINAL));
		FightRecord nulls = new FightRecord();
		nulls.swings = null;
		nulls.hitsTaken = null;
		nulls.jumps = null;
		nulls.trades = null;
		nulls.damage = null;
		nulls.settings = null;
		assertAllZero(StatsCalculator.compute(nulls, FINAL));
	}

	private static void assertAllZero(FightStats s) throws IllegalAccessException {
		for (Field f : FightStats.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) {
				continue;
			}
			Object v = f.get(s);
			if (v instanceof Double d) {
				assertFalse(d.isNaN() || d.isInfinite(), f.getName());
				assertEquals(0.0, d, 0.0, f.getName());
			} else if (v instanceof Integer i) {
				assertEquals(0, i, f.getName());
			} else if (v instanceof Boolean b) {
				assertFalse(b, f.getName());
			} else if (v instanceof int[] a) {
				assertArrayEquals(new int[a.length], a, f.getName());
			}
		}
	}

	// ---- live replay (single source of truth, §4.5) ------------------------------------------------------

	@Test
	void liveReplayMatchesComputeAtEveryStep() {
		long start = 9_000_000_000L;
		PlayerRef kwamix = SampleFights.KWAMIX;
		FightAccumulator acc = new FightAccumulator(start, SampleFights.START_EPOCH_MS, SampleFights.SELF, kwamix,
				"", FightRecord.Settings.defaults(), Pos.ZERO);
		// Each row: t, kind (L landed, N NO-REG, M miss, K hit taken), expected comboAfter, streakAfter, intervalMs.
		//  0    L  → 1 1 -1
		//  400  L  → 2 2 400
		//  700  M  → 2 0 -1     a miss breaks the streak, not the combo
		//  900  K               same-tick trade: the hit taken is processed before your swing
		//  900  L  → 1 1 500    the hit taken closed the chain; interval 900 − 400
		//  1100 N  → 1 1 -1     NO-REG changes nothing and closes no interval
		//  1300 L  → 2 2 400    interval 1300 − 900 (the NO-REG is skipped)
		//  3400 L  → 1 3 -1     2100 ms after the last hit: chain timed out; gap > 2000: no interval
		Object[][] script = {
				{0, 'L', 1, 1, -1},
				{400, 'L', 2, 2, 400},
				{700, 'M', 2, 0, -1},
				{900, 'K'},
				{900, 'L', 1, 1, 500},
				{1100, 'N', 1, 1, -1},
				{1300, 'L', 2, 2, 400},
				{3400, 'L', 1, 3, -1},
		};
		for (Object[] row : script) {
			int t = (Integer) row[0];
			char kind = (Character) row[1];
			if (kind == 'K') {
				acc.addHitTaken(new HitTakenEvent(at(start, t), 0, kwamix, false, 2.9, true, false, Pos.ZERO, new Pos(0, 0, 3)));
				continue;
			}
			SwingEvent e = swing(start, t, kwamix, kind != 'M');
			if (kind == 'N') {
				e = new SwingEvent(e.nanos(), false, 0, kwamix, true, true, 2.6, 2.6, 3.0, false, e.aim(),
						1f, true, false, false, false, true, Pos.ZERO, new Pos(0, 0, 3), 0f, 0);
			}
			acc.addSwing(e, null);
			FightRecord live = acc.view();
			int nowRel = acc.relMs(at(start, t));
			FightStats st = StatsCalculator.compute(live, nowRel);
			// What FightManager publishes (comboAfter/streakAfter from compute, intervalMs from lastIntervalMs).
			int interval = kind == 'L' ? StatsCalculator.lastIntervalMs(live) : -1;
			String at = "t=" + t;
			assertEquals((int) (Integer) row[2], st.currentCombo, at + " comboAfter");
			assertEquals((int) (Integer) row[3], st.currentStreak, at + " streakAfter");
			assertEquals((int) (Integer) row[4], interval, at + " intervalMs");
			assertEquals(st.advantage, AdvantageModel.at(live, nowRel), EPS, at + " advantage");
		}
		// The NO-REG swing itself never closes an interval through lastIntervalMs either: it reports the
		// last qualifying pair, which a NO-REG cannot be part of.
		FightStats fin = StatsCalculator.compute(acc.view(), FINAL);
		assertEquals(2, fin.bestCombo);
		assertEquals(3, fin.bestStreak);
		assertEquals(3, fin.intervalCount);
	}

	private static long at(long start, int ms) {
		return start + ms * 1_000_000L;
	}

	private static SwingEvent swing(long start, int t, PlayerRef target, boolean landed) {
		AimSample aim = new AimSample(0.1, 0.05, landed ? AimZone.CENTER : null, false);
		return new SwingEvent(at(start, t), false, 0, target, landed, false, landed ? 2.6 : 3.4, landed ? 2.7 : Double.NaN,
				3.0, false, aim, 1f, true, false, false, false, true, Pos.ZERO, new Pos(0, 0, 3), 0f, 40);
	}
}
