package kwamster.direction;

import kwamster.Kwamster;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.KwamsterListener;
import kwamster.core.event.WorldLeaveEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import org.jspecify.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * The above-head bars' and the jump cue's per-tick bookkeeping (S12, §5.11a), ported from Direction
 * Indicator: once per client tick it samples every loaded player into a {@link HeadBarMotion} and plays
 * the jump cue for a confirmed jump within {@code direction.radius}. {@link HeadBarRenderer} only reads
 * the result, so drawing stays a pure pass.
 *
 * <p>The cue is {@code assets/kwamster/sounds/jump_cue.ogg}, declared in {@code sounds.json} and not
 * registered: a locally played sound is resolved by the sound manager straight from sounds.json. The
 * file is mono on purpose, because Minecraft attenuates only mono sounds by position, and hearing where
 * someone jumped is the point of the cue.
 *
 * <p>Inert, with no state kept, while the master switch (R4) is off or both the bars and the cue are off.
 * Frozen while a singleplayer game is paused. Client thread.
 */
public final class HeadBarTracker implements KwamsterListener {
	/** The bundled jump cue. */
	public static final SoundEvent JUMP_SOUND = SoundEvent.createVariableRangeEvent(
			Identifier.fromNamespaceAndPath("kwamster", "jump_cue"));

	private static @Nullable HeadBarTracker instance;

	/** One player's motion plus the tick it was last seen on. */
	private static final class Entry {
		final HeadBarMotion motion;
		int seenTick;

		Entry(HeadBarMotion motion, int seenTick) {
			this.motion = motion;
			this.seenTick = seenTick;
		}
	}

	/** Keyed by entity id: a respawned player is a new entity and gets a fresh baseline. */
	private final Map<Integer, Entry> tracked = new HashMap<>();
	private int tickCounter;

	public HeadBarTracker() {
		instance = this;
	}

	/** The registered tracker (for the renderer and the gametest), or null before DIRECTION loads. */
	public static @Nullable HeadBarTracker get() {
		return instance;
	}

	// ---- KwamsterListener --------------------------------------------------------------------------

	@Override
	public void onClientTick(Minecraft mc) {
		try {
			tick(mc);
		} catch (Throwable t) {
			LogLimiter.error("HeadBarTracker.tick", "Head bar update failed", t);
			this.tracked.clear();
		}
	}

	/** Entity ids from the old world mean nothing in the new one. */
	@Override
	public void onWorldLeave(WorldLeaveEvent e) {
		this.tracked.clear();
	}

	// ---- per tick ----------------------------------------------------------------------------------

	private void tick(Minecraft mc) {
		KwamsterConfig cfg = Kwamster.config();
		KwamsterConfig.Direction dir = cfg.direction;
		ClientLevel level = mc.level;
		LocalPlayer self = mc.player;
		if (!cfg.general.enabled || !dir.headBarEnabled && !dir.jumpSoundEnabled || level == null || self == null) {
			this.tracked.clear();
			return;
		}
		if (mc.isPaused()) {
			return;
		}
		this.tickCounter++;
		for (AbstractClientPlayer player : level.players()) {
			track(level, self, player, dir);
		}
		// Anything not seen this tick has left the world; a returning player gets a fresh baseline.
		int now = this.tickCounter;
		this.tracked.values().removeIf(e -> e.seenTick != now);
	}

	private void track(ClientLevel level, LocalPlayer self, AbstractClientPlayer player, KwamsterConfig.Direction dir) {
		double x = player.getX();
		double y = player.getY();
		double z = player.getZ();
		Entry e = this.tracked.get(player.getId());
		if (e == null) {
			this.tracked.put(player.getId(), new Entry(
					HeadBarMotion.seed(x, y, z, player.getBbHeight(), player.onGround(), player.hurtTime), this.tickCounter));
			return;
		}
		e.seenTick = this.tickCounter;
		boolean jumped = e.motion.step(x, y, z, player.getYRot(), player.onGround(), player.hurtTime,
				player.hurtDuration, player.getBbHeight(), dir.jumpKnockbackTicks);
		if (jumped && dir.jumpSoundEnabled && inRange(self, player, dir.radius)
				&& (player != self || dir.jumpSoundForSelf)) {
			level.playLocalSound(x, y, z, JUMP_SOUND, SoundSource.PLAYERS,
					dir.jumpSoundVolume / 100.0F, dir.jumpSoundPitch / 100.0F, false);
		}
	}

	// ---- read side (renderer, gametest) --------------------------------------------------------------

	/** Whether {@code player} gets a bar: bars on, yourself only when enabled, alive and within the radius. */
	public static boolean shouldDraw(LocalPlayer self, AbstractClientPlayer player, KwamsterConfig.Direction dir) {
		if (!dir.headBarEnabled) {
			return false;
		}
		if (player == self && !dir.headBarForSelf) {
			return false;
		}
		return inRange(self, player, dir.radius);
	}

	/** The bar's reading for {@code player}; an untracked player reads as idle. */
	public HeadBarMotion.Heading heading(AbstractClientPlayer player, KwamsterConfig.Direction dir) {
		Entry e = this.tracked.get(player.getId());
		return e == null ? HeadBarMotion.Heading.IDLE : e.motion.heading(dir.moveThreshold / 1000.0);
	}

	/** The player's hitbox height interpolated across the tick (see {@link HeadBarMotion#interpolatedHeight}). */
	public double interpolatedHeight(AbstractClientPlayer player, float partialTick) {
		double now = player.getBbHeight();
		Entry e = this.tracked.get(player.getId());
		return e == null ? now : e.motion.interpolatedHeight(partialTick, now);
	}

	/**
	 * Close enough and alive enough for a cue. Not {@code isAlive()}: its {@code health > 0} test rejects
	 * players on servers that hide health as NaN.
	 */
	private static boolean inRange(LocalPlayer self, AbstractClientPlayer player, int radius) {
		if (player.isSpectator() || player.isRemoved() || player.isDeadOrDying()) {
			return false;
		}
		return self.distanceToSqr(player) <= (double) radius * radius;
	}
}
