package kwamster.core.model;

/**
 * The seven hit-quality tiers (S9), best first. Assigned by {@code kwamster.core.stats.HitGrader}.
 *
 * <p>The declaration order is a stable contract: {@link #code()} is written into share payload v1
 * and indexes {@code FightStats.quality}. Never reorder or insert.
 */
public enum HitQuality {
	THREE_BLOCK_CRIT, THREE_BLOCK_SPRINT, THREE_BLOCK, CRIT, SPRINT, NORMAL, WEAK;

	private static final HitQuality[] BY_CODE = values();

	/** Stable code (0..6), used in payload v1 and as the index into quality arrays. */
	public int code() {
		return ordinal();
	}

	/** True for the three 3-block tiers (codes 0..2), the "perfect" hits of S8. */
	public boolean threeBlock() {
		return ordinal() <= 2;
	}

	/**
	 * The tier with the given code.
	 *
	 * @throws IllegalArgumentException if {@code c} is not in 0..6
	 */
	public static HitQuality byCode(int c) {
		if (c < 0 || c >= BY_CODE.length) {
			throw new IllegalArgumentException("Unknown hit quality code " + c);
		}
		return BY_CODE[c];
	}
}
