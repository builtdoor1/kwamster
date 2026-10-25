package kwamster.core.fight;

import java.util.ArrayList;
import java.util.List;

/**
 * Pause arithmetic for the fight clock (§5.13): the fight clock is wall time minus the time a
 * singleplayer game spent paused. Minecraft-free.
 *
 * <p>FightManager calls {@link #pause} on every paused tick and {@link #resume} on every unpaused
 * tick (both are idempotent), and converts every event time and "now" with {@link #toFight} before it
 * reaches the state machine or the accumulator.
 *
 * <p>{@code toFight(n)} subtracts only the paused time that lies before {@code n}. For any time after
 * the last resume this is exactly {@code n - pausedTotalNanos}; it also places an event stamped before
 * a pause (a trade settled a few ticks later) correctly, and it never reorders events (it is
 * non-decreasing in {@code n}). During a pause the fight clock stands still.
 */
public final class FightClock {
	/** Closed pauses as {start, end} pairs, in order. */
	private final List<long[]> pauses = new ArrayList<>();
	private boolean paused;
	private long pauseStart;
	private long pausedTotal;

	/** Forgets every pause (a new fight starts). */
	public void reset() {
		this.pauses.clear();
		this.paused = false;
		this.pauseStart = 0L;
		this.pausedTotal = 0L;
	}

	/** Notes the pause start on the first paused tick; later calls do nothing until {@link #resume}. */
	public void pause(long now) {
		if (!this.paused) {
			this.paused = true;
			this.pauseStart = now;
		}
	}

	/** Closes the running pause on the first unpaused tick; does nothing when not paused. */
	public void resume(long now) {
		if (this.paused) {
			this.paused = false;
			long end = Math.max(now, this.pauseStart);
			this.pauses.add(new long[] {this.pauseStart, end});
			this.pausedTotal += end - this.pauseStart;
		}
	}

	/** {@code nanos} on the fight clock: minus the paused time before it. */
	public long toFight(long nanos) {
		long excluded = 0L;
		for (long[] p : this.pauses) {
			if (nanos <= p[0]) {
				break;
			}
			excluded += Math.min(nanos, p[1]) - p[0];
		}
		if (this.paused && nanos > this.pauseStart) {
			excluded += nanos - this.pauseStart;
		}
		return nanos - excluded;
	}

	/** True between {@link #pause} and {@link #resume}. */
	public boolean paused() {
		return this.paused;
	}

	/** Total length of the closed pauses. */
	public long pausedTotalNanos() {
		return this.pausedTotal;
	}
}
