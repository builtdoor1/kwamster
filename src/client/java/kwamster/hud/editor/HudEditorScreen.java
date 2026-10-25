package kwamster.hud.editor;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.stats.StatFormat;
import kwamster.hud.Anim;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudManager;
import kwamster.hud.PreviewStrings;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The drag-to-place HUD editor (§7.3). Every element is drawn from {@code HudPreviewData}; disabled
 * ones are ghosts at 35 % alpha with an "(off in settings)" tag.
 *
 * <ul>
 *   <li><b>Mouse.</b> Left-drag moves (snapping to margins, the centre line and other elements unless
 *       Shift is held, with accent guide lines while snapped); release re-anchors to the nearest third of
 *       the screen without moving the element. Scrolling over an element scales it by {@code 1/guiScale}
 *       per step (crisp pixel ratios), or 0.025 with Shift, about its centre. Right-click resets it.</li>
 *   <li><b>Keys.</b> Arrows nudge the selection 1 px (10 with Shift), R resets it, G toggles the grid,
 *       Tab cycles the selection.</li>
 *   <li><b>Saving.</b> The layouts are snapshotted on open. Esc or Done saves the config and returns to
 *       the parent; Cancel restores the snapshot without saving. "Reset all" needs a second click.</li>
 * </ul>
 *
 * The editor writes only {@code KwamsterConfig.layouts}, never a field Cloth edits (§3.10). Client thread.
 */
public final class HudEditorScreen extends Screen {
	private static final int BACKGROUND = 0x50000000;
	private static final int GRID = 0x0FFFFFFF;
	private static final int CENTRE_LINES = 0x22FFFFFF;
	private static final int GRID_STEP = 16;
	private static final int OUTLINE_IDLE = 0x55FFFFFF;
	private static final int OUTLINE_HOVER = 0xCCFFFFFF;
	private static final int TAG_BG = 0xCC000000;
	private static final int TAG_TEXT = 0xFFF1F3F5;
	private static final int TAG_H = 9;
	/** The help pill: 11 px tall like the hit indicator (2 px above and below the caps), 4 px side padding. */
	private static final int HELP_H = 11;
	private static final int HELP_TEXT_Y = 2;
	private static final int HELP_PAD_X = 4;
	/** Gap between the help pill and the screen's top or side edge. */
	private static final int HELP_MARGIN = 2;
	/** Label-coloured on the dark pill: dim text would be too faint on a backdrop. */
	private static final int HELP_TEXT = 0xFF9AA4B2;
	private static final int GUIDE_ALPHA = 0xAA;
	private static final float GHOST_ALPHA = 0.35f;
	private static final long CONFIRM_NANOS = 3_000L * Anim.MS;
	private static final int BUTTON_W = 90;
	private static final int BUTTON_H = 20;
	private static final int BUTTON_GAP = 4;
	private static final int BUTTON_BOTTOM = 28;
	/** Extra pixels around an element that still grab it (the stoplight is only 10 px wide). */
	private static final int HIT_SLOP = 2;
	private static final int NONE = Integer.MIN_VALUE;
	private static final Component RESET_ALL = Component.literal("Reset all");
	private static final Component RESET_CONFIRM = Component.literal("Click to confirm");

	private final @Nullable Screen parent;
	private final Map<String, HudLayout> snapshot;
	private @Nullable HudElementId selected;
	private @Nullable HudElementId dragging;
	private double grabX;
	private double grabY;
	private int guideX = NONE;
	private int guideY = NONE;
	private boolean grid = true;
	private @Nullable Button resetAllButton;
	private long resetArmedAt = Long.MIN_VALUE;

	/**
	 * @param parent the screen to return to (null: back to the game)
	 * @param arg    an optional {@code HudElementId.name()} to preselect; anything else is ignored
	 */
	public HudEditorScreen(@Nullable Screen parent, @Nullable String arg) {
		super(Component.literal("Edit HUD layout"));
		this.parent = parent;
		this.snapshot = copyLayouts(Kwamster.config());
		this.selected = parseId(arg);
	}

	// ---- lifecycle ---------------------------------------------------------------------

	@Override
	protected void init() {
		int bw = Math.min(BUTTON_W, Math.max(40, (this.width - 16 - 2 * BUTTON_GAP) / 3));
		int total = 3 * bw + 2 * BUTTON_GAP;
		int x0 = (this.width - total) / 2;
		int y = this.height - BUTTON_BOTTOM;
		this.resetAllButton = addRenderableWidget(Button.builder(armed() ? RESET_CONFIRM : RESET_ALL, b -> resetAll())
				.bounds(x0, y, bw, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal("Cancel"), b -> cancel())
				.bounds(x0 + bw + BUTTON_GAP, y, bw, BUTTON_H).build());
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
				.bounds(x0 + 2 * (bw + BUTTON_GAP), y, bw, BUTTON_H).build());
	}

	@Override
	public void tick() {
		if (this.resetArmedAt != Long.MIN_VALUE && !armed()) {
			disarmReset();
		}
	}

	/** Esc and Done: save the layouts and return to the parent. */
	@Override
	public void onClose() {
		this.dragging = null;
		Kwamster.saveConfig();
		this.minecraft.setScreen(this.parent);
	}

	/** Cancel: put the snapshot back without saving and return to the parent. */
	private void cancel() {
		this.dragging = null;
		Kwamster.config().layouts = copyLayouts(this.snapshot);
		this.minecraft.setScreen(this.parent);
	}

	private void resetAll() {
		if (armed()) {
			for (HudElementId id : HudElementId.values()) {
				setLayout(id, HudLayout.defaultFor(id));
			}
			disarmReset();
		} else {
			this.resetArmedAt = Clock.nanos();
			if (this.resetAllButton != null) {
				this.resetAllButton.setMessage(RESET_CONFIRM);
			}
		}
	}

	private boolean armed() {
		return this.resetArmedAt != Long.MIN_VALUE && Clock.nanos() - this.resetArmedAt < CONFIRM_NANOS;
	}

	private void disarmReset() {
		this.resetArmedAt = Long.MIN_VALUE;
		if (this.resetAllButton != null) {
			this.resetAllButton.setMessage(RESET_ALL);
		}
	}

	// ---- rendering ---------------------------------------------------------------------

	/** A light dim with no blur so the world stays visible, a faint 16 px grid and the centre cross. */
	@Override
	public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		if (this.minecraft.level == null) {
			renderPanorama(g, partialTick);
		}
		g.fill(0, 0, this.width, this.height, BACKGROUND);
		int cx = this.width / 2;
		int cy = this.height / 2;
		if (this.grid) {
			for (int x = cx % GRID_STEP; x < this.width; x += GRID_STEP) {
				if (x != cx) {
					g.fill(x, 0, x + 1, this.height, GRID);
				}
			}
			for (int y = cy % GRID_STEP; y < this.height; y += GRID_STEP) {
				if (y != cy) {
					g.fill(0, y, this.width, y + 1, GRID);
				}
			}
		}
		g.fill(cx, 0, cx + 1, this.height, CENTRE_LINES);
		g.fill(0, cy, this.width, cy + 1, CENTRE_LINES);
	}

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		KwamsterConfig cfg = Kwamster.config();
		long now = Clock.nanos();
		List<HudElement> elements = HudManager.get().elements();
		Map<HudElementId, HudLayoutMath.Rect> rects = new EnumMap<>(HudElementId.class);
		Map<HudElementId, HudLayoutMath.Rect> outlines = new EnumMap<>(HudElementId.class);
		for (HudElement e : elements) {
			try {
				HudLayoutMath.Rect r = rect(e.id());
				rects.put(e.id(), r);
				outlines.put(e.id(), outlineRect(e.id(), r));
			} catch (Throwable t) {
				LogLimiter.error("HudEditorScreen/" + e.id(), "Could not lay out " + e.id(), t);
			}
		}
		HudElementId hovered = this.dragging != null ? this.dragging : elementAt(mouseX, mouseY, outlines);

		drawHelpLine(g, outlines.values());

		for (HudElement e : elements) {
			HudLayoutMath.Rect r = rects.get(e.id());
			if (r == null) {
				continue;
			}
			float alpha = e.enabled(cfg) ? 1f : GHOST_ALPHA;
			try {
				HudManager.drawElement(g, e, r, cfg.layout(e.id()).scale, now, alpha, true);
			} catch (Throwable t) {
				LogLimiter.error("HudEditorScreen/" + e.id(), "Could not draw the " + e.id() + " preview", t);
			}
		}

		int accent = cfg.hud.accentArgb | 0xFF000000;
		for (HudElement e : elements) {
			HudLayoutMath.Rect o = outlines.get(e.id());
			if (o == null || e.id() == this.selected) {
				continue;
			}
			Draw.outline(g, o.x() - 1, o.y() - 1, o.right() + 1, o.bottom() + 1, e.id() == hovered ? OUTLINE_HOVER : OUTLINE_IDLE);
		}
		for (HudElement e : elements) {
			HudLayoutMath.Rect o = outlines.get(e.id());
			if (o != null && !e.enabled(cfg)) {
				drawOffTag(g, o);
			}
		}
		if (this.selected != null) {
			HudLayoutMath.Rect o = outlines.get(this.selected);
			if (o != null) {
				Draw.outline(g, o.x() - 1, o.y() - 1, o.right() + 1, o.bottom() + 1, accent);
				drawSelectionTag(g, o, cfg, accent);
			}
		}
		if (this.dragging != null) {
			int guide = Draw.withAlpha(accent, GUIDE_ALPHA);
			if (this.guideX != NONE) {
				g.fill(this.guideX, 0, this.guideX + 1, this.height, guide);
			}
			if (this.guideY != NONE) {
				g.fill(0, this.guideY, this.width, this.guideY + 1, guide);
			}
		}

		super.render(g, mouseX, mouseY, partialTick);
	}

	/**
	 * The help line on its own dark pill, so chat lines, the hotbar or a bright sky behind it never make
	 * it unreadable. At the top centre, or just above the buttons when an element covers the top; when
	 * both are covered, wherever it hides the least of the elements (which are drawn over it).
	 */
	private void drawHelpLine(GuiGraphics g, Iterable<HudLayoutMath.Rect> occupied) {
		String help = StatFormat.truncate(PreviewStrings.HELP_LINE, this.width - 2 * (HELP_PAD_X + HELP_MARGIN), this.font::width);
		// the font's trailing spacing column is not counted, so both paddings look equal
		int pillW = Math.max(0, this.font.width(help) - 1) + 2 * HELP_PAD_X;
		int x = (this.width - pillW) / 2;
		int[] candidates = {HELP_MARGIN, this.height - BUTTON_BOTTOM - BUTTON_GAP - HELP_H};
		HudLayoutMath.Rect pill = null;
		long least = Long.MAX_VALUE;
		for (int y : candidates) {
			HudLayoutMath.Rect area = new HudLayoutMath.Rect(x, y, pillW, HELP_H);
			long covered = coveredArea(area, occupied);
			if (covered < least) {
				least = covered;
				pill = area;
				if (covered == 0) {
					break;
				}
			}
		}
		if (pill == null) {
			return;
		}
		Draw.roundRect(g, pill.x(), pill.y(), pill.right(), pill.bottom(), TAG_BG);
		Draw.text(g, this.font, help, pill.x() + HELP_PAD_X, pill.y() + HELP_TEXT_Y, HELP_TEXT, false);
	}

	/** Pixels of {@code area} that the element outlines (grown by 1 px) cover, overlaps counted twice. */
	private static long coveredArea(HudLayoutMath.Rect area, Iterable<HudLayoutMath.Rect> occupied) {
		long total = 0;
		for (HudLayoutMath.Rect o : occupied) {
			HudLayoutMath.Rect r = o.inflate(1);
			long w = Math.min(area.right(), r.right()) - (long) Math.max(area.x(), r.x());
			long h = Math.min(area.bottom(), r.bottom()) - (long) Math.max(area.y(), r.y());
			if (w > 0 && h > 0) {
				total += w * h;
			}
		}
		return total;
	}

	/** "Stats panel · 150 %" above the selection (below it at the top edge); the scale is accent when crisp. */
	private void drawSelectionTag(GuiGraphics g, HudLayoutMath.Rect o, KwamsterConfig cfg, int accent) {
		float scale = cfg.layout(this.selected).scale;
		String name = displayName(this.selected) + PreviewStrings.SEP;
		String pct = Math.round(scale * 100f) + " %";
		boolean crisp = HudLayoutMath.isCrisp(scale, this.minecraft.getWindow().getGuiScale());
		int w = this.font.width(name) + this.font.width(pct) + 3;
		int y = o.y() - 2 - TAG_H;
		if (y < 0) {
			y = o.bottom() + 2;
		}
		int x = HudLayoutMath.clampPos(o.x() - 1, w, this.width);
		g.fill(x, y, x + w, y + TAG_H, TAG_BG);
		int tx = x + 2 + Draw.text(g, this.font, name, x + 2, y + 1, TAG_TEXT, false);
		Draw.text(g, this.font, pct, tx, y + 1, crisp ? accent : Draw.DIM, false);
	}

	/** "(off in settings)" centred inside a large enough ghost, else just below it. */
	private void drawOffTag(GuiGraphics g, HudLayoutMath.Rect o) {
		String tag = PreviewStrings.OFF_TAG;
		int w = this.font.width(tag) + 3;
		int x;
		int y;
		if (o.w() >= w + 4 && o.h() >= TAG_H + 4) {
			x = o.x() + (o.w() - w) / 2;
			y = o.y() + (o.h() - TAG_H) / 2;
		} else {
			x = HudLayoutMath.clampPos(o.x() + (o.w() - w) / 2, w, this.width);
			y = o.bottom() + 2;
			if (y + TAG_H > this.height) {
				y = o.y() - 2 - TAG_H;
			}
		}
		g.fill(x, y, x + w, y + TAG_H, TAG_BG);
		Draw.text(g, this.font, tag, x + 2, y + 1, Draw.DIM, false);
	}

	// ---- mouse ------------------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}
		HudElementId hit = elementAt(event.x(), event.y(), null);
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			setFocused(null);
			this.selected = hit;
			if (hit != null) {
				HudLayoutMath.Rect r = rect(hit);
				this.dragging = hit;
				this.grabX = event.x() - r.x();
				this.grabY = event.y() - r.y();
				this.guideX = NONE;
				this.guideY = NONE;
			}
			return true;
		}
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && hit != null) {
			setLayout(hit, HudLayout.defaultFor(hit));
			this.selected = hit;
			return true;
		}
		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (this.dragging != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			moveDragged(event.x(), event.y(), this.minecraft.hasShiftDown() || event.hasShiftDown());
			return true;
		}
		return super.mouseDragged(event, dragX, dragY);
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (this.dragging != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			HudElementId id = this.dragging;
			this.dragging = null;
			this.guideX = NONE;
			this.guideY = NONE;
			HudLayoutMath.Rect r = rect(id);
			setLayout(id, HudLayoutMath.autoAnchor(r, layout(id).scale, this.width, this.height));
			return true;
		}
		return super.mouseReleased(event);
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		HudElementId hit = this.dragging != null ? this.dragging : elementAt(mouseX, mouseY, null);
		if (hit == null || scrollY == 0) {
			return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
		}
		this.selected = hit;
		HudLayout l = layout(hit);
		float next = HudLayoutMath.scaleStep(l.scale, scrollY > 0 ? 1 : -1,
				this.minecraft.getWindow().getGuiScale(), this.minecraft.hasShiftDown());
		if (next == l.scale) {
			return true;
		}
		HudElement e = HudManager.get().element(hit);
		HudLayoutMath.Rect old = rect(hit);
		double cx = old.x() + old.w() / 2.0;
		double cy = old.y() + old.h() / 2.0;
		int w = HudLayoutMath.scaled(e.width(true), next);
		int h = HudLayoutMath.scaled(e.height(true), next);
		int nx = HudLayoutMath.clampPos((int) Math.round(cx - w / 2.0), w, this.width);
		int ny = HudLayoutMath.clampPos((int) Math.round(cy - h / 2.0), h, this.height);
		HudLayout scaled = new HudLayout(l.anchor, l.offsetX, l.offsetY, next);
		setLayout(hit, HudLayoutMath.withPosition(scaled, nx, ny, w, h, this.width, this.height));
		if (this.dragging == hit) {
			this.grabX = Math.min(this.grabX, w - 1);
			this.grabY = Math.min(this.grabY, h - 1);
		}
		return true;
	}

	private void moveDragged(double mouseX, double mouseY, boolean free) {
		HudElementId id = this.dragging;
		if (id == null) {
			return;
		}
		HudLayoutMath.Rect r = rect(id);
		int nx = (int) Math.round(mouseX - this.grabX);
		int ny = (int) Math.round(mouseY - this.grabY);
		List<HudLayoutMath.Span> xs = new ArrayList<>();
		List<HudLayoutMath.Span> ys = new ArrayList<>();
		for (HudElementId other : HudElementId.values()) {
			if (other != id) {
				HudLayoutMath.Rect o = rect(other);
				xs.add(o.xSpan());
				ys.add(o.ySpan());
			}
		}
		HudLayoutMath.Snap sx = HudLayoutMath.snapAxis(nx, r.w(), this.width, xs, free);
		HudLayoutMath.Snap sy = HudLayoutMath.snapAxis(ny, r.h(), this.height, ys, free);
		this.guideX = sx.snapped() ? Math.min(sx.guide(), this.width - 1) : NONE;
		this.guideY = sy.snapped() ? Math.min(sy.guide(), this.height - 1) : NONE;
		setLayout(id, HudLayoutMath.withPosition(layout(id), sx.pos(), sy.pos(), r.w(), r.h(), this.width, this.height));
	}

	// ---- keys -------------------------------------------------------------------------------

	@Override
	public boolean keyPressed(KeyEvent event) {
		int key = event.key();
		boolean shift = event.hasShiftDown();
		if (key == GLFW.GLFW_KEY_TAB) {
			cycleSelection(shift ? -1 : 1);
			return true;
		}
		if (key == GLFW.GLFW_KEY_G) {
			this.grid = !this.grid;
			return true;
		}
		if (this.selected != null && this.dragging == null) {
			int step = shift ? 10 : 1;
			switch (key) {
				case GLFW.GLFW_KEY_LEFT -> {
					nudge(-step, 0);
					return true;
				}
				case GLFW.GLFW_KEY_RIGHT -> {
					nudge(step, 0);
					return true;
				}
				case GLFW.GLFW_KEY_UP -> {
					nudge(0, -step);
					return true;
				}
				case GLFW.GLFW_KEY_DOWN -> {
					nudge(0, step);
					return true;
				}
				case GLFW.GLFW_KEY_R -> {
					setLayout(this.selected, HudLayout.defaultFor(this.selected));
					return true;
				}
				default -> {
				}
			}
		}
		return super.keyPressed(event);
	}

	private void cycleSelection(int direction) {
		List<HudElement> elements = HudManager.get().elements();
		int n = elements.size();
		int index = -1;
		for (int i = 0; i < n; i++) {
			if (elements.get(i).id() == this.selected) {
				index = i;
				break;
			}
		}
		if (index < 0) {
			index = direction > 0 ? -1 : 0;
		}
		this.selected = elements.get(Math.floorMod(index + direction, n)).id();
		setFocused(null);
	}

	private void nudge(int dx, int dy) {
		HudElementId id = this.selected;
		if (id == null) {
			return;
		}
		HudLayoutMath.Rect r = rect(id);
		int nx = HudLayoutMath.clampPos(r.x() + dx, r.w(), this.width);
		int ny = HudLayoutMath.clampPos(r.y() + dy, r.h(), this.height);
		setLayout(id, HudLayoutMath.withPosition(layout(id), nx, ny, r.w(), r.h(), this.width, this.height));
	}

	// ---- helpers ----------------------------------------------------------------------------

	private HudLayout layout(HudElementId id) {
		return Kwamster.config().layout(id);
	}

	private void setLayout(HudElementId id, HudLayout l) {
		KwamsterConfig cfg = Kwamster.config();
		cfg.layout(id); // makes sure the map exists
		l.scale = HudLayoutMath.clampScale(l.scale);
		cfg.layouts.put(id.name(), l);
	}

	/** The element's layout box as drawn in the editor (preview size). */
	private HudLayoutMath.Rect rect(HudElementId id) {
		return HudManager.rectOf(HudManager.get().element(id), Kwamster.config(), true, this.width, this.height);
	}

	/** The box plus, for the stoplight, its label area. */
	private HudLayoutMath.Rect outlineRect(HudElementId id, HudLayoutMath.Rect r) {
		if (id == HudElementId.STOPLIGHT) {
			HudLayoutMath.Rect label = HudManager.get().stoplight().labelRect(r, layout(id).scale, this.width);
			return label == null ? r : r.union(label);
		}
		return r;
	}

	/**
	 * The element under the point: the selected one if the point is on it (so a selection stays
	 * draggable where another element covers it), else the topmost (last drawn first), with a little
	 * slop for small elements.
	 */
	private @Nullable HudElementId elementAt(double x, double y, @Nullable Map<HudElementId, HudLayoutMath.Rect> outlines) {
		if (this.selected != null) {
			HudLayoutMath.Rect s = outlines != null ? outlines.get(this.selected) : null;
			if (s == null) {
				s = outlineRect(this.selected, rect(this.selected));
			}
			if (s.inflate(HIT_SLOP).contains(x, y)) {
				return this.selected;
			}
		}
		List<HudElement> elements = HudManager.get().elements();
		for (int i = elements.size() - 1; i >= 0; i--) {
			HudElementId id = elements.get(i).id();
			HudLayoutMath.Rect o = outlines != null ? outlines.get(id) : null;
			if (o == null) {
				o = outlineRect(id, rect(id));
			}
			if (o.inflate(HIT_SLOP).contains(x, y)) {
				return id;
			}
		}
		return null;
	}

	private static String displayName(@Nullable HudElementId id) {
		if (id == null) {
			return "";
		}
		return switch (id) {
			case STATS_PANEL -> "Stats panel";
			case HIT_INDICATOR -> "Hit indicator";
			case COMBO_COUNTER -> "Combo counter";
			case STOPLIGHT -> "Stoplight";
			case SUMMARY_CARD -> "Summary card";
		};
	}

	private static @Nullable HudElementId parseId(@Nullable String arg) {
		if (arg == null || arg.isBlank()) {
			return null;
		}
		try {
			return HudElementId.valueOf(arg.trim().toUpperCase(Locale.ROOT));
		} catch (IllegalArgumentException unknown) {
			return null;
		}
	}

	/** Deep copy of every element's layout (filled with defaults where missing). */
	private static Map<String, HudLayout> copyLayouts(KwamsterConfig cfg) {
		Map<String, HudLayout> copy = new LinkedHashMap<>();
		for (HudElementId id : HudElementId.values()) {
			copy.put(id.name(), cfg.layout(id).copy());
		}
		return copy;
	}

	private static Map<String, HudLayout> copyLayouts(Map<String, HudLayout> source) {
		Map<String, HudLayout> copy = new LinkedHashMap<>();
		for (HudElementId id : HudElementId.values()) {
			HudLayout l = source.get(id.name());
			copy.put(id.name(), l == null ? HudLayout.defaultFor(id) : l.copy());
		}
		return copy;
	}
}
