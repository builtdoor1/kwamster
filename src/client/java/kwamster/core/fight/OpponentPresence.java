package kwamster.core.fight;

/**
 * What FightManager could see of the current opponent this tick (input to
 * {@link FightStateMachine#tick}). Minecraft-free.
 *
 * @param found     the opponent is in {@code mc.level.players()}
 * @param alive     {@code isAlive() && !isDeadOrDying()} (best-effort: reads synced health)
 * @param spectator the opponent is a spectator
 * @param distance  {@code self.distanceTo(opponent)}: 3-D, feet positions (Combat Report's distanceToSqr)
 */
public record OpponentPresence(boolean found, boolean alive, boolean spectator, double distance) {
	/** The synthetic gametest opponent: always present, alive and 3 blocks away. */
	public static final OpponentPresence SYNTHETIC = new OpponentPresence(true, true, false, 3.0);
	/** The opponent is not in the level (logged off or untracked). */
	public static final OpponentPresence ABSENT = new OpponentPresence(false, false, false, 0.0);
}
