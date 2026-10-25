package kwamster.capture;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

/**
 * The local player's actual velocity, measured as the distance moved each client tick (port of
 * combat-report {@code VelocityWatch}).
 *
 * <p>Position delta rather than {@code getDeltaMovement()} on purpose: delta movement is what the
 * physics intended, and after a knockback impulse into a wall the two disagree. Trade momentum is about
 * where the fight actually went. Client thread; ticked only on unpaused ticks.
 */
public final class VelocityWatch {
	/** Beyond this in one tick it was a teleport or a pearl, not movement. */
	private static final double TELEPORT_BLOCKS_PER_TICK = 4.0;
	private static final double TICKS_PER_SECOND = 20.0;

	private @Nullable Vec3 lastPos;
	private Vec3 perSecond = Vec3.ZERO;

	/** Forgets the last position and velocity. */
	public void reset() {
		this.lastPos = null;
		this.perSecond = Vec3.ZERO;
	}

	/** Samples the player's position for this tick. */
	public void tick(LocalPlayer player) {
		Vec3 pos = player.position();
		Vec3 last = this.lastPos;
		if (last != null) {
			Vec3 delta = pos.subtract(last);
			double length = delta.length();
			if (Double.isFinite(length) && length <= TELEPORT_BLOCKS_PER_TICK) {
				this.perSecond = delta.scale(TICKS_PER_SECOND);
			}
		}
		this.lastPos = pos;
	}

	/** Blocks per second, world axes. */
	public Vec3 perSecond() {
		return this.perSecond;
	}
}
