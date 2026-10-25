package kwamster.core.model;

/**
 * The outcome of a fight. The declaration order is a stable contract: payload v1 stores
 * {@link #code()} (0 DRAW, 1 WIN, 2 LOSS).
 */
public enum FightResult {
	DRAW, WIN, LOSS;

	/** Stable code: 0 DRAW, 1 WIN, 2 LOSS. */
	public int code() {
		return ordinal();
	}
}
