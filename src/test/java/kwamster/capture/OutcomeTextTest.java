package kwamster.capture;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** §5.13a: kill messages, winner announcements and titles that decide a fight; chat that must not. */
final class OutcomeTextTest {
	private static final String SELF = "builtdoor";
	private static final List<String> OPPS = List.of("Kwamix", "Third");

	private static OutcomeText.Verdict chat(String text) {
		return OutcomeText.classify(text, SELF, OPPS, true);
	}

	private static void win(String text, String opponent) {
		OutcomeText.Verdict v = chat(text);
		assertNotNull(v, text);
		assertEquals(true, v.win(), text);
		assertEquals(opponent, v.opponent(), text);
	}

	private static void loss(String text) {
		OutcomeText.Verdict v = chat(text);
		assertNotNull(v, text);
		assertEquals(false, v.win(), text);
		assertNull(v.opponent(), text);
	}

	private static void nothing(String text) {
		assertNull(chat(text), text);
	}

	@Test
	void passiveKillMessagesNameTheVictimFirst() {
		win("Kwamix was slain by builtdoor", "Kwamix");
		win("Kwamix was killed by you", "Kwamix");
		win("Kwamix has been eliminated by builtdoor!", "Kwamix");
		win("Kwamix eliminated by builtdoor", "Kwamix");
		win("KWAMIX was slain by BUILTDOOR", "Kwamix");
		win("Kwamix (3.5❤) was killed by builtdoor (10❤)", "Kwamix");
		win("[Duels] Kwamix was slain by builtdoor using [Diamond Sword]", "Kwamix");
		win("Duels » Kwamix was eliminated by builtdoor", "Kwamix");
		loss("builtdoor was slain by Kwamix");
		loss("You were killed by Kwamix's arrow");
		loss("builtdoor has been eliminated!");
	}

	@Test
	void activeKillMessagesNameTheVictimAfterTheVerb() {
		win("You killed Kwamix!", "Kwamix");
		win("builtdoor killed Kwamix with 3.5❤ left", "Kwamix");
		win("builtdoor defeated Kwamix", "Kwamix");
		win("Steve killed Kwamix", "Kwamix");
		loss("Kwamix has eliminated you");
		loss("Kwamix defeated you");
		win("Kwamix eliminated!", "Kwamix");
	}

	@Test
	void deathVerbsMakeTheSubjectTheVictim() {
		win("Kwamix died", "Kwamix");
		win("Kwamix fell into the void whilst fighting builtdoor", "Kwamix");
		win("Kwamix is dead!", "Kwamix");
		loss("You died!");
		loss("builtdoor fell off the platform");
	}

	@Test
	void fancyKillMessagesNeedBothPlayers() {
		win("Kwamix was given the cold shoulder by builtdoor", "Kwamix");
		loss("You were turned to dust by Kwamix");
		nothing("Kwamix was hit by builtdoor");
		nothing("Kwamix was challenged by builtdoor");
		nothing("Kwamix was tagged by you");
		nothing("Kwamix was struck by lightning");
	}

	@Test
	void winnerAndLoserAnnouncements() {
		win("Winner: builtdoor - Loser: Kwamix", null);
		loss("Winner: Kwamix (3.5❤)");
		win("Loser: Kwamix", "Kwamix");
		win("The winner is builtdoor", null);
		win("builtdoor is the winner!", null);
		loss("Kwamix won the duel!");
		loss("Kwamix wins!");
		win("You won the duel against Kwamix!", null);
		win("You win!", null);
		win("You have won the match", null);
		loss("You lost the match");
		win("Kwamix lost to builtdoor", "Kwamix");
		win("Round 1 won by builtdoor", null);
	}

	@Test
	void titles() {
		assertEquals(new OutcomeText.Verdict(true, null), OutcomeText.classify("VICTORY!", SELF, OPPS, false));
		assertEquals(new OutcomeText.Verdict(true, null), OutcomeText.classify("#1 Victory Royale", SELF, OPPS, false));
		assertEquals(new OutcomeText.Verdict(true, null), OutcomeText.classify("WINNER!", SELF, OPPS, false));
		assertEquals(new OutcomeText.Verdict(false, null), OutcomeText.classify("DEFEAT!", SELF, OPPS, false));
		assertEquals(new OutcomeText.Verdict(false, null), OutcomeText.classify("ELIMINATED", SELF, OPPS, false));
		assertNull(OutcomeText.classify("DRAW!", SELF, OPPS, false));
		assertNull(OutcomeText.classify("Fight!", SELF, OPPS, false));
	}

	@Test
	void playerChatFromAnyoneInTheFightIsIgnored() {
		nothing("<Kwamix> you died lol");
		nothing("[VIP] Kwamix: i killed you");
		nothing("VIP Kwamix: you died");
		nothing("Kwamix » gg you lost");
		nothing("builtdoor: Kwamix died");
		nothing("<builtdoor> I killed Kwamix");
		// A title is never chat, so a colon does not hide it.
		assertEquals(new OutcomeText.Verdict(false, null), OutcomeText.classify("Kwamix: you lose", SELF, OPPS, false));
	}

	@Test
	void messagesAboutOtherPlayersOrNotAboutResultsDecideNothing() {
		nothing("Kwamix killed Steve");
		nothing("Steve was killed by Bob");
		nothing("Kwamix and builtdoor were killed");
		nothing("You won 50 coins!");
		nothing("Kwamix's win streak: 5");
		nothing("Kwamix lost connection");
		nothing("Kwamix has 3.5❤ left");
		nothing("Match started: builtdoor vs Kwamix");
		nothing("You are now spectating Kwamix");
		nothing("Kwamix needs to win");
		nothing("builtdoor killed 3 players today");
		nothing("You were teleported to spawn");
		nothing("");
		nothing("a".repeat(OutcomeText.MAX_LENGTH + 1));
	}

	@Test
	void namesMatchTheWordsTheyBecome() {
		OutcomeText.Verdict v = OutcomeText.classify(".Kwamix was slain by builtdoor", SELF, List.of(".Kwamix"), true);
		assertEquals(new OutcomeText.Verdict(true, ".Kwamix"), v, "a Geyser player's dot prefix");
		assertNull(OutcomeText.classify("Kwamix was slain by builtdoor", SELF, List.of("", "  "), true), "blank names are skipped");
	}

	@Test
	void yourOwnDeathNeedsNoOpponentNames() {
		assertEquals(new OutcomeText.Verdict(false, null), OutcomeText.classify("You died!", SELF, List.of(), true));
		assertEquals(new OutcomeText.Verdict(false, null), OutcomeText.classify("builtdoor was slain by Zed", SELF, List.of(), true));
		assertNull(OutcomeText.classify("Zed was slain by builtdoor", SELF, List.of(), true), "Zed is not in the fight");
	}
}
