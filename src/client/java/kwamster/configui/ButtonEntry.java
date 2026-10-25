package kwamster.configui;

import kwamster.core.LogLimiter;
import me.shedaniel.clothconfig2.gui.entries.TooltipListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * A clickable button as a Cloth Config list entry (port of combat-tracker's {@code ButtonEntry}).
 * Cloth ships entries for values (toggles, sliders, dropdowns) but none for plain actions, so the
 * settings screen's actions ("Edit HUD layout…", "▶ Test", "Reset lifetime stats", …) use this.
 *
 * <p>Two layouts:
 * <ul>
 *   <li>{@link #wide}: a centred button up to {@value #WIDE_MAX_WIDTH} px wide, labelled by its
 *       action (combat-tracker's layout).</li>
 *   <li>{@link #inline}: a field name on the left and a {@value #INLINE_WIDTH} px button in Cloth's
 *       widget column, so it lines up with the value entries around it (used for the tier "Test").</li>
 * </ul>
 *
 * <p>The press handler receives the entry itself, so it can relabel the button permanently
 * ({@link #setLabel}) or for a moment ({@link #flash}); that is how confirmations and results are
 * shown. A handler that throws is logged (rate-limited) and never reaches the screen. The entry
 * carries no config value: there is nothing to save or reset, and Cloth requirements disable it
 * like any other entry ({@code isEditable()}).
 */
public final class ButtonEntry extends TooltipListEntry<Object> {
	/** Widest a {@link #wide} button gets; a min() against the row, so narrow screens are unaffected. */
	public static final int WIDE_MAX_WIDTH = 260;
	/** Cloth's value-widget column width (BooleanListEntry and friends). */
	public static final int INLINE_WIDTH = 150;
	private static final int HEIGHT = 20;

	private final Button button;
	private final boolean inline;
	private Component label;
	private long flashUntilMs;

	// Cloth marks every entry constructor @Deprecated @ApiStatus.Internal to steer users towards its
	// builders; it has no builder for custom entries, so subclassing through it is the only way.
	@SuppressWarnings("deprecation")
	private ButtonEntry(Component fieldName, Component label, @Nullable Component tooltip, boolean inline, Consumer<ButtonEntry> onPress) {
		super(fieldName, tooltipSupplier(tooltip), false);
		Objects.requireNonNull(onPress, "onPress");
		this.inline = inline;
		this.label = label;
		this.button = Button.builder(label, b -> press(onPress))
				.bounds(0, 0, inline ? INLINE_WIDTH : WIDE_MAX_WIDTH, HEIGHT)
				.build();
	}

	/** A centred action button labelled {@code label}. */
	public static ButtonEntry wide(Component label, @Nullable Component tooltip, Consumer<ButtonEntry> onPress) {
		return new ButtonEntry(label, label, tooltip, false, onPress);
	}

	/** A field name on the left and a button labelled {@code buttonLabel} in the widget column. */
	public static ButtonEntry inline(Component fieldName, Component buttonLabel, @Nullable Component tooltip, Consumer<ButtonEntry> onPress) {
		return new ButtonEntry(fieldName, buttonLabel, tooltip, true, onPress);
	}

	/** The button's resting label (shown whenever no {@link #flash} is running). */
	public Component label() {
		return this.label;
	}

	/** Changes the resting label and cancels any running flash. */
	public void setLabel(Component newLabel) {
		this.label = Objects.requireNonNull(newLabel, "newLabel");
		this.flashUntilMs = 0L;
		this.button.setMessage(newLabel);
	}

	/** Shows {@code message} for {@code durationMs}, then the resting label again. */
	public void flash(Component message, int durationMs) {
		this.flashUntilMs = Util.getMillis() + Math.max(1, durationMs);
		this.button.setMessage(Objects.requireNonNull(message, "message"));
	}

	/** True while a {@link #flash} message is showing. */
	public boolean isFlashing() {
		return this.flashUntilMs != 0L && Util.getMillis() < this.flashUntilMs;
	}

	private void press(Consumer<ButtonEntry> onPress) {
		try {
			onPress.accept(this);
		} catch (Throwable t) {
			LogLimiter.error("ButtonEntry/" + this.label.getString(), "Settings button action failed", t);
			flash(Component.literal("Failed (see log)").withStyle(ChatFormatting.RED), 2000);
		}
	}

	private void expireFlash() {
		if (this.flashUntilMs != 0L && Util.getMillis() >= this.flashUntilMs) {
			this.flashUntilMs = 0L;
			this.button.setMessage(this.label);
		}
	}

	private static Supplier<Optional<Component[]>> tooltipSupplier(@Nullable Component tooltip) {
		Optional<Component[]> value = tooltip == null ? Optional.empty() : Optional.of(new Component[]{tooltip});
		return () -> value;
	}

	@Override
	public void render(GuiGraphics graphics, int index, int y, int x, int entryWidth, int entryHeight,
			int mouseX, int mouseY, boolean isHovered, float delta) {
		super.render(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta);
		expireFlash();
		this.button.active = isEditable();
		if (this.inline) {
			Font font = Minecraft.getInstance().font;
			Component name = getDisplayedFieldName();
			int width = Math.min(INLINE_WIDTH, entryWidth);
			if (font.isBidirectional()) {
				graphics.drawString(font, name, x + entryWidth - font.width(name), y + 6, getPreferredTextColor());
				this.button.setX(x);
			} else {
				graphics.drawString(font, name, x, y + 6, getPreferredTextColor());
				this.button.setX(x + entryWidth - width);
			}
			this.button.setWidth(width);
		} else {
			int width = Math.min(entryWidth, WIDE_MAX_WIDTH);
			this.button.setX(x + (entryWidth - width) / 2);
			this.button.setWidth(width);
		}
		this.button.setY(y);
		this.button.render(graphics, mouseX, mouseY, delta);
	}

	@Override
	public int getItemHeight() {
		return HEIGHT + 4;
	}

	/** Buttons carry no config value. */
	@Override
	public @Nullable Object getValue() {
		return null;
	}

	@Override
	public Optional<Object> getDefaultValue() {
		return Optional.empty();
	}

	/** Nothing to save: actions run when clicked. */
	@Override
	public void save() {
	}

	@Override
	public List<? extends GuiEventListener> children() {
		return List.of(this.button);
	}

	@Override
	public List<? extends NarratableEntry> narratables() {
		return List.of(this.button);
	}
}
