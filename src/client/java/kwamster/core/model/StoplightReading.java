package kwamster.core.model;

import org.jspecify.annotations.Nullable;

/**
 * One reading of the direction stoplight (S12), published by DIRECTION through
 * {@code Services.stoplight()}.
 *
 * @param state         the light to show
 * @param approachBps   smoothed radial speed in blocks per second (+ = toward you)
 * @param knockbackHold the state is frozen because the opponent was just hit
 * @param target        whose movement is shown, or null when nobody is tracked
 * @param sinceNanos    {@code Clock.nanos()} when the current state was entered
 */
public record StoplightReading(StoplightState state, double approachBps, boolean knockbackHold,
		@Nullable PlayerRef target, long sinceNanos) {
	/** Nobody tracked: state OFF. */
	public static final StoplightReading NONE = new StoplightReading(StoplightState.OFF, 0, false, null, 0L);
}
