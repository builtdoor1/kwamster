package kwamster.core.fight;

/**
 * What FightManager could see of the current opponent this tick (input to
 * {@link FightStateMachine#tick}). Minecraft-free.
 *
 * @param found      the opponent is in {@code mc.level.players()}
 * @param alive      {@code isAlive() && !isDeadOrDying()} (best-effort: reads synced health)
 * @param spectator  the opponent is a spectator
 * @param distance   {@code self.distanceTo(opponent)}: 3-D, feet positions (Combat Report's distanceToSqr)
 * @param teleported the opponent's server position jumped more than
 *                   {@link FightStateMachine#TELEPORT_BLOCKS} since the previous tick (§5.13a)
 * @param healthFrac health / max health as last synced, or NaN when unknown
 */
public record OpponentPresence(boolean found, boolean alive, boolean spectator, double distance,
		boolean teleported, float healthFrac) {
	/** The synthetic gametest opponent: always present, alive and 3 blocks away. */
	public static final OpponentPresence SYNTHETIC = new OpponentPresence(true, true, false, 3.0);
	/** The opponent is not in the level (logged off or untracked). */
	public static final OpponentPresence ABSENT = new OpponentPresence(false, false, false, 0.0);

	/** A presence with no teleport and unknown health. */
	public OpponentPresence(boolean found, boolean alive, boolean spectator, double distance) {
		this(found, alive, spectator, distance, false, Float.NaN);
	}
}
