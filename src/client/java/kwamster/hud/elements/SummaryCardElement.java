package kwamster.hud.elements;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.Keybinds;
import kwamster.core.LogLimiter;
import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.FightSummaryRow;
import kwamster.core.model.HitQuality;
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
import java.util.List;

/**
 * The fight summary card (S11, §7.9): a 2 px result bar, a header ({@code WIN vs Kwamix ... 0:42}),
 * a divider and a 3 × 3 grid of figures, with an optional footer that tells how to share the fight.
 *
 * <p>Raised by a {@link FightEndedEvent} with {@code alert} while a level is loaded and the mod is
 * enabled. Enters by sliding down 8 px and fading in over 180 ms, holds for {@code cardDurationMs},
 * fades out over the last {@code cardFadeMs}; a new fight fades it out over 200 ms. The 24 px progress
 * line shrinks over the remaining time. Client thread.
 */
public final class SummaryCardElement implements HudElement {
	private static final long ENTER_MS = 180L;
	private static final long DISMISS_MS = 200L;
	private static final int SLIDE_PX = 8;
	private static final int CARD_BG_ALPHA = 0xE0;
	/** Faint lines in the gutters between the three columns. */
	private static final int GUTTER_LINE = 0x14FFFFFF;
	/** How many newest index rows are searched for the saved fight. */
	private static final int SAVED_LOOKUP_ROWS = 4;
	private static final long SAVED_RECHECK_NANOS = 500L * Anim.MS;

	private @Nullable Card card;
	private @Nullable Card previewCard;
	private int previewSig = Integer.MIN_VALUE;

	@Override
	public HudElementId id() {
		return HudElementId.SUMMARY_CARD;
	}

	@Override
	public boolean enabled(KwamsterConfig c) {
		return c.alert.cardEnabled;
	}

	@Override
	public boolean active(long nowNanos) {
		Card c = this.card;
		if (c == null) {
			return false;
		}
		boolean entering = c.dismissAt == Long.MIN_VALUE && nowNanos - c.shownAt < ENTER_MS * Anim.MS;
		if (entering || visibility(c, nowNanos) > 0f) {
			return true;
		}
		if (Anim.ageMs(nowNanos, c.shownAt) >= Kwamster.config().alert.cardDurationMs) {
			this.card = null;
		}
		return false;
	}

	@Override
	public int width(boolean preview) {
		Card c = preview ? preview() : this.card;
		return c == null ? HudLayoutMath.cardWidth(HudLayoutMath.CARD_COL_MIN) : c.width;
	}

	@Override
	public int height(boolean preview) {
		Card c = preview ? preview() : this.card;
		return HudLayoutMath.cardHeight(c != null && footerShown(c, Kwamster.config()));
	}

	/** Bus hook (via HudManager): raises the card for a fight that alerted. */
	public void onFightEnded(FightEndedEvent e, long nowNanos) {
		if (e == null || !e.alert() || e.record() == null || e.stats() == null) {
			return;
		}
		Minecraft mc = Minecraft.getInstance();
		if (!Kwamster.config().general.enabled || mc.level == null) {
			return;
		}
		Card c = build(e.record(), e.stats(), mc.font, Kwamster.config());
		c.shownAt = nowNanos;
		c.linkAvailable = shareLinkAvailable(e.record());
		c.saved = savedInArchive(e.record());
		c.savedCheckedAt = nowNanos;
		this.card = c;
	}

	/** Bus hook (via HudManager): a new fight fades a showing card out over 200 ms. */
	public void onFightStarted(long nowNanos) {
		Card c = this.card;
		if (c != null && c.dismissAt == Long.MIN_VALUE) {
			c.dismissAt = nowNanos;
		}
	}

	/** The drawn alpha of the in-game card at {@code now} (0 when none), used for the panel's card priority. */
	public float visibility(long nowNanos) {
		Card c = this.card;
		return c == null ? 0f : visibility(c, nowNanos);
	}

	@Override
	public void render(GuiGraphics g, long nowNanos, float alpha, boolean preview) {
		Card c = preview ? preview() : this.card;
		if (c == null) {
			return;
		}
		KwamsterConfig cfg = Kwamster.config();
		float a = alpha;
		float slide = 0f;
		float remaining = 1f;
		if (!preview) {
			a *= visibility(c, nowNanos);
			float enter = Anim.easeOutCubic(Anim.progress(nowNanos, c.shownAt, ENTER_MS));
			slide = -SLIDE_PX * (1f - enter);
			remaining = 1f - Anim.clamp01((float) (Anim.ageMs(nowNanos, c.shownAt) / Math.max(1, cfg.alert.cardDurationMs)));
			if (!c.saved && nowNanos - c.savedCheckedAt > SAVED_RECHECK_NANOS) {
				c.saved = savedInArchive(c.record);
				c.savedCheckedAt = nowNanos;
			}
		}
		if (a <= 0f) {
			return;
		}
		Font font = Minecraft.getInstance().font;
		boolean footer = footerShown(c, cfg);
		int w = c.width;
		int h = HudLayoutMath.cardHeight(footer);

		var pose = g.pose();
		boolean sliding = slide != 0f;
		if (sliding) {
			pose.pushMatrix();
			pose.translate(0f, slide);
		}

		int bg = (cfg.hud.panelBgArgb & 0x00FFFFFF) | (CARD_BG_ALPHA << 24);
		Draw.glass(g, 0, 0, w, h, bg, cfg.hud.borderArgb, 0, a);
		// 2 px result bar along the top, following the cut corners
		int bar = Draw.mul(c.resultColor, a);
		Draw.fill(g, 1, 0, w - 1, 1, bar);
		Draw.fill(g, 0, 1, w, HudLayoutMath.CARD_BAR_H, bar);

		// header: WIN  vs Name ............ 0:42
		int px = HudLayoutMath.CARD_PAD_X;
		int ty = HudLayoutMath.CARD_BAR_H + HudLayoutMath.CARD_PAD_Y;
		int x = px + Draw.text(g, font, c.resultWord, px, ty, Draw.mul(c.resultColor, a), false) + HudLayoutMath.CARD_RESULT_GAP;
		x += Draw.text(g, font, PreviewStrings.VS, x, ty, Draw.mul(cfg.hud.labelArgb, a), false);
		Draw.text(g, font, c.name, x, ty, Draw.mul(cfg.hud.textArgb, a), false);
		Draw.textRight(g, font, c.duration, w - px, ty, Draw.mul(Draw.DIM, a), false);

		// divider
		int dividerY = ty + HudLayoutMath.CARD_HEADER_H + 2;
		Draw.fill(g, 1, dividerY, w - 1, dividerY + 1, Draw.mul(Draw.DIVIDER, a));

		// 3 × 3 cells
		int gridY = ty + HudLayoutMath.CARD_HEADER_H + HudLayoutMath.CARD_DIVIDER_H;
		for (int col = 0; col < 2; col++) {
			int gx = HudLayoutMath.cardColX(col, c.colW) + c.colW + HudLayoutMath.CARD_GUTTER / 2;
			Draw.fill(g, gx, gridY + 1, gx + 1, gridY + HudLayoutMath.CARD_ROWS * HudLayoutMath.CARD_ROW_H - 1, Draw.mul(GUTTER_LINE, a));
		}
		for (int i = 0; i < c.cells.size() && i < 9; i++) {
			Cell cell = c.cells.get(i);
			int col = i % 3;
			int row = i / 3;
			int cx = HudLayoutMath.cardColX(col, c.colW);
			int cy = gridY + row * HudLayoutMath.CARD_ROW_H + 1;
			Draw.text(g, font, cell.label, cx, cy, Draw.mul(cfg.hud.labelArgb, a), false);
			Draw.textRight(g, font, cell.value, cx + c.colW, cy, Draw.mul(cell.color, a), false);
		}

		// footer and progress line
		int rowsEnd = gridY + HudLayoutMath.CARD_ROWS * HudLayoutMath.CARD_ROW_H;
		int lineY;
		if (footer) {
			String text = StatFormat.truncate(footerText(c, preview), HudLayoutMath.cardFooterBudget(w), font::width);
			Draw.text(g, font, text, px, rowsEnd + 1, Draw.mul(Draw.DIM, a), false);
			lineY = rowsEnd + 4;
		} else {
			lineY = rowsEnd + 2;
		}
		int len = Math.round(HudLayoutMath.CARD_PROGRESS_W * Anim.clamp01(remaining));
		Draw.fill(g, w - px - len, lineY, w - px, lineY + 1, Draw.mul(cfg.hud.accentArgb, a));

		if (sliding) {
			pose.popMatrix();
		}
	}

	// ---- state -----------------------------------------------------------------------------

	private static float visibility(Card c, long now) {
		KwamsterConfig.Alert alert = Kwamster.config().alert;
		double age = Anim.ageMs(now, c.shownAt);
		float a = Anim.envelope(age, 0, alert.cardDurationMs, alert.cardFadeMs)
				* Anim.easeOutCubic(Anim.progress(now, c.shownAt, ENTER_MS));
		if (c.dismissAt != Long.MIN_VALUE) {
			a *= 1f - Anim.progress(now, c.dismissAt, DISMISS_MS);
		}
		return a;
	}

	/**
	 * §7.9: the footer shows only when the hint is on and adds information, i.e. when the chat summary
	 * does not already carry the share actions.
	 */
	private static boolean footerShown(Card c, KwamsterConfig cfg) {
		if (!cfg.alert.cardFooterHint) {
			return false;
		}
		boolean chatSummaryWithLink = cfg.general.enabled
				&& cfg.display.mode != KwamsterConfig.DisplayMode.HUD_ONLY
				&& (cfg.display.chatMode == KwamsterConfig.ChatMode.PER_FIGHT || cfg.display.chatMode == KwamsterConfig.ChatMode.BOTH)
				&& cfg.display.chatShareActions
				&& c.linkAvailable;
		return !chatSummaryWithLink;
	}

	private static String footerText(Card c, boolean preview) {
		if (preview) {
			return PreviewStrings.footer(HudPreviewData.cardKeyName());
		}
		if (!c.saved) {
			return "Not saved";
		}
		if (Keybinds.COPY_LAST_LINK.isUnbound()) {
			return "Saved";
		}
		return PreviewStrings.footer(Keybinds.COPY_LAST_LINK.getTranslatedKeyMessage().getString());
	}

	private static boolean shareLinkAvailable(FightRecord record) {
		try {
			return Kwamster.services().archive().shareLink(record) != null;
		} catch (Throwable t) {
			LogLimiter.error("SummaryCardElement.shareLink", "FightArchive.shareLink failed", t);
			return false;
		}
	}

	/** True when RECORD already indexed this fight (its listener runs before the HUD's, §2.1 order). */
	private static boolean savedInArchive(FightRecord record) {
		if (record == null || record.id == null) {
			return false;
		}
		try {
			List<FightSummaryRow> rows = Kwamster.services().archive().list();
			for (int i = 0; i < rows.size() && i < SAVED_LOOKUP_ROWS; i++) {
				FightSummaryRow row = rows.get(i);
				if (row != null && record.id.equals(row.id)) {
					return true;
				}
			}
		} catch (Throwable t) {
			LogLimiter.error("SummaryCardElement.saved", "FightArchive.list failed", t);
		}
		return false;
	}

	private Card preview() {
		KwamsterConfig cfg = Kwamster.config();
		int sig = cfg.tier(HitQuality.THREE_BLOCK).argb * 31 + cfg.tier(HitQuality.THREE_BLOCK_CRIT).argb * 17
				+ cfg.hud.textArgb + Float.floatToIntBits(cfg.feedback.threeBlockReach);
		if (this.previewCard == null || this.previewSig != sig) {
			Card c = build(HudPreviewData.cardRecord(), HudPreviewData.cardStats(), Minecraft.getInstance().font, cfg);
			c.linkAvailable = true;
			c.saved = true;
			this.previewCard = c;
			this.previewSig = sig;
		}
		return this.previewCard;
	}

	/** Formats everything the card shows; the numbers of a finished fight never change. */
	private static Card build(FightRecord r, FightStats s, Font font, KwamsterConfig cfg) {
		Card c = new Card(r);
		String result = r.result == null || r.result.isEmpty() ? FightResult.DRAW.name() : r.result;
		c.resultColor = StatsPanelElement.resultColor(result);
		c.resultWord = Component.literal(result).withStyle(ChatFormatting.BOLD);
		c.duration = StatFormat.duration(r.durationMs);
		float threshold = r.settings != null && r.settings.threeBlockReach > 0 ? r.settings.threeBlockReach : cfg.feedback.threeBlockReach;
		int value = cfg.hud.textArgb;

		List<Cell> cells = new ArrayList<>(9);
		int accColor = s.swings > 0 ? StatsPanelElement.rateColor(100.0 * s.landed / s.swings, 70, 50) : value;
		cells.add(new Cell(PreviewStrings.ACC, StatFormat.pct(s.landed, s.swings), accColor));
		cells.add(new Cell(PreviewStrings.REACH, StatFormat.reach(s.reachAvg, s.reachCount), value));
		int maxColor = s.reachCount > 0 && s.reachMax >= threshold - 1e-6 ? cfg.tier(HitQuality.THREE_BLOCK).argb : value;
		cells.add(new Cell(PreviewStrings.MAX, StatFormat.reach(s.reachMax, s.reachCount), maxColor));
		cells.add(new Cell(PreviewStrings.CRITS, Integer.toString(s.crits), value));
		cells.add(new Cell(PreviewStrings.THREE_BLOCK, Integer.toString(s.threeBlockHits),
				s.threeBlockHits > 0 ? cfg.tier(HitQuality.THREE_BLOCK_CRIT).argb : value));
		cells.add(new Cell(PreviewStrings.SPRINT, Integer.toString(s.sprintHits), value));
		cells.add(new Cell(PreviewStrings.COMBO, Integer.toString(s.bestCombo), value));
		cells.add(new Cell(PreviewStrings.RESETS, StatFormat.ratio(s.jumpSuccess, s.jumpAttempts), value));
		cells.add(new Cell(PreviewStrings.TAKEN, Integer.toString(s.hitsTaken), value));
		c.cells = cells;

		int widest = 0;
		for (Cell cell : cells) {
			widest = Math.max(widest, font.width(cell.label) + PreviewStrings.GAP + font.width(cell.value));
		}
		c.colW = HudLayoutMath.cardColWidth(widest);
		c.width = HudLayoutMath.cardWidth(c.colW);

		int budget = HudLayoutMath.cardNameBudget(c.width, font.width(c.resultWord), font.width(PreviewStrings.VS), font.width(c.duration));
		c.name = StatFormat.truncate(r.opponentName == null ? "" : r.opponentName, budget, font::width);
		return c;
	}

	private record Cell(String label, String value, int color) {
	}

	/** One shown card: its formatted content and timing. */
	private static final class Card {
		final FightRecord record;
		Component resultWord = Component.empty();
		int resultColor = Draw.WARN;
		String name = "";
		String duration = "";
		List<Cell> cells = List.of();
		int colW = HudLayoutMath.CARD_COL_MIN;
		int width = HudLayoutMath.cardWidth(HudLayoutMath.CARD_COL_MIN);
		long shownAt = Clock.nanos();
		long dismissAt = Long.MIN_VALUE;
		boolean linkAvailable;
		boolean saved;
		long savedCheckedAt;

		Card(FightRecord record) {
			this.record = record;
		}
	}
}
