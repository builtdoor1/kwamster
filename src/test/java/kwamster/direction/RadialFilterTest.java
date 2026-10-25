package kwamster.direction;

import kwamster.SampleFights;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.StoplightReading;
import kwamster.core.model.StoplightState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static kwamster.core.model.StoplightState.GREEN;
import static kwamster.core.model.StoplightState.OFF;
import static kwamster.core.model.StoplightState.RED;
import static kwamster.core.model.StoplightState.YELLOW;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §5.11 stoplight filter with the default settings: enter 1.0 b/s, hysteresis 0.5, smoothing 150 ms
 * (α = 1 − e^(−1/3) ≈ 0.283), minimum hold 150 ms, knockback hold 300 ms. You stand at the origin; the
 * opponent's position is fed once per 50 ms tick.
 */
final class RadialFilterTest {
	private static final UUID TARGET = SampleFights.KWAMIX.uuid();
	private static final long TICK = 50_000_000L;

	private RadialFilter filter;
	private KwamsterConfig.Direction cfg;
	private long tick;
	private double ox;
	private double oz;

	@BeforeEach
	void setUp() {
		this.filter = new RadialFilter();
		this.cfg = new KwamsterConfig.Direction();
		this.tick = 0;
	}

	/** Places the opponent and feeds the first (seeding) sample. */
	private StoplightState seed(double x, double z) {
		this.ox = x;
		this.oz = z;
		return feed(false);
	}

	/** Moves the opponent by (dx, dz) blocks this tick and feeds it. */
	private StoplightState step(double dx, double dz) {
		this.ox += dx;
		this.oz += dz;
		return feed(false);
	}

	private StoplightState feed(boolean freshHit) {
		StoplightState s = this.filter.update(TARGET, 7, this.ox, this.oz, 0, 0, freshHit, this.tick * TICK, this.cfg);
		this.tick++;
		return s;
	}

	/** One tick of radial motion at {@code bps} blocks per second (+ = toward the origin) from (0, z). */
	private StoplightState radial(double bps) {
		double len = Math.hypot(this.ox, this.oz);
		double d = bps / 20.0;
		return step(-this.ox / len * d, -this.oz / len * d);
	}

	@Test
	void approachTurnsGreenWithinFourTicks() {
		assertEquals(YELLOW, seed(0, 12), "a reseed starts YELLOW");
		int ticks = 0;
		StoplightState s = YELLOW;
		while (s != GREEN && ticks < 10) {
			s = radial(4.3);
			ticks++;
		}
		assertEquals(GREEN, s);
		assertTrue(ticks <= 4, "GREEN after " + ticks + " ticks (2 settling ticks + 1 sample)");
		assertTrue(this.filter.approachBps() > 1.0);

		// From a settled standstill it is quicker still.
		RadialFilter settled = new RadialFilter();
		this.filter = settled;
		this.tick = 0;
		seed(0, 12);
		for (int i = 0; i < 10; i++) {
			assertEquals(YELLOW, step(0, 0));
		}
		assertEquals(GREEN, radial(4.3), "ema = 0.283 · 4.3 = 1.22 ≥ 1.0 on the first sample");
	}

	@Test
	void standingIsYellow() {
		seed(0, 5);
		for (int i = 0; i < 40; i++) {
			assertEquals(YELLOW, step(0, 0));
		}
		assertEquals(0.0, this.filter.approachBps(), 0.0);
		StoplightReading r = this.filter.reading(SampleFights.KWAMIX);
		assertEquals(YELLOW, r.state());
		assertSame(SampleFights.KWAMIX, r.target());
	}

	@Test
	void retreatIsRed() {
		seed(0, 4);
		StoplightState s = YELLOW;
		for (int i = 0; i < 6; i++) {
			s = radial(-4.3);
		}
		assertEquals(RED, s);
		assertTrue(this.filter.approachBps() < -1.0);
	}

	@Test
	void pureStrafeIsYellow() {
		// Circle around you at 3 blocks, 4.3 b/s along the tangent: the radial part of each one-tick chord
		// is −r(1 − cos Δθ)·20 ≈ −0.15 b/s, far below the 1.0 threshold.
		double r = 3.0;
		double dTheta = (4.3 / 20.0) / r;
		seed(0, r);
		for (int i = 1; i <= 60; i++) {
			double theta = i * dTheta;
			this.ox = r * Math.sin(theta);
			this.oz = r * Math.cos(theta);
			assertEquals(YELLOW, feed(false), "tick " + i);
		}
		assertTrue(Math.abs(this.filter.approachBps()) < 0.3);
	}

	@Test
	void oscillationAroundTheThresholdDoesNotFlicker() {
		seed(0, 40);
		StoplightState prev = YELLOW;
		int changes = 0;
		boolean wasGreen = false;
		for (int i = 0; i < 200; i++) {
			StoplightState s = radial(i % 2 == 0 ? 1.2 : 0.8);
			if (s != prev) {
				changes++;
			}
			wasGreen |= s == GREEN;
			prev = s;
		}
		assertTrue(wasGreen, "the average reaches the 1.0 threshold");
		assertEquals(1, changes, "YELLOW → GREEN once, then hysteresis holds it");

		// Control: without hysteresis and minimum hold the same input does flicker.
		this.filter = new RadialFilter();
		this.tick = 0;
		this.cfg.hysteresisBps = 0f;
		this.cfg.minHoldMs = 0;
		seed(0, 40);
		prev = YELLOW;
		changes = 0;
		for (int i = 0; i < 200; i++) {
			StoplightState s = radial(i % 2 == 0 ? 1.2 : 0.8);
			if (s != prev) {
				changes++;
			}
			prev = s;
		}
		assertTrue(changes > 20, "control flickers " + changes + " times");
	}

	@Test
	void teleportReseedsWithoutASpike() {
		seed(0, 12);
		for (int i = 0; i < 10; i++) {
			radial(4.3);
		}
		assertEquals(GREEN, this.filter.state());
		long teleportAt = this.tick * TICK;
		assertEquals(YELLOW, step(0, 10), "10 blocks in one tick is a teleport, not a 200 b/s retreat");
		assertEquals(0.0, this.filter.approachBps(), 0.0);
		assertTrue(this.filter.settling());
		assertEquals(teleportAt, this.filter.sinceNanos());
		assertEquals(YELLOW, step(0, 0));
		assertEquals(YELLOW, step(0, 0));
		assertFalse(this.filter.settling());
		for (int i = 0; i < 10; i++) {
			radial(-4.3);
			assertTrue(Math.abs(this.filter.approachBps()) <= 4.3 + 1e-9, "no spike: " + this.filter.approachBps());
		}
		assertEquals(RED, this.filter.state());
	}

	@Test
	void knockbackHoldFreezesTheState() {
		seed(0, 10);
		for (int i = 0; i < 10; i++) {
			radial(4.3);
		}
		assertEquals(GREEN, this.filter.state());
		double emaBefore = this.filter.approachBps();
		// Hit: the opponent flies away at 8 b/s. The hold covers samples within 300 ms of the hit.
		this.ox += 0;
		this.oz += 0.4;
		assertEquals(GREEN, feed(true));
		assertTrue(this.filter.knockbackHold());
		for (int i = 0; i < 5; i++) {
			assertEquals(GREEN, radial(-8.0), "frozen at hold tick " + (i + 1));
			assertTrue(this.filter.knockbackHold());
			assertEquals(emaBefore, this.filter.approachBps(), 0.0, "the average is not updated during the hold");
		}
		// 300 ms after the hit the hold ends and the average restarts from the raw sample.
		assertEquals(RED, radial(-8.0));
		assertFalse(this.filter.knockbackHold());
		assertEquals(this.filter.rawBps(), this.filter.approachBps(), 1e-12);
		assertEquals(-8.0, this.filter.approachBps(), 1e-9);
	}

	@Test
	void zeroSmoothingPassesTheRawValue() {
		this.cfg.smoothingMs = 0;
		assertEquals(1.0, RadialFilter.alpha(0), 0.0);
		seed(0, 12);
		step(0, 0);
		step(0, 0);
		for (int i = 0; i < 5; i++) {
			radial(4.3);
			assertEquals(4.3, this.filter.approachBps(), 1e-9);
			assertEquals(this.filter.rawBps(), this.filter.approachBps(), 0.0);
		}
		assertEquals(1.0 - Math.exp(-1.0 / 3.0), RadialFilter.alpha(150), 1e-12);
	}

	@Test
	void freshHitDetection() {
		assertTrue(RadialFilter.isFreshHit(10, 0, 10));
		assertTrue(RadialFilter.isFreshHit(10, 9, 10));
		assertTrue(RadialFilter.isFreshHit(10, 10, 10), "pinned at hurtDuration by rapid hits");
		assertFalse(RadialFilter.isFreshHit(9, 10, 10));
		assertFalse(RadialFilter.isFreshHit(0, 0, 0));
	}

	@Test
	void identityChangeAndResetReseed() {
		seed(0, 10);
		for (int i = 0; i < 10; i++) {
			radial(4.3);
		}
		assertEquals(GREEN, this.filter.state());
		assertEquals(YELLOW, this.filter.update(TARGET, 8, this.ox, this.oz, 0, 0, false, this.tick++ * TICK, this.cfg),
				"a new entity id (respawn) reseeds");
		assertEquals(YELLOW, this.filter.update(UUID.randomUUID(), 8, this.ox, this.oz, 0, 0, false, this.tick++ * TICK, this.cfg),
				"a new target reseeds");
		this.filter.reset();
		assertEquals(OFF, this.filter.state());
		assertSame(StoplightReading.NONE, this.filter.reading(SampleFights.KWAMIX));
	}

	@Test
	void hysteresisRule() {
		assertEquals(GREEN, RadialFilter.classify(YELLOW, 1.0, 1.0, 0.5));
		assertEquals(YELLOW, RadialFilter.classify(YELLOW, 0.99, 1.0, 0.5));
		assertEquals(GREEN, RadialFilter.classify(GREEN, 0.5, 1.0, 0.5), "exit = 1.0 − 0.5");
		assertEquals(YELLOW, RadialFilter.classify(GREEN, 0.49, 1.0, 0.5));
		assertEquals(RED, RadialFilter.classify(RED, -0.5, 1.0, 0.5));
		assertEquals(RED, RadialFilter.classify(GREEN, -1.0, 1.0, 0.5));
		assertEquals(0.5, RadialFilter.exitBps(1.0, 0.5), 1e-12);
		assertEquals(RadialFilter.MIN_EXIT_BPS, RadialFilter.exitBps(1.0, 2.0), 1e-12);
		assertEquals(4.3, RadialFilter.radialBps(0, -0.215, 0, -3), 1e-12);
		assertEquals(0.0, RadialFilter.radialBps(0.215, 0, 0, -3), 1e-12);
	}
}
