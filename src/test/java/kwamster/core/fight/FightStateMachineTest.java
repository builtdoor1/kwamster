package kwamster.core.fight;

import kwamster.SampleFights;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightResult;
import kwamster.core.model.PlayerRef;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.13 start and end rules with the default detection constants: idle 8000 ms, max distance 16
 * blocks, death inference 3000 ms, outcome window 1500 ms (§5.13a). All times are fight-clock nanos built
 * from milliseconds.
 */
final class FightStateMachineTest {
	private static final PlayerRef A = SampleFights.KWAMIX;
	private static final PlayerRef B = SampleFights.ref("Bystander");
	private static final PlayerRef C = SampleFights.ref("Third");
	private static final PlayerRef SELF = SampleFights.SELF;
	private static final OpponentPresence NEAR = new OpponentPresence(true, true, false, 3.0);
	private static final SelfState ALIVE = SelfState.ALIVE;

	private KwamsterConfig.Advanced adv;
	private FightStateMachine sm;

	@BeforeEach
	void setUp() {
		this.adv = new KwamsterConfig.Advanced();
		this.sm = new FightStateMachine(() -> this.adv);
	}

	private static long ms(long ms) {
		return ms * 1_000_000L;
	}

	private FightStateMachine fresh() {
		return new FightStateMachine(() -> this.adv);
	}

	/** Found, alive, with synced health {@code frac}. */
	private static OpponentPresence health(double distance, float frac) {
		return new OpponentPresence(true, true, false, distance, false, frac);
	}

	private static SelfState selfAt(float frac) {
		return new SelfState(false, false, false, frac);
	}

	private static SelfState teleportedAt(float frac) {
		return new SelfState(false, false, true, frac);
	}

	@Test
	void aMissOpensAFightWithAProvisionalOpponent() {
		assertEquals(FightStateMachine.Phase.IDLE, this.sm.phase());
		assertTrue(this.sm.onCombat(A, ms(1000), false, true));
		assertEquals(FightStateMachine.Phase.ACTIVE, this.sm.phase());
		assertEquals(A, this.sm.opponent());
		assertFalse(this.sm.isEngaged(A.uuid()));
		assertEquals(ms(1000), this.sm.startNanos());
		assertEquals(ms(1000), this.sm.lastCombatNanos());
		assertEquals(FightStateMachine.NEVER, this.sm.lastDealtNanos());
		assertFalse(this.sm.onCombat(A, ms(1200), false, true), "only the first event opens");
	}

	@Test
	void aMeleeHitTakenOpensAnEngagedFight() {
		assertTrue(this.sm.onCombat(A, ms(0), true, false));
		assertTrue(this.sm.isEngaged(A.uuid()));
		assertEquals(ms(0), this.sm.lastTakenNanos());
		assertEquals(FightStateMachine.NEVER, this.sm.lastDealtNanos());
	}

	@Test
	void projectileKeepAliveOnlyFromEngagedOpponents() {
		this.sm.onCombat(A, ms(0), true, false);
		this.sm.onKeepAlive(A, ms(7000));
		assertNull(this.sm.tick(ms(15000), NEAR, ALIVE), "15000 − 7000 = 8000 is not > 8000");
		assertEquals(FightEndReason.IDLE, this.sm.tick(ms(15001), NEAR, ALIVE));

		FightStateMachine other = new FightStateMachine(() -> this.adv);
		other.onCombat(A, ms(0), true, false);
		other.onKeepAlive(B, ms(7000)); // B is not engaged: ignored entirely
		assertEquals(ms(0), other.lastCombatNanos());
		assertEquals(FightEndReason.IDLE, other.tick(ms(8001), NEAR, ALIVE));

		FightStateMachine idle = new FightStateMachine(() -> this.adv);
		idle.onKeepAlive(A, ms(0));
		assertEquals(FightStateMachine.Phase.IDLE, idle.phase(), "keep-alive never opens a fight");
	}

	@Test
	void idleEndsStrictlyAfterFightIdleMs() {
		this.sm.onCombat(A, ms(0), true, true);
		assertNull(this.sm.tick(ms(8000), NEAR, ALIVE), "exactly 8000 does not end");
		assertEquals(FightEndReason.IDLE, this.sm.tick(ms(8000) + 1, NEAR, ALIVE));
		assertEquals(FightStateMachine.Phase.ENDING, this.sm.phase());
		assertEquals(ms(0), this.sm.endNanos(), "dead air is not charged: end = lastCombat");
		assertEquals(FightResult.DRAW, FightStateMachine.resultOf(this.sm.pendingReason()));
	}

	@Test
	void idleTimeIsReadLive() {
		this.sm.onCombat(A, ms(0), true, true);
		this.adv.fightIdleMs = 2000;
		assertEquals(FightEndReason.IDLE, this.sm.tick(ms(2001), NEAR, ALIVE));
	}

	@Test
	void opponentFarAtSixteenPointZeroOne() {
		this.sm.onCombat(A, ms(0), true, true);
		assertNull(this.sm.tick(ms(100), new OpponentPresence(true, true, false, 16.0), ALIVE));
		assertEquals(FightEndReason.OPPONENT_FAR, this.sm.tick(ms(200), new OpponentPresence(true, true, false, 16.01), ALIVE));
	}

	@Test
	void opponentLeftWhenNotFound() {
		this.sm.onCombat(A, ms(0), true, false); // their hit: nothing to infer (§5.13a)
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(100), OpponentPresence.ABSENT, ALIVE));
	}

	@Test
	void deathOfAnEngagedOpponentIsAWin() {
		this.sm.onCombat(A, ms(1000), true, true);
		this.sm.onDeath(false, A, ms(2500));
		assertEquals(FightStateMachine.Phase.ENDING, this.sm.phase());
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.pendingReason());
		assertEquals(ms(2500), this.sm.endNanos(), "duration runs to the death time");
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(2550), NEAR, ALIVE));
		assertEquals(FightResult.WIN, FightStateMachine.resultOf(this.sm.pendingReason()));
		assertEquals(1500, (this.sm.endNanos() - this.sm.startNanos()) / 1_000_000L);
	}

	@Test
	void deathOfAProvisionalOpponentIsOpponentLeft() {
		this.sm.onCombat(A, ms(0), false, true); // only whiffed at
		this.sm.onDeath(false, A, ms(500));
		assertEquals(FightStateMachine.Phase.ACTIVE, this.sm.phase(), "a bystander's death is not a win");
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(550), NEAR, ALIVE));
		assertEquals(FightResult.DRAW, FightStateMachine.resultOf(FightEndReason.OPPONENT_LEFT));

		FightStateMachine dead = new FightStateMachine(() -> this.adv);
		dead.onCombat(A, ms(0), false, true);
		assertEquals(FightEndReason.OPPONENT_LEFT, dead.tick(ms(100), new OpponentPresence(true, false, false, 3.0), ALIVE),
				"a provisional opponent found dead (rule 6), not OPPONENT_DIED");
	}

	@Test
	void engagedOpponentFoundDeadIsAWin() {
		this.sm.onCombat(A, ms(0), true, true);
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(100), new OpponentPresence(true, false, false, 3.0), ALIVE));
		assertEquals(ms(100), this.sm.endNanos());
	}

	@Test
	void ownDeathIsALoss() {
		this.sm.onCombat(A, ms(0), true, false);
		this.sm.onDeath(true, SELF, ms(2000));
		assertEquals(FightEndReason.SELF_DIED, this.sm.pendingReason());
		assertEquals(ms(2000), this.sm.endNanos());
		assertEquals(FightResult.LOSS, FightStateMachine.resultOf(FightEndReason.SELF_DIED));

		FightStateMachine screen = new FightStateMachine(() -> this.adv);
		screen.onCombat(A, ms(0), true, false);
		assertEquals(FightEndReason.SELF_DIED, screen.tick(ms(300), NEAR, new SelfState(true, false)));
		assertEquals(ms(300), screen.endNanos());
	}

	@Test
	void spectatorSwitchShortlyAfterYourHitIsAWin() {
		OpponentPresence spectating = new OpponentPresence(true, true, true, 3.0);
		this.sm.onCombat(A, ms(1000), true, true);
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(4000), spectating, ALIVE), "3000 ≤ 3000 still counts");

		FightStateMachine late = new FightStateMachine(() -> this.adv);
		late.onCombat(A, ms(1000), true, true);
		assertEquals(FightEndReason.OPPONENT_LEFT, late.tick(ms(4001), spectating, ALIVE));

		FightStateMachine takenOnly = new FightStateMachine(() -> this.adv);
		takenOnly.onCombat(A, ms(1000), true, false); // engaged, but you never hit them
		assertEquals(FightEndReason.OPPONENT_LEFT, takenOnly.tick(ms(1500), spectating, ALIVE));
	}

	@Test
	void ownSpectatorSwitchShortlyAfterBeingHitIsALoss() {
		this.sm.onCombat(A, ms(1000), true, false);
		assertEquals(FightEndReason.SELF_DIED, this.sm.tick(ms(3500), NEAR, new SelfState(false, true)));

		FightStateMachine late = new FightStateMachine(() -> this.adv);
		late.onCombat(A, ms(1000), true, false);
		assertNull(late.tick(ms(4500), NEAR, new SelfState(false, true)));
	}

	@Test
	void onlyAuthoritativeEventsRetarget() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(B, ms(300), false, true); // a whiff at a passer-by
		assertEquals(A, this.sm.opponent());
		assertFalse(this.sm.isEngaged(B.uuid()));
		assertEquals(ms(300), this.sm.lastCombatNanos(), "a miss still keeps the fight alive");
		assertEquals(ms(0), this.sm.lastDealtNanos());
		this.sm.onCombat(C, ms(600), true, true);
		assertEquals(C, this.sm.opponent());
		assertTrue(this.sm.isEngaged(C.uuid()));
		assertTrue(this.sm.isEngaged(A.uuid()));
		assertEquals(ms(600), this.sm.lastDealtNanos());
	}

	@Test
	void endingIgnoresCombatAndFurtherDeaths() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onDeath(false, A, ms(1000));
		assertFalse(this.sm.onCombat(A, ms(1100), true, true), "a swing while ENDING");
		assertFalse(this.sm.onCombat(C, ms(1100), true, false), "a hit taken while ENDING");
		this.sm.onKeepAlive(A, ms(1200)); // a spear swing or projectile
		assertEquals(FightStateMachine.Phase.ENDING, this.sm.phase());
		assertEquals(A, this.sm.opponent());
		assertEquals(ms(0), this.sm.lastCombatNanos());
		this.sm.onDeath(true, SELF, ms(1300));
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.pendingReason(), "first death wins");
		assertEquals(ms(1000), this.sm.endNanos());
		this.sm.forceEnding(FightEndReason.DISCONNECT, ms(1400));
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.pendingReason(), "forceEnding keeps the death reason");
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(9999), OpponentPresence.ABSENT, new SelfState(true, true)));
	}

	@Test
	void forcedReasonsEndAtTheLastCombat() {
		this.sm.forceEnding(FightEndReason.DISCONNECT, ms(10));
		assertEquals(FightStateMachine.Phase.IDLE, this.sm.phase(), "nothing to end while IDLE");
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(A, ms(700), false, true);
		this.sm.forceEnding(FightEndReason.WORLD_CHANGE, ms(5000));
		assertEquals(FightEndReason.WORLD_CHANGE, this.sm.pendingReason());
		assertEquals(ms(700), this.sm.endNanos());
		this.sm.reset();
		assertEquals(FightStateMachine.Phase.IDLE, this.sm.phase());
		assertNull(this.sm.opponent());
		assertTrue(this.sm.onCombat(B, ms(9000), true, false), "a new fight opens after reset");
		assertFalse(this.sm.isEngaged(A.uuid()));
	}

	@Test
	void resultMapping() {
		for (FightEndReason r : FightEndReason.values()) {
			FightResult expected = r == FightEndReason.OPPONENT_DIED ? FightResult.WIN
					: r == FightEndReason.SELF_DIED ? FightResult.LOSS : FightResult.DRAW;
			assertEquals(expected, FightStateMachine.resultOf(r), r.name());
		}
	}

	@Test
	void ruleOrderDeathBeforeDistanceBeforeIdle() {
		this.sm.onCombat(A, ms(0), true, true);
		// Own death (rule 2) beats a far opponent (7) and idle (8).
		assertEquals(FightEndReason.SELF_DIED, this.sm.tick(ms(20000), new OpponentPresence(true, true, false, 40), new SelfState(true, false)));
		FightStateMachine far = new FightStateMachine(() -> this.adv);
		far.onCombat(A, ms(0), true, true);
		assertEquals(FightEndReason.OPPONENT_FAR, far.tick(ms(20000), new OpponentPresence(true, true, false, 40), ALIVE));
	}

	// ---- §5.13a: results without a death event ----------------------------------

	@Test
	void opponentVanishingRightAfterYourHitIsAWin() {
		this.sm.onCombat(A, ms(0), true, false);
		this.sm.onCombat(A, ms(400), true, true); // your hit is the last one
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(500), OpponentPresence.ABSENT, ALIVE));
		assertEquals("Opponent vanished right after your hit", this.sm.resultNote());
		assertEquals(ms(400), this.sm.endNanos(), "an inferred death ends at the last hit");
		assertFalse(this.sm.awaitingEvidence(ms(600)), "decided: nothing to wait for");
	}

	@Test
	void opponentTeleportedFarRightAfterYourHitIsAWin() {
		this.sm.onCombat(A, ms(0), true, true);
		OpponentPresence jumped = new OpponentPresence(true, true, false, 70.0, true, 1.0f);
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(300), jumped, ALIVE));
		assertEquals("Opponent was teleported away right after your hit", this.sm.resultNote());
	}

	@Test
	void vanishingAfterTheirHitOrOutsideTheWindowIsADraw() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(A, ms(300), true, false); // their hit is the last
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(400), OpponentPresence.ABSENT, ALIVE));
		assertNull(this.sm.resultNote());

		FightStateMachine late = fresh();
		late.onCombat(A, ms(0), true, true);
		assertEquals(FightEndReason.OPPONENT_LEFT, late.tick(ms(1501), OpponentPresence.ABSENT, ALIVE), "1501 > 1500");
		assertFalse(late.awaitingEvidence(ms(1600)), "no evidence wait outside the window");

		FightStateMachine provisional = fresh();
		provisional.onCombat(A, ms(0), false, true); // only whiffed: never engaged
		assertEquals(FightEndReason.OPPONENT_LEFT, provisional.tick(ms(100), OpponentPresence.ABSENT, ALIVE));
	}

	@Test
	void visibleHighHealthMeansTheOpponentLeft() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.noteOpponentHealthVisible(true);
		assertNull(this.sm.tick(ms(50), health(3.0, 0.9f), ALIVE));
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(100), OpponentPresence.ABSENT, ALIVE));

		FightStateMachine low = fresh();
		low.onCombat(A, ms(0), true, true);
		low.noteOpponentHealthVisible(true);
		assertNull(low.tick(ms(50), health(3.0, 0.2f), ALIVE));
		assertEquals(FightEndReason.OPPONENT_DIED, low.tick(ms(100), OpponentPresence.ABSENT, ALIVE));
	}

	@Test
	void anAbruptDrawWaitsForAKillMessage() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(A, ms(300), true, false); // their hit last: nothing to infer
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(400), OpponentPresence.ABSENT, ALIVE));
		assertTrue(this.sm.awaitingEvidence(ms(400)));
		assertTrue(this.sm.awaitingEvidence(ms(1399)));
		assertFalse(this.sm.awaitingEvidence(ms(1400)), "EVIDENCE_GRACE_MS = 1000");
		this.sm.onOutcomeHint(true, ms(900), "Read from chat: \"Kwamix was slain by builtdoor\"");
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.pendingReason());
		assertEquals("Read from chat: \"Kwamix was slain by builtdoor\"", this.sm.resultNote());
		assertFalse(this.sm.awaitingEvidence(ms(1000)), "decided: no more waiting");
		assertEquals(ms(300), this.sm.endNanos());

		FightStateMachine late = fresh();
		late.onCombat(A, ms(0), true, false);
		assertEquals(FightEndReason.OPPONENT_LEFT, late.tick(ms(100), OpponentPresence.ABSENT, ALIVE));
		late.onOutcomeHint(true, ms(1200), "chat");
		assertEquals(FightEndReason.OPPONENT_LEFT, late.pendingReason(), "the wait ran out at 1100");
	}

	@Test
	void aMessageWhileTheFightRunsDecidesItOutright() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onOutcomeHint(true, ms(200), "Read from the title: \"VICTORY!\"");
		assertEquals(FightStateMachine.Phase.ENDING, this.sm.phase());
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(250), NEAR, ALIVE));
		assertEquals("Read from the title: \"VICTORY!\"", this.sm.resultNote());

		FightStateMachine lost = fresh();
		lost.onCombat(A, ms(0), true, false);
		lost.onOutcomeHint(false, ms(100), "Read from chat: \"You died!\"");
		assertEquals(FightEndReason.SELF_DIED, lost.pendingReason());

		FightStateMachine whiffs = fresh();
		whiffs.onCombat(A, ms(0), false, true); // nobody engaged
		whiffs.onOutcomeHint(true, ms(100), "title");
		assertEquals(FightStateMachine.Phase.ACTIVE, whiffs.phase(), "a win needs an engaged opponent");

		FightStateMachine died = fresh();
		died.onCombat(A, ms(0), true, true);
		died.onDeath(true, SELF, ms(100));
		died.onOutcomeHint(true, ms(150), "title");
		assertEquals(FightEndReason.SELF_DIED, died.pendingReason(), "a real death is never overridden");
		assertNull(died.resultNote());
	}

	@Test
	void teleportedAwayAfterTheirHitWithLowHealthIsALoss() {
		OpponentPresence farNow = new OpponentPresence(true, true, false, 80.0);
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(A, ms(400), true, false);
		assertNull(this.sm.tick(ms(450), NEAR, selfAt(0.3f)));
		assertEquals(FightEndReason.SELF_DIED, this.sm.tick(ms(500), farNow, teleportedAt(1.0f)),
				"the health reset on the teleport tick does not hide the low health before it");
		assertEquals("You were teleported away right after their hit", this.sm.resultNote());

		FightStateMachine healthy = fresh();
		healthy.onCombat(A, ms(0), true, true);
		healthy.onCombat(A, ms(400), true, false);
		assertNull(healthy.tick(ms(450), NEAR, selfAt(0.9f)));
		assertEquals(FightEndReason.OPPONENT_FAR, healthy.tick(ms(500), farNow, teleportedAt(1.0f)),
				"not low: no death, the opponent is just far away now");
	}

	@Test
	void teleportedAwayAfterYourHitIsAWinUnlessItIsUnclear() {
		OpponentPresence farNow = new OpponentPresence(true, true, false, 80.0);
		this.sm.onCombat(A, ms(0), true, false);
		this.sm.onCombat(A, ms(400), true, true);
		assertNull(this.sm.tick(ms(450), NEAR, selfAt(0.8f)));
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.tick(ms(500), farNow, teleportedAt(1.0f)));
		assertEquals("You were teleported away right after your hit", this.sm.resultNote());

		// You were low too, and a server that cancels the killing blow never shows it: it may have been theirs.
		FightStateMachine close = fresh();
		close.onCombat(A, ms(0), true, false);
		close.onCombat(A, ms(400), true, true);
		assertNull(close.tick(ms(450), NEAR, selfAt(0.2f)));
		assertEquals(FightEndReason.OPPONENT_FAR, close.tick(ms(500), farNow, teleportedAt(1.0f)));

		// ... unless their synced health was still high: then it was you.
		FightStateMachine outclassed = fresh();
		outclassed.onCombat(A, ms(0), true, false);
		outclassed.onCombat(A, ms(400), true, true);
		outclassed.noteOpponentHealthVisible(true);
		assertNull(outclassed.tick(ms(450), health(3.0, 0.9f), selfAt(0.2f)));
		assertEquals(FightEndReason.SELF_DIED, outclassed.tick(ms(500), health(80.0, 0.9f), teleportedAt(1.0f)));
	}

	@Test
	void worldChangeRightAfterAHitDecidesTheResult() {
		this.sm.onCombat(A, ms(0), true, false);
		this.sm.onCombat(A, ms(400), true, true);
		assertNull(this.sm.tick(ms(450), NEAR, selfAt(0.9f)));
		this.sm.forceEnding(FightEndReason.WORLD_CHANGE, ms(600));
		assertEquals(FightEndReason.OPPONENT_DIED, this.sm.pendingReason());
		assertEquals("You changed world right after your hit", this.sm.resultNote());

		FightStateMachine respawned = fresh();
		respawned.onCombat(A, ms(0), true, true);
		respawned.onCombat(A, ms(400), true, false);
		assertNull(respawned.tick(ms(450), NEAR, selfAt(0.1f)));
		respawned.forceEnding(FightEndReason.WORLD_CHANGE, ms(600));
		assertEquals(FightEndReason.SELF_DIED, respawned.pendingReason());

		FightStateMachine unclear = fresh();
		unclear.onCombat(A, ms(0), true, true);
		unclear.onCombat(A, ms(400), true, false);
		assertNull(unclear.tick(ms(450), NEAR, selfAt(0.9f)));
		unclear.forceEnding(FightEndReason.WORLD_CHANGE, ms(600));
		assertEquals(FightEndReason.WORLD_CHANGE, unclear.pendingReason());
		assertTrue(unclear.awaitingEvidence(ms(600)), "a world change right after a hit waits for a message");
		unclear.onOutcomeHint(false, ms(700), "chat");
		assertEquals(FightEndReason.SELF_DIED, unclear.pendingReason());
	}

	@Test
	void portalTravelIsNeverAResult() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.onCombat(A, ms(400), true, false);
		assertNull(this.sm.tick(ms(450), NEAR, new SelfState(false, false, false, 0.1f, true)));
		this.sm.forceEnding(FightEndReason.WORLD_CHANGE, ms(600));
		assertEquals(FightEndReason.WORLD_CHANGE, this.sm.pendingReason(), "escaping through a portal at low health");
		assertNull(this.sm.resultNote());

		FightStateMachine later = fresh();
		later.onCombat(A, ms(0), true, true);
		assertNull(later.tick(ms(100), NEAR, new SelfState(false, false, false, 0.1f, true)));
		later.onCombat(A, ms(2500), true, false);
		assertNull(later.tick(ms(2550), NEAR, selfAt(0.1f)));
		later.forceEnding(FightEndReason.WORLD_CHANGE, ms(2700));
		assertEquals(FightEndReason.SELF_DIED, later.pendingReason(), "2600 ms after the portal: PORTAL_GRACE_MS = 2000");
	}

	@Test
	void inferenceAndTheEvidenceWaitCanBeSwitchedOff() {
		this.adv.inferOutcomes = false;
		this.adv.readOutcomeMessages = false;
		this.sm.onCombat(A, ms(0), true, true);
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(100), OpponentPresence.ABSENT, ALIVE));
		assertFalse(this.sm.awaitingEvidence(ms(100)));
	}

	@Test
	void outcomeWindowIsReadLive() {
		this.adv.outcomeWindowMs = 500;
		this.sm.onCombat(A, ms(0), true, true);
		assertEquals(FightEndReason.OPPONENT_LEFT, this.sm.tick(ms(501), OpponentPresence.ABSENT, ALIVE));
	}

	@Test
	void resetForgetsTheResultNote() {
		this.sm.onCombat(A, ms(0), true, true);
		this.sm.tick(ms(100), OpponentPresence.ABSENT, ALIVE);
		assertNotNull(this.sm.resultNote());
		this.sm.reset();
		assertNull(this.sm.resultNote());
		assertTrue(this.sm.onCombat(A, ms(5000), true, true));
		assertNull(this.sm.resultNote());
	}
}
