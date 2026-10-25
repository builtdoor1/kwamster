package kwamster.configui;

import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.ColorEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.Optional;
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
 * <p>Cloth has no builder hook for a custom entry class, so these construct the entries the way
 * {@code ColorFieldBuilder.build()} and {@code IntFieldBuilder.build()} do.
 */
final class GatedEntries {
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

	/** {@link ColorEntry} with a gated error and a save that skips malformed or too-transparent text. */
	private static final class Colour extends ColorEntry {
		private final @Nullable Requirement gate;
		private final boolean text;

		// Cloth marks entry constructors @Deprecated @ApiStatus.Internal to steer users towards its
		// builders; the builders cannot create a subclass, so this goes through the constructor directly.
		@SuppressWarnings("deprecation")
		Colour(Component name, int value, Component resetKey, int def, Supplier<Optional<Component[]>> tooltip,
				@Nullable Requirement gate, boolean text, IntConsumer save) {
			super(name, value, resetKey, () -> def, v -> save.accept(v), tooltip, false);
			this.gate = gate;
			this.text = text;
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
}
