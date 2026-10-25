package kwamster.core.fight;

/**
 * The local player's state this tick (input to {@link FightStateMachine#tick}). Minecraft-free.
 *
 * <p>Declared next to {@link OpponentPresence} in §4.9; it lives in its own file because Java allows
 * one public top-level type per file.
 *
 * @param dead      {@code isDeadOrDying()} or the death screen is open
 * @param spectator the local player is a spectator
 */
public record SelfState(boolean dead, boolean spectator) {
	/** Alive and not spectating. */
	public static final SelfState ALIVE = new SelfState(false, false);
}
