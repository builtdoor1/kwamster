package kwamster.record.screen;

import kwamster.core.model.FightResult;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.stats.StatFormat;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * The scrolling list of saved fights on {@link SavedFightsScreen} (§10): one 34 px row per fight with
 * a result-colour strip, "WIN vs Kwamix" (bold result word, "+N" for extra opponents, the name
 * truncated to the row), a dim detail line ("Oct 20 15:30 · 0:42 · 74% · 2.84 · combo 5") and a star
 * when pinned.
 */
public final class FightList extends ObjectSelectionList<FightList.Row> {
	/** Row height in GUI pixels. */
	public static final int ROW_HEIGHT = 34;
	/** Widest row. */
	private static final int MAX_ROW_WIDTH = 360;

	static final int WIN_COLOUR = 0xFF3DDC84;
	static final int LOSS_COLOUR = 0xFFFF5A5F;
	static final int DRAW_COLOUR = 0xFFFFC53D;
	private static final int VALUE_COLOUR = 0xFFF1F3F5;
	private static final int DIM_COLOUR = 0xFF6B7280;
	private static final int PIN_COLOUR = 0xFFFFD43B;
	private static final String PIN = "★";
	private static final String SEP = " · ";
	private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("MMM d HH:mm", Locale.ENGLISH);

	private final Consumer<@Nullable FightSummaryRow> onSelect;

	/**
	 * @param top      y of the list's top edge
	 * @param onSelect told about every selection change (null = nothing selected)
	 */
	public FightList(Minecraft minecraft, int width, int height, int top, Consumer<@Nullable FightSummaryRow> onSelect) {
		super(minecraft, width, height, top, ROW_HEIGHT);
		this.onSelect = onSelect;
	}

	/** Replaces the rows, newest first, and selects the row with {@code selectId} if it is still there. */
	public void setRows(List<FightSummaryRow> rows, @Nullable String selectId) {
		this.clearEntries();
		Row select = null;
		for (FightSummaryRow data : rows) {
			Row row = new Row(data);
			this.addEntry(row);
			if (selectId != null && selectId.equals(data.id)) {
				select = row;
			}
		}
		this.refreshScrollAmount();
		this.setSelected(select);
	}

	/** Selects the row at {@code index} (clamped), or nothing if the list is empty. */
	public void selectIndex(int index) {
		List<Row> rows = this.children();
		this.setSelected(rows.isEmpty() ? null : rows.get(Math.max(0, Math.min(rows.size() - 1, index))));
	}

	/** The selected fight, or null. */
	public @Nullable FightSummaryRow selectedRow() {
		Row row = this.getSelected();
		return row == null ? null : row.data;
	}

	/** Index of the selected row, or -1. */
	public int selectedIndex() {
		Row row = this.getSelected();
		return row == null ? -1 : this.children().indexOf(row);
	}

	public boolean isEmpty() {
		return this.children().isEmpty();
	}

	@Override
	public void setSelected(@Nullable Row row) {
		super.setSelected(row);
		this.onSelect.accept(row == null ? null : row.data);
	}

	@Override
	public int getRowWidth() {
		return Math.max(120, Math.min(MAX_ROW_WIDTH, this.width - 24));
	}

	/** The colour of a result name (WIN / LOSS / DRAW). */
	static int resultColour(@Nullable String result) {
		if (FightResult.WIN.name().equals(result)) {
			return WIN_COLOUR;
		}
		if (FightResult.LOSS.name().equals(result)) {
			return LOSS_COLOUR;
		}
		return DRAW_COLOUR;
	}

	/** "WIN", "LOSS" or "DRAW". */
	static String resultWord(@Nullable String result) {
		if (FightResult.WIN.name().equals(result) || FightResult.LOSS.name().equals(result)) {
			return result;
		}
		return FightResult.DRAW.name();
	}

	/** "Oct 20 15:30 · 0:42 · 74% · 2.84 · combo 5" in local time. */
	static String detailLine(FightSummaryRow row) {
		String when = row.startEpochMs > 0L
				? WHEN.format(Instant.ofEpochMilli(row.startEpochMs).atZone(ZoneId.systemDefault()))
				: StatFormat.DASH;
		return when
				+ SEP + StatFormat.duration(row.durationMs)
				+ SEP + StatFormat.pct(row.accuracyPct, row.swings)
				+ SEP + StatFormat.reach(row.reachAvg, row.landed)
				+ SEP + "combo " + row.bestCombo;
	}

	/** One saved fight. */
	public final class Row extends ObjectSelectionList.Entry<Row> {
		private final FightSummaryRow data;
		private final String detail;

		Row(FightSummaryRow data) {
			this.data = data;
			this.detail = detailLine(data);
		}

		/** The index row this entry shows. */
		public FightSummaryRow data() {
			return this.data;
		}

		@Override
		public void renderContent(GuiGraphics graphics, int mouseX, int mouseY, boolean hovered, float partialTick) {
			Font font = FightList.this.minecraft.font;
			int x = this.getContentX();
			int y = this.getContentY();
			int w = this.getContentWidth();
			int h = this.getContentHeight();
			int colour = resultColour(this.data.result);
			graphics.fill(x, y, x + 3, y + h, colour);

			int textX = x + 9;
			int right = x + w - 4;
			int pinWidth = 0;
			if (this.data.pinned) {
				pinWidth = font.width(PIN) + 6;
				graphics.drawString(font, PIN, right - font.width(PIN), y + 4, PIN_COLOUR, true);
			}

			MutableComponent result = Component.literal(resultWord(this.data.result))
					.withStyle(ChatFormatting.BOLD).withColor(colour);
			String vs = " vs ";
			String extra = this.data.extraOpponents > 0 ? " +" + this.data.extraOpponents : "";
			int nameMax = right - pinWidth - textX - font.width(result) - font.width(vs) - font.width(extra);
			String name = StatFormat.truncate(this.data.opponent == null ? "" : this.data.opponent,
					Math.max(0, nameMax), font::width);
			MutableComponent line = result.append(Component.literal(vs + name + extra)
					.withStyle(style -> style.withBold(false)).withColor(VALUE_COLOUR));
			graphics.drawString(font, line, textX, y + 4, VALUE_COLOUR, true);

			String detailText = StatFormat.truncate(this.detail, Math.max(0, right - textX), font::width);
			graphics.drawString(font, detailText, textX, y + 17, DIM_COLOUR, true);
		}

		@Override
		public Component getNarration() {
			String extra = this.data.extraOpponents > 0 ? " and " + this.data.extraOpponents + " more" : "";
			return Component.literal(resultWord(this.data.result) + " vs " + this.data.opponent + extra
					+ ", " + this.detail + (this.data.pinned ? ", pinned" : ""));
		}
	}
}
