package kwamster.core.config;

/**
 * The HUD elements that have a layout. {@code name()} is the key in {@code KwamsterConfig.layouts}
 * and the optional argument of {@code ScreenRegistry.ScreenId.HUD_EDITOR}.
 */
public enum HudElementId {
	STATS_PANEL, HIT_INDICATOR, COMBO_COUNTER, STOPLIGHT, SUMMARY_CARD
}
