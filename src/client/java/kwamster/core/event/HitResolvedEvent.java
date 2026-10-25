package kwamster.core.event;

import kwamster.core.model.HitQuality;
import org.jspecify.annotations.Nullable;

/**
 * A swing was graded and added to the fight (CORE event). Always dispatched after the
 * {@link SwingEvent} it resolves.
 *
 * @param swing       the original capture event (raw {@code Clock.nanos()} timestamps)
 * @param quality     null = miss; non-null for every landed swing, NO-REG ones included ({@code swing.intoImmunity()})
 * @param comboAfter  exactly {@code LiveFightView.currentCombo} right after this swing (§5.5)
 * @param streakAfter exactly {@code LiveFightView.currentStreak} right after this swing
 * @param intervalMs  the S8 interval this hit closes (same opponent, ≤ intervalMaxGapMs, NO-REG excluded), -1 if none
 * @param nanos       {@code Clock.nanos()} at resolution (not the swing time), so HUD and crosshair ages start fresh
 */
public record HitResolvedEvent(SwingEvent swing, @Nullable HitQuality quality,
		int comboAfter, int streakAfter, int intervalMs, long nanos) {
}
