package kwamster.record;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.io.AtomicFiles;
import kwamster.core.io.Json;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.ShareLink;
import kwamster.core.model.StatsTotals;
import kwamster.core.service.FightArchive;
import kwamster.core.stats.StatsCalculator;
import org.jspecify.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * RECORD's {@link FightArchive}: saves finished fights, keeps the index and the lifetime totals,
 * prunes, deletes, pins and builds share links (§10).
 *
 * <p><b>Threads.</b> Every public method runs on the client thread. All decisions, all state changes
 * and all JSON serialisation happen there; the single daemon {@code KWAMSTER-IO} thread only receives
 * immutable {@code (Path, String)} writes and {@code Path} deletions, in order, and hands them to
 * {@link AtomicFiles} (R2d). So the IO thread never sees a live list, record or totals object.
 *
 * <p><b>Save order</b> per fight: fight file, index, prune deletions, lifetime. {@link #shutdown()},
 * called once by CORE's {@code CLIENT_STOPPING} handler after the last fight was finalised, drains the
 * queue for at most two seconds.
 */
public final class FightStore implements FightArchive {
	/** Name of the IO thread. */
	public static final String IO_THREAD_NAME = "KWAMSTER-IO";
	/** How long {@link #shutdown()} waits for queued writes. */
	public static final long SHUTDOWN_WAIT_MS = 2000L;
	/** Recently saved fights kept as JSON so {@link #load} works before their write has landed. */
	private static final int RECENT_JSON = 8;
	/** Share links cached per record object (the HUD card and the chat line ask for the same fight). */
	private static final int LINK_CACHE = 8;
	/** Upper bound for the collision suffix search. */
	private static final int MAX_SUFFIX = 10_000;

	private final Path dataDir;
	private final Path fightsDir;
	private final Path brokenDir;
	private final Path indexFile;
	private final FightIndex index = new FightIndex();
	private final LifetimeStore lifetime;
	private final ExecutorService io;
	private final Map<String, String> recentJson = lru(RECENT_JSON);
	private final Map<LinkKey, Optional<ShareLink>> links = lru(LINK_CACHE);
	private boolean shutdown;

	/** @param dataDir {@code Kwamster.dataDir()}: fights live in {@code fights/}, lifetime totals in {@code lifetime.json} */
	public FightStore(Path dataDir) {
		this.dataDir = dataDir;
		this.fightsDir = dataDir.resolve("fights");
		this.brokenDir = this.fightsDir.resolve("broken");
		this.indexFile = this.fightsDir.resolve(FightIndex.FILE_NAME);
		this.lifetime = new LifetimeStore(dataDir.resolve(LifetimeStore.FILE_NAME));
		this.io = Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, IO_THREAD_NAME);
			t.setDaemon(true);
			return t;
		});
	}

	/**
	 * Reads the index (reconciling it with the fight files, or rebuilding it) and the lifetime totals.
	 * A repaired index is written back unless the master switch is off (R4: no file writes while
	 * disabled; it is then repaired in memory only).
	 */
	public void init() {
		boolean dirty = false;
		try {
			dirty = this.index.load(this.fightsDir, this.brokenDir);
		} catch (Throwable t) {
			KwamsterClient.LOG.error("Could not load the saved-fights index", t);
		}
		this.lifetime.load(Clock.epochMs());
		if (dirty && Kwamster.config().general.enabled) {
			enqueueIndexWrite();
		}
		KwamsterClient.LOG.info("Saved fights: {}", this.index.size());
	}

	// ---- saving ---------------------------------------------------------------

	/**
	 * The §10 save decision for a finished fight: save when {@code saveFights} and
	 * ({@code alert} or {@code saveTrivialFights}). The master switch is deliberately not checked, so a
	 * fight finalised as DISABLED is still saved (R4). When saving: give the fight a unique id (updating
	 * {@code record.id}), update the in-memory index (a non-alert fight's row is marked
	 * {@link FightSummaryRow#trivial}, so pruning removes it first) and, for counted fights ({@code alert}),
	 * the lifetime totals, then enqueue fight file, index, prune deletions and lifetime, in that order. A
	 * non-alert fight that would have to push a real fight out (every unpinned slot holds a real fight) is
	 * pruned itself instead: nothing of it is written.
	 */
	public void onFightEnded(FightEndedEvent e) {
		FightRecord r = e.record();
		if (r == null) {
			return;
		}
		KwamsterConfig.Recording rec = Kwamster.config().recording;
		if (!rec.saveFights || !(e.alert() || rec.saveTrivialFights)) {
			return;
		}
		FightStats s = e.stats() != null ? e.stats()
				: (r.stats != null ? r.stats : StatsCalculator.compute(r, StatsCalculator.FINAL));

		String base = FightIndex.isSafeId(r.id) ? r.id : FightRecord.baseId(r.startEpochMs, r.opponentName);
		String id = uniqueId(base);
		r.id = id;
		String file = id + FightIndex.FIGHT_EXTENSION;
		String json;
		try {
			json = Json.GSON.toJson(r);
		} catch (RuntimeException ex) {
			LogLimiter.error("FightStore.save", "Could not serialise fight " + id + "; it was not saved", ex);
			return;
		}

		FightSummaryRow row = FightIndex.rowFor(id, file, r, s);
		row.trivial = !e.alert(); // pruned before any real fight
		this.index.add(row);
		this.recentJson.put(id, json);
		List<FightSummaryRow> pruned = rec.maxSavedFights > 0 ? this.index.prune(rec.maxSavedFights, id) : List.of();
		boolean savedPruned = false;
		for (FightSummaryRow p : pruned) {
			this.recentJson.remove(p.id);
			savedPruned |= id.equals(p.id);
		}
		String lifetimeJson = null;
		if (e.alert()) {
			this.lifetime.add(r, s);
			lifetimeJson = serialise(this.lifetime::toJson, "lifetime totals");
		}
		// Only a trivial fight can prune itself; then the index changed only if older trivial fights went too.
		boolean indexChanged = !savedPruned || pruned.size() > 1;
		String indexJson = indexChanged ? serialise(this.index::toJson, "index") : null;

		if (!savedPruned) {
			enqueue(new WriteJob(this.fightsDir.resolve(file), json));
		}
		if (indexJson != null) {
			enqueue(new WriteJob(this.indexFile, indexJson));
		}
		for (FightSummaryRow p : pruned) {
			if (!id.equals(p.id)) {
				enqueue(new DeleteJob(this.fightsDir.resolve(p.file)));
			}
		}
		if (lifetimeJson != null) {
			enqueue(new WriteJob(this.lifetime.file(), lifetimeJson));
		}
		if (savedPruned) {
			KwamsterClient.LOG.debug("Trivial fight {} not kept: all {} unpinned slots hold real fights", id, rec.maxSavedFights);
		}
		if (pruned.size() > (savedPruned ? 1 : 0)) {
			KwamsterClient.LOG.debug("Pruned {} old fight(s) over the limit of {}", pruned.size() - (savedPruned ? 1 : 0),
					rec.maxSavedFights);
		}
	}

	// ---- FightArchive ---------------------------------------------------------

	@Override
	public List<FightSummaryRow> list() {
		return this.index.rows();
	}

	@Override
	public Optional<FightRecord> load(String id) {
		if (id == null) {
			return Optional.empty();
		}
		String json = this.recentJson.get(id);
		if (json == null) {
			FightSummaryRow row = this.index.find(id);
			String file = row != null ? row.file
					: (FightIndex.isSafeId(id) ? id + FightIndex.FIGHT_EXTENSION : null);
			if (file == null) {
				return Optional.empty();
			}
			Path path = this.fightsDir.resolve(file);
			if (!Files.isRegularFile(path)) {
				return Optional.empty();
			}
			try {
				json = Files.readString(path, StandardCharsets.UTF_8);
			} catch (IOException ex) {
				LogLimiter.error("FightStore.load", "Could not read " + path.getFileName(), ex);
				return Optional.empty();
			}
		}
		return Optional.ofNullable(FightIndex.parseFight(json));
	}

	/**
	 * The share link of {@code record} with the current recording settings; null if the base URL is
	 * invalid or {@code record} is null. A pure computation, independent of saving; cached per record
	 * object and settings, so the chat line, the card and the copy key do not re-encode.
	 */
	@Override
	public @Nullable ShareLink shareLink(@Nullable FightRecord record) {
		if (record == null) {
			return null;
		}
		try {
			KwamsterConfig.Recording rec = Kwamster.config().recording;
			LinkKey key = new LinkKey(record, rec.shareBaseUrl, rec.shareIncludeServer, rec.sharePositions,
					rec.shareMaxChars, size(record.swings), size(record.hitsTaken), size(record.jumps),
					record.durationMs, record.result);
			Optional<ShareLink> cached = this.links.get(key);
			if (cached == null) {
				cached = Optional.ofNullable(ShareLinks.build(record, rec.shareBaseUrl, rec.shareIncludeServer,
						rec.sharePositions, rec.shareMaxChars));
				this.links.put(key, cached);
			}
			return cached.orElse(null);
		} catch (Throwable t) {
			LogLimiter.error("FightStore.shareLink", "Could not build a share link", t);
			return null;
		}
	}

	@Override
	public boolean delete(String id) {
		if (id == null) {
			return false;
		}
		FightSummaryRow row = this.index.remove(id);
		this.recentJson.remove(id);
		Path file;
		if (row != null) {
			file = this.fightsDir.resolve(row.file);
		} else if (FightIndex.isSafeId(id)) {
			file = this.fightsDir.resolve(id + FightIndex.FIGHT_EXTENSION);
			if (!Files.isRegularFile(file)) {
				return false;
			}
		} else {
			return false;
		}
		enqueue(new DeleteJob(file));
		if (row != null) {
			enqueueIndexWrite();
		}
		return true;
	}

	@Override
	public void setPinned(String id, boolean pinned) {
		if (this.index.setPinned(id, pinned)) {
			enqueueIndexWrite();
		}
	}

	/** The fights folder (created if missing, so it can be opened). */
	@Override
	public Path folder() {
		try {
			Files.createDirectories(this.fightsDir);
		} catch (IOException e) {
			LogLimiter.error("FightStore.folder", "Could not create " + this.fightsDir, e);
		}
		return this.fightsDir;
	}

	/** A copy of the lifetime totals. */
	@Override
	public StatsTotals lifetime() {
		return this.lifetime.view();
	}

	@Override
	public void resetLifetime() {
		this.lifetime.reset(Clock.epochMs());
		String json = serialise(this.lifetime::toJson, "lifetime totals");
		if (json != null) {
			enqueue(new WriteJob(this.lifetime.file(), json));
		}
	}

	/**
	 * {@code executor.shutdown()} then {@code awaitTermination(2 s)}. Called once by CORE's
	 * {@code CLIENT_STOPPING} handler; later calls do nothing. Writes still queued after the wait are
	 * left to the daemon thread (every write is atomic, so an interrupted one never corrupts a file).
	 */
	@Override
	public void shutdown() {
		if (this.shutdown) {
			return;
		}
		this.shutdown = true;
		this.io.shutdown();
		try {
			if (!this.io.awaitTermination(SHUTDOWN_WAIT_MS, TimeUnit.MILLISECONDS)) {
				KwamsterClient.LOG.warn("Saving fights took longer than {} ms at shutdown; some files may not be written",
						SHUTDOWN_WAIT_MS);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** The data folder this store was created for. */
	public Path dataDir() {
		return this.dataDir;
	}

	// ---- internals ------------------------------------------------------------

	/** {@code base}, or {@code base-2}, {@code base-3}... if that id is indexed or its file exists. */
	private String uniqueId(String base) {
		String id = base;
		for (int n = 2; n <= MAX_SUFFIX && taken(id); n++) {
			id = base + "-" + n;
		}
		return id;
	}

	private boolean taken(String id) {
		if (this.index.find(id) != null) {
			return true;
		}
		try {
			return Files.exists(this.fightsDir.resolve(id + FightIndex.FIGHT_EXTENSION));
		} catch (RuntimeException e) {
			return false;
		}
	}

	private void enqueueIndexWrite() {
		String json = serialise(this.index::toJson, "index");
		if (json != null) {
			enqueue(new WriteJob(this.indexFile, json));
		}
	}

	private static @Nullable String serialise(Supplier<String> json, String what) {
		try {
			return json.get();
		} catch (RuntimeException e) {
			LogLimiter.error("FightStore.serialise/" + what, "Could not serialise the " + what, e);
			return null;
		}
	}

	private void enqueue(Runnable job) {
		try {
			this.io.execute(job);
		} catch (RejectedExecutionException e) {
			LogLimiter.error("FightStore.enqueue", "A file write was requested after shutdown and was skipped", e);
		}
	}

	private static int size(@Nullable List<?> list) {
		return list == null ? 0 : list.size();
	}

	private static <K, V> Map<K, V> lru(int max) {
		return new LinkedHashMap<>(16, 0.75f, true) {
			@Override
			protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
				return size() > max;
			}
		};
	}

	/** One atomic write, run on the IO thread. Holds only immutable data. */
	private record WriteJob(Path file, String content) implements Runnable {
		@Override
		public void run() {
			try {
				AtomicFiles.writeString(this.file, this.content);
			} catch (IOException | RuntimeException e) {
				LogLimiter.error("KWAMSTER-IO.write", "Could not write " + this.file.getFileName(), e);
			}
		}
	}

	/** One deletion, run on the IO thread. */
	private record DeleteJob(Path file) implements Runnable {
		@Override
		public void run() {
			try {
				Files.deleteIfExists(this.file);
			} catch (IOException | RuntimeException e) {
				LogLimiter.error("KWAMSTER-IO.delete", "Could not delete " + this.file.getFileName(), e);
			}
		}
	}

	/**
	 * Share-link cache key: the record object itself (records have identity equality) plus everything
	 * that changes the link. The sizes guard against a caller passing a record that is still growing.
	 */
	private record LinkKey(FightRecord record, String base, boolean includeServer, boolean positions, int maxChars,
			int swings, int taken, int jumps, int durationMs, @Nullable String result) {
	}
}
