package kwamster.core.config;

/**
 * The nine HUD anchor points (§3.10, §7.2). {@code fx} and {@code fy} are the anchor's fraction of
 * the screen width and height, each one of 0, 0.5 or 1.
 */
public enum Anchor {
	TOP_LEFT(0f, 0f), TOP_CENTER(.5f, 0f), TOP_RIGHT(1f, 0f),
	CENTER_LEFT(0f, .5f), CENTER(.5f, .5f), CENTER_RIGHT(1f, .5f),
	BOTTOM_LEFT(0f, 1f), BOTTOM_CENTER(.5f, 1f), BOTTOM_RIGHT(1f, 1f);

	public final float fx, fy;

	Anchor(float fx, float fy) {
		this.fx = fx;
		this.fy = fy;
	}

	/** The anchor nearest to {@code (fx, fy)}. A non-finite component counts as 0.5. Ties go to the first declared. */
	public static Anchor of(float fx, float fy) {
		float x = Float.isFinite(fx) ? fx : .5f;
		float y = Float.isFinite(fy) ? fy : .5f;
		Anchor best = CENTER;
		float bestDist = Float.MAX_VALUE;
		for (Anchor a : values()) {
			float dx = x - a.fx;
			float dy = y - a.fy;
			float d = dx * dx + dy * dy;
			if (d < bestDist) {
				bestDist = d;
				best = a;
			}
		}
		return best;
	}
}
