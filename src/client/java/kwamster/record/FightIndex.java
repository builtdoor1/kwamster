package kwamster.record;

import kwamster.KwamsterClient;
import kwamster.core.io.Json;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.stats.StatsCalculator;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The saved-fights index (§10): one {@link FightSummaryRow} per saved fight, newest first, kept in
 * memory and mirrored to {@code fights/index.json} as {@code {"schema":1,"fights":[...]}}.
 *
 * <p>{@link #load(Path, Path)} reads the file and reconciles it with the folder: rows whose file is
 * gone are dropped, fight files the index does not know (a crash between the fight write and the index
 * write) are added, and when the index is missing or unparsable it is rebuilt by scanning every
 * {@code fights/*.json}. Unparsable fight files are moved to {@code fights/broken/}.
 *
 * <p>Client thread only. The IO thread never sees this object; it receives {@link #toJson()} strings.
 */
public final class FightIndex {
	/** {@code schema} written into {@code index.json}. */
	public static final int SCHEMA = 1;
	/** The index file name inside the fights folder. */
	public static final String FILE_NAME = "index.json";
	/** Fight file extension. */
	public static final String FIGHT_EXTENSION = ".json";
	/** Longest accepted fight id (the base id is 36 characters; suffixes add a few). */
	private static final int MAX_ID_LENGTH = 96;

	/** Newest first, then by id descending, so the order is deterministic. */
	private static final Comparator<FightSummaryRow> NEWEST_FIRST =
			Comparator.<FightSummaryRow>comparingLong(r -> r.startEpochMs).reversed()
					.thenComparing(r -> r.id, Comparator.reverseOrder());

	private final List<FightSummaryRow> rows = new ArrayList<>();
	private @Nullable List<FightSummaryRow> snapshot;

	/** An unmodifiable view of the rows, newest first (rebuilt only after a change). */
	public List<FightSummaryRow> rows() {
		List<FightSummaryRow> s = this.snapshot;
		if (s == null) {
			s = Collections.unmodifiableList(new ArrayList<>(this.rows));
			this.snapshot = s;
		}
		return s;
	}

	public int size() {
		return this.rows.size();
	}

	/** The row with {@code id}, or null. */
	public @Nullable FightSummaryRow find(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (FightSummaryRow r : this.rows) {
			if (id.equals(r.id)) {
				return r;
			}
		}
		return null;
	}

	/** Adds a row (replacing any row with the same id) at its newest-first position. */
	public void add(FightSummaryRow row) {
		removeQuietly(row.id);
		int at = 0;
		while (at < this.rows.size() && NEWEST_FIRST.compare(this.rows.get(at), row) <= 0) {
			at++;
		}
		this.rows.add(at, row);
		this.snapshot = null;
	}

	/** Removes and returns the row with {@code id}, or null. */
	public @Nullable FightSummaryRow remove(@Nullable String id) {
		FightSummaryRow r = removeQuietly(id);
		if (r != null) {
			this.snapshot = null;
		}
		return r;
	}

	/** Sets the pin flag; true if it changed. */
	public boolean setPinned(@Nullable String id, boolean pinned) {
		FightSummaryRow r = find(id);
		if (r == null || r.pinned == pinned) {
			return false;
		}
		r.pinned = pinned;
		this.snapshot = null;
		return true;
	}

	/**
	 * Applies the pruning rule of §10: while more than {@code maxUnpinned} unpinned fights are indexed,
	 * unpinned fights are removed, oldest first: first the {@link FightSummaryRow#trivial trivial} ones
	 * (non-alert fights saved by {@code saveTrivialFights}), and only when none is left the real ones.
	 * Pinned fights are never removed. {@code keepId} is the fight just saved:
	 * <ul>
	 *   <li>a real one is never removed;</li>
	 *   <li>a trivial one is removed itself (last among the trivial ones) rather than letting a real fight
	 *       go, and the real pass does not run at all: a non-alert save never deletes a real fight. If the
	 *       index is still over the limit after that (the limit was lowered), the next real save prunes it.</li>
	 * </ul>
	 * So a lobby full of single whiffs can never push a real fight out, even when every slot holds one.
	 * Returns the removed rows, oldest first within each pass (their files still need deleting; when
	 * {@code keepId} itself is among them, its file was never written).
	 */
	public List<FightSummaryRow> prune(int maxUnpinned, @Nullable String keepId) {
		if (maxUnpinned <= 0) {
			return List.of();
		}
		int unpinned = 0;
		for (FightSummaryRow r : this.rows) {
			if (!r.pinned) {
				unpinned++;
			}
		}
		FightSummaryRow kept = find(keepId);
		boolean keepTrivial = kept != null && kept.trivial && !kept.pinned;
		List<FightSummaryRow> removed = new ArrayList<>();
		// Pass 0: trivial fights other than the one just saved, oldest first.
		unpinned = removeOldest(removed, unpinned, maxUnpinned, r -> r.trivial && r != kept);
		if (keepTrivial) {
			// The fight just saved is trivial: it goes before any real fight would, and no real fight goes.
			if (unpinned > maxUnpinned) {
				this.rows.remove(kept);
				removed.add(kept);
			}
		} else {
			// Pass 1: real fights other than the one just saved, oldest first.
			removeOldest(removed, unpinned, maxUnpinned, r -> !r.trivial && r != kept);
		}
		if (!removed.isEmpty()) {
			this.snapshot = null;
		}
		return removed;
	}

	/** Removes unpinned rows matching {@code which}, oldest first, until at most {@code max} unpinned remain. */
	private int removeOldest(List<FightSummaryRow> removed, int unpinned, int max, Predicate<FightSummaryRow> which) {
		for (int i = this.rows.size() - 1; i >= 0 && unpinned > max; i--) {
			FightSummaryRow r = this.rows.get(i);
			if (r.pinned || !which.test(r)) {
				continue;
			}
			this.rows.remove(i);
			removed.add(r);
			unpinned--;
		}
		return unpinned;
	}

	/** The on-disk form, {@code {"schema":1,"fights":[...]}} (compact). */
	public String toJson() {
		Disk disk = new Disk();
		disk.fights = new ArrayList<>(this.rows);
		return Json.GSON.toJson(disk);
	}

	// ---- loading --------------------------------------------------------------

	/**
	 * Loads {@code fightsDir/index.json} and reconciles it with the fight files in {@code fightsDir}
	 * (see the class comment). Never throws for IO or parse problems; they are logged.
	 *
	 * @return true when the in-memory index differs from the file and should be written back
	 */
	public boolean load(Path fightsDir, Path brokenDir) {
		this.rows.clear();
		this.snapshot = null;
		Path indexFile = fightsDir.resolve(FILE_NAME);
		List<FightSummaryRow> parsed = null;
		boolean indexExists = Files.isRegularFile(indexFile);
		if (indexExists) {
			try {
				parsed = parse(Files.readString(indexFile, StandardCharsets.UTF_8));
			} catch (IOException e) {
				KwamsterClient.LOG.warn("Could not read {}; rebuilding it from the fight files", FILE_NAME, e);
			}
			if (parsed == null) {
				KwamsterClient.LOG.warn("{} is unreadable; rebuilding it from the fight files", FILE_NAME);
			}
		}

		List<Path> files = fightFiles(fightsDir);
		Set<String> names = new HashSet<>();
		for (Path f : files) {
			names.add(f.getFileName().toString());
		}

		boolean dirty = (!indexExists && !files.isEmpty()) || (indexExists && parsed == null);
		Set<String> known = new HashSet<>();
		if (parsed != null) {
			for (FightSummaryRow row : parsed) {
				if (!names.contains(row.file) || known.contains(row.file) || find(row.id) != null) {
					dirty = true; // file gone, or a duplicate entry
					continue;
				}
				known.add(row.file);
				this.rows.add(row);
			}
		}
		for (Path f : files) {
			String name = f.getFileName().toString();
			if (known.contains(name)) {
				continue;
			}
			FightSummaryRow row = scanFile(f, brokenDir);
			if (row != null && find(row.id) == null) {
				this.rows.add(row);
				known.add(name);
			}
			dirty = true;
		}
		this.rows.sort(NEWEST_FIRST);
		return dirty;
	}

	/** Parses {@code index.json}; null if it is not a valid index. Invalid rows are skipped. */
	public static @Nullable List<FightSummaryRow> parse(String json) {
		Disk disk;
		try {
			disk = Json.GSON.fromJson(json, Disk.class);
		} catch (RuntimeException e) {
			return null; // malformed JSON or wrong shape
		}
		if (disk == null || disk.fights == null) {
			return null;
		}
		List<FightSummaryRow> out = new ArrayList<>(disk.fights.size());
		for (FightSummaryRow row : disk.fights) {
			FightSummaryRow clean = sanitize(row);
			if (clean != null) {
				out.add(clean);
			}
		}
		return out;
	}

	/** Parses one fight file's JSON; null if it is not a fight record. Null lists are replaced with empty ones. */
	public static @Nullable FightRecord parseFight(String json) {
		FightRecord r;
		try {
			r = Json.GSON.fromJson(json, FightRecord.class);
		} catch (RuntimeException e) {
			return null; // malformed JSON or wrong shape
		}
		if (r == null) {
			return null;
		}
		if (r.opponents == null) {
			r.opponents = new ArrayList<>();
		}
		if (r.opponentUuids == null) {
			r.opponentUuids = new ArrayList<>();
		}
		if (r.engaged == null) {
			r.engaged = new ArrayList<>();
		}
		if (r.swings == null) {
			r.swings = new ArrayList<>();
		}
		if (r.hitsTaken == null) {
			r.hitsTaken = new ArrayList<>();
		}
		if (r.jumps == null) {
			r.jumps = new ArrayList<>();
		}
		if (r.trades == null) {
			r.trades = new ArrayList<>();
		}
		if (r.damage == null) {
			r.damage = new ArrayList<>();
		}
		if (r.server == null) {
			r.server = "";
		}
		if (r.settings == null) {
			r.settings = FightRecord.Settings.defaults();
		}
		return r;
	}

	/**
	 * The index row of a saved fight, unpinned and not {@link FightSummaryRow#trivial trivial} (the file
	 * does not record the alert decision; {@link FightStore} marks a fresh non-alert fight itself).
	 *
	 * @param stats the final numbers; computed from the record when null
	 */
	public static FightSummaryRow rowFor(String id, String file, FightRecord r, @Nullable FightStats stats) {
		FightStats s = stats != null ? stats : (r.stats != null ? r.stats : StatsCalculator.compute(r, StatsCalculator.FINAL));
		FightSummaryRow row = new FightSummaryRow();
		row.id = id;
		row.file = file;
		row.opponent = r.opponentName == null ? "" : r.opponentName;
		row.result = resultName(r.result);
		row.server = r.server == null ? "" : r.server;
		row.startEpochMs = r.startEpochMs;
		row.durationMs = Math.max(0, r.durationMs);
		row.swings = s.swings;
		row.landed = s.landed;
		row.jumpAttempts = s.jumpAttempts;
		row.jumpSuccess = s.jumpSuccess;
		row.bestCombo = s.bestCombo;
		row.extraOpponents = extraEngaged(r);
		row.accuracyPct = finite(s.accuracyPct);
		row.reachAvg = finite(s.reachAvg);
		row.pinned = false;
		return row;
	}

	/** True for a fight id this store accepts as a file stem: 1–96 characters of {@code [A-Za-z0-9_-]}. */
	public static boolean isSafeId(@Nullable String id) {
		if (id == null || id.isEmpty() || id.length() > MAX_ID_LENGTH) {
			return false;
		}
		for (int i = 0; i < id.length(); i++) {
			char c = id.charAt(i);
			boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/** True for {@code <safe id>.json} other than {@code index.json}. */
	public static boolean isFightFileName(@Nullable String name) {
		return name != null && name.endsWith(FIGHT_EXTENSION) && !name.equals(FILE_NAME)
				&& isSafeId(name.substring(0, name.length() - FIGHT_EXTENSION.length()));
	}

	// ---- internals ------------------------------------------------------------

	private @Nullable FightSummaryRow removeQuietly(@Nullable String id) {
		if (id == null) {
			return null;
		}
		for (int i = 0; i < this.rows.size(); i++) {
			if (id.equals(this.rows.get(i).id)) {
				return this.rows.remove(i);
			}
		}
		return null;
	}

	/** Every {@code <safe id>.json} directly inside {@code fightsDir} (not {@code index.json}, not subfolders). */
	private static List<Path> fightFiles(Path fightsDir) {
		List<Path> out = new ArrayList<>();
		if (!Files.isDirectory(fightsDir)) {
			return out;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(fightsDir, "*" + FIGHT_EXTENSION)) {
			for (Path p : stream) {
				if (isFightFileName(p.getFileName().toString()) && Files.isRegularFile(p)) {
					out.add(p);
				}
			}
		} catch (IOException e) {
			KwamsterClient.LOG.warn("Could not list {}", fightsDir, e);
		}
		return out;
	}

	/** Reads one fight file into a row; moves it to {@code brokenDir} if it is not a fight record. */
	private static @Nullable FightSummaryRow scanFile(Path file, Path brokenDir) {
		String name = file.getFileName().toString();
		FightRecord r = null;
		try {
			r = parseFight(Files.readString(file, StandardCharsets.UTF_8));
		} catch (IOException e) {
			// A read error (not UTF-8, locked) is treated like bad JSON only when it is a decoding error.
			if (!(e instanceof CharacterCodingException)) {
				KwamsterClient.LOG.warn("Could not read {}; leaving it out of the index for now", file, e);
				return null;
			}
		}
		if (r == null) {
			moveBroken(file, brokenDir);
			return null;
		}
		String id = name.substring(0, name.length() - FIGHT_EXTENSION.length());
		return rowFor(id, name, r, r.stats);
	}

	private static void moveBroken(Path file, Path brokenDir) {
		try {
			Files.createDirectories(brokenDir);
			Files.move(file, brokenDir.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
			KwamsterClient.LOG.warn("{} is not a readable fight; moved it to fights/broken/", file.getFileName());
		} catch (IOException e) {
			KwamsterClient.LOG.warn("{} is not a readable fight and could not be moved aside", file, e);
		}
	}

	/** A clean copy of a row read from disk, or null if it cannot be used. */
	private static @Nullable FightSummaryRow sanitize(@Nullable FightSummaryRow row) {
		if (row == null || !isSafeId(row.id)) {
			return null;
		}
		String file = row.file == null ? row.id + FIGHT_EXTENSION : row.file;
		if (!isFightFileName(file)) {
			return null;
		}
		row.file = file;
		row.opponent = row.opponent == null ? "" : row.opponent;
		row.result = resultName(row.result);
		row.server = row.server == null ? "" : row.server;
		row.durationMs = Math.max(0L, row.durationMs);
		row.swings = Math.max(0, row.swings);
		row.landed = Math.max(0, row.landed);
		row.jumpAttempts = Math.max(0, row.jumpAttempts);
		row.jumpSuccess = Math.max(0, row.jumpSuccess);
		row.bestCombo = Math.max(0, row.bestCombo);
		row.extraOpponents = Math.max(0, row.extraOpponents);
		row.accuracyPct = finite(row.accuracyPct);
		row.reachAvg = finite(row.reachAvg);
		return row;
	}

	private static String resultName(@Nullable String result) {
		if (result != null) {
			for (FightResult r : FightResult.values()) {
				if (r.name().equals(result)) {
					return result;
				}
			}
		}
		return FightResult.DRAW.name();
	}

	/** Engaged opponents other than the primary. */
	private static int extraEngaged(FightRecord r) {
		if (r.engaged == null || r.opponentUuids == null) {
			return 0;
		}
		int primary = r.opponentUuid == null ? -1 : r.opponentUuids.indexOf(r.opponentUuid);
		int extra = 0;
		boolean primarySeen = false;
		for (int i = 0; i < r.engaged.size(); i++) {
			if (Boolean.TRUE.equals(r.engaged.get(i))) {
				if (i == primary) {
					primarySeen = true;
				} else {
					extra++;
				}
			}
		}
		// Without a resolvable primary, one engaged opponent is the primary itself.
		return primarySeen || extra == 0 ? extra : extra - 1;
	}

	private static double finite(double v) {
		return Double.isFinite(v) ? v : 0.0;
	}

	/** The JSON shape of {@code index.json}. */
	private static final class Disk {
		int schema = SCHEMA;
		List<FightSummaryRow> fights = new ArrayList<>();
	}
}
