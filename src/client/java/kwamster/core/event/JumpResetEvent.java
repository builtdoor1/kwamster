package kwamster.core.event;

import kwamster.core.model.PlayerRef;

/**
 * A decided jump-reset attempt (§5.1).
 *
 * @param nanos      jump time
 * @param hitNanos   hit time
 * @param deltaTicks jumpTick - hitTick (≤ 0 = early)
 * @param deltaMs    game time {@code 50·(deltaTicks - 1)} (0 = first tick after the hit)
 * @param wallMs     wall-clock jump - hit, informative only (never used for decisions)
 * @param success    decided with the live config window (drives the live chat line only)
 * @param attacker   who hit you
 */
public record JumpResetEvent(long nanos, long hitNanos, int deltaTicks, int deltaMs, int wallMs,
		boolean success, PlayerRef attacker) {

	/** A copy with different times (CORE uses this to move the event onto the fight clock). */
	public JumpResetEvent withTimes(long newNanos, long newHitNanos) {
		return new JumpResetEvent(newNanos, newHitNanos, this.deltaTicks, this.deltaMs, this.wallMs, this.success, this.attacker);
	}
}
