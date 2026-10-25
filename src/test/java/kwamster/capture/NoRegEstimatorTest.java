package kwamster.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.4 NO-REG estimation. The unit tests pin the two rules; the model tests replay a vanilla client and
 * server, tick by tick, under latency:
 * <ul>
 *   <li>The client sends an attack 1 ms into its tick ({@code handleKeybinds}). The server handles queued
 *       packets at the start of its tick, restarts the victim's timer (20) on a registered hit, ignores hits
 *       while the timer is above 10 (so a hit registers {@code ≥ 10} server ticks after the last one), and
 *       flushes the damage event 5 ms into the tick.</li>
 *   <li>The client handles that damage event at the next frame boundary after it arrives, never inside a
 *       tick: then its copy of the timer is 20, it is read by the next tick's {@code startAttack}, and
 *       counted down after it ({@code tickEntities} follows {@code handleKeybinds}).</li>
 *   <li>One-way latency is half the ping, plus optional jitter; each direction stays in order (TCP).</li>
 * </ul>
 * The old rule (client copy above 10) is replayed alongside to show the model reproduces the defect.
 */
final class NoRegEstimatorTest {
	private static final UUID VICTIM = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-000000000002");
	private static final int WINDOW = NoRegEstimator.DEFAULT_IMMUNITY_TICKS;
	private static final int[] PINGS = {0, 50, 100, 150};
	/** (client phase, server phase) in ms: where in 50 ms each side's ticks start. */
	private static final double[][] PHASES = {{0, 0}, {0, 25}, {17, 40}, {33, 7}, {49, 48}};

	private NoRegEstimator est;

	@BeforeEach
	void setUp() {
		this.est = new NoRegEstimator();
	}

	// ---- unit rules --------------------------------------------------------------

	@Test
	void firstHitRegistersAndAnchorsTheWindow() {
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 80, WINDOW));
		assertTrue(this.est.onLandedSwing(VICTIM, 105, 0, 80, WINDOW), "5 ticks later is inside the window");
		assertTrue(this.est.onLandedSwing(VICTIM, 109, 0, 80, WINDOW), "9 ticks after the anchor");
		assertFalse(this.est.onLandedSwing(VICTIM, 110, 0, 80, WINDOW), "10 ticks after the anchor registers");
		assertTrue(this.est.onLandedSwing(VICTIM, 112, 0, 80, WINDOW), "flagged swings never move the anchor");
	}

	@Test
	void twoSwingsInOneTickFlagTheSecond() {
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 0, WINDOW));
		assertTrue(this.est.onLandedSwing(VICTIM, 100, 0, 0, WINDOW));
	}

	@Test
	void victimsAreTrackedSeparately() {
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 0, WINDOW));
		assertFalse(this.est.onLandedSwing(OTHER, 101, 0, 0, WINDOW));
		assertTrue(this.est.onLandedSwing(VICTIM, 102, 0, 0, WINDOW));
	}

	@Test
	void rawClientCopyIsIgnoredAfterYourOwnHit() {
		// Your hit registered at 100; its damage event came back at 103 and set the client copy to 20.
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 100, WINDOW));
		this.est.onDamage(VICTIM, true, 103, 100);
		// At 111 the copy still reads 12 (> 10), but the server has counted 11 ticks: it registers.
		assertFalse(this.est.onLandedSwing(VICTIM, 111, 12, 100, WINDOW));
	}

	@Test
	void someoneElsesDamageUsesTheLaggedCopy() {
		// lagTicks(100 ms) = 3: the server is 3 ticks further along than the copy says.
		assertEquals(3, NoRegEstimator.lagTicks(100));
		this.est.onDamage(VICTIM, false, 200, 100);
		assertTrue(this.est.onLandedSwing(VICTIM, 203, 17, 100, WINDOW), "about 6 server ticks after the damage");
		this.est.reset();
		this.est.onDamage(VICTIM, false, 200, 100);
		assertFalse(this.est.onLandedSwing(VICTIM, 208, 13, 100, WINDOW), "about 10 server ticks after the damage");
		this.est.reset();
		// Never damaged as far as we know, timer at 0: nothing to go on, registers.
		assertFalse(this.est.onLandedSwing(VICTIM, 300, 0, 100, WINDOW));
	}

	@Test
	void yourProjectileCountsAsSomeoneElsesDamage() {
		// Your arrow (ownMelee = false) restarts the server timer without anchoring the melee rule.
		this.est.onDamage(VICTIM, false, 400, 50);
		assertTrue(this.est.onLandedSwing(VICTIM, 402, 19, 50, WINDOW));
	}

	@Test
	void yourSweepOnABystanderArmsTheSecondaryRule() {
		// A sweep from your hit on someone else damaged OTHER (a direct player_attack source from you), but you
		// never landed a swing on OTHER: no anchor explains it, so it restarted OTHER's timer unseen.
		this.est.onDamage(OTHER, true, 100, 50);
		assertTrue(this.est.onLandedSwing(OTHER, 104, 16, 50, WINDOW), "about 6 server ticks after the sweep");
		assertFalse(this.est.onLandedSwing(OTHER, 112, 8, 50, WINDOW), "about 14 server ticks after it");
	}

	@Test
	void yourDamageLongAfterTheAnchorIsNotTheAnchors() {
		// Registered hit on VICTIM at 100; much later a sweep of yours restarts its timer.
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 50, WINDOW));
		this.est.onDamage(VICTIM, true, 102, 50);
		this.est.onDamage(VICTIM, true, 140, 50);
		assertTrue(this.est.onLandedSwing(VICTIM, 143, 17, 50, WINDOW), "the primary rule alone would pass this");
	}

	@Test
	void yourOwnDamageComingBackLateStillBelongsToTheAnchor() {
		// lagTicks(100) = 3, plus 6 ticks of slack: an event 9 ticks after the anchor is still the anchor's.
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 100, WINDOW));
		this.est.onDamage(VICTIM, true, 109, 100);
		assertFalse(this.est.onLandedSwing(VICTIM, 110, 19, 100, WINDOW), "the lagged copy is ignored");
	}

	@Test
	void zeroWindowDisablesDetection() {
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 0, 0, 0));
		assertFalse(this.est.onLandedSwing(VICTIM, 100, 20, 0, 0));
		this.est.onDamage(VICTIM, false, 100, 0);
		assertFalse(this.est.onLandedSwing(VICTIM, 101, 20, 0, 0));
	}

	@Test
	void lagTicksFormula() {
		assertEquals(1, NoRegEstimator.lagTicks(0));
		assertEquals(1, NoRegEstimator.lagTicks(24));
		assertEquals(2, NoRegEstimator.lagTicks(50));
		assertEquals(3, NoRegEstimator.lagTicks(100));
		assertEquals(4, NoRegEstimator.lagTicks(150));
		assertEquals(1, NoRegEstimator.lagTicks(-5), "unknown ping counts as 0");
	}

	@Test
	void staleVictimsAreForgotten() {
		for (int i = 0; i < 20; i++) {
			this.est.onDamage(new UUID(0L, i), false, 0, 0);
		}
		this.est.onDamage(VICTIM, true, NoRegEstimator.FORGET_TICKS + 10, 0);
		assertEquals(1, this.est.tracked());
	}

	// ---- tick model: your own hits ------------------------------------------------

	@Test
	void chargedCombosAreNeverFlaggedAtAnyPing() {
		for (int ping : PINGS) {
			for (int spacing : new int[] {11, 12, 13}) {
				for (double[] phase : PHASES) {
					List<Swing> r = new TickModel(ping, phase[0], phase[1], 0.0, 1L).run(spaced(1000, spacing, 20), new long[0]);
					for (Swing s : r) {
						assertTrue(s.registered, "model sanity: every hit " + spacing + " ticks apart registers");
					}
					assertEquals(0, errors(r), "ping " + ping + ", spacing " + spacing + ", phase " + phase[0] + "/" + phase[1]);
				}
			}
		}
	}

	@Test
	void theOldRawRuleFlagsRegisteredCombosOnline() {
		// The defect the estimator replaces: at 100 ms, 11-tick combos read as NO-REG from the second hit on.
		for (double[] phase : PHASES) {
			List<Swing> r = new TickModel(100, phase[0], phase[1], 0.0, 1L).run(spaced(1000, 11, 20), new long[0]);
			assertTrue(legacyErrors(r) >= 15, "legacy misflags at phase " + phase[0] + "/" + phase[1] + ": " + legacyErrors(r));
			assertEquals(0, errors(r));
		}
	}

	@Test
	void spamClickingMatchesTheServerExactlyWithoutJitter() {
		for (int ping : PINGS) {
			for (double[] phase : PHASES) {
				for (long[] pattern : spamPatterns()) {
					List<Swing> r = new TickModel(ping, phase[0], phase[1], 0.0, 7L).run(pattern, new long[0]);
					assertEquals(0, errors(r), "ping " + ping + ", phase " + phase[0] + "/" + phase[1]);
					assertTrue(registered(r) < r.size(), "spam must contain swings into immunity");
				}
			}
		}
	}

	@Test
	void twentyCpsRegistersEveryTenTicks() {
		List<Swing> r = new TickModel(100, 0, 25, 0.0, 1L).run(spaced(1000, 1, 61), new long[0]);
		assertEquals(7, registered(r), "ticks 1000, 1010, ..., 1060");
		int flagged = 0;
		for (Swing s : r) {
			flagged += s.flagged ? 1 : 0;
		}
		assertEquals(54, flagged);
	}

	@Test
	void jitterNeverBreaksCombosAndRarelyMisjudgesSpam() {
		// Up to 20 ms of extra one-way delay per packet, at every server phase in 5 ms steps, so packets
		// regularly cross a server tick boundary and the server sees a gap one tick off the client's.
		int spamSwings = 0;
		int spamErrors = 0;
		int spamLegacy = 0;
		int boundaryShifts = 0;
		long seed = 11L;
		for (int ping : PINGS) {
			for (double clientPhase : new double[] {0, 23}) {
				for (double serverPhase = 0; serverPhase < 50; serverPhase += 5) {
					for (int spacing : new int[] {11, 12, 13}) {
						List<Swing> r = new TickModel(ping, clientPhase, serverPhase, 20.0, seed++)
								.run(spaced(1000, spacing, 20), new long[0]);
						assertEquals(0, errors(r), "combo with jitter, ping " + ping + ", spacing " + spacing);
					}
					for (long[] pattern : spamPatterns()) {
						List<Swing> r = new TickModel(ping, clientPhase, serverPhase, 20.0, seed++).run(pattern, new long[0]);
						spamSwings += r.size();
						spamErrors += errors(r);
						spamLegacy += legacyErrors(r);
						boundaryShifts += serverGapShifts(r);
					}
				}
			}
		}
		assertTrue(boundaryShifts > 0, "model sanity: jitter must move some packets across a server tick");
		// Measured: about 4.5 % (only swings exactly at the 9/10-tick edge can flip), against about 18 % for
		// the old rule, whose errors are systematic rather than edge cases.
		assertTrue(spamErrors * 12 <= spamSwings, "spam misjudged " + spamErrors + "/" + spamSwings);
		assertTrue(spamErrors * 3 < spamLegacy, "estimator " + spamErrors + " vs old rule " + spamLegacy);
	}

	// ---- tick model: someone else's damage ------------------------------------------

	@Test
	void thirdPartyImmunityIsCaughtAfterItsDamageEventArrives() {
		long thirdParty = 1000L;
		for (int ping : PINGS) {
			for (double[] phase : PHASES) {
				for (long k = 995; k <= 1020; k++) {
					// A fresh replay per swing: one hit by someone else, then one swing of yours.
					List<Swing> r = new TickModel(ping, phase[0], phase[1], 0.0, 3L).run(new long[] {k}, new long[] {thirdParty});
					Swing s = r.get(0);
					if (s.copy <= 0 || s.serverElapsed < 0) {
						continue; // the damage event had not arrived yet: nothing on the client can know
					}
					String where = "ping " + ping + ", phase " + phase[0] + "/" + phase[1] + ", " + s.serverElapsed + " server ticks";
					if (s.serverElapsed <= 7) {
						assertTrue(s.flagged, "clearly immune: " + where);
						assertFalse(s.registered);
					} else if (s.serverElapsed >= 12) {
						assertFalse(s.flagged, "clearly free: " + where);
						assertTrue(s.registered);
					}
				}
			}
		}
	}

	// ---- model -------------------------------------------------------------------------

	private static long[] spaced(long first, int spacing, int count) {
		long[] t = new long[count];
		for (int i = 0; i < count; i++) {
			t[i] = first + (long) i * spacing;
		}
		return t;
	}

	/** 20 CPS, 10 CPS, double clicks every third tick, and a seeded mix of 1–4 tick gaps with double clicks. */
	private static List<long[]> spamPatterns() {
		List<long[]> out = new ArrayList<>();
		out.add(spaced(1000, 1, 60));
		out.add(spaced(1000, 2, 40));
		long[] doubles = new long[40];
		for (int i = 0; i < 40; i++) {
			doubles[i] = 1000 + (i / 2) * 3L;
		}
		out.add(doubles);
		Random rng = new Random(42L);
		long[] mixed = new long[80];
		long t = 1000;
		for (int i = 0; i < mixed.length; i++) {
			mixed[i] = t;
			if (rng.nextInt(5) != 0) {
				t += 1 + rng.nextInt(4);
			}
		}
		out.add(mixed);
		return out;
	}

	private static int errors(List<Swing> r) {
		int n = 0;
		for (Swing s : r) {
			n += s.flagged == s.registered ? 1 : 0;
		}
		return n;
	}

	private static int legacyErrors(List<Swing> r) {
		int n = 0;
		for (Swing s : r) {
			n += s.legacyFlagged == s.registered ? 1 : 0;
		}
		return n;
	}

	/** Swings for which the server saw a different gap to the last registered hit than the client did. */
	private static int serverGapShifts(List<Swing> r) {
		int n = 0;
		long lastRegTick = Long.MIN_VALUE;
		for (Swing s : r) {
			if (lastRegTick != Long.MIN_VALUE && s.serverElapsed >= 0 && s.serverElapsed != s.tick - lastRegTick) {
				n++;
			}
			if (s.registered) {
				lastRegTick = s.tick;
			}
		}
		return n;
	}

	private static int registered(List<Swing> r) {
		int n = 0;
		for (Swing s : r) {
			n += s.registered ? 1 : 0;
		}
		return n;
	}

	/**
	 * One replayed swing.
	 *
	 * @param serverElapsed server ticks since the last registered hit (yours or anyone's) when the server
	 *                      handled this swing, -1 if there was none
	 */
	private record Swing(long tick, int copy, boolean flagged, boolean legacyFlagged, boolean registered,
			long serverElapsed) {
	}

	/** A deterministic replay of one victim's hurt immunity on a vanilla client and server. */
	private static final class TickModel {
		private static final double TICK = 50.0;
		private static final double KEYBIND_OFFSET = 1.0;
		private static final double SERVER_FLUSH = 5.0;

		private final int pingMs;
		private final double clientPhase;
		private final double serverPhase;
		private final double jitterMs;
		private final Random rng;

		TickModel(int pingMs, double clientPhase, double serverPhase, double jitterMs, long seed) {
			this.pingMs = pingMs;
			this.clientPhase = clientPhase;
			this.serverPhase = serverPhase;
			this.jitterMs = jitterMs;
			this.rng = new Random(seed);
		}

		private double jitter() {
			return this.jitterMs <= 0 ? 0.0 : this.rng.nextDouble() * this.jitterMs;
		}

		/** A server-side hurt attempt: yours (index into the swings) or a third party's. */
		private record Hurt(long serverTick, int order, int swing) {
		}

		/** A damage event on its way to the client. */
		private record Arrival(double time, boolean ownMelee) {
		}

		List<Swing> run(long[] swingTicks, long[] thirdPartyServerTicks) {
			int n = swingTicks.length;
			// 1. Upstream: when the server handles each attack packet (in order, start of a server tick).
			List<Hurt> hurts = new ArrayList<>();
			double lastArrival = Double.NEGATIVE_INFINITY;
			for (int i = 0; i < n; i++) {
				double send = swingTicks[i] * TICK + this.clientPhase + KEYBIND_OFFSET;
				double arrive = Math.max(lastArrival, send + this.pingMs / 2.0 + jitter());
				lastArrival = arrive;
				long serverTick = (long) Math.ceil((arrive - this.serverPhase) / TICK - 1e-9);
				hurts.add(new Hurt(serverTick, 1, i));
			}
			for (long x : thirdPartyServerTicks) {
				hurts.add(new Hurt(x, 0, -1)); // processed before your packets in that tick
			}
			hurts.sort((a, b) -> a.serverTick() != b.serverTick() ? Long.compare(a.serverTick(), b.serverTick())
					: a.order() != b.order() ? Integer.compare(a.order(), b.order()) : Integer.compare(a.swing(), b.swing()));

			// 2. Server: vanilla immunity, and the damage events it sends back.
			boolean[] registered = new boolean[n];
			long[] elapsed = new long[n];
			List<Arrival> arrivals = new ArrayList<>();
			Long lastHurt = null;
			double lastDown = Double.NEGATIVE_INFINITY;
			for (Hurt h : hurts) {
				long since = lastHurt == null ? -1 : h.serverTick() - lastHurt;
				boolean reg = lastHurt == null || since >= WINDOW;
				if (h.swing() >= 0) {
					registered[h.swing()] = reg;
					elapsed[h.swing()] = since;
				}
				if (reg) {
					lastHurt = h.serverTick();
					double sent = h.serverTick() * TICK + this.serverPhase + SERVER_FLUSH;
					double arrive = Math.max(lastDown, sent + this.pingMs / 2.0 + jitter());
					lastDown = arrive;
					arrivals.add(new Arrival(arrive, h.swing() >= 0));
				}
			}

			// 3. Client: damage events between ticks, swings in handleKeybinds, then the countdown.
			NoRegEstimator estimator = new NoRegEstimator();
			List<Swing> out = new ArrayList<>(n);
			long firstTick = Long.MAX_VALUE;
			long lastTick = Long.MIN_VALUE;
			for (long k : swingTicks) {
				firstTick = Math.min(firstTick, k);
				lastTick = Math.max(lastTick, k);
			}
			for (long x : thirdPartyServerTicks) {
				firstTick = Math.min(firstTick, x - 5);
			}
			int copy = 0;
			int nextArrival = 0;
			int nextSwing = 0;
			for (long k = firstTick - 20; k <= lastTick; k++) {
				// Packets handled at frame boundaries before tick k: the last tick started was k - 1.
				while (nextArrival < arrivals.size() && arrivalTick(arrivals.get(nextArrival)) <= k - 1) {
					Arrival a = arrivals.get(nextArrival++);
					estimator.onDamage(VICTIM, a.ownMelee(), k - 1, this.pingMs);
					copy = NoRegEstimator.HURT_TICKS;
				}
				while (nextSwing < n && swingTicks[nextSwing] == k) {
					boolean flagged = estimator.onLandedSwing(VICTIM, k, copy, this.pingMs, WINDOW);
					out.add(new Swing(k, copy, flagged, copy > WINDOW, registered[nextSwing], elapsed[nextSwing]));
					nextSwing++;
				}
				if (copy > 0) {
					copy--;
				}
			}
			return out;
		}

		/** The last client tick started before the event is handled (never inside a tick). */
		private long arrivalTick(Arrival a) {
			return (long) Math.floor((a.time() - this.clientPhase) / TICK);
		}
	}
}
