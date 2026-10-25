package kwamster.hud;

import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The HUD's fixed strings plus the sample and worst-case strings each element can produce (§7.4,
 * §7.9). {@link HudPreviewData} shows the samples in the editor, the elements use the labels, and the
 * fit tests measure the worst cases with {@link AsciiWidths}, so an overflow shows up in the editor and
 * fails a test before it reaches a player. Minecraft-free (R6).
 */
public final class PreviewStrings {
	/** Minimum gap between a label and its value, and between a header's left text and its chip. */
	public static final int GAP = 6;
	/** Separator between two figures in one value. */
	public static final String SEP = " · ";

	// ---- names and keys ---------------------------------------------------------

	/** The opponent in the editor's sample fight. */
	public static final String SAMPLE_NAME = "Kwamix";
	/** A 16-character name (the vanilla maximum), shown on the worst-case card and used by the fit tests. */
	public static final String WORST_NAME = "Opponent_Name_16";
	/** A typical copy-link key name. */
	public static final String SAMPLE_KEY = "Right Control";
	/** The longest vanilla key name ({@code key.keyboard.keypad.decimal}, en_us). */
	public static final String WORST_KEY = "Keypad Decimal";
	/** Prefix before the opponent name in the panel header and on the card. */
	public static final String VS = "vs ";
	/** Wordmark shown when no fight is in scope. */
	public static final String WORDMARK = "KWAMSTER";
	/** Panel chip in SESSION scope. */
	public static final String SESSION_CHIP = "SESSION";
	/** The live-fight dot before the timer. */
	public static final String LIVE_DOT = "●";
	/** The single row shown when there is nothing to show yet. */
	public static final String NO_FIGHTS = "No fights yet";

	// ---- panel row labels (§7.4, in PanelRows order) -----------------------------

	public static final String HITS = "Hits";
	public static final String REACH = "Reach";
	public static final String COMBO = "Combo";
	public static final String STREAK = "Streak";
	public static final String CRITS = "Crits";
	public static final String SPRINT = "Sprint";
	public static final String THREE_BLOCK = "3-Block";
	public static final String RESETS = "Resets";
	public static final String INTERVAL = "Interval";
	public static final String TAKEN = "Taken";
	public static final String AIM = "Aim";
	public static final String TRADES = "Trades";
	public static final String ADVANTAGE = "Advantage";
	public static final String RECORD = "Record";

	// ---- card cell labels (§7.9) ---------------------------------------------------

	public static final String ACC = "Acc";
	public static final String MAX = "Max";

	// ---- other fixed strings ---------------------------------------------------------

	/** The stoplight label's reserved width is measured on this string (§7.7). */
	public static final String STOPLIGHT_LABEL_RESERVE = "APPROACH -9.9";
	/** The combo counter's caption. */
	public static final String COMBO_CAPTION = "COMBO";
	/** The editor's help line (§7.3). */
	public static final String HELP_LINE = "Drag to move · Scroll to resize · Right-click to reset · Shift: no snap";
	/** Tag on an element whose feature is switched off. */
	public static final String OFF_TAG = "(off in settings)";

	// ---- samples (what the editor shows) ------------------------------------------------

	/** The sample fight's timer chip. */
	public static final String SAMPLE_CHIP = "● 0:42";
	/** The sample hit indicator text (reach, two spaces, tier label). */
	public static final String INDICATOR_SAMPLE = "2.97  3-BLOCK CRIT";

	/** Every panel row the sample fight can show, in PanelRows order (Record is SESSION-only and absent). */
	public static final List<Cell> PANEL_ROWS_SAMPLE = List.of(
			new Cell(HITS, "23/31 · 74%", 0),
			new Cell(REACH, "2.84 avg · 3.00", 0),
			new Cell(COMBO, "3 · best 5", 0),
			new Cell(STREAK, "4 · best 9", 0),
			new Cell(CRITS, "6 · 26%", 0),
			new Cell(SPRINT, "12 · 52%", 0),
			new Cell(THREE_BLOCK, "4 · 17%", 0),
			new Cell(RESETS, "5/7 · 71%", 0),
			new Cell(INTERVAL, "548 ms", 0),
			new Cell(TAKEN, "14", 0),
			new Cell(AIM, "C12 E4 H5 L2", 0),
			new Cell(TRADES, "3W 1L", 0),
			new Cell(ADVANTAGE, "YOU", HudLayoutMath.ADV_BAR_GAP + HudLayoutMath.ADV_BAR_W));

	/** The sample (worst-case per §7.9) card cells, row by row. */
	public static final List<Cell> CARD_CELLS_SAMPLE = List.of(
			new Cell(ACC, "100%", 0), new Cell(REACH, "2.97", 0), new Cell(MAX, "3.00", 0),
			new Cell(CRITS, "12", 0), new Cell(THREE_BLOCK, "9", 0), new Cell(SPRINT, "17", 0),
			new Cell(COMBO, "7", 0), new Cell(RESETS, "12/17", 0), new Cell(TAKEN, "14", 0));

	// ---- worst cases (what the fit tests measure) ----------------------------------------

	/**
	 * The widest value every panel row can produce in any scope (live fight, last fight, session):
	 * 5-digit counts, {@code 100%}, {@code best 99}, a two-digit custom-range reach. Aim counts are
	 * abbreviated from 10 000 ({@code 12k}), so 4 digits is their worst case.
	 */
	public static final List<Cell> PANEL_ROWS_WORST = List.of(
			new Cell(HITS, "99999/99999 · 100%", 0),
			new Cell(REACH, "12.34 avg · 12.34", 0),
			new Cell(COMBO, "99 · best 99", 0),
			new Cell(STREAK, "999 · best 999", 0),
			new Cell(CRITS, "99999 · 100%", 0),
			new Cell(SPRINT, "99999 · 100%", 0),
			new Cell(THREE_BLOCK, "99999 · 100%", 0),
			new Cell(RESETS, "99999/99999 · 100%", 0),
			new Cell(INTERVAL, "9999 ms", 0),
			new Cell(TAKEN, "99999", 0),
			new Cell(AIM, "C9999 E9999 H9999 L9999", 0),
			new Cell(TRADES, "999W 999L", 0),
			new Cell(ADVANTAGE, "THEM", HudLayoutMath.ADV_BAR_GAP + HudLayoutMath.ADV_BAR_W),
			new Cell(ADVANTAGE, "100% · 100%", 0),
			new Cell(RECORD, "999W 999L 999D", 0),
			new Cell(NO_FIGHTS, "", 0));

	/**
	 * Every panel header state with its widest chip, before name truncation: live fight, last fight,
	 * SESSION and nothing-yet.
	 */
	public static final List<Header> PANEL_HEADERS_WORST = List.of(
			new Header(VS + WORST_NAME, false, "", LIVE_DOT + " 1:02:03"),
			new Header(VS + WORST_NAME, false, "DRAW", SEP + "1:02:03"),
			new Header(VS + WORST_NAME, false, "LOSS", SEP + "1:02:03"),
			new Header(WORDMARK, true, "", SESSION_CHIP),
			new Header(WORDMARK, true, "", ""));

	/** The widest value every card cell can produce, row by row. */
	public static final List<Cell> CARD_CELLS_WORST = List.of(
			new Cell(ACC, "100%", 0), new Cell(REACH, "12.34", 0), new Cell(MAX, "12.34", 0),
			new Cell(CRITS, "99999", 0), new Cell(THREE_BLOCK, "99999", 0), new Cell(SPRINT, "99999", 0),
			new Cell(COMBO, "999", 0), new Cell(RESETS, "999/999", 0), new Cell(TAKEN, "99999", 0));

	/** The widest card result word (bold). */
	public static final String CARD_RESULT_WORST = "DRAW";
	/** The widest card duration. */
	public static final String CARD_DURATION_WORST = "1:02:03";
	/** The longest card footer before truncation. */
	public static final String CARD_FOOTER_WORST = footer(WORST_KEY);

	private PreviewStrings() {
	}

	/** The card footer for a bound copy-link key (§7.9). */
	public static String footer(String keyName) {
		return "Saved · press [" + keyName + "] to share";
	}

	/**
	 * A label and its value as one row or cell draws them: label left, value right, at least
	 * {@link #GAP} apart. {@code extraPx} is drawn beside the value (the advantage bar).
	 */
	public record Cell(String label, String value, int extraPx) {
		/** {@code width(label) + GAP + width(value) + extraPx}; just the label when the value is empty. */
		public int width(ToIntFunction<String> width) {
			if (this.value.isEmpty() && this.extraPx == 0) {
				return width.applyAsInt(this.label);
			}
			return width.applyAsInt(this.label) + GAP + width.applyAsInt(this.value) + this.extraPx;
		}
	}

	/**
	 * A panel header: left text (bold for the wordmark) and a right chip made of a bold part (the result
	 * word) followed by a regular part.
	 */
	public record Header(String left, boolean leftBold, String chipBold, String chipPlain) {
		/** Width of the chip alone (0 when there is none). */
		public int chipWidth(ToIntFunction<String> plain, ToIntFunction<String> bold) {
			return bold.applyAsInt(this.chipBold) + plain.applyAsInt(this.chipPlain);
		}

		/** Left text, {@link #GAP} and chip; just the left text when there is no chip. */
		public int width(ToIntFunction<String> plain, ToIntFunction<String> bold) {
			int left = this.leftBold ? bold.applyAsInt(this.left) : plain.applyAsInt(this.left);
			int chip = chipWidth(plain, bold);
			return chip == 0 ? left : left + GAP + chip;
		}
	}
}
