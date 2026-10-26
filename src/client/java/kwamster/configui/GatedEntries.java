package kwamster.configui;

import kwamster.core.LogLimiter;
import me.shedaniel.clothconfig2.api.AbstractConfigEntry;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.AbstractConfigScreen;
import me.shedaniel.clothconfig2.gui.entries.ColorEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import me.shedaniel.clothconfig2.gui.entries.SubCategoryListEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Cloth text entries whose built-in validation pauses while a requirement disables them.
 *
 * <p>Cloth 21.11's {@code AbstractConfigEntry.getConfigError()} never consults {@code isEnabled()}: it
 * falls back to the entry's own {@code getError()} (a malformed {@code #AARRGGBB}, a number out of
 * range), and the Save button stays at "Cannot save" while any entry has an error. A disabled entry
 * cannot be edited or reset, though, so bad text typed before its controlling toggle was switched off
 * would block Save with no way to fix it. These subclasses report no error while {@code gate} fails
 * (evaluated directly, so it is current even for entries Cloth has not ticked yet), and they never save
 * a value that fails validation: the live config keeps its previous value instead. Builder-made entries
 * (string fields, dropdowns) get the same behaviour from a gated error supplier and a checking save
 * consumer in {@link ConfigScreenBuilder}.
 *
 * <p>Colour entries also open the {@link ColorPickerScreen}: the 20 px swatch {@code ColorEntry} draws
 * left of the hex field is covered by a {@link PickSwatch} button (the colour over a checkerboard, an
 * accent ring, a hand cursor and a "Click to pick a colour" tooltip on hover, and a small corner fold so
 * it reads as clickable at rest). It sits in the entry's widget list, so Cloth routes clicks, Tab focus
 * and narration to it like to the text field. Done in the picker writes the colour into the hex field
 * with {@code ColorEntry.setValue(int)}, which makes it a pending edit exactly like typing it: the
 * screen shows it as edited, Save applies it, Cancel discards it and the reset button still works. A
 * greyed-out entry never opens the picker.
 *
 * <p>Cloth has no builder hook for a custom entry class, so these construct the entries the way
 * {@code ColorFieldBuilder.build()} and {@code IntFieldBuilder.build()} do.
 */
final class GatedEntries {
	/** Size of {@code ColorEntry}'s swatch ({@code ColorDisplayWidget}, 20 × 20) and its gap to the hex field. */
	private static final int SWATCH = 20;
	private static final int SWATCH_GAP = 3;
	private static final int SWATCH_CHECK = 3;
	/** Cloth's own swatch frame colours: grey, white while the hex field is focused. */
	private static final int FRAME = 0xFFA0A0A0;
	private static final int FRAME_FOCUSED = 0xFFFFFFFF;
	private static final int FRAME_DISABLED = 0xFF4A4A4A;
	private static final int ACCENT = 0xFF8B5CF6;
	private static final int ACCENT_GLOW = 0x738B5CF6;
	private static final int INVALID = 0xFFFF5A5F;
	private static final int DISABLED_VEIL = 0x8C101010;
	private static final int FOLD = 5;
	private static final int FOLD_EDGE = 0x99000000;
	private static final int FOLD_REST = 0xD9FFFFFF;
	private static final int FOLD_HOT = 0xFFFFFFFF;
	private static final Component PICK_LABEL = Component.literal("Pick a colour");
	private static final Component PICK_TOOLTIP = Component.literal("Click to pick a colour");

	private GatedEntries() {
	}

	/** True while {@code gate} allows editing (no gate: always). */
	static boolean active(@Nullable Requirement gate) {
		return gate == null || gate.check();
	}

	/**
	 * An alpha colour field ({@code #AARRGGBB}), disabled while {@code gate} fails. A {@code text}
	 * colour must also keep an alpha of at least {@code KwamsterConfig.MIN_TEXT_ALPHA} (§3).
	 */
	static ColorEntry colour(ConfigEntryBuilder eb, Component name, int value, int def, Component tooltip,
			@Nullable Requirement gate, boolean text, IntConsumer save) {
		Colour entry = new Colour(name, value, eb.getResetButtonKey(), def, tooltip(tooltip), gate, text, save);
		entry.withAlpha();
		entry.setRequirement(gate);
		return entry;
	}

	/** An int text field limited to {@code min..max}, disabled while {@code gate} fails. */
	static IntegerListEntry intField(ConfigEntryBuilder eb, Component name, int value, int def, int min, int max,
			Component tooltip, @Nullable Requirement gate, IntConsumer save) {
		Int entry = new Int(name, value, eb.getResetButtonKey(), def, tooltip(tooltip), gate, save);
		entry.setMinimum(min);
		entry.setMaximum(max);
		entry.setRequirement(gate);
		return entry;
	}

	private static Supplier<Optional<Component[]>> tooltip(Component tooltip) {
		Optional<Component[]> value = Optional.of(new Component[]{tooltip});
		return () -> value;
	}

	/**
	 * {@link ColorEntry} with a gated error, a save that skips malformed or too-transparent text, and a
	 * swatch that opens the colour picker.
	 */
	private static final class Colour extends ColorEntry {
		private final @Nullable Requirement gate;
		private final boolean text;
		private final PickSwatch pick;

		// Cloth marks entry constructors @Deprecated @ApiStatus.Internal to steer users towards its
		// builders; the builders cannot create a subclass, so this goes through the constructor directly.
		@SuppressWarnings("deprecation")
		Colour(Component name, int value, Component resetKey, int def, Supplier<Optional<Component[]>> tooltip,
				@Nullable Requirement gate, boolean text, IntConsumer save) {
			super(name, value, resetKey, () -> def, v -> save.accept(v), tooltip, false);
			this.gate = gate;
			this.text = text;
			this.pick = new PickSwatch(this);
			// children() and narratables() are this list: Cloth now routes clicks, focus and narration here too
			this.widgets.add(this.pick);
		}

		@Override
		public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
				int mouseX, int mouseY, boolean isHovered, float delta) {
			super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
			try {
				// drawn over Cloth's own swatch, at the place ColorEntry.render just put it
				this.pick.active = canPick();
				this.pick.setPosition(swatchX(), y);
				this.pick.render(graphics, mouseX, mouseY, delta);
			} catch (Throwable t) {
				LogLimiter.error("GatedEntries.Colour.render", "Could not draw the colour swatch", t);
			}
		}

		/** ColorEntry's swatch x: 23 px left of the hex field, or right after it for a right-to-left font. */
		private int swatchX() {
			return Minecraft.getInstance().font.isBidirectional()
					? this.textFieldWidget.getX() + this.textFieldWidget.getWidth()
					: this.textFieldWidget.getX() - SWATCH - SWATCH_GAP;
		}

		/** Over the swatch the tooltip says what a click does; elsewhere it is the entry's own. */
		@Override
		public Optional<Component[]> getTooltip(int mouseX, int mouseY) {
			// pick is null only while the super constructor runs
			if (this.pick != null && this.pick.isMouseOver(mouseX, mouseY)) {
				return Optional.of(new Component[]{PICK_TOOLTIP});
			}
			return super.getTooltip(mouseX, mouseY);
		}

		/** True while the entry can be edited: the screen allows edits and the requirement passes now. */
		boolean canPick() {
			try {
				return isEditable() && active(this.gate);
			} catch (Throwable t) {
				return false;
			}
		}

		/** The colour the hex field holds, when Cloth can parse it (empty while the text is malformed). */
		OptionalInt shownColour() {
			return isValidColorString(this.textFieldWidget.getValue()) ? OptionalInt.of(getValue()) : OptionalInt.empty();
		}

		boolean fieldFocused() {
			return this.textFieldWidget.isFocused();
		}

		/**
		 * Opens the picker on the field's colour (the value the screen opened with while the text is
		 * malformed), with this setting's default for its Default button. Nothing happens while the entry
		 * is greyed out.
		 */
		void openPicker() {
			if (!canPick()) {
				return;
			}
			try {
				Minecraft mc = Minecraft.getInstance();
				Screen parent = getConfigScreen();
				if (parent == null) {
					parent = mc.screen;
				}
				int start = shownColour().orElse(this.original);
				int def = getDefaultValue().orElse(start);
				mc.setScreen(new ColorPickerScreen(parent, path(parent), start, def, this.text, this::picked));
			} catch (Throwable t) {
				LogLimiter.error("GatedEntries.openPicker", "Could not open the colour picker", t);
			}
		}

		/**
		 * Done in the picker: the colour goes into the hex field, a pending edit exactly like typed text.
		 * This is the body of {@code ColorEntry.setValue(int)}, which Cloth 21.11 marks deprecated without
		 * a replacement.
		 */
		private void picked(int argb) {
			OptionalInt now = shownColour();
			if (now.isEmpty() || now.getAsInt() != argb) {
				this.textFieldWidget.setValue(getHexColorString(argb));
			}
		}

		/** Category, subcategories and this entry's name, for the picker's title ("Hit feedback · ■ Crit · Colour"). */
		private List<Component> path(@Nullable Screen screen) {
			List<Component> path = new ArrayList<>();
			try {
				if (screen instanceof AbstractConfigScreen config) {
					for (Map.Entry<Component, List<AbstractConfigEntry<?>>> category : config.getCategorizedEntries().entrySet()) {
						List<Component> trail = new ArrayList<>();
						if (contains(category.getValue(), trail)) {
							path.add(category.getKey());
							path.addAll(trail);
							break;
						}
					}
				}
			} catch (Throwable t) {
				path.clear();
				LogLimiter.error("GatedEntries.path", "Could not find the colour entry's category", t);
			}
			path.add(getFieldName());
			return path;
		}

		/** Depth-first search for this entry; {@code trail} collects the names of the subcategories on the way down. */
		private boolean contains(Iterable<?> entries, List<Component> trail) {
			for (Object e : entries) {
				if (e == this) {
					return true;
				}
				if (e instanceof SubCategoryListEntry sub) {
					trail.add(sub.getFieldName());
					if (contains(sub.getValue(), trail)) {
						return true;
					}
					trail.remove(trail.size() - 1);
				}
			}
			return false;
		}

		@Override
		public Optional<Component> getError() {
			// gate is null while the super constructor runs: validate as an enabled entry would.
			return active(this.gate) ? validationError() : Optional.empty();
		}

		/** Cloth's parse error, then the text-alpha rule; independent of the gate. */
		private Optional<Component> validationError() {
			Optional<Component> parse = super.getError();
			if (parse.isPresent() || !this.text || EntryChecks.textAlphaOk(getValue())) {
				return parse;
			}
			return Optional.of(Component.literal("Alpha must be at least 20 (hex), or the text would be invisible"));
		}

		@Override
		public void save() {
			// Reached with bad text only while disabled (an enabled error blocks Save): keep the live value.
			if (validationError().isEmpty()) {
				super.save();
			}
		}
	}

	/** {@link IntegerListEntry} with a gated error and a save that skips unparsable or out-of-range text. */
	private static final class Int extends IntegerListEntry {
		private final @Nullable Requirement gate;

		@SuppressWarnings("deprecation")
		Int(Component name, int value, Component resetKey, int def, Supplier<Optional<Component[]>> tooltip,
				@Nullable Requirement gate, IntConsumer save) {
			super(name, value, resetKey, () -> def, v -> save.accept(v), tooltip, false);
			this.gate = gate;
		}

		@Override
		public Optional<Component> getError() {
			return active(this.gate) ? super.getError() : Optional.empty();
		}

		@Override
		public void save() {
			// IntegerListEntry.getValue() turns unparsable text into 0, which would mean "unlimited".
			if (super.getError().isEmpty()) {
				super.save();
			}
		}
	}

	/**
	 * A colour entry's swatch as a button. It shows the hex field's colour over a checkerboard (so alpha
	 * is visible; Cloth's swatch blends over white) or a red cross while the text is malformed, keeps
	 * Cloth's grey/white frame at rest, and gets an accent ring on hover or keyboard focus. A small corner
	 * fold marks it as clickable. Inactive (greyed, dimmed) while the entry cannot be edited.
	 */
	private static final class PickSwatch extends AbstractButton {
		/** Shared by every swatch (client thread): each draws into it and submits it as one GUI element. */
		private static final ColorPickerScreen.QuadBatch BATCH = new ColorPickerScreen.QuadBatch();
		private final Colour entry;

		PickSwatch(Colour entry) {
			super(0, 0, SWATCH, SWATCH, PICK_LABEL);
			this.entry = entry;
		}

		@Override
		public void onPress(InputWithModifiers input) {
			this.entry.openPicker();
		}

		@Override
		protected void renderContents(GuiGraphics g, int mouseX, int mouseY, float delta) {
			ColorPickerScreen.QuadBatch b = BATCH;
			try {
				int x0 = getX();
				int y0 = getY();
				int x1 = x0 + getWidth();
				int y1 = y0 + getHeight();
				boolean hot = this.active && isHoveredOrFocused();
				if (hot) {
					ColorPickerScreen.outline(b, x0 - 1, y0 - 1, x1 + 1, y1 + 1, ACCENT_GLOW);
				}
				int frame = hot ? ACCENT : !this.active ? FRAME_DISABLED : this.entry.fieldFocused() ? FRAME_FOCUSED : FRAME;
				ColorPickerScreen.fill(b, x0, y0, x1, y1, frame);
				ColorPickerScreen.checker(b, x0 + 1, y0 + 1, x1 - 1, y1 - 1, SWATCH_CHECK, false);
				OptionalInt colour = this.entry.shownColour();
				if (colour.isPresent()) {
					ColorPickerScreen.fill(b, x0 + 1, y0 + 1, x1 - 1, y1 - 1, colour.getAsInt());
				} else {
					cross(b, x0 + 1, y0 + 1, x1 - 1, y1 - 1, INVALID);
				}
				if (this.active) {
					fold(b, x1 - 1, y1 - 1, FOLD + 1, FOLD_EDGE);
					fold(b, x1 - 1, y1 - 1, FOLD, hot ? FOLD_HOT : FOLD_REST);
				} else {
					ColorPickerScreen.fill(b, x0 + 1, y0 + 1, x1 - 1, y1 - 1, DISABLED_VEIL);
				}
				b.submit(g);
			} catch (Throwable t) {
				b.clear();
				LogLimiter.error("GatedEntries.PickSwatch", "Could not draw the colour swatch", t);
			}
		}

		@Override
		protected void updateWidgetNarration(NarrationElementOutput output) {
			defaultButtonNarrationText(output);
		}

		/** Both diagonals of the square at {@code (x0, y0)}: the "no valid colour" mark. */
		private static void cross(ColorPickerScreen.Rects r, int x0, int y0, int x1, int y1, int argb) {
			int n = Math.min(x1 - x0, y1 - y0);
			for (int i = 0; i < n; i++) {
				ColorPickerScreen.fill(r, x0 + i, y0 + i, x0 + i + 1, y0 + i + 1, argb);
				ColorPickerScreen.fill(r, x0 + n - 1 - i, y0 + i, x0 + n - i, y0 + i + 1, argb);
			}
		}

		/** A right triangle with {@code size} px legs in the corner left of {@code right} and above {@code bottom}. */
		private static void fold(ColorPickerScreen.Rects r, int right, int bottom, int size, int argb) {
			for (int i = 0; i < size; i++) {
				ColorPickerScreen.fill(r, right - (size - i), bottom - 1 - i, right, bottom - i, argb);
			}
		}
	}
}
