package kwamster.core.model;

/**
 * Where on the target's hitbox a landed hit struck (S3). One label per hit, assigned with the
 * precedence HIGH &gt; LOW &gt; EDGE &gt; CENTER by {@code kwamster.core.stats.AimZones}.
 */
public enum AimZone {
	CENTER, EDGE, HIGH, LOW;

	/** Stable code used by the share payload and the aim totals ({@code StatsTotals.aim}). */
	public int code() {
		return ordinal();
	}
}
