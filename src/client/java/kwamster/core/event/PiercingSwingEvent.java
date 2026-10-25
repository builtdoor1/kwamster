package kwamster.core.event;

import kwamster.core.model.PlayerRef;
import org.jspecify.annotations.Nullable;

/**
 * A spear ({@code PIERCING_WEAPON}) swing (§5.15). Counted and used as keep-alive only; never a swing.
 *
 * @param nanos  hook time
 * @param target the cone-resolved player (same rule as a miss, §5.4), or null
 */
public record PiercingSwingEvent(long nanos, @Nullable PlayerRef target) {
}
