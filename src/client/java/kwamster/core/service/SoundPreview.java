package kwamster.core.service;

import kwamster.core.config.KwamsterConfig;
import org.jspecify.annotations.Nullable;

/** Plays a tier sound for the settings screen's "Test" buttons, ignoring toggles and throttling. Implemented by FEEDBACK; the CORE default is a no-op. */
public interface SoundPreview {
	/**
	 * Plays {@code soundId} once.
	 *
	 * @param soundId   namespaced sound id
	 * @param volumePct 0..100
	 * @param pitchPct  50..200
	 * @param channel   the vanilla volume category to play on; the settings screen passes its pending
	 *                  (unsaved) choice. Null means the saved {@code feedback.soundChannel}.
	 */
	void preview(String soundId, int volumePct, int pitchPct, KwamsterConfig.@Nullable SoundChannel channel);
}
