package kwamster.core.config;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Anchor, offset and scale of one HUD element (§3.10, §7.2). Offsets are GUI pixels measured from
 * the anchor; the scale is clamped to {@link #MIN_SCALE}..{@link #MAX_SCALE} by
 * {@code KwamsterConfig.clampAndFill()}. Edited only by the HUD editor, never by Cloth.
 */
public final class HudLayout {
	public static final float MIN_SCALE = 0.5f;
	public static final float MAX_SCALE = 3.0f;

	public Anchor anchor;
	public int offsetX;
	public int offsetY;
	/** 0.5..3.0. */
	public float scale;

	public HudLayout(Anchor a, int x, int y, float s) {
		this.anchor = a;
		this.offsetX = x;
		this.offsetY = y;
		this.scale = s;
	}

	/** For Gson: a missing anchor is filled with the element's default by {@code clampAndFill()}. */
	private HudLayout() {
		this.scale = 1f;
	}

	public HudLayout copy() {
		return new HudLayout(this.anchor, this.offsetX, this.offsetY, this.scale);
	}

	/** Fresh default layouts for every element, keyed by {@code HudElementId.name()}. */
	public static Map<String, HudLayout> defaults() {
		Map<String, HudLayout> map = new LinkedHashMap<>();
		for (HudElementId id : HudElementId.values()) {
			map.put(id.name(), defaultFor(id));
		}
		return map;
	}

	/**
	 * A fresh default layout for {@code id} (the §3.10 table). The panel sits 5 px from the left edge, not
	 * 6: at 427×240 its 128 px minimum width would otherwise end at x = 134, inside the vanilla title band
	 * that starts at W/2 − 80 = 133.5 (§7.0 default-layout validation).
	 */
	public static HudLayout defaultFor(HudElementId id) {
		return switch (id) {
			case STATS_PANEL -> new HudLayout(Anchor.TOP_LEFT, 5, 6, 1f);
			case HIT_INDICATOR -> new HudLayout(Anchor.CENTER, 0, 26, 1f);
			case COMBO_COUNTER -> new HudLayout(Anchor.CENTER, 30, 0, 1f);
			case STOPLIGHT -> new HudLayout(Anchor.CENTER, -30, 0, 1f);
			case SUMMARY_CARD -> new HudLayout(Anchor.TOP_CENTER, 0, 20, 1f);
		};
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof HudLayout h && h.anchor == this.anchor && h.offsetX == this.offsetX
				&& h.offsetY == this.offsetY && Float.compare(h.scale, this.scale) == 0;
	}

	@Override
	public int hashCode() {
		int h = this.anchor == null ? 0 : this.anchor.hashCode();
		h = 31 * h + this.offsetX;
		h = 31 * h + this.offsetY;
		return 31 * h + Float.hashCode(this.scale);
	}

	@Override
	public String toString() {
		return "HudLayout[" + this.anchor + " " + this.offsetX + "," + this.offsetY + " x" + this.scale + "]";
	}
}
