package kwamster.feedback;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.TierStyle;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.model.HitQuality;
import kwamster.core.service.SoundPreview;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

/**
 * Hit-quality tier sounds (§8.1) and the settings screen's sound preview ({@link SoundPreview}).
 *
 * <p>A resolved landed hit plays its tier's sound when the master switch, the sound toggle, the tier
 * and the tier's sound are all on, the hit is not NO-REG, and at least {@link #MIN_GAP_NANOS} passed
 * since the last KWAMSTER sound (anti-stacking). Sounds are played straight from their id with the
 * 12-argument {@link SimpleSoundInstance} constructor (the shape {@code forUI} uses: no attenuation,
 * relative to the listener) so the volume channel is selectable and no registry lookup is needed;
 * resource-pack sounds work as long as they exist in {@code sounds.json}. The id is checked against
 * the sound manager on every call, never cached, so resource reloads are reflected.
 *
 * <p>Client thread.
 */
public final class HitSoundPlayer implements SoundPreview {
	/** Minimum gap between two KWAMSTER hit sounds: 40 ms. */
	public static final long MIN_GAP_NANOS = 40_000_000L;

	private boolean anyPlayed;
	private long lastPlayedNanos;

	/** Plays the tier sound of a resolved swing, if every §8.1 condition holds. */
	public void onHitResolved(HitResolvedEvent e) {
		HitQuality q = e.quality();
		if (q == null || e.swing() == null || e.swing().intoImmunity()) {
			return; // misses have no sound; NO-REG hits are silent (§5.4)
		}
		KwamsterConfig cfg = Kwamster.config();
		if (!cfg.general.enabled || !cfg.feedback.soundsEnabled) {
			return;
		}
		TierStyle s = cfg.tier(q);
		if (!s.enabled || !s.soundEnabled) {
			return;
		}
		long now = Clock.nanos();
		if (this.anyPlayed && now - this.lastPlayedNanos < MIN_GAP_NANOS) {
			return;
		}
		if (play(s.soundId, s.volumePct, s.pitchPct, cfg.feedback.soundChannel)) {
			this.anyPlayed = true;
			this.lastPlayedNanos = now;
		}
	}

	/**
	 * Plays {@code soundId} once the same way a hit does, ignoring the toggles and the throttle, on
	 * {@code channel} (the settings screen's pending choice) or, when null, the saved channel.
	 */
	@Override
	public void preview(String soundId, int volumePct, int pitchPct, KwamsterConfig.@Nullable SoundChannel channel) {
		try {
			play(soundId, volumePct, pitchPct, channel != null ? channel : Kwamster.config().feedback.soundChannel);
		} catch (Throwable t) {
			LogLimiter.error("HitSoundPlayer.preview", "Could not preview sound " + soundId, t);
		}
	}

	/**
	 * Plays {@code soundId} at {@code volumePct}/100 and {@code pitchPct}/100 on the channel's volume
	 * slider. Returns false (and plays nothing) when the id is malformed, unknown to the sound manager
	 * (for example a missing resource-pack sound), or the volume is 0.
	 */
	static boolean play(@Nullable String soundId, int volumePct, int pitchPct, KwamsterConfig.@Nullable SoundChannel channel) {
		if (soundId == null) {
			return false;
		}
		Identifier id = Identifier.tryParse(soundId.trim());
		if (id == null) {
			return false;
		}
		int volume = Math.max(0, Math.min(100, volumePct));
		if (volume == 0) {
			return false;
		}
		int pitch = Math.max(50, Math.min(200, pitchPct));
		Minecraft mc = Minecraft.getInstance();
		SoundManager sounds = mc == null ? null : mc.getSoundManager();
		if (sounds == null || sounds.getSoundEvent(id) == null) {
			return false;
		}
		sounds.play(new SimpleSoundInstance(id, source(channel), volume / 100f, pitch / 100f,
				SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0.0, 0.0, 0.0, true));
		return true;
	}

	/** The vanilla volume slider a {@link KwamsterConfig.SoundChannel} maps to (PLAYERS when null). */
	static SoundSource source(KwamsterConfig.@Nullable SoundChannel channel) {
		if (channel == null) {
			return SoundSource.PLAYERS;
		}
		return switch (channel) {
			case MASTER -> SoundSource.MASTER;
			case PLAYERS -> SoundSource.PLAYERS;
			case UI -> SoundSource.UI;
		};
	}
}
