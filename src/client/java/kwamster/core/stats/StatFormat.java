package kwamster.core.stats;

import java.util.Locale;
import java.util.function.ToIntFunction;

/**
 * Number formatting shared by the HUD, chat, card and screens, so every surface prints a figure the
 * same way. Minecraft-free: text width is passed in as a function ({@code font::width} in game, a stub
 * in tests). All decimal output uses {@link Locale#ROOT} (a '.' separator in every language).
 */
public final class StatFormat {
	/** Printed when a figure has no data (its count or denominator is 0). */
	public static final String DASH = "—";
	/** Appended by {@link #truncate} (vanilla's own {@code CommonComponents.ELLIPSIS} text). */
	public static final String ELLIPSIS = "...";

	private StatFormat() {
	}

	/** {@code "74%"} for {@code 100·part/whole} rounded, or {@link #DASH} when {@code whole ≤ 0}. */
	public static String pct(int part, int whole) {
		return whole <= 0 ? DASH : pct(100.0 * part / whole);
	}

	/** {@code "74%"}: a stored percentage rounded to a whole number. */
	public static String pct(double percent) {
		return Math.round(finite(percent)) + "%";
	}

	/** {@code "74%"}, or {@link #DASH} when {@code count ≤ 0} (the percentage has no data). */
	public static String pct(double percent, int count) {
		return count <= 0 ? DASH : pct(percent);
	}

	/**
	 * Reach in blocks with two decimals, <b>rounded down</b>: {@code "2.84"} for 2.8475. Floor
	 * semantics with {@link HitGrader#EPSILON}, the tolerance of the 3-block comparison, so a shown
	 * value never overstates the reach: whenever the text reads at least a two-decimal threshold such as
	 * {@code "2.95"}, {@code reach ≥ threshold − EPSILON} holds and the hit was 3-block eligible (a
	 * 2.9499 hit prints {@code "2.94"}, never a misleading {@code "2.95"}). The share payload quantises
	 * the same way ({@code SharePayloadCodec.reachQ}).
	 */
	public static String reach(double blocks) {
		double cm = Math.floor((finite(blocks) + HitGrader.EPSILON) * 100.0);
		return String.format(Locale.ROOT, "%.2f", cm / 100.0);
	}

	/** {@link #reach(double)} ({@code "2.84"}), or {@link #DASH} when {@code count ≤ 0}. */
	public static String reach(double blocks, int count) {
		return count <= 0 ? DASH : reach(blocks);
	}

	/**
	 * The reach of a swing measured against its attack range {@code limit}: {@link #reach(double)}, rounded
	 * down, except that a reach over the range ({@code blocks > limit}) never reads at or below the range's
	 * own text: it shows at least {@code reach(limit)} plus 0.01 ({@code "MISS 3.01 (over reach)"} for a
	 * 3.004-block miss against 3.00, never {@code "3.00"}). Only misses can be over the range, and misses are
	 * never graded, so this cannot make a hit look 3-block eligible. A non-finite or non-positive
	 * {@code limit} means no comparison.
	 */
	public static String reachVsRange(double blocks, double limit) {
		double b = finite(blocks);
		double cm = Math.floor((b + HitGrader.EPSILON) * 100.0);
		if (Double.isFinite(limit) && limit > 0.0 && b > limit) {
			cm = Math.max(cm, Math.floor((limit + HitGrader.EPSILON) * 100.0) + 1.0);
		}
		return String.format(Locale.ROOT, "%.2f", cm / 100.0);
	}

	/** {@code value} with exactly {@code places} decimals (0..6), e.g. {@code decimals(2.5, 1) = "2.5"}. */
	public static String decimals(double value, int places) {
		int p = Math.max(0, Math.min(6, places));
		return String.format(Locale.ROOT, "%." + p + "f", finite(value));
	}

	/**
	 * A signed value with {@code places} decimals and an ASCII sign: {@code "+3.1"}, {@code "-2.4"},
	 * {@code "+0.0"} (a value that rounds to zero is never printed as {@code "-0.0"}).
	 */
	public static String signed(double value, int places) {
		int p = Math.max(0, Math.min(6, places));
		double scale = Math.pow(10, p);
		double rounded = Math.round(finite(value) * scale) / scale;
		if (rounded == 0.0) {
			rounded = 0.0; // folds -0.0
		}
		return (rounded < 0 ? "-" : "+") + String.format(Locale.ROOT, "%." + p + "f", Math.abs(rounded));
	}

	/** A signed whole number: {@code "+12"}, {@code "-3"}, {@code "+0"}. */
	public static String signed(long value) {
		return (value < 0 ? "-" : "+") + Math.abs(value);
	}

	/** Milliseconds rounded: {@code "548 ms"}. */
	public static String ms(double millis) {
		return Math.round(finite(millis)) + " ms";
	}

	/** {@code "548 ms"}, or {@link #DASH} when {@code count ≤ 0}. */
	public static String ms(double millis, int count) {
		return count <= 0 ? DASH : ms(millis);
	}

	/** {@code "5/7"}. */
	public static String ratio(int part, int whole) {
		return part + "/" + whole;
	}

	/**
	 * A duration: {@code "m:ss"} below an hour ({@code "0:42"}, {@code "12:05"}), {@code "h:mm:ss"} from
	 * an hour ({@code "1:02:03"}). Negative values print as {@code "0:00"}.
	 */
	public static String duration(long millis) {
		long totalSec = Math.max(0L, millis) / 1000L;
		long h = totalSec / 3600L;
		long m = (totalSec / 60L) % 60L;
		long sec = totalSec % 60L;
		if (h > 0) {
			return String.format(Locale.ROOT, "%d:%02d:%02d", h, m, sec);
		}
		return String.format(Locale.ROOT, "%d:%02d", m, sec);
	}

	/**
	 * {@code s} unchanged if it fits in {@code maxPx}; otherwise the longest prefix that fits with
	 * {@link #ELLIPSIS} appended. If not even the ellipsis fits, returns as much of it as fits (possibly
	 * empty). Surrogate pairs are never split.
	 *
	 * @param width text width in pixels ({@code font::width} in game)
	 */
	public static String truncate(String s, int maxPx, ToIntFunction<String> width) {
		if (s == null || s.isEmpty()) {
			return s == null ? "" : s;
		}
		if (width.applyAsInt(s) <= maxPx) {
			return s;
		}
		int end = s.length();
		while (end > 0) {
			end = s.offsetByCodePoints(end, -1);
			String candidate = s.substring(0, end) + ELLIPSIS;
			if (width.applyAsInt(candidate) <= maxPx) {
				return candidate;
			}
		}
		// Nothing of the text fits beside the ellipsis: show as much of the ellipsis as fits.
		for (int n = ELLIPSIS.length(); n > 0; n--) {
			String dots = ELLIPSIS.substring(0, n);
			if (width.applyAsInt(dots) <= maxPx) {
				return dots;
			}
		}
		return "";
	}

	private static double finite(double v) {
		return Double.isFinite(v) ? v : 0.0;
	}
}
