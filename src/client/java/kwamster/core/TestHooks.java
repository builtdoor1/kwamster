package kwamster.core;

import kwamster.Kwamster;
import kwamster.core.event.DeathEvent;
import kwamster.core.event.EventBus;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.fight.FightManager;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.AimSample;
import kwamster.core.model.CrosshairMode;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import kwamster.core.model.StoplightReading;
import kwamster.core.stats.AimZones;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import org.jspecify.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Synthetic event injection, render overrides and a HUD draw counter for the client gametest
 * (§4.10, §12.3). Gametests cannot spawn remote players, so combat is injected here: the events go
 * through the real {@link EventBus}, so FightManager, FEEDBACK, HUD and CROSSHAIR see exactly what a
 * real fight produces.
 *
 * <p>Every method throws {@link IllegalStateException} unless {@code Minecraft.getInstance().isSameThread()}:
 * the gametest calls it only inside {@code ctx.runOnClient(mc -> ...)} (R2c).
 */
public final class TestHooks {
	/** Game time of one client tick. */
	private static final long TICK_NANOS = 50_000_000L;
	/** The synthetic opponent stands this far ahead. */
	private static final double SYNTHETIC_DISTANCE = 3.0;

	private final FightManager fights;
	private final EventBus bus;
	private @Nullable PlayerRef opponent;
	private @Nullable StoplightReading stoplightOverride;
	private @Nullable CrosshairMode crosshairOverride;
	private int hudDraws;

	public TestHooks(FightManager fights, EventBus bus) {
		this.fights = fights;
		this.bus = bus;
	}

	/**
	 * Starts a fight against a synthetic opponent standing 3 blocks ahead. Presence is always
	 * SYNTHETIC; the opponent counts as engaged. A running fight is ended first. The opponent's UUID is
	 * derived from the name (a version-3 UUID, so it never looks like an NPC).
	 */
	public PlayerRef beginSyntheticFight(String opponentName) {
		assertClientThread();
		String name = opponentName == null || opponentName.isBlank() ? "Opponent" : opponentName.trim();
		PlayerRef ref = new PlayerRef(UUID.nameUUIDFromBytes(("kwamster-synthetic:" + name).getBytes(StandardCharsets.UTF_8)), name);
		this.opponent = ref;
		this.fights.beginSynthetic(ref, selfPos());
		return ref;
	}

	/** {@link #swing(boolean, double, boolean, boolean, boolean, double, double, boolean)} with {@code intoImmunity = false}. */
	public void swing(boolean landed, double reach, boolean charged, boolean crit, boolean sprint, double aimH, double aimV) {
		swing(landed, reach, charged, crit, sprint, aimH, aimV, false);
	}

	/**
	 * Posts a {@link SwingEvent} at the synthetic opponent. As in vanilla, a crit requires a charged
	 * swing and implies not sprinting; {@code intoImmunity} applies to landed swings only. The aim zone
	 * is derived from {@code (aimH, aimV)} for landed swings.
	 */
	public void swing(boolean landed, double reach, boolean charged, boolean crit, boolean sprint, double aimH, double aimV, boolean intoImmunity) {
		assertClientThread();
		PlayerRef target = requireOpponent();
		boolean isCrit = charged && crit;
		boolean isSprint = sprint && !isCrit;
		AimSample aim = new AimSample(aimH, aimV, landed ? AimZones.of(aimH, aimV) : null, false);
		Pos self = selfPos();
		SwingEvent e = new SwingEvent(Clock.nanos(), false, 0, target, landed, landed && intoImmunity,
				reach, landed ? reach : Double.NaN, 3.0, false, aim,
				charged ? 1.0f : 0.5f, charged, isCrit, isSprint, false, !isCrit,
				self, opponentPos(self), selfYaw(), 0);
		this.bus.post(e);
	}

	/** Posts a melee {@link HitTakenEvent} from the synthetic opponent. */
	public void hitTaken(double attackerReach, boolean selfOnGround) {
		assertClientThread();
		PlayerRef attacker = requireOpponent();
		Pos self = selfPos();
		this.bus.post(new HitTakenEvent(Clock.nanos(), Clock.ticks(), attacker, false, attackerReach,
				selfOnGround, false, self, opponentPos(self)));
	}

	/**
	 * Posts a decided {@link JumpResetEvent} with {@code deltaMs = 50·(deltaTicks - 1)}; success per the
	 * running fight's settings snapshot (the live config when no fight runs). The later of jump and hit
	 * is "now".
	 */
	public void jumpReset(int deltaTicks) {
		assertClientThread();
		PlayerRef attacker = requireOpponent();
		int deltaMs = 50 * (deltaTicks - 1);
		FightRecord live = this.fights.liveRecord();
		FightRecord.Settings s = live != null && live.settings != null ? live.settings : FightRecord.Settings.from(Kwamster.config());
		boolean success = deltaTicks >= 1 && deltaMs >= s.jumpMinMs && deltaMs <= s.jumpMaxMs;
		long now = Clock.nanos();
		long hit = deltaTicks >= 1 ? now - deltaTicks * TICK_NANOS : now;
		long jump = hit + deltaTicks * TICK_NANOS;
		this.bus.post(new JumpResetEvent(jump, hit, deltaTicks, deltaMs, deltaTicks * 50, success, attacker));
	}

	/** WIN → DeathEvent(opponent); LOSS → DeathEvent(self); DRAW → forceEnding(IDLE). Finalised on the next tick. */
	public void endSyntheticFight(FightResult result) {
		assertClientThread();
		PlayerRef opp = requireOpponent();
		long now = Clock.nanos();
		switch (result == null ? FightResult.DRAW : result) {
			case WIN -> this.bus.post(new DeathEvent(now, opp, false));
			case LOSS -> this.bus.post(new DeathEvent(now, PlayerRefs.self(Minecraft.getInstance()), true));
			case DRAW -> this.fights.requestEnd(FightEndReason.IDLE);
		}
	}

	public void setStoplightOverride(@Nullable StoplightReading r) {
		assertClientThread();
		this.stoplightOverride = r;
	}

	/** DIRECTION's {@code reading()} returns this when it is non-null. */
	public @Nullable StoplightReading stoplightOverride() {
		assertClientThread();
		return this.stoplightOverride;
	}

	public void setCrosshairOverride(@Nullable CrosshairMode m) {
		assertClientThread();
		this.crosshairOverride = m;
	}

	/** {@code CrosshairStateEval} returns this when it is non-null. */
	public @Nullable CrosshairMode crosshairOverride() {
		assertClientThread();
		return this.crosshairOverride;
	}

	/** HudManager calls this once per element it actually draws (not in previews). */
	public void countHudDraw() {
		assertClientThread();
		this.hudDraws++;
	}

	public int hudDraws() {
		assertClientThread();
		return this.hudDraws;
	}

	// ---- internals ----------------------------------------------------------

	private static void assertClientThread() {
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || !mc.isSameThread()) {
			throw new IllegalStateException("KWAMSTER TestHooks must be called on the client thread (use ctx.runOnClient)");
		}
	}

	private PlayerRef requireOpponent() {
		PlayerRef ref = this.opponent;
		if (ref == null) {
			throw new IllegalStateException("No synthetic fight: call beginSyntheticFight first");
		}
		return ref;
	}

	private static Pos selfPos() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p == null ? Pos.ZERO : PlayerRefs.of(p.position());
	}

	private static float selfYaw() {
		LocalPlayer p = Minecraft.getInstance().player;
		return p == null ? 0f : p.getYRot();
	}

	/** {@link #SYNTHETIC_DISTANCE} blocks ahead along the horizontal look direction (yaw 0 faces +z). */
	private static Pos opponentPos(Pos self) {
		double yaw = Math.toRadians(selfYaw());
		return new Pos(self.x() - Math.sin(yaw) * SYNTHETIC_DISTANCE, self.y(), self.z() + Math.cos(yaw) * SYNTHETIC_DISTANCE);
	}
}
