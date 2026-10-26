package kwamster.core.fight;

import kwamster.core.config.KwamsterConfig;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightResult;
import kwamster.core.model.PlayerRef;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * When a fight starts and why it ends (§5.13, §5.14). Minecraft-free.
 *
 * <p>Every {@code nanos} argument is on the fight clock (FightManager subtracts paused time first).
 * The detection constants ({@code fightIdleMs}, {@code fightMaxDistance}, {@code deathInferenceMs},
 * {@code outcomeWindowMs}) are read live from the supplier on every {@link #tick}.
 *
 * <p>Phases: IDLE (no fight) → ACTIVE → ENDING (a reason is pending; FightManager finalises on the
 * next client tick, or once the evidence hold of §5.13a runs out) → IDLE via {@link #reset()}.
 *
 * <p><b>Results without a death event (§5.13a).</b> Practice and duel servers usually cancel the killing
 * blow and teleport the loser away, so no death event ever arrives and the fight would end as a draw.
 * With {@code inferOutcomes} on, an abrupt end within {@code outcomeWindowMs} of the last hit decides
 * the result: the opponent vanishing or teleporting far away right after <em>your</em> hit is a WIN (unless
 * their synced health was still high), and you teleporting, respawning or changing world right after
 * <em>their</em> hit with your health low is a LOSS. A kill or win message ({@link #onOutcomeHint}) decides it
 * outright, and an abrupt draw waits {@link #EVIDENCE_GRACE_MS} for such a message before it is final.
 */
public final class FightStateMachine {
	/** The lifecycle phase. */
	public enum Phase { IDLE, ACTIVE, ENDING }

	/** Returned by {@link #lastDealtNanos()} and {@link #lastTakenNanos()} when there was no such hit. */
	public static final long NEVER = Long.MIN_VALUE;
	/** A position jump longer than this in one tick is a teleport, not movement or an ender pearl (§5.13a). */
	public static final double TELEPORT_BLOCKS = 64.0;
	/** Health at or below this share of max health makes a death plausible (§5.13a). */
	public static final float LOW_HEALTH = 0.5f;
	/** How long an abrupt draw waits for a kill or win message before it is final (§5.13a). */
	public static final long EVIDENCE_GRACE_MS = 1000L;
	/** A world change or teleport this soon after you stood in a portal is travel, never an inferred death (§5.13a). */
	public static final long PORTAL_GRACE_MS = 2000L;
	/** Health samples kept for the plausibility checks: 128 ticks covers the 5 s maximum window (100 ticks at 20 TPS). */
	private static final int SAMPLES = 128;

	private final Supplier<KwamsterConfig.Advanced> adv;
	private final Set<UUID> engaged = new HashSet<>();
	/** Non-engaged players whose death event was seen this fight (rule 6). */
	private final Set<UUID> deadSeen = new HashSet<>();
	private final long[] sampleNanos = new long[SAMPLES];
	private final float[] oppHealth = new float[SAMPLES];
	private final float[] selfHealth = new float[SAMPLES];
	private int sampleCount;
	private int sampleNext;

	private Phase phase = Phase.IDLE;
	private PlayerRef opponent;
	private long startNanos;
	private long lastCombat;
	private long lastDealt = NEVER;
	private long lastTaken = NEVER;
	private FightEndReason pendingReason;
	private long endNanos;
	private boolean opponentHealthVisible;
	private @Nullable String resultNote;
	private long evidenceUntil = NEVER;
	private long lastPortal = NEVER;

	public FightStateMachine(Supplier<KwamsterConfig.Advanced> adv) {
		this.adv = Objects.requireNonNull(adv, "adv");
	}

	public Phase phase() {
		return this.phase;
	}

	/**
	 * Melee hit dealt/taken (authoritative=true; marks {@code opponent} engaged and makes it current)
	 * or a miss (false; never retargets, never engages). Ignored while ENDING. Returns true if it opened
	 * a fight.
	 *
	 * @param dealt true for a swing (landed or missed), false for a hit taken
	 */
	public boolean onCombat(PlayerRef opponent, long nanos, boolean authoritative, boolean dealt) {
		Objects.requireNonNull(opponent, "opponent");
		if (this.phase == Phase.ENDING) {
			return false;
		}
		boolean opened = false;
		if (this.phase == Phase.IDLE) {
			reset();
			this.phase = Phase.ACTIVE;
			this.startNanos = nanos;
			this.lastCombat = nanos;
			this.opponent = opponent;
			opened = true;
		} else {
			this.lastCombat = Math.max(this.lastCombat, nanos);
		}
		if (authoritative) {
			this.opponent = opponent;
			this.engaged.add(opponent.uuid());
			if (dealt) {
				this.lastDealt = this.lastDealt == NEVER ? nanos : Math.max(this.lastDealt, nanos);
			} else {
				this.lastTaken = this.lastTaken == NEVER ? nanos : Math.max(this.lastTaken, nanos);
			}
		}
		return opened;
	}

	/**
	 * Non-authoritative keep-alive (projectile hit taken from, direct/indirect confirm on, or spear
	 * swing at an ENGAGED player). Updates lastCombat only. Never opens a fight. Ignored while ENDING
	 * and for players who are not engaged.
	 */
	public void onKeepAlive(PlayerRef who, long nanos) {
		if (this.phase != Phase.ACTIVE || who == null || !this.engaged.contains(who.uuid())) {
			return;
		}
		this.lastCombat = Math.max(this.lastCombat, nanos);
	}

	public boolean isEngaged(UUID who) {
		return who != null && this.engaged.contains(who);
	}

	/**
	 * A death event. Self, or an engaged opponent, moves the fight to ENDING (SELF_DIED or
	 * OPPONENT_DIED) with {@code endNanos} = the death time; the first death wins and later ones are
	 * ignored. The death of a non-engaged player only matters if they are the current opponent: rule 6
	 * then ends the fight as OPPONENT_LEFT on the next tick.
	 */
	public void onDeath(boolean self, PlayerRef who, long nanos) {
		if (this.phase != Phase.ACTIVE) {
			return;
		}
		if (self) {
			enterEnding(FightEndReason.SELF_DIED, nanos);
		} else if (who != null && this.engaged.contains(who.uuid())) {
			enterEnding(FightEndReason.OPPONENT_DIED, nanos);
		} else if (who != null) {
			this.deadSeen.add(who.uuid());
		}
	}

	/**
	 * → ENDING with {@code reason} unless already ENDING (a pending death keeps its reason). Ignored while
	 * IDLE. A WORLD_CHANGE (you respawned, changed dimension or were sent to another server) is first run
	 * through the §5.13a inference: right after their hit with your health low it is a LOSS, right after your
	 * hit it is a WIN; otherwise it stays a draw that waits briefly for a kill or win message.
	 */
	public void forceEnding(FightEndReason reason, long nanos) {
		if (this.phase != Phase.ACTIVE) {
			return;
		}
		if (reason == FightEndReason.WORLD_CHANGE) {
			KwamsterConfig.Advanced a = this.adv.get();
			FightEndReason inferred = a.inferOutcomes ? inferFromSelfMove(nanos, a.outcomeWindowMs * 1_000_000L) : null;
			if (inferred == FightEndReason.SELF_DIED) {
				enterInferred(inferred, "You respawned or changed world right after their hit");
			} else if (inferred != null) {
				enterInferred(inferred, "You changed world right after your hit");
			} else {
				enterAbrupt(reason, nanos);
			}
			return;
		}
		enterEnding(reason, nanos);
	}

	/**
	 * A kill or win message about this fight (§5.13a). While ACTIVE it ends the fight with that result
	 * (a win needs an engaged opponent). While an abrupt draw is waiting for evidence it turns that draw
	 * into the result. Otherwise (a death already decided it, or the wait ran out) it is ignored.
	 */
	public void onOutcomeHint(boolean win, long nanos, String note) {
		FightEndReason result = win ? FightEndReason.OPPONENT_DIED : FightEndReason.SELF_DIED;
		if (this.phase == Phase.ACTIVE) {
			if (win && this.engaged.isEmpty()) {
				return;
			}
			enterInferred(result, note);
		} else if (awaitingEvidence(nanos) && !isDeath(this.pendingReason)) {
			if (win && this.engaged.isEmpty()) {
				return;
			}
			this.pendingReason = result;
			this.resultNote = note;
			this.evidenceUntil = NEVER;
		}
	}

	/** FightManager passes whether the opponent's health has been seen changing this fight (§5.9). */
	public void noteOpponentHealthVisible(boolean visible) {
		this.opponentHealthVisible = visible;
	}

	/** True while an abrupt draw is still waiting for a kill or win message (§5.13a); FightManager does not finalise yet. */
	public boolean awaitingEvidence(long nanos) {
		return this.phase == Phase.ENDING && this.evidenceUntil != NEVER && nanos < this.evidenceUntil;
	}

	/** How a WIN or LOSS without a death event was decided, or null (§5.13a). */
	public @Nullable String resultNote() {
		return this.resultNote;
	}

	/**
	 * Evaluates rules 1–8 of §5.13 with the §5.13a inference before rule 6 (first match wins) and returns
	 * the reason the fight is ending with, or null. While ENDING it returns the pending reason; while IDLE,
	 * null. A non-null result leaves the machine in ENDING until {@link #reset()}; FightManager checks
	 * {@link #awaitingEvidence} before it finalises.
	 */
	public @Nullable FightEndReason tick(long nanos, OpponentPresence p, SelfState self) {
		if (this.phase == Phase.IDLE) {
			return null;
		}
		if (this.phase == Phase.ENDING) {
			return this.pendingReason;
		}
		KwamsterConfig.Advanced a = this.adv.get();
		long inferenceNanos = a.deathInferenceMs * 1_000_000L;
		boolean oppEngaged = opponentEngaged();
		OpponentPresence presence = p == null ? OpponentPresence.ABSENT : p;
		SelfState me = self == null ? SelfState.ALIVE : self;
		sample(nanos, presence, me);
		if (me.inPortal()) {
			this.lastPortal = nanos;
		}

		// Rule 1 (death event of an engaged opponent) already moved the machine to ENDING in onDeath.
		// 2. own death
		if (me.dead()) {
			return enterEnding(FightEndReason.SELF_DIED, nanos);
		}
		// 3. engaged opponent found dead (best-effort health read)
		if (oppEngaged && presence.found() && !presence.alive()) {
			return enterEnding(FightEndReason.OPPONENT_DIED, nanos);
		}
		// 4. engaged opponent switched to spectator shortly after your hit
		boolean spectatorDeath = oppEngaged && presence.found() && presence.spectator()
				&& this.lastDealt != NEVER && nanos - this.lastDealt <= inferenceNanos;
		if (spectatorDeath) {
			return enterEnding(FightEndReason.OPPONENT_DIED, nanos);
		}
		// 5. you switched to spectator shortly after being hit
		if (me.spectator() && this.lastTaken != NEVER && nanos - this.lastTaken <= inferenceNanos) {
			return enterEnding(FightEndReason.SELF_DIED, nanos);
		}
		// 5a. §5.13a: you were teleported away, or the engaged opponent vanished or was teleported, right after a hit
		if (a.inferOutcomes) {
			long window = a.outcomeWindowMs * 1_000_000L;
			if (me.teleported()) {
				FightEndReason inferred = inferFromSelfMove(nanos, window);
				if (inferred == FightEndReason.SELF_DIED) {
					return enterInferred(inferred, "You were teleported away right after their hit");
				}
				if (inferred != null) {
					return enterInferred(inferred, "You were teleported away right after your hit");
				}
			} else if ((!presence.found() || presence.teleported())
					&& yourHitLast(nanos, window) && opponentHealthPlausible(nanos, window)) {
				return enterInferred(FightEndReason.OPPONENT_DIED, presence.found()
						? "Opponent was teleported away right after your hit"
						: "Opponent vanished right after your hit");
			}
		}
		// 6. opponent gone, spectating (outside rule 4), or a non-engaged opponent who died
		boolean deadBystander = !oppEngaged && (!presence.alive()
				|| (this.opponent != null && this.deadSeen.contains(this.opponent.uuid())));
		if (!presence.found() || presence.spectator() || deadBystander) {
			return enterAbrupt(FightEndReason.OPPONENT_LEFT, nanos);
		}
		// 7. too far (3-D feet distance)
		if (presence.distance() > a.fightMaxDistance) {
			return enterAbrupt(FightEndReason.OPPONENT_FAR, nanos);
		}
		// 8. idle (strictly greater)
		if (nanos - this.lastCombat > a.fightIdleMs * 1_000_000L) {
			return enterEnding(FightEndReason.IDLE, nanos);
		}
		return null;
	}

	/** The reason waiting to be finalised, or null when not ENDING. */
	public @Nullable FightEndReason pendingReason() {
		return this.pendingReason;
	}

	/** The death time for the two death reasons, otherwise {@link #lastCombatNanos()} at the moment the fight entered ENDING. */
	public long endNanos() {
		return this.endNanos;
	}

	/** The current opponent; null while IDLE. */
	public @Nullable PlayerRef opponent() {
		return this.opponent;
	}

	public long startNanos() {
		return this.startNanos;
	}

	public long lastCombatNanos() {
		return this.lastCombat;
	}

	/** Last landed hit dealt, or {@link #NEVER}. */
	public long lastDealtNanos() {
		return this.lastDealt;
	}

	/** Last melee hit taken, or {@link #NEVER}. */
	public long lastTakenNanos() {
		return this.lastTaken;
	}

	/** Back to IDLE, forgetting everything about the fight. */
	public void reset() {
		this.phase = Phase.IDLE;
		this.opponent = null;
		this.startNanos = 0L;
		this.lastCombat = 0L;
		this.lastDealt = NEVER;
		this.lastTaken = NEVER;
		this.pendingReason = null;
		this.endNanos = 0L;
		this.engaged.clear();
		this.deadSeen.clear();
		this.sampleCount = 0;
		this.sampleNext = 0;
		this.opponentHealthVisible = false;
		this.resultNote = null;
		this.evidenceUntil = NEVER;
		this.lastPortal = NEVER;
	}

	/** OPPONENT_DIED → WIN, SELF_DIED → LOSS, everything else → DRAW. */
	public static FightResult resultOf(FightEndReason r) {
		if (r == FightEndReason.OPPONENT_DIED) {
			return FightResult.WIN;
		}
		if (r == FightEndReason.SELF_DIED) {
			return FightResult.LOSS;
		}
		return FightResult.DRAW;
	}

	private FightEndReason enterEnding(FightEndReason reason, long nanos) {
		this.phase = Phase.ENDING;
		this.pendingReason = reason;
		this.endNanos = isDeath(reason) ? nanos : this.lastCombat;
		return reason;
	}

	/** → ENDING with a result decided without a death event (§5.13a). The fight ends at its last hit. */
	private FightEndReason enterInferred(FightEndReason reason, String note) {
		this.phase = Phase.ENDING;
		this.pendingReason = reason;
		this.endNanos = this.lastCombat;
		this.resultNote = note;
		this.evidenceUntil = NEVER;
		return reason;
	}

	/**
	 * → ENDING with a draw reason that came abruptly (opponent gone or far, world change). Within
	 * {@code outcomeWindowMs} of the last combat it waits {@link #EVIDENCE_GRACE_MS} for a kill or win
	 * message that arrives a tick or two after the teleport (§5.13a).
	 */
	private FightEndReason enterAbrupt(FightEndReason reason, long nanos) {
		enterEnding(reason, nanos);
		KwamsterConfig.Advanced a = this.adv.get();
		if (a.readOutcomeMessages && nanos - this.lastCombat <= a.outcomeWindowMs * 1_000_000L) {
			this.evidenceUntil = nanos + EVIDENCE_GRACE_MS * 1_000_000L;
		}
		return reason;
	}

	/**
	 * §5.13a: the result when you were moved away (teleport, respawn or world change), or null when it is
	 * unclear. A LOSS needs your health low and their hit within the window, plus either their hit last or
	 * the opponent's synced health still high. A WIN needs your health not low and your hit last. A server
	 * that cancels the killing blow never shows it, which is why the last hit alone is not enough. Travel
	 * through a portal (escaping at low health, say) is never a result.
	 */
	private @Nullable FightEndReason inferFromSelfMove(long nanos, long window) {
		if (this.lastPortal != NEVER && nanos - this.lastPortal <= PORTAL_GRACE_MS * 1_000_000L) {
			return null;
		}
		boolean selfLow = selfHealthLow(nanos, window);
		boolean hitByThem = this.lastTaken != NEVER && nanos - this.lastTaken <= window;
		if (selfLow && hitByThem && (this.lastTaken > this.lastDealt || opponentHealthHigh(nanos, window))) {
			return FightEndReason.SELF_DIED;
		}
		if (!selfLow && yourHitLast(nanos, window) && opponentHealthPlausible(nanos, window)) {
			return FightEndReason.OPPONENT_DIED;
		}
		return null;
	}

	/** Your hit on the engaged opponent is the last hit of the fight and lies within {@code window} of {@code nanos}. */
	private boolean yourHitLast(long nanos, long window) {
		return opponentEngaged() && this.lastDealt != NEVER && nanos - this.lastDealt <= window
				&& this.lastDealt > this.lastTaken;
	}

	private boolean opponentEngaged() {
		return this.opponent != null && this.engaged.contains(this.opponent.uuid());
	}

	/** The opponent's death is plausible: their health is hidden, or it was at or below {@link #LOW_HEALTH} within the window. */
	private boolean opponentHealthPlausible(long nanos, long window) {
		if (!this.opponentHealthVisible) {
			return true;
		}
		float min = minHealth(this.oppHealth, nanos, window);
		return Float.isNaN(min) || min <= LOW_HEALTH;
	}

	/** The opponent's synced health stayed above {@link #LOW_HEALTH} for the whole window: they did not die. */
	private boolean opponentHealthHigh(long nanos, long window) {
		if (!this.opponentHealthVisible) {
			return false;
		}
		float min = minHealth(this.oppHealth, nanos, window);
		return !Float.isNaN(min) && min > LOW_HEALTH;
	}

	/** Your health was at or below {@link #LOW_HEALTH} within the window. */
	private boolean selfHealthLow(long nanos, long window) {
		float min = minHealth(this.selfHealth, nanos, window);
		return !Float.isNaN(min) && min <= LOW_HEALTH;
	}

	private void sample(long nanos, OpponentPresence p, SelfState me) {
		this.sampleNanos[this.sampleNext] = nanos;
		this.oppHealth[this.sampleNext] = p.healthFrac();
		this.selfHealth[this.sampleNext] = me.healthFrac();
		this.sampleNext = (this.sampleNext + 1) % SAMPLES;
		this.sampleCount = Math.min(this.sampleCount + 1, SAMPLES);
	}

	/** The lowest finite sample within {@code window} of {@code nanos}, or NaN when there is none. */
	private float minHealth(float[] health, long nanos, long window) {
		float min = Float.NaN;
		for (int i = 0; i < this.sampleCount; i++) {
			float h = health[i];
			if (nanos - this.sampleNanos[i] <= window && Float.isFinite(h) && (Float.isNaN(min) || h < min)) {
				min = h;
			}
		}
		return min;
	}

	private static boolean isDeath(@Nullable FightEndReason reason) {
		return reason == FightEndReason.OPPONENT_DIED || reason == FightEndReason.SELF_DIED;
	}
}
