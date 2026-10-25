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
 * The detection constants ({@code fightIdleMs}, {@code fightMaxDistance}, {@code deathInferenceMs})
 * are read live from the supplier on every {@link #tick}.
 *
 * <p>Phases: IDLE (no fight) → ACTIVE → ENDING (a reason is pending; FightManager finalises on the
 * next client tick) → IDLE via {@link #reset()}.
 */
public final class FightStateMachine {
	/** The lifecycle phase. */
	public enum Phase { IDLE, ACTIVE, ENDING }

	/** Returned by {@link #lastDealtNanos()} and {@link #lastTakenNanos()} when there was no such hit. */
	public static final long NEVER = Long.MIN_VALUE;

	private final Supplier<KwamsterConfig.Advanced> adv;
	private final Set<UUID> engaged = new HashSet<>();
	/** Non-engaged players whose death event was seen this fight (rule 6). */
	private final Set<UUID> deadSeen = new HashSet<>();

	private Phase phase = Phase.IDLE;
	private PlayerRef opponent;
	private long startNanos;
	private long lastCombat;
	private long lastDealt = NEVER;
	private long lastTaken = NEVER;
	private FightEndReason pendingReason;
	private long endNanos;

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

	/** → ENDING with {@code reason} unless already ENDING (a pending death keeps its reason). Ignored while IDLE. */
	public void forceEnding(FightEndReason reason, long nanos) {
		if (this.phase != Phase.ACTIVE) {
			return;
		}
		enterEnding(reason, nanos);
	}

	/**
	 * Evaluates rules 1–8 of §5.13 (first match wins) and returns the reason to finalise now, or null.
	 * While ENDING it returns the pending reason; while IDLE, null. A non-null result leaves the machine
	 * in ENDING until {@link #reset()}.
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
		boolean oppEngaged = this.opponent != null && this.engaged.contains(this.opponent.uuid());
		OpponentPresence presence = p == null ? OpponentPresence.ABSENT : p;
		SelfState me = self == null ? SelfState.ALIVE : self;

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
		// 6. opponent gone, spectating (outside rule 4), or a non-engaged opponent who died
		boolean deadBystander = !oppEngaged && (!presence.alive()
				|| (this.opponent != null && this.deadSeen.contains(this.opponent.uuid())));
		if (!presence.found() || presence.spectator() || deadBystander) {
			return enterEnding(FightEndReason.OPPONENT_LEFT, nanos);
		}
		// 7. too far (3-D feet distance)
		if (presence.distance() > a.fightMaxDistance) {
			return enterEnding(FightEndReason.OPPONENT_FAR, nanos);
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
		boolean death = reason == FightEndReason.OPPONENT_DIED || reason == FightEndReason.SELF_DIED;
		this.endNanos = death ? nanos : this.lastCombat;
		return reason;
	}
}
