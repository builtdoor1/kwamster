package kwamster.core.fight;

/**
 * The local player's state this tick (input to {@link FightStateMachine#tick}). Minecraft-free.
 *
 * <p>Declared next to {@link OpponentPresence} in §4.9; it lives in its own file because Java allows
 * one public top-level type per file.
 *
 * @param dead       {@code isDeadOrDying()} or the death screen is open
 * @param spectator  the local player is a spectator
 * @param teleported your position jumped more than {@link FightStateMachine#TELEPORT_BLOCKS} since the
 *                   previous tick (§5.13a)
 * @param healthFrac (health + absorption) / max health, or NaN when unknown
 * @param inPortal   you stand in a nether portal, end portal or end gateway block, so a world change or
 *                   teleport soon after is travel, not a death (§5.13a)
 */
public record SelfState(boolean dead, boolean spectator, boolean teleported, float healthFrac, boolean inPortal) {
	/** Alive and not spectating. */
	public static final SelfState ALIVE = new SelfState(false, false);

	/** A state with no teleport, unknown health and no portal. */
	public SelfState(boolean dead, boolean spectator) {
		this(dead, spectator, false, Float.NaN, false);
	}

	/** A state outside any portal. */
	public SelfState(boolean dead, boolean spectator, boolean teleported, float healthFrac) {
		this(dead, spectator, teleported, healthFrac, false);
	}
}
