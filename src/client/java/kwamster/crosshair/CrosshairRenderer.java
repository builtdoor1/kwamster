package kwamster.crosshair;

import com.mojang.blaze3d.platform.Window;
import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.TierStyle;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.model.CrosshairMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.Identifier;
import org.joml.Matrix3x2fStack;

import java.util.Objects;

/**
 * The custom crosshair (S15, §6.5, §7.8). {@code GuiCrosshairMixin} routes every sprite that
 * vanilla's {@code Gui.renderCrosshair} blits through {@link #onVanillaSprite}, so all of vanilla's
 * gates (first person, the spectator rule, the 3D-crosshair debug entry, F1, the loading screen)
 * still decide whether anything is drawn at all.
 *
 * <p>The crosshair is drawn in physical pixels: the pose is scaled by {@code 1 / guiScale}, so every
 * span lands on whole framebuffer pixels and stays crisp at any GUI scale. Spans are cached per
 * parameter set (the hit flash only cycles through three gap values), so a steady crosshair costs a
 * handful of {@code fill} calls per frame. Client (render) thread only.
 */
public final class CrosshairRenderer {
	private static final Identifier CROSSHAIR = Identifier.withDefaultNamespace("hud/crosshair");
	private static final Identifier INDICATOR_FULL = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_full");
	private static final Identifier INDICATOR_BACKGROUND = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_background");
	private static final Identifier INDICATOR_PROGRESS = Identifier.withDefaultNamespace("hud/crosshair_attack_indicator_progress");

	/** Extra gap, in config pixels, at the very start of a hit flash (§5.12). */
	private static final double FLASH_GAP_PX = 2.0;
	private static final int CACHE_SLOTS = 4;

	private static final Key[] CACHE_KEYS = new Key[CACHE_SLOTS];
	private static final CrosshairShapes.Spans[] CACHE_SPANS = new CrosshairShapes.Spans[CACHE_SLOTS];
	private static int cacheNext;

	private CrosshairRenderer() {
	}

	/**
	 * Decides one vanilla crosshair sprite (§6.5) and returns true to let vanilla draw it.
	 * <ul>
	 * <li>KWAMSTER or the custom crosshair is off: vanilla draws everything.</li>
	 * <li>{@code hud/crosshair}: the custom crosshair is drawn now, in its place.</li>
	 * <li>The three attack-indicator sprites: kept or hidden per {@code crosshair.attackIndicator}.</li>
	 * <li>Any other sprite: vanilla draws it.</li>
	 * </ul>
	 * Never throws: on any failure vanilla draws the sprite (R3).
	 */
	public static boolean onVanillaSprite(GuiGraphics g, Identifier sprite) {
		try {
			KwamsterConfig cfg = Kwamster.config();
			KwamsterConfig.Crosshair c = cfg.crosshair;
			if (cfg.general == null || !cfg.general.enabled || c == null || !c.enabled || sprite == null) {
				return true;
			}
			if (CROSSHAIR.equals(sprite)) {
				return !draw(g, cfg, c);
			}
			if (INDICATOR_FULL.equals(sprite) || INDICATOR_BACKGROUND.equals(sprite) || INDICATOR_PROGRESS.equals(sprite)) {
				return c.attackIndicator != KwamsterConfig.AttackIndicatorMode.HIDE;
			}
			return true;
		} catch (Throwable t) {
			LogLimiter.error("CrosshairRenderer.onVanillaSprite", "Custom crosshair failed; drawing the vanilla sprite", t);
			return true;
		}
	}

	/** Draws the custom crosshair; returns false (nothing drawn) when the window is not ready. */
	private static boolean draw(GuiGraphics g, KwamsterConfig cfg, KwamsterConfig.Crosshair c) {
		Minecraft mc = Minecraft.getInstance();
		Window window = mc.getWindow();
		int gs = window.getGuiScale();
		int fbW = window.getWidth();
		int fbH = window.getHeight();
		if (gs < 1 || fbW <= 0 || fbH <= 0) {
			return false;
		}
		int k = c.scaleWithGui ? Math.max(1, Math.round(gs / 2f)) : 1;

		int colour = modeColour(c, CrosshairStateEval.evaluate(mc));
		int gapPx = c.gap;
		HitResolvedEvent hit = Kwamster.fights().lastResolved();
		if (c.hitFlash && c.hitFlashMs > 0 && hit != null && hit.quality() != null && !hit.swing().intoImmunity()) {
			double ageMs = (Clock.nanos() - hit.nanos()) / 1_000_000.0;
			if (ageMs >= 0.0 && ageMs < c.hitFlashMs) {
				TierStyle tier = cfg.tier(hit.quality());
				if (tier.enabled) {
					colour = tier.argb | 0xFF000000;
					gapPx += (int) Math.round(FLASH_GAP_PX * (1.0 - ageMs / c.hitFlashMs));
				}
			}
		}

		CrosshairShapes.Spans spans = spans(new Key(c.shape, c.size * k, gapPx * k, c.thickness * k,
				c.centerDot * k, c.outline * k, fbW / 2, fbH / 2));

		Matrix3x2fStack pose = g.pose();
		pose.pushMatrix();
		try {
			pose.scale(1f / gs, 1f / gs);
			int outlineArgb = c.outlineArgb;
			for (int[] r : spans.outline()) {
				g.fill(r[0], r[1], r[2], r[3], outlineArgb);
			}
			for (int[] r : spans.fill()) {
				g.fill(r[0], r[1], r[2], r[3], colour);
			}
		} finally {
			pose.popMatrix();
		}
		return true;
	}

	/** The configured colour of {@code mode} (§7.8). */
	private static int modeColour(KwamsterConfig.Crosshair c, CrosshairMode mode) {
		return switch (mode == null ? CrosshairMode.DEFAULT : mode) {
			case DEFAULT -> c.defaultArgb;
			case ON_PLAYER -> c.onPlayerArgb;
			case CRIT_READY -> c.critReadyArgb;
			case CRIT_READY_ON_PLAYER -> c.critOnPlayerArgb;
		};
	}

	/** Spans for {@code key}, built on first use and kept in a tiny round-robin cache. */
	private static CrosshairShapes.Spans spans(Key key) {
		for (int i = 0; i < CACHE_SLOTS; i++) {
			if (key.equals(CACHE_KEYS[i])) {
				return CACHE_SPANS[i];
			}
		}
		CrosshairShapes.Spans built = CrosshairShapes.build(key.shape, key.size, key.gap, key.thickness,
				key.centerDot, key.outline, key.cx, key.cy);
		int slot = cacheNext;
		cacheNext = (cacheNext + 1) % CACHE_SLOTS;
		CACHE_KEYS[slot] = key;
		CACHE_SPANS[slot] = built;
		return built;
	}

	/** Everything the spans depend on, already in physical pixels. */
	private record Key(KwamsterConfig.CrosshairShape shape, int size, int gap, int thickness,
			int centerDot, int outline, int cx, int cy) {
		Key {
			shape = Objects.requireNonNullElse(shape, KwamsterConfig.CrosshairShape.CROSS);
		}
	}
}
