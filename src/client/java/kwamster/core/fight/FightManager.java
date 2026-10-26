package kwamster.core.fight;

import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.DamageObservedEvent;
import kwamster.core.event.DeathEvent;
import kwamster.core.event.EventBus;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.FightStartedEvent;
import kwamster.core.event.HitConfirmedEvent;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.KwamsterListener;
import kwamster.core.event.OutcomeHintEvent;
import kwamster.core.event.PiercingSwingEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.event.TradeEvent;
import kwamster.core.event.WorldLeaveEvent;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import kwamster.core.model.StatsTotals;
import kwamster.core.service.FightService;
import kwamster.core.stats.HitGrader;
import kwamster.core.stats.StatsCalculator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.InterpolationHandler;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The fight lifecycle (§5.13, §5.14): CORE's core listener (always dispatched first) and the
 * {@link FightService} behind {@code Kwamster.fights()}.
 *
 * <p>It turns capture events into fights with {@link FightStateMachine} and {@link FightAccumulator},
 * grades every landed swing with {@link HitGrader} against the fight's settings snapshot, and posts
 * {@link FightStartedEvent}, {@link HitResolvedEvent}, {@link FightEndedEvent} and
 * {@link WorldLeaveEvent}. Per client tick it tracks the singleplayer pause (the fight clock), detects
 * disconnects and world changes, evaluates the end rules and samples ping. A fight that ended abruptly
 * right after a hit stays in ENDING for up to {@link FightStateMachine#EVIDENCE_GRACE_MS} while a kill or
 * win message can still decide it (§5.13a).
 *
 * <p>Client thread only, except {@link #markDisconnectPending()} (R2a). A fight is finalised only from
 * the client tick or the {@code CLIENT_STOPPING} handler, never inside a bus dispatch.
 */
public final class FightManager implements FightService, KwamsterListener {
	/** {@link LiveFightView#stats} is recomputed at most this often while the record is unchanged. */
	private static final long STATS_CACHE_NANOS = 50_000_000L;
	/** Ping is sampled once per second. */
	private static final int PING_INTERVAL_TICKS = 20;
	private static final double TELEPORT_SQR = FightStateMachine.TELEPORT_BLOCKS * FightStateMachine.TELEPORT_BLOCKS;

	private final EventBus bus;
	private final FightStateMachine sm = new FightStateMachine(() -> Kwamster.config().advanced);
	private final AtomicBoolean disconnectPending = new AtomicBoolean();

	/** The running fight (ACTIVE or ENDING), or null while IDLE. */
	private @Nullable Fight fight;
	private boolean finalizing;

	private @Nullable ClientLevel lastLevel;
	private @Nullable LocalPlayer lastPlayer;
	private int lastPlayerId = -1;

	private @Nullable FightRecord lastRecord;
	private @Nullable FightStats lastStats;
	private long lastEndedNanos;
	private @Nullable HitResolvedEvent lastResolved;
	private StatsTotals session = newSession();

	public FightManager(EventBus bus) {
		this.bus = bus;
	}

	// ---- control --------------------------------------------------------------

	/** The only method that may be called off the client thread (DISCONNECT on a Netty thread, R2a): sets a flag, nothing else. */
	public void markDisconnectPending() {
		this.disconnectPending.set(true);
	}

	/**
	 * Ends the running fight now, synchronously (the {@code CLIENT_STOPPING} handler uses
	 * {@code GAME_EXIT}). A death already pending keeps its reason. Does nothing while IDLE.
	 */
	public void forceEnd(FightEndReason reason) {
		Fight f = this.fight;
		if (f == null || this.sm.phase() == FightStateMachine.Phase.IDLE) {
			return;
		}
		if (this.sm.phase() == FightStateMachine.Phase.ACTIVE) {
			this.sm.forceEnding(reason, f.clock.toFight(Clock.nanos()));
		}
		FightEndReason pending = this.sm.pendingReason();
		finalizeNow(pending != null ? pending : reason);
	}

	/** Moves the running fight to ENDING with {@code reason}; it is finalised on the next client tick. Does nothing unless ACTIVE. */
	public void requestEnd(FightEndReason reason) {
		Fight f = this.fight;
		if (f != null && this.sm.phase() == FightStateMachine.Phase.ACTIVE) {
			this.sm.forceEnding(reason, f.clock.toFight(Clock.nanos()));
		}
	}

	/**
	 * Opens a fight against a synthetic opponent (gametest, §4.10). A running fight is ended first
	 * (IDLE). The opponent counts as engaged, its presence is always {@link OpponentPresence#SYNTHETIC},
	 * and the fight never idles out: it ends through a death event, {@link #requestEnd} or a forced reason.
	 */
	public void beginSynthetic(PlayerRef opponent, @Nullable Pos selfPos) {
		if (this.fight != null) {
			forceEnd(FightEndReason.IDLE);
		}
		long now = Clock.nanos();
		Fight f = open(opponent, now, selfPos);
		f.synthetic = opponent.uuid();
		this.sm.onCombat(opponent, f.clock.toFight(now), true, true);
		f.acc.markEngaged(f.acc.indexOf(opponent.uuid()));
		f.mods++;
		this.bus.post(new FightStartedEvent(f));
	}

	/** The live record of the running fight (owned by CORE; do not mutate), or null. */
	public @Nullable FightRecord liveRecord() {
		Fight f = this.fight;
		return f == null ? null : f.acc.view();
	}

	/** The state machine phase. */
	public FightStateMachine.Phase phase() {
		return this.sm.phase();
	}

	// ---- FightService ---------------------------------------------------------

	@Override
	public boolean inFight() {
		return this.fight != null;
	}

	@Override
	public @Nullable LiveFightView current() {
		return this.fight;
	}

	@Override
	public @Nullable PlayerRef currentOpponent() {
		return this.fight == null ? null : this.sm.opponent();
	}

	@Override
	public @Nullable FightRecord lastRecord() {
		return this.lastRecord;
	}

	@Override
	public @Nullable FightStats lastStats() {
		return this.lastStats;
	}

	@Override
	public long lastEndedNanos() {
		return this.lastEndedNanos;
	}

	@Override
	public @Nullable HitResolvedEvent lastResolved() {
		return this.lastResolved;
	}

	@Override
	public StatsTotals session() {
		return this.session;
	}

	@Override
	public void resetSession() {
		this.session = newSession();
	}

	// ---- capture events -------------------------------------------------------

	@Override
	public void onSwing(SwingEvent e) {
		if (e == null || e.target() == null) {
			return;
		}
		FightStateMachine.Phase phase = this.sm.phase();
		if (phase == FightStateMachine.Phase.ENDING) {
			return;
		}
		boolean opened = false;
		if (phase == FightStateMachine.Phase.IDLE) {
			if (!Kwamster.config().general.enabled) {
				return;
			}
			open(e.target(), e.nanos(), e.selfPos());
			opened = true;
		}
		Fight f = this.fight;
		if (f == null) {
			return;
		}
		long fn = f.clock.toFight(e.nanos());
		this.sm.onCombat(e.target(), fn, e.landed(), true);
		FightRecord rec = f.acc.view();
		HitQuality q = e.landed()
				? HitGrader.grade(e.charged(), e.crit(), e.sprint(), e.reach(), rec.settings.threeBlockReach, e.customRange())
				: null;
		f.acc.addSwing(fn == e.nanos() ? e : e.withNanos(fn), q);
		f.mods++;
		if (opened) {
			this.bus.post(new FightStartedEvent(f));
		}
		long now = Clock.nanos();
		FightStats st = f.compute(now);
		int interval = e.landed() && !e.intoImmunity() ? StatsCalculator.lastIntervalMs(rec) : -1;
		HitResolvedEvent resolved = new HitResolvedEvent(e, q, st.currentCombo, st.currentStreak, interval, now);
		this.lastResolved = resolved;
		this.bus.post(resolved);
	}

	@Override
	public void onPiercingSwing(PiercingSwingEvent e) {
		Fight f = this.fight;
		if (e == null || e.target() == null || f == null || this.sm.phase() != FightStateMachine.Phase.ACTIVE) {
			return;
		}
		if (!this.sm.isEngaged(e.target().uuid())) {
			return;
		}
		f.acc.addPiercingSwing();
		this.sm.onKeepAlive(e.target(), f.clock.toFight(e.nanos()));
		f.mods++;
	}

	@Override
	public void onHitTaken(HitTakenEvent e) {
		if (e == null || e.attacker() == null) {
			return;
		}
		FightStateMachine.Phase phase = this.sm.phase();
		if (phase == FightStateMachine.Phase.ENDING) {
			return;
		}
		if (e.projectile()) {
			// Keep-alive only, and only from an engaged opponent; never opens a fight.
			Fight f = this.fight;
			if (phase == FightStateMachine.Phase.ACTIVE && f != null && this.sm.isEngaged(e.attacker().uuid())) {
				f.acc.addProjectileHitTaken();
				this.sm.onKeepAlive(e.attacker(), f.clock.toFight(e.nanos()));
				f.mods++;
			}
			return;
		}
		boolean opened = false;
		if (phase == FightStateMachine.Phase.IDLE) {
			if (!Kwamster.config().general.enabled) {
				return;
			}
			open(e.attacker(), e.nanos(), e.selfPos());
			opened = true;
		}
		Fight f = this.fight;
		if (f == null) {
			return;
		}
		long fn = f.clock.toFight(e.nanos());
		this.sm.onCombat(e.attacker(), fn, true, false);
		f.acc.addHitTaken(fn == e.nanos() ? e : e.withNanos(fn));
		f.mods++;
		if (opened) {
			this.bus.post(new FightStartedEvent(f));
		}
	}

	@Override
	public void onJumpReset(JumpResetEvent e) {
		Fight f = this.fight;
		if (e == null || f == null) {
			return;
		}
		long jump = f.clock.toFight(e.nanos());
		long hit = f.clock.toFight(e.hitNanos());
		if (this.sm.phase() == FightStateMachine.Phase.ENDING && hit > this.sm.endNanos()) {
			return;
		}
		f.acc.addJumpReset(jump == e.nanos() && hit == e.hitNanos() ? e : e.withTimes(jump, hit));
		f.mods++;
	}

	@Override
	public void onDeath(DeathEvent e) {
		Fight f = this.fight;
		if (e == null || f == null || this.sm.phase() != FightStateMachine.Phase.ACTIVE) {
			return;
		}
		this.sm.onDeath(e.self(), e.who(), f.clock.toFight(e.nanos()));
	}

	@Override
	public void onHitConfirmed(HitConfirmedEvent e) {
		Fight f = this.fight;
		if (e == null || e.victim() == null || f == null) {
			return;
		}
		long fn = f.clock.toFight(e.nanos());
		if (this.sm.phase() == FightStateMachine.Phase.ACTIVE) {
			this.sm.onKeepAlive(e.victim(), fn); // engaged players only
		}
		if (e.direct()) {
			f.acc.addConfirm(fn == e.nanos() ? e : e.withNanos(fn));
			f.mods++;
		}
	}

	@Override
	public void onDamageObserved(DamageObservedEvent e) {
		Fight f = this.fight;
		if (e == null || f == null) {
			return;
		}
		long fn = f.clock.toFight(e.nanos());
		DamageObservedEvent conv = fn == e.nanos() ? e : e.withNanos(fn);
		if (e.self()) {
			f.acc.addDamage(conv, -1);
		} else {
			int idx = f.acc.indexOf(e.who());
			if (idx < 0) {
				return;
			}
			f.acc.addDamage(conv, idx);
			f.acc.markHealthVisible();
		}
		f.mods++;
	}

	@Override
	public void onTrade(TradeEvent e) {
		Fight f = this.fight;
		if (e == null || e.opponent() == null || f == null) {
			return;
		}
		long fn = f.clock.toFight(e.nanos());
		if (this.sm.phase() == FightStateMachine.Phase.ENDING && fn > this.sm.endNanos()) {
			return;
		}
		f.acc.addTrade(fn == e.nanos() ? e : e.withNanos(fn));
		f.mods++;
	}

	/**
	 * A kill or win message (§5.13a). One that names who died or lost must name an engaged opponent of
	 * this fight; anything else (another player's fight) is ignored.
	 */
	@Override
	public void onOutcomeHint(OutcomeHintEvent e) {
		Fight f = this.fight;
		if (e == null || f == null || this.sm.phase() == FightStateMachine.Phase.IDLE) {
			return;
		}
		if (e.opponent() != null) {
			PlayerRef named = null;
			for (PlayerRef ref : f.opponents()) {
				if (ref.name().equalsIgnoreCase(e.opponent())) {
					named = ref;
					break;
				}
			}
			if (named == null || !this.sm.isEngaged(named.uuid())) {
				return;
			}
		}
		this.sm.onOutcomeHint(e.win(), f.clock.toFight(e.nanos()), e.note());
	}

	// ---- per tick (§5.13) -----------------------------------------------------

	@Override
	public void onClientTick(Minecraft mc) {
		long now = Clock.nanos();
		boolean paused = mc.isPaused();
		Fight running = this.fight;
		if (running != null) {
			if (paused) {
				running.clock.pause(now);
			} else {
				running.clock.resume(now);
			}
		}

		// 1. identity tracking
		ClientLevel level = mc.level;
		LocalPlayer player = mc.player;
		ClientPacketListener connection = mc.getConnection();
		boolean disconnected = this.disconnectPending.getAndSet(false)
				|| (this.lastLevel != null && (level == null || connection == null));
		boolean worldChanged = !disconnected && this.lastLevel != null && level != null
				&& (level != this.lastLevel || player != this.lastPlayer
				|| (player != null && player.getId() != this.lastPlayerId));

		try {
			tickFight(mc, now, paused, disconnected, worldChanged);
		} catch (Throwable t) {
			LogLimiter.error("FightManager.tick", "Fight tick failed", t);
		}

		// 6. world-leave notifications, after any finalisation this tick
		if (disconnected) {
			this.bus.post(new WorldLeaveEvent(now, WorldLeaveEvent.Reason.DISCONNECT));
		} else if (worldChanged) {
			this.bus.post(new WorldLeaveEvent(now, WorldLeaveEvent.Reason.WORLD_CHANGE));
		}
		this.lastLevel = level;
		this.lastPlayer = player;
		this.lastPlayerId = player != null ? player.getId() : -1;
	}

	/** Steps 2–5 of the per-tick order. */
	private void tickFight(Minecraft mc, long now, boolean paused, boolean disconnected, boolean worldChanged) {
		Fight f = this.fight;
		if (f == null) {
			return;
		}
		long fightNow = f.clock.toFight(now);
		// 2. a pending end (death, test hook) is finalised now; it wins over a disconnect or world change.
		// An abrupt draw still waiting for a kill or win message (§5.13a) holds until the wait runs out,
		// unless you disconnect or switch KWAMSTER off.
		if (this.sm.phase() == FightStateMachine.Phase.ENDING) {
			boolean hold = this.sm.awaitingEvidence(fightNow) && !disconnected && Kwamster.config().general.enabled;
			if (!hold) {
				FightEndReason pending = this.sm.pendingReason();
				finalizeNow(pending != null ? pending : FightEndReason.IDLE);
			}
			return;
		}
		if (this.sm.phase() != FightStateMachine.Phase.ACTIVE) {
			return;
		}
		// 3. forced reasons
		FightEndReason forced = disconnected ? FightEndReason.DISCONNECT
				: worldChanged ? FightEndReason.WORLD_CHANGE
				: !Kwamster.config().general.enabled ? FightEndReason.DISABLED
				: null;
		if (forced != null) {
			this.sm.forceEnding(forced, fightNow);
			if (!this.sm.awaitingEvidence(fightNow)) {
				FightEndReason pending = this.sm.pendingReason();
				finalizeNow(pending != null ? pending : forced);
			}
			return;
		}
		// 4. end rules (not while paused)
		if (!paused) {
			PlayerRef opponent = this.sm.opponent();
			if (f.synthetic != null && opponent != null && f.synthetic.equals(opponent.uuid())) {
				this.sm.onKeepAlive(opponent, fightNow); // a synthetic fight never idles out
			}
			this.sm.noteOpponentHealthVisible(f.acc.view().opponentHealthVisible);
			FightEndReason reason = this.sm.tick(fightNow, presence(mc, f, opponent), selfState(mc, f));
			if (reason != null) {
				if (!this.sm.awaitingEvidence(fightNow)) {
					finalizeNow(reason);
				}
				return;
			}
		}
		// 5. ping sampling while ACTIVE
		if (++f.pingTicks >= PING_INTERVAL_TICKS) {
			f.pingTicks = 0;
			samplePing(mc, f);
		}
	}

	private OpponentPresence presence(Minecraft mc, Fight f, @Nullable PlayerRef opponent) {
		if (opponent == null) {
			return OpponentPresence.ABSENT;
		}
		if (f.synthetic != null && f.synthetic.equals(opponent.uuid())) {
			return OpponentPresence.SYNTHETIC;
		}
		ClientLevel level = mc.level;
		if (level == null) {
			f.lastOppPos = null;
			return OpponentPresence.ABSENT;
		}
		for (Player p : level.players()) {
			if (opponent.uuid().equals(p.getUUID())) {
				// Same predicate as CAPTURE (SwingCapture.isLiving): isAlive() reads health > 0, which is false
				// for a server that hides health as NaN, and would end every fight as OPPONENT_DIED.
				boolean alive = !p.isRemoved() && !p.isDeadOrDying();
				double distance = mc.player != null ? mc.player.distanceTo(p) : 0.0;
				// The server position, not the interpolated one: a teleport is smoothed over several ticks.
				Vec3 pos = serverPosition(p);
				boolean teleported = f.lastOppPos != null && opponent.uuid().equals(f.lastOppUuid)
						&& f.lastOppPos.distanceToSqr(pos) > TELEPORT_SQR;
				f.lastOppPos = pos;
				f.lastOppUuid = opponent.uuid();
				return new OpponentPresence(true, alive, p.isSpectator(), distance, teleported, healthFrac(p));
			}
		}
		f.lastOppPos = null;
		return OpponentPresence.ABSENT;
	}

	private static SelfState selfState(Minecraft mc, Fight f) {
		LocalPlayer self = mc.player;
		if (self == null) {
			return SelfState.ALIVE;
		}
		boolean dead = self.isDeadOrDying() || mc.screen instanceof DeathScreen;
		Vec3 pos = self.position();
		boolean teleported = f.lastSelfPos != null && f.lastSelfId == self.getId()
				&& f.lastSelfPos.distanceToSqr(pos) > TELEPORT_SQR;
		f.lastSelfPos = pos;
		f.lastSelfId = self.getId();
		ClientLevel level = mc.level;
		boolean inPortal = level != null && (isPortal(level.getBlockState(self.blockPosition()))
				|| isPortal(level.getBlockState(BlockPos.containing(self.getEyePosition()))));
		return new SelfState(dead, self.isSpectator(), teleported, healthFrac(self), inPortal);
	}

	private static boolean isPortal(BlockState state) {
		return state.is(Blocks.NETHER_PORTAL) || state.is(Blocks.END_PORTAL) || state.is(Blocks.END_GATEWAY);
	}

	/** Where the server last put {@code e}: the interpolation target while it is still sliding there. */
	private static Vec3 serverPosition(Entity e) {
		InterpolationHandler h = e.getInterpolation();
		return h != null && h.hasActiveInterpolation() ? h.position() : e.position();
	}

	/** (Health + absorption) / max health, or NaN when the server hides or garbles it. */
	private static float healthFrac(LivingEntity e) {
		float max = e.getMaxHealth();
		float health = e.getHealth() + e.getAbsorptionAmount();
		return Float.isFinite(health) && Float.isFinite(max) && max > 0f ? health / max : Float.NaN;
	}

	// ---- open and finalise ----------------------------------------------------

	/** Creates the accumulator for a new fight. The caller then feeds the opening event to the state machine. */
	private Fight open(PlayerRef opponent, long rawNanos, @Nullable Pos origin) {
		Minecraft mc = Minecraft.getInstance();
		KwamsterConfig cfg = Kwamster.config();
		long startEpochMs = Clock.epochMs() - (Clock.nanos() - rawNanos) / 1_000_000L;
		FightAccumulator acc = new FightAccumulator(rawNanos, startEpochMs, PlayerRefs.self(mc), opponent,
				mc == null ? "" : serverLabel(mc), FightRecord.Settings.from(cfg), origin);
		Fight f = new Fight(acc, startEpochMs);
		this.fight = f;
		samplePing(mc, f);
		return f;
	}

	/**
	 * Finalisation (§5.13): flush CAPTURE's pending trades (they dispatch synchronously into this
	 * listener while it is still ENDING), finish the record, compute the final stats, decide the
	 * alert, add to the session, reset to IDLE and post {@link FightEndedEvent}.
	 *
	 * <p>The state is reset before the event is posted, so during its dispatch {@link #inFight()} is
	 * false and {@link #lastRecord()} already returns the finished record.
	 */
	private void finalizeNow(FightEndReason reason) {
		Fight f = this.fight;
		if (f == null) {
			this.sm.reset();
			return;
		}
		if (this.finalizing) {
			return;
		}
		this.finalizing = true;
		FightEndedEvent ended = null;
		try {
			try {
				Kwamster.services().capture().flushPending();
			} catch (Throwable t) {
				LogLimiter.error("FightManager.flushPending", "CaptureControl.flushPending failed", t);
			}
			FightResult result = FightStateMachine.resultOf(reason);
			FightRecord record = f.acc.finish(this.sm.endNanos(), result, reason, Kwamster.modVersion());
			record.resultNote = result != FightResult.DRAW ? this.sm.resultNote() : null;
			FightStats stats = StatsCalculator.compute(record, StatsCalculator.FINAL);
			record.stats = stats;
			int events = record.swings.size() + record.hitsTaken.size();
			boolean alert = events >= Kwamster.config().advanced.minFightEvents || result != FightResult.DRAW;
			if (alert) {
				this.session.add(record, stats);
			}
			this.lastRecord = record;
			this.lastStats = stats;
			this.lastEndedNanos = Clock.nanos();
			ended = new FightEndedEvent(record, stats, alert);
			KwamsterClient.LOG.debug("Fight vs {} ended: {} ({}, {} ms, {} events, alert {})",
					record.opponentName, result, reason, record.durationMs, events, alert);
		} finally {
			this.fight = null;
			this.sm.reset();
			this.finalizing = false;
		}
		this.bus.post(ended);
	}

	private static void samplePing(@Nullable Minecraft mc, Fight f) {
		if (mc == null) {
			return;
		}
		ClientPacketListener connection = mc.getConnection();
		LocalPlayer self = mc.player;
		if (connection == null || self == null) {
			return;
		}
		PlayerInfo info = connection.getPlayerInfo(self.getUUID());
		if (info != null) {
			f.acc.addPingSample(info.getLatency());
		}
	}

	/** "Singleplayer", the server host lower-cased without its port, or "" when unknown (§5.13). */
	private static String serverLabel(Minecraft mc) {
		if (mc.hasSingleplayerServer()) {
			return "Singleplayer";
		}
		ServerData data = mc.getCurrentServer();
		if (data == null || data.ip == null) {
			return "";
		}
		String host = data.ip.trim().toLowerCase(Locale.ROOT);
		if (host.startsWith("[")) {
			int close = host.indexOf(']');
			return close > 0 ? host.substring(0, close + 1) : host;
		}
		int colon = host.indexOf(':');
		if (colon >= 0 && colon == host.lastIndexOf(':')) {
			host = host.substring(0, colon);
		}
		return host;
	}

	private static StatsTotals newSession() {
		StatsTotals totals = new StatsTotals();
		totals.sinceEpochMs = Clock.epochMs();
		return totals;
	}

	// ---- the running fight -----------------------------------------------------

	/** One fight: its accumulator, its fight clock and the live view over them. */
	private final class Fight implements LiveFightView {
		final FightAccumulator acc;
		final FightClock clock = new FightClock();
		final long startEpochMs;
		/** Bumped on every record change; invalidates the stats cache. */
		int mods;
		int pingTicks;
		@Nullable UUID synthetic;
		/** Last tick's server position of {@link #lastOppUuid} and your own position, for teleports (§5.13a). */
		@Nullable Vec3 lastOppPos;
		@Nullable UUID lastOppUuid;
		@Nullable Vec3 lastSelfPos;
		int lastSelfId = -1;
		private @Nullable FightStats cached;
		private long cachedAt;
		private int cachedMods = -1;
		private List<PlayerRef> opponentsCache = List.of();
		private int opponentsCacheSize = -1;

		Fight(FightAccumulator acc, long startEpochMs) {
			this.acc = acc;
			this.startEpochMs = startEpochMs;
		}

		/** Fresh {@code StatsCalculator.compute} at {@code nowNanos}; refreshes the cache. */
		FightStats compute(long nowNanos) {
			int rel = this.acc.finished() ? StatsCalculator.FINAL : this.acc.relMs(this.clock.toFight(nowNanos));
			FightStats s = StatsCalculator.compute(this.acc.view(), rel);
			this.cached = s;
			this.cachedAt = nowNanos;
			this.cachedMods = this.mods;
			return s;
		}

		@Override
		public long startNanos() {
			return this.acc.startNanos();
		}

		@Override
		public long startEpochMs() {
			return this.startEpochMs;
		}

		@Override
		public PlayerRef opponent() {
			if (FightManager.this.fight == this) {
				PlayerRef current = FightManager.this.sm.opponent();
				if (current != null) {
					return current;
				}
			}
			FightRecord r = this.acc.view();
			return new PlayerRef(parseUuid(r.opponentUuid), r.opponentName == null ? "" : r.opponentName);
		}

		@Override
		public List<PlayerRef> opponents() {
			FightRecord r = this.acc.view();
			int size = r.opponents.size();
			if (size != this.opponentsCacheSize) {
				List<PlayerRef> list = new ArrayList<>(size);
				for (int i = 0; i < size; i++) {
					String uuid = i < r.opponentUuids.size() ? r.opponentUuids.get(i) : null;
					list.add(new PlayerRef(parseUuid(uuid), r.opponents.get(i)));
				}
				this.opponentsCache = Collections.unmodifiableList(list);
				this.opponentsCacheSize = size;
			}
			return this.opponentsCache;
		}

		@Override
		public long elapsedMs(long nowNanos) {
			if (this.acc.finished()) {
				return this.acc.view().durationMs;
			}
			return Math.max(0, this.acc.relMs(this.clock.toFight(nowNanos)));
		}

		@Override
		public FightStats stats(long nowNanos) {
			FightStats s = this.cached;
			if (s != null && this.cachedMods == this.mods
					&& (this.acc.finished() || Math.abs(nowNanos - this.cachedAt) <= STATS_CACHE_NANOS)) {
				return s;
			}
			return compute(nowNanos);
		}

		@Override
		public int currentCombo(long nowNanos) {
			return stats(nowNanos).currentCombo;
		}

		@Override
		public int currentComboTaken(long nowNanos) {
			return stats(nowNanos).currentComboTaken;
		}

		@Override
		public int currentStreak() {
			return stats(Clock.nanos()).currentStreak;
		}

		@Override
		public double advantage(long nowNanos) {
			return stats(nowNanos).advantage;
		}

		private static UUID parseUuid(@Nullable String s) {
			if (s != null && !s.isEmpty()) {
				try {
					return UUID.fromString(s);
				} catch (IllegalArgumentException ignored) {
					// fall through
				}
			}
			return new UUID(0L, 0L);
		}
	}
}
