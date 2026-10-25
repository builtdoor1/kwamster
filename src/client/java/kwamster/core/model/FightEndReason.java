package kwamster.core.model;

/**
 * Why a fight ended (§5.13). Each reason carries a stable {@link #code} written into payload v1;
 * never renumber.
 */
public enum FightEndReason {
	IDLE(0), OPPONENT_DIED(1), SELF_DIED(2), OPPONENT_LEFT(3), OPPONENT_FAR(4),
	DISCONNECT(5), WORLD_CHANGE(6), GAME_EXIT(7), DISABLED(8);

	/** Stable code used in payload v1. */
	public final int code;

	FightEndReason(int code) {
		this.code = code;
	}

	/**
	 * The reason with the given code.
	 *
	 * @throws IllegalArgumentException if no reason has that code
	 */
	public static FightEndReason byCode(int c) {
		for (FightEndReason r : values()) {
			if (r.code == c) {
				return r;
			}
		}
		throw new IllegalArgumentException("Unknown fight end reason code " + c);
	}
}
