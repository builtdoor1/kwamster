package kwamster.capture;

import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the result of a fight out of a server chat line, action-bar message or title (§5.13a).
 * Minecraft-free: the caller passes plain text with formatting stripped, your name and the names of
 * everyone in the running fight.
 *
 * <p>Three kinds of message decide a fight:
 * <ul>
 *   <li><b>Kill messages.</b> Whoever the message says died lost: "Kwamix was slain by builtdoor",
 *       "builtdoor eliminated Kwamix", "You died", "Kwamix fell into the void whilst fighting builtdoor". In
 *       the passive form ("was … by", or a verb like "died" or "fell") the victim comes before the verb; in
 *       the active form ("killed", "eliminated") after it. Fancy kill messages of the form
 *       "&lt;victim&gt; was &lt;anything&gt; by &lt;killer&gt;" count when they name you and an opponent.</li>
 *   <li><b>Winner announcements.</b> "Winner: builtdoor", "Loser: Kwamix", "Kwamix won the duel!",
 *       "You win!", "You lost the match", "Round 1 won by builtdoor".</li>
 *   <li><b>One-word titles.</b> "VICTORY!" or "WINNER!", and "DEFEAT!" or "ELIMINATED!".</li>
 * </ul>
 *
 * <p>A chat line spoken by you or an opponent ("&lt;Kwamix&gt; you died lol", "[VIP] Kwamix: gg") is never
 * read, because many servers send player chat as system messages. A message about players outside the
 * fight decides nothing, so another kill in the same arena does not end yours.
 */
public final class OutcomeText {
	/**
	 * What a message says.
	 *
	 * @param win      true when you won or the opponent died
	 * @param opponent the opponent named as the one who died or lost, spelled as passed in; null when the
	 *                 message names no opponent ("VICTORY!", "Winner: builtdoor") or says you lost
	 */
	public record Verdict(boolean win, @Nullable String opponent) {
	}

	/** Longer messages (help pages, MOTDs, match summaries) are skipped. */
	public static final int MAX_LENGTH = 256;

	private static final int NONE = 0;
	private static final int SELF = 1;
	private static final int OPP = 2;

	/** Sentence breaks; a dot between digits ("3.5❤") is not one. */
	private static final Pattern CLAUSES = Pattern.compile("(?<!\\d)\\.(?!\\d)|[!?|•»\\n;]|\\s[-–—]\\s");
	private static final Pattern WORD = Pattern.compile("[a-z0-9_]+");
	private static final Pattern NOT_WORD = Pattern.compile("[^a-z0-9_]");
	private static final Pattern NUMBER = Pattern.compile("\\d+");

	/** Verbs that only appear in kill messages. Hit verbs ("shot", "smashed") are left out: some servers report hits with them. */
	private static final Set<String> KILL_WORDS = Set.of(
			"killed", "slain", "slew", "slayed", "eliminated", "murdered", "executed", "destroyed", "obliterated",
			"annihilated", "vaporized", "vaporised", "demolished", "slaughtered", "butchered", "terminated",
			"finished", "defeated", "bested", "vanquished", "rekt", "wrecked", "pwned", "deleted", "dispatched");
	/** Death words whose subject is the victim even without "was": "Kwamix died", "Kwamix fell into the void". */
	private static final Set<String> VICTIM_FIRST = Set.of(
			"died", "dies", "dead", "perished", "fell", "drowned", "burned", "burnt", "exploded", "starved",
			"suffocated", "froze", "withered");
	private static final Set<String> PASSIVE = Set.of("was", "were", "been", "got", "gets", "is", "are");
	/** "&lt;a&gt; was &lt;these&gt; by &lt;b&gt;" is not a kill. */
	private static final Set<String> NOT_A_KILL = Set.of(
			"hit", "tagged", "combat", "challenged", "invited", "sent", "requested", "accepted", "declined",
			"denied", "healed", "damaged", "attacked", "targeted", "spotted", "marked", "revealed", "teleported",
			"queued", "matched", "paired", "warned", "muted", "kicked", "banned", "reported", "followed", "added",
			"removed", "promoted", "joined", "spectated", "watched");
	private static final Set<String> WINNER = Set.of("winner", "winners", "victor", "champion");
	private static final Set<String> LOSER = Set.of("loser", "losers");
	private static final Set<String> WON = Set.of("won", "wins", "win");
	private static final Set<String> LOST = Set.of("lost", "loses", "lose");
	/** Words between a name and "won"/"lost": "you have won", "Kwamix has lost". */
	private static final Set<String> AUX = Set.of("has", "have", "just");
	/** "won 50 coins", "win streak", "lost connection" are not results. */
	private static final Set<String> NOT_A_RESULT = Set.of(
			"coins", "coin", "xp", "exp", "tokens", "token", "points", "point", "gold", "credits", "credit", "gems",
			"gem", "money", "streak", "streaks", "winstreak", "rate", "chance", "connection");
	private static final List<List<String>> WIN_TITLES = List.of(
			List.of("victory"), List.of("victory", "royale"), List.of("winner"));
	private static final List<List<String>> LOSS_TITLES = List.of(
			List.of("defeat"), List.of("defeated"), List.of("eliminated"));

	private OutcomeText() {
	}

	/**
	 * The result {@code text} names, or null when it names none.
	 *
	 * @param text          the message, formatting codes already stripped
	 * @param selfName      your name ("you" always counts as you too)
	 * @param opponentNames everyone in the running fight
	 * @param chatLine      true for chat and action-bar messages, which may be a player talking; false for titles
	 */
	public static @Nullable Verdict classify(String text, String selfName, Collection<String> opponentNames, boolean chatLine) {
		if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
			return null;
		}
		String self = token(selfName);
		Map<String, String> opps = new HashMap<>();
		if (opponentNames != null) {
			for (String name : opponentNames) {
				String key = token(name);
				if (!key.isEmpty()) {
					opps.put(key, name.trim());
				}
			}
		}
		opps.remove(self);
		opps.remove("you");
		String lower = text.toLowerCase(Locale.ROOT);
		if (chatLine && spokenByParty(lower, self, opps)) {
			return null;
		}
		for (String clause : CLAUSES.split(lower)) {
			List<String> words = words(clause);
			if (!words.isEmpty()) {
				Verdict v = clause(words, self, opps);
				if (v != null) {
					return v;
				}
			}
		}
		return null;
	}

	/**
	 * A chat line whose speaker is you or an opponent: up to four words, then ':', '»' or '>' (this covers
	 * "&lt;Kwamix&gt; …", "Kwamix: …", "[VIP] Kwamix » …" and "VIP Kwamix: …"). "Winner: builtdoor" passes,
	 * since "Winner" is nobody in the fight.
	 */
	private static boolean spokenByParty(String lower, String self, Map<String, String> opps) {
		int limit = Math.min(lower.length(), 48);
		for (int i = 0; i < limit; i++) {
			char c = lower.charAt(i);
			if (c == ':' || c == '»' || c == '>') {
				List<String> prefix = words(lower.substring(0, i));
				if (prefix.isEmpty() || prefix.size() > 4) {
					return false;
				}
				String speaker = prefix.get(prefix.size() - 1);
				return speaker.equals(self) || opps.containsKey(speaker);
			}
		}
		return false;
	}

	private static @Nullable Verdict clause(List<String> w, String self, Map<String, String> opps) {
		int n = w.size();
		int[] role = new int[n];
		for (int i = 0; i < n; i++) {
			String t = w.get(i);
			role[i] = t.equals("you") || (!self.isEmpty() && t.equals(self)) ? SELF : opps.containsKey(t) ? OPP : NONE;
		}

		// 1. a kill message with a kill or death word
		for (int k = 0; k < n; k++) {
			String cue = w.get(k);
			boolean victimFirst = VICTIM_FIRST.contains(cue);
			if (!victimFirst && !KILL_WORDS.contains(cue)) {
				continue;
			}
			boolean passive = victimFirst || passiveAt(w, k) || w.subList(k + 1, n).contains("by");
			if (passive) {
				int victim = lastRole(role, 0, k);
				if (victim >= 0) {
					return mixed(role, 0, k) ? null : died(role[victim], w.get(victim), opps);
				}
			} else {
				int victim = firstRole(role, k + 1, n);
				if (victim < 0 && onlyNumbers(w, k + 1)) {
					// "Kwamix eliminated!": nothing after the verb, so its subject is the one who died
					victim = lastRole(role, 0, k);
					if (victim >= 0 && mixed(role, 0, k)) {
						return null;
					}
				}
				if (victim >= 0) {
					return died(role[victim], w.get(victim), opps);
				}
			}
		}

		// 2. "<victim> was <anything> by <killer>", naming you and an opponent
		for (int by = w.indexOf("by"); by >= 0; by = indexOf(w, "by", by + 1)) {
			int marker = -1;
			for (int i = by - 1; i >= 0 && by - i <= 8; i--) {
				if (PASSIVE.contains(w.get(i))) {
					marker = i;
					break;
				}
			}
			if (marker < 0 || containsAny(w, marker + 1, by, NOT_A_KILL)) {
				continue;
			}
			int victim = lastRole(role, 0, marker);
			int killer = firstRole(role, by + 1, n);
			if (victim >= 0 && killer >= 0 && role[victim] != role[killer]) {
				return died(role[victim], w.get(victim), opps);
			}
		}

		// 3. winner and loser announcements
		for (int i = 0; i < n; i++) {
			String t = w.get(i);
			if (WINNER.contains(t) || LOSER.contains(t)) {
				int who = firstRole(role, i + 1, Math.min(n, i + 4));
				if (who < 0) {
					who = lastRole(role, Math.max(0, i - 3), i);
				}
				if (who >= 0) {
					boolean selfWon = WINNER.contains(t) == (role[who] == SELF);
					return selfWon ? new Verdict(true, role[who] == OPP ? opps.get(w.get(who)) : null) : new Verdict(false, null);
				}
			} else if (WON.contains(t) || LOST.contains(t)) {
				if (i + 1 < n && (NUMBER.matcher(w.get(i + 1)).matches() || NOT_A_RESULT.contains(w.get(i + 1)))) {
					continue;
				}
				int who = -1;
				if (i >= 1 && role[i - 1] != NONE) {
					who = i - 1;
				} else if (i >= 2 && role[i - 2] != NONE && AUX.contains(w.get(i - 1)) && !t.equals("win") && !t.equals("lose")) {
					who = i - 2;
				} else if (i + 2 < n && w.get(i + 1).equals("by") && role[i + 2] != NONE) {
					who = i + 2; // "Round 1 won by builtdoor"
				}
				if (who >= 0) {
					boolean selfWon = WON.contains(t) == (role[who] == SELF);
					return selfWon ? new Verdict(true, role[who] == OPP ? opps.get(w.get(who)) : null) : new Verdict(false, null);
				}
			}
		}

		// 4. one-word titles
		List<String> core = new ArrayList<>(n);
		for (String t : w) {
			if (!NUMBER.matcher(t).matches()) {
				core.add(t);
			}
		}
		if (WIN_TITLES.contains(core)) {
			return new Verdict(true, null);
		}
		if (LOSS_TITLES.contains(core)) {
			return new Verdict(false, null);
		}
		return null;
	}

	/** The verdict when the player at {@code role} died. */
	private static Verdict died(int role, String word, Map<String, String> opps) {
		return role == SELF ? new Verdict(false, null) : new Verdict(true, opps.get(word));
	}

	/** "was killed", "has been killed", "got absolutely destroyed": a passive marker one or two words before {@code k}. */
	private static boolean passiveAt(List<String> w, int k) {
		return (k >= 1 && PASSIVE.contains(w.get(k - 1))) || (k >= 2 && PASSIVE.contains(w.get(k - 2)));
	}

	/** Both you and an opponent are named in [from, to): the victim is unclear. */
	private static boolean mixed(int[] role, int from, int to) {
		boolean self = false;
		boolean opp = false;
		for (int i = from; i < to; i++) {
			self |= role[i] == SELF;
			opp |= role[i] == OPP;
		}
		return self && opp;
	}

	private static int firstRole(int[] role, int from, int to) {
		for (int i = from; i < to; i++) {
			if (role[i] != NONE) {
				return i;
			}
		}
		return -1;
	}

	private static int lastRole(int[] role, int from, int to) {
		for (int i = to - 1; i >= from; i--) {
			if (role[i] != NONE) {
				return i;
			}
		}
		return -1;
	}

	private static int indexOf(List<String> w, String word, int from) {
		for (int i = from; i < w.size(); i++) {
			if (w.get(i).equals(word)) {
				return i;
			}
		}
		return -1;
	}

	private static boolean onlyNumbers(List<String> w, int from) {
		for (int i = from; i < w.size(); i++) {
			if (!NUMBER.matcher(w.get(i)).matches()) {
				return false;
			}
		}
		return true;
	}

	private static boolean containsAny(List<String> w, int from, int to, Set<String> set) {
		for (int i = from; i < to; i++) {
			if (set.contains(w.get(i))) {
				return true;
			}
		}
		return false;
	}

	/** A name as the word it becomes in a message: lower case, only [a-z0-9_] (Geyser's ".Name" matches "Name"). */
	private static String token(@Nullable String name) {
		return name == null ? "" : NOT_WORD.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("");
	}

	private static List<String> words(String s) {
		List<String> out = new ArrayList<>();
		Matcher m = WORD.matcher(s);
		while (m.find()) {
			out.add(m.group());
		}
		return out;
	}
}
