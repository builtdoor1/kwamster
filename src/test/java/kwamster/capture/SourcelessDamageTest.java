package kwamster.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Attribution of sourceless damage events (pre-1.19.4 servers through ViaVersion). The DamageSource shape
 * check itself (no cause, no direct entity, no position, type generic) needs a damage-type registry and
 * lives in CombatCapture; these pin the MC-free decisions behind it.
 */
final class SourcelessDamageTest {
	private static final UUID KWAMIX = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID RIVAL = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	private static final UUID BYSTANDER = UUID.fromString("00000000-0000-4000-8000-00000000000c");
	private static final long MS = 1_000_000L;

	private SourcelessDamage damage;

	@BeforeEach
	void setUp() {
		this.damage = new SourcelessDamage();
	}

	private static SourcelessDamage.Candidate swingingAt(UUID who, double reach, int swingTime) {
		return new SourcelessDamage.Candidate(who, reach, true, swingTime);
	}

	@Test
	void aSwingThatJustStartedQualifies() {
		assertTrue(SourcelessDamage.swungRecently(true, -1), "the animate packet came in this frame");
		assertTrue(SourcelessDamage.swungRecently(true, 0));
		assertTrue(SourcelessDamage.swungRecently(true, 2));
		assertFalse(SourcelessDamage.swungRecently(true, 3));
		assertFalse(SourcelessDamage.swungRecently(false, 0));
	}

	@Test
	void nobodySwingingNearbyMeansTheEventIsDropped() {
		// Fall, cactus or fire damage: nobody near you swung.
		assertEquals(-1, SourcelessDamage.pickAttacker(List.of(), null));
		assertEquals(-1, SourcelessDamage.pickAttacker(List.of(
				new SourcelessDamage.Candidate(KWAMIX, 2.0, false, 0),
				swingingAt(RIVAL, 6.0, 0),
				swingingAt(BYSTANDER, 3.0, 4)), null));
	}

	@Test
	void theNearestSwingingPlayerIsPicked() {
		int i = SourcelessDamage.pickAttacker(List.of(
				swingingAt(BYSTANDER, 4.2, 1),
				swingingAt(RIVAL, 2.6, -1),
				swingingAt(KWAMIX, 3.4, 0)), null);
		assertEquals(1, i);
	}

	@Test
	void theFightOpponentWinsWhenTheyQualify() {
		List<SourcelessDamage.Candidate> c = List.of(swingingAt(RIVAL, 2.0, 0), swingingAt(KWAMIX, 3.9, 2));
		assertEquals(1, SourcelessDamage.pickAttacker(c, KWAMIX));
		// ...but not when they do not.
		List<SourcelessDamage.Candidate> far = List.of(swingingAt(RIVAL, 2.0, 0), swingingAt(KWAMIX, 4.6, 0));
		assertEquals(0, SourcelessDamage.pickAttacker(far, KWAMIX));
	}

	@Test
	void reachLimitIsInclusiveAndNaNNeverQualifies() {
		assertEquals(0, SourcelessDamage.pickAttacker(List.of(swingingAt(RIVAL, SourcelessDamage.MAX_ATTACKER_REACH, 0)), null));
		assertEquals(-1, SourcelessDamage.pickAttacker(List.of(swingingAt(RIVAL, Double.NaN, 0)), null));
	}

	@Test
	void confirmationNeedsYourRecentRegisteredSwing() {
		long t = 10_000L * MS;
		assertFalse(this.damage.claimOwnHit(RIVAL, t, 80), "you never hit them");
		this.damage.onRegisteredSwing(RIVAL, t);
		assertFalse(this.damage.claimOwnHit(RIVAL, t - MS, 80), "damage before your swing is not yours");
		assertFalse(this.damage.claimOwnHit(KWAMIX, t + 100 * MS, 80), "someone else's damage");
		assertTrue(this.damage.claimOwnHit(RIVAL, t + 120 * MS, 80), "one round trip later");
		this.damage.onRegisteredSwing(RIVAL, t);
		assertTrue(this.damage.claimOwnHit(RIVAL, t + 380 * MS, 80), "ping + 300 ms is the edge");
		this.damage.onRegisteredSwing(RIVAL, t);
		assertFalse(this.damage.claimOwnHit(RIVAL, t + 381 * MS, 80));
		this.damage.reset();
		assertFalse(this.damage.claimOwnHit(RIVAL, t + 100 * MS, 80));
	}

	@Test
	void oneRegisteredSwingConfirmsOneEvent() {
		// Your hit comes back, then a fire tick or a teammate's hit restarts the timer a few ticks later.
		long t = 10_000L * MS;
		this.damage.onRegisteredSwing(RIVAL, t);
		assertTrue(this.damage.claimOwnHit(RIVAL, t + 110 * MS, 80));
		assertFalse(this.damage.claimOwnHit(RIVAL, t + 250 * MS, 80), "the swing is spent");
		// A newer registered swing can confirm again.
		this.damage.onRegisteredSwing(RIVAL, t + 600 * MS);
		assertTrue(this.damage.claimOwnHit(RIVAL, t + 700 * MS, 80));
	}

	@Test
	void anEventSoonerThanARoundTripIsNotYoursAndLeavesTheSwing() {
		// At 120 ms ping your own event needs about 120 ms; one 20 ms after the swing was caused before it
		// reached the server (another player's hit), so it must not take your confirmation.
		long t = 10_000L * MS;
		this.damage.onRegisteredSwing(RIVAL, t);
		assertFalse(this.damage.claimOwnHit(RIVAL, t + 20 * MS, 120));
		assertTrue(this.damage.claimOwnHit(RIVAL, t + 140 * MS, 120), "your own event still confirms");
	}

	@Test
	void minimumAgeIsLenientWithTheTabListPing() {
		assertEquals(0, SourcelessDamage.minConfirmAgeMs(0), "unknown ping: no lower bound");
		assertEquals(0, SourcelessDamage.minConfirmAgeMs(40));
		assertEquals(10, SourcelessDamage.minConfirmAgeMs(60), "ping - 50");
		assertEquals(50, SourcelessDamage.minConfirmAgeMs(100));
		assertEquals(100, SourcelessDamage.minConfirmAgeMs(200), "half the ping above 100 ms");
		assertEquals(0, SourcelessDamage.minConfirmAgeMs(-5));
	}

	// ---- knockback corroboration (burning, poisoned or withered) -----------------------------

	@Test
	void aMeleeKnockbackAwayFromTheAttackerCounts() {
		// You stood still (ground gravity only); the attacker is west of you; the velocity packet pushes you east.
		assertTrue(SourcelessDamage.showsKnockback(0, -0.0784, 0, 0.4, 0.36, 0.0, 1.0, 0.0));
		// Sprinting at the attacker: half your motion is kept, the push still wins clearly.
		assertTrue(SourcelessDamage.showsKnockback(-0.28, -0.0784, 0, 0.26, 0.36, 0.0, 3.0, 0.0));
		// Falling fast: little or no upward motion, but the horizontal push is there.
		assertTrue(SourcelessDamage.showsKnockback(0.0, -0.9, 0.0, 0.0, -0.05, 0.4, 0.0, 2.0));
	}

	@Test
	void aFireTickVelocityPacketDoesNotCount() {
		// The server's own idea of your motion: no push, maybe some residue, and upward after a jump.
		assertFalse(SourcelessDamage.showsKnockback(0.12, -0.0784, 0.05, 0.0, -0.0784, 0.0, 1.0, 0.0));
		assertFalse(SourcelessDamage.showsKnockback(0.2, 0.3, 0.0, 0.05, 0.33, 0.0, 1.0, 0.0), "upward alone is no knockback");
		// Chasing the opponent while burning: the server residue points at them, not away.
		assertFalse(SourcelessDamage.showsKnockback(-0.25, 0.1, 0.0, -0.18, 0.0, 0.0, 1.0, 0.0));
	}

	@Test
	void noVelocityChangeMeansNoKnockback() {
		assertFalse(SourcelessDamage.showsKnockback(0.5, 0.2, 0.0, 0.5, 0.2, 0.0, 1.0, 0.0),
				"already moving away, but no packet arrived");
		assertFalse(SourcelessDamage.showsKnockback(0, 0, 0, Double.NaN, 0, 0, 1.0, 0.0));
	}

	@Test
	void anAttackerOnTopOfYouIsJudgedBySpeedAlone() {
		assertTrue(SourcelessDamage.showsKnockback(0, -0.0784, 0, 0.0, 0.36, -0.4, 0.0, 0.0));
		assertFalse(SourcelessDamage.showsKnockback(0.1, -0.0784, 0, 0.02, -0.0784, 0.03, 1e-4, 0.0));
	}
}
