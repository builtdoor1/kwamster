package kwamster.feedback;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.KwamsterModule;
import kwamster.core.LogLimiter;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.KwamsterListener;

/**
 * The FEEDBACK unit (order 400): tier hit sounds and chat output (§8). Registers the
 * {@link HitSoundPlayer} as the {@code SoundPreview} service and one bus listener that forwards to the
 * sound player and the {@link ChatReporter}, each call isolated so a failure in one never silences the
 * other.
 */
public final class FeedbackModule implements KwamsterModule {
	/** Module order (§2.1). */
	public static final int ORDER = 400;

	@Override
	public int order() {
		return ORDER;
	}

	@Override
	public void init() {
		HitSoundPlayer sounds = new HitSoundPlayer();
		ChatReporter chat = new ChatReporter();
		Kwamster.services().setSounds(sounds);
		Kwamster.events().register(new Listener(sounds, chat));
		KwamsterClient.LOG.debug("KWAMSTER feedback ready (hit sounds, chat output)");
	}

	/** Forwards bus events to the sound player and the chat reporter. Client thread. */
	private static final class Listener implements KwamsterListener {
		private final HitSoundPlayer sounds;
		private final ChatReporter chat;

		Listener(HitSoundPlayer sounds, ChatReporter chat) {
			this.sounds = sounds;
			this.chat = chat;
		}

		@Override
		public void onHitResolved(HitResolvedEvent e) {
			if (e == null) {
				return;
			}
			try {
				this.sounds.onHitResolved(e);
			} catch (Throwable t) {
				LogLimiter.error("FeedbackModule.sound", "Hit sound failed", t);
			}
			try {
				this.chat.onHitResolved(e);
			} catch (Throwable t) {
				LogLimiter.error("FeedbackModule.hitChat", "Per-hit chat line failed", t);
			}
		}

		@Override
		public void onJumpReset(JumpResetEvent e) {
			if (e == null) {
				return;
			}
			try {
				this.chat.onJumpReset(e);
			} catch (Throwable t) {
				LogLimiter.error("FeedbackModule.jumpChat", "Jump-reset chat line failed", t);
			}
		}

		@Override
		public void onFightEnded(FightEndedEvent e) {
			if (e == null) {
				return;
			}
			try {
				this.chat.onFightEnded(e);
			} catch (Throwable t) {
				LogLimiter.error("FeedbackModule.fightChat", "Fight summary chat failed", t);
			}
		}
	}
}
