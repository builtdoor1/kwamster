package kwamster.core;

import kwamster.Kwamster;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.ShareLink;
import kwamster.core.model.StatsTotals;
import kwamster.core.model.StoplightReading;
import kwamster.core.service.CaptureControl;
import kwamster.core.service.FightArchive;
import kwamster.core.service.SoundPreview;
import kwamster.core.service.StoplightSource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Holder of the service implementations other units provide (reached via
 * {@code Kwamster.services()}). Every slot starts with a CORE no-op default, so a missing or failed
 * module degrades a feature instead of crashing: no stoplight, no saved fights, silent previews.
 * Passing null to a setter restores the default. Client thread.
 */
public final class Services {
	private static final StoplightSource NO_STOPLIGHT = () -> StoplightReading.NONE;
	private static final SoundPreview NO_SOUNDS = (id, volume, pitch, channel) -> {
	};
	private static final CaptureControl NO_CAPTURE = () -> {
	};
	private static final FightArchive NO_ARCHIVE = new EmptyArchive();

	private StoplightSource stoplight = NO_STOPLIGHT;
	private FightArchive archive = NO_ARCHIVE;
	private SoundPreview sounds = NO_SOUNDS;
	private CaptureControl capture = NO_CAPTURE;
	private final ScreenRegistry screens = new ScreenRegistry();

	public StoplightSource stoplight() {
		return this.stoplight;
	}

	public void setStoplight(@Nullable StoplightSource s) {
		this.stoplight = s == null ? NO_STOPLIGHT : s;
	}

	public FightArchive archive() {
		return this.archive;
	}

	public void setArchive(@Nullable FightArchive a) {
		this.archive = a == null ? NO_ARCHIVE : a;
	}

	public SoundPreview sounds() {
		return this.sounds;
	}

	public void setSounds(@Nullable SoundPreview s) {
		this.sounds = s == null ? NO_SOUNDS : s;
	}

	public CaptureControl capture() {
		return this.capture;
	}

	public void setCapture(@Nullable CaptureControl c) {
		this.capture = c == null ? NO_CAPTURE : c;
	}

	/** CORE's screen registry; never replaced. */
	public ScreenRegistry screens() {
		return this.screens;
	}

	/** The default archive: nothing saved, nothing to share. */
	private static final class EmptyArchive implements FightArchive {
		@Override
		public List<FightSummaryRow> list() {
			return List.of();
		}

		@Override
		public Optional<FightRecord> load(String id) {
			return Optional.empty();
		}

		@Override
		public @Nullable ShareLink shareLink(FightRecord record) {
			return null;
		}

		@Override
		public boolean delete(String id) {
			return false;
		}

		@Override
		public void setPinned(String id, boolean pinned) {
		}

		@Override
		public Path folder() {
			return Kwamster.dataDir().resolve("fights");
		}

		@Override
		public StatsTotals lifetime() {
			return new StatsTotals();
		}

		@Override
		public void resetLifetime() {
		}

		@Override
		public void shutdown() {
		}
	}
}
