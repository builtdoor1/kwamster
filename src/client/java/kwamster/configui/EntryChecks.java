package kwamster.configui;

import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.KwamsterConfig.SoundChannel;
import kwamster.core.config.TierStyle;
import org.jspecify.annotations.Nullable;

/**
 * The Minecraft-free checks behind some settings entries, kept apart so they can be unit-tested: the
 * tier label rule, the text-colour alpha rule and the volume warning of the tier "▶ Test" button. Each
 * check returns a user-facing message, or null when the value is fine; {@link ConfigScreenBuilder}
 * wraps the message in a {@code Component}.
 */
final class EntryChecks {
	private EntryChecks() {
	}

	/** Why {@code value} is not a usable tier label (blank, or longer than {@link TierStyle#MAX_LABEL_LENGTH} once trimmed), or null. */
	static @Nullable String labelProblem(@Nullable String value) {
		String s = value == null ? "" : value.trim();
		if (s.isEmpty()) {
			return "The label cannot be empty";
		}
		if (s.length() > TierStyle.MAX_LABEL_LENGTH) {
			return "At most " + TierStyle.MAX_LABEL_LENGTH + " characters (now " + s.length() + ")";
		}
		return null;
	}

	/** True when a text colour keeps an alpha of at least {@link KwamsterConfig#MIN_TEXT_ALPHA} (§3), so it stays visible. */
	static boolean textAlphaOk(int argb) {
		return (argb >>> 24) >= KwamsterConfig.MIN_TEXT_ALPHA;
	}

	/**
	 * Why a tier sound preview would be silent, or null. Checked in the order a listener would fix it:
	 * the tier's own volume, then vanilla's Master Volume, then the vanilla volume category the sound
	 * plays on (Players and UI are also scaled by Master; a null channel means Players, as in FEEDBACK).
	 * The vanilla volumes are slider values from 0 to 1; NaN counts as 0.
	 */
	static @Nullable String previewVolumeProblem(int tierVolumePct, @Nullable SoundChannel channel, float masterVolume, float channelVolume) {
		if (tierVolumePct <= 0) {
			return "Volume is 0%";
		}
		if (!(masterVolume > 0f)) {
			return "Master Volume is 0%";
		}
		SoundChannel c = channel == null ? SoundChannel.PLAYERS : channel;
		if (c != SoundChannel.MASTER && !(channelVolume > 0f)) {
			return vanillaName(c) + " volume is 0%";
		}
		return null;
	}

	/** The name vanilla's Music & Sounds screen gives the slider ({@code soundCategory.*} in en_us). */
	static String vanillaName(SoundChannel c) {
		return switch (c) {
			case MASTER -> "Master Volume";
			case PLAYERS -> "Players";
			case UI -> "UI";
		};
	}
}
