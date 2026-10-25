package kwamster.record;

import kwamster.KwamsterClient;
import kwamster.core.io.AtomicFiles;
import kwamster.core.io.Json;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import kwamster.core.model.StatsTotals;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Arrays;

/**
 * Lifetime totals over every counted fight, persisted as {@code lifetime.json} (pretty Gson
 * {@link StatsTotals}, §10).
 *
 * <ul>
 *   <li>A missing file starts empty with {@code sinceEpochMs = now}; nothing is written until the first
 *       counted fight or a reset.</li>
 *   <li>A broken file is moved aside ({@code lifetime.broken-<epochMs>.json}) and the totals start
 *       empty.</li>
 * </ul>
 *
 * <p>Client thread only. Writes go through {@link FightStore}'s IO executor as {@link #toJson()}
 * strings.
 */
public final class LifetimeStore {
	/** The file name inside the data folder. */
	public static final String FILE_NAME = "lifetime.json";

	private final Path file;
	private StatsTotals totals = new StatsTotals();

	public LifetimeStore(Path file) {
		this.file = file;
	}

	/** The file this store mirrors. */
	public Path file() {
		return this.file;
	}

	/** Reads the file (see the class comment). Never throws. */
	public void load(long nowEpochMs) {
		String text;
		try {
			text = Files.readString(this.file, StandardCharsets.UTF_8);
		} catch (NoSuchFileException e) {
			this.totals = fresh(nowEpochMs);
			return;
		} catch (CharacterCodingException e) {
			recoverBroken(nowEpochMs, e);
			return;
		} catch (IOException e) {
			KwamsterClient.LOG.warn("Could not read {}; lifetime totals start empty for this session", this.file, e);
			this.totals = fresh(nowEpochMs);
			return;
		}
		StatsTotals parsed;
		try {
			parsed = Json.GSON.fromJson(text, StatsTotals.class);
		} catch (RuntimeException e) {
			recoverBroken(nowEpochMs, e);
			return;
		}
		if (parsed == null) {
			recoverBroken(nowEpochMs, null);
			return;
		}
		this.totals = repair(parsed, nowEpochMs);
	}

	/** A fresh copy of the totals (callers cannot change the stored ones). */
	public StatsTotals view() {
		return copy(this.totals);
	}

	/** Adds one counted fight. */
	public void add(FightRecord r, FightStats s) {
		this.totals.add(r, s);
	}

	/** Starts over with empty totals since {@code nowEpochMs}. */
	public void reset(long nowEpochMs) {
		this.totals = fresh(nowEpochMs);
	}

	/** The pretty JSON of the current totals, serialised now (client thread). */
	public String toJson() {
		return Json.PRETTY.toJson(this.totals);
	}

	private void recoverBroken(long nowEpochMs, @Nullable Exception cause) {
		try {
			Path backup = AtomicFiles.backupBroken(this.file, nowEpochMs);
			KwamsterClient.LOG.warn("{} is unreadable ({}); moved it to {} and started the lifetime totals over",
					this.file.getFileName(), firstLine(cause), backup.getFileName());
		} catch (IOException e) {
			KwamsterClient.LOG.warn("{} is unreadable and could not be moved aside; lifetime totals start empty",
					this.file, e);
		}
		this.totals = fresh(nowEpochMs);
	}

	private static String firstLine(@Nullable Exception e) {
		if (e == null || e.getMessage() == null) {
			return e == null ? "empty file" : e.getClass().getSimpleName();
		}
		return e.getMessage().lines().findFirst().orElse("");
	}

	private static StatsTotals fresh(long nowEpochMs) {
		StatsTotals t = new StatsTotals();
		t.sinceEpochMs = nowEpochMs;
		return t;
	}

	/** Fixes what a hand-edited file can break: null or short arrays, non-finite sums, a missing start date. */
	private static StatsTotals repair(StatsTotals t, long nowEpochMs) {
		if (t.aim == null) {
			t.aim = new int[4];
		} else if (t.aim.length < 4) {
			t.aim = Arrays.copyOf(t.aim, 4);
		}
		if (t.quality == null) {
			t.quality = new int[7];
		} else if (t.quality.length < 7) {
			t.quality = Arrays.copyOf(t.quality, 7);
		}
		if (!Double.isFinite(t.reachSum)) {
			t.reachSum = 0.0;
		}
		if (!Double.isFinite(t.reachMax)) {
			t.reachMax = 0.0;
		}
		if (!Double.isFinite(t.intervalSum)) {
			t.intervalSum = 0.0;
		}
		if (t.sinceEpochMs <= 0L) {
			t.sinceEpochMs = nowEpochMs;
		}
		return t;
	}

	private static StatsTotals copy(StatsTotals t) {
		StatsTotals c = Json.GSON.fromJson(Json.GSON.toJson(t), StatsTotals.class);
		return c == null ? new StatsTotals() : c;
	}
}
