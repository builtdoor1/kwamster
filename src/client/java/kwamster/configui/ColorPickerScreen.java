package kwamster.configui;

import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import com.mojang.blaze3d.vertex.VertexConsumer;
import kwamster.Kwamster;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import me.shedaniel.clothconfig2.gui.ClothConfigScreen;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.gui.render.state.GuiElementRenderState;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/**
 * KWAMSTER's colour picker, opened from the swatch of every colour entry in the settings screen
 * ({@link GatedEntries}).
 *
 * <ul>
 *   <li><b>Picking.</b> A saturation/value square for the current hue (one gradient per physical
 *       pixel column, so it stays crisp at every GUI scale), a hue strip and an alpha strip over a
 *       checkerboard. Click or drag anywhere in them; a drag keeps following the mouse after it leaves
 *       the control. The mouse wheel nudges the strips and the R/G/B/A fields (Shift: bigger steps).</li>
 *   <li><b>Feedback.</b> Before/after halves over a checkerboard (clicking "before" goes back to it),
 *       a sample of the colour as text and as a bar on the HUD panel background, and an HSV readout.
 *       Everything updates live while dragging.</li>
 *   <li><b>Typing.</b> A hex field ({@code #AARRGGBB}; with or without {@code #}, six or eight
 *       digits) and R, G, B, A fields, all kept in sync. A field being typed in keeps the user's text
 *       until it loses focus, then shows the canonical value.</li>
 *   <li><b>Swatches.</b> KWAMSTER's palette and the last {@value #RECENT_MAX} colours picked this game
 *       session.</li>
 *   <li><b>Text colours</b> keep an alpha of at least {@link KwamsterConfig#MIN_TEXT_ALPHA}: the strip
 *       and the fields clamp to it, the strip shows the blocked band and the title row says so.</li>
 *   <li><b>Leaving.</b> Done (Enter) hands the colour to {@code onDone}, which makes it a pending,
 *       unsaved change of the entry, exactly as if it had been typed. Cancel (Esc) changes nothing.
 *       Both return to the parent screen; for Cloth's screen the list scroll position is put back,
 *       because Cloth rebuilds its list when it is shown again.</li>
 * </ul>
 *
 * The many small rectangles (the square's columns, the checkerboards, the marker ring) are collected in
 * a {@link QuadBatch} and submitted as one GUI element, because 1.21.11's {@code GuiRenderState} places
 * every element by scanning the ones before it. Nothing here throws into vanilla (R3): every handler
 * catches and logs through {@link LogLimiter}. Client thread.
 */
public final class ColorPickerScreen extends Screen {
	// ---- KWAMSTER look (§7.0) ---------------------------------------------------------
	private static final int CARD_BG = 0xE00E1116;
	private static final int BORDER = 0x26FFFFFF;
	private static final int BORDER_HOVER = 0x59FFFFFF;
	private static final int TOP_HIGHLIGHT = 0x14FFFFFF;
	private static final int DIVIDER = 0x1FFFFFFF;
	private static final int SHADOW_1 = 0x40000000;
	private static final int SHADOW_2 = 0x20000000;
	private static final int LABEL = 0xFF9AA4B2;
	private static final int VALUE = 0xFFF1F3F5;
	private static final int DIM = 0xFF6B7280;
	private static final int ACCENT = 0xFF8B5CF6;
	private static final int ACCENT_HOVER = 0xFF9F7BF8;
	private static final int WARN = 0xFFFFC53D;
	private static final int BAD = 0xFFFF5A5F;
	private static final int WHITE = 0xFFFFFFFF;
	private static final int FIELD_BG = 0xFF07090C;
	private static final int BUTTON_BG = 0x1AFFFFFF;
	private static final int BUTTON_BG_HOVER = 0x33FFFFFF;
	private static final int CHECK_LIGHT = 0xFFA8ADB4;
	private static final int CHECK_DARK = 0xFF6E737B;
	private static final int MARKER_DARK = 0xE0000000;
	private static final int BLOCKED_BAND = 0xB3000000;
	private static final int BAR_TRACK = 0x33FFFFFF;
	private static final int SAMPLE_PANEL_FALLBACK = 0xB80E1116;

	// ---- layout (GUI pixels) ------------------------------------------------------------
	private static final int MARGIN = 6;
	private static final int PAD = 8;
	private static final int GAP = 6;
	private static final int STRIP_W = 12;
	private static final int COLUMN_GAP = 10;
	private static final int TITLE_H = 11;
	private static final int BUTTON_H = 20;
	private static final int BUTTON_W = 72;
	private static final int FIELD_H = 16;
	private static final int HEADER_H = 9;
	private static final int PREVIEW_MIN = 24;
	private static final int PREVIEW_GROW = 40;
	private static final int SAMPLE_H = 22;
	private static final int BAR_H = 6;
	private static final int SQ_MIN = 64;
	private static final int SQ_MAX = 256;
	private static final int RIGHT_MIN = 150;
	private static final int RIGHT_MAX = 212;
	private static final int SWATCH_COLUMNS = 8;
	private static final int SWATCH_GAP = 3;
	private static final int RECENT_GAP = 5;
	private static final int CHECK = 4;
	private static final int CHANNEL_LABEL_W = 8;
	private static final int CHANNEL_GAP = 4;

	private static final int RECENT_MAX = 8;
	private static final long TOOLTIP_DELAY_MS = 450L;
	private static final long SWATCH_TOOLTIP_DELAY_MS = 250L;
	private static final long CLAMP_FLASH_MS = 1500L;

	private static final String PALETTE_LABEL = "Palette";
	private static final String RECENT_LABEL = "Recent";
	private static final String SAMPLE_TEXT = "Sample 3.07";
	private static final String SEP = " · ";
	private static final String ELLIPSIS = "...";
	private static final String TEXT_HINT = "Text colour: alpha 20+";
	private static final Component TEXT_HINT_TOOLTIP = Component.literal("Text colours keep an alpha of at least 20 (hex), about 13%, or the text would be invisible. The alpha strip and fields stop there.");

	/** KWAMSTER's own colours: the accent, the seven hit tiers, good/warn/bad and four neutrals. */
	private static final List<Named> PALETTE = List.of(
			new Named(0xFF8B5CF6, "KWAMSTER accent"),
			new Named(0xFFFFD43B, "3-block crit"),
			new Named(0xFF3DDC97, "3-block sprint"),
			new Named(0xFFB794F6, "3-block hit"),
			new Named(0xFFFF922B, "Crit"),
			new Named(0xFF4DABF7, "Sprint hit"),
			new Named(0xFFE9ECEF, "Normal hit"),
			new Named(0xFF868E96, "Weak hit"),
			new Named(0xFF3DDC84, "Good"),
			new Named(0xFFFFC53D, "Warning"),
			new Named(0xFFFF5A5F, "Bad"),
			new Named(0xFFFFFFFF, "White"),
			new Named(0xFFCED4DA, "Light grey"),
			new Named(0xFF495057, "Dark grey"),
			new Named(0xFF000000, "Black"));
	/** Colours confirmed with Done this game session, newest first (client thread only). */
	private static final List<Integer> RECENT = new ArrayList<>();

	private final @Nullable Screen parent;
	private final List<Component> path;
	private final int original;
	private final int defaultArgb;
	private final boolean text;
	private final IntConsumer onDone;
	/** The parent's Cloth list scroll when the picker opened, or NaN. */
	private final double parentScroll;

	/** The colour being edited: HSV is the source of truth for the square and strips, {@link #argb} the exact result. */
	private double hue;
	private double sat;
	private double val;
	private int alpha;
	private int argb;

	private Layout lay = Layout.EMPTY;
	private Drag drag = Drag.NONE;
	private boolean syncing;
	private long clampFlashUntil;
	private Area hoverArea = Area.NONE;
	private long hoverSince;
	private final List<Field> fields = new ArrayList<>();
	private @Nullable FlatButton defaultButton;
	private final QuadBatch batch = new QuadBatch();

	/**
	 * @param parent      the screen to return to (normally the Cloth settings screen; null: the game)
	 * @param path        where the setting lives, outermost first, ending with the setting's name
	 *                    (e.g. "Hit feedback", "■ 3-block crit", "Colour"); shown as the title
	 * @param initialArgb the colour to start from; also the "before" half
	 * @param defaultArgb the setting's default, for the Default button
	 * @param textColour  true for text colours, which keep an alpha of at least
	 *                    {@link KwamsterConfig#MIN_TEXT_ALPHA}
	 * @param onDone      receives the picked colour when Done is pressed (never on Cancel)
	 */
	public ColorPickerScreen(@Nullable Screen parent, List<Component> path, int initialArgb, int defaultArgb,
			boolean textColour, IntConsumer onDone) {
		super(Component.literal("Colour picker: " + joinPlain(path)));
		this.parent = parent;
		this.path = List.copyOf(path);
		this.original = initialArgb;
		this.defaultArgb = defaultArgb;
		this.text = textColour;
		this.onDone = onDone;
		this.parentScroll = scrollOf(parent);
		int start = ColorMath.atLeastAlpha(initialArgb, minAlpha());
		ColorMath.Hsv hsv = ColorMath.toHsv(start);
		this.hue = hsv.hue();
		this.sat = hsv.saturation();
		this.val = hsv.value();
		this.alpha = ColorMath.alpha(start);
		this.argb = start;
	}

	/** The picker's current colour (what Done would hand over). */
	public int colour() {
		return this.argb;
	}

	// ---- lifecycle -------------------------------------------------------------------------

	@Override
	protected void init() {
		this.drag = Drag.NONE;
		this.fields.clear();
		this.defaultButton = null;
		try {
			this.lay = Layout.compute(this.font, this.width, this.height);
			buildWidgets();
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.init", "Could not lay out the colour picker", t);
		}
	}

	private void buildWidgets() {
		Layout l = this.lay;
		int hexLabelW = this.font.width("Hex") + 5;
		int hexW = Math.min(l.rightW() - hexLabelW, this.font.width("#DDDDDDDD") + 14);
		addField(Field.HEX, "Hex colour", l.rightX() + hexLabelW, l.hexY(), hexW, 10);

		int groupW = (l.rightW() - 3 * CHANNEL_GAP) / 4;
		int[] shifts = {ColorMath.RED_SHIFT, ColorMath.GREEN_SHIFT, ColorMath.BLUE_SHIFT, ColorMath.ALPHA_SHIFT};
		String[] names = {"Red", "Green", "Blue", "Alpha"};
		for (int i = 0; i < 4; i++) {
			int gx = l.rightX() + i * (groupW + CHANNEL_GAP);
			addField(shifts[i], names[i] + " (0 to 255)", gx + CHANNEL_LABEL_W, l.rgbaY(), groupW - CHANNEL_LABEL_W, 3);
		}

		int sx = l.rightX() + l.labelColW();
		int cell = l.swatch() + l.swatchGap();
		for (int i = 0; i < PALETTE.size(); i++) {
			Named n = PALETTE.get(i);
			int col = i % SWATCH_COLUMNS;
			addSwatch(n.argb(), n.name(), sx + col * cell, i < SWATCH_COLUMNS ? l.palette1Y() : l.palette2Y());
		}
		for (int i = 0; i < RECENT.size() && i < SWATCH_COLUMNS; i++) {
			addSwatch(RECENT.get(i), "Recent", sx + i * cell, l.recentY());
		}

		int bw = l.buttonW();
		int right = l.innerX() + l.innerW();
		FlatButton reset = new FlatButton(Component.literal("Default"), false, input -> {
			if (input instanceof MouseButtonEvent) {
				setFocused(null); // a click ends any typing; keyboard focus stays put
			}
			applyArgb(this.defaultArgb, null);
		});
		reset.setTooltip(Tooltip.create(Component.literal("Sets the picker to this setting's default, " + ColorMath.toHex(this.defaultArgb) + ".")));
		reset.setRectangle(bw, BUTTON_H, l.innerX(), l.buttonsY());
		this.defaultButton = addRenderableWidget(reset);
		FlatButton cancel = new FlatButton(Component.literal("Cancel"), false, input -> onClose());
		cancel.setTooltip(Tooltip.create(Component.literal("Back to the settings without changing this colour (Esc).")));
		cancel.setRectangle(bw, BUTTON_H, right - 2 * bw - GAP, l.buttonsY());
		addRenderableWidget(cancel);
		FlatButton done = new FlatButton(Component.literal("Done"), true, input -> done());
		done.setTooltip(Tooltip.create(Component.literal("Use this colour (Enter). Like typed text, it is applied when you save the settings.")));
		done.setRectangle(bw, BUTTON_H, right - bw, l.buttonsY());
		addRenderableWidget(done);
	}

	private void addField(int shift, String narration, int x, int y, int w, int maxLength) {
		Field f = new Field(this.font, Component.literal(narration), shift);
		f.setMaxLength(maxLength);
		f.setFilter(shift == Field.HEX ? ColorPickerScreen::hexInputOk : ColorPickerScreen::channelInputOk);
		f.frame(x, y, w, FIELD_H);
		f.setValue(canonical(f));
		f.setResponder(s -> typed(f, s));
		this.fields.add(addRenderableWidget(f));
	}

	private void addSwatch(int colour, String name, int x, int y) {
		SwatchButton b = new SwatchButton(colour, name);
		b.setRectangle(this.lay.swatch(), this.lay.swatch(), x, y);
		addRenderableWidget(b);
	}

	/** Esc and Cancel: back to the parent, nothing changed. */
	@Override
	public void onClose() {
		returnToParent();
	}

	/** Done and Enter: hand the colour to the entry, remember it, go back. */
	private void done() {
		int result = this.argb;
		if (result != this.original) {
			remember(result);
		}
		try {
			this.onDone.accept(result);
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.done", "Could not hand the picked colour to the settings entry", t);
		}
		returnToParent();
	}

	private void returnToParent() {
		this.drag = Drag.NONE;
		this.minecraft.setScreen(this.parent);
		restoreScroll(this.parent, this.parentScroll);
	}

	private static void remember(int argb) {
		RECENT.remove(Integer.valueOf(argb));
		RECENT.add(0, argb);
		while (RECENT.size() > RECENT_MAX) {
			RECENT.remove(RECENT.size() - 1);
		}
	}

	// ---- colour state ------------------------------------------------------------------------

	private int minAlpha() {
		return this.text ? KwamsterConfig.MIN_TEXT_ALPHA : 0;
	}

	/**
	 * Sets the colour from an exact ARGB value (typed, a swatch, Default, "before"). A grey keeps the
	 * current hue and black also the saturation, so the square does not jump. {@code source} is the
	 * field being typed in, whose text is left alone.
	 */
	private void applyArgb(int argb, @Nullable Field source) {
		int c = clampAlphaFlash(argb);
		ColorMath.Hsv hsv = ColorMath.toHsv(c, this.hue, this.sat);
		this.hue = hsv.hue();
		this.sat = hsv.saturation();
		this.val = hsv.value();
		this.alpha = ColorMath.alpha(c);
		this.argb = c;
		syncFields(source);
	}

	/** Recomputes the colour after the square or the hue strip moved. */
	private void applyHsv() {
		this.argb = ColorMath.withAlpha(ColorMath.hsvToRgb(this.hue, this.sat, this.val), this.alpha);
		syncFields(null);
	}

	private void setAlpha(int a) {
		this.argb = clampAlphaFlash(ColorMath.withAlpha(this.argb, a));
		this.alpha = ColorMath.alpha(this.argb);
		syncFields(null);
	}

	/** {@code argb} with the text-colour alpha floor applied; flashes the hint when that changed it. */
	private int clampAlphaFlash(int argb) {
		int c = ColorMath.atLeastAlpha(argb, minAlpha());
		if (c != argb) {
			this.clampFlashUntil = Util.getMillis() + CLAMP_FLASH_MS;
		}
		return c;
	}

	private String canonical(Field f) {
		return f.shift == Field.HEX ? ColorMath.toHex(this.argb) : Integer.toString(ColorMath.channel(this.argb, f.shift));
	}

	/** Shows the colour in every field except {@code source}, the one being typed in. */
	private void syncFields(@Nullable Field source) {
		for (Field f : this.fields) {
			if (f != source) {
				showCanonical(f);
			}
		}
	}

	/** A field that lost focus drops whatever was typed and shows the canonical value again. */
	private void normaliseIdleFields() {
		for (Field f : this.fields) {
			if (!f.isFocused()) {
				showCanonical(f);
			}
		}
	}

	private void showCanonical(Field f) {
		String want = canonical(f);
		if (!want.equals(f.getValue())) {
			this.syncing = true;
			try {
				f.setValue(want);
			} finally {
				this.syncing = false;
			}
		}
		f.setTextColor(VALUE);
	}

	/**
	 * Live parsing of typed text: valid text moves the colour at once. The text is grey while it is
	 * still too short, red when it cannot become valid, and amber when the value was clamped.
	 */
	private void typed(Field f, String s) {
		if (this.syncing) {
			return;
		}
		try {
			if (f.shift == Field.HEX) {
				OptionalInt c = ColorMath.parseHex(s);
				if (c.isEmpty()) {
					f.setTextColor(hexIncomplete(s) ? LABEL : BAD);
					return;
				}
				applyArgb(c.getAsInt(), f);
				f.setTextColor(ColorMath.alpha(c.getAsInt()) < minAlpha() ? WARN : VALUE);
			} else {
				OptionalInt v = ColorMath.parseChannel(s);
				if (v.isEmpty()) {
					f.setTextColor(LABEL);
					return;
				}
				int raw = v.getAsInt();
				applyArgb(ColorMath.withChannel(this.argb, f.shift, raw), f);
				boolean clamped = raw > 255 || (f.shift == ColorMath.ALPHA_SHIFT && raw < minAlpha());
				f.setTextColor(clamped ? WARN : VALUE);
			}
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.typed", "Could not read the typed colour", t);
		}
	}

	/** True while typed hex text is a valid start of a colour (fewer than six digits so far). */
	private static boolean hexIncomplete(String s) {
		String digits = s.strip();
		if (digits.startsWith("#")) {
			digits = digits.substring(1);
		} else if (digits.startsWith("0x") || digits.startsWith("0X")) {
			digits = digits.substring(2);
		}
		if (digits.length() >= 6) {
			return false;
		}
		for (int i = 0; i < digits.length(); i++) {
			char c = digits.charAt(i);
			if (c == '#' || c == 'x' || c == 'X' || c == ' ') {
				return false;
			}
		}
		return true;
	}

	private static boolean hexInputOk(String s) {
		if (s.length() > 10) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			if (!ColorMath.isHexInputChar(s.charAt(i))) {
				return false;
			}
		}
		return true;
	}

	private static boolean channelInputOk(String s) {
		if (s.length() > 3) {
			return false;
		}
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < '0' || c > '9') {
				return false;
			}
		}
		return true;
	}

	// ---- mouse and keys ---------------------------------------------------------------------

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		try {
			if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				double x = event.x();
				double y = event.y();
				Drag target = dragTargetAt(x, y);
				if (target != Drag.NONE) {
					setFocused(null);
					this.drag = target;
					dragTo(x, y);
					return true;
				}
				if (areaAt(x, y) == Area.BEFORE) {
					setFocused(null);
					AbstractWidget.playButtonClickSound(this.minecraft.getSoundManager());
					applyArgb(this.original, null);
					return true;
				}
			}
			if (super.mouseClicked(event, doubleClick)) {
				return true;
			}
			setFocused(null);
			return false;
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.mouseClicked", "Colour picker click failed", t);
			return true;
		}
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		try {
			if (this.drag != Drag.NONE && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				dragTo(event.x(), event.y());
				return true;
			}
			return super.mouseDragged(event, dragX, dragY);
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.mouseDragged", "Colour picker drag failed", t);
			return true;
		}
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		try {
			if (this.drag != Drag.NONE && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
				dragTo(event.x(), event.y());
				this.drag = Drag.NONE;
				return true;
			}
			return super.mouseReleased(event);
		} catch (Throwable t) {
			this.drag = Drag.NONE;
			LogLimiter.error("ColorPickerScreen.mouseReleased", "Colour picker release failed", t);
			return true;
		}
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		try {
			if (scrollY != 0.0 && this.drag == Drag.NONE) {
				int dir = scrollY > 0.0 ? 1 : -1;
				boolean big = this.minecraft.hasShiftDown();
				Drag strip = dragTargetAt(mouseX, mouseY);
				if (strip == Drag.HUE) {
					this.hue = ColorMath.clampHue(this.hue - dir * (big ? 10.0 : 1.0));
					applyHsv();
					return true;
				}
				if (strip == Drag.ALPHA) {
					setAlpha(this.alpha + dir * (big ? 16 : 1));
					return true;
				}
				for (Field f : this.fields) {
					if (f.shift != Field.HEX && f.isMouseOver(mouseX, mouseY)) {
						int next = ColorMath.channel(this.argb, f.shift) + dir * (big ? 10 : 1);
						applyArgb(ColorMath.withChannel(this.argb, f.shift, next), null);
						return true;
					}
				}
			}
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.mouseScrolled", "Colour picker scroll failed", t);
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		try {
			if (event.isConfirmation() && !(getFocused() instanceof AbstractButton)) {
				done();
				return true;
			}
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.keyPressed", "Colour picker key failed", t);
			return true;
		}
		return super.keyPressed(event);
	}

	private Drag dragTargetAt(double x, double y) {
		Layout l = this.lay;
		if (l.sq() <= 0) {
			return Drag.NONE;
		}
		int top = l.sqY();
		int bottom = l.sqY() + l.sq();
		if (x >= l.sqX() && x < l.sqX() + l.sq() && y >= top && y < bottom) {
			return Drag.SQUARE;
		}
		// the strips take clicks a little beyond their edges, like their handles
		if (y >= top - 2 && y < bottom + 2) {
			if (x >= l.hueX() - 2 && x < l.hueX() + STRIP_W + 2) {
				return Drag.HUE;
			}
			if (x >= l.alphaX() - 2 && x < l.alphaX() + STRIP_W + 2) {
				return Drag.ALPHA;
			}
		}
		return Drag.NONE;
	}

	/** Moves the dragged control to the mouse, clamped to its ends when the mouse is outside it. */
	private void dragTo(double x, double y) {
		Layout l = this.lay;
		double fy = ColorMath.fraction(y, l.sqY(), l.sq());
		switch (this.drag) {
			case SQUARE -> {
				this.sat = ColorMath.fraction(x, l.sqX(), l.sq());
				this.val = 1.0 - fy;
				applyHsv();
			}
			case HUE -> {
				this.hue = 360.0 * fy;
				applyHsv();
			}
			case ALPHA -> setAlpha((int) Math.round(255.0 * (1.0 - fy)));
			case NONE -> {
			}
		}
	}

	private Area areaAt(double x, double y) {
		Layout l = this.lay;
		Drag d = dragTargetAt(x, y);
		if (d == Drag.SQUARE) {
			return Area.SQUARE;
		}
		if (d == Drag.HUE) {
			return Area.HUE;
		}
		if (d == Drag.ALPHA) {
			return Area.ALPHA;
		}
		if (y >= l.previewY() && y < l.previewY() + l.previewH() && x >= l.rightX() && x < l.rightX() + l.rightW()) {
			return x < l.rightX() + l.rightW() / 2 ? Area.BEFORE : Area.AFTER;
		}
		if (this.text && y >= l.innerY() && y < l.innerY() + TITLE_H
				&& x >= l.innerX() + l.innerW() - l.hintW() && x < l.innerX() + l.innerW()) {
			return Area.HINT;
		}
		return Area.NONE;
	}

	// ---- rendering --------------------------------------------------------------------------

	@Override
	public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
		try {
			normaliseIdleFields();
			if (this.defaultButton != null) {
				this.defaultButton.active = this.argb != ColorMath.atLeastAlpha(this.defaultArgb, minAlpha());
			}
			int gs = Math.max(1, this.minecraft.getWindow().getGuiScale());
			QuadBatch b = this.batch;
			drawCard(b);
			drawSquare(b, gs);
			drawHueStrip(b, gs);
			drawAlphaStrip(b);
			drawSquareMarker(b, gs);
			drawStripHandle(b, gs, this.lay.hueX(), ColorMath.clampHue(this.hue) / 360.0, this.drag == Drag.HUE);
			drawStripHandle(b, gs, this.lay.alphaX(), 1.0 - this.alpha / 255.0, this.drag == Drag.ALPHA);
			drawPreview(b);
			drawSamplePanel(b);
			b.submit(g);
			drawTitle(g);
			drawTexts(g);
			hoverFeedback(g, mouseX, mouseY);
		} catch (Throwable t) {
			this.batch.clear();
			LogLimiter.error("ColorPickerScreen.render", "Could not draw the colour picker", t);
		}
		super.render(g, mouseX, mouseY, partialTick);
	}

	/** The dark glass card: outside shadow, rounded body, border, top highlight and two dividers. */
	private void drawCard(Rects r) {
		Layout l = this.lay;
		int x0 = l.cardX();
		int y0 = l.cardY();
		int x1 = x0 + l.cardW();
		int y1 = y0 + l.cardH();
		fill(r, x0 + 1, y1, x1 - 1, y1 + 1, SHADOW_1);
		fill(r, x0 + 1, y1 + 1, x1 - 1, y1 + 2, SHADOW_2);
		roundRect(r, x0, y0, x1, y1, CARD_BG);
		border(r, x0, y0, x1, y1, BORDER);
		fill(r, x0 + 1, y0 + 1, x1 - 1, y0 + 2, TOP_HIGHLIGHT);
		int titleRule = l.innerY() + TITLE_H + GAP / 2 - 1;
		fill(r, l.innerX(), titleRule, l.innerX() + l.innerW(), titleRule + 1, DIVIDER);
		int buttonRule = l.buttonsY() - GAP / 2 - 1;
		fill(r, l.innerX(), buttonRule, l.innerX() + l.innerW(), buttonRule + 1, DIVIDER);
	}

	/**
	 * The saturation/value square, one gradient per physical pixel column: each runs from the fully
	 * bright colour at the top (white blended towards the pure hue by the column's saturation) to black
	 * at the bottom. Neighbouring columns that round to the same 8-bit colour share one gradient.
	 */
	private void drawSquare(QuadBatch b, int gs) {
		Layout l = this.lay;
		int n = l.sq() * gs;
		float x = l.sqX();
		float y0 = l.sqY();
		float y1 = l.sqY() + l.sq();
		int runStart = 0;
		int runTop = ColorMath.hsvToRgb(this.hue, 0.5 / n, 1.0);
		for (int i = 1; i <= n; i++) {
			int top = i < n ? ColorMath.hsvToRgb(this.hue, (i + 0.5) / n, 1.0) : 0;
			if (i == n || top != runTop) {
				b.gradient(x + runStart / (float) gs, y0, x + i / (float) gs, y1, runTop, ColorMath.BLACK);
				runStart = i;
				runTop = top;
			}
		}
		border(b, l.sqX() - 1, l.sqY() - 1, l.sqX() + l.sq() + 1, l.sqY() + l.sq() + 1, BORDER);
	}

	/** Six gradient segments, red at the top through yellow, green, cyan, blue and magenta back to red. */
	private void drawHueStrip(QuadBatch b, int gs) {
		Layout l = this.lay;
		float x0 = l.hueX();
		float x1 = l.hueX() + STRIP_W;
		int h = l.sq() * gs;
		for (int k = 0; k < 6; k++) {
			float ya = l.sqY() + Math.round(h * k / 6.0) / (float) gs;
			float yb = l.sqY() + Math.round(h * (k + 1) / 6.0) / (float) gs;
			b.gradient(x0, ya, x1, yb, ColorMath.hsvToRgb(60.0 * k, 1.0, 1.0), ColorMath.hsvToRgb(60.0 * (k + 1), 1.0, 1.0));
		}
		border(b, l.hueX() - 1, l.sqY() - 1, l.hueX() + STRIP_W + 1, l.sqY() + l.sq() + 1, BORDER);
	}

	/** Opaque at the top to transparent at the bottom, over a checkerboard; text colours show the blocked band. */
	private void drawAlphaStrip(QuadBatch b) {
		Layout l = this.lay;
		int x0 = l.alphaX();
		int x1 = x0 + STRIP_W;
		int y0 = l.sqY();
		int y1 = y0 + l.sq();
		checker(b, x0, y0, x1, y1, CHECK, false);
		int rgb = this.argb & 0x00FFFFFF;
		b.gradient(x0, y0, x1, y1, ColorMath.BLACK | rgb, rgb);
		if (this.text) {
			int limit = y0 + (int) Math.round(l.sq() * (1.0 - KwamsterConfig.MIN_TEXT_ALPHA / 255.0));
			fill(b, x0, limit, x1, y1, BLOCKED_BAND);
			fill(b, x0, limit, x1, limit + 1, LABEL);
		}
		border(b, x0 - 1, y0 - 1, x1 + 1, y1 + 1, BORDER);
	}

	/** A white ring with dark edges around the picked point, in physical pixels so it stays round. */
	private void drawSquareMarker(QuadBatch b, int gs) {
		Layout l = this.lay;
		double cx = (l.sqX() + this.sat * l.sq()) * gs;
		double cy = (l.sqY() + (1.0 - this.val) * l.sq()) * gs;
		double outer = (this.drag == Drag.SQUARE ? 5.0 : 4.5) * gs;
		double inner = outer - Math.max(1.5, 1.25 * gs);
		annulus(b, gs, cx, cy, inner - 1.0, outer + 1.0, MARKER_DARK);
		annulus(b, gs, cx, cy, inner, outer, WHITE);
	}

	/** A white line handle across a strip at {@code t} (0 top, 1 bottom), with a dark edge (accent while dragged). */
	private void drawStripHandle(QuadBatch b, int gs, int stripX, double t, boolean active) {
		Layout l = this.lay;
		float s = gs;
		int x0 = stripX * gs;
		int x1 = (stripX + STRIP_W) * gs;
		int yc = (int) Math.round((l.sqY() + ColorMath.clamp01(t) * l.sq()) * gs);
		int half = Math.max(1, (gs + 1) / 2);
		int over = Math.max(1, (3 * gs) / 2);
		int edge = Math.max(1, gs / 2);
		b.fill((x0 - over - edge) / s, (yc - half - edge) / s, (x1 + over + edge) / s, (yc + half + edge) / s, active ? ACCENT : MARKER_DARK);
		b.fill((x0 - over) / s, (yc - half) / s, (x1 + over) / s, (yc + half) / s, WHITE);
	}

	/** "Before" and "after" halves over a checkerboard, so transparency shows. */
	private void drawPreview(Rects r) {
		Layout l = this.lay;
		int x0 = l.rightX();
		int x1 = x0 + l.rightW();
		int y0 = l.previewY();
		int y1 = y0 + l.previewH();
		int mid = x0 + l.rightW() / 2;
		checker(r, x0, y0, x1, y1, CHECK, true);
		fillInRounded(r, x0, y0, mid, y1, x0, y0, x1, y1, this.original);
		fillInRounded(r, mid, y0, x1, y1, x0, y0, x1, y1, this.argb);
		border(r, x0, y0, x1, y1, BORDER);
	}

	/** The sample panel (the HUD panel background) and the colour as a filled bar; the sample text follows in {@link #drawTexts}. */
	private void drawSamplePanel(Rects r) {
		Layout l = this.lay;
		int x0 = l.rightX();
		int x1 = x0 + l.rightW();
		int y0 = l.sampleY();
		int y1 = y0 + SAMPLE_H;
		roundRect(r, x0, y0, x1, y1, samplePanelBg());
		border(r, x0, y0, x1, y1, BORDER);
		int barX0 = sampleBarX0();
		int barX1 = x1 - 6;
		if (barX1 - barX0 >= 16) {
			int by0 = y0 + (SAMPLE_H - BAR_H) / 2;
			roundRect(r, barX0, by0, barX1, by0 + BAR_H, BAR_TRACK);
			roundRect(r, barX0, by0, barX0 + Math.round((barX1 - barX0) * 0.7f), by0 + BAR_H, this.argb);
		}
	}

	private int sampleBarX0() {
		return this.lay.rightX() + 6 + this.font.width(SAMPLE_TEXT) + 6;
	}

	/** The live HUD panel background, so the sample matches the HUD (the shipped dark glass if unreadable). */
	private static int samplePanelBg() {
		try {
			return Kwamster.config().hud.panelBgArgb;
		} catch (Throwable t) {
			return SAMPLE_PANEL_FALLBACK;
		}
	}

	/** Where the setting lives ("Hit feedback · ■ 3-block crit · Colour"), and the text-colour hint on the right. */
	private void drawTitle(GuiGraphics g) {
		Layout l = this.lay;
		int y = l.innerY() + 1;
		int right = l.innerX() + l.innerW();
		int avail = l.innerW();
		if (this.text) {
			boolean flashing = Util.getMillis() < this.clampFlashUntil;
			g.drawString(this.font, TEXT_HINT, right - l.hintW(), y, flashing ? WARN : DIM, false);
			avail -= l.hintW() + 10;
		}
		int n = this.path.size();
		if (n == 0) {
			g.drawString(this.font, "Colour", l.innerX(), y, VALUE, false);
			return;
		}
		int start = 0;
		while (start < n - 1 && pathWidth(start) > avail) {
			start++;
		}
		int x = l.innerX();
		int end = l.innerX() + avail;
		for (int i = start; i < n; i++) {
			Component c = this.path.get(i);
			if (i < n - 1) {
				g.drawString(this.font, c, x, y, LABEL, false);
				x += this.font.width(c);
				g.drawString(this.font, SEP, x, y, DIM, false);
				x += this.font.width(SEP);
			} else if (this.font.width(c) <= end - x) {
				g.drawString(this.font, c, x, y, VALUE, false);
			} else {
				int room = Math.max(0, end - x - this.font.width(ELLIPSIS));
				g.drawString(this.font, this.font.plainSubstrByWidth(c.getString(), room) + ELLIPSIS, x, y, VALUE, false);
			}
		}
	}

	private int pathWidth(int start) {
		int w = 0;
		for (int i = start; i < this.path.size(); i++) {
			w += this.font.width(this.path.get(i));
			if (i < this.path.size() - 1) {
				w += this.font.width(SEP);
			}
		}
		return w;
	}

	/** Every label on the right column, the sample text and the HSV readout (all flat, on the card). */
	private void drawTexts(GuiGraphics g) {
		Layout l = this.lay;
		int x = l.rightX();
		int right = l.rightX() + l.rightW();
		int fieldTextY = (FIELD_H - 8) / 2;
		g.drawString(this.font, "Before", x, l.headerY(), LABEL, false);
		g.drawString(this.font, "After", right - this.font.width("After") + 1, l.headerY(), LABEL, false);
		g.drawString(this.font, SAMPLE_TEXT, x + 6, l.sampleY() + (SAMPLE_H - 8) / 2, this.argb, false);
		g.drawString(this.font, "Hex", x, l.hexY() + fieldTextY, LABEL, false);
		if (!this.fields.isEmpty()) {
			String hsv = String.format(Locale.ROOT, "HSV %d° %d%% %d%%", Math.round(this.hue) % 360,
					Math.round(this.sat * 100.0), Math.round(this.val * 100.0));
			int free = right - (this.fields.get(0).frameRight() + 6);
			if (this.font.width(hsv) > free) {
				hsv = hsv.substring(4);
			}
			if (this.font.width(hsv) <= free) {
				g.drawString(this.font, hsv, right - this.font.width(hsv) + 1, l.hexY() + fieldTextY, DIM, false);
			}
		}
		int groupW = (l.rightW() - 3 * CHANNEL_GAP) / 4;
		String[] channels = {"R", "G", "B", "A"};
		for (int i = 0; i < 4; i++) {
			g.drawString(this.font, channels[i], x + i * (groupW + CHANNEL_GAP), l.rgbaY() + fieldTextY, LABEL, false);
		}
		int swatchTextY = (l.swatch() - 7) / 2;
		g.drawString(this.font, PALETTE_LABEL, x, l.palette1Y() + swatchTextY, LABEL, false);
		g.drawString(this.font, RECENT_LABEL, x, l.recentY() + swatchTextY, LABEL, false);
		if (RECENT.isEmpty()) {
			String empty = "Your picks appear here";
			if (this.font.width(empty) > l.rightW() - l.labelColW()) {
				empty = "None yet";
			}
			g.drawString(this.font, empty, x + l.labelColW(), l.recentY() + swatchTextY, DIM, false);
		}
	}

	/** Cursors for the custom controls, and delayed tooltips for the strips, the preview halves and the hint. */
	private void hoverFeedback(GuiGraphics g, int mouseX, int mouseY) {
		if (this.drag == Drag.SQUARE) {
			g.requestCursor(CursorTypes.CROSSHAIR);
		} else if (this.drag != Drag.NONE) {
			g.requestCursor(CursorTypes.RESIZE_NS);
		}
		Area area = this.drag == Drag.NONE ? areaAt(mouseX, mouseY) : Area.NONE;
		long now = Util.getMillis();
		if (area != this.hoverArea) {
			this.hoverArea = area;
			this.hoverSince = now;
		}
		switch (area) {
			case SQUARE -> g.requestCursor(CursorTypes.CROSSHAIR);
			case HUE, ALPHA, BEFORE -> g.requestCursor(CursorTypes.POINTING_HAND);
			default -> {
			}
		}
		if (area == Area.NONE || area == Area.SQUARE || now - this.hoverSince < TOOLTIP_DELAY_MS) {
			return;
		}
		Component tip = switch (area) {
			case HUE -> Component.literal("Hue. Drag, or scroll for small steps (Shift: bigger).");
			case ALPHA -> Component.literal(this.text
					? "Opacity (alpha), transparent at the bottom. Text colours stop at 20 (hex) so they stay readable."
					: "Opacity (alpha), transparent at the bottom. Drag, or scroll for small steps (Shift: bigger).");
			case BEFORE -> Component.literal("Before: " + ColorMath.toHex(this.original) + ". Click to go back to it.");
			case AFTER -> Component.literal("After: " + ColorMath.toHex(this.argb));
			case HINT -> TEXT_HINT_TOOLTIP;
			default -> null;
		};
		if (tip != null) {
			g.setTooltipForNextFrame(this.font, tip, mouseX, mouseY);
		}
	}

	// ---- drawing helpers (KWAMSTER's 1 px rounded look) ---------------------------------------

	/** Where rectangles go: straight to the GUI ({@code g::fill}) or into a {@link QuadBatch}. */
	@FunctionalInterface
	interface Rects {
		void fill(int x0, int y0, int x1, int y1, int argb);
	}

	/** A filled rect; skipped when empty or fully transparent. */
	static void fill(Rects r, int x0, int y0, int x1, int y1, int argb) {
		if (x1 > x0 && y1 > y0 && (argb >>> 24) != 0) {
			r.fill(x0, y0, x1, y1, argb);
		}
	}

	/**
	 * Fills the part of {@code [rx0, rx1) × [ry0, ry1)} inside the box {@code [bx0, bx1) × [by0, by1)},
	 * leaving the box's four corner pixels unpainted (1 px rounded corners).
	 */
	static void fillInRounded(Rects r, int rx0, int ry0, int rx1, int ry1, int bx0, int by0, int bx1, int by1, int argb) {
		int x0 = Math.max(rx0, bx0);
		int y0 = Math.max(ry0, by0);
		int x1 = Math.min(rx1, bx1);
		int y1 = Math.min(ry1, by1);
		if (x1 <= x0 || y1 <= y0) {
			return;
		}
		fill(r, x0, Math.max(y0, by0 + 1), x1, Math.min(y1, by1 - 1), argb);
		if (y0 == by0) {
			fill(r, Math.max(x0, bx0 + 1), by0, Math.min(x1, bx1 - 1), by0 + 1, argb);
		}
		if (y1 == by1 && by1 - 1 > by0) {
			fill(r, Math.max(x0, bx0 + 1), by1 - 1, Math.min(x1, bx1 - 1), by1, argb);
		}
	}

	static void roundRect(Rects r, int x0, int y0, int x1, int y1, int argb) {
		fillInRounded(r, x0, y0, x1, y1, x0, y0, x1, y1, argb);
	}

	/** A 1 px border inside the box that skips the four corner pixels (same silhouette as {@link #roundRect}). */
	static void border(Rects r, int x0, int y0, int x1, int y1, int argb) {
		if (x1 - x0 < 3 || y1 - y0 < 3) {
			return;
		}
		fill(r, x0 + 1, y0, x1 - 1, y0 + 1, argb);
		fill(r, x0 + 1, y1 - 1, x1 - 1, y1, argb);
		fill(r, x0, y0 + 1, x0 + 1, y1 - 1, argb);
		fill(r, x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/** A full 1 px outline including the corners, inside {@code [x0, x1) × [y0, y1)}. */
	static void outline(Rects r, int x0, int y0, int x1, int y1, int argb) {
		fill(r, x0, y0, x1, y0 + 1, argb);
		fill(r, x0, y1 - 1, x1, y1, argb);
		fill(r, x0, y0 + 1, x0 + 1, y1 - 1, argb);
		fill(r, x1 - 1, y0 + 1, x1, y1 - 1, argb);
	}

	/**
	 * The checkerboard behind translucent colours (also used by the settings entry's swatch, so both
	 * show transparency the same way): {@code tile} px squares, light in the top-left corner; with
	 * {@code rounded} the four corner pixels stay unpainted.
	 */
	static void checker(Rects r, int x0, int y0, int x1, int y1, int tile, boolean rounded) {
		int step = Math.max(1, tile);
		int k = rounded ? 0 : 1;
		int bx0 = x0 - k;
		int by0 = y0 - k;
		int bx1 = x1 + k;
		int by1 = y1 + k;
		fillInRounded(r, x0, y0, x1, y1, bx0, by0, bx1, by1, CHECK_LIGHT);
		int row = 0;
		for (int ty = y0; ty < y1; ty += step, row++) {
			for (int tx = x0 + ((row & 1) == 0 ? step : 0); tx < x1; tx += 2 * step) {
				fillInRounded(r, tx, ty, Math.min(tx + step, x1), Math.min(ty + step, y1), bx0, by0, bx1, by1, CHECK_DARK);
			}
		}
	}

	/**
	 * Fills the physical pixels whose centres lie at a distance in {@code [inner, outer)} from
	 * {@code (cx, cy)}; all lengths in physical pixels, {@code gs} of them per GUI pixel.
	 */
	private static void annulus(QuadBatch b, int gs, double cx, double cy, double inner, double outer, int argb) {
		float s = gs;
		double o2 = outer * outer;
		double i2 = inner > 0.0 ? inner * inner : -1.0;
		int yStart = (int) Math.floor(cy - outer);
		int yEnd = (int) Math.ceil(cy + outer);
		for (int y = yStart; y < yEnd; y++) {
			double dy = y + 0.5 - cy;
			double dy2 = dy * dy;
			if (dy2 >= o2) {
				continue;
			}
			double wo = Math.sqrt(o2 - dy2);
			int xa = (int) Math.ceil(cx - wo - 0.5);
			int xb = (int) Math.floor(cx + wo - 0.5) + 1;
			if (dy2 < i2) {
				double wi = Math.sqrt(i2 - dy2);
				int ia = (int) Math.ceil(cx - wi - 0.5);
				int ib = (int) Math.floor(cx + wi - 0.5) + 1;
				b.fill(xa / s, y / s, Math.min(ia, xb) / s, (y + 1) / s, argb);
				b.fill(Math.max(ib, xa) / s, y / s, xb / s, (y + 1) / s, argb);
			} else {
				b.fill(xa / s, y / s, xb / s, (y + 1) / s, argb);
			}
		}
	}

	// ---- Cloth list scroll ------------------------------------------------------------------

	private static double scrollOf(@Nullable Screen screen) {
		try {
			if (screen instanceof ClothConfigScreen cloth && cloth.listWidget != null) {
				return cloth.listWidget.getScroll();
			}
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.scrollOf", "Could not read the settings list position", t);
		}
		return Double.NaN;
	}

	/** Cloth builds a new list (scrolled to the top) whenever its screen is shown again; put the old position back. */
	private static void restoreScroll(@Nullable Screen screen, double scroll) {
		if (Double.isNaN(scroll)) {
			return;
		}
		try {
			if (screen instanceof ClothConfigScreen cloth && cloth.listWidget != null) {
				cloth.listWidget.scrollTo(scroll, false);
			}
		} catch (Throwable t) {
			LogLimiter.error("ColorPickerScreen.restoreScroll", "Could not restore the settings list position", t);
		}
	}

	private static String joinPlain(List<Component> path) {
		StringBuilder sb = new StringBuilder();
		for (Component c : path) {
			if (sb.length() > 0) {
				sb.append(SEP);
			}
			sb.append(c.getString());
		}
		return sb.length() == 0 ? "Colour" : sb.toString();
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	// ---- types ------------------------------------------------------------------------------

	private enum Drag {
		NONE, SQUARE, HUE, ALPHA
	}

	private enum Area {
		NONE, SQUARE, HUE, ALPHA, BEFORE, AFTER, HINT
	}

	private record Named(int argb, String name) {
	}

	/**
	 * Every position on the card, in GUI pixels. The square takes whatever height is left after the
	 * title and button rows (at most {@value #SQ_MAX}), the right column {@value #RIGHT_MIN} to
	 * {@value #RIGHT_MAX} px. Spare height in the right column first grows the preview (by up to
	 * {@value #PREVIEW_GROW} px), then is shared by the gaps between its sections; the swatches stay
	 * level with the bottom of the square. At 427 × 240 (854 × 480 at GUI 2) the card fills the screen
	 * with a 169 px square; at 480 × 270 and 640 × 360 (1920 × 1080 at GUI 4 and 3) the square is 199
	 * and 256 px. At the smallest GUI (320 × 240) the square shrinks to fit the width.
	 */
	private record Layout(int cardX, int cardY, int cardW, int cardH, int innerX, int innerY, int innerW,
			int sqX, int sqY, int sq, int hueX, int alphaX, int rightX, int rightW,
			int headerY, int previewY, int previewH, int sampleY, int hexY, int rgbaY,
			int palette1Y, int palette2Y, int recentY, int swatch, int swatchGap, int labelColW,
			int buttonsY, int buttonW, int hintW) {
		static final Layout EMPTY = new Layout(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0);

		static Layout compute(Font font, int width, int height) {
			int availW = Math.max(0, width - 2 * MARGIN - 2 * PAD);
			int availH = Math.max(0, height - 2 * MARGIN - 2 * PAD);
			int fixedW = GAP + STRIP_W + GAP + STRIP_W + COLUMN_GAP;
			int chromeH = TITLE_H + GAP + GAP + BUTTON_H;
			int sqByH = availH - chromeH;
			int rightW = clamp(availW - fixedW - sqByH, RIGHT_MIN, RIGHT_MAX);
			int sq = clamp(Math.min(sqByH, availW - fixedW - rightW), SQ_MIN, SQ_MAX);

			int labelColW = Math.max(font.width(PALETTE_LABEL), font.width(RECENT_LABEL)) + 5;
			int cell = clamp((rightW - labelColW + SWATCH_GAP) / SWATCH_COLUMNS, 11, 18);
			int swatch = cell - SWATCH_GAP;
			int fixedRightH = HEADER_H + 2 + PREVIEW_MIN + GAP + SAMPLE_H + GAP + FIELD_H + 4 + FIELD_H + GAP
					+ 3 * swatch + SWATCH_GAP + RECENT_GAP;
			int contentH = Math.max(sq, fixedRightH);
			int extra = contentH - fixedRightH;
			int grow = Math.min(extra, PREVIEW_GROW);
			int previewH = PREVIEW_MIN + grow;
			int spread = (extra - grow) / 3;

			int innerW = sq + fixedW + rightW;
			int innerH = TITLE_H + GAP + contentH + GAP + BUTTON_H;
			int cardW = innerW + 2 * PAD;
			int cardH = innerH + 2 * PAD;
			int cardX = (width - cardW) / 2;
			int cardY = (height - cardH) / 2;
			int innerX = cardX + PAD;
			int innerY = cardY + PAD;
			int contentY = innerY + TITLE_H + GAP;
			int hueX = innerX + sq + GAP;
			int alphaX = hueX + STRIP_W + GAP;
			int rightX = alphaX + STRIP_W + COLUMN_GAP;

			int headerY = contentY;
			int previewY = headerY + HEADER_H + 2;
			int sampleY = previewY + previewH + GAP + spread;
			int hexY = sampleY + SAMPLE_H + GAP + spread;
			int rgbaY = hexY + FIELD_H + 4;
			int recentY = contentY + contentH - swatch;
			int palette2Y = recentY - RECENT_GAP - swatch;
			int palette1Y = palette2Y - SWATCH_GAP - swatch;
			int buttonsY = contentY + contentH + GAP;
			int buttonW = Math.min(BUTTON_W, (innerW - 2 * GAP) / 3);
			int hintW = font.width(TEXT_HINT);
			return new Layout(cardX, cardY, cardW, cardH, innerX, innerY, innerW,
					innerX, contentY, sq, hueX, alphaX, rightX, rightW,
					headerY, previewY, previewH, sampleY, hexY, rgbaY,
					palette1Y, palette2Y, recentY, swatch, SWATCH_GAP, labelColW,
					buttonsY, buttonW, hintW);
		}
	}

	/**
	 * Collects axis-aligned quads (a flat colour or a vertical gradient) and submits them to the GUI as
	 * ONE element. 1.21.11's {@code GuiRenderState} places each submitted element by scanning the
	 * elements already placed, so the hundreds of small fills behind the square's columns, the
	 * checkerboards and the marker ring would make every frame slower; one element per batch keeps that
	 * cost flat. Quads are drawn in the order they were added. Coordinates are floats in the current
	 * pose's units, so physical-pixel shapes are fractions of a GUI pixel. Reusable; client thread.
	 */
	static final class QuadBatch implements Rects {
		private float[] xy = new float[256];
		private int[] colours = new int[128];
		private int count;
		private float minX;
		private float minY;
		private float maxX;
		private float maxY;

		@Override
		public void fill(int x0, int y0, int x1, int y1, int argb) {
			gradient(x0, y0, x1, y1, argb, argb);
		}

		void fill(float x0, float y0, float x1, float y1, int argb) {
			gradient(x0, y0, x1, y1, argb, argb);
		}

		/** A quad from {@code top} at {@code y0} to {@code bottom} at {@code y1}; skipped when empty or fully transparent. */
		void gradient(float x0, float y0, float x1, float y1, int top, int bottom) {
			if (!(x1 > x0) || !(y1 > y0) || ((top | bottom) >>> 24) == 0) {
				return;
			}
			if (this.count * 4 == this.xy.length) {
				this.xy = Arrays.copyOf(this.xy, this.xy.length * 2);
				this.colours = Arrays.copyOf(this.colours, this.colours.length * 2);
			}
			int i = this.count * 4;
			this.xy[i] = x0;
			this.xy[i + 1] = y0;
			this.xy[i + 2] = x1;
			this.xy[i + 3] = y1;
			this.colours[this.count * 2] = top;
			this.colours[this.count * 2 + 1] = bottom;
			if (this.count == 0) {
				this.minX = x0;
				this.minY = y0;
				this.maxX = x1;
				this.maxY = y1;
			} else {
				this.minX = Math.min(this.minX, x0);
				this.minY = Math.min(this.minY, y0);
				this.maxX = Math.max(this.maxX, x1);
				this.maxY = Math.max(this.maxY, y1);
			}
			this.count++;
		}

		/** Drops everything added since the last submit. */
		void clear() {
			this.count = 0;
		}

		/** Hands the collected quads to the GUI as one element (with the current pose and scissor) and empties the batch. */
		void submit(GuiGraphics g) {
			if (this.count == 0) {
				return;
			}
			try {
				Matrix3x2f pose = new Matrix3x2f(g.pose());
				int bx = (int) Math.floor(this.minX);
				int by = (int) Math.floor(this.minY);
				ScreenRectangle box = new ScreenRectangle(bx, by, (int) Math.ceil(this.maxX) - bx, (int) Math.ceil(this.maxY) - by)
						.transformMaxBounds(pose);
				ScreenRectangle scissor = g.scissorStack.peek();
				ScreenRectangle bounds = scissor == null ? box : scissor.intersection(box);
				if (bounds != null) {
					g.guiRenderState.submitGuiElement(new State(pose, Arrays.copyOf(this.xy, this.count * 4),
							Arrays.copyOf(this.colours, this.count * 2), scissor, bounds));
				}
			} finally {
				this.count = 0;
			}
		}

		/** The submitted, immutable copy: quads in order, top colour on the upper edge as vanilla's fillGradient does. */
		private record State(Matrix3x2f pose, float[] xy, int[] colours, @Nullable ScreenRectangle scissorArea,
				ScreenRectangle bounds) implements GuiElementRenderState {
			@Override
			public void buildVertices(VertexConsumer consumer) {
				for (int q = 0; q < this.colours.length / 2; q++) {
					float x0 = this.xy[q * 4];
					float y0 = this.xy[q * 4 + 1];
					float x1 = this.xy[q * 4 + 2];
					float y1 = this.xy[q * 4 + 3];
					int top = this.colours[q * 2];
					int bottom = this.colours[q * 2 + 1];
					consumer.addVertexWith2DPose(this.pose, x0, y0).setColor(top);
					consumer.addVertexWith2DPose(this.pose, x0, y1).setColor(bottom);
					consumer.addVertexWith2DPose(this.pose, x1, y1).setColor(bottom);
					consumer.addVertexWith2DPose(this.pose, x1, y0).setColor(top);
				}
			}

			@Override
			public RenderPipeline pipeline() {
				return RenderPipelines.GUI;
			}

			@Override
			public TextureSetup textureSetup() {
				return TextureSetup.noTexture();
			}
		}
	}

	/**
	 * A text field in KWAMSTER's look: an unbordered {@link EditBox} inside a dark rounded frame (accent
	 * border while focused) that takes clicks over its whole area. {@link #shift} names the channel it
	 * edits, or {@link #HEX} for the whole colour.
	 */
	private static final class Field extends EditBox {
		static final int HEX = -1;
		final int shift;
		private int frameX;
		private int frameY;
		private int frameW;
		private int frameH;

		Field(Font font, Component narration, int shift) {
			super(font, 0, 0, 10, 9, narration);
			this.shift = shift;
			setBordered(false);
			setTextShadow(false);
			setTextColor(VALUE);
		}

		void frame(int x, int y, int w, int h) {
			this.frameX = x;
			this.frameY = y;
			this.frameW = w;
			this.frameH = h;
			setX(x + 4);
			setY(y + (h - 8) / 2);
			setWidth(Math.max(1, w - 8));
			setHeight(9);
		}

		int frameRight() {
			return this.frameX + this.frameW;
		}

		@Override
		public boolean isMouseOver(double x, double y) {
			return isActive() && x >= this.frameX && y >= this.frameY && x < this.frameX + this.frameW && y < this.frameY + this.frameH;
		}

		@Override
		public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
			try {
				boolean hover = isMouseOver(mouseX, mouseY);
				int x1 = this.frameX + this.frameW;
				int y1 = this.frameY + this.frameH;
				roundRect(g::fill, this.frameX, this.frameY, x1, y1, FIELD_BG);
				border(g::fill, this.frameX, this.frameY, x1, y1, isFocused() ? ACCENT : hover ? BORDER_HOVER : BORDER);
				super.renderWidget(g, mouseX, mouseY, partialTick);
				if (hover) {
					g.requestCursor(CursorTypes.IBEAM);
				}
			} catch (Throwable t) {
				LogLimiter.error("ColorPickerScreen.Field", "Could not draw a colour field", t);
			}
		}
	}

	/** A flat rounded button in KWAMSTER's look; the primary one (Done) is filled with the accent. */
	private static final class FlatButton extends AbstractButton {
		private final boolean primary;
		private final Consumer<InputWithModifiers> action;

		FlatButton(Component label, boolean primary, Consumer<InputWithModifiers> action) {
			super(0, 0, BUTTON_W, BUTTON_H, label);
			this.primary = primary;
			this.action = action;
			setTooltipDelay(Duration.ofMillis(TOOLTIP_DELAY_MS));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			try {
				this.action.accept(input);
			} catch (Throwable t) {
				LogLimiter.error("ColorPickerScreen.FlatButton/" + getMessage().getString(), "Colour picker button failed", t);
			}
		}

		/** A mouse click does not focus the button, so Enter keeps meaning Done afterwards. */
		@Override
		public boolean shouldTakeFocusAfterInteraction() {
			return false;
		}

		@Override
		protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
			try {
				int x0 = getX();
				int y0 = getY();
				int x1 = x0 + getWidth();
				int y1 = y0 + getHeight();
				boolean hot = this.active && isHoveredOrFocused();
				int bg;
				if (this.primary && this.active) {
					bg = hot ? ACCENT_HOVER : ACCENT;
				} else {
					bg = hot ? BUTTON_BG_HOVER : BUTTON_BG;
				}
				roundRect(g::fill, x0, y0, x1, y1, bg);
				int edge = isFocused() ? (this.primary ? WHITE : ACCENT) : hot ? BORDER_HOVER : BORDER;
				border(g::fill, x0, y0, x1, y1, edge);
				Font font = Minecraft.getInstance().font;
				Component label = getMessage();
				int textColour = !this.active ? DIM : this.primary ? WHITE : VALUE;
				int tx = x0 + (getWidth() - Math.max(0, font.width(label) - 1) + 1) / 2;
				g.drawString(font, label, tx, y0 + (getHeight() - 8) / 2, textColour, false);
			} catch (Throwable t) {
				LogLimiter.error("ColorPickerScreen.FlatButton", "Could not draw a colour picker button", t);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}

	/** One palette or recent swatch: click (or Enter while focused) to pick its colour. */
	private final class SwatchButton extends AbstractButton {
		private final int swatchColour;

		SwatchButton(int colour, String name) {
			super(0, 0, 10, 10, Component.literal(name + " " + ColorMath.toHex(colour)));
			this.swatchColour = colour;
			setTooltip(Tooltip.create(Component.literal(name + "  " + ColorMath.toHex(colour))));
			setTooltipDelay(Duration.ofMillis(SWATCH_TOOLTIP_DELAY_MS));
		}

		@Override
		public void onPress(InputWithModifiers input) {
			try {
				if (input instanceof MouseButtonEvent) {
					// a click ends any typing (the field then shows the swatch); keyboard focus stays put
					ColorPickerScreen.this.setFocused(null);
				}
				applyArgb(this.swatchColour, null);
			} catch (Throwable t) {
				LogLimiter.error("ColorPickerScreen.SwatchButton", "Could not pick a swatch", t);
			}
		}

		/** A mouse click does not focus the swatch, so Enter keeps meaning Done afterwards. */
		@Override
		public boolean shouldTakeFocusAfterInteraction() {
			return false;
		}

		@Override
		protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
			QuadBatch b = ColorPickerScreen.this.batch;
			try {
				int x0 = getX();
				int y0 = getY();
				int x1 = x0 + getWidth();
				int y1 = y0 + getHeight();
				checker(b, x0, y0, x1, y1, CHECK, true);
				roundRect(b, x0, y0, x1, y1, this.swatchColour);
				border(b, x0, y0, x1, y1, BORDER);
				boolean hot = isHoveredOrFocused();
				boolean selected = this.swatchColour == ColorPickerScreen.this.argb;
				if (hot || selected) {
					outline(b, x0 - 2, y0 - 2, x1 + 2, y1 + 2, hot ? ACCENT : WHITE);
				}
				b.submit(g);
			} catch (Throwable t) {
				b.clear();
				LogLimiter.error("ColorPickerScreen.SwatchButton", "Could not draw a swatch", t);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}
	}
}
