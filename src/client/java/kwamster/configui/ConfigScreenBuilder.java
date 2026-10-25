package kwamster.configui;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.ScreenRegistry;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.KwamsterConfig.AttackIndicatorMode;
import kwamster.core.config.KwamsterConfig.ChatMode;
import kwamster.core.config.KwamsterConfig.CrosshairShape;
import kwamster.core.config.KwamsterConfig.DisplayMode;
import kwamster.core.config.KwamsterConfig.PanelIdle;
import kwamster.core.config.KwamsterConfig.SoundChannel;
import kwamster.core.config.KwamsterConfig.StoplightStyle;
import kwamster.core.config.KwamsterConfig.StoplightTarget;
import kwamster.core.config.TierStyle;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.HitQuality;
import kwamster.core.model.ShareLink;
import kwamster.core.service.FightArchive;
import me.shedaniel.clothconfig2.api.AbstractConfigListEntry;
import me.shedaniel.clothconfig2.api.ConfigBuilder;
import me.shedaniel.clothconfig2.api.ConfigCategory;
import me.shedaniel.clothconfig2.api.ConfigEntryBuilder;
import me.shedaniel.clothconfig2.api.Requirement;
import me.shedaniel.clothconfig2.gui.entries.BooleanListEntry;
import me.shedaniel.clothconfig2.gui.entries.ColorEntry;
import me.shedaniel.clothconfig2.gui.entries.DropdownBoxEntry;
import me.shedaniel.clothconfig2.gui.entries.EnumListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerListEntry;
import me.shedaniel.clothconfig2.gui.entries.IntegerSliderEntry;
import me.shedaniel.clothconfig2.gui.entries.StringListEntry;
import me.shedaniel.clothconfig2.gui.entries.TextListEntry;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;

/**
 * Builds the KWAMSTER settings screen with Cloth Config: one entry per Cloth-edited field of
 * {@link KwamsterConfig}, following the §3 widget column and the §3.12 entry rules.
 *
 * <ul>
 *   <li><b>Every entry</b> has a literal label, a one-line tooltip, a default from
 *       {@link KwamsterConfig#defaults()} (Cloth's per-entry reset button) and, for sliders, a text
 *       getter with units. Enum selectors use readable names instead of Cloth's raw-name default.</li>
 *   <li><b>Stepped and float fields</b> are edited as scaled int sliders (Cloth has no float slider and
 *       no step option); the save consumer scales back. A slider the user did not move writes the
 *       original value back untouched, so an off-step value from a hand-edited file survives an
 *       unrelated save.</li>
 *   <li><b>Requirements</b> grey out dependent entries (§3.12), always using the pending value of the
 *       controlling entry, which is built first.</li>
 *   <li><b>Validation pauses while an entry is disabled.</b> Cloth would otherwise let a greyed-out
 *       entry that still holds bad text block Save, although it can no longer be edited or reset. A
 *       validated entry under a requirement therefore reports no error while the requirement fails
 *       ({@link GatedEntries}, or a gated error supplier), and its save never writes an invalid value:
 *       the live config keeps the previous one.</li>
 *   <li><b>Saving.</b> Each save consumer writes its field into the live {@code Kwamster.config()} at
 *       save time, then {@code Kwamster.saveConfig()} clamps and writes the file once. Cloth never builds
 *       an entry for {@code layouts}: the HUD editor (opened from the buttons here) owns that map and
 *       saves it itself, so pressing Save after visiting the editor cannot overwrite the layout, and
 *       the editor's own save never writes Cloth's pending, unsaved values (§3.10).</li>
 * </ul>
 *
 * <p>Category titles (and the {@code initialCategory} keys) are {@link #CATEGORIES}. Client thread.
 */
public final class ConfigScreenBuilder {
	public static final String GENERAL = "General";
	public static final String HUD = "HUD";
	public static final String HIT_FEEDBACK = "Hit feedback";
	public static final String FIGHT_ALERT = "Fight alert";
	public static final String CROSSHAIR = "Crosshair";
	public static final String DIRECTION = "Direction";
	public static final String RECORDING = "Recording & sharing";
	public static final String ADVANCED = "Advanced";
	/** The category titles in screen order (§3.12); each is also a valid {@code initialCategory}. */
	public static final List<String> CATEGORIES = List.of(GENERAL, HUD, HIT_FEEDBACK, FIGHT_ALERT, CROSSHAIR, DIRECTION, RECORDING, ADVANCED);

	private static final int FLASH_MS = 2000;
	/** DIRECTION's jump cue ({@code assets/kwamster/sounds.json}); a literal, so CONFIGUI never imports DIRECTION (R1). */
	private static final String JUMP_SOUND_ID = "kwamster:jump_cue";
	private static final int CONFIRM_MS = 4000;
	/** Help-text colour for description lines (ARGB: drawString skips colours without alpha). */
	private static final int DESCRIPTION_ARGB = 0xFFA8B0BC;

	private final ConfigEntryBuilder eb;
	/** Read once for the initial values; save consumers write {@link #live()} instead. */
	private final KwamsterConfig cur;
	private final KwamsterConfig def = KwamsterConfig.defaults();

	private ConfigScreenBuilder(ConfigEntryBuilder eb, KwamsterConfig cur) {
		this.eb = eb;
		this.cur = cur;
	}

	/**
	 * The settings screen. When {@code initialCategory} matches a category title (§3.12) the screen
	 * opens on it through {@code ConfigBuilder.setFallbackCategory}; null or an unknown title opens
	 * "General". Never throws: if Cloth cannot build the screen, a small error screen leading back to
	 * {@code parent} is returned and the cause is logged.
	 */
	public static Screen build(@Nullable Screen parent, @Nullable String initialCategory) {
		try {
			return buildCloth(parent, initialCategory);
		} catch (Throwable t) {
			KwamsterClient.LOG.error("Could not build the KWAMSTER settings screen", t);
			return new AlertScreen(() -> Minecraft.getInstance().setScreen(parent),
					Component.literal("KWAMSTER settings could not be opened"),
					Component.literal("Something went wrong while building the settings screen. The game log has the details."),
					Component.literal("Back"), true);
		}
	}

	private static Screen buildCloth(@Nullable Screen parent, @Nullable String initialCategory) {
		ConfigBuilder builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.literal("KWAMSTER"))
				.setSavingRunnable(ConfigScreenBuilder::onSave);
		ConfigScreenBuilder b = new ConfigScreenBuilder(builder.entryBuilder(), Kwamster.config());

		Map<String, ConfigCategory> categories = new LinkedHashMap<>();
		for (String title : CATEGORIES) {
			// Plain literal keys: Cloth matches the fallback category with Component.equals.
			categories.put(title, builder.getOrCreateCategory(Component.literal(title)));
		}
		b.general(categories.get(GENERAL));
		b.hud(categories.get(HUD));
		b.hitFeedback(categories.get(HIT_FEEDBACK));
		b.fightAlert(categories.get(FIGHT_ALERT));
		b.crosshair(categories.get(CROSSHAIR));
		b.direction(categories.get(DIRECTION));
		b.recording(categories.get(RECORDING));
		b.advanced(categories.get(ADVANCED));

		ConfigCategory initial = findCategory(categories, initialCategory);
		if (initial != null) {
			builder.setFallbackCategory(initial);
		}
		return builder.build();
	}

	private static @Nullable ConfigCategory findCategory(Map<String, ConfigCategory> categories, @Nullable String title) {
		if (title == null) {
			return null;
		}
		ConfigCategory exact = categories.get(title);
		if (exact != null) {
			return exact;
		}
		String wanted = title.trim();
		for (Map.Entry<String, ConfigCategory> e : categories.entrySet()) {
			if (e.getKey().equalsIgnoreCase(wanted)) {
				return e.getValue();
			}
		}
		return null;
	}

	/** Cloth's saving runnable: every save consumer has already written the live config. */
	private static void onSave() {
		Kwamster.saveConfig();
	}

	/** The live config; save consumers resolve it at save time, never at build time. */
	private static KwamsterConfig live() {
		return Kwamster.config();
	}

	// ---- General ------------------------------------------------------------

	private void general(ConfigCategory c) {
		KwamsterConfig.General g = this.cur.general;
		KwamsterConfig.General gd = this.def.general;
		KwamsterConfig.Display d = this.cur.display;
		KwamsterConfig.Display dd = this.def.display;

		c.addEntry(toggle("KWAMSTER enabled", g.enabled, gd.enabled,
				"Master switch. Off: no HUD, custom crosshair, sounds, chat lines or new fights; the game is pure vanilla.",
				null, v -> live().general.enabled = v));
		c.addEntry(toggle("Hide all HUD elements", g.hudHidden, gd.hudHidden,
				"Hides every KWAMSTER HUD element; fights are still tracked. The Toggle HUD key (Controls → KWAMSTER, unbound by default) flips this too.",
				null, v -> live().general.hudHidden = v));
		c.addEntry(toggle("Hide HUD while F3 is open", g.hideInDebug, gd.hideInDebug,
				"Hides the KWAMSTER HUD while the F3 debug screen is shown.",
				null, v -> live().general.hideInDebug = v));

		EnumListEntry<DisplayMode> mode = enumSelector("Stats output", DisplayMode.class, d.mode, dd.mode,
				ConfigScreenBuilder::displayModeName,
				"Controls only the stats panel and chat output. The fight card, hit indicator, combo counter and stoplight have their own toggles.",
				null, v -> live().display.mode = v);
		c.addEntry(mode);
		Requirement chatAllowed = () -> mode.getValue() != DisplayMode.HUD_ONLY;

		EnumListEntry<ChatMode> chat = enumSelector("Chat output", ChatMode.class, d.chatMode, dd.chatMode,
				ConfigScreenBuilder::chatModeName,
				"When KWAMSTER prints to chat: a summary after each fight, a line for every hit, both, or never. Unavailable while Stats output is HUD only.",
				chatAllowed, v -> live().display.chatMode = v);
		c.addEntry(chat);
		Requirement fightLines = () -> chatAllowed.check() && (chat.getValue() == ChatMode.PER_FIGHT || chat.getValue() == ChatMode.BOTH);
		Requirement hitLines = () -> chatAllowed.check() && (chat.getValue() == ChatMode.PER_HIT || chat.getValue() == ChatMode.BOTH);

		c.addEntry(slider("Fight summary lines", d.chatFightLines, 1, 3, dd.chatFightLines,
				v -> v == 1 ? "1 line" : v + " lines",
				"1: result and link. 2: adds hits, reach, crits and jump resets. 3: adds combo, streak, sprint, 3-block and hits taken.",
				fightLines, v -> live().display.chatFightLines = v));
		c.addEntry(toggle("Add [Copy link] [Open] to fight summary", d.chatShareActions, dd.chatShareActions,
				"Clickable buttons on the summary line that copy or open the fight's share link.",
				fightLines, v -> live().display.chatShareActions = v));
		c.addEntry(toggle("Per-hit: include misses", d.chatHitMisses, dd.chatHitMisses,
				"With per-hit chat, also print a line for each missed swing.",
				hitLines, v -> live().display.chatHitMisses = v));
		c.addEntry(toggle("Per-hit: include jump resets", d.chatJumpResets, dd.chatJumpResets,
				"With per-hit chat, also print a line for each jump-reset attempt, with how many game ticks after the hit you jumped.",
				hitLines, v -> live().display.chatJumpResets = v));
	}

	// ---- HUD ----------------------------------------------------------------

	private void hud(ConfigCategory c) {
		KwamsterConfig.Hud h = this.cur.hud;
		KwamsterConfig.Hud hd = this.def.hud;

		c.addEntry(enumSelector("Panel outside a fight", PanelIdle.class, h.panelWhenIdle, hd.panelWhenIdle,
				ConfigScreenBuilder::panelIdleName,
				"What the stats panel shows between fights: your last fight, this session's totals, or nothing.",
				null, v -> live().hud.panelWhenIdle = v));
		c.addEntry(toggle("Compact panel", h.panelCompact, hd.panelCompact,
				"Tighter padding and a narrower minimum width for the stats panel.",
				null, v -> live().hud.panelCompact = v));
		c.addEntry(panelRows(h.rows, hd.rows));

		BooleanListEntry combo = toggle("Combo counter", h.comboCounterEnabled, hd.comboCounterEnabled,
				"Shows your current combo as a large number next to the crosshair.",
				null, v -> live().hud.comboCounterEnabled = v);
		c.addEntry(combo);
		c.addEntry(slider("Show combo counter from", h.comboCounterMin, 2, 5, hd.comboCounterMin,
				v -> v + " hits",
				"The counter appears once your combo reaches this many hits.",
				Requirement.isTrue(combo), v -> live().hud.comboCounterMin = v));
		c.addEntry(toggle("Flash values when they change", h.valueFlash, hd.valueFlash,
				"A panel value briefly lights up in the accent colour when it changes.",
				null, v -> live().hud.valueFlash = v));

		c.addEntry(colour("Accent", h.accentArgb, hd.accentArgb,
				"Titles, highlights and the HUD editor selection, as #AARRGGBB.",
				null, false, v -> live().hud.accentArgb = v));
		c.addEntry(colour("Panel background", h.panelBgArgb, hd.panelBgArgb,
				"Stats panel background, as #AARRGGBB; lower alpha shows more of the world.",
				null, false, v -> live().hud.panelBgArgb = v));
		c.addEntry(colour("Panel border", h.borderArgb, hd.borderArgb,
				"1 px panel and card border, as #AARRGGBB; alpha 00 hides it.",
				null, false, v -> live().hud.borderArgb = v));
		c.addEntry(colour("Values", h.textArgb, hd.textArgb,
				"Colour of the numbers, as #AARRGGBB (alpha at least 20 hex).",
				null, true, v -> live().hud.textArgb = v));
		c.addEntry(colour("Labels", h.labelArgb, hd.labelArgb,
				"Colour of the row labels, as #AARRGGBB (alpha at least 20 hex).",
				null, true, v -> live().hud.labelArgb = v));

		c.addEntry(ButtonEntry.wide(Component.literal("Edit HUD layout\u2026"),
				Component.literal("Drag, scale and anchor every HUD element on screen. The layout saves when you press Done there."),
				b -> openHudEditor(b, null)));
		c.addEntry(ButtonEntry.wide(Component.literal("Reset HUD layout"),
				Component.literal("Puts every HUD element back in its default place and size, and saves the layout right away."),
				ConfigScreenBuilder::resetHudLayout));
	}

	@SuppressWarnings("rawtypes") // Cloth's startSubCategory takes a raw List<AbstractConfigListEntry>
	private AbstractConfigListEntry<?> panelRows(KwamsterConfig.PanelRows r, KwamsterConfig.PanelRows rd) {
		List<AbstractConfigListEntry> rows = new ArrayList<>();
		rows.add(toggle("Hits", r.hits, rd.hits, "Landed out of swung, and accuracy.",
				null, v -> live().hud.rows.hits = v));
		rows.add(toggle("Reach", r.reach, rd.reach, "Average and longest reach of your landed hits.",
				null, v -> live().hud.rows.reach = v));
		rows.add(toggle("Combo", r.combo, rd.combo, "Your current and best combo.",
				null, v -> live().hud.rows.combo = v));
		rows.add(toggle("Streak", r.streak, rd.streak, "Landed hits in a row without a miss.",
				null, v -> live().hud.rows.streak = v));
		rows.add(toggle("Crits", r.crits, rd.crits, "Critical hits, and their share of all landed hits.",
				null, v -> live().hud.rows.crits = v));
		rows.add(toggle("Sprint", r.sprint, rd.sprint, "Sprint hits (charged and sprinting), and their share.",
				null, v -> live().hud.rows.sprint = v));
		rows.add(toggle("3-Block", r.threeBlock, rd.threeBlock, "Hits landed from the 3-block reach or farther, and their share.",
				null, v -> live().hud.rows.threeBlock = v));
		rows.add(toggle("Resets", r.jumpResets, rd.jumpResets, "Successful jump resets out of attempts.",
				null, v -> live().hud.rows.jumpResets = v));
		rows.add(toggle("Interval", r.interval, rd.interval, "Average time between your hits on the same opponent.",
				null, v -> live().hud.rows.interval = v));
		rows.add(toggle("Taken", r.taken, rd.taken, "Melee hits you took.",
				null, v -> live().hud.rows.taken = v));
		rows.add(toggle("Aim", r.aim, rd.aim, "Where your hits landed: centre, edge, high and low.",
				null, v -> live().hud.rows.aim = v));
		rows.add(toggle("Trades", r.trades, rd.trades, "Hit trades (you both hit within the trade window) won and lost.",
				null, v -> live().hud.rows.trades = v));
		rows.add(toggle("Advantage", r.advantage, rd.advantage, "Who is winning the exchange: a live bar in a fight, the time split for the last fight.",
				null, v -> live().hud.rows.advantage = v));
		rows.add(toggle("Record", r.record, rd.record, "Wins, losses and draws; shown only with session totals.",
				null, v -> live().hud.rows.record = v));
		return this.eb.startSubCategory(Component.literal("Panel rows"), rows)
				.setTooltip(Component.literal("Choose which rows the stats panel shows. Rows draw in this order."))
				.setExpanded(false)
				.build();
	}

	private static void openHudEditor(ButtonEntry b, @Nullable HudElementId select) {
		Screen current = Minecraft.getInstance().screen;
		boolean opened = Kwamster.services().screens().open(ScreenRegistry.ScreenId.HUD_EDITOR, current,
				select == null ? null : select.name());
		if (!opened) {
			b.flash(bad("HUD editor unavailable"), FLASH_MS);
		}
	}

	private static void resetHudLayout(ButtonEntry b) {
		// The editor owns `layouts` (§3.10): reset and save it now, independent of Cloth's Save.
		live().layouts = HudLayout.defaults();
		Kwamster.saveConfig();
		b.setLabel(good("Layout reset"));
	}

	// ---- Hit feedback -------------------------------------------------------

	private void hitFeedback(ConfigCategory c) {
		KwamsterConfig.Feedback f = this.cur.feedback;
		KwamsterConfig.Feedback fd = this.def.feedback;

		c.addEntry(floatSlider("3-block reach", f.threeBlockReach, fd.threeBlockReach, 100f, 250, 300,
				v -> fmt("%.2f blocks", v / 100.0),
				"A charged hit is a 3-block hit when the distance from your eyes to the nearest point of the target's hitbox is at least this. Applies from the next fight.",
				null, v -> live().feedback.threeBlockReach = v));

		BooleanListEntry sounds = toggle("Hit sounds", f.soundsEnabled, fd.soundsEnabled,
				"Plays the tier's sound when you land a hit; NO-REG hits (target still invulnerable) stay silent. Each tier below has its own sound toggle.",
				null, v -> live().feedback.soundsEnabled = v);
		c.addEntry(sounds);
		// Built before the tiers: their Test buttons play on this entry's pending value.
		EnumListEntry<SoundChannel> channel = enumSelector("Volume category", SoundChannel.class, f.soundChannel, fd.soundChannel,
				ConfigScreenBuilder::soundChannelName,
				"Which vanilla volume slider (Options \u2192 Music & Sounds) scales KWAMSTER's hit sounds. Players and UI are also scaled by Master Volume.",
				Requirement.isTrue(sounds), v -> live().feedback.soundChannel = v);
		c.addEntry(channel);

		BooleanListEntry indicator = toggle("Hit indicator", f.indicatorEnabled, fd.indicatorEnabled,
				"A small pill under the crosshair showing each hit's tier in its colour.",
				null, v -> live().feedback.indicatorEnabled = v);
		c.addEntry(indicator);
		Requirement indicatorOn = Requirement.isTrue(indicator);
		c.addEntry(steppedSlider("Indicator duration", f.indicatorDurationMs, fd.indicatorDurationMs, 50, 6, 60,
				ms -> ms + " ms",
				"How long the hit indicator stays on screen after a hit.",
				indicatorOn, v -> live().feedback.indicatorDurationMs = v));
		c.addEntry(toggle("Indicator: show misses", f.indicatorShowMisses, fd.indicatorShowMisses,
				"The indicator also shows MISS and the distance when a swing misses.",
				indicatorOn, v -> live().feedback.indicatorShowMisses = v));
		c.addEntry(toggle("Indicator: show reach", f.indicatorShowReach, fd.indicatorShowReach,
				"The indicator shows the hit's reach in blocks before the tier label.",
				indicatorOn, v -> live().feedback.indicatorShowReach = v));

		c.addEntry(description("Hit tiers: each landed hit gets the first tier that matches. A disabled tier shows no indicator, flash, sound or chat line, but the hit is still recorded."));
		for (HitQuality q : HitQuality.values()) {
			c.addEntry(tier(q, channel));
		}
	}

	@SuppressWarnings("rawtypes") // Cloth's startSubCategory takes a raw List<AbstractConfigListEntry>
	private AbstractConfigListEntry<?> tier(HitQuality q, EnumListEntry<SoundChannel> channel) {
		TierStyle t = this.cur.tier(q);
		TierStyle d = this.def.tier(q);
		List<AbstractConfigListEntry> rows = new ArrayList<>();

		BooleanListEntry enabled = toggle("Enabled", t.enabled, d.enabled,
				"Off: no indicator, crosshair flash, sound or per-hit chat line for this tier. The hit is still recorded and counted.",
				null, v -> live().tier(q).enabled = v);
		rows.add(enabled);
		Requirement on = Requirement.isTrue(enabled);

		rows.add(colour("Colour", t.argb, d.argb,
				"Indicator, crosshair flash and chat colour for this tier, as #AARRGGBB.",
				on, true, v -> live().tier(q).argb = v));
		// Disabled entries never block Save, and an invalid value is never written (class Javadoc).
		rows.add(this.eb.startStrField(Component.literal("Label"), t.label)
				.setDefaultValue(d.label)
				.setTooltip(Component.literal("Text on the hit indicator and the per-hit chat line, up to " + TierStyle.MAX_LABEL_LENGTH + " characters."))
				.setErrorSupplier(v -> on.check() ? labelError(v) : Optional.empty())
				.setSaveConsumer(v -> {
					if (labelError(v).isEmpty()) {
						live().tier(q).label = v.trim();
					}
				})
				.setRequirement(on)
				.build());

		BooleanListEntry sound = toggle("Sound", t.soundEnabled, d.soundEnabled,
				"Play this tier's sound when you land it (Hit sounds must be on too).",
				on, v -> live().tier(q).soundEnabled = v);
		rows.add(sound);
		Requirement soundOn = Requirement.all(on, Requirement.isTrue(sound));

		DropdownBoxEntry<String> soundId = this.eb.startStringDropdownMenu(Component.literal("Sound id"), t.soundId)
				.setDefaultValue(d.soundId)
				.setSelections(SoundSuggestions.IDS)
				.setSuggestionMode(true)
				.setErrorSupplier(v -> soundOn.check() ? soundIdError(v) : Optional.empty())
				.setTooltipSupplier(ConfigScreenBuilder::soundIdTooltip)
				.setSaveConsumer(v -> {
					if (soundIdError(v).isEmpty()) {
						live().tier(q).soundId = v.trim();
					}
				})
				.setRequirement(soundOn)
				.build();
		rows.add(soundId);
		IntegerSliderEntry volume = slider("Volume", t.volumePct, 0, 100, d.volumePct,
				v -> v + "%",
				"Playback volume, further scaled by the vanilla slider chosen under Volume category.",
				soundOn, v -> live().tier(q).volumePct = v);
		rows.add(volume);
		IntegerSliderEntry pitch = slider("Pitch", t.pitchPct, 50, 200, d.pitchPct,
				v -> v + "%",
				"Playback pitch: 100% is the sound's normal pitch, lower is deeper.",
				soundOn, v -> live().tier(q).pitchPct = v);
		rows.add(pitch);

		ButtonEntry test = ButtonEntry.inline(Component.literal("Preview"), Component.literal("\u25B6 Test"),
				Component.literal("Plays this tier's sound with the values above and the Volume category, including changes you have not saved yet."),
				b -> previewSound(b, soundId, volume, pitch, channel));
		test.setRequirement(on);
		rows.add(test);

		// Unstyled root, so only the swatch takes the tier colour (siblings inherit their parent's style).
		MutableComponent title = Component.empty()
				.append(Component.literal("\u25A0 ").withColor(t.argb & 0xFFFFFF))
				.append(Component.literal(tierName(q)));
		return this.eb.startSubCategory(title, rows)
				.setTooltip(Component.literal(tierDescription(q)))
				.setExpanded(false)
				.build();
	}

	/**
	 * Reads the tier's pending (unsaved) values and the pending Volume category, and plays them through
	 * FEEDBACK's {@code SoundPreview}. Flashes a warning when the result would be silent: the tier volume,
	 * vanilla's Master Volume or the chosen vanilla category at 0.
	 */
	private static void previewSound(ButtonEntry b, DropdownBoxEntry<String> soundId, IntegerSliderEntry volume,
			IntegerSliderEntry pitch, EnumListEntry<SoundChannel> channelEntry) {
		Identifier id = parseSoundId(soundId.getValue());
		if (id == null) {
			b.flash(bad("Invalid sound id"), FLASH_MS);
			return;
		}
		if (!soundExists(id)) {
			b.flash(bad("Unknown sound"), FLASH_MS);
			return;
		}
		int volumePct = volume.getValue();
		SoundChannel channel = channelEntry.getValue();
		Kwamster.services().sounds().preview(id.toString(), volumePct, pitch.getValue(), channel);
		String silent = previewVolumeProblem(volumePct, channel);
		if (silent != null) {
			b.flash(Component.literal(silent).withStyle(ChatFormatting.GOLD), FLASH_MS);
		}
	}

	/** {@link EntryChecks#previewVolumeProblem} with vanilla's current slider values (unknown: no warning). */
	private static @Nullable String previewVolumeProblem(int volumePct, @Nullable SoundChannel channel) {
		float master = 1f;
		float category = 1f;
		try {
			Options options = Minecraft.getInstance().options;
			master = options.getSoundSourceVolume(SoundSource.MASTER);
			category = options.getSoundSourceVolume(soundSource(channel));
		} catch (Throwable t) {
			// unknown state: warn only about the tier's own volume
		}
		return EntryChecks.previewVolumeProblem(volumePct, channel, master, category);
	}

	/** The vanilla volume category a {@link SoundChannel} plays on (PLAYERS when null), as FEEDBACK maps it (§8.1). */
	private static SoundSource soundSource(@Nullable SoundChannel channel) {
		if (channel == null) {
			return SoundSource.PLAYERS;
		}
		return switch (channel) {
			case MASTER -> SoundSource.MASTER;
			case PLAYERS -> SoundSource.PLAYERS;
			case UI -> SoundSource.UI;
		};
	}

	private static Optional<Component> labelError(@Nullable String value) {
		String problem = EntryChecks.labelProblem(value);
		return problem == null ? Optional.empty() : Optional.of(Component.literal(problem));
	}

	/** Blocks only syntax errors (§3.4); an id the sound manager does not know is a tooltip warning. */
	private static Optional<Component> soundIdError(@Nullable String value) {
		if (value == null || value.isBlank()) {
			return Optional.of(Component.literal("Enter a sound id, e.g. minecraft:block.note_block.pling"));
		}
		if (parseSoundId(value) == null) {
			return Optional.of(Component.literal("Not a valid sound id: lower-case namespace:path, e.g. minecraft:block.note_block.pling"));
		}
		return Optional.empty();
	}

	/** Re-evaluated on every hover (no cache), so a resource-pack reload is reflected at once. */
	private static Optional<Component[]> soundIdTooltip(@Nullable String value) {
		Component base = Component.literal("Vanilla or resource-pack sound: pick one from the list or type any id.");
		Identifier id = parseSoundId(value);
		if (id != null && !soundExists(id)) {
			return Optional.of(new Component[]{base, Component.literal("Unknown sound (resource pack?)").withStyle(ChatFormatting.GOLD)});
		}
		return Optional.of(new Component[]{base});
	}

	private static @Nullable Identifier parseSoundId(@Nullable String value) {
		if (value == null) {
			return null;
		}
		String s = value.trim();
		return s.isEmpty() ? null : Identifier.tryParse(s);
	}

	private static boolean soundExists(Identifier id) {
		try {
			return Minecraft.getInstance().getSoundManager().getSoundEvent(id) != null;
		} catch (Throwable t) {
			return true; // unknown state: do not warn
		}
	}

	// ---- Fight alert --------------------------------------------------------

	private void fightAlert(ConfigCategory c) {
		KwamsterConfig.Alert a = this.cur.alert;
		KwamsterConfig.Alert ad = this.def.alert;

		c.addEntry(description("After a fight with enough events (Advanced \u2192 Minimum events), or any win or loss, KWAMSTER shows a summary card. The chat summary is set under General \u2192 Chat output."));
		BooleanListEntry card = toggle("Summary card", a.cardEnabled, ad.cardEnabled,
				"Shows a card with the fight's result and key numbers when a fight ends.",
				null, v -> live().alert.cardEnabled = v);
		c.addEntry(card);
		Requirement cardOn = Requirement.isTrue(card);
		c.addEntry(steppedSlider("Show card for", a.cardDurationMs, ad.cardDurationMs, 100, 20, 300,
				ms -> fmt("%.1f s", ms / 1000.0),
				"How long the card stays on screen after a fight.",
				cardOn, v -> live().alert.cardDurationMs = v));
		c.addEntry(steppedSlider("Fade out", a.cardFadeMs, ad.cardFadeMs, 50, 0, 30,
				ms -> ms + " ms",
				"Length of the card's fade-out at the end; 0 ms hides it at once.",
				cardOn, v -> live().alert.cardFadeMs = v));
		c.addEntry(toggle("Footer hint", a.cardFooterHint, ad.cardFooterHint,
				"A footer saying how to share the fight, shown only when the chat summary does not already offer the link.",
				cardOn, v -> live().alert.cardFooterHint = v));
		c.addEntry(ButtonEntry.wide(Component.literal("Position card\u2026"),
				Component.literal("Opens the HUD editor with the summary card selected."),
				b -> openHudEditor(b, HudElementId.SUMMARY_CARD)));
	}

	// ---- Crosshair ----------------------------------------------------------

	private void crosshair(ConfigCategory c) {
		KwamsterConfig.Crosshair x = this.cur.crosshair;
		KwamsterConfig.Crosshair xd = this.def.crosshair;

		BooleanListEntry enabled = toggle("Custom crosshair", x.enabled, xd.enabled,
				"Replaces the vanilla crosshair with a crisp, state-coloured one. Off: vanilla crosshair. The Toggle custom crosshair key (Controls → KWAMSTER, unbound by default) flips this too.",
				null, v -> live().crosshair.enabled = v);
		c.addEntry(enabled);
		Requirement on = Requirement.isTrue(enabled);

		c.addEntry(toggle("Scale with GUI scale", x.scaleWithGui, xd.scaleWithGui,
				"Sizes below are screen pixels; turn this on to grow them with the GUI scale (for 4K screens).",
				on, v -> live().crosshair.scaleWithGui = v));
		c.addEntry(enumSelector("Shape", CrosshairShape.class, x.shape, xd.shape,
				ConfigScreenBuilder::shapeName,
				"Crosshair shape: four arms, T (no top arm), diagonal X, ring or solid dot.",
				on, v -> live().crosshair.shape = v));
		c.addEntry(slider("Arm length / radius", x.size, 1, 40, xd.size,
				v -> v + " px",
				"Length of each arm, or the radius for Circle and the diameter for Dot, in screen pixels.",
				on, v -> live().crosshair.size = v));
		c.addEntry(slider("Gap", x.gap, 0, 20, xd.gap,
				v -> v + " px",
				"Empty space between the centre and each arm, in screen pixels.",
				on, v -> live().crosshair.gap = v));
		c.addEntry(slider("Thickness", x.thickness, 1, 10, xd.thickness,
				v -> v + " px",
				"Width of the arms and of the ring, in screen pixels.",
				on, v -> live().crosshair.thickness = v));
		c.addEntry(slider("Outline", x.outline, 0, 3, xd.outline,
				v -> v == 0 ? "None" : v + " px",
				"Border around the crosshair for contrast on any background; 0 = none.",
				on, v -> live().crosshair.outline = v));
		c.addEntry(colour("Outline colour", x.outlineArgb, xd.outlineArgb,
				"Colour of the outline, as #AARRGGBB.",
				on, false, v -> live().crosshair.outlineArgb = v));
		c.addEntry(slider("Centre dot", x.centerDot, 0, 10, xd.centerDot,
				v -> v == 0 ? "Off" : v + " px",
				"Adds a square dot of this size in the middle; 0 = off.",
				on, v -> live().crosshair.centerDot = v));
		c.addEntry(enumSelector("Vanilla attack indicator", AttackIndicatorMode.class, x.attackIndicator, xd.attackIndicator,
				ConfigScreenBuilder::attackIndicatorName,
				"Only applies when Options \u2192 Attack Indicator is set to Crosshair. In Hotbar mode vanilla draws it next to the hotbar and this setting has no effect.",
				on, v -> live().crosshair.attackIndicator = v));

		c.addEntry(colour("Colour: default", x.defaultArgb, xd.defaultArgb,
				"Crosshair colour when none of the states below applies, as #AARRGGBB.",
				on, false, v -> live().crosshair.defaultArgb = v));
		c.addEntry(colour("Colour: on a player in reach", x.onPlayerArgb, xd.onPlayerArgb,
				"Colour while you aim at a player close enough to hit right now, as #AARRGGBB.",
				on, false, v -> live().crosshair.onPlayerArgb = v));
		c.addEntry(colour("Colour: crit ready (falling)", x.critReadyArgb, xd.critReadyArgb,
				"Colour while you are falling with a charged attack and not sprinting, so the next hit would crit, as #AARRGGBB.",
				on, false, v -> live().crosshair.critReadyArgb = v));
		c.addEntry(colour("Colour: crit ready on a player", x.critOnPlayerArgb, xd.critOnPlayerArgb,
				"Colour when a crit is ready and you aim at a player in reach, as #AARRGGBB.",
				on, false, v -> live().crosshair.critOnPlayerArgb = v));
		c.addEntry(toggle("Living mobs count as targets", x.targetMobs, xd.targetMobs,
				"Also use the on-a-player colours when aiming at mobs and other living entities.",
				on, v -> live().crosshair.targetMobs = v));

		BooleanListEntry flash = toggle("Flash tier colour on hit", x.hitFlash, xd.hitFlash,
				"Briefly shows the hit tier's colour when you land a hit and widens the gap by 2 px, so the flash reads even when the colours match.",
				on, v -> live().crosshair.hitFlash = v);
		c.addEntry(flash);
		c.addEntry(slider("Flash duration", x.hitFlashMs, 0, 600, xd.hitFlashMs,
				v -> v + " ms",
				"How long the hit flash lasts.",
				Requirement.all(on, Requirement.isTrue(flash)), v -> live().crosshair.hitFlashMs = v));
	}

	// ---- Direction ----------------------------------------------------------

	private void direction(ConfigCategory c) {
		KwamsterConfig.Direction s = this.cur.direction;
		KwamsterConfig.Direction sd = this.def.direction;

		BooleanListEntry bars = toggle("Direction bars above heads", s.headBarEnabled, sd.headBarEnabled,
				"A flat bar above every nearby player's head: green when they move the way they are looking, red when they backpedal, yellow when they stand still or strafe.",
				null, v -> live().direction.headBarEnabled = v);
		c.addEntry(bars);
		Requirement barsOn = Requirement.isTrue(bars);
		BooleanListEntry jump = toggle("Jump sound", s.jumpSoundEnabled, sd.jumpSoundEnabled,
				"A short cue the moment a nearby player starts a jump, coming from their direction. Knockback does not count as a jump.",
				null, v -> live().direction.jumpSoundEnabled = v);
		c.addEntry(jump);
		Requirement jumpOn = Requirement.isTrue(jump);
		c.addEntry(slider("Radius", s.radius, 1, 128, sd.radius,
				v -> v + " blocks",
				"Players further away get no bar and no jump sound.",
				Requirement.any(barsOn, jumpOn), v -> live().direction.radius = v));
		c.addEntry(headBars(s, sd, barsOn));
		c.addEntry(jumpSound(s, sd, jumpOn));
		c.addEntry(stoplight(s, sd));
	}

	@SuppressWarnings("rawtypes") // Cloth's startSubCategory takes a raw List<AbstractConfigListEntry>
	private AbstractConfigListEntry<?> headBars(KwamsterConfig.Direction s, KwamsterConfig.Direction sd, Requirement on) {
		List<AbstractConfigListEntry> rows = new ArrayList<>();
		rows.add(toggle("Show above yourself", s.headBarForSelf, sd.headBarForSelf,
				"Also draws your own bar. Only visible in third person (F5); handy for checking the bars work.",
				on, v -> live().direction.headBarForSelf = v));
		rows.add(colour("Moving forward", s.forwardArgb, sd.forwardArgb,
				"Bar colour when the player moves the way they are looking, as #AARRGGBB (the alpha is the bar's opacity).",
				on, false, v -> live().direction.forwardArgb = v));
		rows.add(colour("Moving backward", s.backwardArgb, sd.backwardArgb,
				"Bar colour when the player backpedals, as #AARRGGBB.",
				on, false, v -> live().direction.backwardArgb = v));
		rows.add(colour("Not moving", s.idleArgb, sd.idleArgb,
				"Bar colour when the player stands still or strafes, as #AARRGGBB.",
				on, false, v -> live().direction.idleArgb = v));
		rows.add(colour("Backdrop", s.backdropArgb, sd.backdropArgb,
				"The dark frame behind the bar, as #AARRGGBB.",
				on, false, v -> live().direction.backdropArgb = v));
		rows.add(slider("Movement threshold", s.moveThreshold, 0, 500, sd.moveThreshold,
				v -> fmt("%.3f b/t", v / 1000.0),
				"Below this speed along their facing the bar reads as not moving. Sneaking is about 0.065 blocks per tick, walking 0.216, sprinting 0.280.",
				on, v -> live().direction.moveThreshold = v));
		rows.add(slider("Width", s.barWidth, 5, 300, sd.barWidth,
				v -> fmt("%.2f blocks", v / 100.0),
				"Width of the coloured bar.",
				on, v -> live().direction.barWidth = v));
		rows.add(slider("Height", s.barHeight, 2, 100, sd.barHeight,
				v -> fmt("%.2f blocks", v / 100.0),
				"Height of the coloured bar.",
				on, v -> live().direction.barHeight = v));
		rows.add(slider("Height above head", s.headClearance, 0, 300, sd.headClearance,
				v -> fmt("%.2f blocks", v / 100.0),
				"Gap between the top of the player's hitbox and the bar. The default tucks it between the head and the nametag.",
				on, v -> live().direction.headClearance = v));
		return this.eb.startSubCategory(Component.literal("Bar look"), rows)
				.setTooltip(Component.literal("Colours, size and position of the above-head bars."))
				.setExpanded(false)
				.build();
	}

	@SuppressWarnings("rawtypes") // Cloth's startSubCategory takes a raw List<AbstractConfigListEntry>
	private AbstractConfigListEntry<?> jumpSound(KwamsterConfig.Direction s, KwamsterConfig.Direction sd, Requirement on) {
		List<AbstractConfigListEntry> rows = new ArrayList<>();
		rows.add(toggle("Play for your own jumps", s.jumpSoundForSelf, sd.jumpSoundForSelf,
				"Leave off to hear only other players jump.",
				on, v -> live().direction.jumpSoundForSelf = v));
		IntegerSliderEntry volume = slider("Volume", s.jumpSoundVolume, 0, 200, sd.jumpSoundVolume,
				v -> v + "%",
				"Cue volume. It also follows vanilla's Players volume slider.",
				on, v -> live().direction.jumpSoundVolume = v);
		rows.add(volume);
		IntegerSliderEntry pitch = slider("Pitch", s.jumpSoundPitch, 50, 200, sd.jumpSoundPitch,
				v -> v + "%",
				"Playback pitch: 100% is the cue's normal pitch.",
				on, v -> live().direction.jumpSoundPitch = v);
		rows.add(pitch);
		rows.add(slider("Ignore knockback for", s.jumpKnockbackTicks, 0, 40, sd.jumpKnockbackTicks,
				v -> v == 0 ? "Off" : fmt("%d ticks", v),
				"After a standing player is hit, their next launch is knockback, not a jump, for this long. Cleared the moment they land, so a longer value costs very little.",
				on, v -> live().direction.jumpKnockbackTicks = v));
		ButtonEntry test = ButtonEntry.inline(Component.literal("Preview"), Component.literal("▶ Test"),
				Component.literal("Plays the jump cue with the volume and pitch above, including changes you have not saved yet. In game it comes from the jumping player's direction."),
				b -> previewJumpSound(b, volume, pitch));
		test.setRequirement(on);
		rows.add(test);
		return this.eb.startSubCategory(Component.literal("Jump sound settings"), rows)
				.setTooltip(Component.literal("Who the jump cue plays for, how loud, and the knockback filter."))
				.setExpanded(false)
				.build();
	}

	/** Plays the jump cue through FEEDBACK's {@code SoundPreview} with the pending volume and pitch. */
	private static void previewJumpSound(ButtonEntry b, IntegerSliderEntry volume, IntegerSliderEntry pitch) {
		int volumePct = volume.getValue();
		Kwamster.services().sounds().preview(JUMP_SOUND_ID, volumePct, pitch.getValue(), SoundChannel.PLAYERS);
		String silent = previewVolumeProblem(volumePct, SoundChannel.PLAYERS);
		if (silent != null) {
			b.flash(Component.literal(silent).withStyle(ChatFormatting.GOLD), FLASH_MS);
		}
	}

	@SuppressWarnings("rawtypes") // Cloth's startSubCategory takes a raw List<AbstractConfigListEntry>
	private AbstractConfigListEntry<?> stoplight(KwamsterConfig.Direction s, KwamsterConfig.Direction sd) {
		List<AbstractConfigListEntry> c = new ArrayList<>();
		BooleanListEntry enabled = toggle("Opponent stoplight", s.stoplightEnabled, sd.stoplightEnabled,
				"A traffic light on the HUD showing whether your fight opponent is approaching you (green), holding or strafing (yellow) or backing away (red).",
				null, v -> live().direction.stoplightEnabled = v);
		c.add(enabled);
		Requirement on = Requirement.isTrue(enabled);

		EnumListEntry<StoplightTarget> target = enumSelector("Whose movement", StoplightTarget.class, s.target, sd.target,
				ConfigScreenBuilder::stoplightTargetName,
				"Track only your current fight opponent, or outside a fight also the player you aim at.",
				on, v -> live().direction.target = v);
		c.add(target);
		c.add(floatSlider("Approach/retreat threshold", s.enterBps, sd.enterBps, 10f, 3, 40,
				v -> fmt("%.1f b/s", v / 10.0),
				"Speed toward or away from you, in blocks per second, that switches the light to green or red.",
				on, v -> live().direction.enterBps = v));
		c.add(floatSlider("Hysteresis", s.hysteresisBps, sd.hysteresisBps, 10f, 0, 20,
				v -> fmt("%.1f b/s", v / 10.0),
				"Green or red holds until the speed drops this far below the threshold, which stops flicker.",
				on, v -> live().direction.hysteresisBps = v));
		c.add(slider("Smoothing", s.smoothingMs, 0, 600, sd.smoothingMs,
				v -> v == 0 ? "Off" : v + " ms",
				"Averages the opponent's speed over about this long; higher is steadier but slower to react.",
				on, v -> live().direction.smoothingMs = v));
		c.add(slider("Minimum hold", s.minHoldMs, 0, 600, sd.minHoldMs,
				v -> v + " ms",
				"A light stays lit at least this long before it can change.",
				on, v -> live().direction.minHoldMs = v));
		c.add(slider("Ignore knockback for", s.kbHoldMs, 0, 1000, sd.kbHoldMs,
				v -> v + " ms",
				"After the opponent takes a hit, the light holds this long so knockback does not read as retreating.",
				on, v -> live().direction.kbHoldMs = v));
		c.add(floatSlider("Aimed player range", s.aimedRange, sd.aimedRange, 1f, 3, 16,
				v -> v + " blocks",
				"Outside a fight, how far away an aimed-at player can be and still be tracked.",
				Requirement.all(on, () -> target.getValue() == StoplightTarget.FIGHT_OR_AIMED),
				v -> live().direction.aimedRange = v));
		c.add(enumSelector("Style", StoplightStyle.class, s.style, sd.style,
				ConfigScreenBuilder::stoplightStyleName,
				"Three lights stacked, three in a row, or one light in the active colour.",
				on, v -> live().direction.style = v));
		c.add(toggle("Show label and speed", s.showLabel, sd.showLabel,
				"Writes APPROACH, HOLD or RETREAT and the speed in blocks per second beside the light.",
				on, v -> live().direction.showLabel = v));
		c.add(toggle("Hide when nobody is tracked", s.hideWhenNoTarget, sd.hideWhenNoTarget,
				"Off: the unlit light stays on screen at 40% opacity when no player is tracked.",
				on, v -> live().direction.hideWhenNoTarget = v));
		c.add(colour("Approaching", s.greenArgb, sd.greenArgb,
				"Light colour when the opponent moves toward you, as #AARRGGBB.",
				on, false, v -> live().direction.greenArgb = v));
		c.add(colour("Holding / strafing", s.yellowArgb, sd.yellowArgb,
				"Light colour when the opponent keeps their distance or strafes, as #AARRGGBB.",
				on, false, v -> live().direction.yellowArgb = v));
		c.add(colour("Backing away", s.redArgb, sd.redArgb,
				"Light colour when the opponent moves away from you, as #AARRGGBB.",
				on, false, v -> live().direction.redArgb = v));
		c.add(colour("Unlit", s.offArgb, sd.offArgb,
				"Colour of the lights that are off, as #AARRGGBB.",
				on, false, v -> live().direction.offArgb = v));
		ButtonEntry position = ButtonEntry.wide(Component.literal("Position stoplight\u2026"),
				Component.literal("Opens the HUD editor with the stoplight selected."),
				b -> openHudEditor(b, HudElementId.STOPLIGHT));
		position.setRequirement(on);
		c.add(position);
		return this.eb.startSubCategory(Component.literal("HUD stoplight"), c)
				.setTooltip(Component.literal("An optional on-screen traffic light for your fight opponent's movement toward or away from you."))
				.setExpanded(false)
				.build();
	}

	// ---- Recording & sharing -------------------------------------------------

	private void recording(ConfigCategory c) {
		KwamsterConfig.Recording r = this.cur.recording;
		KwamsterConfig.Recording rd = this.def.recording;

		BooleanListEntry save = toggle("Save every fight", r.saveFights, rd.saveFights,
				"Writes each finished fight to config/kwamster/fights, for the Saved fights screen and share links later.",
				null, v -> live().recording.saveFights = v);
		c.addEntry(save);
		Requirement saving = Requirement.isTrue(save);
		c.addEntry(toggle("Also save fights below the minimum events", r.saveTrivialFights, rd.saveTrivialFights,
				"Also keep short draws with fewer events than Advanced \u2192 Minimum events (they never alert).",
				saving, v -> live().recording.saveTrivialFights = v));
		// Validation pauses while "Save every fight" is off; bad text is never saved (0 would mean unlimited).
		IntegerListEntry keep = GatedEntries.intField(this.eb, Component.literal("Saved fights to keep"),
				r.maxSavedFights, rd.maxSavedFights, 0, 5000,
				Component.literal("The oldest unpinned fights are deleted beyond this many (0 to 5000); 0 = unlimited. Pinned fights are never deleted."),
				saving, v -> live().recording.maxSavedFights = v);
		c.addEntry(keep);

		StringListEntry url = this.eb.startStrField(Component.literal("Viewer URL"), r.shareBaseUrl)
				.setDefaultValue(rd.shareBaseUrl)
				.setTooltip(Component.literal("Web page that share links open. Change it only if you host the viewer yourself."))
				.setErrorSupplier(v -> KwamsterConfig.isValidShareBaseUrl(v)
						? Optional.empty()
						: Optional.of(Component.literal("Needs a full http(s) address with a host, e.g. https://example.com/kwamster/")))
				.setSaveConsumer(v -> live().recording.shareBaseUrl = v == null ? KwamsterConfig.DEFAULT_SHARE_BASE_URL : v.trim())
				.build();
		c.addEntry(url);
		c.addEntry(toggle("Include server name (never raw IPs)", r.shareIncludeServer, rd.shareIncludeServer,
				"Puts the server's name in share links; numeric IP addresses are always left out.",
				null, v -> live().recording.shareIncludeServer = v));
		c.addEntry(toggle("Include positions for the replay", r.sharePositions, rd.sharePositions,
				"Adds positions relative to where the fight started (never world coordinates) so the viewer can replay it.",
				null, v -> live().recording.sharePositions = v));
		IntegerListEntry maxChars = GatedEntries.intField(this.eb, Component.literal("Max link length"),
				r.shareMaxChars, rd.shareMaxChars, 600, 8000,
				Component.literal("Longest share link in characters (600 to 8000); longer fights are thinned to fit. 1900 fits one Discord message."),
				null, v -> live().recording.shareMaxChars = v);
		c.addEntry(maxChars);

		c.addEntry(ButtonEntry.wide(Component.literal("Saved fights\u2026"),
				Component.literal("Browse, share, pin and delete your saved fights."),
				ConfigScreenBuilder::openSavedFights));
		c.addEntry(ButtonEntry.wide(Component.literal("Open fights folder"),
				Component.literal("Opens config/kwamster/fights in your file manager."),
				ConfigScreenBuilder::openFightsFolder));
		c.addEntry(ButtonEntry.wide(Component.literal("Copy last fight link"),
				Component.literal("Copies the share link of your most recent fight. The fight travels inside the link; nothing is uploaded."),
				ConfigScreenBuilder::copyLastLink));
		c.addEntry(ButtonEntry.wide(Component.literal("Reset session stats"),
				Component.literal("Clears the totals of this game session (the stats panel's Session view)."),
				ConfigScreenBuilder::resetSession));
		c.addEntry(ButtonEntry.wide(Component.literal("Reset lifetime stats"),
				Component.literal("Clears your lifetime totals. Saved fight files are kept. Click twice to confirm."),
				new LifetimeReset()));
	}

	private static void openSavedFights(ButtonEntry b) {
		Screen current = Minecraft.getInstance().screen;
		if (!Kwamster.services().screens().open(ScreenRegistry.ScreenId.SAVED_FIGHTS, current)) {
			b.flash(bad("Saved fights unavailable"), FLASH_MS);
		}
	}

	private static void openFightsFolder(ButtonEntry b) {
		try {
			Path folder = Kwamster.services().archive().folder();
			Files.createDirectories(folder);
			Util.getPlatform().openPath(folder);
		} catch (Exception e) {
			KwamsterClient.LOG.warn("Could not open the fights folder", e);
			b.flash(bad("Could not open the folder"), FLASH_MS);
		}
	}

	/** The session's last fight, or else the newest saved one (so the button still works after a restart). */
	private static void copyLastLink(ButtonEntry b) {
		FightArchive archive = Kwamster.services().archive();
		FightRecord record = Kwamster.fights().lastRecord();
		if (record == null) {
			List<FightSummaryRow> saved = archive.list();
			if (!saved.isEmpty() && saved.get(0).id != null) {
				record = archive.load(saved.get(0).id).orElse(null);
			}
		}
		if (record == null) {
			b.flash(bad("No fight to share yet"), FLASH_MS);
			return;
		}
		ShareLink link = archive.shareLink(record);
		if (link == null) {
			b.flash(bad("Link unavailable (check the viewer URL)"), FLASH_MS);
			return;
		}
		Minecraft.getInstance().keyboardHandler.setClipboard(link.url());
		b.flash(good("Link copied (" + link.length() + " chars)"), FLASH_MS);
	}

	private static void resetSession(ButtonEntry b) {
		Kwamster.fights().resetSession();
		b.flash(good("Session stats reset"), FLASH_MS);
	}

	/** Two-click confirmation, so a stray click cannot wipe the lifetime totals. */
	private static final class LifetimeReset implements Consumer<ButtonEntry> {
		private long armedUntilMs;

		@Override
		public void accept(ButtonEntry b) {
			long now = Util.getMillis();
			if (now >= this.armedUntilMs) {
				this.armedUntilMs = now + CONFIRM_MS;
				b.flash(bad("Click again to reset lifetime stats"), CONFIRM_MS);
				return;
			}
			this.armedUntilMs = 0L;
			Kwamster.services().archive().resetLifetime();
			b.flash(good("Lifetime stats reset"), FLASH_MS);
		}
	}

	// ---- Advanced -----------------------------------------------------------

	private void advanced(ConfigCategory c) {
		KwamsterConfig.Advanced a = this.cur.advanced;
		KwamsterConfig.Advanced ad = this.def.advanced;

		String snapshot = "Detection constants. The 3-block reach, jump windows, combo timeout, advantage window and interval gap are copied when a fight starts, so changes to them apply from the next fight.";
		c.setDescription(new FormattedText[]{Component.literal(snapshot)});
		c.addEntry(description(snapshot));

		c.addEntry(steppedSlider("End fight after idle", a.fightIdleMs, ad.fightIdleMs, 500, 4, 120,
				ms -> fmt("%.1f s", ms / 1000.0),
				"A fight ends when nobody lands, takes or swings a hit for longer than this.",
				null, v -> live().advanced.fightIdleMs = v));
		c.addEntry(floatSlider("End fight beyond", a.fightMaxDistance, ad.fightMaxDistance, 1f, 6, 64,
				v -> v + " blocks",
				"A fight ends when your opponent is farther away than this.",
				null, v -> live().advanced.fightMaxDistance = v));
		c.addEntry(slider("Minimum events", a.minFightEvents, 1, 20, ad.minFightEvents,
				v -> v == 1 ? "1 event" : v + " events",
				"A draw with fewer swings plus hits taken raises no card or chat line and is left out of session and lifetime totals. A win or loss always counts.",
				null, v -> live().advanced.minFightEvents = v));
		c.addEntry(toggle("Ignore NPCs (players missing from the tab list)", a.ignoreNpcs, ad.ignoreNpcs,
				"Hub NPCs that look like players never start a fight, count as targets or colour the crosshair.",
				null, v -> live().advanced.ignoreNpcs = v));

		IntegerSliderEntry jumpMin = slider("Reset success from", a.jumpWindowMinMs, 0, 100, ad.jumpWindowMinMs,
				v -> fmt("%d ms (tick %d)", v, ceilDiv(v, 50) + 1),
				"Earliest jump that counts as a successful reset, in game time: 0 ms is the first tick after the hit, +50 ms per tick.",
				null, v -> live().advanced.jumpWindowMinMs = v);
		c.addEntry(jumpMin);
		IntegerSliderEntry jumpMax = this.eb.startIntSlider(Component.literal("\u2026to"), clamp(a.jumpWindowMaxMs, 20, 250), 20, 250)
				.setDefaultValue(ad.jumpWindowMaxMs)
				.setTextGetter(v -> Component.literal(fmt("%d ms (tick %d)", v, v / 50 + 1)))
				.setTooltip(Component.literal("Latest jump that counts as a successful reset, in game time (0 ms = first tick after the hit). Must not be below the start."))
				.setErrorSupplier(v -> v < jumpMin.getValue()
						? Optional.of(Component.literal("Reset success \"to\" must not be below \"from\""))
						: Optional.empty())
				.setSaveConsumer(v -> live().advanced.jumpWindowMaxMs = v)
				.build();
		c.addEntry(jumpMax);
		c.addEntry(this.eb.startIntSlider(Component.literal("Jump counts as an attempt within"), clamp(a.jumpAttemptMs, 100, 400), 100, 400)
				.setDefaultValue(ad.jumpAttemptMs)
				.setTextGetter(v -> Component.literal(fmt("%d ms (tick %d)", v, v / 50 + 1)))
				.setTooltip(Component.literal("A jump this soon after a hit (game time) counts as a reset attempt; later jumps are ignored. Must not be below the success window."))
				.setErrorSupplier(v -> v < jumpMax.getValue()
						? Optional.of(Component.literal("The attempt window must not be shorter than the success window"))
						: Optional.empty())
				.setSaveConsumer(v -> live().advanced.jumpAttemptMs = v)
				.build());

		c.addEntry(slider("Combo timeout", a.comboTimeoutMs, 0, 10000, ad.comboTimeoutMs,
				v -> v == 0 ? "Never" : v + " ms",
				"A combo also ends when your next hit comes later than this; 0 = never times out.",
				null, v -> live().advanced.comboTimeoutMs = v));
		c.addEntry(slider("Advantage window", a.advantageWindowMs, 1000, 10000, ad.advantageWindowMs,
				v -> v + " ms",
				"How far back the advantage bar looks when weighing hits dealt against hits taken.",
				null, v -> live().advanced.advantageWindowMs = v));
		c.addEntry(slider("Trade window", a.tradeWindowMs, 100, 1000, ad.tradeWindowMs,
				v -> v + " ms",
				"A hit dealt and a hit taken this close together count as a trade.",
				null, v -> live().advanced.tradeWindowMs = v));
		c.addEntry(floatSlider("Miss target range", a.swingTargetRange, ad.swingTargetRange, 1f, 3, 10,
				v -> v + " blocks",
				"A swing at the air counts as a miss against the nearest player within this range.",
				null, v -> live().advanced.swingTargetRange = v));
		c.addEntry(slider("Miss target cone", a.swingTargetConeDeg, 5, 60, ad.swingTargetConeDeg,
				v -> v + "\u00B0",
				"A swing at the air only counts against a player within this angle of where you look.",
				null, v -> live().advanced.swingTargetConeDeg = v));
		c.addEntry(slider("Interval max gap", a.intervalMaxGapMs, 500, 5000, ad.intervalMaxGapMs,
				v -> v + " ms",
				"Gaps between hits longer than this are left out of the hit interval average.",
				null, v -> live().advanced.intervalMaxGapMs = v));
		c.addEntry(slider("Spectator switch after a hit counts as a death within", a.deathInferenceMs, 0, 10000, ad.deathInferenceMs,
				v -> v == 0 ? "Off" : v + " ms",
				"On servers that turn the loser into a spectator instead of killing them, a switch this soon after the last hit ends the fight as a win or loss.",
				null, v -> live().advanced.deathInferenceMs = v));
		c.addEntry(slider("NO-REG immunity window", a.noRegImmunityTicks, 0, 20, ad.noRegImmunityTicks,
				v -> v == 0 ? "Off" : fmt("%d ticks", v),
				"A hit this soon after your last registered hit on the same player is flagged NO-REG (vanilla: 10 ticks). Set 0 on combo servers with a reduced hit delay.",
				null, v -> live().advanced.noRegImmunityTicks = v));
	}

	// ---- entry helpers --------------------------------------------------------

	private BooleanListEntry toggle(String label, boolean value, boolean def, String tooltip,
			@Nullable Requirement requirement, Consumer<Boolean> save) {
		return this.eb.startBooleanToggle(Component.literal(label), value)
				.setDefaultValue(def)
				.setTooltip(Component.literal(tooltip))
				.setSaveConsumer(save)
				.setRequirement(requirement)
				.build();
	}

	private <E extends Enum<E>> EnumListEntry<E> enumSelector(String label, Class<E> type, E value, E def,
			Function<E, String> names, String tooltip, @Nullable Requirement requirement, Consumer<E> save) {
		return this.eb.startEnumSelector(Component.literal(label), type, value)
				.setDefaultValue(def)
				.setEnumNameProvider(e -> Component.literal(names.apply(type.cast(e))))
				.setTooltip(Component.literal(tooltip))
				.setSaveConsumer(save)
				.setRequirement(requirement)
				.build();
	}

	private IntegerSliderEntry slider(String label, int value, int min, int max, int def, IntFunction<String> text,
			String tooltip, @Nullable Requirement requirement, IntConsumer save) {
		return this.eb.startIntSlider(Component.literal(label), clamp(value, min, max), min, max)
				.setDefaultValue(clamp(def, min, max))
				.setTextGetter(v -> Component.literal(text.apply(v)))
				.setTooltip(Component.literal(tooltip))
				.setSaveConsumer(save::accept)
				.setRequirement(requirement)
				.build();
	}

	/**
	 * An int field edited in steps of {@code step} (§3.12): the slider runs over {@code value / step}.
	 * {@code text} receives the real value. An unmoved slider saves the original value.
	 */
	private IntegerSliderEntry steppedSlider(String label, int value, int def, int step, int minSteps, int maxSteps,
			IntFunction<String> text, String tooltip, @Nullable Requirement requirement, IntConsumer save) {
		int initial = clamp(Math.round(value / (float) step), minSteps, maxSteps);
		return slider(label, initial, minSteps, maxSteps, Math.round(def / (float) step),
				s -> text.apply(s * step), tooltip, requirement,
				s -> save.accept(s == initial ? value : s * step));
	}

	/**
	 * A float field edited as an int slider over {@code value * scale} (§3, "Units"). {@code text}
	 * receives the slider value. An unmoved slider saves the original value.
	 */
	private IntegerSliderEntry floatSlider(String label, float value, float def, float scale, int min, int max,
			IntFunction<String> text, String tooltip, @Nullable Requirement requirement, Consumer<Float> save) {
		int initial = clamp(Math.round(value * scale), min, max);
		return slider(label, initial, min, max, Math.round(def * scale), text, tooltip, requirement,
				s -> save.accept(s == initial ? value : s / scale));
	}

	/**
	 * An alpha colour field ({@code #AARRGGBB}). A {@code text} colour must keep an alpha of at least
	 * {@link KwamsterConfig#MIN_TEXT_ALPHA} (§3). Validation pauses while {@code requirement} fails, and
	 * malformed text is never saved ({@link GatedEntries#colour}).
	 */
	private ColorEntry colour(String label, int value, int def, String tooltip, @Nullable Requirement requirement,
			boolean text, IntConsumer save) {
		return GatedEntries.colour(this.eb, Component.literal(label), value, def, Component.literal(tooltip),
				requirement, text, save);
	}

	private TextListEntry description(String text) {
		return this.eb.startTextDescription(Component.literal(text))
				.setColor(DESCRIPTION_ARGB)
				.build();
	}

	private static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	private static int ceilDiv(int a, int b) {
		return -Math.floorDiv(-a, b);
	}

	private static String fmt(String pattern, Object... args) {
		return String.format(Locale.ROOT, pattern, args);
	}

	private static MutableComponent good(String text) {
		return Component.literal(text).withStyle(ChatFormatting.GREEN);
	}

	private static MutableComponent bad(String text) {
		return Component.literal(text).withStyle(ChatFormatting.RED);
	}

	// ---- readable enum names (§3.12) --------------------------------------------

	private static String displayModeName(DisplayMode m) {
		return switch (m) {
			case HUD_ONLY -> "HUD only";
			case CHAT_ONLY -> "Chat only";
			case BOTH -> "HUD + chat";
		};
	}

	private static String chatModeName(ChatMode m) {
		return switch (m) {
			case OFF -> "Off";
			case PER_FIGHT -> "After each fight";
			case PER_HIT -> "Every hit";
			case BOTH -> "Both";
		};
	}

	private static String panelIdleName(PanelIdle m) {
		return switch (m) {
			case LAST_FIGHT -> "Last fight";
			case SESSION -> "Session totals";
			case HIDE -> "Hidden";
		};
	}

	private static String soundChannelName(SoundChannel m) {
		return switch (m) {
			case MASTER -> "Master";
			case PLAYERS -> "Players";
			case UI -> "UI";
		};
	}

	private static String shapeName(CrosshairShape m) {
		return switch (m) {
			case CROSS -> "Cross";
			case T -> "T";
			case X -> "X";
			case CIRCLE -> "Circle";
			case DOT -> "Dot";
		};
	}

	private static String attackIndicatorName(AttackIndicatorMode m) {
		return switch (m) {
			case KEEP -> "Keep";
			case HIDE -> "Hide";
		};
	}

	private static String stoplightTargetName(StoplightTarget m) {
		return switch (m) {
			case FIGHT_ONLY -> "Current fight only";
			case FIGHT_OR_AIMED -> "Fight or aimed player";
		};
	}

	private static String stoplightStyleName(StoplightStyle m) {
		return switch (m) {
			case VERTICAL -> "Vertical";
			case HORIZONTAL -> "Horizontal";
			case SINGLE -> "Single light";
		};
	}

	private static String tierName(HitQuality q) {
		return switch (q) {
			case THREE_BLOCK_CRIT -> "3-block crit";
			case THREE_BLOCK_SPRINT -> "3-block sprint";
			case THREE_BLOCK -> "3-block hit";
			case CRIT -> "Crit";
			case SPRINT -> "Sprint hit";
			case NORMAL -> "Normal hit";
			case WEAK -> "Weak hit";
		};
	}

	private static String tierDescription(HitQuality q) {
		return switch (q) {
			case THREE_BLOCK_CRIT -> "A charged critical hit landed from at least the 3-block reach.";
			case THREE_BLOCK_SPRINT -> "A charged sprint hit landed from at least the 3-block reach.";
			case THREE_BLOCK -> "Any other charged hit landed from at least the 3-block reach.";
			case CRIT -> "A charged critical hit (falling, not sprinting) from closer than the 3-block reach.";
			case SPRINT -> "A charged sprint hit from closer than the 3-block reach.";
			case NORMAL -> "A charged hit that is neither a crit nor a sprint hit, from closer than the 3-block reach.";
			case WEAK -> "A hit before your attack recharged (below 90% charge).";
		};
	}
}
