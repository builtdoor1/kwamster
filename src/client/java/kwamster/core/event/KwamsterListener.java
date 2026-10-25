package kwamster.core.event;

import net.minecraft.client.Minecraft;

/**
 * Receives KWAMSTER events from the {@link EventBus}. Every method defaults to a no-op, so a unit
 * overrides only what it needs. All calls arrive on the client thread; an exception thrown here is
 * caught and logged by the bus and never reaches another listener or vanilla.
 */
public interface KwamsterListener {
	default void onSwing(SwingEvent e) {
	}

	default void onPiercingSwing(PiercingSwingEvent e) {
	}

	default void onHitTaken(HitTakenEvent e) {
	}

	default void onJump(JumpEvent e) {
	}

	default void onJumpReset(JumpResetEvent e) {
	}

	default void onDeath(DeathEvent e) {
	}

	default void onHitConfirmed(HitConfirmedEvent e) {
	}

	default void onDamageObserved(DamageObservedEvent e) {
	}

	default void onTrade(TradeEvent e) {
	}

	default void onWorldLeave(WorldLeaveEvent e) {
	}

	default void onFightStarted(FightStartedEvent e) {
	}

	default void onHitResolved(HitResolvedEvent e) {
	}

	default void onFightEnded(FightEndedEvent e) {
	}

	/** END_CLIENT_TICK, after keybind handling. Core listener first. Fires on ticks with no level too. */
	default void onClientTick(Minecraft mc) {
	}
}
