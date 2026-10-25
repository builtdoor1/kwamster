package kwamster.hud.elements;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.TierStyle;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.stats.StatFormat;
import kwamster.hud.Anim;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudPreviewData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * The colour-coded hit indicator under the crosshair (S9, §7.5): an 11 px pill with a 2 px bar and
 * text in the tier colour ({@code 2.97  3-BLOCK CRIT}), a grey {@code MISS 3.42 ↑} for a miss (the
 * arrow when over reach), or a grey {@code 2.97  NO-REG} for a hit into hurt immunity.
 *
 * <p>Animation: pop-in from 1.15× to 1× over 120 ms about the centre, hold, then fade over the last
 * 250 ms of {@code indicatorDurationMs}. Ages are measured from {@code HitResolvedEvent.nanos}, so a
 * new hit restarts it. Client thread.
 */
public final class HitIndicatorElement implements HudElement {
	/** Misses and NO-REG hits. */
	public static final int GREY = 0xFF8A919E;
	private static final int PILL_ARGB = 0x99000000;
	private static final long POP_MS = 120L;
	private static final double FADE_MS = 250.0;
	private static final float POP_FROM = 1.15f;
	/** Text baseline offset inside the 11 px pill: 2 px above and below the caps. */
	private static final int TEXT_Y = 2;

	@Override
	public HudElementId id() {
		return HudElementId.HIT_INDICATOR;
	}

	@Override
	public boolean enabled(KwamsterConfig c) {
		return c.feedback.indicatorEnabled;
	}

	@Override
	public boolean active(long nowNanos) {
		HitResolvedEvent e = Kwamster.fights().lastResolved();
		KwamsterConfig cfg = Kwamster.config();
		return e != null && Anim.ageMs(nowNanos, e.nanos()) < cfg.feedback.indicatorDurationMs && look(e, cfg) != null;
	}

	@Override
	public int width(boolean preview) {
		Look l = current(preview, Clock.nanos());
		Font font = Minecraft.getInstance().font;
		return HudLayoutMath.indicatorWidth(l == null ? font.width("MISS") : font.width(l.text));
	}

	@Override
	public int height(boolean preview) {
		return HudLayoutMath.INDICATOR_H;
	}

	@Override
	public void render(GuiGraphics g, long nowNanos, float alpha, boolean preview) {
		Look l = current(preview, nowNanos);
		if (l == null) {
			return;
		}
		KwamsterConfig cfg = Kwamster.config();
		Font font = Minecraft.getInstance().font;
		int w = HudLayoutMath.indicatorWidth(font.width(l.text));
		int h = HudLayoutMath.INDICATOR_H;

		float a = alpha;
		float scale = 1f;
		if (!preview && l.nanos != Long.MIN_VALUE) {
			double age = Anim.ageMs(nowNanos, l.nanos);
			a *= Anim.envelope(age, 0, cfg.feedback.indicatorDurationMs, FADE_MS);
			scale = Anim.lerp(POP_FROM, 1f, Anim.easeOutCubic(Anim.progress(nowNanos, l.nanos, POP_MS)));
		}
		if (a <= 0f) {
			return;
		}

		Matrix3x2fStack pose = g.pose();
		boolean popping = scale != 1f;
		if (popping) {
			pose.pushMatrix();
			pose.translate(w / 2f, h / 2f);
			pose.scale(scale, scale);
			pose.translate(-w / 2f, -h / 2f);
		}
		Draw.roundRect(g, 0, 0, w, h, Draw.mul(PILL_ARGB, a));
		int bar = Draw.mul(l.color, a);
		// the 2 px bar follows the pill's cut corners
		Draw.fill(g, 0, 1, 1, h - 1, bar);
		Draw.fill(g, 1, 0, HudLayoutMath.INDICATOR_BAR_W, h, bar);
		Draw.text(g, font, l.text, HudLayoutMath.INDICATOR_BAR_W + HudLayoutMath.INDICATOR_PAD, TEXT_Y, Draw.mul(l.color, a), false);
		if (popping) {
			pose.popMatrix();
		}
	}

	private @Nullable Look current(boolean preview, long now) {
		KwamsterConfig cfg = Kwamster.config();
		if (preview) {
			// always the sample tier, even when it (or the indicator) is switched off: the editor shows ghosts
			HitResolvedEvent sample = HudPreviewData.hit(now);
			TierStyle tier = cfg.tier(sample.quality());
			String reach = StatFormat.reach(sample.swing().reach());
			String label = tier.label == null ? sample.quality().name() : tier.label;
			return new Look(cfg.feedback.indicatorShowReach ? reach + "  " + label : label, tier.argb, Long.MIN_VALUE);
		}
		HitResolvedEvent e = Kwamster.fights().lastResolved();
		return e == null ? null : look(e, cfg);
	}

	/**
	 * Text and colour for {@code e}, or null when it shows nothing: a landed hit whose tier is
	 * disabled, or a miss while {@code indicatorShowMisses} is off.
	 */
	static @Nullable Look look(HitResolvedEvent e, KwamsterConfig cfg) {
		SwingEvent s = e.swing();
		if (s == null) {
			return null;
		}
		boolean showReach = cfg.feedback.indicatorShowReach;
		String reach = StatFormat.reach(s.reach());
		if (e.quality() == null) {
			if (!cfg.feedback.indicatorShowMisses) {
				return null;
			}
			boolean over = Double.isFinite(s.reach()) && Double.isFinite(s.rangeLimit()) && s.reach() > s.rangeLimit();
			String missReach = StatFormat.reachVsRange(s.reach(), s.rangeLimit()); // over reach never reads as in range
			String text = (showReach ? "MISS " + missReach : "MISS") + (over ? " ↑" : "");
			return new Look(text, GREY, e.nanos());
		}
		if (s.intoImmunity()) {
			return new Look(showReach ? reach + "  NO-REG" : "NO-REG", GREY, e.nanos());
		}
		TierStyle tier = cfg.tier(e.quality());
		if (!tier.enabled) {
			return null;
		}
		String label = tier.label == null ? e.quality().name() : tier.label;
		return new Look(showReach ? reach + "  " + label : label, tier.argb, e.nanos());
	}

	/** What the pill shows; {@code nanos} is the resolution time ({@code Long.MIN_VALUE} for a static preview). */
	record Look(String text, int color, long nanos) {
	}
}
