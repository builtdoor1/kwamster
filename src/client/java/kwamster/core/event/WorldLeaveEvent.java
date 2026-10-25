package kwamster.core.event;

/**
 * The connection went away or the world changed. Posted only by CORE (FightManager, §5.13), on the
 * client thread, AFTER any fight it ends has been finalised.
 *
 * @param nanos  {@code Clock.nanos()} when detected
 * @param reason DISCONNECT: the connection went away. WORLD_CHANGE: the ClientLevel or the LocalPlayer
 *               instance changed (dimension change, server switch, respawn)
 */
public record WorldLeaveEvent(long nanos, Reason reason) {
	/** What happened. */
	public enum Reason {
		DISCONNECT, WORLD_CHANGE
	}
}
