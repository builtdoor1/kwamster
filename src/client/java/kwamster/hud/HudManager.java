package kwamster.hud;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.FightStartedEvent;
import kwamster.core.event.KwamsterListener;
import kwamster.hud.editor.HudEditorScreen;
import kwamster.hud.elements.ComboCounterElement;
import kwamster.hud.elements.HitIndicatorElement;
import kwamster.hud.elements.StatsPanelElement;
import kwamster.hud.elements.StoplightElement;
import kwamster.hud.elements.SummaryCardElement;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import org.joml.Matrix3x2fStack;
import org.jspecify.annotations.Nullable;

import java.util.List;

/**
 * Holds the five HUD elements and draws them as KWAMSTER's single Fabric HUD layer (§6.6, §7.1):
 * layout transforms, the card-priority fade of the stats panel, F1/F3/editor suppression and the
 * master switch (R4). Also the HUD's bus listener (card and panel triggers).
 *
 * <p>Draw order: STATS_PANEL, STOPLIGHT, COMBO_COUNTER, HIT_INDICATOR, SUMMARY_CARD. Every element is
 * drawn inside its own try/catch, so one failing element never takes the others (or vanilla) down
 * (R3). Client thread.
 */
public final class HudManager {
	/** The panel fades this fast when the card covers it, and back in after the card has gone. */
	private static final long PANEL_PRIORITY_MS = 150L;

	private static final HudManager INSTANCE = new HudManager();

	private final StatsPanelElement panel = new StatsPanelElement();
	private final StoplightElement stoplight = new StoplightElement();
	private final ComboCounterElement combo = new ComboCounterElement();
	private final HitIndicatorElement indicator = new HitIndicatorElement();
	private final SummaryCardElement card = new SummaryCardElement();
	private final List<HudElement> drawOrder = List.of(this.panel, this.stoplight, this.combo, this.indicator, this.card);
	private final KwamsterListener listener = new Listener();

	private float panelVisibility = 1f;
	private long lastFrameNanos = Long.MIN_VALUE;

	private HudManager() {
	}

	/** The single instance. */
	public static HudManager get() {
		return INSTANCE;
	}

	/** The elements in draw order. */
	public List<HudElement> elements() {
		return this.drawOrder;
	}

	/** The element with layout key {@code id}. */
	public HudElement element(HudElementId id) {
		return switch (id) {
			case STATS_PANEL -> this.panel;
			case HIT_INDICATOR -> this.indicator;
			case COMBO_COUNTER -> this.combo;
			case STOPLIGHT -> this.stoplight;
			case SUMMARY_CARD -> this.card;
		};
	}

	/** The stoplight element (the editor outlines its label area too). */
	public StoplightElement stoplight() {
		return this.stoplight;
	}

	/** The HUD's bus listener (registered by {@link HudModule}). */
	public KwamsterListener listener() {
		return this.listener;
	}

	/** The registered Fabric HUD layer: {@code HudElementRegistry.attachElementBefore(BOSS_BAR, kwamster:hud, HudManager::render)}. */
	public static void render(GuiGraphics g, DeltaTracker deltaTracker) {
		try {
			INSTANCE.renderLayer(g);
		} catch (Throwable t) {
			LogLimiter.error("HudManager.render", "HUD rendering failed", t);
		}
	}

	/** The drawn rect of {@code e} from its layout, clamped on a {@code screenW × screenH} GUI-scaled screen. */
	public static HudLayoutMath.Rect rectOf(HudElement e, KwamsterConfig cfg, boolean preview, int screenW, int screenH) {
		HudLayout l = cfg.layout(e.id());
		return HudLayoutMath.rect(l, e.width(preview), e.height(preview), screenW, screenH);
	}

	/**
	 * Draws {@code e} with its top-left at {@code r}'s corner and the layout {@code scale}
	 * ({@code pose.translate(x, y); pose.scale(s, s)}, §7.2).
	 */
	public static void drawElement(GuiGraphics g, HudElement e, HudLayoutMath.Rect r, float scale, long nowNanos, float alpha, boolean preview) {
		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.translate(r.x(), r.y());
			float s = HudLayoutMath.clampScale(scale);
			if (s != 1f) {
				pose.scale(s, s);
			}
			e.render(g, nowNanos, alpha, preview);
		} finally {
			pose.popMatrix();
		}
	}

	private void renderLayer(GuiGraphics g) {
		Minecraft mc = Minecraft.getInstance();
		KwamsterConfig cfg = Kwamster.config();
		if (!cfg.general.enabled || cfg.general.hudHidden || mc.options.hideGui) {
			return;
		}
		if (mc.screen instanceof HudEditorScreen) {
			return;
		}
		if (cfg.general.hideInDebug && mc.debugEntries.isOverlayVisible()) {
			return;
		}
		long now = Clock.nanos();
		long dt = this.lastFrameNanos == Long.MIN_VALUE ? Long.MAX_VALUE : now - this.lastFrameNanos;
		this.lastFrameNanos = now;
		int screenW = g.guiWidth();
		int screenH = g.guiHeight();

		HudLayoutMath.Rect cardRect = null;
		try {
			if (this.card.enabled(cfg) && this.card.active(now)) {
				cardRect = rectOf(this.card, cfg, false, screenW, screenH);
			}
		} catch (Throwable t) {
			LogLimiter.error("HudManager/card-priority", "Summary card state failed", t);
		}

		for (HudElement e : this.drawOrder) {
			try {
				drawInGame(g, e, cfg, now, dt, screenW, screenH, cardRect);
			} catch (Throwable t) {
				LogLimiter.error("HudManager/" + e.id(), "HUD element " + e.id() + " failed", t);
			}
		}
	}

	private void drawInGame(GuiGraphics g, HudElement e, KwamsterConfig cfg, long now, long dt, int screenW, int screenH,
			HudLayoutMath.@Nullable Rect cardRect) {
		boolean isPanel = e == this.panel;
		if (!e.enabled(cfg) || !e.active(now)) {
			if (isPanel) {
				// not drawn: no fade-out to play, so take the target state right away
				this.panelVisibility = cardRect != null ? 0f : 1f;
			}
			return;
		}
		HudLayoutMath.Rect r = rectOf(e, cfg, false, screenW, screenH);
		float alpha = 1f;
		if (isPanel) {
			boolean covered = cardRect != null && r.intersects(cardRect);
			this.panelVisibility = Anim.stepToward(this.panelVisibility, covered ? 0f : 1f, dt, PANEL_PRIORITY_MS);
			alpha = this.panelVisibility;
			if (alpha <= 0f) {
				return;
			}
		}
		drawElement(g, e, r, cfg.layout(e.id()).scale, now, alpha, false);
		Kwamster.testHooks().countHudDraw();
	}

	/** Card and panel triggers. Every hook is isolated by the bus. */
	private final class Listener implements KwamsterListener {
		@Override
		public void onFightStarted(FightStartedEvent e) {
			long now = Clock.nanos();
			HudManager.this.card.onFightStarted(now);
			HudManager.this.panel.invalidate();
		}

		@Override
		public void onFightEnded(FightEndedEvent e) {
			if (!Kwamster.config().general.enabled) {
				return;
			}
			long now = Clock.nanos();
			HudManager.this.panel.onFightEnded(e, now);
			HudManager.this.card.onFightEnded(e, now);
		}
	}
}
