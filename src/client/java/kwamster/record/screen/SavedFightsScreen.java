package kwamster.record.screen;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.ShareLink;
import kwamster.core.model.StatsTotals;
import kwamster.core.service.FightArchive;
import kwamster.core.stats.StatFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The saved-fights browser (§10): the lifetime summary, a {@link FightList} of every saved fight, and
 * buttons acting on the selected fight (copy or open its share link, pin, delete with a two-click
 * confirm) plus "Open folder" and "Done".
 *
 * <p>Links are built only when a button asks for one (loading the fight file on the client thread;
 * it is small) and cached per fight while the screen is open. The list follows the archive: a fight
 * saved while the screen is open appears without reopening it.
 */
public final class SavedFightsScreen extends Screen {
	private static final int TITLE_COLOUR = 0xFFF1F3F5;
	private static final int DIM_COLOUR = 0xFF6B7280;
	private static final int EMPTY_COLOUR = 0xFF9AA4B2;
	private static final long FLASH_NANOS = 1_500_000_000L;
	private static final long CONFIRM_NANOS = 3_000_000_000L;
	private static final int COPY_WIDTH = 72;
	private static final int OPEN_WIDTH = 56;
	private static final int PIN_WIDTH = 56;
	private static final int DELETE_WIDTH = 116;
	private static final int BUTTON_HEIGHT = 20;
	private static final int GAP = 4;
	private static final int LIST_TOP = 32;
	private static final DateTimeFormatter SINCE = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH);

	private final @Nullable Screen parent;
	private final FightArchive archive;
	private final Map<String, Optional<ShareLink>> links = new HashMap<>();

	private @Nullable FightList list;
	private @Nullable Button copyButton;
	private @Nullable Button openButton;
	private @Nullable Button pinButton;
	private @Nullable Button deleteButton;

	/** Kept across re-inits (window resizes). */
	private @Nullable String selectedId;
	private @Nullable List<FightSummaryRow> shownRows;
	private String lifetimeLine = "";
	private @Nullable String confirmDeleteId;
	private long confirmUntil;
	private @Nullable String copyFlash;
	private long copyFlashUntil;
	private @Nullable String openFlash;
	private long openFlashUntil;

	public SavedFightsScreen(@Nullable Screen parent, FightArchive archive) {
		super(Component.literal("Saved fights"));
		this.parent = parent;
		this.archive = archive;
	}

	@Override
	protected void init() {
		int listBottom = this.height - 56;
		this.list = new FightList(this.minecraft, this.width, Math.max(FightList.ROW_HEIGHT, listBottom - LIST_TOP),
				LIST_TOP, this::onSelected);
		this.addRenderableWidget(this.list);

		// The delete button is wider so "Click again to delete" fits without scrolling.
		int rowWidth = COPY_WIDTH + OPEN_WIDTH + PIN_WIDTH + DELETE_WIDTH + 3 * GAP;
		int x = this.width / 2 - rowWidth / 2;
		int y1 = this.height - 50;
		int y2 = this.height - 26;
		int bx = x;
		this.copyButton = this.addRenderableWidget(Button.builder(Component.literal("Copy link"), b -> this.copyLink())
				.bounds(bx, y1, COPY_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal("Copy the selected fight's share link")))
				.build());
		bx += COPY_WIDTH + GAP;
		this.openButton = this.addRenderableWidget(Button.builder(Component.literal("Open"), b -> this.openLink())
				.bounds(bx, y1, OPEN_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal("Open the selected fight in the web viewer")))
				.build());
		bx += OPEN_WIDTH + GAP;
		this.pinButton = this.addRenderableWidget(Button.builder(Component.literal("Pin"), b -> this.togglePin())
				.bounds(bx, y1, PIN_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal("Pinned fights are never removed to make room")))
				.build());
		bx += PIN_WIDTH + GAP;
		this.deleteButton = this.addRenderableWidget(Button.builder(Component.literal("Delete"), b -> this.delete())
				.bounds(bx, y1, DELETE_WIDTH, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal("Delete the selected fight's file")))
				.build());
		int halfWidth = (rowWidth - GAP) / 2;
		this.addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> this.openFolder())
				.bounds(x, y2, halfWidth, BUTTON_HEIGHT)
				.tooltip(Tooltip.create(Component.literal(this.archive.folder().toString())))
				.build());
		this.addRenderableWidget(Button.builder(Component.literal("Done"), b -> this.onClose())
				.bounds(x + rowWidth - halfWidth, y2, halfWidth, BUTTON_HEIGHT)
				.build());

		this.lifetimeLine = lifetimeLine(this.archive.lifetime());
		this.refreshRows();
		this.updateButtons();
	}

	@Override
	public void tick() {
		super.tick();
		if (this.archive.list() != this.shownRows) {
			this.refreshRows(); // a fight was saved or pruned while the screen is open
			this.lifetimeLine = lifetimeLine(this.archive.lifetime());
		}
		this.updateButtons();
	}

	@Override
	public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
		super.render(graphics, mouseX, mouseY, partialTick);
		graphics.drawCenteredString(this.font, this.title, this.width / 2, 8, TITLE_COLOUR);
		graphics.drawCenteredString(this.font, this.lifetimeLine, this.width / 2, 20, DIM_COLOUR);
		if (this.list != null && this.list.isEmpty()) {
			int mid = LIST_TOP + (this.list.getHeight() - 9) / 2;
			graphics.drawCenteredString(this.font, "No fights saved yet. Fight someone!", this.width / 2, mid, EMPTY_COLOUR);
			if (!Kwamster.config().recording.saveFights) {
				graphics.drawCenteredString(this.font, "Saving is off (Settings → Recording & sharing)",
						this.width / 2, mid + 12, DIM_COLOUR);
			}
		}
	}

	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	// ---- actions --------------------------------------------------------------

	private void onSelected(@Nullable FightSummaryRow row) {
		String id = row == null ? null : row.id;
		if (id == null || !id.equals(this.selectedId)) {
			this.confirmDeleteId = null;
			this.copyFlash = null;
			this.openFlash = null;
		}
		this.selectedId = id;
		this.updateButtons();
	}

	private void copyLink() {
		ShareLink link = this.selectedLink();
		if (link == null) {
			this.copyFlash = "No link";
		} else {
			this.minecraft.keyboardHandler.setClipboard(link.url());
			this.copyFlash = "Copied ✔";
		}
		this.copyFlashUntil = Clock.nanos() + FLASH_NANOS;
		this.updateButtons();
	}

	private void openLink() {
		ShareLink link = this.selectedLink();
		if (link == null) {
			this.flashOpen("No link");
			return;
		}
		try {
			Util.getPlatform().openUri(new URI(link.url()));
		} catch (URISyntaxException | RuntimeException e) {
			LogLimiter.error("SavedFightsScreen.open", "Could not open the share link", e);
			this.flashOpen("Failed");
		}
	}

	private void togglePin() {
		FightSummaryRow row = this.selectedRow();
		if (row != null) {
			this.archive.setPinned(row.id, !row.pinned);
			this.refreshRows();
		}
	}

	private void delete() {
		FightSummaryRow row = this.selectedRow();
		if (row == null || this.list == null) {
			return;
		}
		long now = Clock.nanos();
		if (row.id.equals(this.confirmDeleteId) && now < this.confirmUntil) {
			int index = this.list.selectedIndex();
			this.confirmDeleteId = null;
			this.archive.delete(row.id);
			this.links.remove(row.id);
			this.selectedId = null;
			this.refreshRows();
			this.list.selectIndex(index);
		} else {
			this.confirmDeleteId = row.id;
			this.confirmUntil = now + CONFIRM_NANOS;
		}
		this.updateButtons();
	}

	private void openFolder() {
		Path folder = this.archive.folder();
		try {
			Util.getPlatform().openPath(folder);
		} catch (RuntimeException e) {
			LogLimiter.error("SavedFightsScreen.folder", "Could not open " + folder, e);
		}
	}

	// ---- state ----------------------------------------------------------------

	private void refreshRows() {
		List<FightSummaryRow> rows = this.archive.list();
		this.shownRows = rows;
		if (this.list != null) {
			this.list.setRows(rows, this.selectedId);
		}
	}

	private @Nullable FightSummaryRow selectedRow() {
		return this.list == null ? null : this.list.selectedRow();
	}

	/** The selected fight's link, built on first use and cached while the screen is open; null if unavailable. */
	private @Nullable ShareLink selectedLink() {
		FightSummaryRow row = this.selectedRow();
		if (row == null) {
			return null;
		}
		Optional<ShareLink> link = this.links.get(row.id);
		if (link == null) {
			try {
				link = this.archive.load(row.id).map(this.archive::shareLink);
			} catch (RuntimeException e) {
				LogLimiter.error("SavedFightsScreen.link", "Could not build the share link", e);
				link = Optional.empty();
			}
			this.links.put(row.id, link);
		}
		return link.orElse(null);
	}

	private void flashOpen(String text) {
		this.openFlash = text;
		this.openFlashUntil = Clock.nanos() + FLASH_NANOS;
		this.updateButtons();
	}

	private void updateButtons() {
		if (this.copyButton == null || this.openButton == null || this.pinButton == null || this.deleteButton == null) {
			return;
		}
		long now = Clock.nanos();
		FightSummaryRow row = this.selectedRow();
		boolean selected = row != null;
		this.copyButton.active = selected;
		this.openButton.active = selected;
		this.pinButton.active = selected;
		this.deleteButton.active = selected;

		if (this.copyFlash != null && now >= this.copyFlashUntil) {
			this.copyFlash = null;
		}
		if (this.openFlash != null && now >= this.openFlashUntil) {
			this.openFlash = null;
		}
		if (this.confirmDeleteId != null && (now >= this.confirmUntil || row == null || !row.id.equals(this.confirmDeleteId))) {
			this.confirmDeleteId = null;
		}
		setLabel(this.copyButton, this.copyFlash != null ? this.copyFlash : "Copy link");
		setLabel(this.openButton, this.openFlash != null ? this.openFlash : "Open");
		setLabel(this.pinButton, row != null && row.pinned ? "Unpin" : "Pin");
		setLabel(this.deleteButton, this.confirmDeleteId != null ? "Click again to delete" : "Delete");
	}

	private static void setLabel(Button button, String text) {
		if (!text.equals(button.getMessage().getString())) {
			button.setMessage(Component.literal(text));
		}
	}

	/** "132 fights · 78W 41L 13D · 71% acc · since Oct 20". */
	static String lifetimeLine(StatsTotals t) {
		String since = t.sinceEpochMs > 0L
				? SINCE.format(Instant.ofEpochMilli(t.sinceEpochMs).atZone(ZoneId.systemDefault()))
				: StatFormat.DASH;
		if (t.fights <= 0) {
			return "No counted fights yet · since " + since;
		}
		return t.fights + (t.fights == 1 ? " fight" : " fights")
				+ " · " + t.wins + "W " + t.losses + "L " + t.draws + "D"
				+ " · " + StatFormat.pct(t.landed, t.swings) + " acc"
				+ " · since " + since;
	}
}
