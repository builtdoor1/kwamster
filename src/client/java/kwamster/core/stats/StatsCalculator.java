package kwamster.core.stats;

import kwamster.core.model.AimZone;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * {@link FightRecord} → {@link FightStats}: the single source of truth for every number KWAMSTER
 * shows (§4.5, §5). The HUD, the chat lines, the card, the saved files and the live
 * {@code HitResolvedEvent} fields all come from here. Minecraft-free.
 *
 * <p>{@code nowRelMs} is the fight-clock time relative to the fight start. The live view passes the
 * current time; then {@code durationMs = max(0, nowRel)}. Finished fights pass {@link #FINAL}; then
 * {@code durationMs = record.durationMs}, every time-dependent figure (advantage, chain timeouts) is
 * evaluated at {@code durationMs}, the fight end closes both combo chains, and only
 * {@code currentCombo}, {@code currentComboTaken} and {@code currentStreak} are reported as 0.
 *
 * <p>Every constant comes from {@code record.settings} (the snapshot taken at fight open). Every
 * division is guarded: with no data a figure is 0 and its count is 0, never NaN.
 */
public final class StatsCalculator {
	/** Pass as {@code nowRelMs} for a finished fight. */
	public static final int FINAL = Integer.MAX_VALUE;
	/** A health drop up to this long BEFORE a hit is still linked to it (§5.9). */
	public static final int DAMAGE_LINK_BACK_MS = 100;
	/** A health drop up to this long AFTER a hit is linked to it (combat-report DAMAGE_LINK_MAX_MS). */
	public static final int DAMAGE_LINK_FORWARD_MS = 900;
	/** Fallback attack range for swings whose stored range limit is unusable. */
	private static final double VANILLA_RANGE = 3.0;

	private static final Comparator<FightRecord.Swing> SWING_ORDER = Comparator.comparingInt(s -> s.t);
	private static final Comparator<FightRecord.HitTaken> TAKEN_ORDER = Comparator.comparingInt(h -> h.t);

	private StatsCalculator() {
	}

	/** Every derived number of {@code r} at {@code nowRelMs} ({@link #FINAL} for a finished fight). A null record gives all zeros. */
	public static FightStats compute(@Nullable FightRecord r, int nowRelMs) {
		FightStats s = new FightStats();
		if (r == null) {
			return s;
		}
		FightRecord.Settings st = settings(r);
		boolean fin = nowRelMs == FINAL;
		int duration = fin ? Math.max(0, r.durationMs) : Math.max(0, nowRelMs);
		s.durationMs = duration;

		List<FightRecord.Swing> swings = sortedSwings(r);
		List<FightRecord.HitTaken> taken = sortedTaken(r);

		swingTotals(s, swings, st);
		streak(s, swings, fin);
		intervals(s, swings, st);
		momentum(s, swings, taken, st, duration, fin);
		jumps(s, r, taken, st);
		hitsTaken(s, r, taken);
		trades(s, r);
		damage(s, r, swings, taken);
		s.piercingSwings = Math.max(0, r.piercingSwings);
		return s;
	}

	/**
	 * The S8 interval closed by the latest landed non-NO-REG swing of {@code r} (same opponent as the
	 * previous such swing, {@code 0 ≤ Δ ≤ intervalMaxGapMs}), or -1 if it closed none. This is the
	 * {@code HitResolvedEvent.intervalMs} rule, evaluated with the same code as {@link #compute}.
	 */
	public static int lastIntervalMs(@Nullable FightRecord r) {
		if (r == null) {
			return -1;
		}
		FightRecord.Settings st = settings(r);
		int result = -1;
		FightRecord.Swing prev = null;
		for (FightRecord.Swing sw : sortedSwings(r)) {
			if (!countsForMomentum(sw)) {
				continue;
			}
			result = -1;
			if (prev != null) {
				long d = interval(prev, sw, st);
				if (d >= 0) {
					result = (int) d;
				}
			}
			prev = sw;
		}
		return result;
	}

	/** A(t) at relative time {@code tRelMs} with the chain state as of that time (§5.5). */
	static double advantageAt(@Nullable FightRecord r, int tRelMs) {
		if (r == null) {
			return 0.0;
		}
		FightRecord.Settings st = settings(r);
		Timeline tl = new Timeline(sortedSwings(r), sortedTaken(r), st);
		tl.advanceTo(tRelMs);
		return tl.advantage(tRelMs);
	}

	// ---- S2, S3, S4, S6, S7, S8 tiers, confirmation ---------------------------

	private static void swingTotals(FightStats s, List<FightRecord.Swing> swings, FightRecord.Settings st) {
		double reachSum = 0.0;
		double missSum = 0.0;
		for (FightRecord.Swing sw : swings) {
			s.swings++;
			double reach = finiteOr(sw.reach, 0.0);
			if (sw.landed) {
				s.landed++;
				if (sw.intoImmunity) {
					s.immuneHits++;
				}
				reachSum += reach;
				s.reachMax = Math.max(s.reachMax, reach);
				s.reachCount++;
				switch (zoneOf(sw)) {
					case CENTER -> s.aimCenter++;
					case EDGE -> s.aimEdge++;
					case HIGH -> s.aimHigh++;
					case LOW -> s.aimLow++;
				}
				HitQuality q = qualityOf(sw, st);
				s.quality[q.code()]++;
				if (q.threeBlock()) {
					s.threeBlockHits++;
				}
				// crit = charged && canCriticalAttack; a crit requires !isSprinting, so the sprint-hit
				// count (charged && sprinting) excludes crits and always equals quality[1] + quality[4].
				if (sw.charged && sw.crit) {
					s.crits++;
				}
				if (sw.charged && sw.sprint && !sw.crit) {
					s.sprintHits++;
				}
				if (sw.confirmed) {
					s.hitsConfirmed++;
				}
			} else {
				missSum += reach;
				double limit = Float.isFinite(sw.rangeLimit) && sw.rangeLimit > 0f ? sw.rangeLimit : VANILLA_RANGE;
				if (s.missReachCount == 0 || reach > s.missReachMax) {
					s.missReachMax = reach;
					s.missReachMaxLimit = limit;
				}
				s.missReachCount++;
				if (reach > limit) {
					s.overReachMisses++;
				}
			}
		}
		s.misses = s.swings - s.landed;
		s.accuracyPct = pct(s.landed, s.swings);
		s.reachAvg = s.reachCount > 0 ? reachSum / s.reachCount : 0.0;
		s.missReachAvg = s.missReachCount > 0 ? missSum / s.missReachCount : 0.0;
		s.nonSprintHits = s.landed - s.sprintHits;
		s.sprintPct = pct(s.sprintHits, s.landed);
		s.critPct = pct(s.crits, s.landed);
		s.threeBlockPct = pct(s.threeBlockHits, s.landed);
		s.confirmPct = pct(s.hitsConfirmed, s.landed);
	}

	// ---- S5 hit streak ------------------------------------------------------

	/** Landed swings in a row with no miss between; NO-REG hits neither count nor break it; hits taken do not break it. */
	private static void streak(FightStats s, List<FightRecord.Swing> swings, boolean fin) {
		int streak = 0;
		int best = 0;
		for (FightRecord.Swing sw : swings) {
			if (sw.landed) {
				if (!sw.intoImmunity) {
					streak++;
					best = Math.max(best, streak);
				}
			} else {
				streak = 0;
			}
		}
		s.bestStreak = best;
		s.currentStreak = fin ? 0 : streak;
	}

	// ---- S8 intervals -------------------------------------------------------

	private static void intervals(FightStats s, List<FightRecord.Swing> swings, FightRecord.Settings st) {
		List<Long> values = new ArrayList<>();
		FightRecord.Swing prev = null;
		for (FightRecord.Swing sw : swings) {
			if (!countsForMomentum(sw)) {
				continue;
			}
			if (prev != null) {
				long d = interval(prev, sw, st);
				if (d >= 0) {
					values.add(d);
				}
			}
			prev = sw;
		}
		int n = values.size();
		s.intervalCount = n;
		if (n == 0) {
			return;
		}
		double sum = 0.0;
		for (long v : values) {
			sum += v;
		}
		double mean = sum / n;
		double sq = 0.0;
		for (long v : values) {
			double d = v - mean;
			sq += d * d;
		}
		s.intervalAvgMs = mean;
		s.intervalSdMs = Math.sqrt(sq / n);
	}

	/**
	 * Δ = (t_i - clickLead_i) - (t_{i-1} - clickLead_{i-1}) for consecutive landed non-NO-REG swings on
	 * the same opponent, or -1 when the pair does not qualify (other opponent, negative, or above
	 * {@code intervalMaxGapMs}).
	 */
	private static long interval(FightRecord.Swing prev, FightRecord.Swing cur, FightRecord.Settings st) {
		if (prev.opp != cur.opp) {
			return -1;
		}
		long d = clickTime(cur) - clickTime(prev);
		return d >= 0 && d <= st.intervalMaxGapMs ? d : -1;
	}

	private static long clickTime(FightRecord.Swing sw) {
		return (long) sw.t - Math.max(0, sw.clickLeadMs);
	}

	// ---- S5 combos and advantage ---------------------------------------------

	private static void momentum(FightStats s, List<FightRecord.Swing> swings, List<FightRecord.HitTaken> taken,
			FightRecord.Settings st, int duration, boolean fin) {
		Timeline tl = new Timeline(swings, taken, st);
		int samples = duration / AdvantageModel.SAMPLE_MS + 1;
		int you = 0;
		int them = 0;
		for (int k = 0; k < samples; k++) {
			int tau = k * AdvantageModel.SAMPLE_MS;
			tl.advanceTo(tau);
			double a = tl.advantage(tau);
			if (AdvantageModel.isYou(a)) {
				you++;
			} else if (AdvantageModel.isThem(a)) {
				them++;
			}
		}
		s.advYouPct = 100.0 * you / samples;
		s.advThemPct = 100.0 * them / samples;

		// The headline advantage and the current chains, as of durationMs.
		tl.advanceTo(duration);
		s.advantage = tl.advantage(duration);
		Chains ch = tl.chains;
		s.currentCombo = fin ? 0 : ch.currentDealt(duration);
		s.currentComboTaken = fin ? 0 : ch.currentTaken(duration);

		// Anything stamped after durationMs still belongs to the totals.
		tl.advanceTo(Long.MAX_VALUE);
		if (fin) {
			ch.closeAll();
		}
		s.bestCombo = ch.bestDealt;
		s.combosDealt = ch.combosDealt;
		s.comboHitsDealt = ch.comboHitsDealt;
		s.avgCombo = ch.combosDealt > 0 ? (double) ch.comboHitsDealt / ch.combosDealt : 0.0;
		s.bestComboTaken = ch.bestTaken;
		s.combosTaken = ch.combosTaken;
		s.comboHitsTaken = ch.comboHitsTaken;
		s.avgComboTaken = ch.combosTaken > 0 ? (double) ch.comboHitsTaken / ch.combosTaken : 0.0;
	}

	/**
	 * Landed non-NO-REG swings (D) and melee hits taken (K) merged in record order (ties: K first),
	 * with the windowed counts of the advantage formula.
	 */
	private static final class Timeline {
		final int[] dealt;
		final int[] taken;
		final Chains chains;
		final int window;
		int nextDealt;
		int nextTaken;
		int dealtOut;
		int takenOut;

		Timeline(List<FightRecord.Swing> swings, List<FightRecord.HitTaken> hits, FightRecord.Settings st) {
			int n = 0;
			for (FightRecord.Swing sw : swings) {
				if (countsForMomentum(sw)) {
					n++;
				}
			}
			this.dealt = new int[n];
			int i = 0;
			for (FightRecord.Swing sw : swings) {
				if (countsForMomentum(sw)) {
					this.dealt[i++] = sw.t;
				}
			}
			this.taken = new int[hits.size()];
			for (int j = 0; j < hits.size(); j++) {
				this.taken[j] = hits.get(j).t;
			}
			this.chains = new Chains(Math.max(0, st.comboTimeoutMs));
			this.window = Math.max(1, st.advantageWindowMs);
		}

		/** Applies every event with {@code t ≤ tau} in record order. Does not expire chains. */
		void advanceTo(long tau) {
			while (true) {
				boolean hasDealt = this.nextDealt < this.dealt.length && this.dealt[this.nextDealt] <= tau;
				boolean hasTaken = this.nextTaken < this.taken.length && this.taken[this.nextTaken] <= tau;
				if (!hasDealt && !hasTaken) {
					return;
				}
				if (hasTaken && (!hasDealt || this.taken[this.nextTaken] <= this.dealt[this.nextDealt])) {
					this.chains.taken(this.taken[this.nextTaken++]);
				} else {
					this.chains.dealt(this.dealt[this.nextDealt++]);
				}
			}
		}

		/** A(tau); call after {@link #advanceTo}(tau). Expires timed-out chains as of tau. */
		double advantage(long tau) {
			this.chains.expire(tau);
			long low = tau - this.window;
			while (this.dealtOut < this.nextDealt && this.dealt[this.dealtOut] <= low) {
				this.dealtOut++;
			}
			while (this.takenOut < this.nextTaken && this.taken[this.takenOut] <= low) {
				this.takenOut++;
			}
			return AdvantageModel.value(this.nextDealt - this.dealtOut, this.nextTaken - this.takenOut,
					this.chains.currentDealt(tau), this.chains.currentTaken(tau));
		}
	}

	/**
	 * The dealt and taken combo chains. A dealt hit extends the dealt chain and closes the taken one;
	 * a hit taken does the reverse. A chain also closes when its next own-kind event (or the evaluation
	 * time) is more than {@code comboTimeoutMs} after its last event ({@code 0} = never).
	 */
	private static final class Chains {
		final int timeout;
		int dealtLen;
		int takenLen;
		long lastDealt;
		long lastTaken;
		int bestDealt;
		int bestTaken;
		int combosDealt;
		int comboHitsDealt;
		int combosTaken;
		int comboHitsTaken;

		Chains(int timeout) {
			this.timeout = timeout;
		}

		void dealt(int t) {
			if (this.dealtLen > 0 && timedOut(this.lastDealt, t)) {
				closeDealt();
			}
			this.dealtLen++;
			this.lastDealt = t;
			this.bestDealt = Math.max(this.bestDealt, this.dealtLen);
			closeTaken();
		}

		void taken(int t) {
			if (this.takenLen > 0 && timedOut(this.lastTaken, t)) {
				closeTaken();
			}
			this.takenLen++;
			this.lastTaken = t;
			this.bestTaken = Math.max(this.bestTaken, this.takenLen);
			closeDealt();
		}

		void expire(long now) {
			if (this.dealtLen > 0 && timedOut(this.lastDealt, now)) {
				closeDealt();
			}
			if (this.takenLen > 0 && timedOut(this.lastTaken, now)) {
				closeTaken();
			}
		}

		int currentDealt(long now) {
			return this.dealtLen > 0 && !timedOut(this.lastDealt, now) ? this.dealtLen : 0;
		}

		int currentTaken(long now) {
			return this.takenLen > 0 && !timedOut(this.lastTaken, now) ? this.takenLen : 0;
		}

		void closeAll() {
			closeDealt();
			closeTaken();
		}

		private boolean timedOut(long last, long now) {
			return this.timeout > 0 && now - last > this.timeout;
		}

		private void closeDealt() {
			if (this.dealtLen >= 2) {
				this.combosDealt++;
				this.comboHitsDealt += this.dealtLen;
			}
			this.dealtLen = 0;
		}

		private void closeTaken() {
			if (this.takenLen >= 2) {
				this.combosTaken++;
				this.comboHitsTaken += this.takenLen;
			}
			this.takenLen = 0;
		}
	}

	// ---- S1 jump resets -----------------------------------------------------

	private static void jumps(FightStats s, FightRecord r, List<FightRecord.HitTaken> taken, FightRecord.Settings st) {
		long deltaSum = 0L;
		int nonEarly = 0;
		if (r.jumps != null) {
			for (FightRecord.JumpAttempt j : r.jumps) {
				if (j == null) {
					continue;
				}
				s.jumpAttempts++;
				if (j.deltaTicks <= 0) {
					s.jumpEarly++;
					continue;
				}
				nonEarly++;
				deltaSum += j.deltaMs;
				// Recomputed from the snapshot, so success + early + late always equals attempts.
				if (j.deltaMs >= st.jumpMinMs && j.deltaMs <= st.jumpMaxMs) {
					s.jumpSuccess++;
				} else {
					s.jumpLate++;
				}
			}
		}
		s.jumpPct = pct(s.jumpSuccess, s.jumpAttempts);
		s.jumpAvgDeltaMs = nonEarly > 0 ? (double) deltaSum / nonEarly : 0.0;
		for (FightRecord.HitTaken h : taken) {
			if (h.onGround) {
				s.jumpOpportunities++;
			}
		}
	}

	// ---- hits taken ---------------------------------------------------------

	private static void hitsTaken(FightStats s, FightRecord r, List<FightRecord.HitTaken> taken) {
		s.hitsTaken = taken.size();
		s.projectileHitsTaken = Math.max(0, r.projectileHitsTaken);
		double sum = 0.0;
		for (FightRecord.HitTaken h : taken) {
			sum += finiteOr(h.reach, 0.0);
		}
		s.takenReachAvg = taken.isEmpty() ? 0.0 : sum / taken.size();
	}

	// ---- trades (§5.10) -----------------------------------------------------

	private static void trades(FightStats s, FightRecord r) {
		if (r.trades == null) {
			return;
		}
		double momentumSum = 0.0;
		for (FightRecord.Trade t : r.trades) {
			if (t == null) {
				continue;
			}
			s.trades++;
			if (t.damageKnown && Float.isFinite(t.dealt) && Float.isFinite(t.taken)) {
				if (t.dealt > t.taken) {
					s.tradesWon++;
				} else if (t.dealt < t.taken) {
					s.tradesLost++;
				} else {
					s.tradesEven++;
				}
			}
			if (t.momentumKnown && Float.isFinite(t.momentumPct)) {
				s.tradesWithMomentum++;
				momentumSum += t.momentumPct;
			}
		}
		s.tradeWinPct = pct(s.tradesWon, s.tradesWon + s.tradesLost);
		s.avgMomentumPct = s.tradesWithMomentum > 0 ? momentumSum / s.tradesWithMomentum : 0.0;
	}

	// ---- damage (§5.9) ------------------------------------------------------

	private static void damage(FightStats s, FightRecord r, List<FightRecord.Swing> swings, List<FightRecord.HitTaken> taken) {
		s.damageKnown = r.opponentHealthVisible;
		if (!s.damageKnown || r.damage == null) {
			return;
		}
		for (FightRecord.DamageDrop d : r.damage) {
			if (d == null || !Float.isFinite(d.amount) || d.amount <= 0f) {
				continue;
			}
			if (d.self) {
				if (linkedToHitTaken(d.t, taken)) {
					s.damageTaken += d.amount;
				}
			} else if (d.opp >= 0 && linkedToLandedHit(d.t, d.opp, swings)) {
				s.damageDealt += d.amount;
			}
		}
	}

	private static boolean linkedToLandedHit(int dropT, int opp, List<FightRecord.Swing> swings) {
		for (FightRecord.Swing sw : swings) {
			if (sw.t > (long) dropT + DAMAGE_LINK_BACK_MS) {
				return false;
			}
			if (sw.landed && sw.opp == opp && inLinkWindow(dropT, sw.t)) {
				return true;
			}
		}
		return false;
	}

	private static boolean linkedToHitTaken(int dropT, List<FightRecord.HitTaken> taken) {
		for (FightRecord.HitTaken h : taken) {
			if (h.t > (long) dropT + DAMAGE_LINK_BACK_MS) {
				return false;
			}
			if (inLinkWindow(dropT, h.t)) {
				return true;
			}
		}
		return false;
	}

	/** {@code -100 ≤ t_drop - t_hit ≤ 900}. */
	private static boolean inLinkWindow(int dropT, int hitT) {
		long d = (long) dropT - hitT;
		return d >= -DAMAGE_LINK_BACK_MS && d <= DAMAGE_LINK_FORWARD_MS;
	}

	// ---- helpers ------------------------------------------------------------

	/** Landed and not NO-REG: the swings that build combos, streaks, intervals and the advantage {@code d}. */
	private static boolean countsForMomentum(FightRecord.Swing sw) {
		return sw.landed && !sw.intoImmunity;
	}

	private static FightRecord.Settings settings(FightRecord r) {
		return r.settings != null ? r.settings : FightRecord.Settings.defaults();
	}

	private static List<FightRecord.Swing> sortedSwings(FightRecord r) {
		List<FightRecord.Swing> out = new ArrayList<>(r.swings == null ? 0 : r.swings.size());
		if (r.swings != null) {
			for (FightRecord.Swing sw : r.swings) {
				if (sw != null) {
					out.add(sw);
				}
			}
		}
		out.sort(SWING_ORDER);
		return out;
	}

	private static List<FightRecord.HitTaken> sortedTaken(FightRecord r) {
		List<FightRecord.HitTaken> out = new ArrayList<>(r.hitsTaken == null ? 0 : r.hitsTaken.size());
		if (r.hitsTaken != null) {
			for (FightRecord.HitTaken h : r.hitsTaken) {
				if (h != null) {
					out.add(h);
				}
			}
		}
		out.sort(TAKEN_ORDER);
		return out;
	}

	private static AimZone zoneOf(FightRecord.Swing sw) {
		if (sw.zone != null) {
			try {
				return AimZone.valueOf(sw.zone);
			} catch (IllegalArgumentException ignored) {
				// fall through to the offsets
			}
		}
		return AimZones.of(finiteOr(sw.aimH, 0.0), finiteOr(sw.aimV, 0.0));
	}

	private static HitQuality qualityOf(FightRecord.Swing sw, FightRecord.Settings st) {
		if (sw.quality != null) {
			try {
				return HitQuality.valueOf(sw.quality);
			} catch (IllegalArgumentException ignored) {
				// fall through to regrading
			}
		}
		return HitGrader.grade(sw.charged, sw.crit, sw.sprint, finiteOr(sw.reach, 0.0), st.threeBlockReach, sw.customRange);
	}

	private static double finiteOr(double v, double fallback) {
		return Double.isFinite(v) ? v : fallback;
	}

	private static double pct(int part, int whole) {
		return whole <= 0 ? 0.0 : 100.0 * part / whole;
	}
}
