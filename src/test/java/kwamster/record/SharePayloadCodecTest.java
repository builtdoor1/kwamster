package kwamster.record;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import kwamster.SampleFights;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.ShareLink;
import kwamster.core.stats.HitGrader;
import kwamster.core.stats.StatsCalculator;
import kwamster.record.SharePayloadCodec.Content;
import kwamster.record.SharePayloadCodec.Decoded;
import kwamster.record.SharePayloadCodec.Event;
import kwamster.record.SharePayloadCodec.Options;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static kwamster.record.SharePayloadCodec.FLAG_DAMAGE;
import static kwamster.record.SharePayloadCodec.FLAG_DOWNSAMPLED;
import static kwamster.record.SharePayloadCodec.FLAG_MULTI;
import static kwamster.record.SharePayloadCodec.FLAG_POSITIONS;
import static kwamster.record.SharePayloadCodec.FLAG_SERVER;
import static kwamster.record.SharePayloadCodec.KIND_DEATH;
import static kwamster.record.SharePayloadCodec.KIND_HIT;
import static kwamster.record.SharePayloadCodec.KIND_JUMP;
import static kwamster.record.SharePayloadCodec.KIND_MISS;
import static kwamster.record.SharePayloadCodec.KIND_TAKEN;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Share payload v1 (§9.2–9.3, §9.6): primitives, round trips within quantisation (reach rounded down),
 * every flag combination, the opponent table, the NO-REG bit, tie order, reserved kinds, code-point-safe
 * names, out-of-range fields and the inflated-size cap, and the golden vectors that
 * {@code tools/viewer-check.mjs} decodes with the viewer's JavaScript (plus {@code rejected/*.txt}, which
 * both decoders must refuse).
 */
final class SharePayloadCodecTest {
	/** Where the golden vectors go (§9.6); Gradle runs tests in the project directory. */
	static final Path VECTOR_DIR = Path.of("build", "share-vectors");
	/** {@code Decoded} as JSON with nulls kept, so the JS side's {@code server: null} has a counterpart. */
	static final Gson VECTOR_GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().setPrettyPrinting().create();

	// ---- primitives ------------------------------------------------------------------------------

	@Test
	void varIntAndZigzagEdgeValues() {
		assertBytes(new int[] {0x00}, new SharePayloadCodec.Writer().varInt(0));
		assertBytes(new int[] {0x7F}, new SharePayloadCodec.Writer().varInt(127));
		assertBytes(new int[] {0x80, 0x01}, new SharePayloadCodec.Writer().varInt(128));
		assertBytes(new int[] {0x80, 0x80, 0x80, 0x80, 0x08}, new SharePayloadCodec.Writer().varLong(1L << 31));
		assertBytes(new int[] {0x01}, new SharePayloadCodec.Writer().zig(-1));
		assertBytes(new int[] {0x7F}, new SharePayloadCodec.Writer().zig(-64));
		assertBytes(new int[] {0x81, 0x01}, new SharePayloadCodec.Writer().zig(-65));
		assertBytes(new int[] {0x80, 0x01}, new SharePayloadCodec.Writer().zig(64));
		assertBytes(new int[] {0x00}, new SharePayloadCodec.Writer().zig(0));

		SharePayloadCodec.Writer w = new SharePayloadCodec.Writer()
				.varInt(0).varInt(127).varInt(128).varLong(1L << 31).zig(-1).zig(-64).zig(-65).zig(0)
				.varInt(Integer.MAX_VALUE).zig(Long.MIN_VALUE).u8(255).i8(-128).i8(127);
		SharePayloadCodec.Reader r = new SharePayloadCodec.Reader(w.toByteArray());
		assertEquals(0, r.varInt());
		assertEquals(127, r.varInt());
		assertEquals(128, r.varInt());
		assertEquals(1L << 31, r.varLong());
		assertEquals(-1, r.zigInt());
		assertEquals(-64, r.zigInt());
		assertEquals(-65, r.zigInt());
		assertEquals(0, r.zigInt());
		assertEquals(Integer.MAX_VALUE, r.varInt());
		assertEquals(Long.MIN_VALUE, r.zig());
		assertEquals(255, r.u8());
		assertEquals(-128, r.i8());
		assertEquals(127, r.i8());
		assertEquals(0, r.remaining());

		// 2^31 does not fit a varInt field, and unsigned fields reject negatives on write.
		assertThrows(IllegalArgumentException.class,
				() -> new SharePayloadCodec.Reader(new SharePayloadCodec.Writer().varLong(1L << 31).toByteArray()).varInt());
		assertThrows(IllegalArgumentException.class, () -> new SharePayloadCodec.Writer().varInt(-1));
		assertThrows(IllegalArgumentException.class, () -> new SharePayloadCodec.Writer().u8(256));
		assertThrows(IllegalArgumentException.class, () -> new SharePayloadCodec.Reader(new byte[] {(byte) 0x80}).varInt());
	}

	private static void assertBytes(int[] expected, SharePayloadCodec.Writer w) {
		byte[] actual = w.toByteArray();
		assertEquals(expected.length, actual.length);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(expected[i], actual[i] & 0xFF, "byte " + i);
		}
	}

	@Test
	void quantisers() {
		assertEquals(118, SharePayloadCodec.reachQ(2.95));
		assertEquals(118, SharePayloadCodec.reachQ(2.95f), "float widening");
		assertEquals(118, SharePayloadCodec.reachQ(2.95 - 5e-7), "inside the 3-block tolerance");
		assertEquals(117, SharePayloadCodec.reachQ(2.9499), "rounded down: never back as 2.95");
		assertEquals(117, SharePayloadCodec.reachQ(2.9376));
		assertEquals(118, SharePayloadCodec.reachQ(2.974));
		assertEquals(119, SharePayloadCodec.reachQ(2.975));
		assertEquals(254, SharePayloadCodec.reachQ(6.374));
		assertEquals(255, SharePayloadCodec.reachQ(6.375));
		assertEquals(0, SharePayloadCodec.reachQ(-1));
		assertEquals(255, SharePayloadCodec.reachQ(10));
		assertEquals(0, SharePayloadCodec.reachQ(Double.NaN));
		assertEquals(295, SharePayloadCodec.reachCm(2.95));
		assertEquals(295, SharePayloadCodec.reachCm(2.95f));
		assertEquals(294, SharePayloadCodec.reachCm(2.9499));
		assertEquals(283, SharePayloadCodec.reachCm(2.836));
		assertEquals(0, SharePayloadCodec.reachCm(-1));
		assertEquals(0, SharePayloadCodec.reachCm(Double.NaN));
		assertEquals(127, SharePayloadCodec.aimQ(1.9));
		assertEquals(-127, SharePayloadCodec.aimQ(-3));
		assertEquals(-35, SharePayloadCodec.aimQ(-0.354));
		assertEquals(0, SharePayloadCodec.bearing(1, 0));
		assertEquals(64, SharePayloadCodec.bearing(0, 1));
		assertEquals(128, SharePayloadCodec.bearing(-1, 0));
		assertEquals(192, SharePayloadCodec.bearing(0, -1));
		assertEquals(30, SharePayloadCodec.distDm(1.8, 2.4));
		assertEquals(2000, SharePayloadCodec.distDm(500, 0));
	}

	/** The viewer's reach text for a reachQ (docs/index.html {@code reachQText}): whole cm, rounded down. */
	static double viewerReach(int reachQ) {
		return Math.floor(reachQ * 5 / 2.0) / 100.0;
	}

	/**
	 * A reach the viewer shows as at least the 3-block threshold was graded 3-block (it never overstates),
	 * and for thresholds on the 1/40 grid the converse holds too.
	 */
	@Test
	void viewerReachNeverOverstatesTheThreeBlockThreshold() {
		for (double threshold : new double[] {2.5, 2.9, 2.93, 2.95, 2.97, 3.0}) {
			boolean onGrid = Math.abs(threshold * 40 - Math.rint(threshold * 40)) < 1e-9;
			for (int i = -20000; i <= 20000; i++) {
				double reach = threshold + i * 2.5e-6;
				boolean three = HitGrader.grade(true, false, false, reach, threshold, false).threeBlock();
				boolean shownAtLeast = viewerReach(SharePayloadCodec.reachQ(reach)) >= threshold - 1e-9;
				if (shownAtLeast) {
					assertTrue(three, "shown " + viewerReach(SharePayloadCodec.reachQ(reach)) + " for reach " + reach + " < " + threshold);
				}
				if (onGrid) {
					assertEquals(three, shownAtLeast, "reach " + reach + " vs " + threshold);
				}
			}
		}
		assertEquals(2.95, viewerReach(118));
		assertEquals(2.92, viewerReach(117));
		assertEquals(2.97, viewerReach(119), "2.975 shows as 2.97, like StatFormat.reach(2.975)");
	}

	/** An over-reach miss (and an over-reach miss maximum) never decodes to a figure at or inside the range. */
	@Test
	void overReachMissesNeverDecodeInsideTheRange() {
		FightRecord r = SampleFights.script()
				.miss(100, 3.004)
				.miss(200, 3.4299)
				.miss(300, 2.9499)
				.miss(400, 3.0)
				.duration(400).finish();
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(r, true), Options.FULL));
		List<Decoded.Ev> ev = d.events();
		assertEquals(1, ev.get(0).arg(), "3.004 is over the 3.0 range");
		assertEquals(121, ev.get(0).reachQ(), "one step above the range's 120");
		assertEquals(3.02, viewerReach(ev.get(0).reachQ()));
		assertEquals(137, ev.get(1).reachQ(), "far enough over: still rounded down");
		assertEquals(117, ev.get(2).reachQ(), "inside the range: rounded down");
		assertEquals(0, ev.get(3).arg(), "at the range is not over it");
		assertEquals(120, ev.get(3).reachQ());
		assertEquals(342, d.summary()[9], "the longest miss is far over: plain cm");

		FightRecord close = SampleFights.script().miss(100, 3.004).miss(200, 2.5).duration(200).finish();
		Decoded dc = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(close, true), Options.FULL));
		assertEquals(301, dc.summary()[9], "the longest miss is 3.004 against 3.00: never 300 next to 1 over reach");
		assertEquals(1, dc.summary()[10]);
		assertEquals(300, SharePayloadCodec.reachCmVsRange(3.0, 3.0));
		assertEquals(250, SharePayloadCodec.reachCmVsRange(2.5, 0.0));
		for (int i = 1; i <= 4000; i++) {
			double limit = 2.0 + i * 0.0005;
			double reach = limit + 1e-7 * i;
			assertTrue(viewerReach(SharePayloadCodec.reachQ(SharePayloadCodec.overReachShown(reach, limit))) > viewerReach(SharePayloadCodec.reachQ(limit)),
					reach + " over " + limit);
			assertTrue(SharePayloadCodec.reachCmVsRange(reach, limit) > SharePayloadCodec.reachCm(limit));
		}
	}

	// ---- round trip --------------------------------------------------------------------------------

	@Test
	void exampleRoundTripsWithinQuantisation() {
		FightRecord r = SampleFights.example();
		Content c = SharePayloadCodec.content(r, true);
		byte[] payload = SharePayloadCodec.encode(c, Options.FULL);
		assertArrayEquals(payload, SharePayloadCodec.payloadOf(SharePayloadCodec.fragment(payload)));
		assertArrayEquals(payload, SharePayloadCodec.payloadOf("#" + SharePayloadCodec.fragment(payload)));
		Decoded d = SharePayloadCodec.decode(payload);

		assertEquals(1, d.version());
		assertEquals(FLAG_POSITIONS | FLAG_DAMAGE | FLAG_SERVER, d.flags());
		assertEquals("builtdoor", d.player());
		assertEquals(List.of("Kwamix"), d.opponents());
		assertEquals(SampleFights.EXAMPLE_SERVER, d.server());
		assertEquals(r.startEpochMs / 1000L, d.startEpochSec());
		assertEquals(r.durationMs, d.durationMs());
		assertEquals(FightResult.WIN.code(), d.result());
		assertEquals(FightEndReason.OPPONENT_DIED.code, d.endReason());
		assertEquals(42, d.pingMs());
		assertEquals(295, d.threeBlockCm());
		assertEquals(0, d.jumpMinMs());
		assertEquals(80, d.jumpMaxMs());
		assertEquals(200, d.jumpAttemptMs());
		assertEquals(2000, d.comboTimeoutMs());
		assertEquals(3000, d.advantageWindowMs());
		// The summary is exact and over every event.
		assertArrayEquals(SharePayloadCodec.summaryOf(r.stats, true), d.summary());
		assertEquals(44, d.summary().length);
		assertEquals(r.stats.swings, d.summary()[0]);
		assertEquals(r.stats.landed, d.summary()[1]);
		assertEquals(r.stats.hitsTaken, d.summary()[4]);
		assertEquals(SharePayloadCodec.reachCm(r.stats.reachAvg), d.summary()[6], "reach cm are rounded down");
		assertEquals((long) Math.floor((r.stats.reachMax + 1e-6) * 100), d.summary()[7]);
		assertEquals((long) Math.floor((r.stats.missReachMax + 1e-6) * 100), d.summary()[9]);
		assertEquals(r.stats.immuneHits, d.summary()[39]);
		assertEquals(Math.round(r.stats.damageDealt * 10), d.summary()[42]);
		// 70 swings + 24 taken + 10 jumps + 1 death.
		assertEquals(105, c.events().size());
		assertEquals(105, d.totalEvents());
		assertEquals(105, d.events().size());
		assertFalse(d.truncated());
		assertEventsClose(c, d, true);
		Decoded.Ev last = d.events().get(d.events().size() - 1);
		assertEquals(KIND_DEATH, last.kind());
		assertEquals(0, last.arg(), "WIN: the opponent died");
	}

	/** Every decoded event matches its source event within the §12.2 quantisation bounds. */
	static void assertEventsClose(Content c, Decoded d, boolean positions) {
		assertEquals(c.events().size(), d.events().size());
		for (int i = 0; i < d.events().size(); i++) {
			assertEventClose(c.events().get(i), d.events().get(i), positions, c.multi(), "event " + i);
		}
	}

	static void assertEventClose(Event e, Decoded.Ev v, boolean positions, boolean multi, String where) {
		assertEquals(e.kind(), v.kind(), where + " kind");
		assertEquals(e.arg() & 7, v.arg(), where + " arg");
		assertEquals(e.kind() == KIND_HIT && e.noReg(), v.noReg(), where + " noReg");
		boolean hasOpp = e.kind() <= KIND_TAKEN || (e.kind() == KIND_DEATH && (e.arg() & 1) == 0);
		assertEquals(multi && hasOpp ? e.opp() : 0, v.opp(), where + " opp");
		assertTrue(Math.abs(v.tCs() * 10 - Math.max(0, e.t())) <= 5, where + " t " + e.t() + " vs " + v.tCs());
		if (e.kind() == KIND_HIT || e.kind() == KIND_MISS || e.kind() == KIND_TAKEN) {
			if (e.reach() < 6.375) {
				// Rounded down: the decoded reach is at most the real one and less than a 1/40 step below it.
				double back = v.reachQ() / 40.0;
				assertTrue(back <= e.reach() + 1e-6 && e.reach() - back < 0.025, where + " reach " + e.reach() + " vs " + back);
			}
		} else {
			assertEquals(-1, v.reachQ(), where + " reachQ absent");
		}
		if (e.kind() == KIND_HIT || e.kind() == KIND_MISS) {
			assertAim(e.aimH(), v.aimH(), where + " aimH");
			assertAim(e.aimV(), v.aimV(), where + " aimV");
		}
		assertEquals(e.kind() == KIND_JUMP ? e.deltaMs() : 0, v.deltaMs(), where + " deltaMs");
		boolean pos = positions && e.kind() <= KIND_TAKEN;
		assertEquals(pos, v.hasPos(), where + " hasPos");
		if (pos) {
			double sx = v.sxDm() / 10.0;
			double sz = v.szDm() / 10.0;
			assertEquals(e.sx(), sx, 0.05 + 1e-6, where + " sx");
			assertEquals(e.sz(), sz, 0.05 + 1e-6, where + " sz");
			double theta = v.bearing() * 2 * Math.PI / 256;
			double dist = v.distDm() / 10.0;
			double ox = sx + dist * Math.cos(theta);
			double oz = sz + dist * Math.sin(theta);
			double trueDist = Math.hypot(e.ox() - e.sx(), e.oz() - e.sz());
			double bound = 0.0708 + 0.05 + trueDist * Math.PI / 256 + 1e-6;
			assertTrue(Math.hypot(ox - e.ox(), oz - e.oz()) <= bound, where + " opponent position");
		} else {
			assertEquals(0, v.sxDm());
			assertEquals(0, v.bearing());
			assertEquals(0, v.distDm());
		}
	}

	private static void assertAim(double exact, int q, String where) {
		if (Math.abs(exact) <= 1.27) {
			assertEquals(exact, q / 100.0, 0.005 + 1e-9, where);
		} else {
			assertEquals(exact > 0 ? 127 : -127, q, where);
		}
	}

	// ---- flags ---------------------------------------------------------------------------------------

	/**
	 * A small fight for the flag matrix: HIT, MISS, TAKEN, JUMP and a WIN death. With {@code multi} a
	 * second engaged opponent (Rival) gets events too; {@code damage} adds visible health drops;
	 * {@code server} sets a shareable host name.
	 */
	static FightRecord flagFight(boolean damage, boolean server, boolean multi) {
		SampleFights.Script s = SampleFights.script()
				.hit(120, 2.97, x -> {
					x.crit = true;
					x.aimH = 0.31f;
					x.aimV = 0.62f;
					x.sx = 0.4f;
					x.sz = -0.2f;
					x.ox = 2.1f;
					x.oz = 1.9f;
				})
				.miss(480, 3.62, x -> {
					x.aimH = -1.6f;
					x.aimV = 0.2f;
					x.sx = 0.9f;
					x.ox = 3.6f;
					x.oz = 1.0f;
				})
				.taken(700, true, x -> {
					x.sx = 1.25f;
					x.sz = 0.35f;
					x.ox = -1.2f;
					x.oz = 2.0f;
				})
				.jump(760, 1)
				.hit(1340, 2.41, x -> {
					x.sprint = true;
					x.aimH = -0.55f;
					x.sx = 1.0f;
					x.sz = 0.4f;
					x.ox = 3.4f;
					x.oz = 0.4f;
				})
				.duration(1800)
				.result(FightResult.WIN, FightEndReason.OPPONENT_DIED)
				.with(r -> r.pingMs = 57);
		if (multi) {
			s.opponent(SampleFights.ref("Rival"), true)
					.hit(900, 2.66, x -> {
						x.opp = 1;
						x.aimV = -0.4f;
						x.ox = -2.0f;
						x.oz = -1.5f;
					})
					.taken(1100, false, x -> x.opp = 1);
		}
		if (damage) {
			s.drop(200, false, 0, 4.5).drop(760, true, -1, 3.0);
		}
		if (server) {
			s.with(r -> r.server = "play.example.net");
		}
		return s.finish();
	}

	@Test
	void everyFlagCombinationRoundTrips() {
		for (int bits = 0; bits < 16; bits++) {
			boolean positions = (bits & 1) != 0;
			boolean damage = (bits & 2) != 0;
			boolean server = (bits & 4) != 0;
			boolean multi = (bits & 8) != 0;
			FightRecord r = flagFight(damage, server, multi);
			Content c = SharePayloadCodec.content(r, true);
			Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, new Options(positions, true, 16, false)));
			String where = flagName(positions, damage, server, multi);
			int expected = (positions ? FLAG_POSITIONS : 0) | (damage ? FLAG_DAMAGE : 0)
					| (server ? FLAG_SERVER : 0) | (multi ? FLAG_MULTI : 0);
			assertEquals(expected, d.flags(), where);
			assertEquals(server ? "play.example.net" : null, d.server(), where);
			assertEquals(multi ? List.of("Kwamix", "Rival") : List.of("Kwamix"), d.opponents(), where);
			assertEquals(damage ? 44 : 42, d.summary().length, where);
			assertArrayEquals(SharePayloadCodec.summaryOf(r.stats, damage), d.summary(), where);
			assertEquals(57, d.pingMs(), where);
			assertEventsClose(c, d, positions);
			if (multi) {
				// Per-event opponent indices survive: Rival's hit (900) and hit taken (1100) carry 1.
				int rivalEvents = 0;
				for (Decoded.Ev ev : d.events()) {
					if (ev.opp() == 1) {
						rivalEvents++;
					}
				}
				assertEquals(2, rivalEvents, where);
			}
		}
		// DOWNSAMPLED is a writer flag: set exactly when asked.
		Content c = SharePayloadCodec.content(flagFight(true, true, true), true);
		List<Event> kept = ShareLinks.thin(c.events(), 0.0);
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, kept, new Options(true, true, 16, true)));
		assertEquals(FLAG_POSITIONS | FLAG_DAMAGE | FLAG_SERVER | FLAG_MULTI | FLAG_DOWNSAMPLED, d.flags());
		assertEquals(c.events().size(), d.totalEvents());
		assertEquals(kept.size(), d.events().size());
	}

	static String flagName(boolean positions, boolean damage, boolean server, boolean multi) {
		return "flags_p" + (positions ? 1 : 0) + "_d" + (damage ? 1 : 0) + "_s" + (server ? 1 : 0) + "_m" + (multi ? 1 : 0);
	}

	@Test
	void serverIsSharedOnlyWhenAllowedAndNotAnIp() {
		FightRecord r = flagFight(false, true, false);
		assertNull(SharePayloadCodec.content(r, false).server(), "shareIncludeServer off");
		for (String ip : new String[] {"192.168.1.20", "127.1", "[::1]", "2001:db8::1", "play.example.net:25565"}) {
			r.server = ip;
			assertNull(SharePayloadCodec.content(r, true).server(), ip);
			assertTrue(SharePayloadCodec.isIpLiteral(ip), ip);
		}
		r.server = "  Hypixel.net ";
		assertEquals("Hypixel.net", SharePayloadCodec.content(r, true).server());
		r.server = "Singleplayer";
		assertEquals("Singleplayer", SharePayloadCodec.content(r, true).server());
		r.server = "";
		assertNull(SharePayloadCodec.content(r, true).server());
	}

	// ---- opponent table -------------------------------------------------------------------------------

	@Test
	void twoOpponentTable() {
		FightRecord r = SampleFights.multi(1);
		Content c = SharePayloadCodec.content(r, true);
		assertEquals(List.of("Kwamix", "Rival1"), c.opponents());
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, Options.FULL));
		assertEquals(c.opponents(), d.opponents());
		assertTrue((d.flags() & FLAG_MULTI) != 0);
		assertEventsClose(c, d, true);
		// The bystander (only whiffed at, never engaged) is left out of the stream, not the summary.
		int bystanderSwings = countSwings(r, 2);
		assertEquals(3, bystanderSwings);
		assertEquals(r.swings.size() + r.hitsTaken.size() - bystanderSwings, d.totalEvents());
		assertEquals(r.stats.swings, d.summary()[0]);
	}

	@Test
	void fourOpponentTableDropsTheFifthAndTheBystander() {
		FightRecord r = SampleFights.multi(4); // Kwamix + 4 engaged rivals + 1 bystander
		Content c = SharePayloadCodec.content(r, true);
		assertEquals(List.of("Kwamix", "Rival1", "Rival2", "Rival3"), c.opponents(),
				"primary, then by descending landed + taken");
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, Options.FULL));
		assertEquals(c.opponents(), d.opponents());
		assertEventsClose(c, d, true);
		int omitted = countSwings(r, 4) + countTaken(r, 4) + countSwings(r, 5);
		assertTrue(omitted > 0);
		assertEquals(r.swings.size() + r.hitsTaken.size() - omitted, d.totalEvents());
		Set<Integer> opps = new HashSet<>();
		for (Decoded.Ev ev : d.events()) {
			opps.add(ev.opp());
		}
		assertEquals(Set.of(0, 1, 2, 3), opps, "every table index appears, none beyond it");
		assertEquals(r.stats.swings, d.summary()[0], "the summary still counts every event");
		assertEquals(r.stats.hitsTaken, d.summary()[4]);
	}

	private static int countSwings(FightRecord r, int opp) {
		return (int) r.swings.stream().filter(s -> s.opp == opp).count();
	}

	private static int countTaken(FightRecord r, int opp) {
		return (int) r.hitsTaken.stream().filter(h -> h.opp == opp).count();
	}

	// ---- NO-REG, ties, reserved kinds, names, empty ----------------------------------------------

	static FightRecord noRegFight() {
		return SampleFights.script()
				.hit(100, 2.97, x -> x.crit = true)
				.hit(180, 2.97, x -> {
					x.crit = true;
					x.intoImmunity = true;
				})
				.hit(260, 2.4, x -> x.intoImmunity = true)
				.hit(900, 2.5)
				.duration(900).finish();
	}

	@Test
	void noRegBitSurvives() {
		Content c = SharePayloadCodec.content(noRegFight(), true);
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, Options.FULL));
		List<Boolean> flags = new ArrayList<>();
		for (Decoded.Ev ev : d.events()) {
			flags.add(ev.noReg());
		}
		assertEquals(List.of(false, true, true, false), flags);
		assertEquals(2, d.summary()[39], "S40 immuneHits");
		assertEquals(0, d.events().get(1).arg(), "a NO-REG 3-block crit keeps its tier code (0)");
	}

	@Test
	void tiesDecodeTakenFirstThenSwingsThenJumpsThenDeath() {
		// Added in the "wrong" order on purpose: jump, swing, then the hit taken, all at 1000 ms.
		FightRecord r = SampleFights.script()
				.jump(1000, 1).hit(1000, 2.5).taken(1000, true).miss(1000, 3.5)
				.duration(1000).result(FightResult.LOSS, FightEndReason.SELF_DIED).finish();
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(r, true), Options.FULL));
		List<Integer> kinds = new ArrayList<>();
		for (Decoded.Ev ev : d.events()) {
			kinds.add(ev.kind());
			assertEquals(100, ev.tCs());
		}
		assertEquals(List.of(KIND_TAKEN, KIND_HIT, KIND_MISS, KIND_JUMP, KIND_DEATH), kinds);
		assertEquals(1, d.events().get(4).arg(), "LOSS: you died");
	}

	@Test
	void reservedKindsStopDecodingGracefully() {
		Content c = reservedKindContent();
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, Options.FULL));
		assertTrue(d.truncated());
		assertEquals(2, d.events().size(), "the events before the reserved kind are kept");
		assertEquals(c.events().size(), d.totalEvents());
	}

	static Content reservedKindContent() {
		Content base = SharePayloadCodec.content(flagFight(false, false, false), true);
		List<Event> events = new ArrayList<>(base.events().subList(0, 2));
		Event prev = events.get(1);
		events.add(new Event(prev.t() + 10, 5, 2, false, 0, 0, 0, 0, 0, 0, 0, 0, 0));
		events.addAll(base.events().subList(2, base.events().size()));
		events.sort((a, b) -> Integer.compare(a.t(), b.t()));
		return new Content(base.player(), base.opponents(), base.server(), base.startEpochSec(), base.durationMs(),
				base.result(), base.endReason(), base.pingMs(), base.threeBlockCm(), base.jumpMinMs(), base.jumpMaxMs(),
				base.jumpAttemptMs(), base.comboTimeoutMs(), base.advantageWindowMs(), base.summary(), base.damage(), events);
	}

	/**
	 * A hand-built payload that sets every bit v1 readers must ignore (§9.3): flag bits 5–7, tag bit 7 on
	 * every event, and tag bit 6 (NO-REG) on a MISS and a TAKEN, where it carries no meaning.
	 */
	static byte[] reservedBitsPayload() {
		SharePayloadCodec.Writer w = new SharePayloadCodec.Writer();
		w.u8(1).u8(0xE0 | FLAG_SERVER);
		w.str("builtdoor", 16).str("Kwamix", 16).str("future.example.net", 32);
		w.varLong(1_792_510_212L).varInt(4200).u8(FightResult.WIN.code() | (FightEndReason.OPPONENT_DIED.code << 2));
		w.varInt(40).varInt(295).varInt(0).varInt(80).varInt(200).varInt(2000).varInt(3000);
		for (int i = 0; i < SharePayloadCodec.SUMMARY_FIELDS; i++) {
			if (i == SharePayloadCodec.SIGNED_SUMMARY_INDEX) {
				w.zig(-3);
			} else {
				w.varInt(i);
			}
		}
		w.varInt(4).varInt(4);
		w.varInt(10).u8(KIND_MISS | (1 << 3) | 0x40 | 0x80).u8(140).i8(-120).i8(15);
		w.varInt(25).u8(KIND_HIT | (3 << 3) | 0x40 | 0x80).u8(110).i8(20).i8(40);
		w.varInt(5).u8(KIND_TAKEN | 0x40 | 0x80).u8(112);
		w.varInt(380).u8(KIND_DEATH | 0x40 | 0x80);
		return w.toByteArray();
	}

	@Test
	void reservedBitsAreIgnored() {
		Decoded d = SharePayloadCodec.decode(reservedBitsPayload());
		assertEquals(0xE0 | FLAG_SERVER, d.flags(), "the raw flags byte is reported as read");
		assertEquals("future.example.net", d.server());
		assertEquals(List.of("Kwamix"), d.opponents());
		assertEquals(-3, d.summary()[SharePayloadCodec.SIGNED_SUMMARY_INDEX]);
		assertEquals(4, d.events().size());
		assertFalse(d.truncated());
		Decoded.Ev miss = d.events().get(0);
		assertEquals(KIND_MISS, miss.kind());
		assertEquals(1, miss.arg());
		assertFalse(miss.noReg(), "bit 6 means NO-REG only on a HIT");
		assertEquals(-120, miss.aimH());
		Decoded.Ev hit = d.events().get(1);
		assertEquals(KIND_HIT, hit.kind());
		assertEquals(3, hit.arg());
		assertTrue(hit.noReg());
		assertEquals(35, hit.tCs());
		assertFalse(d.events().get(2).noReg());
		assertEquals(KIND_DEATH, d.events().get(3).kind());
		assertFalse(d.events().get(3).noReg());
	}

	static FightRecord multibyteFight() {
		return SampleFights.script()
				.with(r -> r.playerName = "ab😀😀😀😀") // 2 + 4·4 = 18 bytes
				.with(r -> r.opponentName = "😀😀😀😀😀") // 20 bytes
				.with(r -> r.opponents.set(0, r.opponentName))
				.with(r -> r.server = "café.example.org")
				.hit(100, 2.5).duration(100).finish();
	}

	@Test
	void multibyteNamesAreCutOnACodePointBoundary() {
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(multibyteFight(), true), Options.FULL));
		assertEquals("ab😀😀😀", d.player(), "18 bytes cut to 14, never mid-emoji");
		assertEquals("😀😀😀😀", d.opponents().get(0), "20 bytes cut to 16");
		assertEquals("café.example.org", d.server());
		assertEquals(14, SharePayloadCodec.utf8Capped("aaaaaaaaaaaaaa€", 16).length, "a 3-byte char that does not fit is dropped");
		assertEquals("", new String(SharePayloadCodec.utf8Capped("😀", 3), StandardCharsets.UTF_8));
		assertEquals(0, SharePayloadCodec.utf8Capped(null, 16).length);
	}

	/**
	 * Names that start with U+FEFF (a server NPC or practice bot padding its profile name; PlayerRefs only
	 * strips § codes and ASCII whitespace). Java's decoder keeps the character, so the viewer must too.
	 */
	static FightRecord bomFight() {
		return SampleFights.script()
				.with(r -> r.playerName = "﻿")
				.with(r -> r.opponentName = "﻿Bot")
				.with(r -> r.opponents.set(0, r.opponentName))
				.hit(100, 2.5).duration(100).finish();
	}

	@Test
	void aLeadingByteOrderMarkIsPartOfTheName() {
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(bomFight(), true), Options.FULL));
		assertEquals("﻿", d.player(), "a name that is only the mark is not empty");
		assertEquals("﻿Bot", d.opponents().get(0));
	}

	@Test
	void emptyFightEncodesAndDecodes() {
		FightRecord r = SampleFights.script().duration(0).finish();
		Content c = SharePayloadCodec.content(r, true);
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, Options.FULL));
		assertEquals(FLAG_POSITIONS, d.flags());
		assertEquals(0, d.totalEvents());
		assertTrue(d.events().isEmpty());
		assertArrayEquals(new int[42], d.summary());
		assertNull(d.server());
		// A WIN with no events still carries its DEATH.
		r.result = FightResult.WIN.name();
		r.endReason = FightEndReason.OPPONENT_DIED.name();
		Decoded win = SharePayloadCodec.decode(SharePayloadCodec.encode(SharePayloadCodec.content(r, true), Options.FULL));
		assertEquals(1, win.events().size());
		assertEquals(KIND_DEATH, win.events().get(0).kind());
	}

	@Test
	void malformedPayloadsAreRejected() {
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.decode(new byte[0]));
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.decode(new byte[] {2, 0}));
		byte[] ok = SharePayloadCodec.encode(SharePayloadCodec.content(flagFight(true, true, true), true), Options.FULL);
		byte[] cut = Arrays.copyOf(ok, ok.length - 3);
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.decode(cut));
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.payloadOf("not base64 !"));
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.payloadOf("AAAA"));
	}

	/** Bytes a v1 reader must reject, by name; the viewer's decoder must reject the same (§9.6, viewer-check). */
	static Map<String, byte[]> rejectedPayloads() {
		Map<String, byte[]> m = new LinkedHashMap<>();
		// A 10-byte varint whose last byte carries bits past bit 63 (H6, the only varLong field).
		SharePayloadCodec.Writer wide = new SharePayloadCodec.Writer().u8(1).u8(0).str("a", 16).str("b", 16);
		for (int i = 0; i < 9; i++) {
			wide.u8(0x80);
		}
		m.put("varint_past_64_bits", wide.u8(0x02).toByteArray());
		m.put("start_time_2_53", header(new SharePayloadCodec.Writer(), 0, 1L << 53).toByteArray());
		m.put("duration_2_31", new SharePayloadCodec.Writer().u8(1).u8(0).str("a", 16).str("b", 16)
				.varLong(1_792_510_212L).varLong(1L << 31).toByteArray());
		m.put("multi_count_0", new SharePayloadCodec.Writer().u8(1).u8(FLAG_MULTI).str("a", 16).str("b", 16).u8(0).toByteArray());
		m.put("multi_count_4", new SharePayloadCodec.Writer().u8(1).u8(FLAG_MULTI).str("a", 16).str("b", 16).u8(4)
				.str("c", 16).str("d", 16).str("e", 16).str("f", 16).toByteArray());
		m.put("summary_zig_2_31", summaryWithMomentum(1L << 31).toByteArray());
		m.put("summary_zig_neg", summaryWithMomentum(-(1L << 31) - 1).toByteArray());
		// Two DEATH events whose times add up past 2^31 - 1 centiseconds.
		m.put("event_time_overflow", events(0, 2).varInt(Integer.MAX_VALUE).u8(KIND_DEATH | (1 << 3))
				.varInt(1).u8(KIND_DEATH | (1 << 3)).toByteArray());
		m.put("jump_delta_overflow", events(0, 1).varInt(10).u8(KIND_JUMP).zig(-(1L << 31) - 1).toByteArray());
		// Positions whose running sum leaves the int range.
		m.put("position_overflow", events(FLAG_POSITIONS, 2)
				.varInt(10).u8(KIND_TAKEN).u8(100).zig(Integer.MAX_VALUE).zig(0).u8(0).varInt(30)
				.varInt(10).u8(KIND_TAKEN).u8(100).zig(1).zig(0).u8(0).varInt(30).toByteArray());
		m.put("truncated_event", events(0, 1).varInt(10).u8(KIND_HIT).u8(110).toByteArray());
		return m;
	}

	/** H1..H15 with a minimal header: names "a" and "b", 1 s long, the default windows. */
	private static SharePayloadCodec.Writer header(SharePayloadCodec.Writer w, int flags, long startEpochSec) {
		w.u8(1).u8(flags).str("a", 16).str("b", 16);
		w.varLong(startEpochSec).varInt(1000).u8(0);
		w.varInt(0).varInt(295).varInt(0).varInt(80).varInt(200).varInt(2000).varInt(3000);
		return w;
	}

	private static SharePayloadCodec.Writer summaryWithMomentum(long momentum) {
		SharePayloadCodec.Writer w = header(new SharePayloadCodec.Writer(), 0, 1_792_510_212L);
		for (int i = 0; i < SharePayloadCodec.SUMMARY_FIELDS; i++) {
			if (i == SharePayloadCodec.SIGNED_SUMMARY_INDEX) {
				w.zig(momentum);
			} else {
				w.varInt(0);
			}
		}
		return w;
	}

	/** A header, a zero summary and E1 = E2 = {@code count}; the caller appends the events. */
	private static SharePayloadCodec.Writer events(int flags, int count) {
		SharePayloadCodec.Writer w = header(new SharePayloadCodec.Writer(), flags, 1_792_510_212L);
		for (int i = 0; i < SharePayloadCodec.SUMMARY_FIELDS; i++) {
			if (i == SharePayloadCodec.SIGNED_SUMMARY_INDEX) {
				w.zig(0);
			} else {
				w.varInt(0);
			}
		}
		return w.varInt(count).varInt(count);
	}

	@Test
	void outOfRangeFieldsAreRejectedAsMalformed() {
		for (Map.Entry<String, byte[]> e : rejectedPayloads().entrySet()) {
			assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.decode(e.getValue()), e.getKey());
		}
		// The largest accepted values still decode.
		Decoded big = SharePayloadCodec.decode(events(0, 1).varInt(Integer.MAX_VALUE).u8(KIND_DEATH | (1 << 3)).toByteArray());
		assertEquals(Integer.MAX_VALUE, big.events().get(0).tCs());
		Decoded start = SharePayloadCodec.decode(summaryWithMomentum(Integer.MIN_VALUE).varInt(0).varInt(0).toByteArray());
		assertEquals(Integer.MIN_VALUE, start.summary()[SharePayloadCodec.SIGNED_SUMMARY_INDEX]);
		byte[] maxStart = header(new SharePayloadCodec.Writer(), 0, SharePayloadCodec.MAX_START_EPOCH_SEC).toByteArray();
		// Only the header: the decoder then runs out of bytes in the summary, past the start-time check.
		IllegalArgumentException cut = assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.decode(maxStart));
		assertEquals("Truncated payload", cut.getMessage());
	}

	@Test
	void inflatedSizeIsCapped() {
		byte[] ok = SharePayloadCodec.encode(SharePayloadCodec.content(flagFight(true, true, true), true), Options.FULL);
		byte[] atCap = Arrays.copyOf(ok, SharePayloadCodec.MAX_PAYLOAD_BYTES); // trailing bytes are ignored
		assertArrayEquals(atCap, SharePayloadCodec.payloadOf(SharePayloadCodec.fragment(atCap)));
		assertEquals(SharePayloadCodec.decode(ok).events().size(), SharePayloadCodec.decode(atCap).events().size());
		byte[] over = Arrays.copyOf(ok, SharePayloadCodec.MAX_PAYLOAD_BYTES + 1);
		assertThrows(IllegalArgumentException.class, () -> SharePayloadCodec.payloadOf(SharePayloadCodec.fragment(over)));
	}

	// ---- golden vectors (§9.6) ------------------------------------------------------------------------

	@Test
	void writesGoldenVectors() throws IOException {
		Map<String, String> fragments = new LinkedHashMap<>();
		fragments.put("empty", fragment(SampleFights.script().duration(0).finish(), Options.FULL));
		for (int bits = 0; bits < 16; bits++) {
			boolean positions = (bits & 1) != 0;
			boolean damage = (bits & 2) != 0;
			boolean server = (bits & 4) != 0;
			boolean multi = (bits & 8) != 0;
			fragments.put(flagName(positions, damage, server, multi),
					fragment(flagFight(damage, server, multi), new Options(positions, true, 16, false)));
		}
		Content all = SharePayloadCodec.content(allFlagsFight(), true);
		List<Event> thinnedHalf = ShareLinks.thin(all.events(), 0.3);
		assertTrue(thinnedHalf.size() < all.events().size(), "the downsampled vector really leaves events out");
		fragments.put("downsampled", SharePayloadCodec.fragment(
				SharePayloadCodec.encode(all, thinnedHalf, new Options(true, true, 16, true))));
		ShareLink thinned = ShareLinks.build(SampleFights.fiveMinutes(), KwamsterConfig.DEFAULT_SHARE_BASE_URL, true, true, 1900);
		assertNotNull(thinned);
		assertTrue(thinned.downsampled());
		fragments.put("thinned", thinned.url().substring(thinned.url().indexOf('#') + 1));
		fragments.put("reserved_kind", SharePayloadCodec.fragment(SharePayloadCodec.encode(reservedKindContent(), Options.FULL)));
		fragments.put("multibyte_name", fragment(multibyteFight(), Options.FULL));
		fragments.put("bom_name", fragment(bomFight(), Options.FULL));
		fragments.put("reserved_bits", SharePayloadCodec.fragment(reservedBitsPayload()));
		fragments.put("noreg", fragment(noRegFight(), Options.FULL));
		fragments.put("multi4", fragment(SampleFights.multi(4), Options.FULL));
		fragments.put("example", fragment(SampleFights.example(), Options.FULL));
		// Exactly at the inflated-size cap (trailing bytes are ignored): both decoders must still accept it.
		byte[] small = SharePayloadCodec.encode(SharePayloadCodec.content(flagFight(false, false, false), true), Options.FULL);
		fragments.put("at_size_cap", SharePayloadCodec.fragment(Arrays.copyOf(small, SharePayloadCodec.MAX_PAYLOAD_BYTES)));

		// Fragments both decoders must reject (viewer-check requires the JS decoder to throw on each).
		Map<String, String> rejected = new LinkedHashMap<>();
		for (Map.Entry<String, byte[]> e : rejectedPayloads().entrySet()) {
			rejected.put(e.getKey(), SharePayloadCodec.fragment(e.getValue()));
		}
		rejected.put("over_size_cap", SharePayloadCodec.fragment(Arrays.copyOf(small, SharePayloadCodec.MAX_PAYLOAD_BYTES + 1)));

		Files.createDirectories(VECTOR_DIR);
		Path rejectedDir = VECTOR_DIR.resolve("rejected");
		Files.createDirectories(rejectedDir);
		for (Path dir : List.of(VECTOR_DIR, rejectedDir)) {
			try (DirectoryStream<Path> old = Files.newDirectoryStream(dir, "*.{txt,json}")) {
				for (Path p : old) {
					Files.delete(p);
				}
			}
		}
		for (Map.Entry<String, String> e : fragments.entrySet()) {
			Decoded d = SharePayloadCodec.decode(SharePayloadCodec.payloadOf(e.getValue()));
			write(VECTOR_DIR.resolve(e.getKey() + ".txt"), e.getValue());
			write(VECTOR_DIR.resolve(e.getKey() + ".json"), VECTOR_GSON.toJson(d));
		}
		for (Map.Entry<String, String> e : rejected.entrySet()) {
			assertThrows(IllegalArgumentException.class,
					() -> SharePayloadCodec.decode(SharePayloadCodec.payloadOf(e.getValue())), e.getKey());
			write(rejectedDir.resolve(e.getKey() + ".txt"), e.getValue());
		}
		assertTrue(SharePayloadCodec.decode(SharePayloadCodec.payloadOf(fragments.get("reserved_kind"))).truncated());
		assertEquals(fragments.size(), countFiles(VECTOR_DIR, "*.txt"));
		assertEquals(fragments.size(), countFiles(VECTOR_DIR, "*.json"));
		assertEquals(rejected.size(), countFiles(rejectedDir, "*.txt"));
	}

	/** A 1v4 fight with visible health and a server name: every H2 flag can be set at once. */
	static FightRecord allFlagsFight() {
		FightRecord r = SampleFights.multi(3);
		r.opponentHealthVisible = true;
		for (FightRecord.Swing sw : r.swings) {
			if (sw.landed) {
				FightRecord.DamageDrop d = new FightRecord.DamageDrop();
				d.t = sw.t + 60;
				d.opp = sw.opp;
				d.amount = 2.5f;
				r.damage.add(d);
			}
		}
		r.damage.sort((a, b) -> Integer.compare(a.t, b.t));
		r.stats = StatsCalculator.compute(r, StatsCalculator.FINAL);
		return r;
	}

	@Test
	void allFlagsAtOnce() {
		FightRecord r = allFlagsFight();
		Content c = SharePayloadCodec.content(r, true);
		List<Event> kept = ShareLinks.thin(c.events(), 0.3);
		Decoded d = SharePayloadCodec.decode(SharePayloadCodec.encode(c, kept, new Options(true, true, 16, true)));
		assertEquals(FLAG_POSITIONS | FLAG_DOWNSAMPLED | FLAG_DAMAGE | FLAG_SERVER | FLAG_MULTI, d.flags());
		assertEquals(List.of("Kwamix", "Rival1", "Rival2", "Rival3"), d.opponents());
		assertEquals("ffa.example.com", d.server());
		assertTrue(d.summary()[42] > 0, "damage dealt is known");
		assertEquals(c.events().size(), d.totalEvents());
		assertEquals(kept.size(), d.events().size());
		for (int i = 0; i < kept.size(); i++) {
			assertEventClose(kept.get(i), d.events().get(i), true, true, "kept event " + i);
		}
	}

	private static String fragment(FightRecord r, Options o) {
		return SharePayloadCodec.fragment(SharePayloadCodec.encode(SharePayloadCodec.content(r, true), o));
	}

	private static void write(Path p, String text) {
		try {
			Files.writeString(p, text, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static long countFiles(Path dir, String glob) throws IOException {
		long n = 0;
		try (DirectoryStream<Path> s = Files.newDirectoryStream(dir, glob)) {
			for (Path ignored : s) {
				n++;
			}
		}
		return n;
	}

	@Test
	void statsInContentAreRecomputedWhenAbsent() {
		FightRecord r = flagFight(true, false, false);
		int[] withStats = SharePayloadCodec.content(r, true).summary();
		r.stats = null;
		assertArrayEquals(withStats, SharePayloadCodec.content(r, true).summary());
		assertArrayEquals(SharePayloadCodec.summaryOf(StatsCalculator.compute(r, StatsCalculator.FINAL), true), withStats);
	}
}
