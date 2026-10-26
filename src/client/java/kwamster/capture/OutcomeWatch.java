package kwamster.capture;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.OutcomeHintEvent;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.PlayerRef;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Hands server messages and titles to {@link OutcomeText} while a fight runs, and posts what they say as an
 * {@link OutcomeHintEvent} (§5.13a). It reads system and action-bar messages
 * ({@code ClientReceiveMessageEvents.GAME}, registered by {@link CaptureModule}) and titles
 * ({@code GuiTitleMixin}), which is where practice and duel servers announce kills and winners. Signed player
 * chat never reaches it. It reads nothing while no fight runs, with the master switch off, or with
 * {@code readOutcomeMessages} off, and nothing is stored or sent anywhere: a hint only decides the result.
 */
public final class OutcomeWatch {
	/** Longest message quoted in the result note. */
	private static final int QUOTE_MAX = 64;

	private static volatile boolean active;

	private OutcomeWatch() {
	}

	static void setActive(boolean value) {
		active = value;
	}

	/** {@code ClientReceiveMessageEvents.GAME}: a system message, or an action-bar message when {@code overlay}. */
	public static void onGameMessage(Component message, boolean overlay) {
		read(message, overlay ? "the action bar" : "chat", true);
	}

	/** {@code Gui.setTitle} / {@code Gui.setSubtitle} HEAD. */
	public static void onTitle(Component title, boolean subtitle) {
		read(title, subtitle ? "the subtitle" : "the title", false);
	}

	private static void read(Component message, String source, boolean chatLine) {
		if (!active || message == null) {
			return;
		}
		try {
			KwamsterConfig cfg = Kwamster.config();
			if (!cfg.general.enabled || !cfg.advanced.readOutcomeMessages) {
				return;
			}
			LiveFightView fight = Kwamster.fights().current();
			if (fight == null) {
				return;
			}
			String text = PlayerRefs.stripFormatting(message.getString());
			List<String> names = new ArrayList<>();
			for (PlayerRef ref : fight.opponents()) {
				names.add(ref.name());
			}
			OutcomeText.Verdict v = OutcomeText.classify(text, PlayerRefs.self(Minecraft.getInstance()).name(), names, chatLine);
			if (v == null) {
				return;
			}
			String quote = text.length() > QUOTE_MAX ? text.substring(0, QUOTE_MAX - 1) + "…" : text;
			Kwamster.events().post(new OutcomeHintEvent(Clock.nanos(), v.win(), v.opponent(),
					"Read from " + source + ": \"" + quote + "\""));
		} catch (Throwable t) {
			LogLimiter.error("OutcomeWatch.read", "Reading a server message failed", t);
		}
	}
}
