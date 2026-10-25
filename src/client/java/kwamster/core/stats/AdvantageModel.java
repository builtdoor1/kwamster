package kwamster.core.stats;

import kwamster.core.model.FightRecord;

/**
 * The advantage formula of §5.5. Minecraft-free.
 *
 * <pre>
 * d    = landed non-NO-REG swings with t-W &lt; t_i ≤ t        k = melee hits taken with t-W &lt; t_i ≤ t
 * exch = (d - k) / max(d + k, 1)
 * c    = currentCombo ≥ 1 &amp;&amp; currentComboTaken == 0 ?  +min(currentCombo, 3)/3
 *      : currentComboTaken ≥ 1 &amp;&amp; currentCombo == 0 ?  -min(currentComboTaken, 3)/3
 *      : 0
 * A(t) = clamp(0.5·c + 0.5·exch, -1, 1)
 * </pre>
 *
 * <p>The windowed counts and the chain state over a record are evaluated by {@link StatsCalculator}
 * (the single source of truth); {@link #at(FightRecord, int)} exposes that for one instant.
 */
public final class AdvantageModel {
	/** |A| at or above this labels the moment YOU or THEM. */
	public static final double LABEL_THRESHOLD = 0.15;
	/** Spacing of the summary samples (t = 0, 100, 200, ...). */
	public static final int SAMPLE_MS = 100;

	public static final String YOU = "YOU";
	public static final String EVEN = "EVEN";
	public static final String THEM = "THEM";

	private AdvantageModel() {
	}

	/**
	 * A(t) from its inputs.
	 *
	 * @param dealtInWindow     landed non-NO-REG swings in (t-W, t]
	 * @param takenInWindow     melee hits taken in (t-W, t]
	 * @param currentCombo      the dealt chain at t
	 * @param currentComboTaken the taken chain at t
	 * @return the advantage in [-1, 1]
	 */
	public static double value(int dealtInWindow, int takenInWindow, int currentCombo, int currentComboTaken) {
		double exch = (double) (dealtInWindow - takenInWindow) / Math.max(dealtInWindow + takenInWindow, 1);
		double c;
		if (currentCombo >= 1 && currentComboTaken == 0) {
			c = Math.min(currentCombo, 3) / 3.0;
		} else if (currentComboTaken >= 1 && currentCombo == 0) {
			c = -Math.min(currentComboTaken, 3) / 3.0;
		} else {
			c = 0.0;
		}
		double a = 0.5 * c + 0.5 * exch;
		return Math.max(-1.0, Math.min(1.0, a));
	}

	/** "YOU" if {@code a ≥ 0.15}, "THEM" if {@code a ≤ -0.15}, else "EVEN". */
	public static String label(double a) {
		if (isYou(a)) {
			return YOU;
		}
		return isThem(a) ? THEM : EVEN;
	}

	public static boolean isYou(double a) {
		return a >= LABEL_THRESHOLD;
	}

	public static boolean isThem(double a) {
		return a <= -LABEL_THRESHOLD;
	}

	/**
	 * A(t) over a record at relative time {@code tRelMs}, using {@code record.settings} and the chain
	 * state as of t (events with {@code t_i ≤ t}).
	 */
	public static double at(FightRecord record, int tRelMs) {
		return StatsCalculator.advantageAt(record, tRelMs);
	}
}
