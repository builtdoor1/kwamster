package kwamster.record;

import kwamster.core.model.FightSummaryRow;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Saved-fights pruning (§10): pinned rows and a real fight just saved survive, trivial fights go before real
 * ones, and a trivial save never deletes a real fight (it is dropped itself when every slot holds a real one).
 */
final class FightIndexTest {
	private static FightSummaryRow row(String id, long start, boolean trivial, boolean pinned) {
		FightSummaryRow r = new FightSummaryRow();
		r.id = id;
		r.file = id + FightIndex.FIGHT_EXTENSION;
		r.startEpochMs = start;
		r.trivial = trivial;
		r.pinned = pinned;
		return r;
	}

	private static List<String> ids(List<FightSummaryRow> rows) {
		List<String> out = new ArrayList<>();
		for (FightSummaryRow r : rows) {
			out.add(r.id);
		}
		return out;
	}

	@Test
	void oldestUnpinnedGoFirstWhenNoneIsTrivial() {
		FightIndex index = new FightIndex();
		for (int i = 1; i <= 5; i++) {
			index.add(row("f" + i, i * 1000L, false, i == 1));
		}
		List<FightSummaryRow> removed = index.prune(2, "f5");
		assertEquals(List.of("f2", "f3"), ids(removed), "oldest unpinned first; the pinned f1 stays");
		assertEquals(List.of("f5", "f4", "f1"), ids(index.rows()));
	}

	@Test
	void trivialFightsArePrunedBeforeAnyRealFight() {
		FightIndex index = new FightIndex();
		index.add(row("real1", 1000, false, false));
		index.add(row("real2", 2000, false, false));
		index.add(row("whiff1", 3000, true, false));
		index.add(row("real3", 4000, false, false));
		index.add(row("whiff2", 5000, true, false));
		index.add(row("real4", 6000, false, false)); // the fight just saved
		List<FightSummaryRow> removed = index.prune(3, "real4");
		assertEquals(List.of("whiff1", "whiff2", "real1"), ids(removed),
				"the trivial fights go first, then the oldest real one (never the one just saved)");
		assertEquals(List.of("real4", "real3", "real2"), ids(index.rows()));
	}

	@Test
	void aLobbyOfWhiffsNeverEvictsARealFight() {
		FightIndex index = new FightIndex();
		index.add(row("win", 1000, false, false));
		index.add(row("loss", 2000, false, false));
		for (int i = 0; i < 50; i++) {
			String id = "whiff" + i;
			index.add(row(id, 10_000L + i, true, false));
			index.prune(5, id);
		}
		assertNotNull(index.find("win"));
		assertNotNull(index.find("loss"));
		assertEquals(5, index.size());
		assertTrue(index.find("whiff49") != null, "the newest whiff is kept");
	}

	@Test
	void aLobbyOfWhiffsNeverEvictsARealFightFromAFullIndex() {
		FightIndex index = new FightIndex();
		for (int i = 0; i < 5; i++) {
			index.add(row("real" + i, 1000L + i, false, false));
		}
		for (int i = 0; i < 20; i++) {
			String id = "whiff" + i;
			index.add(row(id, 10_000L + i, true, false));
			List<FightSummaryRow> removed = index.prune(5, id);
			assertEquals(List.of(id), ids(removed), "the whiff itself goes, never a real fight");
		}
		assertEquals(List.of("real4", "real3", "real2", "real1", "real0"), ids(index.rows()));
		// The next real fight is saved as usual and pushes out the oldest real one.
		index.add(row("real5", 20_000L, false, false));
		assertEquals(List.of("real0"), ids(index.prune(5, "real5")));
	}

	@Test
	void aWhiffTakesTheOlderWhiffsSlotBeforeItsOwn() {
		FightIndex index = new FightIndex();
		for (int i = 0; i < 4; i++) {
			index.add(row("real" + i, 1000L + i, false, false));
		}
		index.add(row("whiffA", 5000, true, false));
		index.add(row("whiffB", 6000, true, false));
		assertEquals(List.of("whiffA"), ids(index.prune(5, "whiffB")), "the older whiff goes, the new one stays");
		assertNotNull(index.find("whiffB"));
	}

	@Test
	void aTrivialSaveNeverPrunesRealFightsEvenOverALoweredLimit() {
		FightIndex index = new FightIndex();
		for (int i = 0; i < 6; i++) {
			index.add(row("real" + i, 1000L + i, false, false));
		}
		index.add(row("whiffOld", 7000, true, false));
		index.add(row("whiff", 8000, true, false));
		// The limit was lowered to 3: the trivial save removes only trivial fights; the next real save catches up.
		assertEquals(List.of("whiffOld", "whiff"), ids(index.prune(3, "whiff")));
		assertEquals(6, index.size());
		index.add(row("real6", 9000, false, false));
		assertEquals(List.of("real0", "real1", "real2", "real3"), ids(index.prune(3, "real6")));
	}

	@Test
	void pinnedTrivialFightsSurviveAndATrivialSaveDropsItselfBeforeARealFight() {
		FightIndex index = new FightIndex();
		index.add(row("pinnedWhiff", 1000, true, true));
		index.add(row("real", 2000, false, false));
		index.add(row("whiff", 3000, true, false));
		List<FightSummaryRow> removed = index.prune(1, "whiff");
		assertEquals(List.of("whiff"), ids(removed), "the whiff just saved goes rather than the real fight");
		assertNotNull(index.find("pinnedWhiff"));
		assertNotNull(index.find("real"));
	}

	@Test
	void aRealFightJustSavedIsNeverPruned() {
		FightIndex index = new FightIndex();
		index.add(row("pinned", 1000, false, true));
		index.add(row("older", 2000, false, false));
		index.add(row("new", 500, false, false)); // an odd clock: the new fight sorts oldest
		assertEquals(List.of("older"), ids(index.prune(1, "new")));
		assertNotNull(index.find("new"));
	}

	@Test
	void noLimitOrUnderTheLimitRemovesNothing() {
		FightIndex index = new FightIndex();
		index.add(row("a", 1000, true, false));
		index.add(row("b", 2000, false, false));
		assertTrue(index.prune(0, null).isEmpty(), "0 = keep every fight");
		assertTrue(index.prune(2, null).isEmpty());
		assertEquals(2, index.size());
	}

	@Test
	void legacyRowsWithoutTheFieldCountAsRealFights() {
		List<FightSummaryRow> rows = FightIndex.parse("{\"schema\":1,\"fights\":[{\"id\":\"old\",\"file\":\"old.json\",\"startEpochMs\":5}]}");
		assertNotNull(rows);
		assertEquals(1, rows.size());
		assertFalse(rows.get(0).trivial);
	}
}
