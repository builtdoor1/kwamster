package kwamster.core.fight;

import kwamster.core.event.DamageObservedEvent;
import kwamster.core.event.HitConfirmedEvent;
import kwamster.core.event.HitTakenEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.event.TradeEvent;
import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.HitQuality;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import kwamster.core.stats.AimZones;
import kwamster.core.stats.HitGrader;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * Builds a {@link FightRecord} incrementally (§4.9). Minecraft-free.
 *
 * <p>Every event time passed in is on the fight clock; {@code t} values are whole milliseconds
 * (floor) relative to {@code startNanos}. Positions are stored relative to {@code origin}. Opponents
 * are keyed by UUID only; the first name seen is kept. Lists stay sorted by {@code t} (an event that
 * arrives out of order is inserted after every entry with an equal or smaller {@code t}).
 *
 * <p><b>Sanitising (normative).</b> Every float or double stored passes through
 * {@code finiteOr(x, fallback)}: NaN or infinity becomes the fallback (0, or -1 for {@code rayReach})
 * and the matching {@code *Known} flag, if any, is set false. Gson is never configured to accept
 * special floats, so this keeps every save valid.
 *
 * <p>After {@link #finish} the record is frozen and further calls are ignored.
 */
public final class FightAccumulator {
	/** A direct confirmation matches swings up to {@code latestPingMs + CONFIRM_SLACK_MS} before it (§5.9). */
	public static final int CONFIRM_SLACK_MS = 300;
	/** Range limit stored when the event's own value is unusable (vanilla entity interaction range). */
	private static final double FALLBACK_RANGE_LIMIT = 3.0;

	private final long startNanos;
	private final Pos origin;
	private final FightRecord record = new FightRecord();
	/** Landed hits plus melee hits taken per opponent index (primary-opponent choice). */
	private final List<Integer> authoritative = new ArrayList<>();
	private long pingSum;
	private int pingCount;
	private int latestPing;
	private boolean finished;

	public FightAccumulator(long startNanos, long startEpochMs, PlayerRef self, PlayerRef opponent,
			String server, FightRecord.Settings settings, Pos origin) {
		this.startNanos = startNanos;
		this.origin = origin == null ? Pos.ZERO : origin;
		this.record.startEpochMs = startEpochMs;
		if (self != null) {
			this.record.playerName = nameOf(self);
			this.record.playerUuid = self.uuid() == null ? "" : self.uuid().toString();
		} else {
			this.record.playerName = "";
			this.record.playerUuid = "";
		}
		this.record.server = server == null ? "" : server;
		this.record.settings = settings == null ? FightRecord.Settings.defaults() : settings;
		opponentIndex(Objects.requireNonNull(opponent, "opponent"));
		updatePrimary();
	}

	/** Fight start on the fight clock. */
	public long startNanos() {
		return this.startNanos;
	}

	/** Fight-clock nanos → record {@code t}: whole milliseconds since the start (floor), clamped to the int range. */
	public int relMs(long fightNanos) {
		long ms = Math.floorDiv(fightNanos - this.startNanos, 1_000_000L);
		return (int) Math.max(Integer.MIN_VALUE + 1L, Math.min(Integer.MAX_VALUE - 1L, ms));
	}

	/** Keyed by UUID only; adds (not engaged) if new and keeps the first name seen. */
	public int opponentIndex(PlayerRef p) {
		Objects.requireNonNull(p, "player");
		int existing = indexOf(p.uuid());
		if (existing >= 0) {
			return existing;
		}
		if (this.finished) {
			return -1;
		}
		this.record.opponents.add(nameOf(p));
		this.record.opponentUuids.add(p.uuid() == null ? "" : p.uuid().toString());
		this.record.engaged.add(Boolean.FALSE);
		this.authoritative.add(0);
		return this.record.opponents.size() - 1;
	}

	/** The index of the opponent with this UUID, or -1. Never adds. */
	public int indexOf(@Nullable UUID uuid) {
		if (uuid == null) {
			return -1;
		}
		String key = uuid.toString();
		List<String> uuids = this.record.opponentUuids;
		for (int i = 0; i < uuids.size(); i++) {
			if (key.equals(uuids.get(i))) {
				return i;
			}
		}
		return -1;
	}

	/** A landed swing marks its opponent engaged. {@code q} null for a miss; a landed swing without one is graded with the snapshot. */
	public void addSwing(SwingEvent e, @Nullable HitQuality q) {
		if (this.finished || e == null || e.target() == null) {
			return;
		}
		FightRecord.Swing s = new FightRecord.Swing();
		s.t = relMs(e.nanos());
		s.opp = opponentIndex(e.target());
		s.landed = e.landed();
		s.clickTimed = e.clickTimed();
		s.clickLeadMs = e.clickTimed() ? Math.max(0, Math.min(200, e.clickLeadMs())) : 0;
		s.intoImmunity = e.landed() && e.intoImmunity();
		s.customRange = e.customRange();
		s.reach = toFloat(e.reach(), 0.0);
		s.rayReach = toFloat(e.rayReach(), -1.0);
		double limit = finiteOr(e.rangeLimit(), FALLBACK_RANGE_LIMIT);
		s.rangeLimit = (float) (limit > 0 ? limit : FALLBACK_RANGE_LIMIT);
		AimSample aim = e.aim() == null ? AimSample.NONE : e.aim();
		s.aimH = toFloat(aim.h(), 0.0);
		s.aimV = toFloat(aim.v(), 0.0);
		if (e.landed()) {
			AimZone zone = aim.zone() != null ? aim.zone() : AimZones.of(s.aimH, s.aimV);
			s.zone = zone.name();
			HitQuality quality = q != null ? q
					: HitGrader.grade(e.charged(), e.crit(), e.sprint(), s.reach, this.record.settings.threeBlockReach, e.customRange());
			s.quality = quality.name();
		}
		s.charge = toFloat(e.charge(), 0.0);
		s.charged = e.charged();
		s.crit = e.crit();
		s.sprint = e.sprint();
		s.sweep = e.sweep();
		s.onGround = e.onGround();
		Pos self = e.selfPos();
		Pos target = e.targetPos();
		s.sx = relX(self);
		s.sz = relZ(self);
		s.ox = relX(target);
		s.oz = relZ(target);
		s.yaw = toFloat(e.selfYaw(), 0.0);
		insertSorted(this.record.swings, s, sw -> sw.t);
		if (s.landed) {
			markAuthoritative(s.opp);
		}
	}

	/** Melee only (a projectile event is ignored); marks the attacker engaged. */
	public void addHitTaken(HitTakenEvent e) {
		if (this.finished || e == null || e.attacker() == null || e.projectile()) {
			return;
		}
		FightRecord.HitTaken h = new FightRecord.HitTaken();
		h.t = relMs(e.nanos());
		h.opp = opponentIndex(e.attacker());
		h.reach = toFloat(e.attackerReach(), 0.0);
		h.onGround = e.selfOnGround();
		h.sprinting = e.selfSprinting();
		h.sx = relX(e.selfPos());
		h.sz = relZ(e.selfPos());
		h.ox = relX(e.attackerPos());
		h.oz = relZ(e.attackerPos());
		insertSorted(this.record.hitsTaken, h, x -> x.t);
		markAuthoritative(h.opp);
	}

	public void addProjectileHitTaken() {
		if (!this.finished) {
			this.record.projectileHitsTaken++;
		}
	}

	public void addPiercingSwing() {
		if (!this.finished) {
			this.record.piercingSwings++;
		}
	}

	public void addJumpReset(JumpResetEvent e) {
		if (this.finished || e == null) {
			return;
		}
		FightRecord.JumpAttempt j = new FightRecord.JumpAttempt();
		j.t = relMs(e.nanos());
		j.deltaTicks = e.deltaTicks();
		j.deltaMs = e.deltaMs();
		j.wallMs = e.wallMs();
		j.success = e.success();
		insertSorted(this.record.jumps, j, x -> x.t);
	}

	public void addTrade(TradeEvent e) {
		if (this.finished || e == null || e.opponent() == null) {
			return;
		}
		FightRecord.Trade t = new FightRecord.Trade();
		t.t = relMs(e.nanos());
		t.opp = opponentIndex(e.opponent());
		boolean damageFinite = Double.isFinite(e.dealt()) && Double.isFinite(e.taken());
		t.dealt = toFloat(e.dealt(), 0.0);
		t.taken = toFloat(e.taken(), 0.0);
		t.damageKnown = e.damageKnown() && damageFinite;
		t.momentumPct = toFloat(e.momentumPct(), 0.0);
		t.momentumKnown = e.momentumKnown() && Double.isFinite(e.momentumPct());
		insertSorted(this.record.trades, t, x -> x.t);
	}

	/**
	 * Direct confirmations only (§5.9): marks the EARLIEST unconfirmed landed swing on the victim with
	 * {@code tc - (latestPingMs + 300) ≤ t ≤ tc} as confirmed. A confirmation with no match (sweep
	 * damage on a bystander, a spear stab, a hit from before the fight) is dropped. Never adds opponents.
	 */
	public void addConfirm(HitConfirmedEvent e) {
		if (this.finished || e == null || !e.direct() || e.victim() == null) {
			return;
		}
		int opp = indexOf(e.victim().uuid());
		if (opp < 0) {
			return;
		}
		long tc = relMs(e.nanos());
		long from = tc - (this.latestPing + (long) CONFIRM_SLACK_MS);
		for (FightRecord.Swing s : this.record.swings) {
			if (s.t > tc) {
				break;
			}
			if (s.landed && !s.confirmed && s.opp == opp && s.t >= from) {
				s.confirmed = true;
				return;
			}
		}
	}

	/**
	 * FightManager passes only self drops or drops of a player already in opponents (UUID lookup);
	 * everything else is dropped before this call. Never adds opponents. {@code DamageDrop.opp} is -1
	 * for self.
	 */
	public void addDamage(DamageObservedEvent e, int oppIndexOrMinus1) {
		if (this.finished || e == null) {
			return;
		}
		if (!e.self() && (oppIndexOrMinus1 < 0 || oppIndexOrMinus1 >= this.record.opponents.size())) {
			return;
		}
		FightRecord.DamageDrop d = new FightRecord.DamageDrop();
		d.t = relMs(e.nanos());
		d.self = e.self();
		d.opp = e.self() ? -1 : oppIndexOrMinus1;
		d.amount = toFloat(e.amount(), 0.0);
		insertSorted(this.record.damage, d, x -> x.t);
	}

	/** One ping sample; non-positive latencies (unknown) are ignored. */
	public void addPingSample(int ms) {
		if (this.finished || ms <= 0) {
			return;
		}
		this.pingSum += ms;
		this.pingCount++;
		this.latestPing = ms;
	}

	/** The latest positive ping sample; 0 before the first sample. */
	public int latestPingMs() {
		return this.latestPing;
	}

	/** Called only for opponent drops. */
	public void markHealthVisible() {
		if (!this.finished) {
			this.record.opponentHealthVisible = true;
		}
	}

	/** The live record (owned by the accumulator; do not mutate outside). */
	public FightRecord view() {
		return this.record;
	}

	/** True once {@link #finish} has run. */
	public boolean finished() {
		return this.finished;
	}

	/**
	 * Freezes the record: duration {@code max(0, (endNanos - startNanos)/1e6)}, result, reason, mean ping,
	 * primary opponent and the base id ({@link FightRecord#baseId}). {@code stats} is left for the caller.
	 */
	public FightRecord finish(long endNanos, FightResult result, FightEndReason reason, String modVersion) {
		if (this.finished) {
			return this.record;
		}
		this.record.durationMs = Math.max(0, relMs(endNanos));
		this.record.result = (result == null ? FightResult.DRAW : result).name();
		this.record.endReason = (reason == null ? FightEndReason.IDLE : reason).name();
		this.record.modVersion = modVersion == null ? "" : modVersion;
		this.record.pingMs = this.pingCount > 0 ? (int) Math.round((double) this.pingSum / this.pingCount) : 0;
		updatePrimary();
		this.record.id = FightRecord.baseId(this.record.startEpochMs, this.record.opponentName);
		this.finished = true;
		return this.record;
	}

	/**
	 * Package-private: marks an opponent engaged without an event (the synthetic gametest opponent,
	 * which "counts as engaged", §4.10).
	 */
	void markEngaged(int opp) {
		if (!this.finished && opp >= 0 && opp < this.record.engaged.size()) {
			this.record.engaged.set(opp, Boolean.TRUE);
			updatePrimary();
		}
	}

	// ---- internals ----------------------------------------------------------

	private void markAuthoritative(int opp) {
		if (opp < 0 || opp >= this.record.engaged.size()) {
			return;
		}
		this.record.engaged.set(opp, Boolean.TRUE);
		this.authoritative.set(opp, this.authoritative.get(opp) + 1);
		updatePrimary();
	}

	/** Among engaged opponents, the most landed hits plus melee hits taken; ties to the lowest index; index 0 if nobody is engaged. */
	private void updatePrimary() {
		int best = -1;
		int bestCount = -1;
		for (int i = 0; i < this.record.engaged.size(); i++) {
			if (Boolean.TRUE.equals(this.record.engaged.get(i)) && this.authoritative.get(i) > bestCount) {
				best = i;
				bestCount = this.authoritative.get(i);
			}
		}
		if (best < 0) {
			best = 0;
		}
		if (best < this.record.opponents.size()) {
			this.record.opponentName = this.record.opponents.get(best);
			this.record.opponentUuid = this.record.opponentUuids.get(best);
		}
	}

	private float relX(@Nullable Pos p) {
		return p == null ? 0f : toFloat(p.x() - this.origin.x(), 0.0);
	}

	private float relZ(@Nullable Pos p) {
		return p == null ? 0f : toFloat(p.z() - this.origin.z(), 0.0);
	}

	private static String nameOf(PlayerRef p) {
		return p.name() == null ? "" : p.name();
	}

	private static double finiteOr(double x, double fallback) {
		return Double.isFinite(x) ? x : fallback;
	}

	/** {@code finiteOr} narrowed to float: a value that is not finite as a float becomes the fallback. */
	private static float toFloat(double x, double fallback) {
		float v = (float) x;
		return Float.isFinite(v) ? v : (float) fallback;
	}

	/** Inserts after every element whose t is ≤ the new one's (stable; O(1) for in-order arrivals). */
	private static <T> void insertSorted(List<T> list, T item, ToIntFunction<T> t) {
		int key = t.applyAsInt(item);
		int i = list.size();
		while (i > 0 && t.applyAsInt(list.get(i - 1)) > key) {
			i--;
		}
		list.add(i, item);
	}
}
