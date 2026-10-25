package kwamster.core.stats;

import kwamster.core.model.HitQuality;

/**
 * Grades a landed hit into a {@link HitQuality} tier (§5.9). Minecraft-free.
 *
 * <p>FightManager calls it with the fight's settings snapshot ({@code record.settings.threeBlockReach}).
 */
public final class HitGrader {
	/** Tolerance for the 3-block threshold comparison. */
	public static final double EPSILON = 1e-6;

	private HitGrader() {
	}

	/**
	 * The tier of a landed hit.
	 *
	 * <pre>
	 * if (!charged) return WEAK;
	 * three = !customRange &amp;&amp; reach ≥ threeBlockReach - 1e-6;   // custom ATTACK_RANGE: never a 3-block hit
	 * if (crit)   return three ? THREE_BLOCK_CRIT : CRIT;      // crit implies !sprinting
	 * if (sprint) return three ? THREE_BLOCK_SPRINT : SPRINT;
	 * return three ? THREE_BLOCK : NORMAL;                     // sweeps and plain charged hits
	 * </pre>
	 */
	public static HitQuality grade(boolean charged, boolean crit, boolean sprint, double reach, double threeBlockReach, boolean customRange) {
		if (!charged) {
			return HitQuality.WEAK;
		}
		boolean three = !customRange && reach >= threeBlockReach - EPSILON;
		if (crit) {
			return three ? HitQuality.THREE_BLOCK_CRIT : HitQuality.CRIT;
		}
		if (sprint) {
			return three ? HitQuality.THREE_BLOCK_SPRINT : HitQuality.SPRINT;
		}
		return three ? HitQuality.THREE_BLOCK : HitQuality.NORMAL;
	}
}
