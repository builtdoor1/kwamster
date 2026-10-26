package kwamster.capture;

import kwamster.Kwamster;
import kwamster.core.KwamsterModule;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.KwamsterListener;
import kwamster.core.event.WorldLeaveEvent;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.Minecraft;

/**
 * The CAPTURE unit (order 100): turns vanilla attack, damage, jump, death and input hooks into capture
 * events. Registers the {@code CaptureControl} service (CORE flushes pending trades through it before
 * finalising a fight) and a bus listener for the client tick, world leave and fight end. It also registers
 * a {@code START_CLIENT_TICK} listener, which runs after CORE's own (CORE registers before any module
 * loads, so {@code Clock.ticks()} is already advanced): a sourceless hit taken whose attacker was not yet
 * visible gets its second look there, before the tick's jump code runs. System and action-bar messages
 * go to {@link OutcomeWatch} for kill and win announcements (§5.13a). The mixins call
 * {@link CombatCapture} and {@link OutcomeWatch} directly; they stay inert until this module has initialised.
 */
public final class CaptureModule implements KwamsterModule {
	@Override
	public int order() {
		return 100;
	}

	@Override
	public void init() {
		Kwamster.services().setCapture(CombatCapture::flushPending);
		Kwamster.events().register(new Listener());
		ClientTickEvents.START_CLIENT_TICK.register(CombatCapture::onClientTickStart);
		ClientReceiveMessageEvents.GAME.register(OutcomeWatch::onGameMessage);
		CombatCapture.setActive(true);
		OutcomeWatch.setActive(true);
	}

	/** Forwards the bus callbacks CAPTURE needs to {@link CombatCapture}. */
	private static final class Listener implements KwamsterListener {
		@Override
		public void onClientTick(Minecraft mc) {
			CombatCapture.onClientTick(mc);
		}

		@Override
		public void onWorldLeave(WorldLeaveEvent e) {
			CombatCapture.onWorldLeave(e);
		}

		@Override
		public void onFightEnded(FightEndedEvent e) {
			CombatCapture.onFightEnded(e);
		}
	}
}
