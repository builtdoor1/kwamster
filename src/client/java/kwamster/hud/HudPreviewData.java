package kwamster.hud;

import kwamster.core.event.HitResolvedEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.model.AimSample;
import kwamster.core.model.AimZone;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.Pos;
import kwamster.core.model.StoplightReading;
import kwamster.core.model.StoplightState;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Deterministic sample data the HUD editor draws (§7.3): a fight in progress "vs Kwamix 0:42", a
 * 3-block crit indicator "2.97 3-BLOCK CRIT", combo 4, a GREEN stoplight and a worst-case WIN card
 * (16-character name, {@code 100%}, {@code 12/17}, the longest key name).
 *
 * <p>The numbers are chosen so the elements format exactly the sample strings of
 * {@link PreviewStrings} ({@link PreviewStrings#PANEL_ROWS_SAMPLE}, {@link PreviewStrings#CARD_CELLS_SAMPLE}),
 * which is what the fit tests measure. Every accessor returns a fresh object, so a caller can never
 * corrupt the samples. Client thread.
 */
public final class HudPreviewData {
	/** The sample fight's elapsed time (shown as {@code 0:42}). */
	public static final long PANEL_ELAPSED_MS = 42_000L;
	/** The sample combo counter value. */
	public static final int COMBO = 4;
	/** The sample stoplight speed (shown as {@code APPROACH +3.1}). */
	public static final double STOPLIGHT_BPS = 3.1;

	private HudPreviewData() {
	}

	/** The sample opponent. */
	public static PlayerRef opponent() {
		return ref(PreviewStrings.SAMPLE_NAME);
	}

	/**
	 * Live numbers of the sample fight: Hits 23/31 · 74%, Reach 2.84 avg · 3.00, Combo 3 · best 5,
	 * Streak 4 · best 9, Crits 6 · 26%, Sprint 12 · 52%, 3-Block 4 · 17%, Resets 5/7 · 71%,
	 * Interval 548 ms, Taken 14, Aim C12 E4 H5 L2, Trades 3W 1L, Advantage YOU.
	 */
	public static FightStats panelStats() {
		FightStats s = new FightStats();
		s.durationMs = (int) PANEL_ELAPSED_MS;
		s.swings = 31;
		s.landed = 23;
		s.misses = 8;
		s.accuracyPct = 100.0 * 23 / 31;
		s.reachAvg = 2.84;
		s.reachMax = 3.00;
		s.reachCount = 23;
		s.aimCenter = 12;
		s.aimEdge = 4;
		s.aimHigh = 5;
		s.aimLow = 2;
		s.currentCombo = 3;
		s.bestCombo = 5;
		s.combosDealt = 3;
		s.comboHitsDealt = 11;
		s.avgCombo = 11 / 3.0;
		s.currentStreak = 4;
		s.bestStreak = 9;
		s.advantage = 0.42;
		s.advYouPct = 62;
		s.advThemPct = 21;
		s.sprintHits = 12;
		s.nonSprintHits = 11;
		s.sprintPct = 100.0 * 12 / 23;
		s.crits = 6;
		s.critPct = 100.0 * 6 / 23;
		s.intervalCount = 15;
		s.intervalAvgMs = 548;
		s.intervalSdMs = 61;
		s.threeBlockHits = 4;
		s.threeBlockPct = 100.0 * 4 / 23;
		s.quality = new int[] {2, 1, 1, 4, 11, 3, 1};
		s.jumpAttempts = 7;
		s.jumpSuccess = 5;
		s.jumpLate = 2;
		s.jumpOpportunities = 9;
		s.jumpPct = 100.0 * 5 / 7;
		s.jumpAvgDeltaMs = 36;
		s.hitsTaken = 14;
		s.takenReachAvg = 2.71;
		s.trades = 4;
		s.tradesWon = 3;
		s.tradesLost = 1;
		s.tradeWinPct = 75;
		return s;
	}

	/** The worst-case card's final numbers: Acc 100%, Reach 2.97, Max 3.00, Crits 12, 3-Block 9, Sprint 17, Combo 7, Resets 12/17, Taken 14. */
	public static FightStats cardStats() {
		FightStats s = new FightStats();
		s.durationMs = (int) PANEL_ELAPSED_MS;
		s.swings = 31;
		s.landed = 31;
		s.accuracyPct = 100;
		s.reachAvg = 2.97;
		s.reachMax = 3.00;
		s.reachCount = 31;
		s.crits = 12;
		s.critPct = 100.0 * 12 / 31;
		s.threeBlockHits = 9;
		s.threeBlockPct = 100.0 * 9 / 31;
		s.sprintHits = 17;
		s.nonSprintHits = 14;
		s.sprintPct = 100.0 * 17 / 31;
		s.bestCombo = 7;
		s.bestStreak = 31;
		s.jumpAttempts = 17;
		s.jumpSuccess = 12;
		s.jumpLate = 5;
		s.jumpPct = 100.0 * 12 / 17;
		s.hitsTaken = 14;
		return s;
	}

	/** The worst-case card's record: a WIN in 0:42 against a 16-character name. */
	public static FightRecord cardRecord() {
		FightRecord r = new FightRecord();
		r.id = "preview";
		r.result = FightResult.WIN.name();
		r.endReason = "OPPONENT_DIED";
		r.durationMs = (int) PANEL_ELAPSED_MS;
		r.opponentName = PreviewStrings.WORST_NAME;
		r.opponentUuid = ref(PreviewStrings.WORST_NAME).uuid().toString();
		r.settings = FightRecord.Settings.defaults();
		r.stats = cardStats();
		return r;
	}

	/** The key name the preview card's footer shows. */
	public static String cardKeyName() {
		return PreviewStrings.WORST_KEY;
	}

	/** A landed 3-block crit at 2.97 blocks, resolved at {@code nowNanos}. */
	public static HitResolvedEvent hit(long nowNanos) {
		PlayerRef target = opponent();
		SwingEvent swing = new SwingEvent(nowNanos, false, 0, target, true, false,
				2.97, 2.97, 3.0, false, new AimSample(0.1, 0.45, AimZone.HIGH, false),
				1.0f, true, true, false, false, false, Pos.ZERO, new Pos(0.0, 0.0, 2.97), 0f, 0);
		return new HitResolvedEvent(swing, HitQuality.THREE_BLOCK_CRIT, COMBO, COMBO, 548, nowNanos);
	}

	/** A GREEN reading at +3.1 b/s on the sample opponent. */
	public static StoplightReading stoplight() {
		return new StoplightReading(StoplightState.GREEN, STOPLIGHT_BPS, false, opponent(), 0L);
	}

	private static PlayerRef ref(String name) {
		return new PlayerRef(UUID.nameUUIDFromBytes(("kwamster-preview:" + name).getBytes(StandardCharsets.UTF_8)), name);
	}
}
