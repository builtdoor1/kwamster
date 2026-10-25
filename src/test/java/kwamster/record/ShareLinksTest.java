package kwamster.record;

import kwamster.SampleFights;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightRecord;
import kwamster.core.model.ShareLink;
import kwamster.record.SharePayloadCodec.Content;
import kwamster.record.SharePayloadCodec.Decoded;
import kwamster.record.SharePayloadCodec.Event;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;

import static kwamster.record.SharePayloadCodec.FLAG_DOWNSAMPLED;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §9.1 link assembly and the §9.4–9.5 size budget with stratified thinning. */
final class ShareLinksTest {
	private static final String BASE = KwamsterConfig.DEFAULT_SHARE_BASE_URL;
	private static final int BUDGET = 1900;

	private static Decoded decodeUrl(String url) {
		return SharePayloadCodec.decode(SharePayloadCodec.payloadOf(url.substring(url.indexOf('#') + 1)));
	}

	@Test
	void sixtySecondFightFitsUnthinned() {
		FightRecord r = SampleFights.sixtySeconds();
		ShareLink link = ShareLinks.build(r, BASE, true, true, BUDGET);
		assertNotNull(link);
		assertTrue(link.url().startsWith(BASE + "#"));
		assertEquals(link.url().length(), link.length());
		assertTrue(link.length() <= BUDGET, "length " + link.length());
		assertFalse(link.downsampled());
		assertEquals(72, link.eventsTotal());
		assertEquals(72, link.eventsKept());
		Decoded d = decodeUrl(link.url());
		assertEquals(0, d.flags() & FLAG_DOWNSAMPLED);
		assertTrue((d.flags() & FLAG_POSITIONS) != 0);
		assertTrue((d.flags() & FLAG_SERVER) != 0);
	}

	@Test
	void workedFightOf132EventsFitsUnthinned() {
		FightRecord r = SampleFights.worked();
		Content c = SharePayloadCodec.content(r, true);
		assertEquals(132, c.events().size(), "80 swings + 40 taken + 12 jumps, a DRAW has no DEATH");
		ShareLink link = ShareLinks.build(r, BASE, true, true, BUDGET);
		assertNotNull(link);
		assertTrue(link.length() <= BUDGET, "length " + link.length());
		assertFalse(link.downsampled());
		assertEquals(132, link.eventsKept());
		Decoded d = decodeUrl(link.url());
		assertEquals(0, d.flags() & FLAG_DOWNSAMPLED);
		assertTrue((d.flags() & FLAG_POSITIONS) != 0);
		assertEquals(132, d.events().size());
	}

	@Test
	void fiveMinuteFightIsThinnedToFit() {
		FightRecord r = SampleFights.fiveMinutes();
		Content c = SharePayloadCodec.content(r, true);
		assertEquals(600, c.events().size());
		ShareLink link = ShareLinks.build(r, BASE, true, true, BUDGET);
		assertNotNull(link);
		assertTrue(link.length() <= BUDGET, "length " + link.length());
		assertTrue(link.downsampled());
		assertEquals(600, link.eventsTotal());
		assertTrue(link.eventsKept() < 600 && link.eventsKept() > 60, "kept " + link.eventsKept());

		Decoded thin = decodeUrl(link.url());
		Decoded full = SharePayloadCodec.decode(SharePayloadCodec.encode(c, SharePayloadCodec.Options.FULL));
		assertTrue((thin.flags() & FLAG_DOWNSAMPLED) != 0);
		// The summary block is identical to the full encode: thinning only touches the replay.
		assertArrayEquals(full.summary(), thin.summary());
		assertEquals(600, thin.totalEvents());
		assertEquals(link.eventsKept(), thin.events().size());

		// 1900 chars hold about 150 events with positions, so the first stage (positions on) fits.
		assertTrue((thin.flags() & FLAG_POSITIONS) != 0, "positions are kept");
		// Every thinned event is exactly one of the full stream's events, in order.
		assertSubsequence(full.events(), thin.events());
		// Every DEATH and the first and last event of each kind survive.
		for (int kind : new int[] {KIND_HIT, KIND_MISS, KIND_TAKEN, KIND_JUMP, KIND_DEATH}) {
			List<Decoded.Ev> all = ofKind(full.events(), kind);
			List<Decoded.Ev> kept = ofKind(thin.events(), kind);
			assertFalse(kept.isEmpty(), "kind " + kind);
			assertEquals(all.get(0).tCs(), kept.get(0).tCs(), "first of kind " + kind);
			assertEquals(all.get(all.size() - 1).tCs(), kept.get(kept.size() - 1).tCs(), "last of kind " + kind);
			if (kind == KIND_DEATH) {
				assertEquals(all.size(), kept.size());
			}
		}
		// 3-block and crit hits (codes 0-3) keep a higher share than the other hits.
		double priorityShare = (double) countHits(thin.events(), true) / countHits(full.events(), true);
		double otherShare = (double) countHits(thin.events(), false) / countHits(full.events(), false);
		assertTrue(priorityShare > otherShare, priorityShare + " vs " + otherShare);
	}

	private static void assertSubsequence(List<Decoded.Ev> all, List<Decoded.Ev> kept) {
		int i = 0;
		for (Decoded.Ev k : kept) {
			while (i < all.size() && !all.get(i).equals(k)) {
				i++;
			}
			assertTrue(i < all.size(), "kept event " + k + " is not in the full stream (in order)");
			i++;
		}
	}

	private static List<Decoded.Ev> ofKind(List<Decoded.Ev> events, int kind) {
		List<Decoded.Ev> out = new ArrayList<>();
		for (Decoded.Ev e : events) {
			if (e.kind() == kind) {
				out.add(e);
			}
		}
		return out;
	}

	private static int countHits(List<Decoded.Ev> events, boolean priority) {
		int n = 0;
		for (Decoded.Ev e : events) {
			if (e.kind() == KIND_HIT && (e.arg() <= 3) == priority) {
				n++;
			}
		}
		return n;
	}

	@Test
	void thinKeepsAnchorsAndSpacesEvenly() {
		Content c = SharePayloadCodec.content(SampleFights.fiveMinutes(), true);
		List<Event> none = ShareLinks.thin(c.events(), 0.0);
		// f = 0: only the first and last of each kind plus the DEATH (HIT, MISS, TAKEN, JUMP: 2 each).
		assertEquals(9, none.size());
		assertEquals(c.events(), ShareLinks.thin(c.events(), 1.0));
		assertArrayEquals(new int[] {0, 4, 9}, ShareLinks.evenlySpaced(10, 3));
		assertArrayEquals(new int[] {0}, ShareLinks.evenlySpaced(10, 1));
		assertArrayEquals(new int[0], ShareLinks.evenlySpaced(10, 0));
		assertArrayEquals(new int[] {0, 1, 2}, ShareLinks.evenlySpaced(3, 7));
		// Thinning preserves stream order.
		List<Event> half = ShareLinks.thin(c.events(), 0.5);
		int last = -1;
		for (Event e : half) {
			int idx = c.events().indexOf(e);
			assertTrue(idx > last);
			last = idx;
		}
	}

	@Test
	void searchPicksTheFittingProbeWithTheMostEvents() {
		// Non-monotone size: f ≤ 0.5 → 1899 (fits, longer), 0.5 < f ≤ 0.75 → 1890 (fits, shorter, more
		// events), f > 0.75 → 2000 (too long). The final probe at f = 0 also fits; the answer is f = 0.75.
		DoubleFunction<ShareLinks.Probe> sizes = f -> {
			int kept = (int) Math.round(f * 600);
			int len = f <= 0.5 ? 1899 : f <= 0.75 ? 1890 : 2000;
			return new ShareLinks.Probe(f, kept, len, "x");
		};
		ShareLinks.Probe best = ShareLinks.search(sizes, BUDGET);
		assertNotNull(best);
		assertEquals(0.75, best.keepFraction(), 1e-12);
		assertEquals(450, best.eventsKept());

		// Most events beats shortest: f ≤ 0.5 → 1700, 0.5 < f ≤ 0.75 → 1890, above → 2000.
		DoubleFunction<ShareLinks.Probe> shorterBelow = f -> new ShareLinks.Probe(f, (int) Math.round(f * 600),
				f <= 0.5 ? 1700 : f <= 0.75 ? 1890 : 2000, "y");
		ShareLinks.Probe most = ShareLinks.search(shorterBelow, BUDGET);
		assertEquals(450, most.eventsKept());
		assertEquals(1890, most.length());

		// A bump in the middle: the last probes tried do not fit, an earlier one keeps the most.
		List<Double> tried = new ArrayList<>();
		DoubleFunction<ShareLinks.Probe> bump = f -> {
			tried.add(f);
			int kept = (int) Math.round(f * 600);
			int len = kept >= 440 && kept <= 460 ? 5000 : 1000 + 2 * kept;
			return new ShareLinks.Probe(f, kept, len, "z");
		};
		ShareLinks.Probe b = ShareLinks.search(bump, BUDGET);
		assertEquals(0.0, tried.get(tried.size() - 1), 0.0, "the final probe is f = 0");
		assertEquals(439, b.eventsKept());
		assertTrue(b.length() <= BUDGET);

		assertNull(ShareLinks.search(f -> new ShareLinks.Probe(f, 0, 9999, ""), BUDGET), "nothing fits");
	}

	@Test
	void invalidBasesGiveNoLink() {
		FightRecord r = SampleFights.sixtySeconds();
		for (String bad : new String[] {"ftp://x", "", "   ", "https://a b/", "https://x/|", "notaurl", "https:///path", null}) {
			assertNull(ShareLinks.build(r, bad, true, true, BUDGET), String.valueOf(bad));
			assertNull(ShareLinks.normalizeBase(bad), String.valueOf(bad));
		}
	}

	@Test
	void baseNormalisation() {
		assertEquals("https://host/KWAMSTER/", ShareLinks.normalizeBase("https://host/KWAMSTER"));
		assertEquals("https://host/KWAMSTER/", ShareLinks.normalizeBase("https://host/KWAMSTER/"));
		assertEquals("https://host/KWAMSTER/", ShareLinks.normalizeBase("  https://host/KWAMSTER#x "));
		assertEquals("https://host/KWAMSTER/", ShareLinks.normalizeBase("https://host/KWAMSTER/#old-fragment"));
		assertEquals("https://h/viewer/index.html", ShareLinks.normalizeBase("https://h/viewer/index.html"));
		assertEquals("https://h/v?x=1", ShareLinks.normalizeBase("https://h/v?x=1"));
		assertEquals("https://h/", ShareLinks.normalizeBase("https://h"));
		assertEquals("HTTP://Host.example/k/", ShareLinks.normalizeBase("HTTP://Host.example/k"));
		ShareLink link = ShareLinks.build(SampleFights.sixtySeconds(), "https://h/viewer/index.html", true, true, BUDGET);
		assertTrue(link.url().startsWith("https://h/viewer/index.html#"));
	}

	@Test
	void ipLiteralServersAreOmitted() {
		FightRecord r = SampleFights.sixtySeconds();
		r.server = "203.0.113.7";
		Decoded d = decodeUrl(ShareLinks.build(r, BASE, true, true, BUDGET).url());
		assertEquals(0, d.flags() & FLAG_SERVER);
		assertNull(d.server());
		r.server = "pvp.example.org";
		Decoded named = decodeUrl(ShareLinks.build(r, BASE, true, true, BUDGET).url());
		assertEquals("pvp.example.org", named.server());
		Decoded hidden = decodeUrl(ShareLinks.build(r, BASE, false, true, BUDGET).url());
		assertNull(hidden.server(), "shareIncludeServer off");
	}

	@Test
	void positionsOffIsHonoured() {
		Decoded d = decodeUrl(ShareLinks.build(SampleFights.sixtySeconds(), BASE, true, false, BUDGET).url());
		assertEquals(0, d.flags() & FLAG_POSITIONS);
	}

	@Test
	void smallBudgetsStillFitWithAnExactSummary() {
		Content c = SharePayloadCodec.content(SampleFights.fiveMinutes(), true);
		ShareLink link = ShareLinks.build(SampleFights.fiveMinutes(), BASE, true, true, 600);
		assertTrue(link.length() <= 600, "length " + link.length());
		assertTrue(link.downsampled());
		assertArrayEquals(c.summary(), decodeUrl(link.url()).summary());
		// A budget below even the bare header returns the shortest attempt (over budget, reported).
		ShareLink impossible = ShareLinks.build(SampleFights.fiveMinutes(), BASE, true, true, 50);
		assertTrue(impossible.length() > 50);
		assertEquals(impossible.url().length(), impossible.length());
		Decoded d = decodeUrl(impossible.url());
		assertEquals(0, d.flags() & FLAG_POSITIONS, "the last stage drops positions");
		assertEquals(0, d.flags() & FLAG_SERVER, "and the server");
		assertArrayEquals(c.summary(), d.summary());
	}
}
