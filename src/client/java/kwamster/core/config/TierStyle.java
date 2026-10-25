package kwamster.core.config;

import kwamster.core.model.HitQuality;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Colour, label, sound and toggles of one hit-quality tier (§3.4). A disabled tier suppresses its
 * indicator, crosshair flash, sound and per-hit chat line; the hit is always recorded.
 */
public final class TierStyle {
	/** Longest label the settings screen accepts. */
	public static final int MAX_LABEL_LENGTH = 16;

	/** Master for this tier: indicator, crosshair flash, sound and per-hit chat line. */
	public boolean enabled;
	/** Indicator, crosshair flash and chat colour. */
	public int argb;
	/** For example "3-BLOCK CRIT". */
	public String label;
	public boolean soundEnabled;
	/** Namespaced sound id, for example {@code minecraft:entity.experience_orb.pickup}. */
	public String soundId;
	/** 0..100. */
	public int volumePct;
	/** 50..200. */
	public int pitchPct;

	public TierStyle(boolean enabled, int argb, String label, boolean soundEnabled, String soundId, int volumePct, int pitchPct) {
		this.enabled = enabled;
		this.argb = argb;
		this.label = label;
		this.soundEnabled = soundEnabled;
		this.soundId = soundId;
		this.volumePct = volumePct;
		this.pitchPct = pitchPct;
	}

	/** For Gson. Missing keys are filled from the tier's defaults when the config is loaded. */
	private TierStyle() {
	}

	public TierStyle copy() {
		return new TierStyle(this.enabled, this.argb, this.label, this.soundEnabled, this.soundId, this.volumePct, this.pitchPct);
	}

	/** Fresh defaults for all seven tiers, keyed by {@code HitQuality.name()} in code order. */
	public static Map<String, TierStyle> defaults() {
		Map<String, TierStyle> map = new LinkedHashMap<>();
		for (HitQuality q : HitQuality.values()) {
			map.put(q.name(), defaultFor(q));
		}
		return map;
	}

	/** A fresh default style for {@code q} (the §3.4 table; every tier has a distinct sound). */
	public static TierStyle defaultFor(HitQuality q) {
		return switch (q) {
			case THREE_BLOCK_CRIT -> new TierStyle(true, 0xFFFFD43B, "3-BLOCK CRIT", true, "minecraft:entity.experience_orb.pickup", 80, 120);
			case THREE_BLOCK_SPRINT -> new TierStyle(true, 0xFF3DDC97, "3-BLOCK SPRINT", true, "minecraft:block.note_block.pling", 70, 150);
			case THREE_BLOCK -> new TierStyle(true, 0xFFB794F6, "3-BLOCK", true, "minecraft:block.note_block.chime", 60, 140);
			case CRIT -> new TierStyle(true, 0xFFFF922B, "CRIT", true, "minecraft:block.note_block.bell", 55, 120);
			case SPRINT -> new TierStyle(true, 0xFF4DABF7, "SPRINT", true, "minecraft:block.note_block.hat", 50, 100);
			case NORMAL -> new TierStyle(true, 0xFFE9ECEF, "HIT", false, "minecraft:block.note_block.snare", 35, 100);
			case WEAK -> new TierStyle(true, 0xFF868E96, "WEAK", false, "minecraft:block.note_block.bass", 40, 60);
		};
	}

	@Override
	public boolean equals(Object o) {
		return o instanceof TierStyle t && t.enabled == this.enabled && t.argb == this.argb
				&& Objects.equals(t.label, this.label) && t.soundEnabled == this.soundEnabled
				&& Objects.equals(t.soundId, this.soundId) && t.volumePct == this.volumePct && t.pitchPct == this.pitchPct;
	}

	@Override
	public int hashCode() {
		return Objects.hash(this.enabled, this.argb, this.label, this.soundEnabled, this.soundId, this.volumePct, this.pitchPct);
	}
}
