package kwamster.core.event;

import kwamster.core.model.PlayerRef;

/**
 * A settled trade (§5.10), emitted by CAPTURE's TradeWatch.
 *
 * @param nanos         the time of the LATER of the two paired hits (not the emission time), so it is
 *                      at most the fight's lastCombat
 * @param opponent      the other player
 * @param dealt         health the opponent lost
 * @param taken         health you lost
 * @param damageKnown   {@code dealt > 0} was observed
 * @param momentumPct   own velocity along the you-to-opponent axis, as % of sprint speed
 * @param momentumKnown the momentum sample is valid
 */
public record TradeEvent(long nanos, PlayerRef opponent, double dealt, double taken,
		boolean damageKnown, double momentumPct, boolean momentumKnown) {

	/** A copy with a different {@link #nanos} (CORE uses this to move the event onto the fight clock). */
	public TradeEvent withNanos(long newNanos) {
		return new TradeEvent(newNanos, this.opponent, this.dealt, this.taken, this.damageKnown, this.momentumPct, this.momentumKnown);
	}
}
