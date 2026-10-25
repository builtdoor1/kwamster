package kwamster.core.service;

import kwamster.core.model.FightRecord;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.ShareLink;
import kwamster.core.model.StatsTotals;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Saved fights, lifetime totals and share links. Implemented by RECORD; the CORE default is empty
 * and does nothing. Client thread.
 */
public interface FightArchive {
	/** Newest first, from the in-memory index. */
	List<FightSummaryRow> list();

	/** Reads one saved fight on demand. */
	Optional<FightRecord> load(String id);

	/** The share link for {@code record} (a pure computation, independent of saving); null if the base URL is invalid. */
	@Nullable ShareLink shareLink(FightRecord record);

	/** Deletes a saved fight; false if it did not exist or could not be removed. */
	boolean delete(String id);

	void setPinned(String id, boolean pinned);

	/** The folder the fight files live in. */
	Path folder();

	/** Lifetime totals over every counted fight. */
	StatsTotals lifetime();

	void resetLifetime();

	/**
	 * Called once, from CORE's {@code CLIENT_STOPPING} handler after the last fight was finalised:
	 * {@code executor.shutdown()} then {@code awaitTermination(2 s)}. CORE default: no-op.
	 */
	void shutdown();
}
