package kwamster.core.fight;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import kwamster.SampleFights;
import kwamster.core.event.DamageObservedEvent;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.event.TradeEvent;
import kwamster.core.io.Json;
import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.HitQuality;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import kwamster.core.stats.StatsCalculator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.9 incremental record building: UUID keying, engagement, primary opponent, origin, sanitising, finish. */
final class FightAccumulatorTest {
	private static final long START = 2_000_000_000L;
	private static final PlayerRef KWAMIX = SampleFights.KWAMIX;
	private static final PlayerRef RIVAL = SampleFights.ref("Rival");
	private static final PlayerRef THIRD = SampleFights.ref("Third");
	private static final Pos ORIGIN = new Pos(100.0, 64.0, -50.0);

	private FightAccumulator acc;

	@BeforeEach
	void setUp() {
		this.acc = new FightAccumulator(START, SampleFights.START_EPOCH_MS, SampleFights.SELF, KWAMIX,
				"duels.example.net", FightRecord.Settings.defaults(), ORIGIN);
	}

	private static long at(int ms) {
		return START + ms * 1_000_000L;
	}

	private static SwingEvent swing(int t, PlayerRef target, boolean landed) {
		return swing(t, target, landed, new Pos(101.5, 64.0, -48.0), new Pos(103.0, 64.0, -47.0));
	}

	private static SwingEvent swing(int t, PlayerRef target, boolean landed, Pos self, Pos opp) {
		AimSample aim = new AimSample(0.2, 0.5, landed ? AimZone.HIGH : null, false);
		return new SwingEvent(at(t), true, 30, target, landed, false, 2.8, 2.9, 3.0, false, aim,
				1f, true, true, false, false, false, self, opp, 45f, 40);
	}

	private static HitTakenEvent taken(int t, PlayerRef attacker) {
		return new HitTakenEvent(at(t), 0, attacker, false, 2.7, true, false, ORIGIN, new Pos(102, 64, -50));
	}

	@Test
	void opponentsAreKeyedByUuidAndKeepTheFirstName() {
		assertEquals(0, this.acc.opponentIndex(KWAMIX));
		assertEquals(1, this.acc.opponentIndex(RIVAL));
		assertEquals(0, this.acc.opponentIndex(new PlayerRef(KWAMIX.uuid(), "Kwamix_Renamed")));
		FightRecord r = this.acc.view();
		assertEquals(List.of("Kwamix", "Rival"), r.opponents);
		assertEquals(List.of(KWAMIX.uuid().toString(), RIVAL.uuid().toString()), r.opponentUuids);
		assertEquals(-1, this.acc.indexOf(THIRD.uuid()));
		assertEquals(-1, this.acc.indexOf(null));
	}

	@Test
	void engagementComesFromLandedHitsAndHitsTakenOnly() {
		this.acc.addSwing(swing(0, RIVAL, false), null);
		FightRecord r = this.acc.view();
		assertEquals(List.of(false, false), r.engaged, "the opening target and a whiffed passer-by stay provisional");
		this.acc.addSwing(swing(100, RIVAL, true), HitQuality.CRIT);
		assertEquals(List.of(false, true), r.engaged);
		this.acc.addHitTaken(taken(200, THIRD));
		assertEquals(List.of(false, true, true), r.engaged);
		// A projectile hit is not recorded here and engages nobody.
		this.acc.addHitTaken(new HitTakenEvent(at(300), 0, KWAMIX, true, 10, false, false, ORIGIN, ORIGIN));
		assertEquals(List.of(false, true, true), r.engaged);
		assertEquals(1, r.hitsTaken.size());
	}

	@Test
	void primaryOpponentIsTheMostActiveEngagedOne() {
		FightRecord r = this.acc.view();
		assertEquals("Kwamix", r.opponentName, "index 0 while nobody is engaged");
		this.acc.addSwing(swing(0, KWAMIX, false), null);
		this.acc.addSwing(swing(10, KWAMIX, false), null);
		this.acc.addSwing(swing(20, RIVAL, true), null);
		assertEquals("Rival", r.opponentName, "misses never count; Rival is the only engaged one");
		this.acc.addHitTaken(taken(30, THIRD));
		assertEquals("Rival", r.opponentName, "a 1–1 tie goes to the lower index");
		this.acc.addHitTaken(taken(40, THIRD));
		assertEquals("Third", r.opponentName);
		assertEquals(THIRD.uuid().toString(), r.opponentUuid);
	}

	@Test
	void positionsAreRelativeToTheOrigin() {
		this.acc.addSwing(swing(0, KWAMIX, true), null);
		FightRecord.Swing s = this.acc.view().swings.get(0);
		assertEquals(1.5f, s.sx, 1e-4);
		assertEquals(2.0f, s.sz, 1e-4);
		assertEquals(3.0f, s.ox, 1e-4);
		assertEquals(3.0f, s.oz, 1e-4);
		assertEquals(AimZone.HIGH.name(), s.zone);
		assertEquals(30, s.clickLeadMs);
		assertTrue(s.clickTimed);
	}

	@Test
	void landedSwingWithoutAQualityIsGradedWithTheSnapshot() {
		FightAccumulator custom = new FightAccumulator(START, 0L, SampleFights.SELF, KWAMIX, "",
				copyWith(2.80f), Pos.ZERO);
		custom.addSwing(swing(0, KWAMIX, true), null); // charged crit at 2.8
		assertEquals(HitQuality.THREE_BLOCK_CRIT.name(), custom.view().swings.get(0).quality);
		custom.addSwing(swing(10, KWAMIX, false), null);
		assertEquals(null, custom.view().swings.get(1).quality);
		assertEquals(null, custom.view().swings.get(1).zone);
	}

	private static FightRecord.Settings copyWith(float threeBlock) {
		FightRecord.Settings s = FightRecord.Settings.defaults();
		s.threeBlockReach = threeBlock;
		return s;
	}

	@Test
	void damageDropsNeverAddOpponents() {
		this.acc.addDamage(new DamageObservedEvent(at(100), SampleFights.SELF.uuid(), true, 3.0), -1);
		this.acc.addDamage(new DamageObservedEvent(at(200), THIRD.uuid(), false, 2.0), -1);
		this.acc.addDamage(new DamageObservedEvent(at(300), THIRD.uuid(), false, 2.0), 7);
		this.acc.addDamage(new DamageObservedEvent(at(400), KWAMIX.uuid(), false, 1.5), 0);
		FightRecord r = this.acc.view();
		assertEquals(1, r.opponents.size());
		assertEquals(2, r.damage.size());
		assertTrue(r.damage.get(0).self);
		assertEquals(-1, r.damage.get(0).opp);
		assertEquals(0, r.damage.get(1).opp);
		assertEquals(1.5f, r.damage.get(1).amount, 0f);
	}

	@Test
	void nonFiniteInputsAreSanitisedAndTheRecordStillSerialises() {
		AimSample nanAim = new AimSample(Double.NaN, Double.POSITIVE_INFINITY, null, false);
		Pos inf = new Pos(Double.POSITIVE_INFINITY, 0, Double.NaN);
		this.acc.addSwing(new SwingEvent(at(0), false, 0, KWAMIX, true, false, Double.NaN, Double.POSITIVE_INFINITY,
				Double.NaN, false, nanAim, Float.NaN, true, false, false, false, true, inf, inf, Float.NaN, 0), null);
		this.acc.addSwing(new SwingEvent(at(5), false, 0, KWAMIX, false, false, 1e300, Double.NaN,
				-1, false, nanAim, 0.5f, false, false, false, false, true, inf, inf, 0f, 0), null);
		this.acc.addHitTaken(new HitTakenEvent(at(10), 0, KWAMIX, false, Double.NaN, true, false, inf, inf));
		this.acc.addTrade(new TradeEvent(at(20), KWAMIX, Double.NaN, 3.0, true, Double.POSITIVE_INFINITY, true));
		this.acc.addDamage(new DamageObservedEvent(at(30), SampleFights.SELF.uuid(), true, Double.NaN), -1);
		FightRecord r = this.acc.view();
		FightRecord.Swing s = r.swings.get(0);
		assertEquals(0f, s.reach, 0f);
		assertEquals(-1f, s.rayReach, 0f);
		assertEquals(3.0f, s.rangeLimit, 0f, "an unusable range limit falls back to the vanilla 3.0");
		assertEquals(0f, s.aimH, 0f);
		assertEquals(0f, s.aimV, 0f);
		assertEquals(0f, s.charge, 0f);
		assertEquals(0f, s.sx, 0f);
		assertEquals(0f, s.sz, 0f);
		assertEquals(0f, s.yaw, 0f);
		FightRecord.Swing big = r.swings.get(1);
		assertEquals(0f, big.reach, 0f, "1e300 is not finite as a float");
		assertEquals(3.0f, big.rangeLimit, 0f);
		assertEquals(0f, r.hitsTaken.get(0).reach, 0f);
		FightRecord.Trade t = r.trades.get(0);
		assertFalse(t.damageKnown);
		assertEquals(0f, t.dealt, 0f);
		assertFalse(t.momentumKnown);
		assertEquals(0f, t.momentumPct, 0f);
		assertEquals(0f, r.damage.get(0).amount, 0f);

		FightRecord done = this.acc.finish(at(40), FightResult.DRAW, FightEndReason.IDLE, "test");
		done.stats = StatsCalculator.compute(done, StatsCalculator.FINAL);
		assertDoesNotThrow(() -> Json.GSON.toJson(done));
	}

	@Test
	void finishSetsResultReasonDurationAndId() {
		this.acc.addSwing(swing(0, KWAMIX, true), null);
		this.acc.addPingSample(40);
		this.acc.addPingSample(0); // unknown: ignored
		this.acc.addPingSample(61);
		assertEquals(61, this.acc.latestPingMs());
		FightRecord r = this.acc.finish(START + 12_345_678_901L, FightResult.WIN, FightEndReason.OPPONENT_DIED, "1.0.0+1.21.11");
		assertEquals(12_345, r.durationMs);
		assertEquals("WIN", r.result);
		assertEquals("OPPONENT_DIED", r.endReason);
		assertEquals("1.0.0+1.21.11", r.modVersion);
		assertEquals(51, r.pingMs, "round((40 + 61) / 2)");
		assertEquals("20261020-153012-123-Kwamix", r.id);
		assertEquals("duels.example.net", r.server);
		assertEquals(SampleFights.SELF.name(), r.playerName);
		// Frozen afterwards.
		this.acc.addSwing(swing(50, KWAMIX, true), null);
		assertEquals(1, r.swings.size());
		assertTrue(this.acc.finished());
		// An end before the start never gives a negative duration.
		FightAccumulator early = new FightAccumulator(START, 0L, SampleFights.SELF, KWAMIX, "", null, null);
		assertEquals(0, early.finish(START - 5_000_000L, null, null, null).durationMs);
	}

	@Test
	void timesAreFlooredAndListsStaySorted() {
		this.acc.addJumpReset(new JumpResetEvent(START - 120_500_000L, START + 1_000_000L, 0, -50, -121, false, KWAMIX));
		this.acc.addSwing(swing(500, KWAMIX, true), null);
		this.acc.addSwing(new SwingEvent(START + 199_999_999L, false, 0, KWAMIX, false, false, 3.2, Double.NaN, 3.0,
				false, AimSample.NONE, 0.5f, false, false, false, false, true, ORIGIN, ORIGIN, 0f, 0), null);
		FightRecord r = this.acc.view();
		assertEquals(-121, r.jumps.get(0).t, "a jump just before the opening event has a negative t (floor)");
		assertEquals(199, r.swings.get(0).t, "out-of-order arrival is inserted in t order");
		assertEquals(500, r.swings.get(1).t);
		assertEquals(199, this.acc.relMs(START + 199_999_999L));
	}

	@Test
	void gsonRoundTripIsFieldForField() {
		FightRecord r = SampleFights.example();
		String json = Json.GSON.toJson(r);
		FightRecord back = Json.GSON.fromJson(json, FightRecord.class);
		// Compare parsed trees (key order independent) and the exact text (same field order and float digits).
		JsonElement expected = JsonParser.parseString(json);
		assertEquals(expected, JsonParser.parseString(Json.GSON.toJson(back)));
		assertEquals(json, Json.GSON.toJson(back));
		assertEquals(r.swings.size(), back.swings.size());
		assertEquals(r.stats.landed, back.stats.landed);

		// And a record built by the accumulator itself.
		this.acc.addSwing(swing(0, KWAMIX, true), HitQuality.THREE_BLOCK_CRIT);
		this.acc.addHitTaken(taken(100, RIVAL));
		this.acc.addTrade(new TradeEvent(at(120), RIVAL, 3.0, 4.0, true, -12.5, true));
		FightRecord done = this.acc.finish(at(200), FightResult.LOSS, FightEndReason.SELF_DIED, "x");
		done.stats = StatsCalculator.compute(done, StatsCalculator.FINAL);
		String j2 = Json.GSON.toJson(done);
		assertEquals(j2, Json.GSON.toJson(Json.GSON.fromJson(j2, FightRecord.class)));
	}
}
