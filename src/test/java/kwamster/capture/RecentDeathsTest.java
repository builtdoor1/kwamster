package kwamster.capture;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Corpse clicks: a just-killed body is not a fight target, a respawned player is. */
final class RecentDeathsTest {
	private static final UUID KWAMIX = UUID.fromString("00000000-0000-4000-8000-00000000000a");
	private static final UUID OTHER = UUID.fromString("00000000-0000-4000-8000-00000000000b");
	private static final long MS = 1_000_000L;
	private static final long T0 = 5_000L * MS;

	private RecentDeaths deaths;

	@BeforeEach
	void setUp() {
		this.deaths = new RecentDeaths();
	}

	@Test
	void theBodyIsACorpseForTwoSeconds() {
		Object body = new Object();
		this.deaths.record(KWAMIX, body, T0);
		assertTrue(this.deaths.isCorpse(KWAMIX, body, T0 + 50 * MS), "a spam click 1 tick after the kill");
		assertTrue(this.deaths.isCorpse(KWAMIX, body, T0 + 1_999 * MS));
		assertFalse(this.deaths.isCorpse(KWAMIX, body, T0 + 2_000 * MS));
	}

	@Test
	void aRespawnedPlayerIsANewInstanceAndNeverACorpse() {
		Object body = new Object();
		Object respawned = new Object();
		this.deaths.record(KWAMIX, body, T0);
		assertFalse(this.deaths.isCorpse(KWAMIX, respawned, T0 + 500 * MS));
		assertTrue(this.deaths.isCorpse(KWAMIX, body, T0 + 500 * MS));
	}

	@Test
	void otherPlayersAreUnaffected() {
		Object body = new Object();
		this.deaths.record(KWAMIX, body, T0);
		assertFalse(this.deaths.isCorpse(OTHER, body, T0 + MS));
		assertFalse(this.deaths.isCorpse(OTHER, new Object(), T0 + MS));
	}

	@Test
	void expireAndResetForget() {
		Object body = new Object();
		this.deaths.record(KWAMIX, body, T0);
		this.deaths.expire(T0 + 2_500 * MS);
		assertFalse(this.deaths.isCorpse(KWAMIX, body, T0 + 100 * MS), "expired entries are gone");
		this.deaths.record(KWAMIX, body, T0);
		this.deaths.reset();
		assertFalse(this.deaths.isCorpse(KWAMIX, body, T0 + 100 * MS));
	}

	@Test
	void nullsAreIgnored() {
		this.deaths.record(null, new Object(), T0);
		this.deaths.record(KWAMIX, null, T0);
		assertFalse(this.deaths.isCorpse(null, null, T0));
		assertFalse(this.deaths.isCorpse(KWAMIX, null, T0));
	}
}
