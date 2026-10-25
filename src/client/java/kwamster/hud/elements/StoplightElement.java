package kwamster.hud.elements;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.HudElementId;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.StoplightReading;
import kwamster.core.model.StoplightState;
import kwamster.core.stats.StatFormat;
import kwamster.hud.Anim;
import kwamster.hud.Draw;
import kwamster.hud.HudElement;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudPreviewData;
import kwamster.hud.PreviewStrings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

/**
 * The opponent-movement stoplight (S12, §7.7): red over yellow over green in a dark housing
 * (VERTICAL 10 × 26, HORIZONTAL 26 × 10 left to right, or a SINGLE 10 × 10 light in the active colour).
 *
 * <p>The lit light is drawn in full colour with a 1 px ring at alpha 0x55; unlit lights use
 * {@code offArgb}. State changes cross-fade over 100 ms; during a knockback hold the lit light pulses
 * between 0.6 and 1.0 alpha. The optional label ({@code APPROACH +3.1}) sits beside the housing on the
 * side away from the screen centre, in a fixed reserved width, so nothing moves when the state changes.
 * The layout box is the housing only. With nobody tracked the element hides, or (with
 * {@code hideWhenNoTarget} off) shows unlit at 40 % alpha. Client thread.
 */
public final class StoplightElement implements HudElement {
	private static final int HOUSING_ARGB = 0xB0000000;
	private static final int RING_ALPHA = 0x55;
	private static final long FADE_MS = 100L;
	private static final float IDLE_ALPHA = 0.4f;
	private static final double HOLD_PULSE_HZ = 3.0;

	private StoplightState shown = StoplightState.OFF;
	private StoplightState previous = StoplightState.OFF;
	private long changedAt = Long.MIN_VALUE;

	@Override
	public HudElementId id() {
		return HudElementId.STOPLIGHT;
	}

	@Override
	public boolean enabled(KwamsterConfig c) {
		return c.direction.stoplightEnabled;
	}

	@Override
	public boolean active(long nowNanos) {
		return reading().state() != StoplightState.OFF || !Kwamster.config().direction.hideWhenNoTarget;
	}

	@Override
	public int width(boolean preview) {
		return HudLayoutMath.stoplightWidth(Kwamster.config().direction.style);
	}

	@Override
	public int height(boolean preview) {
		return HudLayoutMath.stoplightHeight(Kwamster.config().direction.style);
	}

	/**
	 * The current reading: the gametest override when set (so screenshots work even without
	 * DIRECTION), else DIRECTION's {@code StoplightSource}; never null.
	 */
	public static StoplightReading reading() {
		StoplightReading r = null;
		try {
			r = Kwamster.testHooks().stoplightOverride();
		} catch (IllegalStateException wrongThread) {
			// only reachable off the client thread; fall through to the service
		}
		if (r == null) {
			try {
				r = Kwamster.services().stoplight().reading();
			} catch (Throwable t) {
				LogLimiter.error("StoplightElement.reading", "StoplightSource.reading failed", t);
			}
		}
		return r == null || r.state() == null ? StoplightReading.NONE : r;
	}

	/**
	 * The screen rect of the label area (reserved width, beside the drawn housing {@code housing}), or
	 * null when the label is off. Used by the editor's outline.
	 */
	public HudLayoutMath.@Nullable Rect labelRect(HudLayoutMath.Rect housing, float scale, int screenW) {
		if (!Kwamster.config().direction.showLabel) {
			return null;
		}
		Font font = Minecraft.getInstance().font;
		int reserve = Math.round(font.width(PreviewStrings.STOPLIGHT_LABEL_RESERVE) * scale);
		int gap = Math.round(HudLayoutMath.STOPLIGHT_LABEL_GAP * scale);
		int labelH = Math.round(font.lineHeight * scale);
		int y = housing.y() + (housing.h() - labelH) / 2;
		boolean left = HudLayoutMath.stoplightLabelLeft(housing.x(), housing.w(), reserve, screenW);
		int x = left ? housing.x() - gap - reserve : housing.right() + gap;
		return new HudLayoutMath.Rect(x, y, reserve, labelH);
	}

	@Override
	public void render(GuiGraphics g, long nowNanos, float alpha, boolean preview) {
		KwamsterConfig cfg = Kwamster.config();
		KwamsterConfig.Direction d = cfg.direction;
		StoplightReading r = preview ? HudPreviewData.stoplight() : reading();
		StoplightState state = r.state();
		float t;
		StoplightState from;
		if (preview) {
			t = 1f;
			from = state;
		} else {
			if (state != this.shown) {
				this.previous = this.shown;
				this.shown = state;
				this.changedAt = nowNanos;
			}
			t = Anim.progress(nowNanos, this.changedAt, FADE_MS);
			from = this.previous;
		}
		float a = alpha * (state == StoplightState.OFF ? IDLE_ALPHA : 1f);
		if (a <= 0f) {
			return;
		}
		float hold = !preview && r.knockbackHold() && state != StoplightState.OFF && !Minecraft.getInstance().isPaused()
				? Anim.pulse(nowNanos, HOLD_PULSE_HZ, 0.6f, 1f) : 1f;

		int w = HudLayoutMath.stoplightWidth(d.style);
		int h = HudLayoutMath.stoplightHeight(d.style);
		Draw.roundRect(g, 0, 0, w, h, Draw.mul(HOUSING_ARGB, a));

		int pad = HudLayoutMath.LIGHT_PAD;
		int step = HudLayoutMath.LIGHT + HudLayoutMath.LIGHT_PAD;
		switch (d.style) {
			case SINGLE -> {
				int lit = lerpState(from, state, t, d);
				float ring = (from != StoplightState.OFF ? 1f - t : 0f) + (state != StoplightState.OFF ? t : 0f);
				drawLight(g, pad, pad, lit, Math.min(1f, ring), state != StoplightState.OFF ? hold : 1f, a);
			}
			case HORIZONTAL -> {
				drawStateLight(g, pad, pad, StoplightState.RED, from, state, t, hold, a, d);
				drawStateLight(g, pad + step, pad, StoplightState.YELLOW, from, state, t, hold, a, d);
				drawStateLight(g, pad + 2 * step, pad, StoplightState.GREEN, from, state, t, hold, a, d);
			}
			default -> {
				drawStateLight(g, pad, pad, StoplightState.RED, from, state, t, hold, a, d);
				drawStateLight(g, pad, pad + step, StoplightState.YELLOW, from, state, t, hold, a, d);
				drawStateLight(g, pad, pad + 2 * step, StoplightState.GREEN, from, state, t, hold, a, d);
			}
		}

		if (d.showLabel) {
			drawLabel(g, r, w, h, a);
		}
	}

	/** One light of the three-light styles, cross-faded between the previous and the current state. */
	private static void drawStateLight(GuiGraphics g, int x, int y, StoplightState light, StoplightState from,
			StoplightState to, float t, float hold, float alpha, KwamsterConfig.Direction d) {
		float lit = (to == light ? t : 0f) + (from == light ? 1f - t : 0f);
		int color = Draw.lerp(d.offArgb, colorOf(light, d), lit);
		drawLight(g, x, y, color, lit, to == light ? hold : 1f, alpha);
	}

	/** A 6 × 6 light with cut corners, plus its 1 px ring scaled by {@code ring} (0 = none). */
	private static void drawLight(GuiGraphics g, int x, int y, int color, float ring, float hold, float alpha) {
		int s = HudLayoutMath.LIGHT;
		if (ring > 0f) {
			int ringArgb = Draw.withAlpha(color, Math.round(RING_ALPHA * Anim.clamp01(ring) * hold));
			Draw.border(g, x - 1, y - 1, x + s + 1, y + s + 1, Draw.mul(ringArgb, alpha));
		}
		Draw.roundRect(g, x, y, x + s, y + s, Draw.mul(Draw.mul(color, hold), alpha));
	}

	private static int lerpState(StoplightState from, StoplightState to, float t, KwamsterConfig.Direction d) {
		return Draw.lerp(colorOf(from, d), colorOf(to, d), t);
	}

	private static int colorOf(StoplightState s, KwamsterConfig.Direction d) {
		return switch (s) {
			case GREEN -> d.greenArgb;
			case YELLOW -> d.yellowArgb;
			case RED -> d.redArgb;
			case OFF -> d.offArgb;
		};
	}

	/** The label beside the housing, on the side away from the screen centre (flipped near an edge). */
	private static void drawLabel(GuiGraphics g, StoplightReading r, int w, int h, float alpha) {
		KwamsterConfig cfg = Kwamster.config();
		Font font = Minecraft.getInstance().font;
		String text = switch (r.state()) {
			case GREEN -> "APPROACH " + StatFormat.signed(r.approachBps(), 1);
			case YELLOW -> "HOLD " + StatFormat.signed(r.approachBps(), 1);
			case RED -> "RETREAT " + StatFormat.signed(r.approachBps(), 1);
			case OFF -> StatFormat.DASH;
		};
		int color = r.state() == StoplightState.OFF ? cfg.hud.labelArgb : colorOf(r.state(), cfg.direction);
		int reserve = font.width(PreviewStrings.STOPLIGHT_LABEL_RESERVE);
		// the pose already holds the element's screen position and scale
		Matrix3x2fStack pose = g.pose();
		float scale = Math.abs(pose.m00()) < 1e-4f ? 1f : pose.m00();
		boolean left = HudLayoutMath.stoplightLabelLeft(pose.m20(), w * scale, reserve * scale, g.guiWidth());
		int y = (h - (font.lineHeight - 1)) / 2;
		int gap = HudLayoutMath.STOPLIGHT_LABEL_GAP;
		if (left) {
			Draw.textRight(g, font, text, -gap, y, Draw.mul(color, alpha), true);
		} else {
			Draw.text(g, font, text, w + gap, y, Draw.mul(color, alpha), true);
		}
	}

	/** Restarts the cross-fade bookkeeping (no animation from a stale state). */
	public void reset() {
		this.shown = reading().state();
		this.previous = this.shown;
		this.changedAt = Clock.nanos() - FADE_MS * Anim.MS;
	}
}
