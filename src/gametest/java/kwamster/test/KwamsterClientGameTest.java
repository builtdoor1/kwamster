package kwamster.test;

import kwamster.Kwamster;
import kwamster.core.Clock;
import kwamster.core.ScreenRegistry.ScreenId;
import kwamster.core.TestHooks;
import kwamster.core.config.HudElementId;
import kwamster.core.config.HudLayout;
import kwamster.core.config.KwamsterConfig;
import kwamster.core.event.FightEndedEvent;
import kwamster.core.event.KwamsterListener;
import kwamster.core.io.Json;
import kwamster.core.model.CrosshairMode;
import kwamster.core.model.FightRecord;
import kwamster.core.model.FightResult;
import kwamster.core.model.FightStats;
import kwamster.core.model.LiveFightView;
import kwamster.core.model.PlayerRef;
import kwamster.core.model.ShareLink;
import kwamster.core.model.StoplightReading;
import kwamster.core.model.StoplightState;
import kwamster.direction.HeadBarMotion;
import kwamster.direction.HeadBarTracker;
import kwamster.hud.HudLayoutMath;
import kwamster.hud.HudManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.CameraType;
import net.minecraft.client.gui.screens.Screen;
import org.lwjgl.glfw.GLFW;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * KWAMSTER's client gametest (DESIGN.md §12.3): drives a real 1.21.11 client through a synthetic fight
 * and takes screenshots of every HUD state, the crosshair modes, the fight card, the settings screen,
 * the HUD editor and the saved-fights screen into {@code build/run/clientGameTest/screenshots/}.
 *
 * <p>Gametests run on their own thread, so every call into KWAMSTER (test hooks, fights, config) is
 * wrapped in {@code ctx.runOnClient} or {@code ctx.computeOnClient}; {@link TestHooks} throws otherwise
 * (R2c). Remote players cannot be spawned here, so combat is injected through the hooks and travels the
 * real event bus. Failures throw {@link AssertionError}. Run with {@code ./gradlew runClientGameTest}.
 */
public final class KwamsterClientGameTest implements FabricClientGameTest {
	/** Ticks between injected combat events (200 ms of game time). */
	private static final int GAP_TICKS = 4;
	/** The default share budget ({@code recording.shareMaxChars}). */
	private static final int SHARE_BUDGET = 1900;

	/** The last FightEndedEvent seen on the bus (written on the client thread). */
	private final AtomicReference<FightEndedEvent> lastEnded = new AtomicReference<>();

	@Override
	public void runTest(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			mc.options.guiScale().set(2);
			mc.options.fov().set(70);
			Kwamster.events().register(new KwamsterListener() {
				@Override
				public void onFightEnded(FightEndedEvent e) {
					KwamsterClientGameTest.this.lastEnded.set(e);
				}
			});
		});

		try (TestSingleplayerContext world = ctx.worldBuilder().create()) {
			world.getClientWorld().waitForChunksRender();
			ctx.waitTicks(10);

			idle(ctx);
			PlayerRef opponent = fight(ctx);
			stoplight(ctx, opponent);
			headBar(ctx);
			crosshair(ctx);
			summaryCard(ctx);
			settings(ctx);
			hudEditor(ctx);
			savedFights(ctx);
			hideChecks(ctx);
			masterSwitch(ctx);
			guiScaleFour(ctx);
		}
	}

	// ---- 1. idle ------------------------------------------------------------------------------------

	private static void idle(ClientGameTestContext ctx) {
		int fights = ctx.computeOnClient(mc -> Kwamster.fights().session().fights);
		check(fights == 0, "no fight before the test starts");
		check(ctx.computeOnClient(mc -> mc.getWindow().getGuiScale()) == 2, "GUI scale 2");
		ctx.takeScreenshot("00_idle");
	}

	// ---- 2. a synthetic fight ---------------------------------------------------------------------------

	private PlayerRef fight(ClientGameTestContext ctx) {
		PlayerRef opponent = ctx.computeOnClient(mc -> Kwamster.testHooks().beginSyntheticFight("Kwamix"));
		check(ctx.computeOnClient(mc -> Kwamster.fights().inFight()), "the synthetic fight is running");
		injectSequence(ctx);
		ctx.takeScreenshot("01_fight_panel");

		FightStats stats = ctx.computeOnClient(mc -> {
			LiveFightView live = Kwamster.fights().current();
			return live == null ? null : live.stats(Clock.nanos());
		});
		check(stats != null, "a live fight view");
		check(stats.swings == 10, "10 swings, got " + stats.swings);
		check(stats.landed == 9, "9 landed, got " + stats.landed);
		check(stats.quality[0] == 1, "one 3-block crit, got " + stats.quality[0]);
		check(stats.currentCombo == 3, "combo 3 after the hit taken, got " + stats.currentCombo);
		check(stats.jumpAttempts == 2 && stats.jumpSuccess == 1, "jump resets 1/2, got " + stats.jumpSuccess + "/" + stats.jumpAttempts);
		int sessionFights = ctx.computeOnClient(mc -> Kwamster.fights().session().fights);
		check(sessionFights == 0, "the session counts only finished fights");

		// One more 3-block crit (combo 4): the indicator pops in and the combo counter shows.
		ctx.runOnClient(mc -> Kwamster.testHooks().swing(true, 2.98, true, true, false, 0.1, 0.5));
		ctx.waitTick();
		ctx.takeScreenshot("02_indicator_combo");
		return opponent;
	}

	/** §12.3 step 2: six tiers, a miss, a hit taken, two jump resets and three more landed hits. */
	private static void injectSequence(ClientGameTestContext ctx) {
		// landed, reach, charged, crit, sprint, aimH, aimV
		hook(ctx, h -> h.swing(true, 2.97, true, true, false, 0.05, 0.45));    // THREE_BLOCK_CRIT
		hook(ctx, h -> h.swing(true, 2.96, true, false, true, -0.2, 0.1));     // THREE_BLOCK_SPRINT
		hook(ctx, h -> h.swing(true, 2.40, true, true, false, 0.6, 0.0));      // CRIT
		hook(ctx, h -> h.swing(true, 2.80, true, false, true, 0.1, -0.5));     // SPRINT
		hook(ctx, h -> h.swing(true, 2.50, true, false, false, 0.0, 0.0));     // NORMAL
		hook(ctx, h -> h.swing(true, 2.00, false, false, false, -0.3, 0.2));   // WEAK
		hook(ctx, h -> h.swing(false, 3.40, true, false, false, 1.4, 0.3));    // miss
		hook(ctx, h -> h.hitTaken(2.9, true));
		hook(ctx, h -> h.jumpReset(1));                                         // success, tick 1
		hook(ctx, h -> h.jumpReset(4));                                         // late
		hook(ctx, h -> h.swing(true, 2.70, true, false, false, 0.2, 0.1));
		hook(ctx, h -> h.swing(true, 2.65, true, false, true, -0.1, 0.0));
		hook(ctx, h -> h.swing(true, 2.85, true, false, false, 0.0, 0.2));
	}

	/** Runs one test-hook call on the client thread, then waits {@link #GAP_TICKS}. */
	private static void hook(ClientGameTestContext ctx, Consumer<TestHooks> call) {
		ctx.runOnClient(mc -> call.accept(Kwamster.testHooks()));
		ctx.waitTicks(GAP_TICKS);
	}

	// ---- 3. stoplight ------------------------------------------------------------------------------------

	private static void stoplight(ClientGameTestContext ctx, PlayerRef opponent) {
		// The HUD stoplight is opt-in since the above-head bars took over (§5.11a).
		ctx.runOnClient(mc -> Kwamster.config().direction.stoplightEnabled = true);
		double[] speeds = {3.1, 0.2, -2.4};
		StoplightState[] states = {StoplightState.GREEN, StoplightState.YELLOW, StoplightState.RED};
		for (int i = 0; i < states.length; i++) {
			StoplightState state = states[i];
			double speed = speeds[i];
			ctx.runOnClient(mc -> Kwamster.testHooks().setStoplightOverride(
					new StoplightReading(state, speed, false, opponent, Clock.nanos())));
			ctx.waitTicks(3);
			StoplightState shown = ctx.computeOnClient(mc -> Kwamster.services().stoplight().reading().state());
			check(shown == state, "the stoplight override is served: " + shown);
			ctx.takeScreenshot("03_stoplight_" + state.name().toLowerCase(Locale.ROOT));
		}
		ctx.runOnClient(mc -> Kwamster.testHooks().setStoplightOverride(StoplightReading.NONE));
		ctx.waitTicks(3);
		ctx.takeScreenshot("03_stoplight_off");
		ctx.runOnClient(mc -> {
			Kwamster.testHooks().setStoplightOverride(null);
			Kwamster.config().direction.stoplightEnabled = false;
		});
	}

	// ---- 3b. above-head direction bar ---------------------------------------------------------------------

	/**
	 * §5.11a: no remote players exist here, so the bar is checked on yourself ("Show above yourself" plus a
	 * third-person camera) while walking forward, backpedalling and standing still with real key input.
	 */
	private static void headBar(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> {
			Kwamster.config().direction.headBarForSelf = true;
			mc.options.setCameraType(CameraType.THIRD_PERSON_BACK);
		});
		ctx.waitTicks(5);
		check(ctx.computeOnClient(mc -> HeadBarTracker.get() != null), "the head bar tracker is registered");

		ctx.getInput().holdKey(options -> options.keyUp);
		ctx.waitTicks(10);
		HeadBarMotion.Heading forward = selfHeading(ctx);
		check(forward == HeadBarMotion.Heading.FORWARD, "walking forward reads forward, got " + forward);
		ctx.takeScreenshot("03_headbar_forward");
		ctx.getInput().releaseKey(options -> options.keyUp);

		ctx.getInput().holdKey(options -> options.keyDown);
		ctx.waitTicks(12);
		HeadBarMotion.Heading backward = selfHeading(ctx);
		check(backward == HeadBarMotion.Heading.BACKWARD, "backpedalling reads backward, got " + backward);
		ctx.takeScreenshot("03_headbar_backward");
		ctx.getInput().releaseKey(options -> options.keyDown);

		ctx.waitTicks(20);
		HeadBarMotion.Heading idle = selfHeading(ctx);
		check(idle == HeadBarMotion.Heading.IDLE, "standing still reads idle, got " + idle);
		ctx.takeScreenshot("03_headbar_idle");

		ctx.runOnClient(mc -> {
			Kwamster.config().direction.headBarForSelf = false;
			mc.options.setCameraType(CameraType.FIRST_PERSON);
		});
		ctx.waitTicks(2);
	}

	private static HeadBarMotion.Heading selfHeading(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> {
			HeadBarTracker tracker = HeadBarTracker.get();
			return tracker == null || mc.player == null ? null : tracker.heading(mc.player, Kwamster.config().direction);
		});
	}

	// ---- 4. crosshair ------------------------------------------------------------------------------------

	private static void crosshair(ClientGameTestContext ctx) {
		for (CrosshairMode mode : CrosshairMode.values()) {
			ctx.runOnClient(mc -> Kwamster.testHooks().setCrosshairOverride(mode));
			ctx.waitTicks(2);
			ctx.takeScreenshot("04_crosshair_" + mode.name().toLowerCase(Locale.ROOT));
		}
		ctx.runOnClient(mc -> {
			Kwamster.testHooks().setCrosshairOverride(null);
			Kwamster.config().crosshair.enabled = false;
		});
		ctx.waitTicks(2);
		ctx.takeScreenshot("04_crosshair_vanilla");
		ctx.runOnClient(mc -> Kwamster.config().crosshair.enabled = true);
	}

	// ---- 5. fight end and the card -------------------------------------------------------------------

	private void summaryCard(ClientGameTestContext ctx) {
		this.lastEnded.set(null);
		ctx.runOnClient(mc -> Kwamster.testHooks().endSyntheticFight(FightResult.WIN));
		ctx.waitTicks(2);
		ctx.takeScreenshot("05_summary_card");

		FightRecord record = ctx.computeOnClient(mc -> Kwamster.fights().lastRecord());
		check(record != null, "a finished record");
		check("WIN".equals(record.result), "result WIN, got " + record.result);
		FightEndedEvent ended = this.lastEnded.get();
		check(ended != null, "a FightEndedEvent was posted");
		check(ended.alert(), "a decisive fight always alerts");
		check(ctx.computeOnClient(mc -> Kwamster.fights().session().fights) == 1, "the alerted fight joined the session");

		Path folder = ctx.computeOnClient(mc -> Kwamster.services().archive().folder());
		ctx.waitFor(mc -> hasFightFile(folder), 100);
		check(hasFightFile(folder), "a fight file under " + folder);

		ShareLink link = ctx.computeOnClient(mc -> Kwamster.services().archive().shareLink(record));
		check(link != null, "a share link");
		check(link.length() <= SHARE_BUDGET, "link fits the budget: " + link.length());
		Path out = FabricLoader.getInstance().getGameDir().resolve("kwamster-last-link.txt");
		try {
			Files.writeString(out, link.url(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	private static boolean hasFightFile(Path folder) {
		if (folder == null || !Files.isDirectory(folder)) {
			return false;
		}
		try (Stream<Path> files = Files.list(folder)) {
			return files.anyMatch(p -> {
				String name = p.getFileName().toString();
				return name.endsWith(".json") && !name.equals("index.json");
			});
		} catch (IOException e) {
			return false;
		}
	}

	// ---- 6. settings ------------------------------------------------------------------------------------

	private static void settings(ClientGameTestContext ctx) {
		// Tabs are not clicked: Cloth's tab buttons are AbstractButtons, which clickScreenButton does not
		// match (ClientGameTestContextImpl.pressMatchingButton). The registry argument picks the category.
		openScreen(ctx, ScreenId.SETTINGS, null);
		ctx.takeScreenshot("06_settings");
		openScreen(ctx, ScreenId.SETTINGS, "Hit feedback");
		ctx.takeScreenshot("06_settings_feedback");
		openScreen(ctx, ScreenId.SETTINGS, "Crosshair");
		ctx.takeScreenshot("06_settings_crosshair");
		ctx.setScreen(() -> null);
		ctx.waitTicks(2);
	}

	private static void openScreen(ClientGameTestContext ctx, ScreenId id, String arg) {
		ctx.setScreen(() -> Kwamster.services().screens().create(id, null, arg));
		ctx.waitTicks(3);
		check(ctx.computeOnClient(mc -> mc.screen != null), id + " screen opened");
	}

	// ---- 7. HUD editor ------------------------------------------------------------------------------------

	private static void hudEditor(ClientGameTestContext ctx) {
		HudLayout before = ctx.computeOnClient(mc -> Kwamster.config().layout(HudElementId.STATS_PANEL).copy());
		openScreen(ctx, ScreenId.HUD_EDITOR, null);
		ctx.takeScreenshot("07_hud_editor");

		dragPanel(ctx, 60, 40);
		ctx.clickScreenButton("Done");
		ctx.waitTicks(2);
		HudLayout moved = ctx.computeOnClient(mc -> Kwamster.config().layout(HudElementId.STATS_PANEL).copy());
		check(!moved.equals(before), "the drag moved the panel: " + before + " → " + moved);
		HudLayout saved = savedLayout(ctx, HudElementId.STATS_PANEL);
		check(moved.equals(saved), "Done saved the layout: memory " + moved + ", file " + saved);

		// Drag again and cancel: the layout goes back to the saved one.
		openScreen(ctx, ScreenId.HUD_EDITOR, null);
		dragPanel(ctx, 30, 25);
		HudLayout dragged = ctx.computeOnClient(mc -> Kwamster.config().layout(HudElementId.STATS_PANEL).copy());
		check(!dragged.equals(moved), "the second drag moved the panel");
		ctx.clickScreenButton("Cancel");
		ctx.waitTicks(2);
		HudLayout restored = ctx.computeOnClient(mc -> Kwamster.config().layout(HudElementId.STATS_PANEL).copy());
		check(restored.equals(moved), "Cancel restored " + moved + ", got " + restored);
		check(savedLayout(ctx, HudElementId.STATS_PANEL).equals(moved), "Cancel wrote nothing");

		// Put the default back for the remaining screenshots.
		ctx.runOnClient(mc -> {
			Kwamster.config().layouts.put(HudElementId.STATS_PANEL.name(), HudLayout.defaultFor(HudElementId.STATS_PANEL));
			Kwamster.saveConfig();
		});
	}

	/** Drags the stats panel by (dx, dy) GUI pixels with real mouse input (press, move, release). */
	private static void dragPanel(ClientGameTestContext ctx, int dx, int dy) {
		double[] start = ctx.computeOnClient(mc -> {
			Screen screen = mc.screen;
			check(screen != null, "the editor is open");
			HudLayoutMath.Rect r = HudManager.rectOf(HudManager.get().element(HudElementId.STATS_PANEL),
					Kwamster.config(), true, screen.width, screen.height);
			double sx = (double) mc.getWindow().getScreenWidth() / mc.getWindow().getGuiScaledWidth();
			double sy = (double) mc.getWindow().getScreenHeight() / mc.getWindow().getGuiScaledHeight();
			// Grab near the panel's bottom-left corner: clear of the card preview (drawn later, so it would
			// win the hit test where they overlap) and of the centre elements.
			return new double[] {(r.x() + 4) * sx, (r.bottom() - 6) * sy, sx, sy};
		});
		double x = start[0];
		double y = start[1];
		// The first cursor event after a grab or screen change is swallowed (MouseHandler.ignoreFirstMove).
		ctx.getInput().setCursorPos(x, y);
		ctx.waitTick();
		ctx.getInput().setCursorPos(x, y);
		ctx.waitTick();
		ctx.getInput().holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTick();
		int steps = 4;
		for (int i = 1; i <= steps; i++) {
			ctx.getInput().setCursorPos(x + dx * start[2] * i / steps, y + dy * start[3] * i / steps);
			ctx.waitTick();
		}
		ctx.getInput().releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT);
		ctx.waitTicks(2);
	}

	private static HudLayout savedLayout(ClientGameTestContext ctx, HudElementId id) {
		Path file = ctx.computeOnClient(mc -> Kwamster.dataDir().resolve("config.json"));
		try {
			KwamsterConfig onDisk = Json.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), KwamsterConfig.class);
			onDisk.clampAndFill();
			return onDisk.layout(id);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	// ---- 8. saved fights ------------------------------------------------------------------------------

	private static void savedFights(ClientGameTestContext ctx) {
		int rows = ctx.computeOnClient(mc -> Kwamster.services().archive().list().size());
		check(rows == 1, "one saved fight, got " + rows);
		openScreen(ctx, ScreenId.SAVED_FIGHTS, null);
		ctx.takeScreenshot("08_saved_fights");
		ctx.setScreen(() -> null);
		ctx.waitTicks(3);
	}

	// ---- 9. F1 / F3 hide checks (render counter, no pixel comparison) ------------------------------

	private static void hideChecks(ClientGameTestContext ctx) {
		int drawing = hudDraws(ctx);
		ctx.waitTicks(3);
		check(hudDraws(ctx) > drawing, "the HUD draws while visible (panel shows the last fight)");

		ctx.runOnClient(mc -> mc.options.hideGui = true);
		ctx.waitTick();
		int hidden = hudDraws(ctx);
		ctx.waitTicks(3);
		check(hudDraws(ctx) == hidden, "F1 hides the HUD");
		ctx.takeScreenshot("09_f1");
		ctx.runOnClient(mc -> mc.options.hideGui = false);

		ctx.runOnClient(mc -> {
			Kwamster.config().general.hideInDebug = true;
			mc.debugEntries.setOverlayVisible(true);
		});
		ctx.waitTick();
		int debug = hudDraws(ctx);
		ctx.waitTicks(3);
		check(hudDraws(ctx) == debug, "F3 hides the HUD with hideInDebug on");
		ctx.takeScreenshot("10_f3");
		ctx.runOnClient(mc -> mc.debugEntries.setOverlayVisible(false));
		ctx.waitTick();
		int shown = hudDraws(ctx);
		ctx.waitTicks(3);
		check(hudDraws(ctx) > shown, "the HUD draws again after F3");
	}

	private static int hudDraws(ClientGameTestContext ctx) {
		return ctx.computeOnClient(mc -> Kwamster.testHooks().hudDraws());
	}

	// ---- 10. master switch --------------------------------------------------------------------------

	private static void masterSwitch(ClientGameTestContext ctx) {
		ctx.runOnClient(mc -> Kwamster.config().general.enabled = false);
		ctx.waitTick();
		int off = hudDraws(ctx);
		ctx.waitTicks(3);
		check(hudDraws(ctx) == off, "nothing draws with the master switch off");
		ctx.takeScreenshot("11_disabled");
		ctx.runOnClient(mc -> Kwamster.config().general.enabled = true);
		ctx.waitTicks(2);
	}

	// ---- 11. GUI scale 4 ---------------------------------------------------------------------------------

	private void guiScaleFour(ClientGameTestContext ctx) {
		ctx.getInput().resizeWindow(1920, 1080);
		ctx.runOnClient(mc -> mc.options.guiScale().set(4));
		ctx.waitTicks(3);
		int scale = ctx.computeOnClient(mc -> mc.getWindow().getGuiScale());
		check(scale == 4, "GUI scale 4 at 1920×1080, got " + scale);

		ctx.runOnClient(mc -> Kwamster.testHooks().beginSyntheticFight("Kwamix"));
		injectSequence(ctx);
		ctx.takeScreenshot("12_gui4_fight");
		this.lastEnded.set(null);
		ctx.runOnClient(mc -> Kwamster.testHooks().endSyntheticFight(FightResult.WIN));
		ctx.waitTicks(2);
		ctx.takeScreenshot("13_gui4_card");
		check(this.lastEnded.get() != null && this.lastEnded.get().alert(), "the second fight ended with an alert");

		ctx.runOnClient(mc -> mc.options.guiScale().set(2));
		ctx.getInput().resizeWindow(854, 480);
		ctx.waitTicks(2);
	}

	// ---- helpers ----------------------------------------------------------------------------------------

	private static void check(boolean condition, String message) {
		if (!condition) {
			throw new AssertionError("KWAMSTER gametest: " + message);
		}
	}
}
