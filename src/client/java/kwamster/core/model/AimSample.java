package kwamster.core.model;

import org.jspecify.annotations.Nullable;

/**
 * Normalised aim offsets of one swing (S3).
 *
 * <p>{@code h} is the across-view offset divided by the projected half-width of the target box
 * (+ = right, as seen by the attacker). {@code v} is {@code (y - centreY) / halfHeight} (+1 = top).
 * Landed hits are within [-1, 1]; misses may exceed it.
 *
 * @param h         horizontal offset, normalised
 * @param v         vertical offset, normalised
 * @param zone      the aim zone; non-null only for landed hits
 * @param eyeInside the attacker's eye was inside the target box (offsets are then 0 and the zone CENTER)
 */
public record AimSample(double h, double v, @Nullable AimZone zone, boolean eyeInside) {
	/** A centred sample with no zone, for swings where no aim could be measured. */
	public static final AimSample NONE = new AimSample(0.0, 0.0, null, false);
}
