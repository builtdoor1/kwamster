package kwamster.core.stats;

import kwamster.core.model.AimZone;

/**
 * Normalised aim offsets to an {@link AimZone} (§5.3), with the precedence HIGH &gt; LOW &gt; EDGE &gt;
 * CENTER. Minecraft-free.
 */
public final class AimZones {
	/** {@code v} at or above this is HIGH (top third of the height); at or below its negative, LOW. */
	public static final double THIRD = 1.0 / 3.0;
	/** {@code |h|} at or above this is EDGE (outer quarter of the width on either side). */
	public static final double EDGE = 0.5;

	private AimZones() {
	}

	/** The zone for offsets {@code (h, v)}. NaN offsets fall through to CENTER. */
	public static AimZone of(double h, double v) {
		if (v >= THIRD) {
			return AimZone.HIGH;
		}
		if (v <= -THIRD) {
			return AimZone.LOW;
		}
		if (Math.abs(h) >= EDGE) {
			return AimZone.EDGE;
		}
		return AimZone.CENTER;
	}
}
