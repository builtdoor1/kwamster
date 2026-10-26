package kwamster.core.config;

import kwamster.core.model.HitQuality;
import org.jspecify.annotations.Nullable;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The whole KWAMSTER configuration (§3), stored as pretty Gson in {@code config/kwamster/config.json}
 * by {@link ConfigStore}. Minecraft-free (R6).
 *
 * <p>All fields are public and non-final so the Cloth save consumers write them directly. Readers
 * fetch {@code Kwamster.config()} every frame or tick and never cache a field. {@link #clampAndFill()}
 * runs after every load and before every save.
 */
public final class KwamsterConfig {
	/** The current {@link #configVersion}. */
	public static final int CURRENT_VERSION = 1;
	/** Default viewer base URL (§3.8): GitHub Pages of github.com/builtdoor1/kwamster, served from /docs. */
	public static final String DEFAULT_SHARE_BASE_URL = "https://builtdoor1.github.io/kwamster/";
	/** The pre-release placeholder, migrated to {@link #DEFAULT_SHARE_BASE_URL} by {@link #clampAndFill()}. */
	static final String LEGACY_SHARE_BASE_URL = "https://builtdoor1.github.io/KWAMSTER/";
	/** Text colours whose alpha is below this are forced opaque. */
	public static final int MIN_TEXT_ALPHA = 0x20;

	public int configVersion = CURRENT_VERSION;
	public General general = new General();
	public Display display = new Display();
	public Hud hud = new Hud();
	public Feedback feedback = new Feedback();
	public Alert alert = new Alert();
	public Crosshair crosshair = new Crosshair();
	public Direction direction = new Direction();
	public Recording recording = new Recording();
	public Advanced advanced = new Advanced();
	/** Key = {@code HudElementId.name()}. Edited only by the HUD editor, never by Cloth. */
	public Map<String, HudLayout> layouts = HudLayout.defaults();

	/** A config with every default, already clamped. */
	public static KwamsterConfig defaults() {
		KwamsterConfig c = new KwamsterConfig();
		c.clampAndFill();
		return c;
	}

	/**
	 * Clamps every value to its §3 range, replaces null sections, strings, enums and maps with
	 * defaults, adds missing {@code tiers}/{@code layouts} keys (and drops unknown ones), forces alpha
	 * 0xFF on any text colour whose alpha is below 0x20, reverts an invalid {@code shareBaseUrl}, and
	 * enforces {@code jumpWindowMinMs ≤ jumpWindowMaxMs ≤ jumpAttemptMs}. Idempotent.
	 */
	public void clampAndFill() {
		if (this.configVersion < 1) {
			this.configVersion = CURRENT_VERSION;
		}
		if (this.general == null) {
			this.general = new General();
		}
		if (this.display == null) {
			this.display = new Display();
		}
		if (this.hud == null) {
			this.hud = new Hud();
		}
		if (this.feedback == null) {
			this.feedback = new Feedback();
		}
		if (this.alert == null) {
			this.alert = new Alert();
		}
		if (this.crosshair == null) {
			this.crosshair = new Crosshair();
		}
		if (this.direction == null) {
			this.direction = new Direction();
		}
		if (this.recording == null) {
			this.recording = new Recording();
		}
		if (this.advanced == null) {
			this.advanced = new Advanced();
		}
		this.display.clamp();
		this.hud.clamp();
		this.feedback.clamp();
		this.alert.clamp();
		this.crosshair.clamp();
		this.direction.clamp();
		this.recording.clamp();
		this.advanced.clamp();
		fillLayouts();
	}

	/** The style of tier {@code q}; never null (a missing entry is filled with the default). */
	public TierStyle tier(HitQuality q) {
		if (this.feedback == null) {
			this.feedback = new Feedback();
		}
		if (this.feedback.tiers == null) {
			this.feedback.tiers = TierStyle.defaults();
		}
		TierStyle t = this.feedback.tiers.get(q.name());
		if (t == null) {
			t = TierStyle.defaultFor(q);
			this.feedback.tiers.put(q.name(), t);
		}
		return t;
	}

	/** The layout of element {@code id}; never null (a missing entry is filled with the default). */
	public HudLayout layout(HudElementId id) {
		if (this.layouts == null) {
			this.layouts = HudLayout.defaults();
		}
		HudLayout l = this.layouts.get(id.name());
		if (l == null) {
			l = HudLayout.defaultFor(id);
			this.layouts.put(id.name(), l);
		}
		return l;
	}

	/**
	 * Minecraft-free copy of {@code Identifier.tryParse(trimmed) != null} (Identifier.tryBySeparator): an
	 * optional {@code namespace:} of {@code [a-z0-9_.-]} and a path of {@code [a-z0-9_.-/]}. Blank is invalid.
	 * {@link #clampAndFill()} reverts a tier sound id that fails it to the tier default, so a hand-edited
	 * config can never leave an error on an enabled Cloth entry that blocks Save.
	 */
	public static boolean isValidSoundId(@Nullable String id) {
		if (id == null) {
			return false;
		}
		String s = id.trim();
		if (s.isEmpty()) {
			return false;
		}
		int sep = s.indexOf(':');
		if (sep < 0) {
			return allChars(s, true);
		}
		return allChars(s.substring(sep + 1), true) && allChars(s.substring(0, sep), false);
	}

	private static boolean allChars(String s, boolean path) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			boolean ok = c == '_' || c == '-' || c == '.' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || path && c == '/';
			if (!ok) {
				return false;
			}
		}
		return true;
	}

	/**
	 * The §9.1 base-URL check, shared by {@link #clampAndFill()}, the Cloth error supplier and RECORD:
	 * the trimmed value parses with {@code new URI(...)}, the scheme is http or https (any case) and the
	 * host is non-empty.
	 */
	public static boolean isValidShareBaseUrl(@Nullable String url) {
		if (url == null) {
			return false;
		}
		String trimmed = url.trim();
		if (trimmed.isEmpty()) {
			return false;
		}
		try {
			URI uri = new URI(trimmed);
			String scheme = uri.getScheme();
			if (scheme == null) {
				return false;
			}
			String s = scheme.toLowerCase(Locale.ROOT);
			if (!s.equals("http") && !s.equals("https")) {
				return false;
			}
			String host = uri.getHost();
			return host != null && !host.isEmpty();
		} catch (URISyntaxException e) {
			return false;
		}
	}

	private void fillLayouts() {
		Map<String, HudLayout> filled = new LinkedHashMap<>();
		for (HudElementId id : HudElementId.values()) {
			HudLayout l = this.layouts == null ? null : this.layouts.get(id.name());
			HudLayout def = HudLayout.defaultFor(id);
			if (l == null) {
				l = def;
			}
			if (l.anchor == null) {
				l.anchor = def.anchor;
			}
			l.scale = clampF(l.scale, HudLayout.MIN_SCALE, HudLayout.MAX_SCALE, 1f);
			filled.put(id.name(), l);
		}
		this.layouts = filled;
	}

	// ---- clamping helpers ---------------------------------------------------

	static int clamp(int v, int lo, int hi) {
		return Math.max(lo, Math.min(hi, v));
	}

	static float clampF(float v, float lo, float hi, float fallback) {
		if (!Float.isFinite(v)) {
			return fallback;
		}
		return Math.max(lo, Math.min(hi, v));
	}

	/** Forces alpha 0xFF when the alpha of a text colour is below {@link #MIN_TEXT_ALPHA}. */
	static int textColour(int argb) {
		return (argb >>> 24) < MIN_TEXT_ALPHA ? argb | 0xFF000000 : argb;
	}

	static <E extends Enum<E>> E orDefault(@Nullable E value, E fallback) {
		return value == null ? fallback : value;
	}

	// ---- enums --------------------------------------------------------------

	/** What the stats panel and chat show (S10): HUD only hides chat output, chat only hides the stats panel. */
	public enum DisplayMode { HUD_ONLY, CHAT_ONLY, BOTH }

	/** When chat lines are printed: never, after each fight, after every hit, or both. */
	public enum ChatMode { OFF, PER_FIGHT, PER_HIT, BOTH }

	/** What the stats panel shows outside a fight. */
	public enum PanelIdle { LAST_FIGHT, SESSION, HIDE }

	/** Which volume slider the hit sounds follow. */
	public enum SoundChannel { MASTER, PLAYERS, UI }

	/** Custom crosshair shapes (§7.8). */
	public enum CrosshairShape { CROSS, T, X, CIRCLE, DOT }

	/** Whether vanilla's crosshair attack indicator is kept or hidden. */
	public enum AttackIndicatorMode { KEEP, HIDE }

	/** Whose movement the stoplight shows: the fight opponent only, or also the aimed-at player. */
	public enum StoplightTarget { FIGHT_ONLY, FIGHT_OR_AIMED }

	/** Stoplight housing layout (§7.7). */
	public enum StoplightStyle { VERTICAL, HORIZONTAL, SINGLE }

	// ---- sections -----------------------------------------------------------

	/** §3.1 (Cloth category "General"). */
	public static final class General {
		/** Master switch (R4). */
		public boolean enabled = true;
		/** Hide all HUD elements (also the toggle-HUD key). */
		public boolean hudHidden = false;
		/** Hide the HUD while the F3 overlay is open. */
		public boolean hideInDebug = true;
	}

	/** §3.2 (S10; Cloth category "General"). */
	public static final class Display {
		public DisplayMode mode = DisplayMode.BOTH;
		public ChatMode chatMode = ChatMode.PER_FIGHT;
		/** 1..3. */
		public int chatFightLines = 2;
		public boolean chatShareActions = true;
		public boolean chatHitMisses = true;
		public boolean chatJumpResets = true;

		void clamp() {
			this.mode = orDefault(this.mode, DisplayMode.BOTH);
			this.chatMode = orDefault(this.chatMode, ChatMode.PER_FIGHT);
			this.chatFightLines = KwamsterConfig.clamp(this.chatFightLines, 1, 3);
		}
	}

	/** Stats panel rows (§3.3), drawn in field order. */
	public static final class PanelRows {
		public boolean hits = true;
		public boolean reach = true;
		public boolean combo = true;
		public boolean streak = false;
		public boolean crits = true;
		public boolean sprint = true;
		public boolean threeBlock = true;
		public boolean jumpResets = true;
		public boolean interval = false;
		public boolean taken = false;
		public boolean aim = false;
		public boolean trades = false;
		public boolean advantage = true;
		/** Drawn only in SESSION scope. */
		public boolean record = true;
	}

	/** §3.3 (Cloth category "HUD"). */
	public static final class Hud {
		public PanelIdle panelWhenIdle = PanelIdle.LAST_FIGHT;
		public boolean panelCompact = false;
		public PanelRows rows = new PanelRows();
		public boolean comboCounterEnabled = true;
		/** 2..5. */
		public int comboCounterMin = 2;
		public boolean valueFlash = true;
		public int accentArgb = 0xFF8B5CF6;
		public int panelBgArgb = 0xB80E1116;
		public int borderArgb = 0x26FFFFFF;
		/** Alpha ≥ 0x20. */
		public int textArgb = 0xFFF1F3F5;
		/** Alpha ≥ 0x20. */
		public int labelArgb = 0xFF9AA4B2;

		void clamp() {
			this.panelWhenIdle = orDefault(this.panelWhenIdle, PanelIdle.LAST_FIGHT);
			if (this.rows == null) {
				this.rows = new PanelRows();
			}
			this.comboCounterMin = KwamsterConfig.clamp(this.comboCounterMin, 2, 5);
			this.textArgb = textColour(this.textArgb);
			this.labelArgb = textColour(this.labelArgb);
		}
	}

	/** §3.4 (S8, S9; Cloth category "Hit feedback"). */
	public static final class Feedback {
		/** 2.50..3.00 blocks. */
		public float threeBlockReach = 2.95f;
		public boolean soundsEnabled = true;
		public SoundChannel soundChannel = SoundChannel.PLAYERS;
		public boolean indicatorEnabled = true;
		/** 300..3000. */
		public int indicatorDurationMs = 800;
		public boolean indicatorShowMisses = true;
		public boolean indicatorShowReach = true;
		/** Key = {@code HitQuality.name()}. */
		public Map<String, TierStyle> tiers = TierStyle.defaults();

		void clamp() {
			this.threeBlockReach = clampF(this.threeBlockReach, 2.50f, 3.00f, 2.95f);
			this.soundChannel = orDefault(this.soundChannel, SoundChannel.PLAYERS);
			this.indicatorDurationMs = KwamsterConfig.clamp(this.indicatorDurationMs, 300, 3000);
			Map<String, TierStyle> filled = new LinkedHashMap<>();
			for (HitQuality q : HitQuality.values()) {
				TierStyle t = this.tiers == null ? null : this.tiers.get(q.name());
				TierStyle def = TierStyle.defaultFor(q);
				if (t == null) {
					t = def;
				}
				if (t.label == null || t.label.isBlank()) {
					t.label = def.label;
				} else if (t.label.length() > TierStyle.MAX_LABEL_LENGTH) {
					t.label = t.label.substring(0, TierStyle.MAX_LABEL_LENGTH);
				}
				// A syntactically invalid id would leave an error on the Cloth entry and block Save on every tab.
				t.soundId = isValidSoundId(t.soundId) ? t.soundId.trim() : def.soundId;
				t.volumePct = KwamsterConfig.clamp(t.volumePct, 0, 100);
				t.pitchPct = KwamsterConfig.clamp(t.pitchPct, 50, 200);
				// The tier colour is also a text colour (indicator label, chat line).
				t.argb = textColour(t.argb);
				filled.put(q.name(), t);
			}
			this.tiers = filled;
		}
	}

	/** §3.5 (S11; Cloth category "Fight alert"). */
	public static final class Alert {
		public boolean cardEnabled = true;
		/** 2000..30000. */
		public int cardDurationMs = 7000;
		/** 0..1500. */
		public int cardFadeMs = 400;
		public boolean cardFooterHint = true;

		void clamp() {
			this.cardDurationMs = KwamsterConfig.clamp(this.cardDurationMs, 2000, 30000);
			this.cardFadeMs = KwamsterConfig.clamp(this.cardFadeMs, 0, 1500);
		}
	}

	/** §3.6 (S15; Cloth category "Crosshair"). Sizes are physical pixels. */
	public static final class Crosshair {
		public boolean enabled = true;
		public boolean scaleWithGui = false;
		public CrosshairShape shape = CrosshairShape.CROSS;
		/** 1..40. */
		public int size = 6;
		/** 0..20. */
		public int gap = 3;
		/** 1..10. */
		public int thickness = 2;
		/** 0..3 (0 = none). */
		public int outline = 1;
		public int outlineArgb = 0xB4000000;
		/** 0..10 (0 = off). */
		public int centerDot = 0;
		public AttackIndicatorMode attackIndicator = AttackIndicatorMode.KEEP;
		public int defaultArgb = 0xFFFFFFFF;
		public int onPlayerArgb = 0xFFFF4D4D;
		public int critReadyArgb = 0xFF38D9FF;
		public int critOnPlayerArgb = 0xFFFF6BD6;
		public boolean targetMobs = false;
		public boolean hitFlash = true;
		/** 0..600. */
		public int hitFlashMs = 150;

		void clamp() {
			this.shape = orDefault(this.shape, CrosshairShape.CROSS);
			this.size = KwamsterConfig.clamp(this.size, 1, 40);
			this.gap = KwamsterConfig.clamp(this.gap, 0, 20);
			this.thickness = KwamsterConfig.clamp(this.thickness, 1, 10);
			this.outline = KwamsterConfig.clamp(this.outline, 0, 3);
			this.centerDot = KwamsterConfig.clamp(this.centerDot, 0, 10);
			this.attackIndicator = orDefault(this.attackIndicator, AttackIndicatorMode.KEEP);
			this.hitFlashMs = KwamsterConfig.clamp(this.hitFlashMs, 0, 600);
		}
	}

	/**
	 * §3.7 (S12; Cloth category "Direction"): the above-head bars and the jump cue, ported from
	 * Direction Indicator (§5.11a), then the optional HUD stoplight (§5.11).
	 */
	public static final class Direction {
		// ---- above-head bars (§5.11a) ----
		/** A flat, camera-facing bar above every nearby player's head. */
		public boolean headBarEnabled = true;
		/** Also draw your own bar; only visible with a detached camera (F5). */
		public boolean headBarForSelf = false;
		/** 1..128 blocks, shared by the bars and the jump cue. */
		public int radius = 24;
		/** 0..500 thousandths of a block per tick; slower reads as idle. Sneaking is ~65, walking ~216. */
		public int moveThreshold = 20;
		public int forwardArgb = 0xEB4CD964;
		public int backwardArgb = 0xEBE03131;
		public int idleArgb = 0xEBFFD43B;
		public int backdropArgb = 0x8C000000;
		/** 5..300 hundredths of a block. */
		public int barWidth = 55;
		/** 2..100 hundredths of a block. */
		public int barHeight = 11;
		/** 0..300 hundredths of a block above the top of the hitbox (between the head and the nametag). */
		public int headClearance = 30;

		// ---- jump cue (§5.11a) ----
		public boolean jumpSoundEnabled = true;
		/** Off by default: the cue is for hearing other players jump. */
		public boolean jumpSoundForSelf = false;
		/** 0..200 percent. */
		public int jumpSoundVolume = 60;
		/** 50..200 percent. */
		public int jumpSoundPitch = 160;
		/** 0..40 ticks (0 = off): after a grounded player is hit, their next launch is knockback, not a jump. */
		public int jumpKnockbackTicks = 10;

		// ---- HUD stoplight (§5.11) ----
		/** Off by default since the head bars took over; replaces the pre-1.0 {@code enabled} key. */
		public boolean stoplightEnabled = false;
		public StoplightTarget target = StoplightTarget.FIGHT_OR_AIMED;
		/** 0.3..4.0 blocks per second. */
		public float enterBps = 1.0f;
		/** 0.0..2.0 blocks per second. */
		public float hysteresisBps = 0.5f;
		/** 0..600. */
		public int smoothingMs = 150;
		/** 0..600. */
		public int minHoldMs = 150;
		/** 0..1000. */
		public int kbHoldMs = 300;
		/** 3..16 blocks. */
		public float aimedRange = 8.0f;
		public StoplightStyle style = StoplightStyle.VERTICAL;
		public boolean showLabel = false;
		public boolean hideWhenNoTarget = true;
		public int greenArgb = 0xFF2BD96B;
		public int yellowArgb = 0xFFFFC53D;
		public int redArgb = 0xFFFF4D4F;
		public int offArgb = 0xFF2A2F37;

		void clamp() {
			this.radius = KwamsterConfig.clamp(this.radius, 1, 128);
			this.moveThreshold = KwamsterConfig.clamp(this.moveThreshold, 0, 500);
			this.barWidth = KwamsterConfig.clamp(this.barWidth, 5, 300);
			this.barHeight = KwamsterConfig.clamp(this.barHeight, 2, 100);
			this.headClearance = KwamsterConfig.clamp(this.headClearance, 0, 300);
			this.jumpSoundVolume = KwamsterConfig.clamp(this.jumpSoundVolume, 0, 200);
			this.jumpSoundPitch = KwamsterConfig.clamp(this.jumpSoundPitch, 50, 200);
			this.jumpKnockbackTicks = KwamsterConfig.clamp(this.jumpKnockbackTicks, 0, 40);
			this.target = orDefault(this.target, StoplightTarget.FIGHT_OR_AIMED);
			this.enterBps = clampF(this.enterBps, 0.3f, 4.0f, 1.0f);
			this.hysteresisBps = clampF(this.hysteresisBps, 0.0f, 2.0f, 0.5f);
			this.smoothingMs = KwamsterConfig.clamp(this.smoothingMs, 0, 600);
			this.minHoldMs = KwamsterConfig.clamp(this.minHoldMs, 0, 600);
			this.kbHoldMs = KwamsterConfig.clamp(this.kbHoldMs, 0, 1000);
			this.aimedRange = clampF(this.aimedRange, 3f, 16f, 8.0f);
			this.style = orDefault(this.style, StoplightStyle.VERTICAL);
		}
	}

	/** §3.8 (S13, S14; Cloth category "Recording &amp; sharing"). */
	public static final class Recording {
		public boolean saveFights = true;
		public boolean saveTrivialFights = true;
		/** 0..5000 (0 = unlimited). */
		public int maxSavedFights = 500;
		/** Valid per §9.1; {@code clampAndFill} reverts an invalid value to the default. */
		public String shareBaseUrl = DEFAULT_SHARE_BASE_URL;
		public boolean shareIncludeServer = true;
		public boolean sharePositions = true;
		/** 600..8000. */
		public int shareMaxChars = 1900;

		void clamp() {
			this.maxSavedFights = KwamsterConfig.clamp(this.maxSavedFights, 0, 5000);
			this.shareBaseUrl = isValidShareBaseUrl(this.shareBaseUrl) ? this.shareBaseUrl.trim() : DEFAULT_SHARE_BASE_URL;
			if (this.shareBaseUrl.equals(LEGACY_SHARE_BASE_URL)) {
				// The pre-release placeholder; the viewer was published under the lower-case repo name.
				this.shareBaseUrl = DEFAULT_SHARE_BASE_URL;
			}
			this.shareMaxChars = KwamsterConfig.clamp(this.shareMaxChars, 600, 8000);
		}
	}

	/** §3.9 detection constants (Cloth category "Advanced"). Jump windows are game time (§5.1). */
	public static final class Advanced {
		/** 2000..60000. */
		public int fightIdleMs = 8000;
		/** 6..64 blocks (3-D feet distance). */
		public float fightMaxDistance = 16.0f;
		/** 1..20. */
		public int minFightEvents = 3;
		/** Ignore NPCs: players missing from the tab list, or with v2 UUIDs (§5.4). */
		public boolean ignoreNpcs = true;
		/** 0..100, game ms. */
		public int jumpWindowMinMs = 0;
		/** 20..250 and ≥ min, game ms. */
		public int jumpWindowMaxMs = 80;
		/** 100..400 and ≥ max, game ms. */
		public int jumpAttemptMs = 200;
		/** 0..10000 (0 = never). */
		public int comboTimeoutMs = 2000;
		/** 1000..10000. */
		public int advantageWindowMs = 3000;
		/** 100..1000. */
		public int tradeWindowMs = 400;
		/** 3..10 blocks. */
		public float swingTargetRange = 6.0f;
		/** 5..60 degrees. */
		public int swingTargetConeDeg = 30;
		/** 500..5000. */
		public int intervalMaxGapMs = 2000;
		/** 0..10000. */
		public int deathInferenceMs = 3000;
		/**
		 * 0..20 ticks: the server's hurt-immunity window used for NO-REG detection (§5.4). 10 is vanilla;
		 * 0 turns detection off (servers with a reduced hit delay).
		 */
		public int noRegImmunityTicks = 10;
		/**
		 * Infer a win or loss when a fight ends abruptly right after the last hit, as on practice and duel
		 * servers that teleport the loser away instead of killing them (§5.13a).
		 */
		public boolean inferOutcomes = true;
		/** Read kill and win messages from server chat and titles to decide the result (§5.13a). */
		public boolean readOutcomeMessages = true;
		/** 500..5000: how soon after the last hit an abrupt end still decides the result (§5.13a). */
		public int outcomeWindowMs = 1500;

		void clamp() {
			this.fightIdleMs = KwamsterConfig.clamp(this.fightIdleMs, 2000, 60000);
			this.fightMaxDistance = clampF(this.fightMaxDistance, 6f, 64f, 16.0f);
			this.minFightEvents = KwamsterConfig.clamp(this.minFightEvents, 1, 20);
			this.jumpWindowMinMs = KwamsterConfig.clamp(this.jumpWindowMinMs, 0, 100);
			this.jumpWindowMaxMs = Math.max(KwamsterConfig.clamp(this.jumpWindowMaxMs, 20, 250), this.jumpWindowMinMs);
			this.jumpAttemptMs = Math.max(KwamsterConfig.clamp(this.jumpAttemptMs, 100, 400), this.jumpWindowMaxMs);
			this.comboTimeoutMs = KwamsterConfig.clamp(this.comboTimeoutMs, 0, 10000);
			this.advantageWindowMs = KwamsterConfig.clamp(this.advantageWindowMs, 1000, 10000);
			this.tradeWindowMs = KwamsterConfig.clamp(this.tradeWindowMs, 100, 1000);
			this.swingTargetRange = clampF(this.swingTargetRange, 3f, 10f, 6.0f);
			this.swingTargetConeDeg = KwamsterConfig.clamp(this.swingTargetConeDeg, 5, 60);
			this.intervalMaxGapMs = KwamsterConfig.clamp(this.intervalMaxGapMs, 500, 5000);
			this.deathInferenceMs = KwamsterConfig.clamp(this.deathInferenceMs, 0, 10000);
			this.noRegImmunityTicks = KwamsterConfig.clamp(this.noRegImmunityTicks, 0, 20);
			this.outcomeWindowMs = KwamsterConfig.clamp(this.outcomeWindowMs, 500, 5000);
		}
	}
}
