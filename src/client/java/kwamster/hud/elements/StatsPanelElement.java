package kwamster.hud.elements;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.StatsTotals;
import kwamster.core.service.FightService;
import kwamster.core.stats.StatFormat;
import kwamster.hud.Anim;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudPreviewData;
import kwamster.hud.PreviewStrings;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The scoreboard-style stats panel (S10, §7.4): a header ({@code vs <name>} with a live timer chip,
 * the last fight's result, or the KWAMSTER wordmark) above one row per enabled {@code PanelRows} field.
 *
 * <p>Scopes: the running fight (live numbers), the last counted fight ({@code LAST_FIGHT}), the
 * session totals ({@code SESSION}), or nothing yet. Rows and width are rebuilt at most every 50 ms;
 * within one scope the width never shrinks, so the panel does not jitter. A row whose value changes
 * flashes from the accent colour back to its own over 300 ms ({@code hud.valueFlash}). Client thread.
 */
public final class StatsPanelElement implements HudElement {
	private static final long REBUILD_NANOS = 50L * Anim.MS;
	private static final long FLASH_MS = 300L;
	private static final double ADV_TAU_MS = 150.0;
	private static final int LIVE_DOT_ARGB = 0xFFFF5A5F;
	/** Advantage label thresholds (§5.5). */
	private static final double ADV_LABEL = 0.15;

	private static final int SCOPE_NONE = 0;
	private static final int SCOPE_FIGHT = 1;
	private static final int SCOPE_LAST = 2;
	private static final int SCOPE_SESSION = 3;
	private static final int SCOPE_EMPTY = 4;

	/** The last fight that raised an alert this session (what LAST_FIGHT shows). */
	private @Nullable FightRecord lastRecord;
	private @Nullable FightStats lastStats;
	private long lastEndedNanos;

	private @Nullable Content live;
	private @Nullable Content preview;
	private long builtAt = Long.MIN_VALUE;
	private int previewSig = Integer.MIN_VALUE;

	private final Map<String, Flash> flashes = new HashMap<>();
	private double advShown;
	private long advFrameNanos;
	private boolean advSeeded;

	@Override
	public HudElementId id() {
		return HudElementId.STATS_PANEL;
	}

	@Override
	public boolean enabled(KwamsterConfig c) {
		return c.display.mode != KwamsterConfig.DisplayMode.CHAT_ONLY;
	}

	@Override
	public boolean active(long nowNanos) {
		return Kwamster.fights().inFight() || Kwamster.config().hud.panelWhenIdle != KwamsterConfig.PanelIdle.HIDE;
	}

	@Override
	public int width(boolean preview) {
		Content c = content(Clock.nanos(), preview);
		return c == null ? HudLayoutMath.PANEL_MIN_W : c.width;
	}

	@Override
	public int height(boolean preview) {
		Content c = content(Clock.nanos(), preview);
		return c == null ? HudLayoutMath.panelHeight(1, false) : c.height;
	}

	/** Bus hook (via HudManager): remembers the last fight that raised an alert, for LAST_FIGHT. */
	public void onFightEnded(FightEndedEvent e, long nowNanos) {
		if (e == null || !e.alert() || e.record() == null || e.stats() == null) {
			return;
		}
		this.lastRecord = e.record();
		this.lastStats = e.stats();
		this.lastEndedNanos = nowNanos;
		this.builtAt = Long.MIN_VALUE;
	}

	/** Forces a rebuild on the next frame (a fight started). */
	public void invalidate() {
		this.builtAt = Long.MIN_VALUE;
	}

	@Override
	public void render(GuiGraphics g, long nowNanos, float alpha, boolean preview) {
		Content c = content(nowNanos, preview);
		if (c == null || alpha <= 0f) {
			return;
		}
		KwamsterConfig cfg = Kwamster.config();
		Font font = Minecraft.getInstance().font;
		boolean compact = cfg.hud.panelCompact;
		int padX = HudLayoutMath.panelPadX(compact);
		int padY = HudLayoutMath.panelPadY(compact);
		int w = c.width;
		int h = c.height;
		int accent = cfg.hud.accentArgb;

		Draw.glass(g, 0, 0, w, h, cfg.hud.panelBgArgb, cfg.hud.borderArgb, Draw.TOP_HIGHLIGHT, alpha);

		// header
		int ty = padY + 1;
		int x = padX;
		for (Seg s : c.left) {
			x += drawSeg(g, font, s, x, ty, alpha, nowNanos);
		}
		if (!c.chip.isEmpty()) {
			int cx = Draw.rightAligned(c.chipWidth, w - padX);
			for (Seg s : c.chip) {
				cx += drawSeg(g, font, s, cx, ty, alpha, nowNanos);
			}
		}
		if (c.rows.isEmpty()) {
			return;
		}

		// divider
		int dividerY = padY + HudLayoutMath.PANEL_HEADER_H + 1;
		Draw.fill(g, 1, dividerY, w - 1, dividerY + 1, Draw.mul(Draw.DIVIDER, alpha));

		// rows
		int rowY = padY + HudLayoutMath.PANEL_HEADER_H + HudLayoutMath.PANEL_DIVIDER_H;
		boolean flash = cfg.hud.valueFlash && !preview;
		for (Row r : c.rows) {
			int textY = rowY + 1;
			Draw.text(g, font, r.label, padX, textY, Draw.mul(r.labelColor, alpha), false);
			int right = w - padX;
			if (r.bar) {
				int barX = right - HudLayoutMath.ADV_BAR_W;
				drawAdvantageBar(g, barX, rowY + 3, preview ? r.barValue : easedAdvantage(r.barValue, nowNanos), alpha);
				right = barX - HudLayoutMath.ADV_BAR_GAP;
			}
			if (!r.segs.isEmpty()) {
				float flashT = flash ? flashProgress(r, nowNanos) : 1f;
				int vx = Draw.rightAligned(r.valueWidth, right);
				for (Seg s : r.segs) {
					Seg drawn = flashT < 1f ? s.withColor(Draw.lerp(accent, s.color, Anim.easeInOutQuad(flashT))) : s;
					vx += drawSeg(g, font, drawn, vx, textY, alpha, nowNanos);
				}
			}
			rowY += HudLayoutMath.PANEL_ROW_H;
		}
	}

	// ---- content -------------------------------------------------------------------------

	private @Nullable Content content(long now, boolean preview) {
		KwamsterConfig cfg = Kwamster.config();
		Font font = Minecraft.getInstance().font;
		if (preview) {
			int sig = configSignature(cfg);
			if (this.preview == null || this.previewSig != sig) {
				this.preview = buildPreview(cfg, font);
				this.previewSig = sig;
			}
			return this.preview;
		}
		Scope scope = scope(cfg);
		Content c = this.live;
		boolean sameScope = c != null && c.scope.equals(scope);
		if (c != null && sameScope && now - this.builtAt < REBUILD_NANOS && now >= this.builtAt) {
			return c;
		}
		Content fresh = build(cfg, font, scope, now, sameScope ? c.width : 0);
		if (!sameScope) {
			this.flashes.clear();
			this.advSeeded = false;
		}
		if (fresh != null) {
			trackFlashes(fresh, now, sameScope);
		}
		this.live = fresh;
		this.builtAt = now;
		return fresh;
	}

	private Scope scope(KwamsterConfig cfg) {
		int sig = configSignature(cfg);
		FightService fs = Kwamster.fights();
		LiveFightView cur = fs.current();
		if (cur != null) {
			return new Scope(SCOPE_FIGHT, cur.startNanos(), sig);
		}
		return switch (cfg.hud.panelWhenIdle) {
			case LAST_FIGHT -> this.lastRecord != null && this.lastStats != null
					? new Scope(SCOPE_LAST, this.lastEndedNanos, sig)
					: new Scope(SCOPE_EMPTY, 0L, sig);
			case SESSION -> new Scope(SCOPE_SESSION, this.lastEndedNanos ^ fs.session().sinceEpochMs, sig);
			case HIDE -> new Scope(SCOPE_NONE, 0L, sig);
		};
	}

	private @Nullable Content build(KwamsterConfig cfg, Font font, Scope scope, long now, int minWidth) {
		Builder b = new Builder(cfg, font);
		switch (scope.kind) {
			case SCOPE_FIGHT -> {
				LiveFightView cur = Kwamster.fights().current();
				if (cur == null) {
					return null;
				}
				FightStats s = cur.stats(now);
				b.liveHeader(cur.opponent().name(), cur.elapsedMs(now));
				b.fightRows(s, true, cfg.feedback.threeBlockReach);
			}
			case SCOPE_LAST -> {
				FightRecord r = this.lastRecord;
				FightStats s = this.lastStats;
				if (r == null || s == null) {
					return null;
				}
				float threshold = r.settings != null && r.settings.threeBlockReach > 0 ? r.settings.threeBlockReach : cfg.feedback.threeBlockReach;
				b.resultHeader(r.opponentName, r.result, r.durationMs);
				b.fightRows(s, false, threshold);
			}
			case SCOPE_SESSION -> {
				StatsTotals t = Kwamster.fights().session();
				b.wordmark(PreviewStrings.SESSION_CHIP);
				if (t.fights <= 0) {
					b.noFights();
				} else {
					b.sessionRows(t, cfg.feedback.threeBlockReach);
				}
			}
			case SCOPE_EMPTY -> {
				b.wordmark(null);
				b.noFights();
			}
			default -> {
				return null;
			}
		}
		return b.finish(scope, minWidth);
	}

	private Content buildPreview(KwamsterConfig cfg, Font font) {
		Builder b = new Builder(cfg, font);
		b.liveHeader(PreviewStrings.SAMPLE_NAME, HudPreviewData.PANEL_ELAPSED_MS);
		b.fightRows(HudPreviewData.panelStats(), true, cfg.feedback.threeBlockReach);
		return b.finish(new Scope(SCOPE_FIGHT, -1L, configSignature(cfg)), 0);
	}

	/** Everything in the config that changes the panel's rows or geometry. */
	private static int configSignature(KwamsterConfig cfg) {
		KwamsterConfig.PanelRows r = cfg.hud.rows;
		int bits = 0;
		boolean[] flags = {r.hits, r.reach, r.combo, r.streak, r.crits, r.sprint, r.threeBlock, r.jumpResets,
				r.interval, r.taken, r.aim, r.trades, r.advantage, r.record, cfg.hud.panelCompact};
		for (int i = 0; i < flags.length; i++) {
			if (flags[i]) {
				bits |= 1 << i;
			}
		}
		int sig = bits * 31 + cfg.hud.panelWhenIdle.ordinal();
		// colours are baked into the cached rows, so a colour change rebuilds them
		int[] colours = {cfg.hud.textArgb, cfg.hud.labelArgb, cfg.hud.accentArgb,
				cfg.tier(HitQuality.THREE_BLOCK).argb, cfg.tier(HitQuality.CRIT).argb, cfg.tier(HitQuality.THREE_BLOCK_CRIT).argb,
				Float.floatToIntBits(cfg.feedback.threeBlockReach)};
		for (int c : colours) {
			sig = sig * 31 + c;
		}
		return sig;
	}

	// ---- flashes and easing ----------------------------------------------------------------

	private void trackFlashes(Content c, long now, boolean sameScope) {
		for (Row r : c.rows) {
			Flash f = this.flashes.get(r.label);
			if (f == null) {
				this.flashes.put(r.label, new Flash(r.valueText, Long.MIN_VALUE));
			} else if (!f.value.equals(r.valueText)) {
				this.flashes.put(r.label, new Flash(r.valueText, sameScope ? now : Long.MIN_VALUE));
			}
		}
	}

	private float flashProgress(Row r, long now) {
		Flash f = this.flashes.get(r.label);
		if (f == null || f.changedAt == Long.MIN_VALUE) {
			return 1f;
		}
		return Anim.progress(now, f.changedAt, FLASH_MS);
	}

	private double easedAdvantage(double target, long now) {
		if (!this.advSeeded) {
			this.advShown = target;
			this.advSeeded = true;
		} else {
			this.advShown = Anim.approach(this.advShown, target, now - this.advFrameNanos, ADV_TAU_MS);
		}
		this.advFrameNanos = now;
		return this.advShown;
	}

	// ---- drawing helpers ---------------------------------------------------------------------

	private static int drawSeg(GuiGraphics g, Font font, Seg s, int x, int y, float alpha, long now) {
		float a = alpha;
		if (s.pulse) {
			a *= Minecraft.getInstance().isPaused() ? 1f : Anim.pulse(now, 1.2, 0.55f, 1f);
		}
		int color = Draw.mul(s.color, a);
		if (s.component != null) {
			return Draw.text(g, font, s.component, x, y, color, false);
		}
		return Draw.text(g, font, s.text, x, y, color, false);
	}

	/** 32×3 track, centre tick, fill from the centre (+A good, −A bad). */
	private static void drawAdvantageBar(GuiGraphics g, int x, int y, double a, float alpha) {
		int w = HudLayoutMath.ADV_BAR_W;
		int h = HudLayoutMath.ADV_BAR_H;
		int mid = x + w / 2;
		Draw.fill(g, x, y, x + w, y + h, Draw.mul(0x33FFFFFF, alpha));
		double clamped = Math.max(-1.0, Math.min(1.0, Double.isFinite(a) ? a : 0.0));
		int px = (int) Math.round(Math.abs(clamped) * (w / 2.0));
		if (px > 0) {
			if (clamped > 0) {
				Draw.fill(g, mid, y, mid + px, y + h, Draw.mul(Draw.GOOD, alpha));
			} else {
				Draw.fill(g, mid - px, y, mid, y + h, Draw.mul(Draw.BAD, alpha));
			}
		}
		Draw.fill(g, mid, y - 1, mid + 1, y + h + 1, Draw.mul(0x66FFFFFF, alpha));
	}

	/** Counts from 10 000 are abbreviated ({@code 12k}, {@code 3M}) so the aim row stays bounded. */
	static String compactCount(int n) {
		if (n < 10_000) {
			return Integer.toString(Math.max(0, n));
		}
		if (n < 1_000_000) {
			return (n / 1000) + "k";
		}
		return (n / 1_000_000) + "M";
	}

	static int resultColor(@Nullable String result) {
		if (FightResult.WIN.name().equals(result)) {
			return Draw.GOOD;
		}
		if (FightResult.LOSS.name().equals(result)) {
			return Draw.BAD;
		}
		return Draw.WARN;
	}

	static int rateColor(double pct, double good, double warn) {
		return pct >= good ? Draw.GOOD : pct >= warn ? Draw.WARN : Draw.BAD;
	}

	// ---- model -------------------------------------------------------------------------------

	/** What the panel shows; {@code id} identifies the fight or idle period. */
	private record Scope(int kind, long id, int configSig) {
	}

	private record Flash(String value, long changedAt) {
	}

	/** One run of text in one colour (bold ones carry their component; pulsing ones blink at 1.2 Hz). */
	private record Seg(String text, int color, @Nullable Component component, boolean pulse) {
		static Seg of(String text, int color) {
			return new Seg(text, color, null, false);
		}

		static Seg bold(String text, int color) {
			return new Seg(text, color, Component.literal(text).withStyle(ChatFormatting.BOLD), false);
		}

		static Seg pulsing(String text, int color) {
			return new Seg(text, color, null, true);
		}

		Seg withColor(int c) {
			return new Seg(this.text, c, this.component, this.pulse);
		}

		int width(Font font) {
			return this.component != null ? font.width(this.component) : font.width(this.text);
		}
	}

	private record Row(String label, int labelColor, List<Seg> segs, String valueText, int valueWidth,
			boolean bar, double barValue) {
	}

	private static final class Content {
		Scope scope;
		List<Seg> left = new ArrayList<>();
		List<Seg> chip = new ArrayList<>();
		int chipWidth;
		List<Row> rows = new ArrayList<>();
		int width;
		int height;
	}

	/** Assembles one {@link Content}: header, rows, width (never below {@code minWidth}) and name truncation. */
	private static final class Builder {
		private final KwamsterConfig cfg;
		private final Font font;
		private final int value;
		private final int label;
		private final int accent;
		private final List<Seg> chip = new ArrayList<>();
		private final List<Row> rows = new ArrayList<>();
		private @Nullable String name;
		private @Nullable Seg wordmark;

		Builder(KwamsterConfig cfg, Font font) {
			this.cfg = cfg;
			this.font = font;
			this.value = cfg.hud.textArgb;
			this.label = cfg.hud.labelArgb;
			this.accent = cfg.hud.accentArgb;
		}

		void liveHeader(@Nullable String opponent, long elapsedMs) {
			this.name = opponent == null ? "" : opponent;
			this.chip.add(Seg.pulsing(PreviewStrings.LIVE_DOT, LIVE_DOT_ARGB));
			this.chip.add(Seg.of(" " + StatFormat.duration(elapsedMs), this.value));
		}

		void resultHeader(@Nullable String opponent, @Nullable String result, long durationMs) {
			this.name = opponent == null ? "" : opponent;
			String word = result == null || result.isEmpty() ? FightResult.DRAW.name() : result;
			this.chip.add(Seg.bold(word, resultColor(word)));
			this.chip.add(Seg.of(PreviewStrings.SEP + StatFormat.duration(durationMs), Draw.DIM));
		}

		void wordmark(@Nullable String chipText) {
			this.wordmark = Seg.bold(PreviewStrings.WORDMARK, this.accent);
			if (chipText != null) {
				this.chip.add(Seg.of(chipText, Draw.DIM));
			}
		}

		void noFights() {
			add(PreviewStrings.NO_FIGHTS, Draw.DIM, List.of(), false, 0);
		}

		void fightRows(FightStats s, boolean live, float threeBlockReach) {
			KwamsterConfig.PanelRows r = this.cfg.hud.rows;
			if (r.hits) {
				String v = StatFormat.ratio(s.landed, s.swings) + PreviewStrings.SEP + StatFormat.pct(s.landed, s.swings);
				int c = s.swings > 0 ? rateColor(100.0 * s.landed / s.swings, 70, 50) : this.value;
				add(PreviewStrings.HITS, v, c);
			}
			if (r.reach) {
				String v = s.reachCount > 0
						? StatFormat.reach(s.reachAvg) + " avg" + PreviewStrings.SEP + StatFormat.reach(s.reachMax)
						: StatFormat.DASH;
				int c = s.reachCount > 0 && s.reachMax >= threeBlockReach - 1e-6 ? tier(HitQuality.THREE_BLOCK) : this.value;
				add(PreviewStrings.REACH, v, c);
			}
			if (r.combo) {
				String v = live ? s.currentCombo + PreviewStrings.SEP + "best " + s.bestCombo : "best " + s.bestCombo;
				add(PreviewStrings.COMBO, v, live && s.currentCombo >= 3 ? this.accent : this.value);
			}
			if (r.streak) {
				String v = live ? s.currentStreak + PreviewStrings.SEP + "best " + s.bestStreak : "best " + s.bestStreak;
				add(PreviewStrings.STREAK, v, this.value);
			}
			if (r.crits) {
				add(PreviewStrings.CRITS, s.crits + PreviewStrings.SEP + StatFormat.pct(s.crits, s.landed),
						s.crits > 0 ? tier(HitQuality.CRIT) : this.value);
			}
			if (r.sprint) {
				add(PreviewStrings.SPRINT, s.sprintHits + PreviewStrings.SEP + StatFormat.pct(s.sprintHits, s.landed), this.value);
			}
			if (r.threeBlock) {
				add(PreviewStrings.THREE_BLOCK, s.threeBlockHits + PreviewStrings.SEP + StatFormat.pct(s.threeBlockHits, s.landed),
						s.threeBlockHits > 0 ? tier(HitQuality.THREE_BLOCK_CRIT) : this.value);
			}
			if (r.jumpResets) {
				add(PreviewStrings.RESETS, resets(s.jumpSuccess, s.jumpAttempts), resetsColor(s.jumpSuccess, s.jumpAttempts));
			}
			if (r.interval) {
				add(PreviewStrings.INTERVAL, StatFormat.ms(s.intervalAvgMs, s.intervalCount), this.value);
			}
			if (r.taken) {
				add(PreviewStrings.TAKEN, Integer.toString(s.hitsTaken), this.value);
			}
			if (r.aim) {
				add(PreviewStrings.AIM, aim(s.aimCenter, s.aimEdge, s.aimHigh, s.aimLow), this.value);
			}
			if (r.trades) {
				addTrades(s.trades, s.tradesWon, s.tradesLost);
			}
			if (r.advantage) {
				if (live) {
					double a = Double.isFinite(s.advantage) ? s.advantage : 0.0;
					String word = a >= ADV_LABEL ? "YOU" : a <= -ADV_LABEL ? "THEM" : "EVEN";
					int c = a >= ADV_LABEL ? Draw.GOOD : a <= -ADV_LABEL ? Draw.BAD : Draw.WARN;
					add(PreviewStrings.ADVANTAGE, this.label, List.of(Seg.of(word, c)), true, a);
				} else {
					add(PreviewStrings.ADVANTAGE, this.label, List.of(
							Seg.of(StatFormat.pct(s.advYouPct), Draw.GOOD),
							Seg.of(PreviewStrings.SEP, this.label),
							Seg.of(StatFormat.pct(s.advThemPct), Draw.BAD)), false, 0);
				}
			}
		}

		void sessionRows(StatsTotals t, float threeBlockReach) {
			KwamsterConfig.PanelRows r = this.cfg.hud.rows;
			if (r.hits) {
				String v = StatFormat.ratio(t.landed, t.swings) + PreviewStrings.SEP + StatFormat.pct(t.landed, t.swings);
				add(PreviewStrings.HITS, v, t.swings > 0 ? rateColor(t.accuracyPct(), 70, 50) : this.value);
			}
			if (r.reach) {
				String v = t.reachCount > 0
						? StatFormat.reach(t.reachAvg()) + " avg" + PreviewStrings.SEP + StatFormat.reach(t.reachMax)
						: StatFormat.DASH;
				int c = t.reachCount > 0 && t.reachMax >= threeBlockReach - 1e-6 ? tier(HitQuality.THREE_BLOCK) : this.value;
				add(PreviewStrings.REACH, v, c);
			}
			if (r.combo) {
				add(PreviewStrings.COMBO, "best " + t.bestCombo, this.value);
			}
			if (r.streak) {
				add(PreviewStrings.STREAK, "best " + t.bestStreak, this.value);
			}
			if (r.crits) {
				add(PreviewStrings.CRITS, t.crits + PreviewStrings.SEP + StatFormat.pct(t.crits, t.landed),
						t.crits > 0 ? tier(HitQuality.CRIT) : this.value);
			}
			if (r.sprint) {
				add(PreviewStrings.SPRINT, t.sprintHits + PreviewStrings.SEP + StatFormat.pct(t.sprintHits, t.landed), this.value);
			}
			if (r.threeBlock) {
				add(PreviewStrings.THREE_BLOCK, t.threeBlock + PreviewStrings.SEP + StatFormat.pct(t.threeBlock, t.landed),
						t.threeBlock > 0 ? tier(HitQuality.THREE_BLOCK_CRIT) : this.value);
			}
			if (r.jumpResets) {
				add(PreviewStrings.RESETS, resets(t.jumpSuccess, t.jumpAttempts), resetsColor(t.jumpSuccess, t.jumpAttempts));
			}
			if (r.interval) {
				add(PreviewStrings.INTERVAL, StatFormat.ms(t.intervalAvgMs(), t.intervalCount), this.value);
			}
			if (r.taken) {
				add(PreviewStrings.TAKEN, Integer.toString(t.hitsTaken), this.value);
			}
			if (r.aim) {
				int[] a = t.aim != null && t.aim.length >= 4 ? t.aim : new int[4];
				add(PreviewStrings.AIM, aim(a[0], a[1], a[2], a[3]), this.value);
			}
			if (r.trades) {
				addTrades(t.trades, t.tradesWon, t.tradesLost);
			}
			if (r.record) {
				add(PreviewStrings.RECORD, t.wins + "W " + t.losses + "L " + t.draws + "D", this.value);
			}
		}

		private void addTrades(int trades, int won, int lost) {
			if (trades <= 0) {
				add(PreviewStrings.TRADES, StatFormat.DASH, this.value);
				return;
			}
			int c = won > lost ? Draw.GOOD : won < lost ? Draw.BAD : this.value;
			add(PreviewStrings.TRADES, won + "W " + lost + "L", c);
		}

		private static String resets(int success, int attempts) {
			return StatFormat.ratio(success, attempts) + PreviewStrings.SEP + StatFormat.pct(success, attempts);
		}

		private int resetsColor(int success, int attempts) {
			return attempts > 0 ? rateColor(100.0 * success / attempts, 60, 30) : this.value;
		}

		private static String aim(int c, int e, int h, int l) {
			return "C" + compactCount(c) + " E" + compactCount(e) + " H" + compactCount(h) + " L" + compactCount(l);
		}

		private int tier(HitQuality q) {
			return this.cfg.tier(q).argb;
		}

		private void add(String labelText, String valueText, int color) {
			add(labelText, this.label, List.of(Seg.of(valueText, color)), false, 0);
		}

		private void add(String labelText, int labelColor, List<Seg> segs, boolean bar, double barValue) {
			StringBuilder text = new StringBuilder();
			int w = 0;
			for (Seg s : segs) {
				text.append(s.text);
				w += s.width(this.font);
			}
			this.rows.add(new Row(labelText, labelColor, segs, text.toString(), w, bar, barValue));
		}

		Content finish(Scope scope, int minWidth) {
			boolean compact = this.cfg.hud.panelCompact;
			Content c = new Content();
			c.scope = scope;
			c.rows = this.rows;
			c.chip = this.chip;
			int chipW = 0;
			for (Seg s : this.chip) {
				chipW += s.width(this.font);
			}
			c.chipWidth = chipW;

			int vsW = this.font.width(PreviewStrings.VS);
			int leftFull = this.wordmark != null ? this.wordmark.width(this.font)
					: vsW + this.font.width(this.name == null ? "" : this.name);
			int widest = chipW > 0 ? leftFull + PreviewStrings.GAP + chipW : leftFull;
			for (Row r : this.rows) {
				int rw = this.font.width(r.label);
				if (!r.segs.isEmpty() || r.bar) {
					rw += PreviewStrings.GAP + r.valueWidth;
				}
				if (r.bar) {
					rw += HudLayoutMath.ADV_BAR_GAP + HudLayoutMath.ADV_BAR_W;
				}
				widest = Math.max(widest, rw);
			}
			c.width = Math.max(minWidth, HudLayoutMath.panelWidth(widest, compact));
			c.height = HudLayoutMath.panelHeight(this.rows.size(), compact);

			if (this.wordmark != null) {
				c.left.add(this.wordmark);
			} else {
				int budget = HudLayoutMath.panelNameBudget(c.width, compact, vsW, chipW);
				String shown = StatFormat.truncate(this.name == null ? "" : this.name, budget, this.font::width);
				c.left.add(Seg.of(PreviewStrings.VS, this.label));
				c.left.add(Seg.of(shown, this.value));
			}
			return c;
		}
	}
}
