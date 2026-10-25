package kwamster.feedback;

import kwamster.Kwamster;
import kwamster.core.LogLimiter;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.config.TierStyle;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.HitResolvedEvent;
import kwamster.core.event.JumpResetEvent;
import kwamster.core.event.SwingEvent;
import kwamster.core.model.AimZone;
import kwamster.core.model.FightEndReason;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.HitQuality;
import kwamster.core.model.ShareLink;
import kwamster.core.stats.AdvantageModel;
import kwamster.core.stats.StatFormat;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * KWAMSTER's chat output (§8.2): per-hit lines (landed, NO-REG, miss), jump-reset lines and the
 * per-fight summary with its full-stats hover and the {@code [Copy link]} {@code [Open]} actions.
 *
 * <p>Chat is allowed only while the master switch is on, the display mode is not HUD only, the chat
 * mode is not OFF and a local player exists (a fight finalised after a disconnect prints nothing).
 * Every number comes from the events and {@code FightStats} as CORE computed them; nothing is
 * recomputed here. Lines go to {@code LocalPlayer.displayClientMessage(component, false)}.
 *
 * <p>Client thread.
 */
public final class ChatReporter {
	/**
	 * Longest tier-count line in the summary hover, in characters (about 200 px; vanilla wraps hover
	 * text at {@code max(guiWidth / 2, 200)} px).
	 */
	private static final int TIER_LINE_CHARS = 34;
	/** Game time per client tick. */
	private static final int TICK_MS = 50;

	private static final String CHECK = "✔";
	private static final String CROSS = "✘";
	private static final String APPROX = "≈";

	// ---- per hit -------------------------------------------------------------

	/** Per-hit line for a resolved swing (chat mode PER_HIT or BOTH). */
	public void onHitResolved(HitResolvedEvent e) {
		SwingEvent swing = e.swing();
		if (swing == null) {
			return;
		}
		KwamsterConfig cfg = Kwamster.config();
		LocalPlayer player = chatPlayer(cfg);
		if (player == null || !perHit(cfg.display.chatMode)) {
			return;
		}
		HitQuality q = e.quality();
		if (q == null) {
			if (cfg.display.chatHitMisses) {
				send(player, missLine(swing));
			}
			return;
		}
		TierStyle tier = cfg.tier(q);
		if (!tier.enabled) {
			return; // a disabled tier suppresses its per-hit chat line (§3.4); the hit is still recorded
		}
		send(player, swing.intoImmunity() ? noRegLine(swing) : landedLine(e, swing, tier));
	}

	/** Per-hit line for a decided jump-reset attempt (chat mode PER_HIT or BOTH, {@code chatJumpResets}). */
	public void onJumpReset(JumpResetEvent e) {
		KwamsterConfig cfg = Kwamster.config();
		LocalPlayer player = chatPlayer(cfg);
		if (player == null || !perHit(cfg.display.chatMode) || !cfg.display.chatJumpResets) {
			return;
		}
		// Only attempts that belong to a fight (CORE records nothing outside one).
		if (!Kwamster.fights().inFight()) {
			return;
		}
		send(player, jumpResetLine(e, cfg.advanced));
	}

	/** {@code [KW] <LABEL> 2.97 · C3 · S5}, hover: charge, aim, sprint, interval and range. */
	static MutableComponent landedLine(HitResolvedEvent e, SwingEvent swing, TierStyle tier) {
		String reach = StatFormat.reach(swing.reach());
		AimZone zone = swing.aim() == null ? null : swing.aim().zone();
		Component hover = ChatFormat.joined(List.of(
				ChatFormat.stat("Charge", StatFormat.pct(swing.charge() * 100.0)),
				ChatFormat.stat("Aim", zone == null ? StatFormat.DASH : zone.name()),
				ChatFormat.stat("Sprint", swing.sprint() ? "yes" : "no"),
				ChatFormat.stat("Interval", e.intervalMs() < 0 ? StatFormat.DASH : StatFormat.ms(e.intervalMs())),
				ChatFormat.stat("Range", reach + " / " + StatFormat.reach(swing.rangeLimit()))));
		MutableComponent body = ChatFormat.hoverSpan(hover)
				.append(ChatFormat.text(tierLabel(tier, e.quality()), tier.argb))
				.append(ChatFormat.value(" " + reach))
				.append(ChatFormat.sep())
				.append(ChatFormat.tight("C", Integer.toString(e.comboAfter())))
				.append(ChatFormat.sep())
				.append(ChatFormat.tight("S", Integer.toString(e.streakAfter())));
		return ChatFormat.line().append(body);
	}

	/** {@code [KW] NO-REG 2.97} in grey, with the immunity explanation as hover. */
	static MutableComponent noRegLine(SwingEvent swing) {
		Component hover = ChatFormat.value(
				"The target was still invulnerable from the previous hit; the server most likely ignored this one.");
		return ChatFormat.line().append(ChatFormat.withHover(
				ChatFormat.text("NO-REG " + StatFormat.reach(swing.reach()), ChatFormat.GREY), hover));
	}

	/**
	 * {@code [KW] MISS 3.42 (over reach)} in grey; "(over reach)" only when {@code reach > rangeLimit}, and
	 * then the reach never reads at or below the range ({@link StatFormat#reachVsRange}).
	 */
	static MutableComponent missLine(SwingEvent swing) {
		String reach = StatFormat.reachVsRange(swing.reach(), swing.rangeLimit());
		boolean over = swing.reach() > swing.rangeLimit();
		String target = swing.target() == null ? null : swing.target().name();
		Component hover = ChatFormat.joined(List.of(
				ChatFormat.stat("Missed", ChatFormat.name(target)),
				ChatFormat.stat("Range", reach + " / " + StatFormat.reach(swing.rangeLimit()))));
		return ChatFormat.line().append(ChatFormat.withHover(
				ChatFormat.text("MISS " + reach + (over ? " (over reach)" : ""), ChatFormat.GREY), hover));
	}

	/**
	 * {@code [KW] Jump reset ✔ tick 1} (good), {@code ✘ tick 4 · late} or {@code ✘ 1 tick early} (bad),
	 * in game ticks (§5.1). A non-early miss below the success window is "off-window". The hover gives
	 * the wall-clock gap and the live success window.
	 */
	static MutableComponent jumpResetLine(JumpResetEvent e, KwamsterConfig.Advanced adv) {
		int colour = e.success() ? ChatFormat.GOOD : ChatFormat.BAD;
		MutableComponent body = Component.empty();
		boolean offWindow = false;
		if (e.success()) {
			body.append(ChatFormat.text("Jump reset " + CHECK + " tick " + e.deltaTicks(), colour));
		} else if (e.deltaTicks() <= 0) {
			body.append(ChatFormat.text("Jump reset " + CROSS + " " + ChatFormat.ticks(1 - e.deltaTicks()) + " early", colour));
		} else {
			offWindow = e.deltaMs() < adv.jumpWindowMinMs;
			body.append(ChatFormat.text("Jump reset " + CROSS + " tick " + e.deltaTicks(), colour))
					.append(ChatFormat.sep())
					.append(ChatFormat.text(offWindow ? "off-window" : "late", colour));
		}

		List<Component> hover = new ArrayList<>();
		int wall = Math.abs(e.wallMs());
		hover.add(ChatFormat.value(APPROX + " " + wall + " ms " + (e.wallMs() < 0 ? "before" : "after") + " the hit (wall clock)"));
		if (e.attacker() != null) {
			hover.add(ChatFormat.stat("Hit by", ChatFormat.name(e.attacker().name())));
		}
		if (offWindow) {
			hover.add(ChatFormat.text("Off-window: sooner than the success window", ChatFormat.DIM));
		}
		hover.add(ChatFormat.text("Success window: " + successWindow(adv.jumpWindowMinMs, adv.jumpWindowMaxMs), ChatFormat.DIM));
		return ChatFormat.line().append(ChatFormat.withHover(body, ChatFormat.lines(hover)));
	}

	/** {@code "tick 1–2 (0–80 ms)"}: the ticks whose game time {@code 50·(tick − 1)} falls in [min, max]. */
	static String successWindow(int minMs, int maxMs) {
		int firstTick = Math.floorDiv(Math.max(0, minMs) + TICK_MS - 1, TICK_MS) + 1;
		int lastTick = Math.floorDiv(Math.max(0, maxMs), TICK_MS) + 1;
		String ms = "(" + minMs + "–" + maxMs + " ms)";
		if (firstTick > lastTick) {
			return "no whole tick " + ms;
		}
		return (firstTick == lastTick ? "tick " + firstTick : "tick " + firstTick + "–" + lastTick) + " " + ms;
	}

	// ---- per fight -----------------------------------------------------------

	/** Fight summary (chat mode PER_FIGHT or BOTH) for a fight that raised an alert. */
	public void onFightEnded(FightEndedEvent e) {
		if (!e.alert() || e.record() == null) {
			return;
		}
		KwamsterConfig cfg = Kwamster.config();
		LocalPlayer player = chatPlayer(cfg);
		if (player == null || !perFight(cfg.display.chatMode)) {
			return;
		}
		FightRecord record = e.record();
		FightStats stats = e.stats() != null ? e.stats() : record.stats;
		FightResult result = ChatFormat.parseResult(record.result);
		Component hover = null;
		if (stats != null) {
			try {
				hover = fightHover(record, stats, result, cfg);
			} catch (RuntimeException ex) {
				// The lines still go out, just without the stats hover.
				LogLimiter.error("ChatReporter.fightHover", "Could not build the fight summary hover", ex);
			}
		}

		ShareLink link = cfg.display.chatShareActions ? shareLink(record) : null;
		send(player, summaryLine1(record, result, hover, link));
		if (stats != null && cfg.display.chatFightLines >= 2) {
			send(player, summaryLine2(stats, hover));
		}
		if (stats != null && cfg.display.chatFightLines >= 3) {
			send(player, summaryLine3(stats, hover));
		}
	}

	/** {@code [KW] WIN vs Kwamix · 0:42  [Copy link] [Open]}. */
	static MutableComponent summaryLine1(FightRecord record, FightResult result, @Nullable Component hover, @Nullable ShareLink link) {
		MutableComponent head = hover == null ? Component.empty() : ChatFormat.hoverSpan(hover);
		head.append(ChatFormat.bold(ChatFormat.resultWord(result), ChatFormat.resultColour(result)))
				.append(ChatFormat.label(" vs "))
				.append(ChatFormat.value(ChatFormat.name(record.opponentName)))
				.append(ChatFormat.sep())
				.append(ChatFormat.value(StatFormat.duration(record.durationMs)));
		MutableComponent line = ChatFormat.line().append(head);
		if (link != null) {
			line.append(Component.literal("  ")).append(ChatFormat.copyLinkAction(link));
			MutableComponent open = ChatFormat.openAction(link);
			if (open != null) {
				line.append(Component.literal(" ")).append(open);
			}
		}
		return line;
	}

	/** {@code    Hits 23/31 (74%) · Reach 2.84/3.00 · Crits 6 · JR 5/7}. */
	static MutableComponent summaryLine2(FightStats s, @Nullable Component hover) {
		String reach = s.reachCount <= 0 ? StatFormat.DASH : StatFormat.reach(s.reachAvg) + "/" + StatFormat.reach(s.reachMax);
		return continuation(hover, List.of(
				ChatFormat.stat("Hits", StatFormat.ratio(s.landed, s.swings) + " (" + StatFormat.pct(s.landed, s.swings) + ")"),
				ChatFormat.stat("Reach", reach),
				ChatFormat.stat("Crits", Integer.toString(s.crits)),
				ChatFormat.stat("JR", StatFormat.ratio(s.jumpSuccess, s.jumpAttempts))));
	}

	/** {@code    Combo 5 · Streak 9 · Sprint 12 · 3-Block 4 · Taken 14}. */
	static MutableComponent summaryLine3(FightStats s, @Nullable Component hover) {
		return continuation(hover, List.of(
				ChatFormat.stat("Combo", Integer.toString(s.bestCombo)),
				ChatFormat.stat("Streak", Integer.toString(s.bestStreak)),
				ChatFormat.stat("Sprint", Integer.toString(s.sprintHits)),
				ChatFormat.stat("3-Block", Integer.toString(s.threeBlockHits)),
				ChatFormat.stat("Taken", Integer.toString(s.hitsTaken))));
	}

	private static MutableComponent continuation(@Nullable Component hover, List<Component> parts) {
		MutableComponent body = ChatFormat.joined(parts);
		return ChatFormat.indented().append(hover == null ? body : ChatFormat.withHover(body, hover));
	}

	/** The multi-line full-stats hover of the summary: every §4.7 group. */
	static Component fightHover(FightRecord r, FightStats s, FightResult result, KwamsterConfig cfg) {
		List<Component> lines = new ArrayList<>();

		// Header and context
		lines.add(Component.empty()
				.append(ChatFormat.bold(ChatFormat.resultWord(result), ChatFormat.resultColour(result)))
				.append(ChatFormat.label(" vs "))
				.append(ChatFormat.value(ChatFormat.name(r.opponentName)))
				.append(ChatFormat.sep())
				.append(ChatFormat.value(StatFormat.duration(r.durationMs))));
		List<String> others = otherEngaged(r);
		if (!others.isEmpty()) {
			lines.add(ChatFormat.stat("Also engaged", String.join(", ", others)));
		}
		String ctx = reasonText(r.endReason);
		if (r.pingMs > 0) {
			ctx += ChatFormat.SEP + "ping " + r.pingMs + " ms";
		}
		lines.add(ChatFormat.text(ctx, ChatFormat.DIM));

		// S4 and confirmation
		List<Component> hits = new ArrayList<>();
		hits.add(ChatFormat.stat("Hits", StatFormat.ratio(s.landed, s.swings) + " (" + StatFormat.pct(s.landed, s.swings) + ")"));
		if (s.immuneHits > 0) {
			hits.add(ChatFormat.stat("NO-REG", Integer.toString(s.immuneHits)));
		}
		lines.add(ChatFormat.joined(hits));
		List<Component> confirmed = new ArrayList<>();
		confirmed.add(ChatFormat.stat("Confirmed", StatFormat.ratio(s.hitsConfirmed, s.landed) + " (" + StatFormat.pct(s.hitsConfirmed, s.landed) + ")"));
		if (s.piercingSwings > 0) {
			confirmed.add(ChatFormat.stat("Spear swings", Integer.toString(s.piercingSwings)));
		}
		lines.add(ChatFormat.joined(confirmed));

		// S2
		lines.add(s.reachCount <= 0 ? ChatFormat.stat("Reach", StatFormat.DASH) : ChatFormat.joined(List.of(
				ChatFormat.stat("Reach", StatFormat.reach(s.reachAvg) + " avg"),
				ChatFormat.stat("max", StatFormat.reach(s.reachMax)))));
		List<Component> misses = new ArrayList<>();
		misses.add(ChatFormat.stat("Misses", Integer.toString(s.misses)));
		if (s.missReachCount > 0) {
			misses.add(ChatFormat.stat("avg", StatFormat.reach(s.missReachAvg)));
			misses.add(ChatFormat.stat("max", StatFormat.reachVsRange(s.missReachMax, s.missReachMaxLimit)));
			misses.add(ChatFormat.stat("over reach", Integer.toString(s.overReachMisses)));
		}
		lines.add(ChatFormat.joined(misses));

		// S3
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Aim centre", Integer.toString(s.aimCenter)),
				ChatFormat.stat("edge", Integer.toString(s.aimEdge)),
				ChatFormat.stat("high", Integer.toString(s.aimHigh)),
				ChatFormat.stat("low", Integer.toString(s.aimLow)))));

		// S5
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Combo best", Integer.toString(s.bestCombo)),
				ChatFormat.stat("avg", s.combosDealt <= 0 ? StatFormat.DASH : StatFormat.decimals(s.avgCombo, 1)),
				ChatFormat.stat("streak", Integer.toString(s.bestStreak)))));
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Taken combo best", Integer.toString(s.bestComboTaken)),
				ChatFormat.stat("avg", s.combosTaken <= 0 ? StatFormat.DASH : StatFormat.decimals(s.avgComboTaken, 1)))));
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Advantage you", StatFormat.pct(s.advYouPct)),
				ChatFormat.stat("them", StatFormat.pct(s.advThemPct)),
				ChatFormat.stat("end", AdvantageModel.label(s.advantage)))));

		// S6, S7, S8
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Sprint", s.sprintHits + " (" + StatFormat.pct(s.sprintPct, s.landed) + ")"),
				ChatFormat.stat("Crits", s.crits + " (" + StatFormat.pct(s.critPct, s.landed) + ")"))));
		String interval = s.intervalCount <= 0 ? StatFormat.DASH
				: Math.round(s.intervalAvgMs) + " ± " + StatFormat.ms(s.intervalSdMs);
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("3-Block", s.threeBlockHits + " (" + StatFormat.pct(s.threeBlockPct, s.landed) + ")"),
				ChatFormat.stat("Interval", interval))));
		lines.addAll(tierLines(s, cfg));

		// S1
		lines.add(ChatFormat.joined(List.of(
				ChatFormat.stat("Jump resets", StatFormat.ratio(s.jumpSuccess, s.jumpAttempts)
						+ " (" + StatFormat.pct(s.jumpSuccess, s.jumpAttempts) + ")"),
				ChatFormat.stat("chances", Integer.toString(s.jumpOpportunities)))));
		if (s.jumpAttempts > 0) {
			int nonEarly = s.jumpAttempts - s.jumpEarly;
			lines.add(ChatFormat.joined(List.of(
					ChatFormat.stat("early", Integer.toString(s.jumpEarly)),
					ChatFormat.stat("late", Integer.toString(s.jumpLate)),
					ChatFormat.stat("avg", StatFormat.ms(s.jumpAvgDeltaMs, nonEarly)))));
		}

		// Taken, trades, damage
		List<Component> taken = new ArrayList<>();
		taken.add(ChatFormat.stat("Taken", Integer.toString(s.hitsTaken)));
		if (s.projectileHitsTaken > 0) {
			taken.add(ChatFormat.stat("projectile", Integer.toString(s.projectileHitsTaken)));
		}
		taken.add(ChatFormat.stat("reach", StatFormat.reach(s.takenReachAvg, s.hitsTaken)));
		lines.add(ChatFormat.joined(taken));
		if (s.trades > 0) {
			lines.add(ChatFormat.joined(List.of(
					ChatFormat.stat("Trades", Integer.toString(s.trades)),
					ChatFormat.stat("won", Integer.toString(s.tradesWon)),
					ChatFormat.stat("lost", Integer.toString(s.tradesLost)),
					ChatFormat.stat("even", Integer.toString(s.tradesEven)))));
			lines.add(ChatFormat.joined(List.of(
					ChatFormat.stat("Trade win", StatFormat.pct(s.tradeWinPct, s.tradesWon + s.tradesLost)),
					ChatFormat.stat("momentum", StatFormat.pct(s.avgMomentumPct, s.tradesWithMomentum)))));
		} else {
			lines.add(ChatFormat.stat("Trades", "0"));
		}
		if (s.damageKnown) {
			lines.add(ChatFormat.joined(List.of(
					ChatFormat.stat("Damage dealt", StatFormat.decimals(s.damageDealt, 1)),
					ChatFormat.stat("taken", StatFormat.decimals(s.damageTaken, 1)))));
		} else {
			lines.add(Component.empty().append(ChatFormat.stat("Damage", StatFormat.DASH))
					.append(ChatFormat.text(" (health hidden)", ChatFormat.DIM)));
		}
		return ChatFormat.lines(lines);
	}

	/**
	 * Non-zero tier counts ({@code "<label> <n>"}, the label in its tier colour), packed greedily into
	 * lines of at most {@link #TIER_LINE_CHARS} characters so the hover stays short.
	 */
	private static List<Component> tierLines(FightStats s, KwamsterConfig cfg) {
		List<Component> out = new ArrayList<>();
		if (s.quality == null) {
			return out;
		}
		List<Component> row = new ArrayList<>();
		int rowChars = 0;
		for (HitQuality q : HitQuality.values()) {
			int n = q.code() < s.quality.length ? s.quality[q.code()] : 0;
			if (n <= 0) {
				continue;
			}
			TierStyle t = cfg.tier(q);
			String label = tierLabel(t, q);
			String count = " " + n;
			int chars = label.length() + count.length();
			if (!row.isEmpty() && rowChars + ChatFormat.SEP.length() + chars > TIER_LINE_CHARS) {
				out.add(ChatFormat.joined(row));
				row = new ArrayList<>();
				rowChars = 0;
			}
			rowChars += (row.isEmpty() ? 0 : ChatFormat.SEP.length()) + chars;
			row.add(Component.empty().append(ChatFormat.text(label, t.argb)).append(ChatFormat.value(count)));
		}
		if (!row.isEmpty()) {
			out.add(ChatFormat.joined(row));
		}
		return out;
	}

	/** The tier's label, or its default label while the config holds a blank one (clampAndFill fixes it on save). */
	static String tierLabel(TierStyle t, @Nullable HitQuality q) {
		if (t.label != null && !t.label.isBlank()) {
			return t.label;
		}
		return q == null ? "HIT" : TierStyle.defaultFor(q).label;
	}

	/** Engaged opponents other than the primary one, names cut to 16 characters. */
	private static List<String> otherEngaged(FightRecord r) {
		List<String> out = new ArrayList<>();
		if (r.opponents == null) {
			return out;
		}
		for (int i = 0; i < r.opponents.size(); i++) {
			boolean engaged = r.engaged != null && i < r.engaged.size() && Boolean.TRUE.equals(r.engaged.get(i));
			String uuid = r.opponentUuids != null && i < r.opponentUuids.size() ? r.opponentUuids.get(i) : null;
			boolean primary = uuid != null ? uuid.equals(r.opponentUuid) : Objects.equals(r.opponents.get(i), r.opponentName);
			if (engaged && !primary) {
				out.add(ChatFormat.name(r.opponents.get(i)));
			}
		}
		return out;
	}

	/** A short phrase for the end reason. */
	static String reasonText(@Nullable String endReason) {
		FightEndReason reason = null;
		if (endReason != null) {
			for (FightEndReason r : FightEndReason.values()) {
				if (r.name().equals(endReason)) {
					reason = r;
					break;
				}
			}
		}
		if (reason == null) {
			return "Ended";
		}
		return switch (reason) {
			case IDLE -> "Ended: idle";
			case OPPONENT_DIED -> "Opponent died";
			case SELF_DIED -> "You died";
			case OPPONENT_LEFT -> "Opponent left";
			case OPPONENT_FAR -> "Opponent out of range";
			case DISCONNECT -> "Disconnected";
			case WORLD_CHANGE -> "World changed";
			case GAME_EXIT -> "Game closed";
			case DISABLED -> "KWAMSTER disabled";
		};
	}

	// ---- plumbing ------------------------------------------------------------

	/** The share link from RECORD (a pure computation, independent of saving); null on failure or an invalid base URL. */
	private static @Nullable ShareLink shareLink(FightRecord record) {
		try {
			return Kwamster.services().archive().shareLink(record);
		} catch (Throwable t) {
			LogLimiter.error("ChatReporter.shareLink", "Could not build the share link for the chat summary", t);
			return null;
		}
	}

	/** The local player when chat output is allowed at all (§8.2), else null. */
	private static @Nullable LocalPlayer chatPlayer(KwamsterConfig cfg) {
		if (!cfg.general.enabled
				|| cfg.display.mode == KwamsterConfig.DisplayMode.HUD_ONLY
				|| cfg.display.chatMode == KwamsterConfig.ChatMode.OFF) {
			return null;
		}
		Minecraft mc = Minecraft.getInstance();
		return mc == null ? null : mc.player;
	}

	static boolean perHit(KwamsterConfig.@Nullable ChatMode mode) {
		return mode == KwamsterConfig.ChatMode.PER_HIT || mode == KwamsterConfig.ChatMode.BOTH;
	}

	static boolean perFight(KwamsterConfig.@Nullable ChatMode mode) {
		return mode == KwamsterConfig.ChatMode.PER_FIGHT || mode == KwamsterConfig.ChatMode.BOTH;
	}

	private static void send(LocalPlayer player, Component line) {
		player.displayClientMessage(line, false);
	}
}
