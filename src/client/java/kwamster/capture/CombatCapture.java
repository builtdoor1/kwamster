package kwamster.capture;

import com.mojang.blaze3d.platform.InputConstants;
import kwamster.Kwamster;
import kwamster.KwamsterClient;
import kwamster.core.Clock;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.DeathEvent;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.HitConfirmedEvent;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.JumpEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.PiercingSwingEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.event.WorldLeaveEvent;
import kwamster.core.fight.PlayerRefs;
import kwamster.core.model.AimSample;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jspecify.annotations.Nullable;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The static entry points behind CAPTURE's mixins (§6.1–6.4), and owner of the open swing. Ports
 * combat-report {@code FightWatcher.onSwingBegin}, {@code onLandedHit}, {@code onSwingCommit},
 * {@code onDamage} and {@code onJump}, without the recording gate: fights are CORE's business, CAPTURE
 * only turns vanilla hooks into capture events on the {@code EventBus}.
 *
 * <p>Lifecycle of one swing: {@link #onStartAttackHead} claims the click, re-applies vanilla's guards,
 * resolves the target and measures it, and opens the swing; {@link #onGameModeAttack} marks it landed,
 * decides NO-REG ({@link NoRegEstimator}) and reclassifies it against the entity vanilla actually attacks;
 * {@link #onStartAttackReturn} posts the {@link SwingEvent} (or {@link PiercingSwingEvent}) and closes it.
 * RETURN fires at every exit, so a rejected swing never lingers. Clicks on dead players and on a body
 * whose death was just seen ({@link RecentDeaths}) are no swings at all.
 *
 * <p>Rules every entry point follows: client thread only (the {@code LivingEntity} mixin filters out the
 * integrated server before calling in, R2b); nothing throws into vanilla (each body is wrapped and logs
 * through {@link LogLimiter}, R3); everything returns immediately while the master switch is off or
 * before {@link CaptureModule} initialised (R4).
 */
public final class CombatCapture {
	private static final long NANOS_PER_MS = 1_000_000L;
	private static final int MAX_CLICK_LEAD_MS = 200;

	private static final HealthWatch HEALTH = new HealthWatch();
	private static final VelocityWatch VELOCITY = new VelocityWatch();
	private static final TradeWatch TRADES = new TradeWatch(HEALTH, VELOCITY);
	private static final JumpResetWatch JUMPS = new JumpResetWatch();
	private static final NoRegEstimator NO_REG = new NoRegEstimator();
	private static final RecentDeaths DEATHS = new RecentDeaths();
	private static final SourcelessDamage SOURCELESS = new SourcelessDamage();

	private static volatile boolean active;
	private static boolean wasEnabled = true;
	private static boolean landedHookLogged;
	private static @Nullable OpenSwing open;
	/** The jump input was down at the end of the previous unpaused tick (rising-edge detection, §5.1). */
	private static boolean jumpWasDown;
	/**
	 * A damage event on this connection named a source entity, so the server sends real damage sources
	 * (1.19.4+) and sourceless events are never attributed by inference. Cleared on world leave.
	 */
	private static boolean sourcedDamageSeen;
	/** A sourceless hit taken whose attacker was not visible yet; looked at again at the next tick start. */
	private static @Nullable PendingHit pendingSourceless;
	/**
	 * Your death, seen in the same frame as a pending sourceless hit (the killing blow's hurt and death
	 * statuses arrive together). Held until that hit is resolved at the next tick start, so the hit reaches
	 * the fight before the death ends it, exactly as when its attacker is known at once.
	 */
	private static @Nullable DeathEvent pendingSelfDeath;
	/** Your velocity at the end of the last unpaused tick: the baseline for the knockback check (§6.3). */
	private static @Nullable Vec3 motionAtTickEnd;

	private CombatCapture() {
	}

	/**
	 * A sourceless hit taken, stamped at the damage hook, waiting for its attacker's arm swing to arrive (or,
	 * while you burn or are poisoned or withered, for the knockback velocity that tells it from a periodic
	 * tick: {@code needsKnockback}, judged against {@code baseline}, your velocity at the end of the last tick).
	 */
	private record PendingHit(LocalPlayer self, long nanos, long tick, boolean onGround, boolean sprinting, Pos selfPos,
			boolean jumpHeld, boolean needsKnockback, Vec3 baseline) {
	}

	/** The swing between {@code startAttack} HEAD and RETURN. */
	private static final class OpenSwing {
		final long nanos;
		final boolean piercing;
		final @Nullable PlayerRef target;
		final @Nullable Entity targetEntity;
		final boolean clickTimed;
		final int clickLeadMs;
		final SwingCapture.@Nullable Geometry geometry;
		final int pingMs;
		HitClassifier.@Nullable HitFlags flags;
		boolean landed;
		boolean intoImmunity;

		private OpenSwing(long nanos, boolean piercing, @Nullable PlayerRef target, @Nullable Entity targetEntity,
				boolean clickTimed, int clickLeadMs, SwingCapture.@Nullable Geometry geometry,
				HitClassifier.@Nullable HitFlags flags, int pingMs) {
			this.nanos = nanos;
			this.piercing = piercing;
			this.target = target;
			this.targetEntity = targetEntity;
			this.clickTimed = clickTimed;
			this.clickLeadMs = clickLeadMs;
			this.geometry = geometry;
			this.flags = flags;
			this.pingMs = pingMs;
		}

		static OpenSwing piercing(long nanos, @Nullable PlayerRef target) {
			return new OpenSwing(nanos, true, target, null, false, 0, null, null, 0);
		}
	}

	// ---- swing: Minecraft.startAttack and MultiPlayerGameMode.attack ---------------

	/**
	 * {@code Minecraft.startAttack} HEAD (§6.1): claims the click, applies the guards of §5.4 in order,
	 * resolves the target, captures reach, ray reach, aim, flags and positions, and opens the swing.
	 */
	public static void onStartAttackHead(Minecraft mc) {
		try {
			open = null; // a swing left open by an earlier, aborted call is stale
			if (!live()) {
				return;
			}
			long now = Clock.nanos();
			long press = ClickTimestamps.claim(now);
			LocalPlayer self = mc.player;
			ClientLevel level = mc.level;
			HitResult hit = mc.hitResult;
			// 1-4: world present, vanilla's own bail-outs, spectators (vanilla never calls player.attack for them)
			if (self == null || level == null || hit == null) {
				return;
			}
			if (mc.missTime > 0 || self.isHandsBusy() || self.isSpectator()) {
				return;
			}
			// 5-6: the remaining vanilla bail-outs (Minecraft.java:1694–1701)
			ItemStack held = self.getItemInHand(InteractionHand.MAIN_HAND);
			if (!held.isItemEnabled(level.enabledFeatures()) || self.cannotAttackWithItem(held, 0)) {
				return;
			}
			KwamsterConfig.Advanced adv = Kwamster.config().advanced;
			// 7: spears are routed to piercingAttack, which never tells the client whether the stab landed
			if (held.has(DataComponents.PIERCING_WEAPON)) {
				SwingCapture.Target t = SwingCapture.resolve(mc, self, adv, p -> isCorpse(p, now));
				open = OpenSwing.piercing(now, t == null ? null : PlayerRefs.of(t.player()));
				return;
			}
			// 8: target resolution (dead players and just-killed bodies are dropped, not misses)
			SwingCapture.Target target = SwingCapture.resolve(mc, self, adv, p -> isCorpse(p, now));
			if (target == null) {
				return;
			}
			Player victim = target.player();
			Vec3 hitLocation = target.onTarget() ? hit.getLocation() : null;
			SwingCapture.Geometry geometry = SwingCapture.measure(self, victim, hitLocation);
			HitClassifier.HitFlags flags = HitClassifier.classify(self, victim);
			boolean clickTimed = press != Long.MIN_VALUE;
			int clickLeadMs = clickTimed ? clickLeadMs(now, press) : 0;
			open = new OpenSwing(now, false, PlayerRefs.of(victim), victim, clickTimed, clickLeadMs, geometry, flags,
					ownPing(mc, self));
		} catch (Throwable t) {
			open = null;
			LogLimiter.error("CombatCapture.onStartAttackHead", "Swing capture failed", t);
		}
	}

	/**
	 * {@code MultiPlayerGameMode.attack} HEAD for a non-spectator (§6.2): the open swing reached
	 * {@code player.attack}. Marks it landed, decides NO-REG with {@link NoRegEstimator} (from your own
	 * send ticks, not the round-trip-late client copy of the victim's timer), and reclassifies charge, crit,
	 * sprint and sweep against the entity vanilla actually attacks (the charge is still intact here: the
	 * ticker is reset only after {@code player.attack}). A hit on a dead player or a just-killed body is
	 * never marked landed.
	 */
	public static void onGameModeAttack(Player player, Entity target) {
		try {
			if (!live()) {
				return;
			}
			if (!landedHookLogged) {
				landedHookLogged = true;
				KwamsterClient.LOG.info("Landed-hit hook active");
			}
			OpenSwing s = open;
			if (s == null || s.piercing || s.landed || target != s.targetEntity) {
				return;
			}
			if (!(target instanceof Player victim) || victim == player || PlayerRefs.isNpc(victim)) {
				return;
			}
			if (!SwingCapture.isLiving(victim, p -> isCorpse(p, Clock.nanos()))) {
				open = null; // a click on a body is no swing at all (resolve() normally drops it at HEAD already)
				return;
			}
			UUID id = victim.getUUID();
			s.landed = true;
			// The server's hurt-immunity window (§5.4, advanced.noRegImmunityTicks): 10 in vanilla, 0 = off.
			s.intoImmunity = NO_REG.onLandedSwing(id, Clock.ticks(), victim.invulnerableTime, s.pingMs,
					Kwamster.config().advanced.noRegImmunityTicks);
			if (!s.intoImmunity) {
				// A swing into hurt immunity never comes back as a damage event, so it may not confirm one.
				SOURCELESS.onRegisteredSwing(id, s.nanos);
			}
			s.flags = HitClassifier.classify(player, victim);
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onGameModeAttack", "Landed-hit capture failed", t);
		}
	}

	/** {@code Minecraft.startAttack} RETURN (§6.1): posts the open swing's event and closes it. */
	public static void onStartAttackReturn() {
		OpenSwing s = open;
		open = null;
		if (s == null) {
			return;
		}
		try {
			if (!live()) {
				return;
			}
			if (s.piercing) {
				Kwamster.events().post(new PiercingSwingEvent(s.nanos, s.target));
				return;
			}
			SwingCapture.Geometry g = s.geometry;
			HitClassifier.HitFlags f = s.flags;
			PlayerRef target = s.target;
			if (g == null || f == null || target == null) {
				return;
			}
			AimSample aim = AimMath.withOutcome(g.aim(), s.landed);
			SwingEvent event = new SwingEvent(s.nanos, s.clickTimed, s.clickLeadMs, target, s.landed,
					s.landed && s.intoImmunity, g.reach(), g.rayReach(), g.rangeLimit(), g.customRange(), aim,
					f.charge(), f.charged(), f.crit(), f.sprint(), f.sweep(), f.onGround(),
					g.selfPos(), g.targetPos(), g.selfYaw(), s.pingMs);
			if (s.landed && !s.intoImmunity) {
				// A hit into hurt immunity never registers on the server, so it cannot be one half of a trade.
				TRADES.onHitDealt(target, s.nanos);
			}
			Kwamster.events().post(event);
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onStartAttackReturn", "Swing commit failed", t);
		}
	}

	// ---- LivingEntity hooks (the mixin has already filtered out the integrated server) ---

	/**
	 * {@code LivingEntity.handleDamageEvent} HEAD (§6.3). Routing (NPC attackers and victims are ignored,
	 * THORNS is ignored both ways):
	 * <ul>
	 *   <li>victim self, cause a player, direct: melee {@link HitTakenEvent}, then the jump-reset and trade watches;</li>
	 *   <li>victim self, cause a player, indirect: projectile {@link HitTakenEvent};</li>
	 *   <li>victim another player, cause self: {@link HitConfirmedEvent} ({@code direct = source.isDirect()}).</li>
	 * </ul>
	 *
	 * <p><b>Sourceless events</b> (no cause, no direct entity, no position, type {@code generic}): what a
	 * pre-1.19.4 server's "entity hurt" status becomes through ViaVersion, ViaFabricPlus or ViaProxy. Once
	 * any damage event on this connection has named a source, the server is modern and sourceless events
	 * are ignored as before. Otherwise {@link SourcelessDamage} infers the missing attacker (victim self:
	 * a nearby player whose arm swing just started, else nothing; while you burn or are poisoned or withered,
	 * only with a knockback velocity) or confirmation (victim another player: only if a registered swing of
	 * yours on them is about one round trip to ping + 300 ms old; each swing confirms one event).
	 *
	 * <p>Every damage event on another player also feeds the NO-REG estimator, which needs to know whether
	 * the victim's last damage was your own melee hit.
	 */
	public static void onDamageEvent(LivingEntity victim, DamageSource source) {
		try {
			if (!live() || source == null) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			LocalPlayer self = mc.player;
			if (self == null) {
				return;
			}
			Entity cause = source.getEntity();
			if (cause != null || source.getDirectEntity() != null) {
				sourcedDamageSeen = true;
			}
			boolean thorns = source.is(DamageTypes.THORNS);
			boolean inferable = !sourcedDamageSeen && isSourceless(source);
			if (victim == self) {
				if (thorns) {
					return;
				}
				if (inferable) {
					onSourcelessHitTaken(mc, self);
					return;
				}
				if (!(cause instanceof Player attacker) || attacker == self || PlayerRefs.isNpc(attacker)) {
					return;
				}
				long now = Clock.nanos();
				long tick = Clock.ticks();
				boolean onGround = self.onGround();
				boolean sprinting = self.isSprinting();
				Pos selfPos = PlayerRefs.of(self.position());
				if (source.isDirect()) {
					if (!SwingCapture.isLiving(attacker, p -> isCorpse(p, now))) {
						// Its death was already processed, so the fight with it is over: a late melee event from
						// a body must not open a new one (projectiles never open fights, so they pass).
						return;
					}
					postMeleeHitTaken(self, attacker, now, tick, onGround, sprinting, selfPos, jumpWasDown);
				} else {
					Kwamster.events().post(new HitTakenEvent(now, tick, PlayerRefs.of(attacker), true,
							SwingCapture.reachTo(attacker, self), onGround, sprinting, selfPos,
							PlayerRefs.of(attacker.position())));
				}
				return;
			}
			if (!(victim instanceof Player hurt) || PlayerRefs.isNpc(hurt)) {
				return;
			}
			long now = Clock.nanos();
			UUID id = hurt.getUUID();
			int ping = ownPing(mc, self);
			boolean ours;
			boolean direct;
			if (inferable) {
				ours = SOURCELESS.claimOwnHit(id, now, ping);
				direct = true;
			} else {
				ours = cause == self && !thorns;
				direct = source.isDirect();
			}
			NO_REG.onDamage(id, ours && direct, Clock.ticks(), ping);
			if (ours) {
				Kwamster.events().post(new HitConfirmedEvent(now, PlayerRefs.of(hurt), direct));
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onDamageEvent", "Damage event capture failed", t);
		}
	}

	/** {@code LivingEntity.jumpFromGround} HEAD (§6.3): a real jump of the local player (never knockback). */
	public static void onJump(LivingEntity entity) {
		try {
			if (!live()) {
				return;
			}
			LocalPlayer self = Minecraft.getInstance().player;
			if (self == null || entity != self) {
				return;
			}
			long now = Clock.nanos();
			long tick = Clock.ticks();
			Kwamster.events().post(new JumpEvent(now, tick, PlayerRefs.of(self.position())));
			KwamsterConfig.Advanced adv = Kwamster.config().advanced;
			JumpResetEvent attempt = JUMPS.onJump(tick, now, adv.jumpWindowMinMs, adv.jumpWindowMaxMs, adv.jumpAttemptMs);
			if (attempt != null) {
				Kwamster.events().post(attempt);
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onJump", "Jump capture failed", t);
		}
	}

	/** {@code LivingEntity.handleEntityEvent} HEAD (§6.3): entity event 3 on a player is the authoritative death signal. */
	public static void onEntityEvent(LivingEntity entity, byte id) {
		if (id != EntityEvent.DEATH) {
			return;
		}
		try {
			if (!live() || !(entity instanceof Player who)) {
				return;
			}
			boolean self = who == Minecraft.getInstance().player;
			if (!self && PlayerRefs.isNpc(who)) {
				return;
			}
			long now = Clock.nanos();
			if (!self) {
				// Its body stays pickable for about a second: clicks on it must not open a new fight.
				DEATHS.record(who.getUUID(), who, now);
			}
			DeathEvent death = new DeathEvent(now, PlayerRefs.of(who), self);
			if (self && pendingSourceless != null) {
				// The killing blow's hurt status came in this frame and still waits for its attacker: post the
				// death after it (at the next tick start, with this time), or the fight would end first and lose it.
				pendingSelfDeath = death;
				return;
			}
			Kwamster.events().post(death);
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onEntityEvent", "Death capture failed", t);
		}
	}

	// ---- input: click timestamps only (§6.4) ---------------------------------------

	/** {@code MouseHandler.onButton} HEAD: timestamps a press of whatever mouse button is bound to Attack. */
	public static void onMouseButton(MouseHandler handler, MouseButtonInfo info, int action) {
		if (action != GLFW.GLFW_PRESS) {
			return;
		}
		try {
			if (!live()) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.screen != null || !handler.isMouseGrabbed()) {
				return;
			}
			InputConstants.Key key = InputConstants.Type.MOUSE.getOrCreate(info.button());
			if (key.equals(KeyBindingHelper.getBoundKeyOf(mc.options.keyAttack))) {
				ClickTimestamps.record(Clock.nanos());
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onMouseButton", "Click timestamp failed", t);
		}
	}

	/** {@code KeyboardHandler.keyPress} HEAD: timestamps a press of the key bound to Attack, if it is a keyboard key. */
	public static void onKey(KeyEvent event, int action) {
		if (action != GLFW.GLFW_PRESS) {
			return;
		}
		try {
			if (!live() || event == null) {
				return;
			}
			Minecraft mc = Minecraft.getInstance();
			if (mc.screen != null || !mc.mouseHandler.isMouseGrabbed()) {
				return;
			}
			if (InputConstants.getKey(event).equals(KeyBindingHelper.getBoundKeyOf(mc.options.keyAttack))) {
				ClickTimestamps.record(Clock.nanos());
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onKey", "Key timestamp failed", t);
		}
	}

	// ---- module side: ticks, world leave, fight end, CaptureControl ------------------

	/**
	 * START_CLIENT_TICK (registered by {@link CaptureModule}, after CORE's tick counter): a sourceless hit
	 * taken whose attacker was not visible at the damage hook gets a second look. Clients that send the
	 * attack before the arm swing (1.9+) make the swing animation arrive right after the hurt event in the
	 * same frame; by the start of the next tick every packet of that frame has been handled, and the jump
	 * code of this tick has not run yet, so the jump-reset window still opens in time. The hit keeps the
	 * time and tick stamped at the damage hook. A hit taken while burning, poisoned or withered is decided
	 * here too, once its knockback velocity can have arrived. A death of yours held behind the pending hit
	 * is posted right after it, with its own time.
	 */
	public static void onClientTickStart(Minecraft mc) {
		PendingHit p = pendingSourceless;
		DeathEvent death = pendingSelfDeath;
		pendingSourceless = null;
		pendingSelfDeath = null;
		if (p == null && death == null) {
			return;
		}
		try {
			if (p != null && live()) {
				resolvePendingHit(mc, p);
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onClientTickStart", "Sourceless hit inference failed", t);
		}
		try {
			if (death != null && live()) {
				Kwamster.events().post(death);
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onClientTickStart.death", "Held death capture failed", t);
		}
	}

	/** The second look at a sourceless hit taken: its attacker now, and its knockback when that is required. */
	private static void resolvePendingHit(Minecraft mc, PendingHit p) {
		LocalPlayer self = mc.player;
		if (self == null || self != p.self()) {
			return;
		}
		Player attacker = inferAttacker(mc, self, Clock.nanos());
		if (attacker == null) {
			return;
		}
		if (p.needsKnockback()) {
			Vec3 now = self.getDeltaMovement();
			Vec3 from = attacker.position();
			Pos at = p.selfPos();
			if (!SourcelessDamage.showsKnockback(p.baseline().x, p.baseline().y, p.baseline().z, now.x, now.y, now.z,
					at.x() - from.x, at.z() - from.z)) {
				return; // a fire, poison or wither tick while someone near you swung: no melee hit
			}
		}
		postMeleeHitTaken(self, attacker, p.nanos(), p.tick(), p.onGround(), p.sprinting(), p.selfPos(), p.jumpHeld());
	}

	/**
	 * END_CLIENT_TICK (after CORE's FightManager): samples health, velocity, trades and the jump input.
	 * Skipped while the game is paused, so the momentum sample and the tick-based windows count game time
	 * only. When the master switch goes off, every watcher is reset once (CORE has already flushed the
	 * trades of the fight it finalised as DISABLED on this tick).
	 */
	public static void onClientTick(Minecraft mc) {
		try {
			if (!live()) {
				if (wasEnabled) {
					wasEnabled = false;
					resetAll();
				}
				return;
			}
			wasEnabled = true;
			if (mc.isPaused()) {
				return;
			}
			long now = Clock.nanos();
			HEALTH.tick(mc, now);
			LocalPlayer self = mc.player;
			if (self != null) {
				VELOCITY.tick(self);
				sampleJumpInput(self, now);
				JumpResetEvent held = JUMPS.onTickEnd(Clock.ticks());
				if (held != null) {
					Kwamster.events().post(held);
				}
				motionAtTickEnd = self.getDeltaMovement();
			} else {
				VELOCITY.reset();
				jumpWasDown = false;
				motionAtTickEnd = null;
			}
			TRADES.tick(mc);
			DEATHS.expire(now);
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onClientTick", "Capture tick failed", t);
		}
	}

	/** Disconnect or world change: posted by CORE after any fight it ended was finalised (and flushed). */
	public static void onWorldLeave(WorldLeaveEvent e) {
		try {
			resetAll();
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onWorldLeave", "Capture reset failed", t);
		}
	}

	/**
	 * A fight was finalised. Its trades were already flushed through {@link #flushPending()}; anything
	 * still pending or half-paired belongs to the finished fight, so it is dropped rather than leaking into
	 * the next one. Pending jump-reset windows are dropped for the same reason.
	 */
	public static void onFightEnded(FightEndedEvent e) {
		try {
			if (TRADES.hasPending()) {
				LogLimiter.warn("CombatCapture.onFightEnded", "Dropping trades that were not flushed before the fight ended");
			}
			TRADES.reset();
			JUMPS.reset();
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.onFightEnded", "Capture fight-end reset failed", t);
		}
	}

	/**
	 * {@code CaptureControl.flushPending()}: takes one more health sample, then settles every pending trade
	 * now and posts its {@code TradeEvent} synchronously. Tolerates {@code mc.level == null} and
	 * {@code mc.player == null} (CORE calls it after a disconnect).
	 *
	 * <p>The extra sample is for the killing blow: CORE finalises on the tick after the death, before
	 * CAPTURE's own tick samples health, and the final drop arrives with the death. Sampled here, its
	 * {@code DamageObservedEvent} reaches the fight before the record is frozen, and the trade flush can
	 * read it. The trade flush itself is not gated by the master switch (it is part of finalising a fight,
	 * including one ended as DISABLED); the health sample is, because it posts capture events (R4).
	 */
	public static void flushPending() {
		Minecraft mc = Minecraft.getInstance();
		try {
			if (live()) {
				HEALTH.sampleNow(mc, Clock.nanos());
			}
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.flushPending.health", "Final health sample failed", t);
		}
		try {
			TRADES.flush(mc);
		} catch (Throwable t) {
			LogLimiter.error("CombatCapture.flushPending", "Trade flush failed", t);
		}
	}

	/** Called by {@link CaptureModule}: the hooks are inert until the module initialised. */
	static void setActive(boolean value) {
		active = value;
	}

	// ---- helpers ----------------------------------------------------------------------

	/** The module is initialised and the master switch is on (R4). */
	private static boolean live() {
		return active && Kwamster.config().general.enabled;
	}

	private static void resetAll() {
		open = null;
		ClickTimestamps.clear();
		HEALTH.reset();
		VELOCITY.reset();
		TRADES.reset();
		JUMPS.reset();
		NO_REG.reset();
		DEATHS.reset();
		SOURCELESS.reset();
		jumpWasDown = false;
		sourcedDamageSeen = false;
		pendingSourceless = null;
		pendingSelfDeath = null;
		motionAtTickEnd = null;
	}

	/** True if {@code p} is the body of a player whose death (entity event 3) was seen in the last 2 s. */
	private static boolean isCorpse(Player p, long nowNanos) {
		return DEATHS.isCorpse(p.getUUID(), p, nowNanos);
	}

	/**
	 * Rising edge of the jump input, once per unpaused client tick, at its end: {@code input.keyPresses} is
	 * what {@code LocalPlayer.aiStep} read this tick and what decided whether {@code jumpFromGround} ran.
	 * A press that is the same action as this tick's {@code jumpFromGround} is ignored by
	 * {@link JumpResetWatch#onJumpPress}; any other press (made in the air, where no jump happens) can still
	 * be an early or late reset attempt (§5.1).
	 */
	private static void sampleJumpInput(LocalPlayer self, long now) {
		boolean down = self.input != null && self.input.keyPresses.jump();
		boolean rising = down && !jumpWasDown;
		jumpWasDown = down;
		if (!rising) {
			return;
		}
		KwamsterConfig.Advanced adv = Kwamster.config().advanced;
		JumpResetEvent attempt = JUMPS.onJumpPress(Clock.ticks(), now, adv.jumpWindowMinMs, adv.jumpWindowMaxMs,
				adv.jumpAttemptMs);
		if (attempt != null) {
			Kwamster.events().post(attempt);
		}
	}

	/**
	 * A melee hit taken from {@code attacker}: posts the {@link HitTakenEvent}, then feeds the jump-reset and
	 * trade watches. The times and own state are the damage hook's; {@code jumpHeld} is whether the jump input
	 * was down at the end of the last tick (a held key jumps on the next tick, §5.1).
	 */
	private static void postMeleeHitTaken(LocalPlayer self, Player attacker, long now, long tick, boolean onGround,
			boolean sprinting, Pos selfPos, boolean jumpHeld) {
		PlayerRef ref = PlayerRefs.of(attacker);
		Kwamster.events().post(new HitTakenEvent(now, tick, ref, false, SwingCapture.reachTo(attacker, self),
				onGround, sprinting, selfPos, PlayerRefs.of(attacker.position())));
		KwamsterConfig.Advanced adv = Kwamster.config().advanced;
		JumpResetEvent attempt = JUMPS.onHitTaken(tick, now, ref, onGround, jumpHeld,
				adv.jumpWindowMinMs, adv.jumpWindowMaxMs, adv.jumpAttemptMs);
		TRADES.onHitTaken(ref, now);
		if (attempt != null) {
			Kwamster.events().post(attempt);
		}
	}

	/**
	 * A damage event with no cause, no direct entity and no position, of type {@code generic}: how
	 * ViaVersion delivers a pre-1.19.4 server's "entity hurt" status (and the only shape it can take there).
	 */
	private static boolean isSourceless(DamageSource source) {
		return source.getEntity() == null && source.getDirectEntity() == null && source.sourcePositionRaw() == null
				&& source.is(DamageTypes.GENERIC);
	}

	/**
	 * A sourceless damage event on the local player: the attacker is inferred now if their arm swing is
	 * already visible, otherwise at the start of the next tick ({@link #onClientTickStart}); with no
	 * candidate either time the event is dropped (fall, fire and cactus damage arrive the same way).
	 *
	 * <p>While you burn or are poisoned or withered, the periodic damage ticks arrive exactly like melee hits
	 * and the opponent beside you is usually swinging, so the hit is always decided at the next tick start
	 * and needs a melee knockback as well: your velocity must have changed since the end of the last tick
	 * and point away from the attacker ({@link SourcelessDamage#showsKnockback}). A real hit's velocity
	 * packet comes in the same server tick as its hurt status, so it has arrived by then in either order.
	 * Burning under Fire Resistance deals no fire ticks, so it needs no such check.
	 */
	private static void onSourcelessHitTaken(Minecraft mc, LocalPlayer self) {
		long now = Clock.nanos();
		long tick = Clock.ticks();
		boolean onGround = self.onGround();
		boolean sprinting = self.isSprinting();
		Pos selfPos = PlayerRefs.of(self.position());
		boolean needsKnockback = (self.isOnFire() && !self.hasEffect(MobEffects.FIRE_RESISTANCE))
				|| self.hasEffect(MobEffects.POISON) || self.hasEffect(MobEffects.WITHER);
		Player attacker = needsKnockback ? null : inferAttacker(mc, self, now);
		if (attacker != null) {
			postMeleeHitTaken(self, attacker, now, tick, onGround, sprinting, selfPos, jumpWasDown);
			return;
		}
		Vec3 baseline = motionAtTickEnd != null ? motionAtTickEnd : self.getDeltaMovement();
		pendingSourceless = new PendingHit(self, now, tick, onGround, sprinting, selfPos, jumpWasDown, needsKnockback,
				baseline);
	}

	/**
	 * The attacker of a sourceless hit on {@code self} (§6.3 fallback): an alive, non-spectator, non-NPC
	 * player other than you, not a just-killed body, within {@value SourcelessDamage#MAX_ATTACKER_REACH}
	 * blocks (eye to your hitbox) whose arm swing just started; the current fight opponent if they qualify,
	 * else the nearest. Null if nobody qualifies.
	 */
	private static @Nullable Player inferAttacker(Minecraft mc, LocalPlayer self, long now) {
		ClientLevel level = mc.level;
		if (level == null) {
			return null;
		}
		List<Player> players = new ArrayList<>(2);
		List<SourcelessDamage.Candidate> candidates = new ArrayList<>(2);
		for (Player p : level.players()) {
			if (p == self || p.isSpectator() || !SwingCapture.isLiving(p, q -> isCorpse(q, now))) {
				continue;
			}
			double reach = SwingCapture.reachTo(p, self);
			if (!(reach <= SourcelessDamage.MAX_ATTACKER_REACH) || PlayerRefs.isNpc(p)) {
				continue;
			}
			players.add(p);
			candidates.add(new SourcelessDamage.Candidate(p.getUUID(), reach, p.swinging, p.swingTime));
		}
		if (candidates.isEmpty()) {
			return null;
		}
		PlayerRef opponent = Kwamster.fights().currentOpponent();
		int i = SourcelessDamage.pickAttacker(candidates, opponent == null ? null : opponent.uuid());
		return i < 0 ? null : players.get(i);
	}

	/** Hook time minus the claimed press, rounded to ms and clamped to 0..200. */
	private static int clickLeadMs(long hookNanos, long pressNanos) {
		long ms = Math.round((hookNanos - pressNanos) / (double) NANOS_PER_MS);
		return (int) Math.max(0L, Math.min(MAX_CLICK_LEAD_MS, ms));
	}

	/** Own tab-list latency in ms, 0 if unknown. */
	private static int ownPing(Minecraft mc, LocalPlayer self) {
		ClientPacketListener connection = mc.getConnection();
		if (connection == null) {
			return 0;
		}
		PlayerInfo info = connection.getPlayerInfo(self.getUUID());
		return info == null ? 0 : Math.max(0, info.getLatency());
	}
}
