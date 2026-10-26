package kwamster.core.config;

import kwamster.core.config.KwamsterConfig.CrosshairShape;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ObjIntConsumer;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Ready-made looks for the HUD (§7): one click restyles the stats panel, the hit indicator, the combo
 * counter, the summary card, the custom crosshair and the above-head direction bars. The HUD unit's
 * presets screen previews each one live before it is applied. Minecraft-free (R6).
 *
 * <p><b>A look is a fixed set of fields.</b> Every preset writes exactly the same <i>look fields</i>
 * ({@link #fieldPaths()}) and nothing else, so applying one preset after another always ends on exactly
 * the second look, and {@link #matching} can tell which preset, if any, a config shows (the "current"
 * badge). A look field a preset does not style keeps its shipped default, which is why
 * {@link #KWAMSTER} restores the default look. The look fields are:
 * <ul>
 *   <li>{@code hud}: the five colours, {@code panelCompact}, the 14 {@code rows}, the combo counter
 *       switch and threshold, and {@code valueFlash};</li>
 *   <li>{@code feedback}: the hit indicator's switch, duration, reach and misses;</li>
 *   <li>{@code alert}: the card's duration, fade and footer hint;</li>
 *   <li>{@code crosshair}: shape, size, gap, thickness, outline and its colour, centre dot, hit flash
 *       and its length, and the four state colours;</li>
 *   <li>{@code direction}: the bar colours, backdrop, width and height.</li>
 * </ul>
 *
 * <p><b>Never touched:</b> detection settings, sounds, chat, recording, the hit tiers, the master switch
 * and every feature switch except two (the combo counter and the hit indicator, which only
 * {@link #MINIMAL} turns off and every other preset turns back on), HUD positions and scales,
 * {@code crosshair.scaleWithGui} (it adapts the sizes to the monitor, not to a style) and
 * {@code hud.panelWhenIdle} (it picks what the panel shows between fights). In every preset the four
 * crosshair state colours stay clearly apart, and stepped values sit on their Cloth slider steps, so the
 * settings screen shows them exactly.
 */
public enum HudPreset {
	/** The shipped defaults (§3, §7.0). */
	KWAMSTER("KWAMSTER", "The shipped look: violet accent on dark glass, outlined cross.", c -> {
	}),
	/** Airy, borderless, lower-contrast glass with the five core rows and a thin crosshair. */
	CLEAN("Clean", "Airy borderless glass, five rows and a thin, quiet crosshair.", HudPreset::clean),
	/** Hits, Reach and Combo only, no combo counter, a short indicator and a small dot. */
	MINIMAL("Minimal", "Hits, Reach and Combo only; no combo counter, a short pill, a dot.", HudPreset::minimal),
	/** A dense, high-contrast compact panel with the rows PvP players track and a crisp cross. */
	COMPETITIVE("Competitive", "Dense high-contrast panel, ten tracked stats, crisp outlined cross.", HudPreset::competitive),
	/** All 14 rows in a compact panel on the KWAMSTER colours. */
	FULL_STATS("Full stats", "All 14 panel rows in a compact panel, on the KWAMSTER colours.", HudPreset::fullStats),
	/** Vivid magenta and cyan on deep indigo, with a bright crosshair. */
	NEON("Neon", "Magenta and cyan on deep indigo with a bright cyan crosshair.", HudPreset::neon),
	/** Greyscale glass and text with a white ring crosshair; state and stat colours keep their meaning. */
	MONOCHROME("Monochrome", "Greyscale glass and text with a white ring crosshair.", HudPreset::monochrome),
	/** Minecraft's own text colours on grey, with a vanilla-style cross. */
	CLASSIC("Classic", "Minecraft colours: yellow labels, white values, a plain cross.", HudPreset::classic);

	private final String displayName;
	private final String description;
	/**
	 * This preset's value for every look field: a fresh default config with the preset's style written
	 * over it. Never handed out ({@link #sample()} copies it onto a new config).
	 */
	private final KwamsterConfig target;

	HudPreset(String displayName, String description, Consumer<KwamsterConfig> style) {
		this.displayName = displayName;
		this.description = description;
		// Runs during enum initialisation: the style methods use only their argument, no static field here.
		KwamsterConfig t = new KwamsterConfig();
		style.accept(t);
		this.target = t;
	}

	/** The name shown on the preset's card, for example "Clean". */
	public String displayName() {
		return this.displayName;
	}

	/** One line that says what the look is. */
	public String description() {
		return this.description;
	}

	/**
	 * Writes this look onto {@code cfg}: every look field, nothing else. A config with a missing section
	 * (one that never went through {@code clampAndFill()}) is filled first. Does not save.
	 */
	public void apply(KwamsterConfig cfg) {
		Objects.requireNonNull(cfg, "cfg");
		if (!filled(cfg)) {
			cfg.clampAndFill();
		}
		for (LookField f : FIELDS) {
			f.copy(this.target, cfg);
		}
	}

	/** True when every look field of {@code cfg} equals this preset's (the other fields do not matter). */
	public boolean matches(@Nullable KwamsterConfig cfg) {
		if (cfg == null || !filled(cfg)) {
			return false;
		}
		for (LookField f : FIELDS) {
			if (!f.same(this.target, cfg)) {
				return false;
			}
		}
		return true;
	}

	/** A new config: the defaults with this look applied, for previews and colour chips. */
	public KwamsterConfig sample() {
		KwamsterConfig c = KwamsterConfig.defaults();
		apply(c);
		return c;
	}

	/** The preset whose look {@code cfg} shows, or null for a custom look. */
	public static @Nullable HudPreset matching(@Nullable KwamsterConfig cfg) {
		for (HudPreset p : values()) {
			if (p.matches(cfg)) {
				return p;
			}
		}
		return null;
	}

	/** The preset with this enum name or display name (any case, trimmed), or null. */
	public static @Nullable HudPreset fromName(@Nullable String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		String wanted = name.trim();
		for (HudPreset p : values()) {
			if (p.name().equalsIgnoreCase(wanted) || p.displayName.equalsIgnoreCase(wanted)
					|| p.name().equals(wanted.toUpperCase(Locale.ROOT).replace(' ', '_'))) {
				return p;
			}
		}
		return null;
	}

	/**
	 * Every look field as a {@code section.field} path of {@code KwamsterConfig} (rows as
	 * {@code hud.rows.<row>}), in the order {@link #apply} writes them. For tests and documentation.
	 */
	public static List<String> fieldPaths() {
		List<String> paths = new ArrayList<>(FIELDS.size());
		for (LookField f : FIELDS) {
			paths.add(f.path());
		}
		return Collections.unmodifiableList(paths);
	}

	private static boolean filled(KwamsterConfig c) {
		return c.hud != null && c.hud.rows != null && c.feedback != null && c.alert != null
				&& c.crosshair != null && c.direction != null;
	}

	// ---- the presets -----------------------------------------------------------------------
	// Each method writes over a fresh default config; whatever it leaves alone stays the default.
	// Stepped values sit on their Cloth slider steps: indicator duration and card fade 50 ms, card time 100 ms.

	private static void clean(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		onlyRows(c, Row.HITS, Row.REACH, Row.COMBO, Row.THREE_BLOCK, Row.RESETS);
		h.comboCounterMin = 3;
		h.valueFlash = false;
		h.accentArgb = 0xFF93C5FD;
		h.panelBgArgb = 0x80101318;
		h.borderArgb = 0x00000000;
		h.textArgb = 0xFFF8FAFC;
		h.labelArgb = 0xFFB8C2CF;

		c.feedback.indicatorDurationMs = 700;
		c.feedback.indicatorShowMisses = false;

		c.alert.cardDurationMs = 6000;
		c.alert.cardFadeMs = 600;
		c.alert.cardFooterHint = false;

		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.CROSS;
		x.size = 5;
		x.gap = 3;
		x.thickness = 1;
		x.outline = 1;
		x.outlineArgb = 0x73000000;
		x.hitFlashMs = 120;
		x.onPlayerArgb = 0xFFFF7070;
		x.critReadyArgb = 0xFF67D4FF;
		x.critOnPlayerArgb = 0xFFFF85DC;

		KwamsterConfig.Direction d = c.direction;
		d.forwardArgb = 0xD94CD964;
		d.backwardArgb = 0xD9E03131;
		d.idleArgb = 0xD9FFD43B;
		d.backdropArgb = 0x59000000;
		d.barWidth = 50;
		d.barHeight = 8;
	}

	private static void minimal(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		h.panelCompact = true;
		onlyRows(c, Row.HITS, Row.REACH, Row.COMBO);
		h.comboCounterEnabled = false;
		h.valueFlash = false;
		h.panelBgArgb = 0x99000000;
		h.borderArgb = 0x00000000;
		h.textArgb = 0xFFFFFFFF;
		h.labelArgb = 0xFFA3A3A3;

		c.feedback.indicatorDurationMs = 500;
		c.feedback.indicatorShowReach = false;
		c.feedback.indicatorShowMisses = false;

		c.alert.cardDurationMs = 4000;
		c.alert.cardFadeMs = 300;
		c.alert.cardFooterHint = false;

		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.DOT;
		x.size = 4;
		x.gap = 0;
		x.thickness = 1;
		x.outline = 1;
		x.outlineArgb = 0x99000000;
		x.hitFlashMs = 120;

		KwamsterConfig.Direction d = c.direction;
		d.backdropArgb = 0x73000000;
		d.barWidth = 45;
		d.barHeight = 8;
	}

	private static void competitive(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		h.panelCompact = true;
		onlyRows(c, Row.HITS, Row.REACH, Row.COMBO, Row.STREAK, Row.CRITS, Row.SPRINT, Row.THREE_BLOCK,
				Row.RESETS, Row.INTERVAL, Row.ADVANTAGE);
		h.accentArgb = 0xFF22D3EE;
		h.panelBgArgb = 0xE6080A0E;
		h.borderArgb = 0x40FFFFFF;
		h.textArgb = 0xFFFFFFFF;
		h.labelArgb = 0xFFB6C0CE;

		c.feedback.indicatorDurationMs = 700;

		c.alert.cardDurationMs = 5000;
		c.alert.cardFadeMs = 250;

		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.CROSS;
		x.size = 5;
		x.gap = 2;
		x.thickness = 2;
		x.outline = 1;
		x.outlineArgb = 0xFF000000;
		x.hitFlashMs = 120;
		x.onPlayerArgb = 0xFFFF3B3B;
		x.critReadyArgb = 0xFF22D3EE;
		x.critOnPlayerArgb = 0xFFFF4FD8;

		KwamsterConfig.Direction d = c.direction;
		d.forwardArgb = 0xFF4CD964;
		d.backwardArgb = 0xFFE03131;
		d.idleArgb = 0xFFFFD43B;
		d.backdropArgb = 0xB3000000;
		d.barWidth = 60;
		d.barHeight = 12;
	}

	private static void fullStats(KwamsterConfig c) {
		c.hud.panelCompact = true;
		onlyRows(c, Row.values());
		// a card with this much behind it is worth a longer look
		c.alert.cardDurationMs = 10000;
	}

	private static void neon(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		h.accentArgb = 0xFFFF3EDB;
		h.panelBgArgb = 0xE0120A26;
		h.borderArgb = 0xB300E5FF;
		h.textArgb = 0xFFFFFFFF;
		h.labelArgb = 0xFFC9B8FF;

		c.feedback.indicatorDurationMs = 900;

		c.alert.cardFadeMs = 500;

		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.CROSS;
		x.size = 7;
		x.gap = 3;
		x.thickness = 2;
		x.outline = 1;
		x.outlineArgb = 0xCC1A0533;
		x.centerDot = 2;
		x.hitFlashMs = 200;
		x.defaultArgb = 0xFF00F0FF;
		x.onPlayerArgb = 0xFFFF2E63;
		x.critReadyArgb = 0xFFC6FF00;
		x.critOnPlayerArgb = 0xFFFF3EDB;

		KwamsterConfig.Direction d = c.direction;
		d.forwardArgb = 0xF000FF9F;
		d.backwardArgb = 0xF0FF2E63;
		d.idleArgb = 0xF0FFE600;
		d.backdropArgb = 0xB3120A26;
		d.barWidth = 60;
		d.barHeight = 10;
	}

	private static void monochrome(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		h.accentArgb = 0xFFD4D4D4;
		h.panelBgArgb = 0xC0161616;
		h.borderArgb = 0x33FFFFFF;
		h.textArgb = 0xFFFFFFFF;
		h.labelArgb = 0xFF9E9E9E;

		// A white ring with a dot and a firm outline reads on any background. The aim states keep their
		// colours: a grey ramp could not keep four states apart on sky, snow and grass.
		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.CIRCLE;
		x.size = 7;
		x.thickness = 1;
		x.outline = 1;
		x.outlineArgb = 0xE6000000;
		x.centerDot = 2;
	}

	private static void classic(KwamsterConfig c) {
		KwamsterConfig.Hud h = c.hud;
		h.valueFlash = false;
		h.accentArgb = 0xFFFFAA00;   // §6 gold
		h.panelBgArgb = 0x99282828;
		h.borderArgb = 0x00000000;
		h.textArgb = 0xFFFFFFFF;     // §f white
		h.labelArgb = 0xFFFFFF55;    // §e yellow

		KwamsterConfig.Crosshair x = c.crosshair;
		x.shape = CrosshairShape.CROSS;
		x.size = 7;
		x.gap = 0;
		x.thickness = 2;
		x.outline = 1;
		x.outlineArgb = 0x66000000;
		x.onPlayerArgb = 0xFFFF5555;      // §c
		x.critReadyArgb = 0xFF55FFFF;     // §b
		x.critOnPlayerArgb = 0xFFFF55FF;  // §d

		KwamsterConfig.Direction d = c.direction;
		d.forwardArgb = 0xEB55FF55;   // §a
		d.backwardArgb = 0xEBFF5555;  // §c
		d.idleArgb = 0xEBFFFF55;      // §e
	}

	// ---- rows --------------------------------------------------------------------------------

	/** The stats panel rows (§3.3), in drawing order. */
	private enum Row { HITS, REACH, COMBO, STREAK, CRITS, SPRINT, THREE_BLOCK, RESETS, INTERVAL, TAKEN, AIM, TRADES, ADVANTAGE, RECORD }

	/** Shows exactly the rows in {@code shown} and hides every other one. */
	private static void onlyRows(KwamsterConfig c, Row... shown) {
		EnumSet<Row> on = EnumSet.noneOf(Row.class);
		Collections.addAll(on, shown);
		KwamsterConfig.PanelRows r = c.hud.rows;
		r.hits = on.contains(Row.HITS);
		r.reach = on.contains(Row.REACH);
		r.combo = on.contains(Row.COMBO);
		r.streak = on.contains(Row.STREAK);
		r.crits = on.contains(Row.CRITS);
		r.sprint = on.contains(Row.SPRINT);
		r.threeBlock = on.contains(Row.THREE_BLOCK);
		r.jumpResets = on.contains(Row.RESETS);
		r.interval = on.contains(Row.INTERVAL);
		r.taken = on.contains(Row.TAKEN);
		r.aim = on.contains(Row.AIM);
		r.trades = on.contains(Row.TRADES);
		r.advantage = on.contains(Row.ADVANTAGE);
		r.record = on.contains(Row.RECORD);
	}

	// ---- look fields ---------------------------------------------------------------------------

	/** One look field: copies itself from a preset's target onto a config, and compares two configs. */
	private interface LookField {
		String path();

		void copy(KwamsterConfig from, KwamsterConfig to);

		boolean same(KwamsterConfig a, KwamsterConfig b);
	}

	private record IntField(String path, ToIntFunction<KwamsterConfig> get, ObjIntConsumer<KwamsterConfig> set) implements LookField {
		@Override
		public void copy(KwamsterConfig from, KwamsterConfig to) {
			this.set.accept(to, this.get.applyAsInt(from));
		}

		@Override
		public boolean same(KwamsterConfig a, KwamsterConfig b) {
			return this.get.applyAsInt(a) == this.get.applyAsInt(b);
		}
	}

	private record BoolField(String path, Predicate<KwamsterConfig> get, BiConsumer<KwamsterConfig, Boolean> set) implements LookField {
		@Override
		public void copy(KwamsterConfig from, KwamsterConfig to) {
			this.set.accept(to, this.get.test(from));
		}

		@Override
		public boolean same(KwamsterConfig a, KwamsterConfig b) {
			return this.get.test(a) == this.get.test(b);
		}
	}

	private record EnumField<E extends Enum<E>>(String path, Function<KwamsterConfig, E> get, BiConsumer<KwamsterConfig, E> set)
			implements LookField {
		@Override
		public void copy(KwamsterConfig from, KwamsterConfig to) {
			this.set.accept(to, this.get.apply(from));
		}

		@Override
		public boolean same(KwamsterConfig a, KwamsterConfig b) {
			return this.get.apply(a) == this.get.apply(b);
		}
	}

	private static LookField ints(String path, ToIntFunction<KwamsterConfig> get, ObjIntConsumer<KwamsterConfig> set) {
		return new IntField(path, get, set);
	}

	private static LookField bool(String path, Predicate<KwamsterConfig> get, BiConsumer<KwamsterConfig, Boolean> set) {
		return new BoolField(path, get, set);
	}

	/** Declared after the constants, so it is set before any method below can run. */
	private static final List<LookField> FIELDS = List.of(
			// stats panel, combo counter and HUD colours (§3.3); panelWhenIdle stays the player's
			bool("hud.panelCompact", c -> c.hud.panelCompact, (c, v) -> c.hud.panelCompact = v),
			bool("hud.rows.hits", c -> c.hud.rows.hits, (c, v) -> c.hud.rows.hits = v),
			bool("hud.rows.reach", c -> c.hud.rows.reach, (c, v) -> c.hud.rows.reach = v),
			bool("hud.rows.combo", c -> c.hud.rows.combo, (c, v) -> c.hud.rows.combo = v),
			bool("hud.rows.streak", c -> c.hud.rows.streak, (c, v) -> c.hud.rows.streak = v),
			bool("hud.rows.crits", c -> c.hud.rows.crits, (c, v) -> c.hud.rows.crits = v),
			bool("hud.rows.sprint", c -> c.hud.rows.sprint, (c, v) -> c.hud.rows.sprint = v),
			bool("hud.rows.threeBlock", c -> c.hud.rows.threeBlock, (c, v) -> c.hud.rows.threeBlock = v),
			bool("hud.rows.jumpResets", c -> c.hud.rows.jumpResets, (c, v) -> c.hud.rows.jumpResets = v),
			bool("hud.rows.interval", c -> c.hud.rows.interval, (c, v) -> c.hud.rows.interval = v),
			bool("hud.rows.taken", c -> c.hud.rows.taken, (c, v) -> c.hud.rows.taken = v),
			bool("hud.rows.aim", c -> c.hud.rows.aim, (c, v) -> c.hud.rows.aim = v),
			bool("hud.rows.trades", c -> c.hud.rows.trades, (c, v) -> c.hud.rows.trades = v),
			bool("hud.rows.advantage", c -> c.hud.rows.advantage, (c, v) -> c.hud.rows.advantage = v),
			bool("hud.rows.record", c -> c.hud.rows.record, (c, v) -> c.hud.rows.record = v),
			bool("hud.comboCounterEnabled", c -> c.hud.comboCounterEnabled, (c, v) -> c.hud.comboCounterEnabled = v),
			ints("hud.comboCounterMin", c -> c.hud.comboCounterMin, (c, v) -> c.hud.comboCounterMin = v),
			bool("hud.valueFlash", c -> c.hud.valueFlash, (c, v) -> c.hud.valueFlash = v),
			ints("hud.accentArgb", c -> c.hud.accentArgb, (c, v) -> c.hud.accentArgb = v),
			ints("hud.panelBgArgb", c -> c.hud.panelBgArgb, (c, v) -> c.hud.panelBgArgb = v),
			ints("hud.borderArgb", c -> c.hud.borderArgb, (c, v) -> c.hud.borderArgb = v),
			ints("hud.textArgb", c -> c.hud.textArgb, (c, v) -> c.hud.textArgb = v),
			ints("hud.labelArgb", c -> c.hud.labelArgb, (c, v) -> c.hud.labelArgb = v),
			// hit indicator (§3.4); the tiers, sounds and 3-block reach are never touched
			bool("feedback.indicatorEnabled", c -> c.feedback.indicatorEnabled, (c, v) -> c.feedback.indicatorEnabled = v),
			ints("feedback.indicatorDurationMs", c -> c.feedback.indicatorDurationMs, (c, v) -> c.feedback.indicatorDurationMs = v),
			bool("feedback.indicatorShowReach", c -> c.feedback.indicatorShowReach, (c, v) -> c.feedback.indicatorShowReach = v),
			bool("feedback.indicatorShowMisses", c -> c.feedback.indicatorShowMisses, (c, v) -> c.feedback.indicatorShowMisses = v),
			// summary card (§3.5); cardEnabled stays the player's
			ints("alert.cardDurationMs", c -> c.alert.cardDurationMs, (c, v) -> c.alert.cardDurationMs = v),
			ints("alert.cardFadeMs", c -> c.alert.cardFadeMs, (c, v) -> c.alert.cardFadeMs = v),
			bool("alert.cardFooterHint", c -> c.alert.cardFooterHint, (c, v) -> c.alert.cardFooterHint = v),
			// crosshair (§3.6); enabled, scaleWithGui, attackIndicator and targetMobs stay the player's
			new EnumField<>("crosshair.shape", c -> c.crosshair.shape, (c, v) -> c.crosshair.shape = v),
			ints("crosshair.size", c -> c.crosshair.size, (c, v) -> c.crosshair.size = v),
			ints("crosshair.gap", c -> c.crosshair.gap, (c, v) -> c.crosshair.gap = v),
			ints("crosshair.thickness", c -> c.crosshair.thickness, (c, v) -> c.crosshair.thickness = v),
			ints("crosshair.outline", c -> c.crosshair.outline, (c, v) -> c.crosshair.outline = v),
			ints("crosshair.outlineArgb", c -> c.crosshair.outlineArgb, (c, v) -> c.crosshair.outlineArgb = v),
			ints("crosshair.centerDot", c -> c.crosshair.centerDot, (c, v) -> c.crosshair.centerDot = v),
			bool("crosshair.hitFlash", c -> c.crosshair.hitFlash, (c, v) -> c.crosshair.hitFlash = v),
			ints("crosshair.hitFlashMs", c -> c.crosshair.hitFlashMs, (c, v) -> c.crosshair.hitFlashMs = v),
			ints("crosshair.defaultArgb", c -> c.crosshair.defaultArgb, (c, v) -> c.crosshair.defaultArgb = v),
			ints("crosshair.onPlayerArgb", c -> c.crosshair.onPlayerArgb, (c, v) -> c.crosshair.onPlayerArgb = v),
			ints("crosshair.critReadyArgb", c -> c.crosshair.critReadyArgb, (c, v) -> c.crosshair.critReadyArgb = v),
			ints("crosshair.critOnPlayerArgb", c -> c.crosshair.critOnPlayerArgb, (c, v) -> c.crosshair.critOnPlayerArgb = v),
			// above-head direction bars (§3.7, "Bar look"); radius, threshold and clearance stay the player's
			ints("direction.forwardArgb", c -> c.direction.forwardArgb, (c, v) -> c.direction.forwardArgb = v),
			ints("direction.backwardArgb", c -> c.direction.backwardArgb, (c, v) -> c.direction.backwardArgb = v),
			ints("direction.idleArgb", c -> c.direction.idleArgb, (c, v) -> c.direction.idleArgb = v),
			ints("direction.backdropArgb", c -> c.direction.backdropArgb, (c, v) -> c.direction.backdropArgb = v),
			ints("direction.barWidth", c -> c.direction.barWidth, (c, v) -> c.direction.barWidth = v),
			ints("direction.barHeight", c -> c.direction.barHeight, (c, v) -> c.direction.barHeight = v));
}
