package kwamster.hud.presets;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.ScreenRegistry;
import kwamster.core.config.Anchor;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.HudPreset;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.KwamsterConfig.CrosshairShape;
import kwamster.core.io.Json;
import kwamster.core.stats.StatFormat;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudManager;
import kwamster.hud.PreviewStrings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.player.PlayerModel;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.PlayerModelType;
import net.minecraft.world.entity.player.PlayerSkin;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Picks a ready-made HUD look ({@link HudPreset}) with a live preview. Opened from the settings
 * screen's HUD tab ("HUD presets…") through {@code ScreenId.HUD_PRESETS}; the optional argument names
 * a preset to preselect (default: the one in use).
 *
 * <ul>
 *   <li><b>Left:</b> one card per preset with its name, description, four colour chips (accent, values,
 *       the panel over a patch of sky, the crosshair) and a "Current" badge on the preset the config
 *       already shows. Descriptions get two lines when every card fits that way (GUI 3 and below on
 *       1080p), else one line with the whole text as a tooltip; the list scrolls when it does not fit.</li>
 *   <li><b>Right:</b> the selected look at 1× over a flat-world backdrop. The real stats panel, hit
 *       indicator, combo counter and summary card are drawn from their preview data through
 *       {@link HudManager#drawElement}, while {@link Kwamster#withPreviewConfig} makes them read the
 *       previewed config. The crosshair and an opponent with a direction bar and a name tag are drawn
 *       here: the CROSSHAIR and DIRECTION units may not be imported (R1), so their geometry (§7.8, §5.11a)
 *       is mirrored. Two views: <i>In fight</i> (panel, crosshair on the opponent in the on-a-player
 *       colour, indicator, combo) and <i>Fight over</i> (summary card, crosshair at rest). A legend below
 *       shows all four crosshair state colours and the three bar colours.</li>
 *   <li><b>Apply</b> writes the preset onto the live config, saves, and opens a fresh settings screen on
 *       the HUD tab, so its entries show the new values (unsaved edits in the old one are discarded).
 *       <b>Back</b> and Esc return to the parent untouched. A double-click on a card applies it;
 *       ↑/↓ choose, ←/→ switch the view, Enter applies.</li>
 * </ul>
 *
 * The previews are deep copies of the live config with the preset applied, so the player's own tiers,
 * 3-block reach and feature switches show exactly as they will (an element switched off outside the look
 * is drawn as a ghost with an "(off in settings)" tag). Positions and element scales are not part of a
 * look, so the preview stages the elements at 1× instead of copying the layout. Every draw step and event
 * handler is isolated, so nothing throws into vanilla (R3). Client thread.
 */
public final class HudPresetsScreen extends Screen {
	private static final Component TITLE = Component.literal("HUD presets");
	private static final HudPreset[] PRESETS = HudPreset.values();
	/** The settings category Apply opens (§3.12; the title is CONFIGUI's, which is not imported). */
	private static final String SETTINGS_HUD_TAB = "HUD";

	// ---- KWAMSTER palette (§7.0) -----------------------------------------------------------
	private static final int ACCENT = 0xFF8B5CF6;
	private static final int VALUE = 0xFFF1F3F5;
	private static final int LABEL = 0xFF9AA4B2;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int BORDER = 0x26FFFFFF;
	private static final int BORDER_HOVER = 0x47FFFFFF;
	private static final int CARD_BG = 0xE00E1116;
	private static final int CARD_BG_HOVER = 0xE0161A21;
	private static final int CARD_BG_SELECTED = 0xE01A1630;
	private static final int TAB_BG = 0xB00E1116;
	private static final int TAB_HOVER = 0x1FFFFFFF;
	private static final int FRAME = 0x40FFFFFF;
	private static final int TAG_BG = 0xCC000000;
	private static final int CHIP_FRAME = 0x99000000;
	private static final int SCROLL_TRACK = 0x1AFFFFFF;
	private static final int SCROLL_THUMB = 0x66FFFFFF;
	private static final int SCROLL_THUMB_HOT = 0xB3FFFFFF;

	// ---- the backdrop: a flat world at noon -----------------------------------------------------
	private static final int SKY_TOP = 0xFF6C97E0;
	private static final int SKY_HORIZON = 0xFFB3CAED;
	private static final int GROUND_FAR = 0xFF5D7D3A;
	private static final int GROUND_NEAR = 0xFF3A5224;
	private static final int HORIZON_LINE = 0x33FFFFFF;
	private static final int CLOUD = 0xD9FFFFFF;
	/** Vanilla's name tag background: the 25 % text background (Options.getBackgroundOpacity). */
	private static final int NAMETAG_BG = 0x40000000;
	/** Stands in for the player model if it cannot be baked. */
	private static final int SILHOUETTE = 0xE0232A36;

	// ---- text ------------------------------------------------------------------------------------
	private static final String TAB_FIGHT = "In fight";
	private static final String TAB_AFTER = "Fight over";
	private static final String BADGE = "Current";
	private static final String HINT = "Double-click a card to apply";
	private static final String[] STATE_NAMES = {"Default", "On a player in reach", "Crit ready (falling)", "Crit ready on a player"};
	private static final String[] BAR_NAMES = {"Moving forward", "Standing still or strafing", "Moving backward"};

	// ---- layout (GUI px) ---------------------------------------------------------------------------
	private static final int MARGIN = 8;
	private static final int MARGIN_SMALL = 6;
	/** Content starts this far down; the title and the view tabs sit above it. */
	private static final int TOP = 22;
	/** Content ends this far above the bottom; the buttons sit below it. */
	private static final int BOTTOM = 32;
	private static final int BUTTON_H = 20;
	private static final int BUTTON_BOTTOM = 26;
	private static final int BACK_W = 100;
	/** A card with a one-line description (the full text is a tooltip), used when the tall ones do not fit. */
	private static final int CARD_H_SHORT = 25;
	/** A card with a two-line description. */
	private static final int CARD_H_TALL = 34;
	private static final int CARD_GAP = 2;
	private static final int CARD_PAD = 5;
	private static final int CHIP = 5;
	private static final int SCROLLBAR_W = 2;
	private static final int SCROLL_STEP = 20;
	private static final int TAB_H = 13;
	private static final int TAB_PAD = 6;
	private static final int LEGEND_H = 12;
	private static final int LEGEND_GAP = 4;
	/** HUD elements keep this far from the preview's edge. */
	private static final int INSET = 6;
	private static final float GHOST_ALPHA = 0.35f;

	// ---- the opponent, in blocks (players are drawn at 15/16 of their model size) --------------------
	private static final double MODEL_PER_BLOCK = 1.0 / 0.9375;
	private static final double CHEST = 1.3;
	private static final double EYES = 1.62;
	private static final double HITBOX = 1.8;
	private static final double NAMETAG = 0.5;
	/** HeadBarRenderer's backdrop margin around the bar. */
	private static final double BAR_BORDER = 0.022;
	private static final float FIGURE_YAW = 20f;

	private final @Nullable Screen parent;
	/** Each preset applied to a deep copy of the live config. */
	private final Map<HudPreset, KwamsterConfig> previews = new EnumMap<>(HudPreset.class);
	/** The preset the live config shows, or null for a custom look. */
	private final @Nullable HudPreset current;
	private int selected;
	private boolean afterFight;
	private int scroll;
	private boolean draggingScrollbar;
	private double dragGrab;
	private int lastClicked = -1;
	private @Nullable Button applyButton;
	private @Nullable PlayerModel figure;
	private @Nullable Identifier figureSkin;
	private boolean figureFailed;
	private @Nullable CrosshairKey spansKey;
	private @Nullable CrosshairSpans spans;
	/** Each card's description, wrapped to its lines; rebuilt with the layout. */
	private final Map<HudPreset, List<String>> descriptionLines = new EnumMap<>(HudPreset.class);
	/** Presets whose description the card cuts short: hovering shows it whole. */
	private final Set<HudPreset> clipped = EnumSet.noneOf(HudPreset.class);

	// layout, recomputed by init() (also on every resize)
	private int cardH = CARD_H_SHORT;
	private int listX;
	private int listY;
	private int listW;
	private int listH;
	private int viewX;
	private int viewY;
	private int viewW;
	private int viewH;
	private int tabX;
	private int tabY;
	private int tabW0;
	private int tabW1;
	private int applyX;

	/**
	 * @param parent the screen to return to (Back, Esc); normally the settings screen
	 * @param arg    an optional {@code HudPreset} name to preselect; anything else is ignored
	 */
	public HudPresetsScreen(@Nullable Screen parent, @Nullable String arg) {
		super(TITLE);
		this.parent = parent;
		KwamsterConfig live = Kwamster.config();
		this.current = HudPreset.matching(live);
		for (HudPreset p : PRESETS) {
			this.previews.put(p, previewConfig(live, p));
		}
		HudPreset initial = HudPreset.fromName(arg);
		if (initial == null) {
			initial = this.current != null ? this.current : HudPreset.KWAMSTER;
		}
		this.selected = initial.ordinal();
	}

	// ---- lifecycle -----------------------------------------------------------------------------

	@Override
	protected void init() {
		layout();
		int y = this.height - BUTTON_BOTTOM;
		addRenderableWidget(Button.builder(Component.literal("Back"), b -> onClose())
				.bounds(this.listX, y, Math.min(this.listW, BACK_W), BUTTON_H)
				.tooltip(Tooltip.create(Component.literal("Back to where you came from, nothing changed (Esc).")))
				.build());
		int applyW = Math.min(this.viewW, clamp(this.viewW / 2, 110, 150));
		this.applyX = this.viewX + this.viewW - applyW;
		this.applyButton = addRenderableWidget(Button.builder(Component.literal("Apply"), b -> apply())
				.bounds(this.applyX, y, applyW, BUTTON_H)
				.build());
		refreshApply();
		ensureVisible(this.selected);
	}

	/** Nothing starts focused, so Enter applies the selected card instead of pressing Back. */
	@Override
	protected void setInitialFocus() {
	}

	/** Back and Esc: return to the parent, nothing changed. */
	@Override
	public void onClose() {
		this.minecraft.setScreen(this.parent);
	}

	private void layout() {
		int m = this.width < 480 ? MARGIN_SMALL : MARGIN;
		int contentBottom = Math.max(TOP + 60, this.height - BOTTOM);
		this.listX = m;
		this.listY = TOP;
		this.listW = clamp(Math.round(this.width * 0.36f), 120, 210);
		this.listH = contentBottom - TOP;
		this.cardH = PRESETS.length * (CARD_H_TALL + CARD_GAP) - CARD_GAP <= this.listH ? CARD_H_TALL : CARD_H_SHORT;
		wrapDescriptions();
		this.viewX = this.listX + this.listW + m;
		this.viewY = TOP;
		this.viewW = Math.max(60, this.width - m - this.viewX);
		this.viewH = Math.max(40, contentBottom - LEGEND_H - LEGEND_GAP - TOP);
		this.tabW0 = this.font.width(TAB_FIGHT) - 1 + 2 * TAB_PAD;
		this.tabW1 = this.font.width(TAB_AFTER) - 1 + 2 * TAB_PAD;
		this.tabX = this.viewX + this.viewW - this.tabW0 - this.tabW1;
		this.tabY = (TOP - TAB_H) / 2;
	}

	/**
	 * Writes the selected preset onto the live config, saves, and reopens the settings on the HUD tab.
	 * Runs from a button press, so nothing may escape into vanilla's input handling (R3).
	 */
	private void apply() {
		HudPreset p = selectedPreset();
		try {
			p.apply(Kwamster.config());
			Kwamster.saveConfig();
			KwamsterClient.LOG.info("KWAMSTER HUD preset applied: {}", p.displayName());
			// A fresh settings screen, so its entries show the new values. It leads back to wherever the
			// old one came from, never to the old one (whose Save would write the previous look back).
			Screen back = outerScreen(this.parent);
			if (!Kwamster.services().screens().open(ScreenRegistry.ScreenId.SETTINGS, back, SETTINGS_HUD_TAB)) {
				this.minecraft.setScreen(back);
			}
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen.apply", "Could not apply the " + p.displayName() + " HUD preset", t);
		}
	}

	private boolean canApply() {
		return selectedPreset() != this.current;
	}

	private void refreshApply() {
		Button b = this.applyButton;
		if (b == null) {
			return;
		}
		HudPreset p = selectedPreset();
		boolean inUse = !canApply();
		b.active = !inUse;
		b.setMessage(Component.literal(inUse ? "In use" : "Apply"));
		b.setTooltip(Tooltip.create(Component.literal(inUse
				? p.displayName() + " is the look you have now."
				: "Gives the HUD, crosshair, hit indicator, card and direction bars the " + p.displayName()
						+ " look and saves. Positions, sounds and detection settings are kept. "
						+ "Unsaved changes in the settings screen are discarded.")));
	}

	private HudPreset selectedPreset() {
		return PRESETS[clamp(this.selected, 0, PRESETS.length - 1)];
	}

	private void select(int index) {
		int i = clamp(index, 0, PRESETS.length - 1);
		if (i != this.selected) {
			this.selected = i;
			refreshApply();
		}
		ensureVisible(i);
	}

	// ---- rendering -----------------------------------------------------------------------------

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		drawSafely("header", () -> drawHeader(g, mouseX, mouseY));
		drawSafely("list", () -> drawList(g, mouseX, mouseY));
		drawSafely("preview", () -> drawPreview(g));
		drawSafely("legend", () -> drawLegend(g, mouseX, mouseY));
		drawSafely("hint", () -> drawHint(g));
		super.render(g, mouseX, mouseY, partialTick);
	}

	private static void drawSafely(String part, Runnable draw) {
		try {
			draw.run();
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen/" + part, "Could not draw the HUD presets " + part, t);
		}
	}

	/** The title, which look is in use, and the two view tabs. */
	private void drawHeader(GuiGraphics g, int mouseX, int mouseY) {
		int y = (TOP - 8) / 2;
		int x = this.listX + Draw.text(g, this.font, TITLE.copy().withStyle(ChatFormatting.BOLD), this.listX, y, VALUE, true);
		String status = PreviewStrings.SEP + (this.current != null ? "Current: " + this.current.displayName() : "Current look: custom");
		int room = this.tabX - 6 - x;
		if (room >= this.font.width(PreviewStrings.SEP) + 24) {
			Draw.text(g, this.font, StatFormat.truncate(status, room, this.font::width), x, y, LABEL, true);
		}

		int split = this.tabX + this.tabW0;
		int x1 = split + this.tabW1;
		int y1 = this.tabY + TAB_H;
		Draw.roundRect(g, this.tabX, this.tabY, x1, y1, TAB_BG);
		Draw.border(g, this.tabX, this.tabY, x1, y1, BORDER);
		drawTab(g, TAB_FIGHT, this.tabX, split, !this.afterFight, inside(mouseX, mouseY, this.tabX, this.tabY, split, y1));
		drawTab(g, TAB_AFTER, split, x1, this.afterFight, inside(mouseX, mouseY, split, this.tabY, x1, y1));
	}

	private void drawTab(GuiGraphics g, String label, int x0, int x1, boolean active, boolean hover) {
		int y0 = this.tabY;
		if (active) {
			Draw.roundRect(g, x0 + 1, y0 + 1, x1 - 1, y0 + TAB_H - 1, ACCENT);
		} else if (hover) {
			Draw.fill(g, x0 + 1, y0 + 1, x1 - 1, y0 + TAB_H - 1, TAB_HOVER);
		}
		int textW = this.font.width(label) - 1;
		Draw.text(g, this.font, label, x0 + (x1 - x0 - textW) / 2, y0 + 3, active ? WHITE : hover ? VALUE : LABEL, false);
	}

	// ---- the preset list --------------------------------------------------------------------------

	private void drawList(GuiGraphics g, int mouseX, int mouseY) {
		int hovered = this.draggingScrollbar ? -1 : cardAt(mouseX, mouseY);
		int w = cardWidth();
		g.enableScissor(this.listX, this.listY, this.listX + this.listW, this.listY + this.listH);
		try {
			for (int i = 0; i < PRESETS.length; i++) {
				int y = cardY(i);
				if (y + this.cardH > this.listY && y < this.listY + this.listH) {
					drawCard(g, i, y, w, i == hovered);
				}
			}
		} finally {
			g.disableScissor();
		}
		if (scrollable()) {
			drawScrollbar(g, mouseX, mouseY);
		}
		if (hovered >= 0 && this.clipped.contains(PRESETS[hovered])) {
			// the card cut the description short: show all of it
			g.setTooltipForNextFrame(this.font, this.font.split(Component.literal(PRESETS[hovered].description()), 200), mouseX, mouseY);
		}
	}

	private void drawCard(GuiGraphics g, int index, int y0, int w, boolean hover) {
		HudPreset p = PRESETS[index];
		KwamsterConfig look = this.previews.get(p);
		boolean chosen = index == this.selected;
		int x0 = this.listX;
		int x1 = x0 + w;
		int y1 = y0 + this.cardH;
		Draw.roundRect(g, x0, y0, x1, y1, chosen ? CARD_BG_SELECTED : hover ? CARD_BG_HOVER : CARD_BG);
		Draw.border(g, x0, y0, x1, y1, chosen ? ACCENT : hover ? BORDER_HOVER : BORDER);
		if (!chosen) {
			Draw.topHighlight(g, x0, y0, x1, Draw.TOP_HIGHLIGHT);
		}
		drawChips(g, look, x0 + CARD_PAD + 1, y0 + (this.cardH - (2 * CHIP + 1)) / 2);

		int tx = x0 + textOffset();
		int right = x1 - CARD_PAD;
		int nameRight = right;
		if (p == this.current) {
			int bw = this.font.width(BADGE) - 1 + 6;
			int bx = right - bw;
			Draw.roundRect(g, bx, y0 + 3, right, y0 + 14, ACCENT);
			Draw.text(g, this.font, BADGE, bx + 3, y0 + 5, WHITE, false);
			nameRight = bx - 4;
		}
		String name = StatFormat.truncate(p.displayName(), Math.max(0, nameRight - tx), this::boldWidth);
		Draw.text(g, this.font, Component.literal(name).withStyle(ChatFormatting.BOLD), tx, y0 + 4, VALUE, false);
		int ly = y0 + 14;
		for (String line : this.descriptionLines.getOrDefault(p, List.of())) {
			Draw.text(g, this.font, line, tx, ly, LABEL, false);
			ly += this.font.lineHeight;
		}
	}

	/** Wraps every description to the card's text width: two lines on tall cards, one on short ones. */
	private void wrapDescriptions() {
		this.descriptionLines.clear();
		this.clipped.clear();
		int width = Math.max(0, cardWidth() - textOffset() - CARD_PAD);
		int maxLines = this.cardH == CARD_H_TALL ? 2 : 1;
		for (HudPreset p : PRESETS) {
			List<String> lines = wrap(p.description(), width, maxLines);
			this.descriptionLines.put(p, lines);
			if (!String.join(" ", lines).equals(p.description())) {
				this.clipped.add(p);
			}
		}
	}

	/** Greedy word wrap into at most {@code maxLines} lines; the last line takes the rest, cut with "..." if needed. */
	private List<String> wrap(String text, int maxPx, int maxLines) {
		List<String> lines = new ArrayList<>(maxLines);
		String[] words = text.split(" ");
		int i = 0;
		while (i < words.length && lines.size() < maxLines - 1) {
			StringBuilder line = new StringBuilder(words[i++]);
			while (i < words.length && this.font.width(line + " " + words[i]) <= maxPx) {
				line.append(' ').append(words[i++]);
			}
			lines.add(StatFormat.truncate(line.toString(), maxPx, this.font::width));
		}
		if (i < words.length) {
			lines.add(StatFormat.truncate(String.join(" ", Arrays.copyOfRange(words, i, words.length)), maxPx, this.font::width));
		}
		return lines;
	}

	/** Accent and values on top; the panel (over a patch of sky, with its border) and the crosshair below. */
	private static void drawChips(GuiGraphics g, KwamsterConfig look, int x, int y) {
		int step = CHIP + 1;
		Draw.roundRect(g, x - 1, y - 1, x + 2 * CHIP + 2, y + 2 * CHIP + 2, CHIP_FRAME);
		Draw.fill(g, x, y, x + CHIP, y + CHIP, look.hud.accentArgb | 0xFF000000);
		Draw.fill(g, x + step, y, x + step + CHIP, y + CHIP, look.hud.textArgb | 0xFF000000);
		Draw.fill(g, x, y + step, x + CHIP, y + step + CHIP, SKY_HORIZON);
		Draw.fill(g, x, y + step, x + CHIP, y + step + CHIP, look.hud.panelBgArgb);
		Draw.outline(g, x, y + step, x + CHIP, y + step + CHIP, look.hud.borderArgb);
		Draw.fill(g, x + step, y + step, x + step + CHIP, y + step + CHIP, look.crosshair.defaultArgb | 0xFF000000);
	}

	private void drawScrollbar(GuiGraphics g, int mouseX, int mouseY) {
		int x = scrollbarX();
		Draw.fill(g, x, this.listY, x + SCROLLBAR_W, this.listY + this.listH, SCROLL_TRACK);
		int[] t = thumb();
		boolean hot = this.draggingScrollbar || inside(mouseX, mouseY, x - 2, t[0], x + SCROLLBAR_W + 2, t[1]);
		Draw.fill(g, x, t[0], x + SCROLLBAR_W, t[1], hot ? SCROLL_THUMB_HOT : SCROLL_THUMB);
	}

	// ---- the preview ---------------------------------------------------------------------------------

	private void drawPreview(GuiGraphics g) {
		KwamsterConfig cfg = this.previews.get(selectedPreset());
		int x0 = this.viewX;
		int y0 = this.viewY;
		int x1 = x0 + this.viewW;
		int y1 = y0 + this.viewH;
		Draw.shadow(g, x0 - 1, y1 + 1, x1 + 1, 1f);
		Draw.border(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, FRAME);
		g.enableScissor(x0, y0, x1, y1);
		try {
			// The HUD elements read Kwamster.config() while they measure and draw: point it at the preview.
			Kwamster.withPreviewConfig(cfg, () -> drawScene(g, cfg));
		} finally {
			g.disableScissor();
		}
	}

	private void drawScene(GuiGraphics g, KwamsterConfig cfg) {
		long now = Clock.nanos();
		HudManager hud = HudManager.get();
		int s = clamp(this.viewH / 5, 20, 48); // GUI px per model unit: the opponent is 2s tall
		double block = MODEL_PER_BLOCK * s;
		int ccy = this.viewY + this.viewH / 2;
		// the crosshair sits on the opponent's chest; the horizon is at eye height, as in first person
		int feetY = ccy + (int) Math.round(CHEST * block);
		drawBackdrop(g, feetY - (int) Math.round(EYES * block));

		if (this.afterFight) {
			drawCrosshair(g, cfg.crosshair, cfg.crosshair.defaultArgb, this.viewX + this.viewW / 2, ccy);
			HudElement card = hud.element(HudElementId.SUMMARY_CARD);
			int w = card.width(true);
			int x = Math.max(this.viewX + 2, this.viewX + (this.viewW - w) / 2);
			drawElement(g, card, new HudLayoutMath.Rect(x, this.viewY + 8, w, card.height(true)), now, card.enabled(cfg));
			return;
		}

		HudElement panel = hud.element(HudElementId.STATS_PANEL);
		HudElement indicator = hud.element(HudElementId.HIT_INDICATOR);
		HudElement combo = hud.element(HudElementId.COMBO_COUNTER);
		HudLayoutMath.Rect panelRect = new HudLayoutMath.Rect(this.viewX + INSET, this.viewY + INSET, panel.width(true), panel.height(true));

		// the opponent, crosshair, indicator and combo share the room right of the panel
		boolean showIndicator = indicator.enabled(cfg);
		boolean showCombo = combo.enabled(cfg);
		int indicatorHalf = showIndicator ? (indicator.width(true) + 1) / 2 : 0;
		int figureHalf = (int) Math.ceil(0.8 * s);
		int needLeft = Math.max(indicatorHalf, figureHalf);
		int needRight = Math.max(Math.max(indicatorHalf, figureHalf), showCombo ? 46 : 0);
		int freeLeft = panelRect.right() + 6;
		int freeRight = this.viewX + this.viewW - 6;
		int ccx = (freeLeft + freeRight) / 2;
		if (freeRight - freeLeft >= needLeft + needRight) {
			ccx = clamp(ccx, freeLeft + needLeft, freeRight - needRight);
		}

		drawOpponent(g, cfg.direction, ccx, feetY, s);
		drawCrosshair(g, cfg.crosshair, cfg.crosshair.onPlayerArgb, ccx, ccy);
		drawElement(g, panel, panelRect, now, panel.enabled(cfg));
		if (showIndicator) {
			drawElement(g, indicator, clusterRect(indicator, cfg, ccx, ccy), now, true);
		}
		if (showCombo) {
			drawElement(g, combo, clusterRect(combo, cfg, ccx, ccy), now, true);
		}
	}

	/**
	 * Sky to the horizon, two blocky clouds, then grass that darkens toward the camera. The clouds stay
	 * clear of the panel (top left), the card (top centre) and the opponent, so no glass shows a cloud
	 * through it and reads as a smudge.
	 */
	private void drawBackdrop(GuiGraphics g, int horizon) {
		int x0 = this.viewX;
		int y0 = this.viewY;
		int x1 = x0 + this.viewW;
		int y1 = y0 + this.viewH;
		int h = clamp(horizon, y0 + 1, y1 - 1);
		g.fillGradient(x0, y0, x1, h, SKY_TOP, SKY_HORIZON);
		cloud(g, x0 + this.viewW * 21 / 25, y0 + (h - y0) * 4 / 25, Math.max(24, this.viewW / 8));
		cloud(g, x0 + this.viewW * 2 / 5, y0 + (h - y0) * 4 / 5, Math.max(18, this.viewW / 10));
		g.fillGradient(x0, h, x1, y1, GROUND_FAR, GROUND_NEAR);
		Draw.fill(g, x0, h, x1, h + 1, HORIZON_LINE);
	}

	private static void cloud(GuiGraphics g, int x, int y, int w) {
		int h = Math.max(3, w / 6);
		Draw.fill(g, x, y, x + w, y + h, CLOUD);
		Draw.fill(g, x + w / 5, y - h + 1, x + w * 3 / 4, y, CLOUD);
	}

	/**
	 * The opponent: a default-skin player model (the picture-in-picture renderer of vanilla's skin
	 * preview), its direction bar in the "moving forward" colour above the head (§5.11a geometry) and
	 * vanilla's name tag above that. A flat silhouette stands in if the model cannot be baked.
	 */
	private void drawOpponent(GuiGraphics g, KwamsterConfig.Direction d, int cx, int feetY, int s) {
		PlayerModel model = figureModel();
		Identifier skin = this.figureSkin;
		if (model != null && skin != null) {
			// GuiSkinRenderer puts the feet 0.101 units above the bottom edge and centres the model in x
			int half = (int) Math.ceil(0.8 * s);
			int y1 = feetY + Math.round(0.101f * s);
			int y0 = y1 - (int) Math.ceil(2.3 * s);
			g.submitSkinRenderState(model, skin, s, 0f, FIGURE_YAW, -1.0625f, cx - half, y0, cx + half, y1);
		} else {
			drawSilhouette(g, cx, feetY, s);
		}

		double block = MODEL_PER_BLOCK * s;
		int barY = feetY - (int) Math.round((HITBOX + d.headClearance / 100.0) * block);
		int hw = Math.max(1, (int) Math.round(d.barWidth / 200.0 * block));
		int hh = Math.max(1, (int) Math.round(d.barHeight / 200.0 * block));
		int b = Math.max(1, (int) Math.round(BAR_BORDER * block));
		float alpha = d.headBarEnabled ? 1f : GHOST_ALPHA;
		Draw.fill(g, cx - hw - b, barY - hh - b, cx + hw + b, barY + hh + b, Draw.mul(d.backdropArgb, alpha));
		Draw.fill(g, cx - hw, barY - hh, cx + hw, barY + hh, Draw.mul(d.forwardArgb, alpha));

		String name = PreviewStrings.SAMPLE_NAME;
		int w = this.font.width(name);
		int tagTop = Math.min(feetY - (int) Math.round((HITBOX + NAMETAG) * block) - 8, barY - hh - b - 11);
		int x = cx - w / 2;
		Draw.fill(g, x - 1, tagTop - 1, x + w, tagTop + 8, NAMETAG_BG);
		Draw.text(g, this.font, name, x, tagTop, WHITE, false);
	}

	/** Head, body, arms and legs in quarter units (a player model is 8 quarters tall). */
	private static void drawSilhouette(GuiGraphics g, int cx, int feetY, int s) {
		int u = Math.max(1, s / 4);
		int head = feetY - 8 * u;
		int neck = feetY - 6 * u;
		int hip = feetY - 3 * u;
		Draw.fill(g, cx - u, head, cx + u, neck, SILHOUETTE);
		Draw.fill(g, cx - 2 * u, neck, cx + 2 * u, hip, SILHOUETTE);
		Draw.fill(g, cx - u, hip, cx + u, feetY, SILHOUETTE);
	}

	private @Nullable PlayerModel figureModel() {
		if (this.figure == null && !this.figureFailed) {
			try {
				PlayerSkin skin = DefaultPlayerSkin.getDefaultSkin();
				boolean slim = skin.model() == PlayerModelType.SLIM;
				this.figure = new PlayerModel(this.minecraft.getEntityModels().bakeLayer(slim ? ModelLayers.PLAYER_SLIM : ModelLayers.PLAYER), slim);
				this.figureSkin = skin.body().texturePath();
			} catch (Throwable t) {
				this.figureFailed = true;
				LogLimiter.error("HudPresetsScreen.figure", "Could not bake the preview player model; drawing a silhouette", t);
			}
		}
		return this.figure;
	}

	/**
	 * The crosshair at the GUI point {@code (ccx, ccy)}, drawn in physical pixels exactly as CROSSHAIR draws
	 * it (§7.8): sizes times {@code max(1, round(guiScale / 2))} when {@code scaleWithGui} is on, outline
	 * first, then the fill. A ghost while the custom crosshair is switched off.
	 */
	private void drawCrosshair(GuiGraphics g, KwamsterConfig.Crosshair c, int colour, int ccx, int ccy) {
		int gs = Math.max(1, this.minecraft.getWindow().getGuiScale());
		int k = c.scaleWithGui ? Math.max(1, Math.round(gs / 2f)) : 1;
		CrosshairSpans sp = crosshairSpans(new CrosshairKey(c.shape == null ? CrosshairShape.CROSS : c.shape,
				c.size * k, c.gap * k, c.thickness * k, c.centerDot * k, c.outline * k));
		float alpha = c.enabled ? 1f : GHOST_ALPHA;
		int outline = Draw.mul(c.outlineArgb, alpha);
		int fill = Draw.mul(colour, alpha);
		int cx = ccx * gs;
		int cy = ccy * gs;
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.scale(1f / gs, 1f / gs);
			for (int[] r : sp.outline()) {
				Draw.fill(g, cx + r[0], cy + r[1], cx + r[2], cy + r[3], outline);
			}
			for (int[] r : sp.fill()) {
				Draw.fill(g, cx + r[0], cy + r[1], cx + r[2], cy + r[3], fill);
			}
		} finally {
			pose.popMatrix();
		}
	}

	private CrosshairSpans crosshairSpans(CrosshairKey key) {
		CrosshairSpans sp = this.spans;
		if (sp == null || !key.equals(this.spansKey)) {
			sp = CrosshairGeometry.build(key);
			this.spans = sp;
			this.spansKey = key;
		}
		return sp;
	}

	/** An element at 1× with its preview data; a ghost with the "(off in settings)" tag when switched off. */
	private void drawElement(GuiGraphics g, HudElement e, HudLayoutMath.Rect r, long now, boolean enabled) {
		try {
			HudManager.drawElement(g, e, r, 1f, now, enabled ? 1f : GHOST_ALPHA, true);
			if (!enabled) {
				String tag = PreviewStrings.OFF_TAG;
				int w = this.font.width(tag) + 3;
				int x = r.x() + (r.w() - w) / 2;
				int y = r.y() + (r.h() - 9) / 2;
				Draw.fill(g, x, y, x + w, y + 9, TAG_BG);
				Draw.text(g, this.font, tag, x + 2, y + 1, Draw.DIM, false);
			}
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen/" + e.id(), "Could not draw the " + e.id() + " preview", t);
		}
	}

	/**
	 * Where a crosshair element sits around {@code (ccx, ccy)}: its own offset when it is anchored to the
	 * centre (so a moved indicator previews where it is), else its default offset; kept inside the view.
	 */
	private HudLayoutMath.Rect clusterRect(HudElement e, KwamsterConfig cfg, int ccx, int ccy) {
		int w = e.width(true);
		int h = e.height(true);
		HudLayout l = cfg.layout(e.id());
		HudLayout offsets = l.anchor == Anchor.CENTER ? l : HudLayout.defaultFor(e.id());
		int x = (int) Math.round(ccx + offsets.offsetX - w / 2.0);
		int y = (int) Math.round(ccy + offsets.offsetY - h / 2.0);
		x = clamp(x, this.viewX + 2, Math.max(this.viewX + 2, this.viewX + this.viewW - 2 - w));
		y = clamp(y, this.viewY + 2, Math.max(this.viewY + 2, this.viewY + this.viewH - 2 - h));
		return new HudLayoutMath.Rect(x, y, w, h);
	}

	// ---- legend and hint ---------------------------------------------------------------------------

	/** All four crosshair state colours and the three bar colours, each named in a tooltip. */
	private void drawLegend(GuiGraphics g, int mouseX, int mouseY) {
		KwamsterConfig cfg = this.previews.get(selectedPreset());
		KwamsterConfig.Crosshair c = cfg.crosshair;
		KwamsterConfig.Direction d = cfg.direction;
		int y = this.viewY + this.viewH + LEGEND_GAP;
		int right = this.viewX + this.viewW;
		int x = this.viewX + 1;

		String crossLabel = c.enabled ? "Crosshair" : "Crosshair (off)";
		x += Draw.text(g, this.font, crossLabel, x, y + 2, LABEL, false) + 3;
		int frame = c.outline > 0 && (c.outlineArgb >>> 24) >= 0x40 ? c.outlineArgb | 0xFF000000 : CHIP_FRAME;
		int[] states = {c.defaultArgb, c.onPlayerArgb, c.critReadyArgb, c.critOnPlayerArgb};
		for (int i = 0; i < states.length; i++) {
			if (x + 9 > right) {
				return;
			}
			Draw.outline(g, x, y + 1, x + 9, y + 10, frame);
			Draw.fill(g, x + 1, y + 2, x + 8, y + 9, states[i]);
			if (inside(mouseX, mouseY, x, y, x + 9, y + LEGEND_H)) {
				g.setTooltipForNextFrame(this.font, Component.literal(STATE_NAMES[i]), mouseX, mouseY);
			}
			x += 11;
		}

		x += 7;
		String barLabel = d.headBarEnabled ? "Bars" : "Bars (off)";
		if (x + this.font.width(barLabel) + 3 + 15 > right) {
			return;
		}
		x += Draw.text(g, this.font, barLabel, x, y + 2, LABEL, false) + 3;
		int[] bars = {d.forwardArgb, d.idleArgb, d.backwardArgb};
		for (int i = 0; i < bars.length; i++) {
			if (x + 15 > right) {
				return;
			}
			Draw.fill(g, x, y + 3, x + 15, y + 9, d.backdropArgb);
			Draw.fill(g, x + 1, y + 4, x + 14, y + 8, bars[i]);
			if (inside(mouseX, mouseY, x, y, x + 15, y + LEGEND_H)) {
				g.setTooltipForNextFrame(this.font, Component.literal(BAR_NAMES[i]), mouseX, mouseY);
			}
			x += 18;
		}
	}

	private void drawHint(GuiGraphics g) {
		int left = this.listX + Math.min(this.listW, BACK_W) + 8;
		int right = this.applyX - 8;
		int w = this.font.width(HINT);
		if (right - left >= w) {
			int y = this.height - BUTTON_BOTTOM + (BUTTON_H - 8) / 2;
			Draw.text(g, this.font, HINT, left + (right - left - w) / 2, y, Draw.DIM, true);
		}
	}

	// ---- input -------------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		try {
			return click(event.x(), event.y(), event.button(), doubleClick);
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen.mouseClicked", "Click handling failed", t);
			return false;
		}
	}

	private boolean click(double mx, double my, int button, boolean doubleClick) {
		if (button != GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			return false;
		}
		int split = this.tabX + this.tabW0;
		if (inside(mx, my, this.tabX, this.tabY, split + this.tabW1, this.tabY + TAB_H)) {
			this.afterFight = mx >= split;
			return true;
		}
		if (scrollable() && inside(mx, my, scrollbarX() - 2, this.listY, this.listX + this.listW + 1, this.listY + this.listH)) {
			int[] t = thumb();
			this.draggingScrollbar = true;
			this.dragGrab = my >= t[0] && my < t[1] ? my - t[0] : (t[1] - t[0]) / 2.0;
			dragScrollbar(my);
			return true;
		}
		int i = cardAt(mx, my);
		if (i < 0) {
			return false;
		}
		// the double-click flag only says two clicks came quickly: both must be on this card
		boolean again = doubleClick && i == this.lastClicked;
		select(i);
		this.lastClicked = i;
		setFocused(null);
		if (again && canApply()) {
			apply();
		}
		return true;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.draggingScrollbar) {
			try {
				dragScrollbar(event.y());
			} catch (Throwable t) {
				LogLimiter.error("HudPresetsScreen.mouseDragged", "Scrollbar drag failed", t);
			}
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.draggingScrollbar && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			this.draggingScrollbar = false;
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (scrollY != 0 && inside(mouseX, mouseY, this.listX, this.listY, this.listX + this.listW, this.listY + this.listH)) {
			this.scroll = clampScroll(this.scroll - (int) Math.round(scrollY * SCROLL_STEP));
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		try {
			if (event.isUp() || event.isDown()) {
				select(this.selected + (event.isDown() ? 1 : -1));
				return true;
			}
			if (event.isLeft() || event.isRight()) {
				this.afterFight = event.isRight();
				return true;
			}
			if (event.key() == GLFW.GLFW_KEY_HOME || event.key() == GLFW.GLFW_KEY_END) {
				select(event.key() == GLFW.GLFW_KEY_HOME ? 0 : PRESETS.length - 1);
				return true;
			}
			// Enter applies, unless the player tabbed to a button (then the button takes it)
			if (event.isConfirmation() && getFocused() == null) {
				if (canApply()) {
					apply();
				}
				return true;
			}
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen.keyPressed", "Key handling failed", t);
		}
		return super.keyPressed(event);
	}

	// ---- list geometry -------------------------------------------------------------------------------

	private int contentHeight() {
		return PRESETS.length * (this.cardH + CARD_GAP) - CARD_GAP;
	}

	private boolean scrollable() {
		return contentHeight() > this.listH;
	}

	private int maxScroll() {
		return Math.max(0, contentHeight() - this.listH);
	}

	private int clampScroll(int value) {
		return clamp(value, 0, maxScroll());
	}

	private int cardWidth() {
		return this.listW - (scrollable() ? SCROLLBAR_W + 3 : 0);
	}

	/** Left edge of a card's text, after the chips. */
	private static int textOffset() {
		return CARD_PAD + 1 + 2 * CHIP + 1 + CARD_PAD + 1;
	}

	private int cardY(int index) {
		return this.listY + index * (this.cardH + CARD_GAP) - this.scroll;
	}

	private int cardAt(double mx, double my) {
		if (!inside(mx, my, this.listX, this.listY, this.listX + cardWidth(), this.listY + this.listH)) {
			return -1;
		}
		int pitch = this.cardH + CARD_GAP;
		double rel = my - this.listY + this.scroll;
		int i = (int) Math.floor(rel / pitch);
		if (i < 0 || i >= PRESETS.length || rel - (double) i * pitch >= this.cardH) {
			return -1;
		}
		return i;
	}

	private void ensureVisible(int index) {
		int top = index * (this.cardH + CARD_GAP);
		int bottom = top + this.cardH;
		int s = this.scroll;
		if (top < s) {
			s = top;
		} else if (bottom > s + this.listH) {
			s = bottom - this.listH;
		}
		this.scroll = clampScroll(s);
	}

	private int scrollbarX() {
		return this.listX + this.listW - SCROLLBAR_W;
	}

	/** The scrollbar thumb's top and bottom. */
	private int[] thumb() {
		int content = contentHeight();
		int h = clamp(Math.round(this.listH * (float) this.listH / Math.max(1, content)), 12, this.listH);
		int max = maxScroll();
		int top = this.listY + (max <= 0 ? 0 : (int) Math.round((double) this.scroll * (this.listH - h) / max));
		return new int[] {top, top + h};
	}

	private void dragScrollbar(double mouseY) {
		int[] t = thumb();
		int range = this.listH - (t[1] - t[0]);
		if (range <= 0) {
			return;
		}
		double frac = (mouseY - this.dragGrab - this.listY) / range;
		this.scroll = clampScroll((int) Math.round(frac * maxScroll()));
	}

	// ---- helpers ---------------------------------------------------------------------------------------

	private int boldWidth(String s) {
		return this.font.width(Component.literal(s).withStyle(ChatFormatting.BOLD));
	}

	private static boolean inside(double x, double y, int x0, int y0, int x1, int y1) {
		return x >= x0 && x < x1 && y >= y0 && y < y1;
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	/** The live config with {@code preset} applied, on a deep copy: the preview never touches the live one. */
	private static KwamsterConfig previewConfig(KwamsterConfig live, HudPreset preset) {
		KwamsterConfig copy;
		try {
			copy = Json.GSON.fromJson(Json.GSON.toJson(live), KwamsterConfig.class);
			copy.clampAndFill();
		} catch (Throwable t) {
			LogLimiter.error("HudPresetsScreen.copy", "Could not copy the config for the preset preview; previewing on the defaults", t);
			copy = KwamsterConfig.defaults();
		}
		preset.apply(copy);
		return copy;
	}

	/**
	 * The screen the settings screen was opened from (the game, a pause menu, Mod Menu). Cloth's config
	 * screen keeps it in a {@code parent} field; CONFIGUI's and Cloth's types are not imported here (R1),
	 * so it is read by name. Null, meaning back to the game, when there is no such field.
	 */
	private static @Nullable Screen outerScreen(@Nullable Screen screen) {
		for (Class<?> c = screen == null ? null : screen.getClass(); c != null && c != Screen.class; c = c.getSuperclass()) {
			Field f;
			try {
				f = c.getDeclaredField("parent");
			} catch (NoSuchFieldException e) {
				continue;
			}
			if (Modifier.isStatic(f.getModifiers()) || !Screen.class.isAssignableFrom(f.getType())) {
				continue;
			}
			try {
				f.setAccessible(true);
				Object value = f.get(screen);
				return value instanceof Screen s && s != screen ? s : null;
			} catch (ReflectiveOperationException | RuntimeException e) {
				LogLimiter.warn("HudPresetsScreen.outerScreen", "Could not read the settings screen's parent: " + e);
				return null;
			}
		}
		return null;
	}

	// ---- crosshair geometry --------------------------------------------------------------------------

	/** Everything the crosshair rects depend on, already in physical pixels. */
	private record CrosshairKey(CrosshairShape shape, int size, int gap, int thickness, int centerDot, int outline) {
	}

	/** Outline and fill rects {@code {x0, y0, x1, y1}} around the pixel-edge centre (0, 0), in physical pixels. */
	private record CrosshairSpans(List<int[]> outline, List<int[]> fill) {
	}

	/**
	 * The crosshair geometry of §7.8, mirrored from CROSSHAIR's {@code CrosshairShapes} (R1 forbids importing
	 * that unit) so the preview matches the game pixel for pixel: the shape and centre dot as a fill mask,
	 * the outline as the fill grown by {@code outline} pixels (Chebyshev) minus the fill, both emitted as
	 * row runs merged down the rows. The centre is the pixel edge (0, 0).
	 */
	private static final class CrosshairGeometry {
		/** Keeps a corrupt value from allocating a huge mask. */
		private static final int MAX_PARAM = 256;

		private CrosshairGeometry() {
		}

		static CrosshairSpans build(CrosshairKey key) {
			int sz = clamp(key.size(), 0, MAX_PARAM);
			int gp = clamp(key.gap(), 0, MAX_PARAM);
			int t = clamp(key.thickness(), 1, MAX_PARAM);
			int dot = clamp(key.centerDot(), 0, MAX_PARAM);
			int o = clamp(key.outline(), 0, MAX_PARAM);
			CrosshairShape shape = key.shape();

			int extent = switch (shape) {
				case CROSS, T, X -> gp + sz + t;
				case CIRCLE -> sz + 1;
				case DOT -> sz / 2 + 1;
			};
			extent = Math.max(extent, t);
			if (dot > 0) {
				extent = Math.max(extent, dot / 2 + 1);
			}
			int r = extent + o + 1;
			int n = 2 * r;
			boolean[] fill = new boolean[n * n];

			int left = -(t / 2);
			int top = -(t / 2);
			int right = left + t;
			int bottom = top + t;
			switch (shape) {
				case CROSS, T -> {
					rect(fill, n, r, left - gp - sz, top, left - gp, bottom);
					rect(fill, n, r, right + gp, top, right + gp + sz, bottom);
					if (shape == CrosshairShape.CROSS) {
						rect(fill, n, r, left, top - gp - sz, right, top - gp);
					}
					rect(fill, n, r, left, bottom + gp, right, bottom + gp + sz);
					if (gp == 0) {
						rect(fill, n, r, left, top, right, bottom);
					}
				}
				case X -> {
					for (int k = gp; k < gp + sz; k++) {
						for (int sx = -1; sx <= 1; sx += 2) {
							int x0 = sx > 0 ? k : -k - t;
							for (int sy = -1; sy <= 1; sy += 2) {
								int y0 = sy > 0 ? k : -k - t;
								rect(fill, n, r, x0, y0, x0 + t, y0 + t);
							}
						}
					}
					if (gp == 0) {
						rect(fill, n, r, left, top, right, bottom);
					}
				}
				case CIRCLE -> {
					long inner = Math.max(0, sz - t);
					disc(fill, n, r, 4L * inner * inner, 4L * sz * sz);
				}
				case DOT -> {
					disc(fill, n, r, 0L, (long) sz * sz);
					if (sz == 1) {
						rect(fill, n, r, 0, 0, 1, 1);
					}
				}
			}
			if (dot > 0) {
				int d0 = -(dot / 2);
				rect(fill, n, r, d0, d0, d0 + dot, d0 + dot);
			}

			List<int[]> ring = List.of();
			if (o > 0) {
				boolean[] grown = dilate(fill, n, o);
				for (int i = 0; i < grown.length; i++) {
					if (fill[i]) {
						grown[i] = false;
					}
				}
				ring = spans(grown, n, r);
			}
			return new CrosshairSpans(ring, spans(fill, n, r));
		}

		/** Sets the pixels of {@code [x0, x1) × [y0, y1)} (centre-relative), clipped to the mask. */
		private static void rect(boolean[] m, int n, int r, int x0, int y0, int x1, int y1) {
			int a = Math.max(x0 + r, 0);
			int b = Math.min(x1 + r, n);
			int c = Math.max(y0 + r, 0);
			int d = Math.min(y1 + r, n);
			for (int y = c; y < d; y++) {
				for (int x = a; x < b; x++) {
					m[y * n + x] = true;
				}
			}
		}

		/** Sets the pixels whose centre lies at a distance d from (0, 0) with {@code min4 <= 4·d² < max4}. */
		private static void disc(boolean[] m, int n, int r, long min4, long max4) {
			for (int y = 0; y < n; y++) {
				long dy = 2L * (y - r) + 1;
				for (int x = 0; x < n; x++) {
					long dx = 2L * (x - r) + 1;
					long d4 = dx * dx + dy * dy;
					if (d4 >= min4 && d4 < max4) {
						m[y * n + x] = true;
					}
				}
			}
		}

		/** The mask grown by an {@code o}-pixel square, as two separable sliding-window passes. */
		private static boolean[] dilate(boolean[] m, int n, int o) {
			boolean[] horizontal = new boolean[n * n];
			for (int y = 0; y < n; y++) {
				int row = y * n;
				int count = 0;
				for (int x = 0; x < Math.min(o, n); x++) {
					if (m[row + x]) {
						count++;
					}
				}
				for (int x = 0; x < n; x++) {
					int enter = x + o;
					if (enter < n && m[row + enter]) {
						count++;
					}
					int leave = x - o - 1;
					if (leave >= 0 && m[row + leave]) {
						count--;
					}
					horizontal[row + x] = count > 0;
				}
			}
			boolean[] out = new boolean[n * n];
			for (int x = 0; x < n; x++) {
				int count = 0;
				for (int y = 0; y < Math.min(o, n); y++) {
					if (horizontal[y * n + x]) {
						count++;
					}
				}
				for (int y = 0; y < n; y++) {
					int enter = y + o;
					if (enter < n && horizontal[enter * n + x]) {
						count++;
					}
					int leave = y - o - 1;
					if (leave >= 0 && horizontal[leave * n + x]) {
						count--;
					}
					out[y * n + x] = count > 0;
				}
			}
			return out;
		}

		/** Maximal row runs, with runs repeated unchanged on the next row merged into one rect. */
		private static List<int[]> spans(boolean[] m, int n, int r) {
			List<int[]> closed = new ArrayList<>();
			List<int[]> open = new ArrayList<>();
			for (int y = 0; y < n; y++) {
				List<int[]> next = new ArrayList<>();
				int row = y * n;
				int x = 0;
				while (x < n) {
					if (!m[row + x]) {
						x++;
						continue;
					}
					int start = x;
					while (x < n && m[row + x]) {
						x++;
					}
					int x0 = start - r;
					int x1 = x - r;
					int[] extended = null;
					for (int[] rect : open) {
						if (rect[0] == x0 && rect[2] == x1) {
							extended = rect;
							break;
						}
					}
					if (extended != null) {
						extended[3] = y - r + 1;
						open.remove(extended);
						next.add(extended);
					} else {
						next.add(new int[] {x0, y - r, x1, y - r + 1});
					}
				}
				closed.addAll(open);
				open = next;
			}
			closed.addAll(open);
			return closed;
		}
	}
}
