# KWAMSTER: design contract

Version 1.1.0 · Minecraft 1.21.11 (Mojang names, `ResourceLocation` is `Identifier`) · Fabric Loader 0.19.3 · Fabric API 0.141.4+1.21.11 · Cloth Config 21.11.153 (required) · Mod Menu 17.0.0 (suggested) · Java 21 · mod id `kwamster` · display name **KWAMSTER** · author **builtdoor** · MIT · client only · jar `kwamster-<modver>+1.21.11.jar`.

This document is the implementation contract (revision 3: every review-round-1 issue is folded in, dispositions in Appendix A; the review-round-2 fixes are folded in too, listed in Appendix B). Nine units are written in parallel: CORE first, then CAPTURE, HUD, CROSSHAIR, FEEDBACK, DIRECTION, RECORD, CONFIGUI and TEST. **Units interact only through CORE types.** If a signature or constant appears here, implement it exactly as written. If a detail is missing, choose the most conservative option and stay inside your own unit's files.

## 0. Ground rules for every unit

| # | Rule |
|---|---|
| R1 | Root package `kwamster`, source root `src/client/java`. A non-CORE unit may import `kwamster`, `kwamster.core.**`, Minecraft, Fabric API, MixinExtras, Gson, and (CONFIGUI only) Cloth Config and Mod Menu. It never imports another non-CORE unit's package. The one exception: mixin classes in `kwamster.mixin` call into the unit that owns them (CAPTURE mixins call `kwamster.capture`, `GuiCrosshairMixin` calls `kwamster.crosshair`). |
| R2 | All KWAMSTER logic runs on the client (render) thread, with exactly these exceptions (each verified in §6): **(a)** `ClientPlayConnectionEvents.DISCONNECT` normally fires on a Netty IO thread (Fabric invokes it from `Connection.channelInactive`, fabric/…/mixin/networking/ConnectionMixin.java:79–84, first caller wins through `AbstractNetworkAddon.handleDisconnect`'s compareAndSet, 156–161). Its callback may only write an atomic (`FightManager.markDisconnectPending()`); everything else happens on the next client tick (§5.13). **(b)** `LivingEntityMixin` targets also run on the integrated-server thread in singleplayer (`ServerPlayer.jumpFromGround` calls `super`, server/level/ServerPlayer.java:1514–1515, and every mob jump reaches `LivingEntity.jumpFromGround`). Each of its handlers starts with `if (!((LivingEntity)(Object)this).level().isClientSide()) return;`, before the R4 check, before any `Kwamster.*` access and before the R3 logger. **(c)** Client gametests run on the gametest thread; every `Kwamster.testHooks()` call is made inside `ctx.runOnClient(...)` and `TestHooks` asserts `Minecraft.getInstance().isSameThread()`. **(d)** RECORD's single `KWAMSTER-IO` executor receives only immutable `(Path, String)` writes and `Path` deletions that were serialised on the client thread (§10); it never touches game state or live objects. |
| R3 | No hook may throw into vanilla. Every mixin body delegates to a static method that wraps its work in `try { … } catch (Throwable t) { KwamsterClient.LOG.error(…) }`, logging at most once per 10 s per call site. |
| R4 | When `Kwamster.config().general.enabled == false`, every hook returns immediately, the HUD draws nothing, the crosshair falls back to vanilla, and no sound, chat line or event is produced. The game is pure vanilla. The single exception: a fight that is running at the moment the switch is turned off is finalised once with reason DISABLED on the next tick and saved silently (no sound, chat or card, because those consumers check `enabled`). No file is written after that. |
| R5 | All user-facing text is `Component.literal(...)`. The only exceptions are the keybind names and the keybind category, which must be translation keys (CORE owns `en_us.json`). |
| R6 | **Minecraft-free packages.** These may not import `net.minecraft.*`, `com.mojang.*`, `net.fabricmc.*` or Cloth: `kwamster.core.model`, `kwamster.core.stats`, `kwamster.core.fight.FightStateMachine`, `kwamster.core.fight.FightAccumulator`, `kwamster.core.fight.FightClock`, `kwamster.core.fight.OpponentPresence` (`kwamster.core.fight.PlayerRefs` and `FightManager` are MC glue and exempt), the event records in `kwamster.core.event` (all except `KwamsterListener` and `EventBus`), `kwamster.core.config.KwamsterConfig` and its satellite types (all except `ConfigStore`; `HudPreset` included), `kwamster.capture.AimMath`, `kwamster.capture.OutcomeText`, `kwamster.capture.JumpResetWatch`, `kwamster.capture.NoRegEstimator`, `kwamster.capture.RecentDeaths`, `kwamster.capture.SourcelessDamage`, `kwamster.configui.EntryChecks`, `kwamster.configui.ColorMath`, `kwamster.hud.HudLayoutMath`, `kwamster.hud.Anim`, `kwamster.hud.AsciiWidths`, `kwamster.hud.PreviewStrings`, `kwamster.crosshair.CrosshairShapes`, `kwamster.direction.RadialFilter`, `kwamster.direction.HeadBarMotion`, `kwamster.record.SharePayloadCodec` and `kwamster.record.ShareLinks`. TEST unit-tests these, plus the in-memory parts of `kwamster.record.FightIndex` (`parse`, `prune`), whose code paths load no Minecraft class. |
| R7 | Code style follows the scaffold: tabs, `final` classes, Javadoc on every public type, no wildcard imports. The logger is `KwamsterClient.LOG` (SLF4J name "KWAMSTER"). |
| R8 | No telemetry, no sockets, no HTTP, no `URLConnection`. Nothing is ported from combat-tracker's IntegrityMonitor, WebhookNotifier, InputContext, provenance or relay. The only things that leave the process are share links, and only when the user clicks a button: copied to the clipboard, or opened with the OS browser through `Util.getPlatform().openUri`. |
| R9 | Times: event timestamps are `long` nanoseconds from `Clock.nanos()` (`System.nanoTime()`). Persisted times are `int` ms relative to fight start on the **fight clock** (wall time minus singleplayer pause time, §5.13), plus one epoch-ms anchor per fight. Jump-reset pairing uses the **client tick index** `Clock.ticks()` instead of wall time (§5.1). |

---

## 1. Overview and data flow

KWAMSTER merges three earlier mods into one PvP client mod:

- From Combat Report (`combat-report/src/client/java/combat_report/combat/*`): the strict capture model. Hits are attributed to a named attacker, hit types are copied verbatim from vanilla, all five `startAttack` guards are re-applied, fights have explicit boundaries, and trades and combos are tracked in both directions.
- From Combat Tracker: aim placement, frame-accurate click timing, the HUD and drag editor, the Cloth button entry, and serverless share links.
- From Direction Indicator: an opponent-movement classifier, rebuilt as an on-screen stoplight.

On top of these it adds hit-quality grading with a sound and colour per tier, a crit-aware crosshair, a fight summary card, per-fight records, and a static web viewer with a replay.

```
              CLIENT THREAD (exceptions: R2 — DISCONNECT only sets a flag)
 ┌──────────────────────────────────────────────────────────────────────────────┐
 │  CAPTURE mixins                                                              │
 │   MouseHandler.onButton / KeyboardHandler.keyPress (attack key only)         │
 │                     ──────────► ClickTimestamps (click nanos)                │
 │   Minecraft.startAttack HEAD ─► CombatCapture.onStartAttackHead  (guards,    │
 │   MultiPlayerGameMode.attack ─►   aim, reach, classification)                │
 │     HEAD (non-spectator)          .onGameModeAttack (landed)                 │
 │   Minecraft.startAttack RETURN ►  .onStartAttackReturn  ──► SwingEvent       │
 │   LivingEntity.handleDamageEvent ► HitTakenEvent / HitConfirmedEvent         │
 │   LivingEntity.jumpFromGround ───► JumpEvent ─► JumpResetWatch ─► JumpReset  │
 │   LivingEntity.handleEntityEvent(3) ► DeathEvent                             │
 │   per tick: HealthWatch ─► DamageObservedEvent; TradeWatch ─► TradeEvent     │
 │                  │                                                           │
 │                  ▼                                                           │
 │   EventBus (CORE). Synchronous; re-entrant posts are queued FIFO.            │
 │                  │                                                           │
 │                  ▼  first listener, always                                   │
 │   FightManager (CORE) = FightStateMachine + FightAccumulator + HitGrader     │
 │     │   emits FightStarted / HitResolved / FightEnded / WorldLeave events   │
 │     │   serves Kwamster.fights() (FightService, LiveFightView, session)      │
 │     ▼                                                                        │
 │  ┌──────────┬───────────────┬──────────────┬──────────────┬──────────────┐   │
 │  │ RECORD   │ FEEDBACK      │ HUD          │ CROSSHAIR    │ DIRECTION    │   │
 │  │ saves    │ tier sounds   │ panel, hit   │ Gui.render-  │ opponent     │   │
 │  │ JSON,    │ per-hit and   │ indicator,   │ Crosshair    │ radial speed │   │
 │  │ index,   │ per-fight     │ combo, card, │ wrap; reads  │ → Stoplight- │   │
 │  │ lifetime │ chat with     │ stoplight,   │ fights() and │ Source       │   │
 │  │ share    │ [Copy] [Open] │ HUD editor   │ config       │ (Services)   │   │
 │  │ codec,   │      ▲        │      ▲       │              │      │       │   │
 │  │ saved-   │      └ Services.archive().shareLink(..)      │      │       │   │
 │  │ fights   │               └ Services.stoplight() ◄────────────────┘       │
 │  │ screen   │                                                               │
 │  └──────────┘                                                               │
 │  CONFIGUI: Cloth screen ⇄ KwamsterConfig (CORE) ⇄ ConfigStore → config.json │
 │  Screens are opened only through Services.screens() (ScreenRegistry, CORE).  │
 └──────────────────────────────────────────────────────────────────────────────┘
        share link ──► https://builtdoor1.github.io/kwamster/#<base64url(deflate(payload v1))>
                       docs/index.html decodes in the browser; nothing is uploaded
```

Lifecycle of one swing:
1. A press of whatever input is bound to Attack timestamps a click.
2. At `startAttack` HEAD the guards are re-applied, the target is resolved, and reach, aim and hit flags are captured. The swing's time is this hook time; the click lead (hook − click) is kept separately for S8.
3. If vanilla reaches `MultiPlayerGameMode.attack` as a non-spectator, the swing is marked landed, reclassified, and flagged NO-REG if the target is still in hurt immunity.
4. At `startAttack` RETURN a `SwingEvent` is posted.
5. FightManager opens or extends the fight, grades the hit and appends it to the record, then posts a `HitResolvedEvent`.
6. FEEDBACK plays the tier sound, the HUD flashes the indicator, and the crosshair flashes the tier colour (none of these for a NO-REG hit).

The fight ends after idle time, a death, the opponent leaving or moving out of range, a disconnect or a world change. FightManager then finalises a `FightRecord` and `FightStats` on the client tick and posts `FightEndedEvent`. RECORD saves it, FEEDBACK prints the chat summary with the share link, and the HUD shows the card.

---

## 2. Package layout: every file and its owning unit

Paths are relative to `src/client/java/kwamster/` unless they start with `src/`, `docs/` or a project-root file name. Each file belongs to exactly one unit. ★ marks a Minecraft-free file (R6).

### CORE

| File | Purpose |
|---|---|
| `KwamsterClient.java` | `ClientModInitializer`. Runs init in the order given in §2.1, wires Fabric events, holds `LOG` and `MOD_ID`. Replaces the scaffold. |
| `Kwamster.java` | Static facade: `config()`, `saveConfig()`, `events()`, `fights()`, `services()`, `testHooks()`, `modVersion()`, `dataDir()`, and `withPreviewConfig(cfg, draw)`, which makes `config()` return `cfg` on the render thread while `draw` runs (the HUD presets preview, §7.10). |
| `core/KwamsterModule.java` | The entrypoint interface each unit implements (`"kwamster"` entrypoint). |
| `core/Services.java` | Holder of service implementations. Each slot has a CORE no-op default. |
| `core/ScreenRegistry.java` | `ScreenId` → factory (with an optional string argument). Every unit opens screens only through this. |
| `core/Keybinds.java` | Registers the KWAMSTER key category and keys, and handles presses (§11). |
| `core/Clock.java` | `nanos()`, `epochMs()` and `ticks()` (client tick index, incremented at `START_CLIENT_TICK`), overridable in tests. |
| `core/TestHooks.java` | Synthetic event injection, render overrides and a HUD draw counter for the gametest. Asserts it is called on the client thread. |
| `core/service/FightService.java` | Read API over live and finished fights and session totals. |
| `core/service/StoplightSource.java` | Implemented by DIRECTION. |
| `core/service/FightArchive.java` | Implemented by RECORD. |
| `core/service/SoundPreview.java` | Implemented by FEEDBACK. |
| `core/service/CaptureControl.java` | Implemented by CAPTURE: flushes pending trades before a fight is finalised. |
| `core/config/KwamsterConfig.java` ★ | Config POJO plus nested section classes and enums, `clampAndFill()`. |
| `core/config/ConfigStore.java` | Load and save `config/kwamster/config.json` (atomic, broken-file backup). |
| `core/config/Anchor.java` ★ | Nine HUD anchors. |
| `core/config/HudElementId.java` ★ | HUD element ids. |
| `core/config/HudLayout.java` ★ | Anchor, offset and scale for one element, plus defaults. |
| `core/config/TierStyle.java` ★ | Colour, sound and toggles for one hit-quality tier, plus defaults. |
| `core/config/HudPreset.java` ★ | The eight HUD looks (§7.10): each writes the same fixed set of look fields; `apply`, `matches`, `matching`, `sample`, `fromName`. |
| `core/event/KwamsterListener.java` | Listener interface, default no-op per event. |
| `core/event/EventBus.java` | Synchronous dispatcher with FIFO re-entrancy queue. |
| `core/event/SwingEvent.java` ★ | Capture event. |
| `core/event/HitTakenEvent.java` ★ | Capture event. |
| `core/event/JumpEvent.java` ★ | Capture event. |
| `core/event/JumpResetEvent.java` ★ | Capture event (decided attempt). |
| `core/event/DeathEvent.java` ★ | Capture event. |
| `core/event/HitConfirmedEvent.java` ★ | Capture event (server damage event on your target). |
| `core/event/DamageObservedEvent.java` ★ | Capture event (health drop). |
| `core/event/TradeEvent.java` ★ | Capture event. |
| `core/event/PiercingSwingEvent.java` ★ | Capture event (spear swing; counted, excluded). |
| `core/event/OutcomeHintEvent.java` ★ | Capture event: a server kill or win message about the running fight (§5.13a). |
| `core/event/WorldLeaveEvent.java` ★ | Disconnect, or level/local-player change. Posted only by CORE's FightManager on the client tick (§5.13). |
| `core/event/FightStartedEvent.java` ★ | CORE event. |
| `core/event/HitResolvedEvent.java` ★ | CORE event (swing graded). |
| `core/event/FightEndedEvent.java` ★ | CORE event. |
| `core/model/PlayerRef.java` ★ | `record PlayerRef(UUID uuid, String name)`. |
| `core/model/Pos.java` ★ | `record Pos(double x, double y, double z)`. |
| `core/model/AimSample.java` ★ | Normalised aim offsets plus zone. |
| `core/model/HitQuality.java` ★ | Seven tiers. |
| `core/model/AimZone.java` ★ | CENTER, EDGE, HIGH, LOW. |
| `core/model/StoplightState.java` ★ | OFF, GREEN, YELLOW, RED. |
| `core/model/StoplightReading.java` ★ | State, speed, target, hold flag. |
| `core/model/CrosshairMode.java` ★ | DEFAULT, ON_PLAYER, CRIT_READY, CRIT_READY_ON_PLAYER. |
| `core/model/FightResult.java` ★ | WIN, LOSS, DRAW. |
| `core/model/FightEndReason.java` ★ | Why the fight ended, with a stable code. |
| `core/model/FightRecord.java` ★ | Gson POJO of a whole fight. |
| `core/model/FightStats.java` ★ | Derived numbers (Gson POJO). |
| `core/model/StatsTotals.java` ★ | Additive totals for session and lifetime. |
| `core/model/FightSummaryRow.java` ★ | One row of the saved-fights index. |
| `core/model/ShareLink.java` ★ | Built URL plus size and downsampling info. |
| `core/model/LiveFightView.java` ★ | Read-only view of the running fight. |
| `core/stats/HitGrader.java` ★ | Charge, crit, sprint and reach → `HitQuality`. |
| `core/stats/AimZones.java` ★ | (h, v) → `AimZone` (precedence rule). |
| `core/stats/AdvantageModel.java` ★ | Advantage formula. |
| `core/stats/StatsCalculator.java` ★ | `FightRecord` → `FightStats` (single source of truth for every number). |
| `core/stats/StatFormat.java` ★ | Number formatting shared by HUD, chat, card and screens. |
| `core/fight/FightStateMachine.java` ★ | Fight start and end rules. |
| `core/fight/OpponentPresence.java` ★ | `record OpponentPresence(...)` and `record SelfState(...)`. |
| `core/fight/FightAccumulator.java` ★ | Builds the `FightRecord` incrementally. |
| `core/fight/FightClock.java` ★ | Pause arithmetic for the fight clock: `reset()`, `pause(long now)`, `resume(long now)`, `long toFight(long nanos)` (§5.13). |
| `core/fight/FightManager.java` | Minecraft glue: listener, presence probing, ping sampling, disconnect/world-change detection, fight clock, implements `FightService`. |
| `core/fight/PlayerRefs.java` | MC glue shared by CORE and CAPTURE: `PlayerRef of(Player)` (formatting stripped as in combat-report `FightWatcher.stripFormatting`, trimmed), `Pos of(Vec3)`, and `boolean isNpc(Player)` (§5.4). |
| `core/io/AtomicFiles.java` | tmp + `ATOMIC_MOVE` writes, broken-file backup. |
| `core/io/Json.java` | Shared Gson instances (`GSON` compact, `PRETTY`). Neither enables `serializeSpecialFloatingPointValues`, so Gson throws on NaN/Infinity: every float stored in a record is sanitised first (§4.9). |
| `src/main/resources/fabric.mod.json` | Updated: adds the `kwamster` entrypoint list and version ranges (§2.2). |
| `src/client/resources/kwamster.client.mixins.json` | Lists every mixin (§2.3). |
| `src/main/resources/assets/kwamster/lang/en_us.json` | Keybind and category names (§11). |

### CAPTURE

| File | Purpose |
|---|---|
| `capture/CaptureModule.java` | `KwamsterModule` (order 100). Registers the `CaptureControl` service and a listener for `onClientTick`, `onWorldLeave` and `onFightEnded`. Also registers `ClientTickEvents.START_CLIENT_TICK` (`CombatCapture::onClientTickStart`, the deferred sourceless-damage check, §6.3); modules init after CORE registers its `Clock` listener, so CAPTURE's runs second. Registers `ClientReceiveMessageEvents.GAME` → `OutcomeWatch` (§5.13a). |
| `capture/CombatCapture.java` | Static entry points called by the five CAPTURE mixins. Owns the open swing. Ports `FightWatcher.onSwingBegin`, `onLandedHit`, `onSwingCommit`, `onDamage` and `onJump`, without the recording gate. |
| `capture/SwingCapture.java` | Port of combat-report `SwingCapture` (target resolution, `reachTo`), extended with ray reach and aim. Skips spectators. |
| `capture/HitClassifier.java` | Port of combat-report `HitClassifier`: verbatim `charge`, `canCriticalAttack` and `isSweepAttack`. Returns `HitFlags`. |
| `capture/AimMath.java` ★ | Hit point → normalised (h, v) and `AimZone`, using doubles only (§5.3). |
| `capture/ClickTimestamps.java` | Port of combat-tracker `ClickTimestamps` (single slot, 200 ms max age). |
| `capture/JumpResetWatch.java` ★ | Pairing of jumps and jump-key presses with hits, with a live decision (§5.1). |
| `capture/NoRegEstimator.java` ★ | NO-REG decision from your own send ticks plus a lagged client-copy rule for other damage (§5.4). |
| `capture/RecentDeaths.java` ★ | Bodies of players whose entity event 3 was seen in the last 2 s (same entity instance), so corpse clicks are no swings (§5.4). |
| `capture/SourcelessDamage.java` ★ | Attacker and confirmation inference for fully sourceless damage events (pre-1.19.4 servers through ViaVersion, §6.3). |
| `capture/TradeWatch.java` | Port of combat-report `TradeWatch` with the sprint-reference fix (§5.10). Emits `TradeEvent`. |
| `capture/HealthWatch.java` | Port of combat-report `HealthWatch`. Also emits `DamageObservedEvent`. |
| `capture/VelocityWatch.java` | Port of combat-report `VelocityWatch`. |
| `capture/OutcomeText.java` ★ | Reads WIN or LOSS out of a kill message, winner announcement or title; ignores chat spoken by anyone in the fight (§5.13a). |
| `capture/OutcomeWatch.java` | Feeds system, action-bar and title text to `OutcomeText` while a fight runs and posts `OutcomeHintEvent` (§5.13a). |
| `mixin/MinecraftAttackMixin.java` | `Minecraft.startAttack` HEAD and RETURN. |
| `mixin/MultiPlayerGameModeMixin.java` | `MultiPlayerGameMode.attack` HEAD, `require = 0` (§6.2). |
| `mixin/LivingEntityMixin.java` | `handleDamageEvent` HEAD, `jumpFromGround` HEAD, `handleEntityEvent` HEAD; every handler returns unless `level().isClientSide()` (R2b). |
| `mixin/MouseHandlerMixin.java` | `MouseHandler.onButton` HEAD (click timestamp only), `require = 0`. |
| `mixin/KeyboardHandlerMixin.java` | `KeyboardHandler.keyPress` HEAD (click timestamp only, for a keyboard-bound Attack), `require = 0`. |
| `mixin/GuiTitleMixin.java` | `Gui.setTitle` and `Gui.setSubtitle` HEAD (read only, for §5.13a), `require = 0`. |

### HUD

| File | Purpose |
|---|---|
| `hud/HudModule.java` | `KwamsterModule` (order 600). Registers the HUD layer with Fabric `HudElementRegistry` (before `BOSS_BAR`, §6.6), the `HUD_EDITOR` and `HUD_PRESETS` screen factories, and a bus listener (card and indicator triggers). |
| `hud/HudManager.java` | Holds the elements, applies layout transforms, fades, F3 hide and editor suppression. Its `render(GuiGraphics, DeltaTracker)` is the registered layer. |
| `hud/HudElement.java` | Element interface (§7.1). |
| `hud/HudLayoutMath.java` ★ | Anchor ↔ position, clamping, auto-anchor, snapping (§7.2). |
| `hud/Draw.java` | Drawing helpers: rounded rect, border, alpha multiply, text with alpha, bar, drop shadow. |
| `hud/Anim.java` ★ | Easing curves and fade envelopes. |
| `hud/AsciiWidths.java` ★ | Vanilla default-font ASCII advance widths (bold +1), for the fit tests only (§7.4). |
| `hud/PreviewStrings.java` ★ | Worst-case and sample row/cell/header strings shared by `HudPreviewData` and the fit tests. |
| `hud/HudPreviewData.java` | Deterministic sample `FightStats`, record, hit and stoplight data for the editor (uses `PreviewStrings`). |
| `hud/elements/StatsPanelElement.java` | Scoreboard-style stats panel. |
| `hud/elements/HitIndicatorElement.java` | Quality pill under the crosshair. |
| `hud/elements/ComboCounterElement.java` | Large combo number near the crosshair. |
| `hud/elements/StoplightElement.java` | Three-light direction indicator. |
| `hud/elements/SummaryCardElement.java` | Fight summary card. |
| `hud/editor/HudEditorScreen.java` | Drag-to-place editor. |
| `hud/presets/HudPresetsScreen.java` | The HUD presets gallery with a live preview of the real HUD elements (§7.10). |

### CROSSHAIR

| File | Purpose |
|---|---|
| `crosshair/CrosshairModule.java` | `KwamsterModule` (order 500). Nothing to register beyond a debug log. It exists so the module list is uniform. |
| `crosshair/CrosshairRenderer.java` | `onVanillaSprite(GuiGraphics, Identifier)` decision plus physical-pixel drawing. |
| `crosshair/CrosshairStateEval.java` | Computes the `CrosshairMode` from player state (§5.12). |
| `crosshair/CrosshairShapes.java` ★ | Shape parameters → pixel masks → merged row spans (disjoint outline and fill, §7.8). |
| `mixin/GuiCrosshairMixin.java` | Two `@WrapWithCondition` on `blitSprite` inside `Gui.renderCrosshair`. |

### FEEDBACK

| File | Purpose |
|---|---|
| `feedback/FeedbackModule.java` | `KwamsterModule` (order 400). Registers a listener and the `SoundPreview` service. |
| `feedback/HitSoundPlayer.java` | Tier sound playback (§8.1). Implements `SoundPreview`. |
| `feedback/ChatReporter.java` | Per-hit, jump-reset and per-fight chat output (§8.2). |
| `feedback/ChatFormat.java` | Component builders (prefix, separators, click and hover). |

### DIRECTION

| File | Purpose |
|---|---|
| `direction/DirectionModule.java` | `KwamsterModule` (order 300). Registers `HeadBarTracker` as a tick listener and `HeadBarRenderer` on `WorldRenderEvents.END_MAIN` (§5.11a), and `OpponentMotionTracker` as `StoplightSource` and as a tick listener (§5.11). |
| `direction/HeadBarMotion.java` ★ | One player's heading, jump confirmation, cooldown and knockback window (§5.11a). |
| `direction/HeadBarTracker.java` | Samples every loaded player per tick into a `HeadBarMotion`, plays the jump cue, serves headings and interpolated heights to the renderer. |
| `direction/HeadBarRenderer.java` | Camera-facing above-head bars on `END_MAIN` with `RenderTypes.debugQuads()` (§5.11a). |
| `src/main/resources/assets/kwamster/sounds.json`, `sounds/jump_cue.ogg` | The jump cue (mono, so it is positional), ported from Direction Indicator. |
| `direction/OpponentMotionTracker.java` | Picks the target, samples positions each tick, feeds `RadialFilter`, publishes `StoplightReading`. |
| `direction/RadialFilter.java` ★ | Smoothing, hysteresis, minimum hold, teleport reset, knockback hold (§5.11). |

### RECORD

| File | Purpose |
|---|---|
| `record/RecordModule.java` | `KwamsterModule` (order 200). Registers the `FightArchive` service, the `SAVED_FIGHTS` screen factory and the `FightEndedEvent` listener. It registers **no** Fabric lifecycle listener: CORE's single `CLIENT_STOPPING` handler calls `archive().shutdown()` (§2.1). |
| `record/FightStore.java` | Implements `FightArchive`: save, load, index, prune, delete, pin, share link, `shutdown()` (drain the IO executor). |
| `record/FightIndex.java` | In-memory and on-disk `index.json` model, rebuilt by a folder scan. |
| `record/LifetimeStore.java` | `lifetime.json` (`StatsTotals`). |
| `record/SharePayloadCodec.java` ★ | Payload v1 encode **and** decode (the decoder is used by tests and is the reference for the JS). |
| `record/ShareLinks.java` ★ | URL assembly, size budget, stratified downsampling search. |
| `record/screen/SavedFightsScreen.java` | Saved-fights browser. |
| `record/screen/FightList.java` | `ObjectSelectionList` of fights. |
| `docs/index.html` | Static viewer for GitHub Pages: decoder (in `<script id="kw-decoder">`, DOM-free, §9.6), stats, timeline, replay, aim map. No external requests. |

### CONFIGUI

| File | Purpose |
|---|---|
| `config/ModMenuIntegration.java` | Rewrites the scaffold: `getModConfigScreenFactory()` returns `parent -> ConfigScreenBuilder.build(parent, null)`. |
| `configui/ConfigUiModule.java` | `KwamsterModule` (order 700). Registers the `SETTINGS` screen factory (`(parent, arg) -> ConfigScreenBuilder.build(parent, arg)`). |
| `configui/ConfigScreenBuilder.java` | `static Screen build(@Nullable Screen parent, @Nullable String initialCategory)`. Builds the Cloth screen (§3 widget column, §3.12 entry rules). When `initialCategory` equals a category title, that category is passed to `ConfigBuilder.setFallbackCategory` so the screen opens on it. |
| `configui/ButtonEntry.java` | Port of combat-tracker `ButtonEntry` (Cloth `TooltipListEntry<Object>`). |
| `configui/GatedEntries.java` | Requirement-aware `ColorEntry`/`IntegerListEntry` subclasses: no error while their requirement fails, and never save unparsable text (§3.12). Colour entries cover Cloth's swatch with a button that opens the colour picker (§3.12a). |
| `configui/ColorPickerScreen.java` | The visual colour picker (§3.12a). |
| `configui/ColorMath.java` ★ | ARGB ↔ HSV, hex and channel text, the text-alpha floor: the picker's arithmetic. |
| `configui/EntryChecks.java` ★ | MC-free label, text-alpha and preview-volume checks used by the Cloth entries. |
| `configui/SoundSuggestions.java` | Curated list of vanilla sound ids for the dropdowns. |

### TEST

| File | Purpose |
|---|---|
| `build.gradle` | **Append only** the test-classpath block from §12.1. No other edits. |
| `src/test/java/kwamster/**/*Test.java` | JUnit 5 tests from §12.2, plus the shared fixture `src/test/java/kwamster/SampleFights.java` (deterministic synthetic records). |
| `src/gametest/java/kwamster/test/KwamsterClientGameTest.java` | Rewrites the scaffold: scenarios from §12.3. |
| `src/gametest/resources/fabric.mod.json` | Exists in the scaffold. TEST owns it and keeps the entrypoint class name `kwamster.test.KwamsterClientGameTest` unchanged. |
| `tools/viewer-check.mjs` | Node script that runs the viewer's JS decoder against the Java golden vectors (§12.1). |
| `docs/images/*.png` | README screenshots, copied from the gametest output. |
| `README.md` | Public README. Sections, in order: features (with screenshots from `docs/images`); install (Fabric API + Cloth Config required, Mod Menu optional); **remove Combat Tracker, Combat Report and Direction Indicator** (duplicate HUDs and the K key clash); keybinds; stat definitions (every S1–S15 rule, including "sprint hit = charged + sprinting", "crit rate over all landed hits", jump-reset ticks, NO-REG hits, NPC filtering); fight rules; share links (what a link contains: player and opponent names, optional server name, relative positions, never coordinates or IPs; how to self-host the viewer by changing the base URL); privacy (no telemetry, no network code); compatibility (Sodium, Iris, Lunar Client: disable Lunar's own crosshair module; 1.8-style servers; spears are counted but excluded); FAQ; credits (builtdoor). |

### 2.1 Init order (`KwamsterClient.onInitializeClient`)

1. `KwamsterConfig cfg = ConfigStore.load()`. Store it in the `Kwamster` facade.
2. Create the `EventBus`, a `Services` with no-op defaults, `TestHooks`, and the `FightManager`. Register it with `bus.registerCore(fightManager)` and set it as `Kwamster.fights()`. Then, **before any module loads**, register CORE's Fabric lifecycle listeners (Fabric array-backed events run default-phase listeners in registration order, so registering here makes CORE's run first):
   - `ClientTickEvents.START_CLIENT_TICK.register(mc -> Clock.onTickStart())` (increments `Clock.ticks()`).
   - `ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> fightManager.markDisconnectPending())`. This may run on a Netty thread (R2a): it sets a `volatile boolean`/`AtomicBoolean` and **does nothing else**. Do not hop with `mc.execute(...)`: `Minecraft.disconnect` calls `dropAllTasks()` right before closing the connection (client/Minecraft.java:2210–2213; the pause-menu path closes the channel first through `level.disconnect` at 2175), so a queued task can be discarded.
   - `ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> { fightManager.forceEnd(FightEndReason.GAME_EXIT); Kwamster.services().archive().shutdown(); })`. This is the **only** `CLIENT_STOPPING` listener in the mod. It runs on the client thread in `Minecraft.destroy` after the "Stopping!" log (fabric/…/event/lifecycle/client/MinecraftMixin.java:41–43). `forceEnd` finalises synchronously (the save is enqueued through the bus), then `shutdown()` drains the IO executor for at most 2 s.
3. `Keybinds.register()`.
4. Load modules: `FabricLoader.getInstance().getEntrypoints("kwamster", KwamsterModule.class)`. Sort by `order()` ascending and call `init()` on each inside its own try/catch. A failing module is logged and skipped, and the rest continue. No module registers `CLIENT_STOPPING` or `DISCONNECT` listeners. The only module-registered Fabric tick listener is CAPTURE's `START_CLIENT_TICK` (§2 CAPTURE), which therefore runs after CORE's `Clock.onTickStart()`.
5. `ClientTickEvents.END_CLIENT_TICK.register(mc -> { Keybinds.handle(mc); bus.postTick(mc); })`. END_CLIENT_TICK fires at the end of every `Minecraft.tick`, including ticks with no level (title screen), which is what lets FightManager finish a fight after a disconnect (§5.13).
6. `LOG.info("KWAMSTER {} ready ({} modules)", version, n)`.

```java
package kwamster.core;
/** One per unit, listed under the "kwamster" entrypoint in fabric.mod.json. Public no-arg constructor. */
public interface KwamsterModule {
	/** Lower runs first. CAPTURE 100, RECORD 200, DIRECTION 300, FEEDBACK 400, CROSSHAIR 500, HUD 600, CONFIGUI 700. */
	int order();
	void init();
}
```

### 2.2 `fabric.mod.json` (CORE writes the final version)

Keep the existing fields. Then set:
- `"entrypoints"`:
  - `"client": ["kwamster.KwamsterClient"]`
  - `"modmenu": ["kwamster.config.ModMenuIntegration"]`
  - `"kwamster": ["kwamster.capture.CaptureModule", "kwamster.record.RecordModule", "kwamster.direction.DirectionModule", "kwamster.feedback.FeedbackModule", "kwamster.crosshair.CrosshairModule", "kwamster.hud.HudModule", "kwamster.configui.ConfigUiModule"]`
- `"depends"`: `"fabricloader": ">=${loader_version}"`, `"minecraft": "${minecraft_version}"`, `"java": ">=21"`, `"fabric-api": ">=0.141.4"`, `"cloth-config": ">=21.11.153"`.
- `"suggests"`: `{ "modmenu": "*" }`.
- `"conflicts"`: `{ "combat_tracker": "*", "combat_report": "*", "directionindicator": "*" }`: the ids in the shipped jars of Combat Tracker (every version overlay, 1.21.1–26.2, uses `combat_tracker`), Combat Report and Direction Indicator. Fabric Loader logs a warning (not a crash) when one is installed alongside KWAMSTER, since they duplicate HUDs and Combat Report also binds K (risk 10).
- `"contact"`: `{ "homepage": "https://github.com/builtdoor1/kwamster", "sources": "https://github.com/builtdoor1/kwamster", "issues": "https://github.com/builtdoor1/kwamster/issues" }`. There is no Modrinth project yet, so no Modrinth link is shipped.
- `"custom"`: `{ "modmenu": { "links": { "modmenu.modrinth": "<Modrinth page>", "modmenu.github_releases": "<GitHub repo>/releases" } } }`, so Mod Menu shows the links on a public release.

Cloth is a hard dependency, so ModMenuIntegration can never load without it (this avoids the crash screentime had).

### 2.3 `kwamster.client.mixins.json` (CORE writes it up front)

```json
{
	"required": true,
	"package": "kwamster.mixin",
	"compatibilityLevel": "JAVA_21",
	"client": [
		"GuiCrosshairMixin",
		"GuiTitleMixin",
		"KeyboardHandlerMixin",
		"LivingEntityMixin",
		"MinecraftAttackMixin",
		"MouseHandlerMixin",
		"MultiPlayerGameModeMixin"
	],
	"injectors": { "defaultRequire": 1 }
}
```

`defaultRequire: 1` applies only to the HEAD/RETURN injectors on methods that cannot disappear (`Minecraft.startAttack`, the three `LivingEntity` methods). Every injector whose target another client mod could plausibly reshape declares `require = 0` explicitly: `MultiPlayerGameModeMixin`, `MouseHandlerMixin`, `KeyboardHandlerMixin`, both `GuiCrosshairMixin` wrappers and both `GuiTitleMixin` injectors. A missing optional injector degrades a feature; it never crashes startup.

The HUD uses no mixin. It registers through Fabric's `HudElementRegistry` (§6.6).

---

## 3. Config POJO (`kwamster.core.config.KwamsterConfig`) ★

- **Storage.** Gson (`Json.PRETTY`) to `config/kwamster/config.json`.
- **Field rule.** All fields are public and non-final, so the Cloth save consumers write them directly. Readers fetch `Kwamster.config()` every frame or tick and never cache a field.
- **Clamping.** `clampAndFill()` clamps every value to the range below. It also replaces null sections, strings, enums or maps with defaults, adds missing `tiers`/`layouts` keys, forces alpha `0xFF` on any **text** colour whose alpha is below `0x20`, and reverts a syntactically invalid tier `soundId` to the tier default (§3.4). It runs after load and before every save.
- **Units.** Cloth has no float slider. Floats are edited with int sliders in the stated sub-unit.
- **Widget column.** This is the Cloth widget CONFIGUI builds. "—" means no Cloth entry exists, because the field is edited elsewhere.

```java
public final class KwamsterConfig {
	public int configVersion = 1;
	public General general = new General();
	public Display display = new Display();
	public Hud hud = new Hud();
	public Feedback feedback = new Feedback();
	public Alert alert = new Alert();
	public Crosshair crosshair = new Crosshair();
	public Direction direction = new Direction();
	public Recording recording = new Recording();
	public Advanced advanced = new Advanced();
	/** Key = HudElementId.name(). Edited only by the HUD editor, never by Cloth. */
	public Map<String, HudLayout> layouts = HudLayout.defaults();

	public static KwamsterConfig defaults();
	public void clampAndFill();
	public TierStyle tier(HitQuality q);          // never null after clampAndFill
	public HudLayout layout(HudElementId id);     // never null after clampAndFill

	public enum DisplayMode { HUD_ONLY, CHAT_ONLY, BOTH }
	public enum ChatMode { OFF, PER_FIGHT, PER_HIT, BOTH }
	public enum PanelIdle { LAST_FIGHT, SESSION, HIDE }
	public enum SoundChannel { MASTER, PLAYERS, UI }
	public enum CrosshairShape { CROSS, T, X, CIRCLE, DOT }
	public enum AttackIndicatorMode { KEEP, HIDE }
	public enum StoplightTarget { FIGHT_ONLY, FIGHT_OR_AIMED }
	public enum StoplightStyle { VERTICAL, HORIZONTAL, SINGLE }
	// nested section classes below, each `public static final class X { … }`
}
```

### 3.1 `General`

| Field | Type | Default | Range | Cloth widget (category "General") | Read by |
|---|---|---|---|---|---|
| `enabled` | boolean | `true` | — | toggle "KWAMSTER enabled" | all units (R4) |
| `hudHidden` | boolean | `false` | — | toggle "Hide all HUD elements" (also the toggle-HUD key) | HUD |
| `hideInDebug` | boolean | `true` | — | toggle "Hide HUD while F3 is open" | HUD |

### 3.2 `Display` (S10)

| Field | Type | Default | Range | Widget ("General") | Read by |
|---|---|---|---|---|---|
| `mode` | DisplayMode | `BOTH` | enum | enum selector "Stats output". HUD_ONLY hides chat output; CHAT_ONLY hides the stats panel. Tooltip: "Controls only the stats panel and chat output. The fight card, hit indicator, combo counter and stoplight have their own toggles." | HUD, FEEDBACK |
| `chatMode` | ChatMode | `PER_FIGHT` | enum | enum selector "Chat output"; disabled (Cloth requirement) while `mode == HUD_ONLY`. Its tooltip ends "Unavailable while Stats output is HUD only." | FEEDBACK |
| `chatFightLines` | int | `2` | 1–3 | int slider "Fight summary lines" (§8.2) | FEEDBACK |
| `chatShareActions` | boolean | `true` | — | toggle "Add [Copy link] [Open] to fight summary" | FEEDBACK |
| `chatHitMisses` | boolean | `true` | — | toggle "Per-hit: include misses" | FEEDBACK |
| `chatJumpResets` | boolean | `true` | — | toggle "Per-hit: include jump resets" | FEEDBACK |

### 3.3 `Hud`

| Field | Type | Default | Range | Widget ("HUD") | Read by |
|---|---|---|---|---|---|
| `panelWhenIdle` | PanelIdle | `LAST_FIGHT` | enum | enum "Panel outside a fight" | HUD |
| `panelCompact` | boolean | `false` | — | toggle "Compact panel" | HUD |
| `rows` | `PanelRows` | see below | — | sub-category "Panel rows", one toggle per field | HUD |
| `comboCounterEnabled` | boolean | `true` | — | toggle "Combo counter" | HUD |
| `comboCounterMin` | int | `2` | 2–5 | int slider "Show combo counter from" | HUD |
| `valueFlash` | boolean | `true` | — | toggle "Flash values when they change" | HUD |
| `accentArgb` | int | `0xFF8B5CF6` | any | alpha colour "Accent" | HUD, CONFIGUI |
| `panelBgArgb` | int | `0xB80E1116` | any | alpha colour "Panel background" | HUD |
| `borderArgb` | int | `0x26FFFFFF` | any | alpha colour "Panel border" | HUD |
| `textArgb` | int | `0xFFF1F3F5` | alpha ≥ 0x20 | alpha colour "Values" | HUD |
| `labelArgb` | int | `0xFF9AA4B2` | alpha ≥ 0x20 | alpha colour "Labels" | HUD |

`PanelRows` (every field is a boolean). Rows draw in this order: `hits = true`, `reach = true`, `combo = true`, `streak = false`, `crits = true`, `sprint = true`, `threeBlock = true`, `jumpResets = true`, `interval = false`, `taken = false`, `aim = false`, `trades = false`, `advantage = true`, `record = true` (the record row draws only in SESSION scope). The defaults are chosen so the in-fight panel (8 rows) ends above the chat band at 427×240 (§7.0 validation).

Plus one ButtonEntry at the top of the tab: "HUD presets…" → `screens().open(HUD_PRESETS, current)` (§7.10).

Plus one ButtonEntry: "Edit HUD layout…" → `screens().open(HUD_EDITOR, current)`.

Plus one ButtonEntry: "Reset HUD layout". It sets `layouts = HudLayout.defaults()` and calls `Kwamster.saveConfig()` immediately. Its label changes to "Layout reset".

### 3.4 `Feedback` (S8, S9)

| Field | Type | Default | Range | Widget ("Hit feedback") | Read by |
|---|---|---|---|---|---|
| `threeBlockReach` | float | `2.95` | 2.50–3.00 | int slider 250–300, text `"%.2f blocks"` | CORE (HitGrader), HUD, FEEDBACK |
| `soundsEnabled` | boolean | `true` | — | toggle "Hit sounds" | FEEDBACK |
| `soundChannel` | SoundChannel | `PLAYERS` | enum | enum "Volume category". Tooltip: "Which vanilla volume slider (Options → Music & Sounds) scales KWAMSTER's hit sounds. Players and UI are also scaled by Master Volume." | FEEDBACK |
| `indicatorEnabled` | boolean | `true` | — | toggle "Hit indicator" | HUD |
| `indicatorDurationMs` | int | `800` | 300–3000 | int slider 6–60 (×50 ms), text `"%d ms"` | HUD |
| `indicatorShowMisses` | boolean | `true` | — | toggle "Indicator: show misses" | HUD |
| `indicatorShowReach` | boolean | `true` | — | toggle "Indicator: show reach" | HUD |
| `tiers` | `Map<String,TierStyle>` | 7 defaults | — | one sub-category per tier (below) | HUD, FEEDBACK, CROSSHAIR |

`TierStyle` (CORE file):

```java
public final class TierStyle {
	public boolean enabled;     // master for this tier: indicator, crosshair flash, sound and per-hit chat line (the hit is always recorded)
	public int argb;            // indicator, crosshair flash and chat colour
	public String label;        // e.g. "3-BLOCK CRIT"
	public boolean soundEnabled;
	public String soundId;      // namespaced id, e.g. "minecraft:entity.experience_orb.pickup"
	public int volumePct;       // 0..100
	public int pitchPct;        // 50..200
	public static Map<String, TierStyle> defaults();
	public TierStyle copy();
}
```

Tier sub-category widgets:
- toggle "Enabled"
- alpha colour "Colour"
- text field "Label" (max 16 characters)
- toggle "Sound"
- string dropdown "Sound id": `startStringDropdownMenu` with `setSelections(SoundSuggestions.IDS)` and `setSuggestionMode(true)`. The error supplier blocks only syntax errors (`Identifier.tryParse(s) == null`), and only while the entry is enabled (§3.12). The tooltip supplier says "Unknown sound (resource pack?)" when `mc.getSoundManager().getSoundEvent(id) == null`, evaluated on every call (no cache, so resource reloads are reflected). `clampAndFill` reverts an id that fails the MC-free copy of that syntax check (`KwamsterConfig.isValidSoundId`) to the tier default, so a hand-edited config can never block Save.
- int slider "Volume" 0–100 %
- int slider "Pitch" 50–200 %
- ButtonEntry "▶ Test". It reads the **pending** values from that tier's entry objects (`getValue()`) **and** the pending "Volume category" entry, then calls `services().sounds().preview(id, vol, pitch, channel)`. When the preview would be silent it flashes a gold warning instead of staying quiet: "Volume is 0%" (the tier volume), "Master Volume is 0%", or "Players volume is 0%" / "UI volume is 0%" (vanilla's sliders, read with `Options.getSoundSourceVolume`; MC-free order and wording in `EntryChecks.previewVolumeProblem`).

Defaults (all sound ids verified in `.ref/mc/net/minecraft/sounds/SoundEvents.java`; line numbers in §8.1):

| Tier (code) | label | argb | soundEnabled | soundId | vol | pitch |
|---|---|---|---|---|---|---|
| THREE_BLOCK_CRIT (0) | `3-BLOCK CRIT` | `0xFFFFD43B` gold | true | `minecraft:entity.experience_orb.pickup` | 80 | 120 |
| THREE_BLOCK_SPRINT (1) | `3-BLOCK SPRINT` | `0xFF3DDC97` mint | true | `minecraft:block.note_block.pling` | 70 | 150 |
| THREE_BLOCK (2) | `3-BLOCK` | `0xFFB794F6` lavender | true | `minecraft:block.note_block.chime` | 60 | 140 |
| CRIT (3) | `CRIT` | `0xFFFF922B` orange | true | `minecraft:block.note_block.bell` | 55 | 120 |
| SPRINT (4) | `SPRINT` | `0xFF4DABF7` blue | true | `minecraft:block.note_block.hat` | 50 | 100 |
| NORMAL (5) | `HIT` | `0xFFE9ECEF` white | **false** | `minecraft:block.note_block.snare` | 35 | 100 |
| WEAK (6) | `WEAK` | `0xFF868E96` grey | **false** | `minecraft:block.note_block.bass` | 40 | 60 |

`enabled` defaults to `true` for every tier. Every tier has a distinct default sound id. The 3-block crit (XP orb, sparkly) and the 3-block sprint (pling, synth) sound clearly different, as the client asked.

A disabled tier (`enabled == false`) suppresses its indicator, crosshair flash, sound and per-hit chat line. The hit is still recorded and counted everywhere.

### 3.5 `Alert` (S11)

| Field | Type | Default | Range | Widget ("Fight alert") | Read by |
|---|---|---|---|---|---|
| `cardEnabled` | boolean | `true` | — | toggle "Summary card" | HUD |
| `cardDurationMs` | int | `7000` | 2000–30000 | int slider 20–300 (tenths of a second), text `"%.1f s"` | HUD |
| `cardFadeMs` | int | `400` | 0–1500 | int slider 0–30 (×50 ms), text `"%d ms"` | HUD |
| `cardFooterHint` | boolean | `true` | — | toggle "Footer hint" (shown only when it adds information, §7.9) | HUD |

Plus one ButtonEntry: "Position card…" → `screens().open(HUD_EDITOR, current, "SUMMARY_CARD")` (editor opens with the card selected).

The card position is set in the HUD editor (`layouts.SUMMARY_CARD`). The chat summary is `display.chatMode` PER_FIGHT or BOTH. Both are raised when `alert` is true (§5.13: enough events **or** a decisive result).

### 3.6 `Crosshair` (S15)

All sizes are in **physical pixels** (§7.8), multiplied by `max(1, round(guiScale/2))` when `scaleWithGui` is on. Every field below `enabled` is disabled (Cloth requirement) while `enabled` is false.

| Field | Type | Default | Range | Widget ("Crosshair") | Read by |
|---|---|---|---|---|---|
| `enabled` | boolean | `true` | — | toggle "Custom crosshair" (off = vanilla) | CROSSHAIR |
| `scaleWithGui` | boolean | `false` | — | toggle "Scale with GUI scale" (for 4K screens) | CROSSHAIR |
| `shape` | CrosshairShape | `CROSS` | enum | enum "Shape" | CROSSHAIR |
| `size` | int | `6` | 1–40 | int slider "Arm length / radius" | CROSSHAIR |
| `gap` | int | `3` | 0–20 | int slider "Gap" | CROSSHAIR |
| `thickness` | int | `2` | 1–10 | int slider "Thickness" | CROSSHAIR |
| `outline` | int | `1` | 0–3 | int slider "Outline" (0 = none) | CROSSHAIR |
| `outlineArgb` | int | `0xB4000000` | any | alpha colour "Outline colour" | CROSSHAIR |
| `centerDot` | int | `0` | 0–10 | int slider "Centre dot" (0 = off) | CROSSHAIR |
| `attackIndicator` | AttackIndicatorMode | `KEEP` | enum | enum "Vanilla attack indicator". Tooltip: "Only applies when Options → Attack Indicator is set to Crosshair. In Hotbar mode vanilla draws it next to the hotbar and this setting has no effect." | CROSSHAIR |
| `defaultArgb` | int | `0xFFFFFFFF` | any | alpha colour "Colour: default" | CROSSHAIR |
| `onPlayerArgb` | int | `0xFFFF4D4D` | any | alpha colour "Colour: on a player in reach" | CROSSHAIR |
| `critReadyArgb` | int | `0xFF38D9FF` | any | alpha colour "Colour: crit ready (falling)" | CROSSHAIR |
| `critOnPlayerArgb` | int | `0xFFFF6BD6` | any | alpha colour "Colour: crit ready on a player" (pink: distinct from every default tier colour, so a hit flash is always visible) | CROSSHAIR |
| `targetMobs` | boolean | `false` | — | toggle "Living mobs count as targets" | CROSSHAIR |
| `hitFlash` | boolean | `true` | — | toggle "Flash tier colour on hit" (also widens the gap by 2 px for the flash, so it reads even when the colours match) | CROSSHAIR |
| `hitFlashMs` | int | `150` | 0–600 | int slider, text `"%d ms"` | CROSSHAIR |

### 3.7 `Direction` (S12)

The above-head bars and the jump cue (§5.11a) come first, with Direction Indicator's defaults. The HUD stoplight (§5.11) follows in its own sub-category; every stoplight field is disabled (Cloth requirement) while `stoplightEnabled` is false.

| Field | Type | Default | Range | Widget ("Direction") | Read by |
|---|---|---|---|---|---|
| `headBarEnabled` | boolean | `true` | — | toggle "Direction bars above heads" | DIRECTION |
| `jumpSoundEnabled` | boolean | `true` | — | toggle "Jump sound" | DIRECTION |
| `radius` | int | `24` | 1–128 blocks | int slider "Radius" (enabled while either of the above is on) | DIRECTION |
| `headBarForSelf` | boolean | `false` | — | "Bar look" toggle "Show above yourself" (F5 only) | DIRECTION |
| `forwardArgb` / `backwardArgb` / `idleArgb` | int | `0xEB4CD964` / `0xEBE03131` / `0xEBFFD43B` | any | "Bar look" alpha colours (alpha 235 = Direction Indicator's fill opacity) | DIRECTION |
| `backdropArgb` | int | `0x8C000000` | any | "Bar look" alpha colour "Backdrop" (alpha 140) | DIRECTION |
| `moveThreshold` | int | `20` | 0–500 thousandths of a block per tick | "Bar look" int slider | DIRECTION |
| `barWidth` / `barHeight` | int | `55` / `11` | 5–300 / 2–100 hundredths of a block | "Bar look" int sliders | DIRECTION |
| `headClearance` | int | `30` | 0–300 hundredths of a block | "Bar look" int slider "Height above head" | DIRECTION |
| `jumpSoundForSelf` | boolean | `false` | — | "Jump sound settings" toggle | DIRECTION |
| `jumpSoundVolume` / `jumpSoundPitch` | int | `60` / `160` | 0–200 / 50–200 % | "Jump sound settings" int sliders, plus a ▶ Test button (FEEDBACK's `SoundPreview`, PLAYERS channel) | DIRECTION |
| `jumpKnockbackTicks` | int | `10` | 0–40 ticks (0 = off) | "Jump sound settings" int slider "Ignore knockback for" | DIRECTION |
| `stoplightEnabled` | boolean | `false` | — | "HUD stoplight" toggle "Opponent stoplight". Replaces the pre-release `enabled` key (default `true`), which is now ignored, so an upgraded config gets the opt-in default | DIRECTION, HUD |
| `target` | StoplightTarget | `FIGHT_OR_AIMED` | enum | enum "Whose movement" | DIRECTION |
| `enterBps` | float | `1.0` | 0.3–4.0 | int slider 3–40 (tenths of b/s) "Approach/retreat threshold" | DIRECTION |
| `hysteresisBps` | float | `0.5` | 0.0–2.0 | int slider 0–20 (tenths) | DIRECTION |
| `smoothingMs` | int | `150` | 0–600 | int slider | DIRECTION |
| `minHoldMs` | int | `150` | 0–600 | int slider | DIRECTION |
| `kbHoldMs` | int | `300` | 0–1000 | int slider "Ignore knockback for" | DIRECTION |
| `aimedRange` | float | `8.0` | 3–16 | int slider 3–16 blocks | DIRECTION |
| `style` | StoplightStyle | `VERTICAL` | enum | enum "Style" | HUD |
| `showLabel` | boolean | `false` | — | toggle "Show label and speed" | HUD |
| `hideWhenNoTarget` | boolean | `true` | — | toggle "Hide when nobody is tracked" (off: the unlit housing stays, drawn at 40 % alpha) | HUD |
| `greenArgb` | int | `0xFF2BD96B` | any | alpha colour "Approaching" | HUD |
| `yellowArgb` | int | `0xFFFFC53D` | any | alpha colour "Holding / strafing" | HUD |
| `redArgb` | int | `0xFFFF4D4F` | any | alpha colour "Backing away" | HUD |
| `offArgb` | int | `0xFF2A2F37` | any | alpha colour "Unlit" | HUD |

Plus one ButtonEntry in the stoplight sub-category: "Position stoplight…" → `screens().open(HUD_EDITOR, current, "STOPLIGHT")`.

### 3.8 `Recording` (S13, S14)

| Field | Type | Default | Range | Widget ("Recording & sharing") | Read by |
|---|---|---|---|---|---|
| `saveFights` | boolean | `true` | — | toggle "Save every fight" | RECORD |
| `saveTrivialFights` | boolean | `true` | — | toggle "Also save fights below the minimum events" | RECORD |
| `maxSavedFights` | int | `500` | 0–5000 | int field with min/max (0 = unlimited) | RECORD |
| `shareBaseUrl` | String | `"https://builtdoor1.github.io/kwamster/"` (the pre-release `…/KWAMSTER/` placeholder is migrated to it) | valid per §9.1 (`new URI(trimmed)` succeeds, scheme `http`/`https`, non-empty host) | string field; its error supplier applies the same check. `clampAndFill` reverts an invalid value to the default (so a hand-edited config can never break chat lines) | RECORD |
| `shareIncludeServer` | boolean | `true` | — | toggle "Include server name (never raw IPs)" | RECORD |
| `sharePositions` | boolean | `true` | — | toggle "Include positions for the replay" | RECORD |
| `shareMaxChars` | int | `1900` | 600–8000 | int field "Max link length" | RECORD |

ButtonEntries in this category:
- "Saved fights…" opens SAVED_FIGHTS.
- "Open fights folder".
- "Copy last fight link".
- "Reset session stats" (`fights().resetSession()`).
- "Reset lifetime stats" (two-click confirm, `archive().resetLifetime()`).

### 3.9 `Advanced` (detection constants)

| Field | Type | Default | Range | Widget ("Advanced") | Read by |
|---|---|---|---|---|---|
| `fightIdleMs` | int | `8000` | 2000–60000 | int slider 4–120 (×500 ms), text `"%.1f s"` | CORE |
| `fightMaxDistance` | float | `16.0` | 6–64 | int slider (blocks), text `"%d blocks"` | CORE |
| `minFightEvents` | int | `3` | 1–20 | int slider. A DRAW with fewer (swings + melee hits taken) raises no alert or chat line and is not added to session or lifetime totals. A WIN or LOSS always alerts (§5.13) | CORE, HUD, FEEDBACK, RECORD |
| `ignoreNpcs` | boolean | `true` | — | toggle "Ignore NPCs (players missing from the tab list)" (§5.4) | CAPTURE, CORE, DIRECTION, CROSSHAIR |
| `jumpWindowMinMs` | int | `0` | 0–100 | int slider "Reset success from", text `"%d ms (tick %d)"`. **Game time** (§5.1): 0 ms = the first tick after the hit, +50 ms per tick | CAPTURE, CORE |
| `jumpWindowMaxMs` | int | `80` | 20–250, ≥ min | int slider "…to", same text | CAPTURE, CORE |
| `jumpAttemptMs` | int | `200` | 100–400, ≥ max | int slider "Jump counts as an attempt within", game time | CAPTURE |
| `comboTimeoutMs` | int | `2000` | 0–10000 | int slider (0 = never) | CORE |
| `advantageWindowMs` | int | `3000` | 1000–10000 | int slider | CORE |
| `tradeWindowMs` | int | `400` | 100–1000 | int slider | CAPTURE |
| `swingTargetRange` | float | `6.0` | 3–10 | int slider (blocks) | CAPTURE |
| `swingTargetConeDeg` | int | `30` | 5–60 | int slider | CAPTURE |
| `intervalMaxGapMs` | int | `2000` | 500–5000 | int slider | CORE |
| `deathInferenceMs` | int | `3000` | 0–10000 | int slider "Spectator switch after a hit counts as a death within" | CORE |
| `inferOutcomes` | boolean | `true` | — | toggle "Detect wins on practice servers" (§5.13a) | CORE |
| `readOutcomeMessages` | boolean | `true` | — | toggle "Read kill and win messages" (§5.13a). Off: no system message, action-bar message or title is read, and abrupt draws are final at once | CAPTURE, CORE |
| `outcomeWindowMs` | int | `1500` | 500–5000 | int slider "Win detection window", text `"%.1f s"` (×100 ms). How soon after the last hit an abrupt end still decides the result, and an abrupt draw waits for a message (§5.13a) | CORE |
| `noRegImmunityTicks` | int | `10` | 0–20 | int slider "NO-REG immunity window", text `"%d ticks"`, 0 shown as "Off". The server's hurt-immunity window used by the NO-REG estimate (§5.4); 0 disables NO-REG detection, for combo servers with a reduced hit delay. Read live at the landed hook (not part of the settings snapshot) | CAPTURE |

`clampAndFill` also enforces `jumpWindowMinMs ≤ jumpWindowMaxMs ≤ jumpAttemptMs`.

**Settings snapshot.** FightManager copies `threeBlockReach`, the three jump windows, `comboTimeoutMs`, `advantageWindowMs` and `intervalMaxGapMs` into `FightRecord.settings` when a fight opens. Grading and every derived number for that fight use the snapshot; config changes apply from the next fight (stated in the Advanced category's description line).

### 3.10 Layout satellite types (CORE files)

```java
public enum Anchor {   // fx, fy ∈ {0, 0.5, 1}
	TOP_LEFT(0f,0f), TOP_CENTER(.5f,0f), TOP_RIGHT(1f,0f),
	CENTER_LEFT(0f,.5f), CENTER(.5f,.5f), CENTER_RIGHT(1f,.5f),
	BOTTOM_LEFT(0f,1f), BOTTOM_CENTER(.5f,1f), BOTTOM_RIGHT(1f,1f);
	public final float fx, fy;
	public static Anchor of(float fx, float fy); // nearest
}
public enum HudElementId { STATS_PANEL, HIT_INDICATOR, COMBO_COUNTER, STOPLIGHT, SUMMARY_CARD }
public final class HudLayout {
	public Anchor anchor; public int offsetX; public int offsetY; public float scale; // scale 0.5..3.0
	public HudLayout(Anchor a, int x, int y, float s);
	public HudLayout copy();
	public static Map<String, HudLayout> defaults();
	public static HudLayout defaultFor(HudElementId id);
}
```

| Element | Default anchor | offset | scale |
|---|---|---|---|
| STATS_PANEL | TOP_LEFT | (5, 6) | 1.0 |
| HIT_INDICATOR | CENTER | (0, 26) | 1.0 |
| COMBO_COUNTER | CENTER | (30, 0) | 1.0 |
| STOPLIGHT | CENTER | (−30, 0) | 1.0 |
| SUMMARY_CARD | TOP_CENTER | (0, 20) | 1.0 |

Why these values (checked by `HudLayoutMathTest`, §12.2): vanilla draws the crosshair at `(H−15)/2` and the attack indicator at `y = H/2 − 7 + 16 = H/2 + 9`, x `W/2 − 8 … W/2 + 8` (client/gui/Gui.java:432 and 442–450; the full sprite's opaque rows end at H/2 + 16). So the reserved vanilla rect is `[W/2−8, H/2−8, W/2+8, H/2+17)`. The hit-indicator pill (11 px tall) at (0, 26) starts at H/2 + 20.5, below that rect even at its 1.15× pop-in. The combo counter (30×26) spans x W/2+15…W/2+45 and the stoplight (10×26) x W/2−35…W/2−25, both within ±13 rows of the centre and clear of the pill and the vanilla rect. The card (56 px tall with default settings, §7.9) at (0, 20) ends at y = 76, above the vanilla title band (`H/2 − 40 … H/2 − 4`, Gui.java:362–367) at both 427×240 (band starts at 80) and 480×270 (95), and below one boss bar (y ≤ 17).

**Ownership rule (avoids clobbering).** Cloth never builds an entry for `layouts`. The HUD editor writes only `layouts`, then calls `Kwamster.saveConfig()`. Opening the editor from inside Cloth and coming back, then pressing Save, therefore cannot overwrite the layout. Each element's visibility toggle lives in its feature section in Cloth (§7.1 table).

### 3.11 `ConfigStore` (CORE)

```java
public final class ConfigStore {
	public static Path dir();                       // FabricLoader configDir/kwamster (created on demand)
	public static KwamsterConfig load();            // never throws
	public static void save(KwamsterConfig cfg);    // clampAndFill then AtomicFiles.writeString(PRETTY)
}
```

`load()` reads `config.json`.
- If the file is missing, it saves defaults.
- If it cannot be parsed, it renames the file to `config.broken-<epochMs>.json`, logs a warning and returns defaults (which are saved).
- Unknown keys are ignored. Missing keys keep their field defaults.
- `configVersion` is migrated forward when it changes. v1 needs no migration.

### 3.12 Cloth entry rules (CONFIGUI)

Every entry follows these rules, so the settings screen looks finished:

- **Enum labels.** Every enum selector calls `setEnumNameProvider` (Cloth's default is `Component.translatable(enum.toString())`, `EnumListEntry.DEFAULT_NAME_PROVIDER`, which would show raw names). Literal tables:
  - DisplayMode: HUD only / Chat only / HUD + chat
  - ChatMode: Off / After each fight / Every hit / Both
  - PanelIdle: Last fight / Session totals / Hidden
  - SoundChannel: Master / Players / UI
  - CrosshairShape: Cross / T / X / Circle / Dot
  - AttackIndicatorMode: Keep / Hide
  - StoplightTarget: Current fight only / Fight or aimed player
  - StoplightStyle: Vertical / Horizontal / Single light
- **Defaults.** Every entry calls `.setDefaultValue(...)` with the value from `KwamsterConfig.defaults()` (this gives Cloth's per-entry reset button).
- **Tooltips.** Every entry has a one-line `.setTooltip(...)`. Where §3 gives tooltip text, use it.
- **Units.** Every slider has `setTextGetter` with units (ms, s, b/s, blocks, %, px).
- **Stepped sliders.** Cloth's `IntSliderBuilder` has no step option. Fields with a step are edited as scaled ints: ms fields with 50 ms steps use `value/50`, `fightIdleMs` uses `value/500`, `cardDurationMs` uses tenths of a second. The save consumer multiplies back.
- **Requirements** (`FieldBuilder.setRequirement(Requirement.isTrue/isValue(...))`, Cloth 21.11; the controlling entry is built first and passed as the `ValueHolder`):
  - `chatMode` and every `chat*` toggle are disabled while `mode == HUD_ONLY`.
  - every crosshair field is disabled while `crosshair.enabled` is false.
  - each tier's fields are disabled while that tier's `enabled` is false.
  - every direction field is disabled while `direction.enabled` is false.
- **Validation pauses while an entry is disabled.** Cloth 21.11's `getConfigError()` is final and falls back to `getError()` without consulting `isEnabled()`, so a greyed-out entry with bad text would block Save. Every validated entry under a requirement therefore reports no error while that requirement fails, and its save never writes an invalid value (the live config keeps the previous one; Cloth would otherwise turn bad colour text into white `-1` and bad number text into `0`). Colour and int fields use `GatedEntries` (`ColorEntry`/`IntegerListEntry` subclasses built like `ColorFieldBuilder`/`IntFieldBuilder.build()`, with the gate checked directly); string fields and dropdowns (tier Label, Sound id) use a gated error supplier and a checking save consumer. The text-colour alpha rule (≥ 0x20) is part of the gated error.
- **Category order and titles** (these exact strings are also the `initialCategory` keys): General, HUD, Hit feedback, Fight alert, Crosshair, Direction, Recording & sharing, Advanced.

### 3.12a Colour picker (CONFIGUI)

- **Opening.** Every colour entry is a `GatedEntries.Colour`. Its 20 px swatch (Cloth's `ColorDisplayWidget`) is covered by a `PickSwatch` button in the entry's `widgets` list, so Cloth routes clicks, Tab focus and narration to it like to the hex field. It draws the colour over a checkerboard with a small corner fold at rest, an accent ring, a hand cursor and the tooltip "Click to pick a colour" on hover, and a red cross while the hex text is malformed. A greyed-out entry never opens the picker.
- **`ColorPickerScreen(parent, path, initialArgb, defaultArgb, textColour, onDone)`.** The title is the setting's path ("Hit feedback · ■ 3-block crit · Colour"). A saturation/value square for the current hue (drawn per physical pixel column, so it is crisp at any GUI scale), a hue strip and an alpha strip over a checkerboard; a drag keeps following the mouse outside the control and the wheel nudges strips and channel fields (Shift: bigger steps). A Before/After preview (clicking Before restores the original), a "Sample 3.07" text and bar on `hud.panelBgArgb`, an HSV readout, a hex field (`#`/`0x` optional, 6 digits opaque or 8) and R, G, B, A fields kept in sync (the focused field keeps its text; grey while incomplete, red when invalid, amber when clamped), a palette (accent, the seven tier colours, good/warn/bad, greys) and "Recent", the last 8 colours confirmed this session. Buttons: Default, Cancel (Esc), Done (Enter); clicking a swatch or button takes no keyboard focus.
- **Text colours** (`textColour = true`) keep alpha ≥ `MIN_TEXT_ALPHA` (0x20): the blocked part of the alpha strip is darkened and the title row reads "Text colour: alpha 20+", amber when a clamp happens.
- **Done** writes the colour into the hex field with the body of `ColorEntry.setValue(int)` (deprecated in Cloth 21.11.153), so it is a pending edit exactly like typing: the entry shows as edited, Save applies it, Cancel discards it, Reset works. Returning restores the settings list's scroll position (Cloth rebuilds the list when its screen is shown again).
- **Rendering.** 1.21.11's GUI renderer slows down with the number of separate shapes per frame, so the square and checkerboards (about 1,300 rects) are batched into a few combined draws (`QuadBatch`).

---

## 4. Data model (exact signatures)

### 4.1 Value types (`kwamster.core.model`) ★

```java
public record PlayerRef(UUID uuid, String name) {}           // name has § codes stripped and is trimmed
public record Pos(double x, double y, double z) {}

/** h: across-view offset / projected half-width (+ = right, as seen by the attacker).
 *  v: (y − centreY) / halfHeight (+1 = top). Hits are within [-1,1]; misses may exceed it. */
public record AimSample(double h, double v, @Nullable AimZone zone, boolean eyeInside) {}

public enum HitQuality {
	THREE_BLOCK_CRIT, THREE_BLOCK_SPRINT, THREE_BLOCK, CRIT, SPRINT, NORMAL, WEAK;
	public int code() { return ordinal(); }            // stable: used in payload v1
	public boolean threeBlock() { return ordinal() <= 2; }
	public static HitQuality byCode(int c);
}
public enum AimZone { CENTER, EDGE, HIGH, LOW; public int code() { return ordinal(); } }
public enum StoplightState { OFF, GREEN, YELLOW, RED }
public record StoplightReading(StoplightState state, double approachBps, boolean knockbackHold,
                               @Nullable PlayerRef target, long sinceNanos) {
	public static final StoplightReading NONE = new StoplightReading(StoplightState.OFF, 0, false, null, 0L);
}
public enum CrosshairMode { DEFAULT, ON_PLAYER, CRIT_READY, CRIT_READY_ON_PLAYER }
public enum FightResult { DRAW, WIN, LOSS; public int code() { return ordinal(); } }  // payload: 0 DRAW, 1 WIN, 2 LOSS
public enum FightEndReason {
	IDLE(0), OPPONENT_DIED(1), SELF_DIED(2), OPPONENT_LEFT(3), OPPONENT_FAR(4),
	DISCONNECT(5), WORLD_CHANGE(6), GAME_EXIT(7), DISABLED(8);
	public final int code; public static FightEndReason byCode(int c);
}
public record ShareLink(String url, int length, boolean downsampled, int eventsKept, int eventsTotal) {}
public final class FightSummaryRow {  // Gson; built on the client thread, serialised there (§10)
	public String id, file, opponent, result, server;
	public long startEpochMs, durationMs;
	public int swings, landed, jumpAttempts, jumpSuccess, bestCombo;
	public int extraOpponents;        // engaged opponents besides the primary ("vs Kwamix +1")
	public double accuracyPct, reachAvg;
	public boolean pinned;
	public boolean trivial;           // !alert at save; false for legacy rows and for rows rebuilt from fight files (pruned first, §10)
}
```

### 4.2 Capture events (`kwamster.core.event`) ★

All are records. `nanos` comes from `Clock.nanos()` at the hook unless stated otherwise. `tick` is `Clock.ticks()` at the hook.

```java
/** One committed swing at a player (landed or missed). Posted at startAttack RETURN. */
public record SwingEvent(
	long nanos,              // startAttack HEAD time (hook time). Used for ordering, fight start/keep-alive, combos, advantage, trades, confirmation
	boolean clickTimed,
	int clickLeadMs,         // hook − claimed click press, 0..200; 0 when !clickTimed. S8 intervals use (t − clickLeadMs)
	PlayerRef target,
	boolean landed,          // reached MultiPlayerGameMode.attack as a non-spectator (§6.2)
	boolean intoImmunity,    // landed && NoRegEstimator flags it at the landed hook ("NO-REG", §5.4): within noRegImmunityTicks of your last registered swing on the target, or inside immunity from other damage
	double reach,            // eye → nearest point of the target AABB (blocks), tick positions
	double rayReach,         // eye → ray entry point; NaN if the ray misses the box
	double rangeLimit,       // §5.2: ATTACK_RANGE max + hitbox margin if the held item has the component, else entityInteractionRange() (3.0 vanilla)
	boolean customRange,     // the held item has DataComponents.ATTACK_RANGE (3-block grading is skipped, §5.9)
	AimSample aim,           // zone non-null only when landed
	float charge,            // getAttackStrengthScale(0.5F)
	boolean charged,         // charge > 0.9F
	boolean crit,            // charged && canCriticalAttack (verbatim)
	boolean sprint,          // raw isSprinting() at classification
	boolean sweep,           // vanilla sweep condition
	boolean onGround,
	Pos selfPos, Pos targetPos,
	float selfYaw,
	int pingMs) {}           // own PlayerInfo latency, 0 if unknown

/** A spear swing. target = the cone-resolved player (same rule as a miss, §5.4) or null. Keep-alive only. */
public record PiercingSwingEvent(long nanos, @Nullable PlayerRef target) {}

/** Damage event on the local player whose cause is another (non-NPC) player. On a server whose damage events
 *  carry no source at all (pre-1.19.4 through ViaVersion) the attacker is inferred (§6.3); such events are not
 *  marked (no field exists; an additive `boolean inferred` would change every constructor). */
public record HitTakenEvent(long nanos, long tick, PlayerRef attacker, boolean projectile,
	double attackerReach,    // attacker eye → own AABB (approximate: remote positions are interpolated)
	boolean selfOnGround, boolean selfSprinting, Pos selfPos, Pos attackerPos) {}

public record JumpEvent(long nanos, long tick, Pos pos) {}

/** A decided jump-reset attempt (§5.1). nanos = jump time, hitNanos = hit time.
 *  deltaTicks = jumpTick − hitTick (≤ 0 = early). deltaMs = game time = 50·(deltaTicks − 1) (0 = first tick after the hit).
 *  wallMs = wall-clock jump − hit (informative only, never used for decisions). success uses the live config window. */
public record JumpResetEvent(long nanos, long hitNanos, int deltaTicks, int deltaMs, int wallMs,
	boolean success, PlayerRef attacker) {}

public record DeathEvent(long nanos, PlayerRef who, boolean self) {}

/** A server damage event on another player caused by you (source.getEntity() == self, never THORNS).
 *  direct = melee (confirms a landed swing, §5.9); !direct = your projectile (keep-alive only).
 *  On a sourceless server a direct confirmation is inferred from your own registered (not NO-REG) swing about one round trip
 *  to ping + 300 ms earlier, one event per swing (§6.3), unmarked. */
public record HitConfirmedEvent(long nanos, PlayerRef victim, boolean direct) {}

/** nanos = the tick the drop was observed. Only self or players already in the fight's opponents are recorded (§4.9). */
public record DamageObservedEvent(long nanos, UUID who, boolean self, double amount) {}

/** nanos = the time of the LATER of the two paired hits (not the emission time), so it is ≤ the fight's lastCombat. */
public record TradeEvent(long nanos, PlayerRef opponent, double dealt, double taken,
	boolean damageKnown, double momentumPct, boolean momentumKnown) {}

/** Posted only by CORE (FightManager, §5.13), on the client thread, AFTER any fight it ends has been finalised.
 *  DISCONNECT: the connection went away. WORLD_CHANGE: the ClientLevel or the LocalPlayer instance changed (dimension change, server switch, respawn). */
public record WorldLeaveEvent(long nanos, Reason reason) {
	public enum Reason { DISCONNECT, WORLD_CHANGE }
}
```

CORE events:

```java
public record FightStartedEvent(LiveFightView fight) {}
/** nanos = Clock.nanos() at resolution (not the swing time), so HUD/crosshair ages start fresh.
 *  quality: null = miss; non-null for every landed swing, NO-REG ones included (swing.intoImmunity()).
 *  comboAfter/streakAfter: exactly LiveFightView.currentCombo/currentStreak right after this swing (§5.5).
 *  intervalMs: the S8 interval this hit closes (same opp, ≤ intervalMaxGapMs, NO-REG excluded), −1 if none. */
public record HitResolvedEvent(SwingEvent swing, @Nullable HitQuality quality,
	int comboAfter, int streakAfter, int intervalMs, long nanos) {}
/** alert = (swings + melee hits taken) ≥ minFightEvents || result != DRAW. */
public record FightEndedEvent(FightRecord record, FightStats stats, boolean alert) {}
```

### 4.3 Listener and bus (`kwamster.core.event`, CORE)

```java
public interface KwamsterListener {
	default void onSwing(SwingEvent e) {}
	default void onPiercingSwing(PiercingSwingEvent e) {}
	default void onHitTaken(HitTakenEvent e) {}
	default void onJump(JumpEvent e) {}
	default void onJumpReset(JumpResetEvent e) {}
	default void onDeath(DeathEvent e) {}
	default void onHitConfirmed(HitConfirmedEvent e) {}
	default void onDamageObserved(DamageObservedEvent e) {}
	default void onTrade(TradeEvent e) {}
	default void onWorldLeave(WorldLeaveEvent e) {}
	default void onFightStarted(FightStartedEvent e) {}
	default void onHitResolved(HitResolvedEvent e) {}
	default void onFightEnded(FightEndedEvent e) {}
	/** END_CLIENT_TICK, after keybind handling. Core listener first. */
	default void onClientTick(net.minecraft.client.Minecraft mc) {}
}

public final class EventBus {
	public void registerCore(KwamsterListener l);   // CORE only; always dispatched first
	public void register(KwamsterListener l);       // dispatched in registration (module) order
	public void unregister(KwamsterListener l);
	/** Dispatches now. If a dispatch is already running, the event is appended to a FIFO queue
	 *  and dispatched after it (so e.g. HitResolvedEvent always follows SwingEvent everywhere). */
	public void post(Object event);
	/** Calls onClientTick on the core listener, then on every other listener. This is NOT a dispatch:
	 *  dispatching() is false while tick handlers run, so an event posted from a tick handler is
	 *  dispatched synchronously and completely before post() returns. */
	public void postTick(net.minecraft.client.Minecraft mc);
	public boolean dispatching();
}
```

- **Typing.** `post` is typed by a pattern `switch` over the record types. An unknown type is logged once.
- **Failure isolation.** Each listener call is wrapped in try/catch, so one unit cannot break another.
- **Thread.** `post` and `postTick` are client-thread only (R2). The bus does not lock.

### 4.4 Services (CORE)

```java
public final class Services {
	public StoplightSource stoplight();      public void setStoplight(StoplightSource s);
	public FightArchive archive();           public void setArchive(FightArchive a);
	public SoundPreview sounds();            public void setSounds(SoundPreview s);
	public CaptureControl capture();         public void setCapture(CaptureControl c);
	public ScreenRegistry screens();         // CORE instance, never replaced
}
public interface StoplightSource { StoplightReading reading(); }              // default → StoplightReading.NONE
/** channel = the vanilla volume category to play on (the pending Cloth value); null = the saved feedback.soundChannel. */
public interface SoundPreview { void preview(String soundId, int volumePct, int pitchPct, KwamsterConfig.@Nullable SoundChannel channel); } // default no-op
/** flushPending first takes one extra health sample (HealthWatch.sampleNow, no retention tick; skipped while
 *  general.enabled is off), then settles pending trades now (posting events synchronously). It must tolerate
 *  mc.level == null and mc.player == null (it is called after a disconnect, §5.13). */
public interface CaptureControl { void flushPending(); }                      // default no-op
public interface FightArchive {                                               // default: empty and no-op
	List<FightSummaryRow> list();                     // newest first, from the in-memory index
	Optional<FightRecord> load(String id);
	@Nullable ShareLink shareLink(FightRecord record); // null if the base URL is invalid
	boolean delete(String id);
	void setPinned(String id, boolean pinned);
	Path folder();
	StatsTotals lifetime();
	void resetLifetime();
	/** Called once, from CORE's CLIENT_STOPPING handler after the last fight was finalised:
	 *  executor.shutdown() then awaitTermination(2 s). CORE default: no-op. */
	void shutdown();
}
public final class ScreenRegistry {
	public enum ScreenId { SETTINGS, HUD_EDITOR, SAVED_FIGHTS, HUD_PRESETS }
	@FunctionalInterface public interface Factory { Screen create(@Nullable Screen parent, @Nullable String arg); }
	public void register(ScreenId id, Factory factory);
	public @Nullable Screen create(ScreenId id, @Nullable Screen parent);              // arg = null
	public @Nullable Screen create(ScreenId id, @Nullable Screen parent, @Nullable String arg);
	/** Minecraft.getInstance().setScreen(create(...)); returns false if not registered. */
	public boolean open(ScreenId id, @Nullable Screen parent);
	public boolean open(ScreenId id, @Nullable Screen parent, @Nullable String arg);
}
```

`Screen` here is `net.minecraft.client.gui.screens.Screen`. The optional `arg` means: SETTINGS → initial Cloth category title (§3.12); HUD_EDITOR → `HudElementId.name()` to preselect; SAVED_FIGHTS → ignored; HUD_PRESETS → a `HudPreset` name to preselect (default: the preset in use). An unknown arg is ignored.

### 4.5 Fight service and live view (CORE)

```java
public interface FightService {
	boolean inFight();
	@Nullable LiveFightView current();
	@Nullable PlayerRef currentOpponent();
	@Nullable FightRecord lastRecord();
	@Nullable FightStats lastStats();
	long lastEndedNanos();                      // 0 if none this session
	@Nullable HitResolvedEvent lastResolved();  // most recent resolved swing (landed or missed)
	StatsTotals session();                      // fights ended this game session with alert == true
	void resetSession();
}

/** All nowNanos arguments are raw Clock.nanos(); the view converts them to the fight clock (pauses excluded). */
public interface LiveFightView {  // ★
	long startNanos();
	long startEpochMs();
	PlayerRef opponent();                    // current opponent (provisional until engaged, §5.14)
	List<PlayerRef> opponents();             // index order = FightRecord.opponents
	long elapsedMs(long nowNanos);
	FightStats stats(long nowNanos);         // StatsCalculator.compute(record, nowRel), cached ≤ 50 ms
	int currentCombo(long nowNanos);         // == stats(now).currentCombo (normative)
	int currentComboTaken(long nowNanos);
	int currentStreak();
	double advantage(long nowNanos);         // [-1, 1], == stats(now).advantage
}
```

**Single source of truth (normative).** Every live number (`HitResolvedEvent.comboAfter/streakAfter/intervalMs`, the HUD rows, the combo counter, the per-hit chat line, the advantage bar) equals what `StatsCalculator.compute(liveRecord, nowRel)` returns for the same record and time. FightManager may compute incrementally but must match; `StatsCalculatorTest` replays a live sequence and compares (§12.2).

### 4.6 `FightRecord` (Gson POJO) ★

Every `t` is an int in ms on the fight clock relative to `startNanos` (pauses excluded, §5.13). Only a jump just before the opening event can have a negative `t`. Positions are blocks relative to **origin = own position at fight start**. No absolute world coordinates are stored. Every float/double is finite (§4.9 sanitising).

```java
public final class FightRecord {
	public int schema = 1;
	public String id;                 // "yyyyMMdd-HHmmss-SSS-<Opp>" UTC, see §10
	public String modVersion;
	public long startEpochMs;
	public int durationMs;            // §5.13
	public String result;             // FightResult.name()
	public String endReason;          // FightEndReason.name()
	public String playerName, playerUuid;
	public String opponentName, opponentUuid;            // primary opponent (§5.14)
	public List<String> opponents = new ArrayList<>();   // names (first seen), index = opp; keyed by UUID
	public List<String> opponentUuids = new ArrayList<>();
	public List<Boolean> engaged = new ArrayList<>();    // parallel to opponents: ≥ 1 authoritative event (§5.14)
	public String server = "";        // host without port, "Singleplayer", or ""
	public int pingMs;                // mean of samples
	public boolean opponentHealthVisible;
	public int piercingSwings, projectileHitsTaken;      // jump opportunities are derived (§5.1), never stored
	public Settings settings = new Settings();           // snapshot at fight open (§3.9)
	public List<Swing> swings = new ArrayList<>();
	public List<HitTaken> hitsTaken = new ArrayList<>();
	public List<JumpAttempt> jumps = new ArrayList<>();
	public List<Trade> trades = new ArrayList<>();
	public List<DamageDrop> damage = new ArrayList<>();
	public FightStats stats;          // filled at finish

	public static final class Settings {
		public float threeBlockReach; public int jumpMinMs, jumpMaxMs, jumpAttemptMs,
		comboTimeoutMs, advantageWindowMs, intervalMaxGapMs;
	}
	public static final class Swing {
		public int t; public int opp; public boolean landed;
		public int clickLeadMs;                          // 0 when !clickTimed
		public boolean intoImmunity;                     // NO-REG (landed only)
		public boolean customRange;                      // ATTACK_RANGE component present
		public float reach, rayReach /* -1 = n/a */, rangeLimit;
		public float aimH, aimV; public String zone;     // null for misses
		public String quality;                           // HitQuality name, null for misses
		public float charge; public boolean charged, crit, sprint, sweep, onGround, clickTimed, confirmed;
		public float sx, sz, ox, oz, yaw;
	}
	public static final class HitTaken {             // melee only
		public int t; public int opp; public float reach; public boolean onGround, sprinting;
		public float sx, sz, ox, oz;
	}
	public static final class JumpAttempt {
		public int t;                                    // jump time
		public int deltaTicks;                           // jumpTick − hitTick (≤ 0 = early)
		public int deltaMs;                              // game time 50·(deltaTicks − 1)
		public int wallMs;                               // wall clock, informative only
		public boolean success;                          // as decided live; StatsCalculator recomputes from deltaMs + settings
	}
	public static final class Trade {
		public int t; public int opp; public float dealt, taken, momentumPct;
		public boolean damageKnown, momentumKnown;
	}
	public static final class DamageDrop { public int t; public boolean self; public int opp /* −1 when self */; public float amount; }
}
```

### 4.7 `FightStats` (derived, Gson) ★

`NaN` is never stored. When something has no data, the value is 0 and the matching count field is 0. Formatters print "—" when the count is 0.

```java
public final class FightStats {
	public int durationMs;                        // live: max(0, nowRel); final: record.durationMs
	// S4
	public int swings, landed, misses; public double accuracyPct;
	public int immuneHits;                        // landed swings flagged NO-REG (included in landed)
	// S2
	public double reachAvg, reachMax; public int reachCount;
	public double missReachAvg, missReachMax; public int missReachCount, overReachMisses;
	public double missReachMaxLimit;              // attack range of the miss that set missReachMax (0: no misses), §7.0
	// S3 (landed only)
	public int aimCenter, aimEdge, aimHigh, aimLow;
	// S5
	public int currentCombo, bestCombo, combosDealt, comboHitsDealt; public double avgCombo;
	public int currentComboTaken, bestComboTaken, combosTaken, comboHitsTaken; public double avgComboTaken;
	public int currentStreak, bestStreak;
	public double advantage, advYouPct, advThemPct;
	// S6, S7
	public int sprintHits, nonSprintHits; public double sprintPct;
	public int crits; public double critPct;
	// S8
	public int intervalCount; public double intervalAvgMs, intervalSdMs;
	public int threeBlockHits; public double threeBlockPct;
	public int[] quality = new int[7];            // by HitQuality.code()
	// S1 (jumpOpportunities = melee hits taken with onGround, derived)
	public int jumpAttempts, jumpSuccess, jumpEarly, jumpLate, jumpOpportunities;
	public double jumpPct, jumpAvgDeltaMs;        // jumpAvgDeltaMs over non-early attempts, game ms
	// taken, trades, damage, confirmation
	public int hitsTaken, projectileHitsTaken; public double takenReachAvg;
	public int trades, tradesWon, tradesLost, tradesEven; public double tradeWinPct, avgMomentumPct; public int tradesWithMomentum;
	public boolean damageKnown; public double damageDealt, damageTaken;
	public int hitsConfirmed; public double confirmPct;
	public int piercingSwings;
}
```

### 4.8 `StatsTotals` (session and lifetime) ★

```java
public final class StatsTotals {
	public long sinceEpochMs;
	public int fights, wins, losses, draws; public long durationMs;
	public int swings, landed, crits, sprintHits, threeBlock, hitsTaken;
	public double reachSum, reachMax; public int reachCount;
	public int jumpAttempts, jumpSuccess, bestCombo, bestStreak;
	public double intervalSum; public int intervalCount;
	public int[] aim = new int[4]; public int[] quality = new int[7];
	public int trades, tradesWon, tradesLost;
	public void add(FightRecord r, FightStats s);
	public double accuracyPct(); public double reachAvg(); public double critPct();
	public double sprintPct(); public double threeBlockPct(); public double jumpPct();
	public double intervalAvgMs(); public double tradeWinPct();
}
```

### 4.9 MC-free fight core (CORE) ★

```java
/** distance = self.distanceTo(opponent): 3-D, feet positions (Combat Report's distanceToSqr).
 *  teleported: the server position jumped more than TELEPORT_BLOCKS since the last tick; healthFrac:
 *  (health + absorption) / max health, NaN when unknown (§5.13a). */
public record OpponentPresence(boolean found, boolean alive, boolean spectator, double distance,
		boolean teleported, float healthFrac) {
	public static final OpponentPresence SYNTHETIC = new OpponentPresence(true, true, false, 3.0);
	public OpponentPresence(boolean found, boolean alive, boolean spectator, double distance); // teleported false, health NaN
}
public record SelfState(boolean dead, boolean spectator, boolean teleported, float healthFrac, boolean inPortal) {
	public SelfState(boolean dead, boolean spectator); // teleported false, health NaN, not in a portal
	public SelfState(boolean dead, boolean spectator, boolean teleported, float healthFrac); // not in a portal
}

/** Every nanos argument is on the fight clock (FightManager subtracts paused time first, §5.13). */
public final class FightStateMachine {
	public enum Phase { IDLE, ACTIVE, ENDING }
	public FightStateMachine(java.util.function.Supplier<KwamsterConfig.Advanced> adv);
	public Phase phase();
	/** Melee hit dealt/taken (authoritative=true; marks `opponent` engaged and makes it current) or a
	 *  miss (false; never retargets, never engages). Ignored while ENDING. Returns true if it opened a fight. */
	public boolean onCombat(PlayerRef opponent, long nanos, boolean authoritative, boolean dealt);
	/** Non-authoritative keep-alive (projectile hit taken from, direct/indirect confirm on, or spear swing at
	 *  an ENGAGED player). Updates lastCombat only. Never opens a fight. Ignored while ENDING. */
	public void onKeepAlive(PlayerRef who, long nanos);
	public boolean isEngaged(UUID who);
	public void onDeath(boolean self, PlayerRef who, long nanos);      // → ENDING (self, or an engaged opponent); first death wins
	public void forceEnding(FightEndReason reason, long nanos);         // → ENDING unless already ENDING; WORLD_CHANGE runs §5.13a first
	/** Evaluates rules 1–8 of §5.13 with rule 5a (§5.13a); returns the reason the fight ends with, or null. */
	public @Nullable FightEndReason tick(long nanos, OpponentPresence p, SelfState self);
	public void onOutcomeHint(boolean win, long nanos, String note);    // §5.13a
	public void noteOpponentHealthVisible(boolean visible);             // §5.9 flag, passed every tick
	public boolean awaitingEvidence(long nanos);                        // an abrupt draw still waiting for a message
	public @Nullable String resultNote();                               // how an inferred WIN or LOSS was decided
	public FightEndReason pendingReason();
	public long endNanos();                  // §5.13
	public PlayerRef opponent();
	public long startNanos(); public long lastCombatNanos(); public long lastDealtNanos(); public long lastTakenNanos();
	public void reset();
	public static FightResult resultOf(FightEndReason r); // OPPONENT_DIED→WIN, SELF_DIED→LOSS, else DRAW
}

public final class FightAccumulator {
	public FightAccumulator(long startNanos, long startEpochMs, PlayerRef self, PlayerRef opponent,
	                        String server, FightRecord.Settings settings, Pos origin);
	/** Keyed by UUID only; adds (not engaged) if new and keeps the first name seen. */
	public int opponentIndex(PlayerRef p);
	public void addSwing(SwingEvent e, @Nullable HitQuality q);   // a landed swing marks its opp engaged
	public void addHitTaken(HitTakenEvent e);              // melee only; marks the attacker engaged
	public void addProjectileHitTaken();
	public void addPiercingSwing();
	public void addJumpReset(JumpResetEvent e);
	public void addTrade(TradeEvent e);
	public void addConfirm(HitConfirmedEvent e);           // direct only; §5.9 matching
	/** FightManager passes only self drops or drops of a player already in opponents (UUID lookup);
	 *  everything else is dropped before this call. Never adds opponents. DamageDrop.opp = −1 for self. */
	public void addDamage(DamageObservedEvent e, int oppIndexOrMinus1);
	public void addPingSample(int ms);
	public int latestPingMs();                             // 0 before the first sample
	public void markHealthVisible();                       // called only for opponent drops
	public FightRecord view();                             // live record (owned; do not mutate outside)
	public FightRecord finish(long endNanos, FightResult result, FightEndReason reason, String modVersion);
}
```

**Sanitising (normative).** Every float or double the accumulator stores (reach, rayReach, aim, charge, positions, yaw, trade damage and momentum, damage amounts) passes through `finiteOr(x, fallback)`: a NaN or infinite value becomes the fallback (0, or −1 for `rayReach`), and the matching `*Known` flag, if any, is set false. Gson is never configured to accept special floats (§2), so this is what keeps every save valid.

**Primary opponent.** Among engaged opponents, the one with the most landed hits plus melee hits taken; ties go to the lowest index. If no opponent is engaged, index 0 (the opening target).

`FightManager` (CORE, MC glue) implements `FightService` and `KwamsterListener`. Its behaviour is fully specified in §5.13–5.14.

### 4.10 `TestHooks` (CORE)

```java
/** Every method throws IllegalStateException unless Minecraft.getInstance().isSameThread():
 *  the gametest calls it only inside ctx.runOnClient(mc -> …) (R2c). */
public final class TestHooks {
	/** Starts a fight against a synthetic opponent standing 3 blocks ahead. Presence is always SYNTHETIC; the opponent counts as engaged. */
	public PlayerRef beginSyntheticFight(String opponentName);
	public void swing(boolean landed, double reach, boolean charged, boolean crit, boolean sprint, double aimH, double aimV);
	public void swing(boolean landed, double reach, boolean charged, boolean crit, boolean sprint, double aimH, double aimV, boolean intoImmunity);
	public void hitTaken(double attackerReach, boolean selfOnGround);
	public void jumpReset(int deltaTicks);                // success per the fight's settings snapshot (deltaMs = 50·(deltaTicks−1))
	/** WIN → DeathEvent(opponent); LOSS → DeathEvent(self); DRAW → forceEnding(IDLE). Finalised on the next tick. */
	public void endSyntheticFight(FightResult result);
	public void setStoplightOverride(@Nullable StoplightReading r);   public @Nullable StoplightReading stoplightOverride();
	public void setCrosshairOverride(@Nullable CrosshairMode m);      public @Nullable CrosshairMode crosshairOverride();
	/** HudManager calls countHudDraw() once per element it actually draws (not in previews). */
	public void countHudDraw();                                        public int hudDraws();
}
```

DIRECTION's `reading()` returns the stoplight override when it is non-null. `CrosshairStateEval` returns the crosshair override when it is non-null.

---

## 5. Stat definitions and formulas

Unless stated otherwise:
- All per-fight figures come from `StatsCalculator.compute(FightRecord r, int nowRelMs)`, using `r.settings` (the snapshot) for every constant. The live view passes the current relative time; then `durationMs = max(0, nowRel)` (the live record's own `durationMs` is still 0). Finished fights pass `Integer.MAX_VALUE`; then `durationMs = r.durationMs`, every time-dependent figure (advantage, chain timeouts) is evaluated at `durationMs`, and only the `currentCombo`, `currentComboTaken` and `currentStreak` fields are reported as 0.
- Percentages are `100·part/whole`. When `whole = 0` the stored value is 0 and display shows "—".
- Event order within the record is ascending `t`. On ties, hits taken come before swings, which come before jumps; equal-kind ties keep processing order. Because swing `t` is the hook time (§4.2) and packets are processed at the start of a frame before the tick that handles keybinds (client/Minecraft.java:1277 vs. `handleKeybinds` inside `tick`), this order equals the order FightManager processed the events, so live and saved numbers agree.

### 5.1 S1 Jump resets (`JumpResetWatch` ★, CAPTURE)

Inputs:
- **Melee hit taken H.** This is a `handleDamageEvent` on self where `source.getEntity() instanceof Player p && p != self` (and `p` is not an NPC, §5.4), `source.isDirect()` holds, and `!source.is(DamageTypes.THORNS)` (§5.15). Stamped with `tickH = Clock.ticks()` and `tH = Clock.nanos()`, plus `selfOnGround`.
- **Own jump J.** This is a `jumpFromGround` call on `mc.player`. Stamped with `tickJ` and `tJ`.
- **Own jump press P** (second input, so late resets are measurable: a jump pressed after a grounded hit has lifted you off the ground never reaches `jumpFromGround`). CAPTURE samples the rising edge of `LocalPlayer.input.keyPresses.jump()` (the `Input` record `aiStep` used this tick) once per unpaused END_CLIENT_TICK, stamped with `Clock.ticks()`, and feeds it to `JumpResetWatch.onJumpPress`. A press in the same tick as a `jumpFromGround` (or as another press) is the same action and is ignored. Otherwise a press pairs with hits exactly like a jump (same windows), so ticks 1–2 are success, ticks 3–5 late, and the window still closes after `W`; a `jumpFromGround` inside the success window still decides success. A bare press (by construction made in the air) counts as an **early** attempt only before a grounded hit; an airborne hit retires an unpaired press, so presses in mid-air combos do not inflate early attempts, and the latest real jump is then still looked at, so a real early jump counts before an airborne hit even when the key was pressed again after it (mashing). **A press still held at a grounded hit is not early yet.** `LocalPlayer.applyInput` re-reads the key every tick (`jumping = input.keyPresses.jump()`, client/player/LocalPlayer.java:639) and `LivingEntity.aiStep` calls `jumpFromGround` whenever `jumping && onGround() && noJumpDelay == 0`, before `travel` (world/entity/LivingEntity.java:2926–2950; `noJumpDelay` is reset to 0 on every tick the key is up, 2949, so it is 0 after a fresh press). A press made in the air, or on the landing tick itself, and held through landing therefore jumps on the first tick after the hit: a 0 ms reset. `onHitTaken` gets `jumpHeld` (the jump input at the end of the last tick, what the next `aiStep` most likely reads); for an unpaired bare press inside the early window with `selfOnGround && jumpHeld` it opens the window as usual and remembers the press instead of pairing it early, and the jump decides. `JumpResetWatch.onTickEnd(tick)`, called at every unpaused END_CLIENT_TICK after the press sample, turns the remembered press into its early attempt (same deltas, timed by the press) when the first tick after the hit ended with no jump (the key was let go, or the jump could not happen). `JumpEvent` is still posted only for a real `jumpFromGround`.

**Timing basis: client ticks, not wall time.** The two hooks are not tick-aligned. Damage packets are processed by `PacketProcessor.processQueuedPackets()` at the start of a frame, before that frame's ticks run (client/Minecraft.java:1277), while `jumpFromGround` runs inside `Minecraft.tick` (`LocalPlayer.aiStep`). A wall-clock `tJ − tH` therefore measures frame phase (FPS-dependent), and after a stutter Minecraft runs catch-up ticks back to back, compressing several game ticks into a few ms. So the decision uses `Clock.ticks()`, the number of client ticks **started** (incremented at `START_CLIENT_TICK`):
- A hit processed between ticks gets the index of the last tick that ran, n. A jump in the very next tick gets n + 1.
- `deltaTicks = tickJ − tickH`. `deltaTicks ≥ 1` means the jump came after the hit; 1 is the earliest possible reset.
- Game-time delta `deltaMs = 50·(deltaTicks − 1)`: 0 ms = jumped on the first tick after the hit, 50 ms = one tick later, and so on. Early jumps give `deltaMs ≤ −50`.
- The config windows are in this game time, so the default success window 0–80 ms means "first or second tick after the hit", and the attempt window 200 ms means up to five ticks. This is FPS-independent and immune to catch-up ticks.
- `wallMs = round((tJ − tH)/1e6)` is kept for information only (hover text), never for decisions.

State: `lastHit {tick, nanos, attacker, paired, heldPress}`, `lastJump {tick, nanos, real, paired}` (the latest jump input of either kind) and `lastRealJump` (the latest real `jumpFromGround`, often the same object).

Constants: `W = advanced.jumpAttemptMs` (200), `min = jumpWindowMinMs` (0), `max = jumpWindowMaxMs` (80), all game ms.

| Trigger | Rule |
|---|---|
| `onJump(tickJ, tJ)` | If `lastHit` exists and is unpaired and `0 ≤ deltaMs ≤ W` (with `deltaTicks = tickJ − lastHit.tick ≥ 1`), emit an attempt with that `deltaMs` and `success = min ≤ deltaMs ≤ max`. Mark both paired. Otherwise store `lastJump = (tickJ, tJ, unpaired)`. |
| `onHitTaken(tickH, tH, selfOnGround, jumpHeld)` | If `lastJump` is an unpaired bare press and `!selfOnGround`, drop it and look at `lastRealJump` instead. If that jump exists and is unpaired and `−W ≤ deltaMs ≤ −50` (jump before the hit, `deltaTicks = jump.tick − tickH ≤ 0`): when it is a bare press and `selfOnGround && jumpHeld`, store `lastHit = (tickH, tH, attacker, unpaired, heldPress = that press)` and emit nothing; otherwise emit an **early** attempt with `success = false` and mark both paired. Otherwise, **only if `selfOnGround`**, store `lastHit = (tickH, tH, attacker, unpaired)`, replacing any older unpaired hit (which simply had no attempt). A hit taken in the air opens no window: it cannot be reset, and scoring a jump after landing as a "late reset" would be wrong. |
| `onTickEnd(tick)` | If `lastHit` is unpaired, has a `heldPress` and `tick > lastHit.tick`, emit that press's **early** attempt (`deltaTicks = press.tick − lastHit.tick`) and mark both paired. A jump that pairs with the hit first clears `heldPress`. |
| `reset()` | On `WorldLeaveEvent`. |

JumpResetWatch is ★ (R6): it takes `(tick, nanos)` pairs and the three window values as arguments, and never reads a clock itself.

- **Attempted** = attempts emitted. **Successful** = attempts with `success`. `jumpPct = success / attempted`.
- `jumpEarly` = attempts with `deltaTicks ≤ 0`. `jumpLate` = non-early attempts that are not successful (outside `[min, max]`; with `min > 0` this includes "too soon" first-tick jumps, labelled "off-window" in hover text). Every attempt is exactly one of success, early or late.
- `jumpAvgDeltaMs` = mean `deltaMs` over non-early attempts.
- **Opportunities** = melee hits taken while `selfOnGround`, derived by StatsCalculator from `hitsTaken[].onGround` (never stored). Non-early attempts can never exceed opportunities. Shown as context: "5/7 resets · 9 chances · 2 early".
- **Recomputation.** StatsCalculator recomputes `success` from each attempt's `deltaMs` and `record.settings` (the snapshot), so success + early + late always sums to attempts. The event's live `success` only drives the live chat line.
- Bow, crossbow, trident, snowball, egg, wind charge and fishing-rod hits are **excluded**, because their damage source is indirect (`direct = projectile`, `cause = shooter`; `DamageSource.isDirect()` is at DamageSource.java:30). The rod bobber deals `thrown(hook, owner)`.
- Thorns is direct but excluded by key.
- A jump before the opening hit of a fight still counts: the event is decided at the hit, and the fight is already open by then.

### 5.2 S2 Reach

- `reach_i = sqrt(target.getBoundingBox().distanceToSqr(self.getEyePosition()))` at `startAttack` HEAD, on tick positions. This is the distance the server's attack check measures: `ServerboundInteractPacket.isWithinRange` → `Player.isWithinAttackRange` → `AttackRange.isInRange(eye distance to the AABB)` (network/protocol/game/ServerboundInteractPacket.java:88–91; world/entity/player/Player.java:1995–1996; world/item/component/AttackRange.java:118–126). With no `ATTACK_RANGE` component, `entityAttackRange()` is `AttackRange.defaultFor(this)` (world/entity/LivingEntity.java:2118–2121: max = entity interaction range, margin 0), which is the same formula as `isWithinEntityInteractionRange`.
- **`rangeLimit`.** `AttackRange ar = self.entityAttackRange(); rangeLimit = ar.effectiveMaxRange(self) + ar.hitboxMargin()`, which equals `self.entityInteractionRange()` when the active item (`self.getActiveItem()`, the same item vanilla reads) has no component. `customRange = self.getActiveItem().has(DataComponents.ATTACK_RANGE)`. A server can put `ATTACK_RANGE` on any item in 1.21.11 (its codec defaults `hitbox_margin` to 0.3), and the client then picks entities with it (client/player/LocalPlayer.java:1169–1177), so landed reach can exceed 3 blocks. Such hits are recorded but never graded as 3-block hits (§5.9).
- **Landed:** `reachAvg` is the mean over landed swings, `reachMax` the max, `reachCount` the number of landed swings.
- **Missed:** `missReachAvg` and `missReachMax` over missed swings.
- `overReachMisses` = missed swings with `reach > rangeLimit` (the attacked-from-too-far check).
- Misses only exist for swings attributed to a player (§5.4), so empty-air swings carry no distance.
- `rayReach` (eye to the ray entry point) is stored for the viewer only.
- Without an `ATTACK_RANGE` component, the client only picks an entity when the ray entry point is closer than the interaction range (LocalPlayer.java:1180–1207, `filterHitResult(entityHitResult, eye, e)`). Landed reach is therefore always below 3.0 on vanilla attributes and plain items.
- Spear swings are excluded (§5.15).

### 5.3 S3 Aim placement (`AimMath` ★, `AimZones` ★)

- **Hit point P.** For landed hits, `P = ((EntityHitResult) mc.hitResult).getLocation()`. That is vanilla's own ray entry point, computed by `GameRenderer.pick(1.0F)` at Minecraft.java:1840, before `handleKeybinds` at 1882, so it uses the tick-position eye. For misses, P is the point on the look ray closest to the box centre: `P = eye + look·max(0, (C − eye)·look)`, the combat-tracker `placementOnHitbox` approach.
- **Box.** `B = target.getBoundingBox()`, the real pose box (1.8 standing, 1.5 sneaking, 0.6 swimming or crawling). `C` is its centre, `H = B.getYsize()`.
- **Right axis.** Take the horizontal look `f = normalize(look.x, 0, look.z)`. If its length is below 1e-6, use yaw: `f = (−sin yaw, 0, cos yaw)`. Then `right = (−f.z, 0, f.x)`.
- **Projected half-width.** `hw = (B.getXsize()/2)·|right.x| + (B.getZsize()/2)·|right.z|`.
- **Offsets.** `h = ((P − C)·right) / hw` and `v = (P.y − C.y) / (H/2)`.
- **Eye inside the box.** If `B.inflate(1e-4).contains(eye)`, then `h = v = 0`, `eyeInside = true` and the zone is CENTER.
- **Zone (landed only, one label, precedence HIGH > LOW > EDGE > CENTER).** HIGH if `v ≥ 1/3` (top third of the height), else LOW if `v ≤ −1/3` (bottom third), else EDGE if `|h| ≥ 0.5` (outer quarter of the width on either side), else CENTER.
- `aimCenter`, `aimEdge`, `aimHigh` and `aimLow` count landed swings by zone, and always sum to `landed`.

### 5.4 S4 Missed hits

A **swing** is a `startAttack` call that passes all of these, in this order:
1. `mc.player != null && mc.level != null && mc.hitResult != null`
2. `mc.missTime <= 0`
3. `!self.isHandsBusy()`
4. `!self.isSpectator()`
5. `held.isItemEnabled(level.enabledFeatures())`
6. `!self.cannotAttackWithItem(held, 0)`
7. `!held.has(DataComponents.PIERCING_WEAPON)`. A spear posts `PiercingSwingEvent` and is not a swing.
8. Target resolution:
   - `hitResult` is an `EntityHitResult` on a `Player ≠ self` that is not an NPC → that player, on target.
   - Any other `EntityHitResult` → **dropped** (mobs, crystals, armour stands, NPC players).
   - An `EntityHitResult` on a player who is not living → **dropped**, not a miss. Living (`SwingCapture.isLiving`) = `!isRemoved() && !isDeadOrDying()` and not a body whose entity event 3 was seen less than 2 s ago (the same entity instance, `RecentDeaths`; a respawn always arrives as a new instance, so a player who respawns within the window can still be fought). `isAlive()` is deliberately not used: it reads `health > 0`, which is false for a server that hides health as NaN.
   - Otherwise (MISS or BLOCK) → the living, non-spectator, non-NPC `Player ≠ self` whose box centre is within `swingTargetRange` (6) and has the smallest angle to the look vector below `swingTargetConeDeg` (30°).
   - If there is none, the swing is dropped.

   The landed hook re-checks "living" and drops the open swing if it fails, so a click on a corpse never posts a swing and can never open a phantom fight.

Guards 2, 3, 5 and 6 are vanilla's own bail-outs, re-applied (Minecraft.java:1683–1706), plus the spectator and piercing checks.

**NPC filter** (`PlayerRefs.isNpc(Player p)`, CORE; active when `advanced.ignoreNpcs`, default on). PvP hubs use fake `Player` entities (RemotePlayer) as queue NPCs; without this filter every queue click would open, sound, print and save a "fight". A player is an NPC when `mc.getConnection() == null`, or `mc.getConnection().getPlayerInfo(p.getUUID()) == null` (not in the tab-list registry, client/multiplayer/ClientPacketListener.java:2483), or `p.getUUID().version() == 2` (Citizens and most server NPC plugins use v2 UUIDs; real players are v4 online or v3 offline). NPCs are treated exactly like non-player entities everywhere: never a swing target, landed target, attacker, death, damage-drop subject, stoplight target or crosshair ON_PLAYER target. The local player is never tested.

- **Landed** = the swing reached `MultiPlayerGameMode.attack` while the local game mode is not spectator, i.e. vanilla will call `player.attack` (§6.2).
- **NO-REG** (`intoImmunity`) = landed, and `NoRegEstimator` (★, CAPTURE) judges the target still in server hurt immunity at the landed hook. The server ignores such a hit unless it out-damages the previous one, and never broadcasts a damage event for it (`LivingEntity.hurtServer`, world/entity/LivingEntity.java:1194–1196). The client's copy of `invulnerableTime` is set to 20 only when the damage event comes back, a round trip after the server restarted its timer (1940; counted down for remote entities, 455–456), so compared raw with 10 it misflags charged hits 10–12 ticks apart at ordinary ping and misses spam clicks inside one round trip. The estimator therefore uses, with `I = advanced.noRegImmunityTicks` (10; 0 disables detection):
  1. **Primary rule (your own hits, latency cancels out).** Per victim UUID, remember `Clock.ticks()` of your last landed swing that was judged registered. Flag when `tick − anchor < I`. Two swings in the same tick give a gap of 0 and the second is flagged. Only an unflagged swing becomes the new anchor.
  2. **Secondary rule (someone else's damage).** Only when the victim's last damage event was **not** your own melee hit coming back from the anchor (another player, your projectile, environment, thorns, and own melee damage that no anchor explains: a sweep that caught a bystander you never hit, since `Player.attack` builds one direct `player_attack` source and `doSweepAttack` reuses it, world/entity/player/Player.java:955 and 1136–1149, or own damage arriving more than `lagTicks(ping) + 6` ticks after the anchor; every damage event on another player feeds `NoRegEstimator.onDamage(victim, ownMelee, tick, ping)`), flag when `(20 − clientInvulnerableTime) + 1 + round(ping/50) < I`.
  - NO-REG hits **count** in swings, landed, accuracy, reach, aim, `quality[]`, crits and sprint hits (S2–S4, S6, S7), and in `immuneHits`.
  - They do **not** increment the combo or the streak, and do not break them either. They are excluded from S8 intervals and from the advantage `d` count. They play no tier sound, raise no crosshair flash, and show a grey "NO-REG" indicator.
  - This stops spam clicks into hurt immunity (and every click on 1.8-style servers, where every hit counts as charged) from inflating combos to the CPS.
- `misses = swings − landed`. `accuracyPct = landed / swings`.
- Landed means the client predicted it. Server confirmation is separate (§5.9).
- Clicks under cooldown are real swings: they count, and become WEAK hits or misses.
- An entity hit outside an item's `ATTACK_RANGE` never reaches `gameMode.attack` (Minecraft.java:1719), so it becomes a miss automatically.

### 5.5 S5 Momentum: combos, streak, advantage

Merge landed swings that are not NO-REG (D) and melee hits taken (K) in record order (§5 intro). NO-REG swings are skipped entirely here: they neither extend nor close a chain.
- On D: the dealt chain increments. If the dealt chain was idle, a new chain starts.
- On K: the dealt chain **closes** (combo = "your landed hits in a row before the opponent hits you back") and the taken chain increments.
- Symmetric for the taken chain.
- **Timeout.** A chain also closes when `comboTimeoutMs > 0` and the next event of its own kind arrives more than `comboTimeoutMs` after its last one. Live, `currentCombo` is 0 if `now − lastD > comboTimeoutMs`.
- A fight end closes both chains.
- Hits on different opponents in the same fight share one chain (1v1 is the design target; see §5.14).

Combo figures:
- `bestCombo` = longest dealt chain, including the current one.
- `combosDealt` = closed chains with length ≥ 2.
- `comboHitsDealt` = the sum of their lengths.
- `avgCombo = comboHitsDealt / combosDealt`.
- The same set exists for taken chains.

**Hit streak.** Consecutive landed swings with no missed swing in between. Hits taken do not break it; NO-REG hits neither count nor break it. `bestStreak` is the maximum. `currentStreak` is the live value.

**Advantage** (`AdvantageModel` ★) at relative time t, with `W = advantageWindowMs` (3000):
```
d = |{landed non-NO-REG swings with t−W < t_i ≤ t}|        k = |{melee hits taken with t−W < t_i ≤ t}|
exch = (d − k) / max(d + k, 1)
c = currentCombo(t) ≥ 1 && currentComboTaken(t) == 0 ?  +min(currentCombo(t), 3)/3
  : currentComboTaken(t) ≥ 1 && currentCombo(t) == 0 ?  −min(currentComboTaken(t), 3)/3
  : 0
A(t) = clamp(0.5·c + 0.5·exch, −1, 1)
```
- **Label:** "YOU" if `A ≥ 0.15`, "THEM" if `A ≤ −0.15`, else "EVEN".
- **Fight summary:** `advYouPct` and `advThemPct` are the share of samples at `t = 0, 100, 200, …, ≤ durationMs` (so `n = floor(durationMs/100) + 1 ≥ 1`, never a division by zero) where the label is YOU or THEM. Live, `durationMs = max(0, nowRel)`.
- `advantage` = `A(nowRel)` live, or `A(durationMs)` final, evaluated with the chain state as of that time (the "report current combo as 0" rule for finished fights applies only to the `currentCombo`/`currentComboTaken`/`currentStreak` fields, never inside `A`).

### 5.6 S6 Sprint hits

- **Definition (README states it): a sprint hit is vanilla's extra-knockback hit, charged AND sprinting.** `sprintHits` = landed swings with `charged && sprint`, where `sprint` is the raw `isSprinting()` read at the landed hook (before vanilla's own `setSprinting(false)` inside `player.attack`). Vanilla grants the sprint knockback only when charged (`isSprinting() && charge > 0.9`), so an uncharged hit while sprinting is not a sprint hit, and spam-clicking while sprinting does not inflate the count.
- Because a crit requires `!isSprinting()`, `sprintHits == quality[THREE_BLOCK_SPRINT] + quality[SPRINT]` exactly, so the panel's Sprint row, the tier bars and the viewer always agree.
- `nonSprintHits = landed − sprintHits`. `sprintPct = sprintHits / landed`.

### 5.7 S7 Crits

- `crit = charged && canCriticalAttack(target)`, verbatim from Player.java:1030–1038: `fallDistance > 0 && !onGround && !onClimbable && !isInWater && !isMobilityRestricted && !isPassenger && target instanceof LivingEntity && !isSprinting`.
- `crits` = landed swings with `crit`. `critPct = crits / landed` (over **all** landed hits, WEAK included). Rationale: the figure answers "what share of the hits you landed were crits"; an uncharged click is a real hit that could have been a charged crit, so it belongs in the denominator. The README states this.

### 5.8 S8 Hit timing and the "perfect" flag

- **Interval.** Over landed swings that are not NO-REG, in record order: for consecutive such swings i−1, i on the **same `opp` index**, `Δ = (t_i − clickLeadMs_i) − (t_{i−1} − clickLeadMs_{i−1})`. Only `Δ ≤ intervalMaxGapMs` is kept.
- `intervalAvgMs` = mean, `intervalSdMs` = population SD, `intervalCount` = n.
- `t − clickLeadMs` is the click press time (frame-accurate) when `ClickTimestamps.claim()` returned a press no older than 200 ms (`clickTimed = true`). Otherwise `clickLeadMs = 0` and the hook time is used. Only intervals use the click time; everything else uses `t`.
- **Perfect = a 3-block hit:** `quality.threeBlock()`. `threeBlockHits` and `threeBlockPct = threeBlockHits / landed`.

### 5.9 Hit quality, confirmation and damage

`HitGrader.grade(boolean charged, boolean crit, boolean sprint, double reach, double threeBlockReach, boolean customRange)` ★, called by FightManager with `record.settings.threeBlockReach` (the snapshot):

```
if (!charged) return WEAK;
boolean three = !customRange && reach >= threeBlockReach - 1e-6;   // custom ATTACK_RANGE: never a 3-block hit
if (crit)   return three ? THREE_BLOCK_CRIT : CRIT;     // crit implies !sprinting
if (sprint) return three ? THREE_BLOCK_SPRINT : SPRINT;
return three ? THREE_BLOCK : NORMAL;                    // sweeps and plain charged hits
```

- `quality[]` counts landed swings by tier and sums to `landed`.
- **Confirmed.** A direct `HitConfirmedEvent` (never THORNS: `DamageSources.thorns(e)` builds a *direct* source caused by the thorns wearer, world/damagesource/DamageSources.java:247–249, so it is excluded at the routing step) for victim V at time tc marks the **earliest unconfirmed landed** swing on V with `tc − (latestPingMs + 300) ≤ t ≤ tc` as `confirmed`. Under ping the hit that registered is the earliest one past immunity, not the latest spam click. NO-REG swings are eligible (the client's immunity estimate can be wrong).
- A confirmation with no matching swing (your sweep damage on a secondary victim, a spear stab, a hit from before the fight) is dropped and not counted.
- An indirect `HitConfirmedEvent` (your projectile) is keep-alive only and never matched.
- **Why this works.** The server broadcasts a damage event only for real damage outside i-frames and not blocked by a shield (LivingEntity.java `hurtServer`: `broadcastDamageEvent` sits inside `if (bl2)` and the shield branch). `confirmPct = hitsConfirmed / landed`.
- **Damage.** This applies only if `opponentHealthVisible`, meaning at least one health drop was observed on an opponent:
  - `damageDealt` = sum of opponent drops with `−100 ≤ t_drop − t_hit ≤ 900` ms after one of your landed hits on that opponent. Each drop is counted once.
  - `damageTaken` = the same over own drops after melee hits taken.
  - Window constants: `DAMAGE_LINK_BACK_MS = 100`, forward 900 ms (combat-report `DAMAGE_LINK_MAX_MS`).

### 5.10 Trades (ported from Combat Report)

`TradeWatch` (CAPTURE) pairs one hit dealt and one melee hit taken, between the same two players, within `tradeWindowMs` (400). Each hit is spent once.

- **Momentum** is sampled 3 ticks after the later hit: own velocity (position delta from `VelocityWatch`) projected on the horizontal you→opponent axis, as a percentage of sprint speed.
- **Fix.** The reference is `5.612 · (attr > 0 ? attr / 0.1 : 1.0) / (self.isSprinting() ? 1.3 : 1.0)` with `attr = MOVEMENT_SPEED`. The `> 0` guard is Combat Report's (TradeWatch.java:211–212); without it high Slowness gives Infinity/NaN. Combat Report understated momentum by 1.3× while sprinting. A non-finite result sets `momentumKnown = false` (and is sanitised again in the accumulator, §4.9).
- **Timestamp.** `TradeEvent.nanos` = the later hit's time (§4.2).
- **Damage** is read 24 ticks later from `HealthWatch` (link window `min(900, 500 + ping)` forward, 100 back). `damageKnown = dealt > 0`.
- **Outcome.** For trades with `damageKnown`: won if `dealt > taken`, lost if `<`, even if `=`. `tradeWinPct = won / (won + lost)`. `avgMomentumPct` is the mean over `momentumKnown` trades.
- `CaptureControl.flushPending()` settles pending trades immediately. FightManager calls it before finalising.

### 5.11 S12 Direction stoplight (`RadialFilter` ★, DIRECTION)

**Target.** The current fight opponent (`fights().currentOpponent()`, looked up by UUID in `mc.level.players()`). If there is none and `target == FIGHT_OR_AIMED`, use `mc.crosshairPickEntity` if it is a non-NPC player; note this is only non-null within attack reach (§5.12), so in practice the next rule is the real path: the nearest non-spectator, non-NPC living player within `aimedRange` and inside a 30° cone of the look vector. If there is still none, the reading is `NONE` (state OFF).

Per client tick, with opponent position O and own position M (horizontal x and z only):
```
raw   = dot((O − O_prev), normalize(M − O)) · 20      // b/s, + = moving toward you
if |O − O_prev|² > 16 (teleport, pearl, respawn) or target changed or entity id changed:
      reseed: ema = 0, state = YELLOW, sinceNanos = now, skip 2 ticks
if kbHold active (opponent hurtTime rose this tick, i.e. hurtTime > prevHurtTime || hurtTime >= hurtDuration > 0,
      the direction-indicator freshHit test) → hold the current state for kbHoldMs, do not update ema,
      reseed ema = raw when the hold ends
alpha = smoothingMs == 0 ? 1 : 1 − exp(−50 / smoothingMs)
ema  += alpha · (raw − ema)
enter = enterBps, exit = max(0, enterBps − hysteresisBps)
candidate =  GREEN  if ema ≥ enter  || (state == GREEN && ema ≥ exit)
            RED    if ema ≤ −enter || (state == RED   && ema ≤ −exit)
            YELLOW otherwise
switch to the candidate only if now − sinceNanos ≥ minHoldMs (then sinceNanos = now)
```

- Strafing and standing still are YELLOW. Strafing at a slant counts its radial component.
- The output is `StoplightReading(state, ema, holding, target, sinceNanos)`.
- Own movement is irrelevant by design: the light shows the **opponent's** intent.

### 5.11a S12 Above-head direction bars and the jump cue (`HeadBarMotion` ★, DIRECTION)

A faithful port of builtdoor's Direction Indicator 1.2.1 (`DirectionIndicatorClient`, `IndicatorRenderer`); the user asked for it "exactly like Direction Indicator". It is the default S12 display; the HUD stoplight of §5.11 is opt-in.

Per client tick (bus `onClientTick`, skipped while paused), for every player in `level.players()`, keyed by entity id (a player not seen this tick is dropped, so a returning player is reseeded):
```
first sight: seed last position, last height, onGround, prevHurtTime; no output
dx, dz   = position − last; if dx² + dz² > 16 (teleport) → dx = dz = 0
forward  = dx · −sin(yaw) + dz · cos(yaw)          // yaw = getYRot(), not body rotation
speed   += 0.45 · (forward − speed)
freshHit = hurtTime > 0 && (hurtTime > prevHurtTime || hurtTime ≥ hurtDuration)
landed (onGround && !lastOnGround) → kbTicks = 0
if jumpKnockbackTicks > 0 && onGround && freshHit → kbTicks = jumpKnockbackTicks, else if kbTicks > 0 → kbTicks−−
cooldown−− (if > 0)
if onGround → confirm = 0; else if lastOnGround → confirm = 4, takeoffY = lastY
if confirm > 0: if y − takeoffY ≥ 0.1 → jumped = cooldown == 0 && kbTicks == 0; cooldown = 6; confirm = 0
                else confirm−−
heading  = |speed| < moveThreshold/1000 ? IDLE : speed > 0 ? FORWARD : BACKWARD
```
- **Cue.** `jumped && jumpSoundEnabled && inRange && (player ≠ self || jumpSoundForSelf)` → `level.playLocalSound(x, y, z, kwamster:jump_cue, PLAYERS, volume/100, pitch/100, false)`. The sound is declared in `sounds.json` only (not registered), and the file is mono so it is attenuated by position.
- **Bars.** `WorldRenderEvents.END_MAIN` (inside the main pass, before `endBatch`; `BEFORE_DEBUG_RENDER` runs in the extraction region in fabric-rendering-v1 16.2.x). For every player with `headBarEnabled`, `inRange` and (`player ≠ self` or `headBarForSelf` with a detached camera): a backdrop quad (half size + 0.022 border) and a fill quad pulled 2 mm toward the camera, built from the camera's up and right vectors at the lerped position + interpolated hitbox height + `headClearance`, in `RenderTypes.debugQuads()` (translucent, no depth write, no culling, `sortOnUpload`). Hidden with F1.
- **inRange.** Not spectating, not removed, not `isDeadOrDying()` (not `isAlive()`, which rejects NaN health), and within `radius` of you. NPCs are not filtered, as in Direction Indicator.
- **R4.** With the master switch off nothing is tracked, drawn or played; with both the bars and the cue off the tracker keeps no state.

### 5.12 S15 Crosshair state (`CrosshairStateEval`, CROSSHAIR)

```
p = mc.player; e = mc.crosshairPickEntity
onTarget  = e != null && e != p && e.isAlive() && ((e instanceof Player pl && !pl.isSpectator() && !PlayerRefs.isNpc(pl))
                                      || (targetMobs && e instanceof LivingEntity))
charged   = p.getAttackStrengthScale(0.5F) > 0.9F                     // what Player.attack reads
critCond  = p.fallDistance > 0 && !p.onGround() && !p.onClimbable() && !p.isInWater()
            && !p.isMobilityRestricted() && !p.isPassenger() && !p.isSprinting()   // Player.java:1030
critReady = charged && critCond
mode = critReady && onTarget ? CRIT_READY_ON_PLAYER : onTarget ? ON_PLAYER : critReady ? CRIT_READY : DEFAULT
```

- **ON_PLAYER means "a player in attack reach".** `mc.crosshairPickEntity` is null beyond `entityInteractionRange` because `filterHitResult(entityHitResult, eye, e)` turns a farther entity hit into a MISS (client/player/LocalPlayer.java:1206–1207). So the "on a player" colour is also a "you can hit them now" signal. The README and the Cloth label say so.
- `fallDistance` only grows while descending, so the crit colour appears on the way down, as Kwamix asked ("falling in the air").
- **Hit flash.** If `hitFlash` is on and `fights().lastResolved()` is a landed, non-NO-REG hit whose age (`now − HitResolvedEvent.nanos`) is below `hitFlashMs` and whose tier is `enabled`, the colour is that tier's ARGB with alpha forced to 0xFF, and the gap widens by `+2·(1 − age/hitFlashMs)` px (rounded), so the flash reads even when the tier colour equals the mode colour.

### 5.13 Fight lifecycle (`FightStateMachine` ★ + `FightManager`)

**Fight clock** (FightManager delegates the arithmetic to ★ `FightClock`). FightManager keeps `pausedTotalNanos`: while ACTIVE or ENDING and `mc.isPaused()`, it notes the pause start on the first paused tick and adds `now − pauseStart` on the first unpaused tick. Every incoming event's `nanos`, and `now`, are converted with `fight(n) = n − pausedTotalNanos` before they reach the state machine or the accumulator, so both see one clock and every `t` and `durationMs` excludes singleplayer pauses. End rules are not evaluated while paused. `pausedTotalNanos` resets to 0 when a fight opens. (There is no per-object timestamp shifting.)

**Start.** The first `SwingEvent` (landed or missed) or melee `HitTakenEvent` while IDLE.
- `startNanos` = that event's nanos (hook time for a swing).
- `startEpochMs = Clock.epochMs() − (Clock.nanos() − startNanos)/1e6`.
- Origin = own position at that event.
- `opponent` = the target or attacker. It is **engaged** if the opening event was a landed hit or a melee hit taken; a fight opened by a miss has a provisional opponent until an authoritative event engages someone.
- The settings snapshot is taken (§3.9).

**Keep-alive.**
- Missed swing: `onCombat(..., authoritative=false)`. It updates `lastCombat` but never retargets or engages.
- Landed hit (NO-REG ones included: the client did hit them) or melee hit taken: `authoritative=true`. It updates `lastCombat`, `lastDealt` or `lastTaken`, engages that player, and makes them the current opponent.
- Non-authoritative keep-alive (`onKeepAlive`, updates `lastCombat` only, never opens a fight), each only for an **engaged** player:
  - a projectile hit taken from them (`projectileHitsTaken++`);
  - a `HitConfirmedEvent` on them, direct or indirect (your arrows keep a bow exchange alive);
  - a `PiercingSwingEvent` whose cone-resolved target is them (spear exchanges stay alive; `piercingSwings++`).
- A projectile hit from a player who is not engaged in the current fight is ignored entirely: not counted, no keep-alive, never opens a fight.

**Per-tick order in `FightManager.onClientTick`** (core listener, first in `postTick`, which is not a dispatch, §4.3). Steps 1 and 6 run in every phase (IDLE included); steps 2–5 run only in the phase they name. Before step 1, the fight clock's pause bookkeeping runs (above).
1. **Identity tracking.** Read `mc.level`, `mc.player` and `mc.getConnection()`. Compute `disconnected = disconnectPending.getAndSet(false) || (lastLevel != null && (mc.level == null || mc.getConnection() == null))`. Compute `worldChanged = !disconnected && lastLevel != null && mc.level != null && (mc.level != lastLevel || mc.player != lastPlayer || (mc.player != null && mc.player.getId() != lastPlayerId))`. A same-dimension respawn keeps the same `ClientLevel` (a new one is built only when the dimension key changes, client/multiplayer/ClientPacketListener.java:1235–1258) but always replaces `mc.player`, which is why the player instance is compared too.
2. If the phase is ENDING: finalise now with the pending reason (a death seen before the disconnect or world change wins). Exception: while `awaitingEvidence(fight(now))` (an abrupt draw waiting for a kill or win message, §5.13a) it does nothing, unless `disconnected` or `!general.enabled`. Go to step 6.
3. If ACTIVE, the first matching forced reason calls `forceEnding(reason, fight(now))` and finalises now with the pending reason (WORLD_CHANGE goes through §5.13a first and may become a WIN, a LOSS, or a draw that waits for a message):
   1. `disconnected` → DISCONNECT
   2. `worldChanged` → WORLD_CHANGE
   3. `!general.enabled` → DISABLED (finalised and saved silently, R4)

   (GAME_EXIT never comes from the tick: it is forced by the `CLIENT_STOPPING` handler, §2.1.)
4. If still ACTIVE and not paused: pass `noteOpponentHealthVisible(record.opponentHealthVisible)`, build `OpponentPresence` for the current opponent and `SelfState` (each with its teleport flag and health fraction, §5.13a), and call `tick(fight(now), presence, self)`, which evaluates rules 1–8 below. A non-null reason finalises now unless `awaitingEvidence`.
5. While ACTIVE: ping sampling (below).
6. If `disconnected`, post `WorldLeaveEvent(DISCONNECT)`; else if `worldChanged`, post `WorldLeaveEvent(WORLD_CHANGE)`. This happens **after** any finalisation in this tick, so CAPTURE's `onWorldLeave` resets `HealthWatch`, `TradeWatch` and `JumpResetWatch` only after `flushPending` has run. Then store `lastLevel = mc.level`, `lastPlayer = mc.player`, `lastPlayerId`.

`FightManager.markDisconnectPending()` is the only method that may be called off-thread; it writes an `AtomicBoolean` and nothing else (R2a). END_CLIENT_TICK still fires after a disconnect because `Minecraft.tick` runs with no level, so the fight is always finished on the next tick.

**End rules** evaluated by `FightStateMachine.tick` (first matching row wins, in table order; "engaged" per §5.14):

| # | Condition | Reason |
|---|---|---|
| 1 | A `DeathEvent` (entity event 3) for an **engaged** opponent was processed (`onDeath`) | OPPONENT_DIED |
| 2 | A `DeathEvent` for self, or `self.dead` (`isDeadOrDying()` or `mc.screen instanceof DeathScreen`) | SELF_DIED |
| 3 | The current opponent is engaged and found with `isRemoved()` or `isDeadOrDying()` (the CAPTURE "living" predicate, §5.4; `isAlive()` is not used because it reads `health > 0`, false for NaN-hidden health. Best-effort: these read synced health, which some servers spoof; the authoritative signal is rule 1's `handleEntityEvent(3)`. `Pose.DYING` is **not** used: only `LivingEntity.die` sets it (world/entity/LivingEntity.java:1451) and `ServerPlayer.die` never calls `super`, server/level/ServerPlayer.java:862–916) | OPPONENT_DIED |
| 4 | The current opponent is engaged, `isSpectator()`, and `now − lastDealt ≤ deathInferenceMs` | OPPONENT_DIED |
| 5 | Self `isSpectator()` and `now − lastTaken ≤ deathInferenceMs` | SELF_DIED |
| 5a | `inferOutcomes` and an abrupt end right after a hit (§5.13a): you teleported, or the engaged opponent vanished or teleported | OPPONENT_DIED or SELF_DIED |
| 6 | The current opponent is not in `mc.level.players()` (logged off, untracked), or is a spectator outside rule 4, or is not engaged and dead | OPPONENT_LEFT |
| 7 | `presence.distance > fightMaxDistance` (3-D feet distance, `self.distanceTo(opponent)`) | OPPONENT_FAR |
| 8 | `now − lastCombat > fightIdleMs` (strictly greater) | IDLE |

Rules 6 and 7, and a WORLD_CHANGE that §5.13a leaves undecided, are **abrupt draws**: within `outcomeWindowMs` of the last combat they wait `EVIDENCE_GRACE_MS` (1000 ms) in ENDING for a kill or win message (§5.13a). Rule 8 never waits.

Forced reasons (FightManager, steps 3 and §2.1): DISCONNECT, WORLD_CHANGE, DISABLED, GAME_EXIT.

**Result.** `resultOf(reason)`: OPPONENT_DIED → WIN, SELF_DIED → LOSS, everything else → DRAW. If both players die, the first death processed decides; later deaths are ignored.

**Duration.** `endNanos` is the death time for the two death reasons seen through a death event or rules 2–5, otherwise `lastCombatNanos` (inferred results of §5.13a included). Like Combat Report, dead air is not charged. `durationMs = max(0, (endNanos − startNanos)/1e6)` on the fight clock.

**ENDING phase.** Death events and `endSyntheticFight` set ENDING; the fight is finalised on the next client tick (step 2), so ENDING lasts one tick, or at most `EVIDENCE_GRACE_MS` for an abrupt draw (§5.13a). While ENDING:
- **Ignored** (and never allowed to open a new fight before finalisation): `SwingEvent`, `HitTakenEvent` (melee and projectile), `PiercingSwingEvent`, further `DeathEvent`s.
- **Still accumulated**: `TradeEvent` with `t ≤ endNanos` (its nanos is the later hit's time), every direct `HitConfirmedEvent` and `DamageObservedEvent` (they refer back to earlier hits; the killing blow's confirmation usually arrives with the death, and its health drop is captured by the extra sample in finalisation step 1), and `JumpResetEvent` whose `hitNanos ≤ endNanos`.

**Finalisation** (`finalizeNow(reason)`). It never runs inside a bus dispatch: only from step 2–4 of `onClientTick` (not a dispatch, §4.3) or from the `CLIENT_STOPPING` handler. It runs, in order:
1. `services().capture().flushPending()`. It first takes an extra health sample (`HealthWatch.sampleNow`, no retention tick; skipped while `general.enabled` is off, so a DISABLED finalisation posts no capture events), so the killing blow's `DamageObservedEvent` is accumulated before `acc.finish` and `TradeWatch.settle` sees the drop (a second sample later in the same tick finds no further drop). Then it settles trades. Its events dispatch synchronously into FightManager, which is still ENDING and accepts them (above). It tolerates `mc.level == null`.
2. `accumulator.finish(...)`, `stats = StatsCalculator.compute(record, MAX)`, `record.stats = stats`.
3. `alert = (record.swings.size() + record.hitsTaken.size()) ≥ minFightEvents || result != DRAW`. A decisive fight (a two-hit kill, a one-shot) always alerts.
4. If `alert`, `session.add(record, stats)`.
5. `bus.post(new FightEndedEvent(record, stats, alert))`. RECORD saves (regardless of `enabled`, so a DISABLED fight is saved), FEEDBACK prints (only if `general.enabled` and `mc.player != null`), the HUD shows the card (only if `general.enabled` and `mc.level != null`).
6. Reset to IDLE.

**Ping.** Sampled once per second from `mc.getConnection().getPlayerInfo(self.getUUID()).getLatency()` while ACTIVE (skipped when the connection is null).

**Server label.**
- `mc.hasSingleplayerServer()` → "Singleplayer".
- Otherwise `mc.getCurrentServer().ip`, with any `:port` stripped and lower-cased. Unknown → "".

### 5.13a Results without a death event (practice and duel servers)

Practice and duel servers usually cancel the killing blow and teleport the loser to spawn (or another world) instead of letting them die, so no entity event 3 arrives and rules 1–5 never fire. Without this section such a fight ends through rule 6 (the loser vanished), rule 7 (they are far away now) or WORLD_CHANGE, all draws. Two signals decide it instead. Both reuse the OPPONENT_DIED and SELF_DIED reasons, so payload v1 and the viewer are unchanged; `FightRecord.resultNote` (local only, never in a share link) says how the result was decided, and the chat summary's hover shows it.

**Inference** (`advanced.inferOutcomes`, rule 5a). Health samples: every tick `tick()` stores the opponent's and your `healthFrac` (128 slots, enough for the 5 s maximum window). "Low" means the minimum finite sample within `outcomeWindowMs` is at most `LOW_HEALTH` = 0.5; the minimum, not the latest sample, because servers reset health on the same tick as the teleport. Your hit is "last" when `lastDealt > lastTaken`, the current opponent is engaged, and `now − lastDealt ≤ outcomeWindowMs`.
- **The engaged opponent vanished** (not in `mc.level.players()`) **or teleported** (server position, i.e. the interpolation target, jumped more than `TELEPORT_BLOCKS` = 64 since the last tick, farther than an ender pearl usually goes) while you did not move: OPPONENT_DIED if your hit is last and their death is plausible: their health is hidden (`opponentHealthVisible` false, §5.9) or was low.
- **You teleported** (more than 64 blocks in one tick), or a **WORLD_CHANGE** arrives (respawn, dimension or server switch): SELF_DIED if your health was low, they hit you within the window, and either their hit is last or their visible health stayed above 0.5 the whole window; else OPPONENT_DIED if your health was not low, your hit is last and their death is plausible; else undecided. A cancelled killing blow sends no damage event, so their final hit is invisible: with both players low and your hit last it stays undecided instead of guessing WIN.
- Travel is not a death: neither self rule fires within `PORTAL_GRACE_MS` (2000 ms) of a tick on which you stood in a nether portal, end portal or end gateway block (`SelfState.inPortal`, checked at your feet and eyes), so escaping through a portal at low health stays a draw.
- Invisibility is not a signal (an invisibility potion would read as a death).

**Messages** (`advanced.readOutcomeMessages`). CAPTURE's `OutcomeWatch` reads system and action-bar messages (`ClientReceiveMessageEvents.GAME`) and titles and subtitles (`GuiTitleMixin`) while a fight runs; signed player chat never reaches it. `OutcomeText.classify(text, selfName, opponentNames, chatLine)` returns WIN or LOSS (plus the opponent named as the one who died or lost) for:
- kill messages: the victim is named before a death verb in the passive form ("Kwamix was slain by builtdoor", "Kwamix died", "Kwamix fell into the void whilst fighting builtdoor") and after it in the active form ("builtdoor eliminated Kwamix"). Kill verbs are a fixed list that leaves out hit verbs ("shot", "smashed"), which some servers use for hit feedback; any "&lt;victim&gt; was … by &lt;killer&gt;" that names you and an opponent also counts, minus non-kill verbs ("hit", "tagged", "challenged");
- winner announcements: "Winner: X", "Loser: X", "X won the duel", "You win", "You lost the match", "Round 1 won by X"; "won 50 coins", "win streak" and "lost connection" are not results;
- one-word titles: VICTORY / WINNER, DEFEAT / ELIMINATED.

A line spoken by you or an opponent (up to four words, then `:`, `»` or `>`, whose last word is your or their name) is ignored, because many servers send player chat as system messages; a title is never treated as chat. A message about players outside the fight decides nothing. FightManager ignores a hint that names an opponent who is not engaged. While ACTIVE a hint ends the fight with its result (a WIN needs an engaged opponent); during an abrupt draw's evidence wait it replaces the draw; otherwise (after a death, or once the wait ran out) it is ignored.

### 5.14 Multiple players and opponent switching

- **Engaged opponent** = a player with at least one authoritative event in this fight: a landed hit on them (NO-REG included) or a melee hit taken from them. `FightRecord.opponents` lists everyone referenced by any event (a miss at a passer-by in the cone adds them, because `Swing.opp` is required); `FightRecord.engaged` marks who is engaged. The fight-opening miss target is provisional until engaged.
- The fight tracks the **current** opponent. Only authoritative events retarget; a stray miss at a passer-by never moves the fight (Combat Report rule).
- Restricted to engaged opponents: the death-ends-as-WIN rules (1, 3, 4), projectile and confirmation keep-alive, the primary-opponent choice (§4.9), and the share link's opponent table (§9.3). A bystander who was only whiffed at and then dies to someone else ends the fight as OPPONENT_LEFT (DRAW), never as a WIN.
- The **primary** opponent (`opponentName` and `opponentUuid`) is the engaged one with the most landed hits plus hits taken, with ties going to the lowest index; index 0 if nobody is engaged.
- Stats are computed over all events in the fight. The distance and presence rules use the current opponent.
- 1v2 fights therefore show up as one fight with two engaged names. Saved fights and the viewer list every engaged name (payload MULTI table, §9.3), and the replay draws one disc per opponent.

### 5.15 Edge cases (normative)

| Case | Rule |
|---|---|
| Spears (`PIERCING_WEAPON`) | `PiercingSwingEvent` only; counted in `piercingSwings` and used as keep-alive for an engaged cone target. Excluded from swings, reach, accuracy and tiers: the client never learns whether a stab landed (Minecraft.java:1709–1713 routes to `piercingAttack`, which never calls `player.attack`; MultiPlayerGameMode.java:511–516). |
| You are a spectator | No swings (guard 4). `MultiPlayerGameMode.attack` skips `player.attack` (MultiPlayerGameMode.java:430), and the landed hook applies the same check (§6.2). |
| Target is a spectator | Excluded from miss attribution and from the crosshair ON_PLAYER state. |
| Non-player entity under the crosshair | The swing is dropped entirely (crystal PvP does not create misses). |
| Server NPCs (fake players: not in the tab-list registry, or v2 UUID) | With `ignoreNpcs` (default) treated as non-player entities: no swing, no fight, no sound, no chat, no save, no stoplight or crosshair target (§5.4). Checked in the manual checklist on a hub. |
| Hits into hurt immunity (spam clicks, 1.8-style servers) | Flagged NO-REG by `NoRegEstimator` (fewer than `noRegImmunityTicks` ticks after your last registered swing on that player, or inside immunity from other damage judged from the lagged client copy): counted for accuracy, reach, aim and tiers, excluded from combos, streak, intervals and advantage, no sound or flash, grey indicator (§5.4). `noRegImmunityTicks = 0` turns it off for reduced-hit-delay servers. |
| Clicking a dead player's body | An `EntityHitResult` player that is removed, `isDeadOrDying()`, or a body whose entity event 3 was seen less than 2 s ago (same instance, `RecentDeaths`) gives a **dropped** swing, not a miss. The cone target excludes them, the landed hook re-checks, and sourced melee hits taken from such an attacker are dropped (projectiles still pass). No swing is posted, so no phantom fight opens after a kill. |
| Damage events with no source (pre-1.19.4 servers through ViaVersion) | Guarded inference (§6.3), used only while no damage event naming a source has been seen since the last `WorldLeaveEvent`. Approximations (README): fall or cactus damage while a nearby opponent has just swung can count as a hit taken; while you burn (without Fire Resistance) or are poisoned or withered, whose periodic ticks arrive exactly like melee hits, a hit taken also needs a knockback velocity away from the attacker, so a real hit whose velocity packet only arrives after the next tick start is dropped then; damage by a third party that reached the server just before a registered swing of yours can count as your confirmation (at most one per swing); hits whose attacker swing animation is more than 2 ticks old are dropped. |
| Item with a server-set `ATTACK_RANGE` component | `rangeLimit` follows the component; reach is recorded; never graded as a 3-block hit (§5.2, §5.9). |
| Bow, rod, snowball, trident, wind-charge hits on you | `HitTakenEvent(projectile=true)`. Keep-alive only, and only from an engaged opponent. Never a jump-reset hit, combo break, trade, or advantage input. |
| Thorns damage | Ignored both ways: not a hit taken, never a confirmation. |
| Your arrows on others | Indirect source: a `HitConfirmedEvent(direct=false)`, keep-alive only for an engaged opponent, never matched to a swing. |
| Creative mode | `hasMissTime()` is false, so there is no 10-tick miss lockout. Swings still count. |
| Server lag or rubber-banding | Landed is client-predicted. `confirmPct` exposes server rejections. Teleport-sized moves reset the stoplight. |
| Disconnect mid-fight (kick, lost connection, pause-menu Disconnect) | The DISCONNECT callback only sets a flag; the next client tick (with or without a level) finalises as DISCONNECT/DRAW, unless a death was already seen, and saves. Pending trades are flushed first, then `WorldLeaveEvent(DISCONNECT)` is posted. No chat line (no player), no card (no level). |
| Game closed mid-fight | CORE's single `CLIENT_STOPPING` handler finalises GAME_EXIT synchronously (the save is enqueued through the bus), then calls `archive().shutdown()`, which drains the executor for at most 2 s. |
| Respawn, dimension change or server switch | Detected in step 1 of the tick: the level instance **or** the local player instance (or its entity id) changes → WORLD_CHANGE, then `WorldLeaveEvent(WORLD_CHANGE)`. |
| Paused singleplayer | The fight clock excludes paused time (`pausedTotalNanos`); end rules are not evaluated while paused. |
| Master switch turned off mid-fight | Finalised once as DISABLED on the next tick and saved silently (R4). |
| Practice-server "fake deaths" (no death event, instant teleport) | Usually OPPONENT_FAR or OPPONENT_LEFT → DRAW. Spectator switches within `deathInferenceMs` are treated as deaths. A same-dimension respawn of **you** without a death event ends the fight as WORLD_CHANGE (DRAW). Further heuristics are out of scope (§13). |
| Multiple attack clicks in one tick | Each is a swing. Only the first claims the click timestamp; the rest have `clickLeadMs = 0`. |
| Health hidden by the server | `damageKnown = false`, trades without damage are not scored, and damage figures show "—". Health hidden as NaN never reads as dead: CAPTURE and FightManager use `!isRemoved() && !isDeadOrDying()`, not `isAlive()`. |

---

## 6. Mixins and hooks (verified against `.ref/mc`)

Line numbers refer to `kwamster-1.21.11/.ref/mc/...` unless the path starts with `fabric/`. All mixins live in package `kwamster.mixin`. Handler names are prefixed `kwamster$`. Bodies are one-line delegations (R3).

### 6.1 `MinecraftAttackMixin` (CAPTURE) → `net.minecraft.client.Minecraft`

| Injection | Target | Point | Reads / does |
|---|---|---|---|
| `kwamster$swingBegin(CallbackInfoReturnable<Boolean>)` | `startAttack()Z` | `@At("HEAD")` | `CombatCapture.onStartAttackHead((Minecraft)(Object)this)`: claims the click, applies the guards from §5.4, resolves the target, computes reach, ray reach, aim, flags and positions, and opens the swing. |
| `kwamster$swingCommit(CallbackInfoReturnable<Boolean>)` | `startAttack()Z` | `@At("RETURN")` | `CombatCapture.onStartAttackReturn()`: if a swing is open, posts the `SwingEvent` and closes it. RETURN fires at every exit, so a rejected swing never lingers. |

Evidence:
- `private boolean startAttack()` at client/Minecraft.java:1682.
- Guards at 1683–1706.
- Piercing branch at 1709–1713.
- ENTITY → `gameMode.attack` at 1717–1721.
- MISS → `resetAttackStrengthTicker` at 1733–1738.
- Called only from `handleKeybinds` at 2059–2061 (`while (keyAttack.consumeClick()) bl3 |= this.startAttack();`).
- Public fields: `crosshairPickEntity` at 360, `hitResult` at 361, `missTime` at 363.
- `pick(1.0F)` runs at 1840, before `handleKeybinds()` at 1882.

### 6.2 `MultiPlayerGameModeMixin` (CAPTURE) → `net.minecraft.client.multiplayer.MultiPlayerGameMode`

```java
@Inject(method = "attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V",
        at = @At("HEAD"), require = 0)
private void kwamster$landed(Player player, Entity target, CallbackInfo ci) {
	if (!((MultiPlayerGameMode)(Object)this).isSpectator()) {   // reproduces the guard at MultiPlayerGameMode.java:430
		CombatCapture.onGameModeAttack(player, target);
	}
}
```

- **What it does.** Marks the open swing landed (if `target instanceof Player`, not an NPC and living, §5.4; otherwise the open swing is dropped), sets `intoImmunity` from `NoRegEstimator.onLandedSwing(uuid, Clock.ticks(), victim.invulnerableTime, pingMs, advanced.noRegImmunityTicks)` (§5.4), and reclassifies charge/crit/sprint/sweep against the actual entity.
- **Why HEAD with `require = 0`, not the `Player.attack` INVOKE.** Another combat or client mod (the user's Lunar profile already runs several) may `@Redirect` or rewrite that INVOKE, and a required injector would then crash the game at startup. HEAD of an existing method always binds. The charge is just as intact at HEAD: only `ensureHasSentCarriedItem()` and the packet send precede `player.attack`, and `resetAttackStrengthTicker()` comes after it.
- **Binding log.** The first time the handler runs in a session, CAPTURE logs once at INFO "Landed-hit hook active". If it never runs, accuracy reads 0 % and the README's troubleshooting entry points to this line.
- **Evidence.** `attack` at client/multiplayer/MultiPlayerGameMode.java:427. `ensureHasSentCarriedItem` 428, packet send 429, the spectator guard at 430, `player.attack(entity)` at 431, and `player.resetAttackStrengthTicker()` at 432. The only caller is Minecraft.java:1720.

### 6.3 `LivingEntityMixin` (CAPTURE) → `net.minecraft.world.entity.LivingEntity`

**Every handler's first statement** (R2b): `if (!((LivingEntity)(Object)this).level().isClientSide()) return;`. In singleplayer these methods also run on the integrated-server thread (`ServerPlayer.jumpFromGround` calls `super`, server/level/ServerPlayer.java:1514–1515; every mob jump reaches `LivingEntity.jumpFromGround`). The check precedes the R4 check, any `Kwamster.*` access and the R3 rate-limited logger. After it, the static delegate checks `this == mc.player` (jump) or the routing below.

| Injection | Target | Point | Does |
|---|---|---|---|
| `kwamster$damage(DamageSource, CallbackInfo)` | `handleDamageEvent(Lnet/minecraft/world/damagesource/DamageSource;)V` | HEAD | `CombatCapture.onDamageEvent(this, source)` (see the routing below) |
| `kwamster$jump(CallbackInfo)` | `jumpFromGround()V` | HEAD | if `this == mc.player`: `JumpEvent(nanos, Clock.ticks(), pos)` plus `JumpResetWatch.onJump` |
| `kwamster$entityEvent(byte, CallbackInfo)` | `handleEntityEvent(B)V` | HEAD | if `b == 3` (EntityEvent.DEATH) and `this instanceof Player` (not an NPC, unless it is self): `DeathEvent(who, self = this == mc.player)` |

Routing of `onDamageEvent` (an NPC attacker or victim is ignored, §5.4):

| Victim | Condition | Result |
|---|---|---|
| self | attacker = `source.getEntity()` is a `Player ≠ self`, and `source.isDirect() && !source.is(DamageTypes.THORNS)` | `HitTakenEvent(projectile=false)`; `JumpResetWatch.onHitTaken`; `TradeWatch.onHitTaken` |
| self | attacker is a `Player ≠ self`, `!source.isDirect()` | `HitTakenEvent(projectile=true)` |
| another `Player` | `source.getEntity() == self && !source.is(DamageTypes.THORNS)` | `HitConfirmedEvent(victim, direct = source.isDirect())` |
| self | **sourceless**: `getEntity()`, `getDirectEntity()` and `sourcePositionRaw()` all null and type `DamageTypes.GENERIC` (the only shape ViaVersion produces for pre-1.19.4 "entity hurt"), and no sourced damage event seen on this connection | Inferred melee hit taken: `SourcelessDamage.pickAttacker` chooses a non-NPC, living, non-spectator player within 4.5 blocks (eye to own AABB) with `swinging && swingTime ≤ 2`, the current fight opponent preferred, otherwise the nearest. If nobody qualifies at the hook (1.9+ attackers send the swing after the attack), it is re-checked once at the next `START_CLIENT_TICK` with the hook's nanos, tick and `jumpHeld`; otherwise dropped. **While burning** (`isOnFire()` without `FIRE_RESISTANCE`), **poisoned or withered** it is always decided at that tick start and kept only if `SourcelessDamage.showsKnockback` holds: your delta movement changed since the END_CLIENT_TICK snapshot (only packets change it in between, so a velocity packet arrived, before or after the status) and the new horizontal velocity points away from the attacker by ≥ 0.15 blocks/tick (vanilla knockback adds about 0.4; a fire, poison or wither tick's velocity packet carries only the server's residual motion). Found: `HitTakenEvent(projectile=false)`, `JumpResetWatch.onHitTaken`, `TradeWatch.onHitTaken`. If your own death (entity event 3) arrives while such a hit is pending (the killing blow's hurt and death statuses come in one frame), the `DeathEvent` is held and posted right after the hit at that tick start, with its own nanos, so the fight still has the hit when it ends |
| another `Player` | sourceless (as above) | `HitConfirmedEvent(victim, direct=true)` only if your last landed swing on that UUID that was judged registered (not NO-REG: such a swing never yields a damage event) is between `SourcelessDamage.minConfirmAgeMs(ping) = max(0, min(ping − 50, ping/2))` and ping + 300 ms old. The match spends the swing (`claimOwnHit`), so one swing confirms one event; a too-young event leaves it for the real one |

Every damage event on another player (thorns and sourceless included) also feeds `NoRegEstimator.onDamage(victim, ownMelee, tick, ping)`, with `ownMelee` true for a sourced direct hit from you (a sweep included) or an inferred confirmation; the estimator decides whether it is the anchor's own damage (§5.4). Any sourced damage event sets `sourcedDamageSeen` (cleared on `WorldLeaveEvent`), which switches the sourceless rows off for the rest of the connection, so modern servers are never touched. A sourced melee hit taken from an attacker whose death was already processed (a corpse, §5.4) is dropped.

Evidence:
- **handleDamageEvent:** world/entity/LivingEntity.java:1938. The client calls it from client/multiplayer/ClientPacketListener.java:1202–1208 after `ensureRunningOnSameThread`, so it runs on the main thread. The source is built by `ClientboundDamageEventPacket.getSource` (network/protocol/game/ClientboundDamageEventPacket.java:71–79), which resolves the cause and direct entities in the client level. `DamageSource.isDirect()` is at world/damagesource/DamageSource.java:30. `DamageTypes.THORNS` is at world/damagesource/DamageTypes.java:51.
- **Server side:** `broadcastDamageEvent` goes to tracking players and self (server/level/ServerLevel.java:1166–1168).
- **jumpFromGround:** LivingEntity.java:2262.
- **Death event:** `handleEntityEvent` is at LivingEntity.java:1953 with `case 3` at 1958. Player, LocalPlayer and RemotePlayer pass byte 3 to `super` (world/entity/player/Player.java `handleEntityEvent` else-branch; client/player/LocalPlayer.java `default: super`). For players the server sends it from `ServerPlayer.die` (server/level/ServerPlayer.java:910, `this.level().broadcastEntityEvent(this, (byte)3)`; `ServerPlayer.die` overrides `LivingEntity.die` without calling `super`), through `broadcastEntityEvent` → `sendToTrackingPlayersAndSelf` (ServerLevel.java:1161–1163). This is the authoritative death signal; the health and pose reads in rule 3 of §5.13 are best-effort.
- **Thorns:** `DamageSources.thorns(entity)` is a direct source caused by the wearer (world/damagesource/DamageSources.java:247–249), hence the explicit THORNS exclusion in both self and confirmation rows.
- **Hurt immunity (NO-REG):** client `handleDamageEvent` sets `invulnerableTime = 20` (LivingEntity.java:1940); `baseTick` decrements it for every non-`ServerPlayer` (455–456); the server skips the damage event while `invulnerableTime > 10` (1194). The client copy lags the server by about a round trip plus a tick, which is why §5.4 uses send ticks instead.

### 6.4 `MouseHandlerMixin` (CAPTURE) → `net.minecraft.client.MouseHandler`

```java
@Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"), require = 0)
private void kwamster$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
	CombatCapture.onMouseButton((MouseHandler)(Object)this, info, action);
}
```

- **What it records.** It records `System.nanoTime()` into `ClickTimestamps` only when all of these hold: `InputConstants.Type.MOUSE.getOrCreate(info.button()).equals(KeyBindingHelper.getBoundKeyOf(mc.options.keyAttack))` (so a rebound Attack works; fabric/…/api/client/keybinding/v1/KeyBindingHelper.java:58), `action == GLFW_PRESS`, `isMouseGrabbed()`, and `mc.screen == null`.
- **Keyboard-bound Attack** (`KeyboardHandlerMixin`, CAPTURE): `@Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"), require = 0)` → `CombatCapture.onKey(keyEvent, action)`, which records the timestamp when `InputConstants.getKey(keyEvent).equals(getBoundKeyOf(keyAttack))`, `action == GLFW_PRESS`, `isMouseGrabbed()` and `mc.screen == null` (client/KeyboardHandler.java:434; `InputConstants.getKey(KeyEvent)` as used at 527/545).
- **Known limitation.** On macOS, `simulateRightClick` remaps ctrl+left before `KeyMapping.click` (MouseHandler.java:74, 157–160), so a raw left press can be credited to a right click. This is rare and only affects `clickLeadMs`; accepted.
- **Why `require = 0`.** Click timing is an enhancement. If another client mod reshapes `onButton` or `keyPress`, the mod still loads and falls back to hook timing (`clickLeadMs = 0`).
- **What is not ported.** No InputContext calls (R8).
- **Evidence.** `onButton(long, MouseButtonInfo, int)` at client/MouseHandler.java:65; `isMouseGrabbed()` at 407; `record MouseButtonInfo(int button, int modifiers)` at client/input/MouseButtonInfo.java:11.

### 6.5 `GuiCrosshairMixin` (CROSSHAIR) → `net.minecraft.client.gui.Gui`

Vanilla `renderCrosshair(GuiGraphics, DeltaTracker)` (client/gui/Gui.java:425–456):
- It draws only in first person (427).
- In spectator mode it draws only over a menu provider (428).
- It skips everything when the F3 "3D crosshair" entry is on (429).
- It calls `nextStratum()` (430), blits `hud/crosshair` 15×15 at the GUI centre (432), and then, if `attackIndicator() == CROSSHAIR` (433), blits `hud/crosshair_attack_indicator_full` (446), or `…_background` (449) plus `…_progress` (450, the 8-int overload).
- Sprite ids are at Gui.java:86–89.
- `render` calls it inside `if (!hideGui)` (205–210).

Wrapping the sprite calls rather than cancelling the method **keeps every vanilla gate**: first person, spectator rule, 3D-crosshair debug entry, F1 and the level-loading screen. It also leaves the attack indicator under user control. The indicator sprites are only blitted here when Options → Attack Indicator is CROSSHAIR (Gui.java:433); in HOTBAR mode `renderItemHotbar` draws it, untouched, so `crosshair.attackIndicator` has no effect there (stated in its tooltip).

```java
import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;   // MixinExtras 0.5.4, bundled with Fabric Loader 0.19.3
import com.mojang.blaze3d.pipeline.RenderPipeline;

@Mixin(Gui.class)
public abstract class GuiCrosshairMixin {
	@WrapWithCondition(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"),
		require = 0)
	private boolean kwamster$sprite(GuiGraphics g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h) {
		return CrosshairRenderer.onVanillaSprite(g, sprite);
	}

	@WrapWithCondition(method = "renderCrosshair(Lnet/minecraft/client/gui/GuiGraphics;Lnet/minecraft/client/DeltaTracker;)V",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiGraphics;blitSprite(Lcom/mojang/blaze3d/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIIIIIII)V"),
		require = 0)
	private boolean kwamster$progress(GuiGraphics g, RenderPipeline pipeline, Identifier sprite,
	                                  int tw, int th, int u, int v, int x, int y, int w, int h) {
		return CrosshairRenderer.onVanillaSprite(g, sprite);
	}
}
```

`CrosshairRenderer.onVanillaSprite(GuiGraphics g, Identifier sprite)` returns `true` to let vanilla draw:

| Condition | Action | Returns |
|---|---|---|
| `!general.enabled \|\| !crosshair.enabled` | — | `true` |
| `sprite.equals(Identifier.withDefaultNamespace("hud/crosshair"))` | draw the custom crosshair now (§7.8) | `false` |
| sprite is `hud/crosshair_attack_indicator_full`, `…_background` or `…_progress` | — | `attackIndicator == KEEP` |
| anything else | — | `true` |

Discriminating by sprite id (rather than ordinal) means a future reordering of the vanilla body cannot misroute calls.

**Why `require = 0`.** If Lunar or another client rewrites the vanilla crosshair, the game still boots and vanilla or the other mod draws instead (§13). The overloads are at client/gui/GuiGraphics.java:298 (6 ints) and 327 (8 ints).

**Composition with Fabric API.** Fabric wraps the *call* to `renderCrosshair` from `render` (fabric/net/fabricmc/fabric/mixin/client/rendering/GuiMixin.java:56–59, `WrapOperation`). That is a different target, so both compose.

### 6.6 HUD render hook (HUD, no mixin)

```java
HudElementRegistry.attachElementBefore(VanillaHudElements.BOSS_BAR,
	Identifier.fromNamespaceAndPath("kwamster", "hud"), HudManager::render);
```

- **Evidence.**
  - `HudElementRegistry.attachElementBefore` is at fabric/net/fabricmc/fabric/api/client/rendering/v1/hud/HudElementRegistry.java:103.
  - `VanillaHudElements.BOSS_BAR` is at …/VanillaHudElements.java:92.
  - Vanilla calls `renderBossOverlay` inside the first `if (!hideGui)` block, after the hotbar and status effects and before the sleep overlay, scoreboard sidebar, action bar, titles, chat, tab list and subtitles (Gui.java:205–228).
- **F1.** It is respected for free (the BOSS_BAR root only renders inside `!hideGui`).
- **Draw order (principle: KWAMSTER never hides server-sent information).** The HUD draws above the world, crosshair, hotbar and status effects, and below boss bars, the scoreboard sidebar, the action bar, titles, chat and the tab list. Where a KWAMSTER element overlaps one of those, the server's text wins and stays readable (for example a VICTORY title sent at the moment the card appears). `GuiRenderState` orders overlapping elements by submission within a stratum, so no extra `nextStratum()` is needed.
- **F3.** `HudManager.render` hides everything except the crosshair (which is not part of the HUD layer) when `general.hideInDebug && mc.debugEntries.isOverlayVisible()`. Evidence: `Minecraft.debugEntries` at client/Minecraft.java:307; `DebugScreenEntryList.isOverlayVisible()` at client/gui/components/debug/DebugScreenEntryList.java:153. Use this, not `DebugScreenOverlay.showDebugScreen()` (DebugScreenOverlay.java:393), which is true whenever any "always on" debug entry is enabled.
- **Suppression.** `HudManager.render` also returns early when `general.hudHidden`, when `mc.options.hideGui` is set, or when `mc.screen instanceof HudEditorScreen` (the editor draws its own previews).

### 6.7 Other hooks (Fabric events, no mixin)

| Hook | Unit | Use |
|---|---|---|
| `ClientTickEvents.START_CLIENT_TICK` | CORE | `Clock.onTickStart()` (tick index for §5.1). Client thread. |
| `ClientTickEvents.START_CLIENT_TICK` | CAPTURE (registered in `CaptureModule.init`, so after CORE's) | `CombatCapture.onClientTickStart`: the one deferred re-check of a sourceless hit taken (with the knockback check while burning, poisoned or withered), then a death of yours held behind it (§6.3). Client thread. |
| `ClientTickEvents.END_CLIENT_TICK` | CORE | `Keybinds.handle`, `bus.postTick(mc)` → FightManager tick (disconnect/world-change detection and finalisation, §5.13), CAPTURE tick (`HealthWatch`, `TradeWatch`, `VelocityWatch`, and the jump-press rising edge of §5.1 while unpaused; each null-checks `mc.level`/`mc.player`), DIRECTION tick. Fires on ticks with no level too. Client thread. |
| `ClientReceiveMessageEvents.GAME` | CAPTURE (registered in `CaptureModule.init`) | `OutcomeWatch.onGameMessage`: system and action-bar messages, read for kill and win announcements while a fight runs (§5.13a). Client thread (packet handling). |
| `ClientPlayConnectionEvents.DISCONNECT` | CORE | **Off-thread (Netty IO, R2a).** `fightManager.markDisconnectPending()` only; the next END_CLIENT_TICK does the rest. |
| `ClientLifecycleEvents.CLIENT_STOPPING` | CORE only (registered in §2.1 step 2, before modules) | `forceEnd(GAME_EXIT)` then `services().archive().shutdown()`. Client thread. RECORD registers nothing here. |
| `KeyBindingHelper.registerKeyBinding` | CORE | §11 |

---

## 7. HUD and visual design

### 7.0 Visual language

The look is clean, dark glass. All colours are ARGB.

| Token | Value | Use |
|---|---|---|
| Panel background | `0xB80E1116` (`hud.panelBgArgb`) | panel, card body |
| Card background | `0xE00E1116` (fixed: background alpha forced to 0xE0) | card |
| Border | `0x26FFFFFF` | 1 px border |
| Top highlight | `0x14FFFFFF` | 1 px line under the top border |
| Value text | `0xFFF1F3F5` | numbers |
| Label text | `0xFF9AA4B2` | row labels |
| Dim text | `0xFF6B7280` | footers, hints |
| Accent | `0xFF8B5CF6` (violet) | title, highlights, editor selection |
| Accent 2 | `0xFF22D3EE` (cyan) | secondary highlights |
| Good / warn / bad | `0xFF3DDC84` / `0xFFFFC53D` / `0xFFFF5A5F` | percentages, results |
| Shadow | `0x40000000` then `0x20000000` | drop shadow **outside** the box only: a 1 px row directly under the bottom edge, then a second row under that, both inset by 1 px at each end (corner notch). Never a full offset rect under a translucent body (that would darken the body and leave a light band at the top). |

- **Font.** Vanilla `mc.font` (9 px line). The title and result words use `ChatFormatting.BOLD` (bold adds +1 px per glyph; width measurement always uses `font.width(component)`).
- **Shadow rule.** Text drawn on a panel, pill or card is flat (`drawString(..., false)`): the backdrop carries contrast. Text with **no backdrop** under it is drawn with the vanilla shadow (`drawString(..., true)`): the combo counter's "COMBO" caption and the stoplight label. The combo counter's 2× number is outlined instead (§7.6), and the editor help line sits on its own pill (§7.3). This keeps them readable over snow, sky, sand and quartz arenas.
- **Rounded corners.** Every box gets 1 px rounded corners: the four corner pixels are left unpainted (`Draw.roundRect`). `Draw.border` and the top highlight line skip the same four corner pixels, so border, highlight and background share one silhouette.
- **Alpha.** Any colour passed to `drawString` with an alpha below `0x08` is skipped instead of drawn (`Draw.text`).
- **Integer scales.** Text is never drawn at a fractional scale by KWAMSTER itself (fractional scales give unevenly wide glyph pixels). Only the user's per-element editor scale can be fractional, and the editor nudges toward crisp values (§7.3). Pop-in animations are the one exception (transient).
- **Reach figures are rounded down.** `StatFormat.reach` shows `floor((blocks + HitGrader.EPSILON)·100)/100` (2.836 reads 2.83, 2.9499 reads 2.94), everywhere reach is shown (chat, HUD, card, saved-fights screen), so a shown value ≥ a 2-decimal threshold means the hit was 3-block eligible. The share payload and the viewer round down too (§9.3, §9.7). **One exception: over-reach figures.** A reach over the attack range it is compared with never reads at or below that range: `StatFormat.reachVsRange(reach, limit)` shows at least `reach(limit) + 0.01` when `reach > limit` (a 3.004-block miss reads "MISS 3.01 (over reach)", never "3.00"). It is used for the miss chat line and its hover, the hit indicator's miss pill and the summary hover's miss maximum (`FightStats.missReachMaxLimit`); the payload does the same with `reachCmVsRange` and `overReachShown`. Only misses can be over the range, and misses are never graded, so this never makes a hit look 3-block eligible.
- **Truncation.** Any string that could overflow (opponent names, footers) is cut with `StatFormat.truncate(String s, int maxPx, ToIntFunction<String> width)`, which keeps the longest prefix that fits with `"..."` appended (vanilla's own `CommonComponents.ELLIPSIS` text). StatFormat stays MC-free because the width function is passed in (`font::width` in game, a fixed-width stub in tests).
- **Animation.** Animations use `Clock.nanos()` and `Anim` easing (`easeOutCubic`, `easeInOutQuad`). Nothing animates while `mc.isPaused()` except fades, which continue. `Anim.progress` and `Anim.ageMs` subtract in double, so a never-set start (`Long.MIN_VALUE`) counts as long ago (progress 1, huge positive age) instead of overflowing.
- **Default-layout validation.** Defaults (§3.10) are checked at GUI-scaled sizes 427×240 (an 854×480 window at GUI 2, the gametest size), 480×270 (1920×1080 at Auto = GUI 4), 640×360 and 960×540, using element sizes computed from `PreviewStrings` with `AsciiWidths` (the same strings `HudPreviewData` shows). No two default element rects intersect (except STATS_PANEL with SUMMARY_CARD, which never show together, §7.1 card priority), and none intersects the vanilla crosshair/indicator rect `[W/2−8, H/2−8, W/2+8, H/2+17)`. In addition, STATS_PANEL and SUMMARY_CARD intersect neither the title band `[W/2−80, H/2−40, W/2+80, H/2−4)` (a 40 px title drawn at 4×, Gui.java:362–367) nor the full unfocused chat band `[0, H−130, 330, H−40)` (default chat width 320 + margins, unfocused height 90, ChatComponent.java:364–372). The three centre elements are exempt from those two bands by design: they hug the crosshair, which itself lies inside both bands at small GUI sizes, and titles and chat draw over them (§6.6). Boss bars (`y 3…17` per bar, 19 px pitch, x `W/2−91…W/2+91`) and the scoreboard sidebar are not asserted: they draw over KWAMSTER (§6.6), and the README says to move elements in the editor if a server uses them.

### 7.1 Element interface (HUD-owned)

```java
public interface HudElement {
	HudElementId id();
	int width(boolean preview);        // unscaled GUI px for the content drawn now (or the preview content)
	int height(boolean preview);
	boolean enabled(KwamsterConfig c); // feature toggle (table below); the editor shows disabled ones as ghosts
	boolean active(long nowNanos);     // has something to show right now (in-game only)
	/** Draw at (0,0). The pose is already translated and scaled. `alpha` multiplies every colour. */
	void render(GuiGraphics g, long nowNanos, float alpha, boolean preview);
}
```

| Element | `enabled(c)` | `active(now)` |
|---|---|---|
| STATS_PANEL | `c.display.mode != CHAT_ONLY` | in fight, or `panelWhenIdle != HIDE` |
| HIT_INDICATOR | `c.feedback.indicatorEnabled` | `lastResolved` younger than `indicatorDurationMs` (age from `HitResolvedEvent.nanos`), and: a landed hit whose tier is enabled, or a NO-REG hit, or a miss with `indicatorShowMisses` |
| COMBO_COUNTER | `c.hud.comboCounterEnabled` | `currentCombo ≥ comboCounterMin`, or less than 1000 ms since it dropped (fade) |
| STOPLIGHT | `c.direction.enabled` | `reading.state != OFF \|\| !hideWhenNoTarget` |
| SUMMARY_CARD | `c.alert.cardEnabled` | a `FightEndedEvent` with `alert`, received while `mc.level != null`, younger than `cardDurationMs` |

`DisplayMode` gates only STATS_PANEL (and chat). The other elements have their own toggles, by design (the "Stats output" tooltip says so, §3.2).

**Card priority.** While SUMMARY_CARD is active and its on-screen rect intersects STATS_PANEL's rect, the panel fades out over 150 ms and stays hidden until the card has faded (it would only repeat the card's numbers). With the defaults this always applies: a ≥ 244 px card centred on a 427–480 px wide screen reaches the panel's area. The editor shows both, and the overlap test exempts this one pair.

`HudManager` draws in this order: STATS_PANEL, STOPLIGHT, COMBO_COUNTER, HIT_INDICATOR, SUMMARY_CARD. It calls `Kwamster.testHooks().countHudDraw()` once per element drawn in game.

### 7.2 Anchor, offset and scale model (`HudLayoutMath` ★)

The screen size `W × H` is in **GUI-scaled** pixels (`g.guiWidth()`, `g.guiHeight()`; GuiGraphics.java:130–136). The element size is `w = round(baseW·scale)`, `h = round(baseH·scale)`.

```
x = round(a.fx·W + offsetX − a.fx·w)          y = round(a.fy·H + offsetY − a.fy·h)
x = clamp(x, 0, max(0, W − w))                 y = clamp(y, 0, max(0, H − h))   // clamped when drawn only; stored values are untouched
```

- **Why this is stable across GUI scales.** Offsets are measured from the nearest edge or centre, and element sizes scale with the GUI. Changing the GUI scale keeps every element in the same relation to its anchor (for example 6 px from the top-left, or 16 px below the crosshair).
- **Auto-anchor on drop.** After a drag, with `cx = x + w/2` and `cy = y + h/2`:
  - `fx = cx < W/3 ? 0 : cx > 2W/3 ? 1 : 0.5`, and the same for `fy`.
  - `anchor = Anchor.of(fx, fy)`.
  - `offsetX = x − fx·W + fx·w`, and the same for y (rounded).
  - The element does not move visually.
- **Snap.** Unless Shift is held, each axis snaps when within 4 px of one of these:
  - screen margins (4 and `W − 4 − w`)
  - the centre line (`W/2 − w/2`)
  - another element's left or right edge
  - another element's edge ± 2 px gap (stacking)
- **Pose transform** for drawing: `pose.pushMatrix(); pose.translate(x, y); pose.scale(scale, scale); element.render(...); pose.popMatrix();` (`Matrix3x2fStack`, used the same way in combat-tracker `HudRenderer.renderScaledAt`).

### 7.3 Editor UX (`HudEditorScreen`)

- **Opening.** Through `screens().open(HUD_EDITOR, parent, arg)`, from the keybind (parent `null`) or from a Cloth button (parent = the Cloth screen). `arg` is an optional `HudElementId.name()` that starts selected ("Position card…", "Position stoplight…").
- **Snapshot and Cancel.** On open, the editor deep-copies `layouts`. Esc or "Done" saves (`Kwamster.saveConfig()`) and returns to the parent. "Cancel" restores the snapshot into `Kwamster.config().layouts` without saving and returns to the parent.
- **Background.** Override `renderBackground` to draw `0x50000000` with **no blur**, so the world stays visible. Add a faint 16 px grid (`0x0FFFFFFF` lines) and the centre cross lines (`0x22FFFFFF`).
- **Content.** Every element is drawn with `preview = true` using `HudPreviewData` (a fake fight "vs Kwamix 0:42", a 3-block crit indicator "2.97 3-BLOCK CRIT", combo 4, a GREEN stoplight, a WIN card). Disabled elements are drawn at 35 % alpha with a "(off in settings)" tag.
- **Outlines.** Each element gets a 1 px outline:
  - idle `0x55FFFFFF`
  - hovered `0xCCFFFFFF`
  - selected accent `0xFF8B5CF6`
  - a 9 px tag above the selected element showing name and scale ("Stats panel · 150 %") on `0xCC000000`. The scale part is drawn in accent when `scale × guiScale` is an integer within the crisp tolerance below (crisp text), dim otherwise.
- **Mouse.**
  - Left-drag moves, with snapping and accent guide lines while snapped.
  - Release runs auto-anchor.
  - Scroll over an element changes its scale by ±`1/guiScale` (0.5 at GUI 2, 0.25 at GUI 4, so every step lands on a crisp integer pixel ratio), or ±0.025 with Shift (fine, `HudLayoutMath.FINE_SCALE_STEP`), clamped to 0.5–3.0, keeping the element's centre fixed. Coarse steps store `k/guiScale` unrounded (as a float, so 4/3 is 1.3333334, not 1.333); only fine steps are rounded to 3 decimals. A scale within `0.001 + 0.0005·guiScale` of a multiple of `1/guiScale` counts as crisp, both for the tag colour and for where the next coarse step goes (snap tolerance `0.01 + that` steps, still below one fine step), so 3-decimal scales saved by older configs (1.333 at GUI 3, 1.167 at GUI 6, 1.571 at GUI 7) step on instead of sticking.
  - Right-click resets that element to `HudLayout.defaultFor`.
- **Keys.**
  - Arrows nudge the selected element 1 px (10 px with Shift).
  - `R` resets the selected element.
  - `G` toggles the grid.
  - `Tab` cycles the selection.
- **Bottom buttons** (vanilla `Button`, 20 px tall, centred): "Reset all" (two-click confirm), "Cancel" and "Done".
- **Help line** "Drag to move · Scroll to resize · Right-click to reset · Shift: no snap", flat, in label colour `0xFF9AA4B2` (dim is too faint on a backdrop), on its own dark pill: `0xCC000000`, 1 px rounded corners, 11 px tall, 4 px side padding. It goes at the top centre, or just above the buttons when an element covers the top (the usual case at GUI 2, where the default stats panel overlaps the full-width line; there it covers chat lines and the hotbar); when both spots are covered, whichever hides fewer element pixels.
- **Exclusive fields.** The editor never writes visibility or any other field that Cloth edits (§3.10).

### 7.4 Stats panel (S10 scoreboard style)

**Size.** Padding is 6 px left and right and 4 px top and bottom (compact: 4 and 3). The row height is 10 px. Between a label and its value there is always at least 6 px.
- **Width** `w = clamp(maxRowWidth + 2·padX, 128, 168)` (compact: `clamp(…, 92, 168)`), where `maxRowWidth` is the widest of the rows being drawn: `font.width(label) + 6 + font.width(value)` per row, and `font.width(left) + 6 + font.width(chip)` for the header. It is recomputed with the 50 ms row cache. Within one scope (one fight, or one idle period) the width never shrinks, so the panel does not jitter as values change; it resets when the scope changes.
- **Overflow.** Only the opponent name can be long. It is truncated with `StatFormat.truncate` (§7.0) so the header fits at 168 px. Every other value is bounded and fits by construction (tested, §12.2).
- **Height in a fight with default rows:** 4 + 10 (header) + 3 (divider) + 8 × 10 + 4 = 101 px, so at the default TOP_LEFT (5, 6) it ends at y = 107, above the chat band at 427×240 (starts at 110) and 480×270 (140). The x offset is 5, not 6 (integration fix): at 427×240 the 128 px minimum width at x = 6 would end at x = 134, inside the title band that starts at W/2 − 80 = 133.5; at x = 5 it ends at 133.

```
┌────────────────────────────────┐   roundRect bg panelBgArgb, 1px border borderArgb, top highlight (corners skipped)
│ vs Kwamix              ● 0:23  │   header: "vs " label colour + name value colour; right chip: pulsing red dot 0xFFFF5A5F (alpha 0.55..1 at 1.2 Hz) + "m:ss" value colour
├────────────────────────────────┤   divider 1px 0x1FFFFFFF, 1px margin above/below
│ Hits             23/31 · 74%   │   label left at x=padX; value right-aligned at w−padX
│ Reach          2.84 avg · 3.00 │
│ Combo                3 · best 5│
│ Crits                 6 · 26%  │
│ Sprint               12 · 52%  │
│ 3-Block               4 · 17%  │
│ Resets               5/7 · 71% │
│ Advantage      YOU ▕▔▔▔│███▔▏  │   word in good/warn/bad colour + 32×3 px bar (track 0x33FFFFFF, centre tick 0x66FFFFFF,
└────────────────────────────────┘   fill from centre: +A good 0xFF3DDC84, −A bad 0xFFFF5A5F; displayed A eases, τ 150 ms)
```

**Header states** (left text · right chip). There is no separate name row and no wordmark during a fight; this keeps the panel short and clean.
- In a fight: `vs <name>` · `● m:ss`.
- Idle with a last fight (`LAST_FIGHT`): `vs <name>` · `WIN · 0:42` (result word bold in its colour, duration dim).
- `SESSION`: **KWAMSTER** (bold, accent) · `SESSION` (dim). The fight count is visible in the Record row.
- Nothing yet: **KWAMSTER** · (no chip), then a single row "No fights yet" in dim text.

| Row (`PanelRows` field) | Label | Fight / last-fight value | SESSION value | Value colour |
|---|---|---|---|---|
| hits | Hits | `landed/swings · acc%` | totals | acc% ≥ 70 good, ≥ 50 warn, else bad |
| reach | Reach | `avg avg · max` (2 decimals) | totals | max ≥ threeBlockReach → THREE_BLOCK tier colour |
| combo | Combo | `cur · best N` (last fight: `best N`) | `best N` | cur ≥ 3 → accent |
| streak | Streak | `cur · best N` | `best N` | value |
| crits | Crits | `n · rate%` | totals | CRIT tier colour if n > 0 |
| sprint | Sprint | `n · %` | totals | value |
| threeBlock | 3-Block | `n · %` | totals | THREE_BLOCK_CRIT tier colour if n > 0 |
| jumpResets | Resets | `s/a · %` | totals | ≥ 60 good, ≥ 30 warn, else bad |
| interval | Interval | `avg ms` | totals | value |
| taken | Taken | `hitsTaken` | totals | value |
| aim | Aim | `C12 E4 H5 L2` | totals | value |
| trades | Trades | `3W 1L` or "—" | totals | W > L good, W < L bad |
| advantage | Advantage | live `YOU/EVEN/THEM` plus the inline bar; last fight `62% · 21%` (you share in good colour, them share in bad colour) | hidden | as shown |
| record | Record | hidden | `5W 2L 1D` | value |

**Value flash** (`hud.valueFlash`): when a row's value string changes, its colour lerps from accent to the normal colour over 300 ms.

**Caching.** The panel recomputes its row strings and width at most every 50 ms (`LiveFightView.stats(now)` is cached).

**Fit test.** The longest strings each row can produce (5-digit counts, `100%`, `best 99`, a 16-character name before truncation) live in ★ `hud/PreviewStrings.java`; ★ `hud/AsciiWidths.java` holds the vanilla default-font ASCII advance widths (from `assets/minecraft/textures/font/ascii.png` via `include/default.json`; bold +1 per glyph). `HudLayoutMathTest` asserts every sample row fits the computed width with the 6 px gap, at most 168 px. `HudPreviewData` uses the same strings, so the editor preview exposes any overflow.

### 7.5 Hit indicator (S9 colour-coded)

- **Shape.** A pill of height 11 px: `roundRect 0x99000000`, a 2 px left bar in the tier colour, then text in the tier colour (flat, it has a backdrop), for example `2.97  3-BLOCK CRIT`. The reach is shown when `indicatorShowReach`, followed by two spaces and the label. Padding is 4 px.
- **Misses.** Shown in grey `0xFF8A919E` as `MISS 3.42`, with `↑` appended when over reach (`reach > rangeLimit`).
- **NO-REG hits.** Shown in grey `0xFF8A919E` as `2.97  NO-REG` (2 px bar grey too), regardless of the tier's colour.
- **Position.** Default CENTER (0, 26): below the vanilla attack indicator (§3.10).
- **Animation.**
  - Pop-in: scale 1.15 → 1.0 over 120 ms (easeOutCubic), about the element centre.
  - Hold.
  - Fade out over the last 250 ms of `indicatorDurationMs`.
  - A new hit restarts the animation. Ages are measured from `HitResolvedEvent.nanos` (resolution time).

### 7.6 Combo counter

- **Shape.** A number drawn at 2× font scale (`COMBO_NUMBER_SCALE = 2`, top at `COMBO_NUMBER_Y = 1`), with a 1× caption "COMBO" (label colour, vanilla shadow) centred below it at `COMBO_CAPTION_Y = 17`. Base box 30 × 26. The number has no backdrop, so it gets a 1 GUI px near-black outline (`0xE6000000`): four axis-offset copies translated in GUI pixels before the 2× scale. This replaces the vanilla shadow, which scaled to a 2 px offset in the darkened number colour and blobbed digits like "4". With its top at y = 1 the outline stays inside the box.
- **Number colour:** 2–3 value white, 4–5 accent, 6 or more `0xFFFFD43B`.
- **Increment.** Pop scale 1.25 → 1.0 over 150 ms. A counter that first appears without an increment (HUD turned back on mid-combo, counter enabled mid-fight, a lag spike over 250 ms) has no pop start (`popAt == NEVER`) and is drawn at scale 1.
- **When the combo breaks.** The last number turns label grey and gets a "×" multiplier sign in the same 2× outlined style on the number's baseline (`COMBO_MARK_GAP = 2`). Number and sign are centred as one group (`HudLayoutMath.comboNumberX(numberGlyphPx, markGlyphPx)`), so it reads as the final tally "4× COMBO"; both fade over 1000 ms. A two-digit broken combo ("12×") is 34 px wide and overhangs the 30 px box by 2 px on each side.
- NO-REG hits never change the number (§5.4).

### 7.7 Stoplight (S12)

- **VERTICAL** (10 × 26 base: 2 + 6 + 2 + 6 + 2 + 6 + 2): a housing `roundRect 0xB0000000`, 2 px padding, three 6 × 6 lights with corners cut and 2 px spacing. Order, top to bottom: red, yellow, green, like a real traffic light.
- **HORIZONTAL** (26 × 10): left to right red, yellow, green.
- **SINGLE** (10 × 10): one light in the active colour.
- **Lit light.** Full colour plus a 1 px outer ring at alpha 0x55. Unlit lights use `offArgb`. State changes cross-fade over 100 ms.
- **Knockback hold.** While it is active, the lit light pulses at alpha 0.6–1.0.
- **Label** (`showLabel`). Drawn **beside** the housing, vertically centred, with shadow (no backdrop), 3 px away, on the side **away from the screen centre** (left of the housing when the housing's centre x < W/2, otherwise right); if that side would leave the screen (x < 2 or > W − 2), it flips to the other side. Text: `APPROACH +3.1`, `HOLD +0.2` or `RETREAT −2.4` (blocks per second, one decimal), right-aligned when on the left. The label area has a **fixed reserved width** = `font.width("APPROACH -9.9")`, so state changes never move anything.
- **Box.** The element's layout box (`width/height`, used for anchoring, clamping and snapping) is the housing only, so the lights stay exactly where the user put them whether or not the label is shown. The editor outline covers housing plus label area.
- **OFF state.** With `hideWhenNoTarget` (default) the element is not drawn. Otherwise all lights are unlit, the whole element is drawn at 40 % alpha, and the label reads `—`.

### 7.8 Crosshair rendering (S15)

The crosshair is drawn in **physical pixels**, so it is crisp at any GUI scale:

```
int gs = window.getGuiScale();                       // Window.java:466 (int)
pose.pushMatrix(); pose.scale(1f / gs, 1f / gs);
int cx = window.getWidth() / 2, cy = window.getHeight() / 2;   // framebuffer size, Window.java:426-431
... spans in physical px ...
pose.popMatrix();
```

`(cx, cy)` is a **pixel-edge** coordinate: the screen centre lies on the boundary between pixels `cx − 1` and `cx` (exact for even framebuffer sizes, 0.5 px off for odd ones). Sizes are multiplied by `k = scaleWithGui ? max(1, round(gs/2f)) : 1` first.

`CrosshairShapes.build(shape, size, gap, thickness, centerDot, outline, cx, cy)` ★ returns `Spans(List<int[]> outline, List<int[]> fill)`: lists of `{x0, y0, x1, y1}` half-open rects, each a **merged row span**. It works on boolean pixel masks:

- **Fill mask** (union of the parts below), t = thickness:
  - **Core.** `L = cx − t/2`, `T = cy − t/2` (integer division), `R = L + t`, `B = T + t`. Exact for even t; 0.5 px off for odd t.
  - **CROSS.** left `[L−gap−size, T, L−gap, B]`, right `[R+gap, T, R+gap+size, B]`, top `[L, T−gap−size, R, T−gap]`, bottom `[L, B+gap, R, B+gap+size]`. If `gap == 0`, also the core.
  - **T.** CROSS without the top arm.
  - **X.** Four diagonal staircase arms, no pose rotation (pixel-aligned, crisp): for each sign pair `(sx, sy) ∈ {±1}²` and each step `k ∈ [gap, gap + size)`, the t × t square whose near corner is at `(cx + sx·k, cy + sy·k)` extending away from the centre (for `sx = −1` the square is `[cx − k − t, cx − k)`, likewise for y). If `gap == 0`, also the core.
  - **CIRCLE.** Pixels whose centre distance `d = hypot(x + 0.5 − cx, y + 0.5 − cy)` satisfies `size − t ≤ d < size`.
  - **DOT.** Pixels with `d < size/2`.
  - **Centre dot** (`centerDot = c > 0`). Pixels with `|x + 0.5 − cx| < c/2` and `|y + 0.5 − cy| < c/2`.
- **Outline mask** (`outline = o > 0`). The fill mask dilated by o (Chebyshev, i.e. grown by an o-pixel square), minus the fill mask. Outline and fill are therefore disjoint, so every pixel is painted exactly once and a translucent outline never stacks into darker bands.
- **Spans.** Each mask is emitted row by row as maximal horizontal runs, then vertically identical runs are merged into rects. Outline spans are drawn first (`outlineArgb`), then fill spans.

**Colours.** The mode colour comes from the config: DEFAULT `crosshair.defaultArgb`, ON_PLAYER `onPlayerArgb`, CRIT_READY `critReadyArgb`, CRIT_READY_ON_PLAYER `critOnPlayerArgb` (defaults `0xFFFFFFFF`, `0xFFFF4D4D`, `0xFF38D9FF`, `0xFFFF6BD6`). The hit flash (§5.12) overrides the mode colour and widens the gap by up to 2 px.

Defaults: CROSS, size 6, gap 3, thickness 2, outline 1: 2·(6+3)+2 = 20 px of fill, 22 px with the outline.

### 7.9 Fight summary card (S11)

A compact card: header, divider, and a 3 × 3 grid of cells, with the background `0xE00E1116`, a 1 px border `0x26FFFFFF` (corners skipped), a top **2 px result bar** in the result colour (WIN `0xFF3DDC84`, LOSS `0xFFFF5A5F`, DRAW `0xFFFFC53D`), and the outside drop shadow (§7.0).

**Geometry.** Padding 8 px left/right, 5 px top/bottom. Three columns of width `colW`, 6 px gutters: `cardW = 16 + 3·colW + 12`. `colW = clamp(widest cell, 72, 88)` where a cell's width is `font.width(label) + 6 + font.width(value)` (so `cardW` is 244–292). Rows are 10 px. Height: 2 (bar) + 5 + 9 (header) + 5 (divider with 2 px margins) + 3 × 10 + 5 = **56 px**, plus 10 px when the footer is shown (66 px).

```
┌──────────────────────────────────────────────────────────┐  2px result bar
│ WIN  vs Kwamix                                      0:42 │  "WIN" bold 1× result colour; name value colour (truncated); duration dim, right-aligned
│──────────────────────────────────────────────────────────│  divider 0x1FFFFFFF
│ Acc          74%  │ Reach       2.84  │ Max          3.00 │  each cell: label at column x (label colour),
│ Crits          6  │ 3-Block        4  │ Sprint         12 │  value right-aligned to column x + colW (value colour)
│ Combo          5  │ Resets       5/7  │ Taken          14 │
│ Saved · press [Right Ctrl] to share…               ▬▬▬▬ │  optional footer (below)
└──────────────────────────────────────────────────────────┘
```

- **Cells.** Row 1: Acc (`acc%`, coloured like the panel's hits row), Reach (avg), Max (max reach, THREE_BLOCK tier colour if ≥ threeBlockReach). Row 2: Crits, 3-Block (THREE_BLOCK_CRIT colour if > 0), Sprint. Row 3: Combo (best), Resets (`s/a`), Taken. Streak, interval, trades and advantage are in the chat summary, its hover, and the viewer.
- **Text.** All flat (backdrop), 1× scale; "WIN" is bold, never scaled (fractional scales blur the bitmap font, §7.0).
- **Footer.** Shown only when `cardFooterHint` is on **and** it adds information, i.e. when `!chatSummaryWithLink`, where `chatSummaryWithLink = general.enabled && display.mode != HUD_ONLY && chatMode ∈ {PER_FIGHT, BOTH} && chatShareActions && link != null`. Text: "Saved · press [<copy-link key>] to share" using the bound key's display name, "Saved" if that key is unbound, or "Not saved" when the fight was not saved. It is truncated with `StatFormat.truncate` so it ends 6 px before the progress line. The progress line is a fixed 24 px wide, 1 px, accent, right-aligned on the footer row, shrinking over the remaining duration. With the footer hidden, the same 24 px progress line sits in the bottom padding.
- **Position.** Default TOP_CENTER (0, 20): with the default 56 px height it ends at y = 76, above the title band at both 427×240 and 480×270 (§3.10). Titles, boss bars and chat draw over the card if the user moves it under them (§6.6).
- **Animation.**
  - Enter: slide down 8 px and fade in over 180 ms (easeOutCubic).
  - Exit: fade over `cardFadeMs`.
  - If a new fight starts while the card shows, it fades out over 200 ms.
- **Preview.** `HudPreviewData` includes a worst-case card (16-character name, `100%`, `12/17`, a long key name) so overflow shows in the editor.

### 7.10 HUD presets (`HudPreset` ★, `HudPresetsScreen`)

**Presets.** KWAMSTER (the shipped defaults), Clean, Minimal, Competitive, Full stats, Neon, Monochrome, Classic. Each preset is a fresh default config with its style written over it, and `apply` copies the same fixed set of **look fields** (`HudPreset.fieldPaths()`) and nothing else, so applying one after another always ends on exactly the last one, and `matching(cfg)` names the preset a config shows (the "Current" badge). The look fields:
- `hud`: the five colours, `panelCompact`, the 14 `rows`, `comboCounterEnabled`, `comboCounterMin`, `valueFlash`;
- `feedback`: `indicatorEnabled`, `indicatorDurationMs`, `indicatorShowReach`, `indicatorShowMisses`;
- `alert`: `cardDurationMs`, `cardFadeMs`, `cardFooterHint`;
- `crosshair`: shape, size, gap, thickness, outline and its colour, centre dot, hit flash and its length, the four state colours;
- `direction`: the three bar colours, backdrop, bar width and height.

Never touched: detection, sounds, chat, recording, the hit tiers, the master switch, every other feature switch, positions and scales (`layouts`), `crosshair.scaleWithGui` and `hud.panelWhenIdle`. Only Minimal switches an element off (the combo counter); every other preset turns the combo counter and the hit indicator back on. In every preset the four crosshair state colours stay clearly apart (Monochrome keeps red/cyan/pink states), text colours keep alpha ≥ 0x20, and stepped values sit on their Cloth slider steps.

**Screen.** Cards on the left (name, description, a four-square swatch; the preset in use carries a "Current" badge), a preview on the right and a legend (the four crosshair state colours, the three bar colours) under it. The preview draws a sky-and-grass backdrop, vanilla's default-skin player model as the opponent with its direction bar and name tag, and the real stats panel, hit indicator, combo counter and summary card through `HudManager.drawElement` with their preview data, inside `Kwamster.withPreviewConfig(sample, …)`. One deep copy of the live config per preset is made when the screen opens (Gson round trip, `clampAndFill`, `apply`), so the preview keeps the player's own tiers, 3-block reach and feature switches. The crosshair geometry is mirrored from CROSSHAIR's `CrosshairShapes` (R1). Two views: **In fight** (panel, crosshair on the opponent in its on-a-player colour, indicator, combo) and **Fight over** (card, crosshair at rest).

**Controls.** ↑/↓ choose a card, ←/→ switch the view, double-click or Enter applies. **Apply** (inactive, "In use", for the preset in use) applies to the live config, saves, and opens a fresh settings screen on the HUD tab whose parent is the old settings screen's parent (read from Cloth's `parent` field; back-to-game if unreadable), so its entries show the new values and the stale screen's Save cannot write the old look back. Unsaved edits in the old settings screen are discarded; Apply's tooltip says so. **Back** and Esc return unchanged.

---

## 8. Feedback

### 8.1 Hit sounds (FEEDBACK `HitSoundPlayer`)

On `onHitResolved` with a non-null quality, look up `TierStyle s = cfg.tier(q)`. Play only when all of these hold:
- `general.enabled`
- `feedback.soundsEnabled`
- `!e.swing().intoImmunity()` (NO-REG hits are silent, §5.4)
- `s.enabled`
- `s.soundEnabled`
- at least 40 ms since the last KWAMSTER sound (anti-stacking)

```java
Identifier id = Identifier.tryParse(s.soundId);          // Identifier.java:49
if (id == null || mc.getSoundManager().getSoundEvent(id) == null) return;   // SoundManager.java:173–175: a plain map lookup, so no cache (a cache would go stale on resource reload)
SoundSource src = switch (cfg.feedback.soundChannel) { case MASTER -> SoundSource.MASTER; case PLAYERS -> SoundSource.PLAYERS; case UI -> SoundSource.UI; };
mc.getSoundManager().play(new SimpleSoundInstance(id, src, s.volumePct / 100f, s.pitchPct / 100f,
	SoundInstance.createUnseededRandom(), false, 0, SoundInstance.Attenuation.NONE, 0, 0, 0, true));
```

Evidence:
- The 12-argument constructor `SimpleSoundInstance(Identifier, SoundSource, float volume, float pitch, RandomSource, boolean looping, int delay, Attenuation, double x, double y, double z, boolean relative)` is at client/resources/sounds/SimpleSoundInstance.java:79–100. It is the same shape `forUI(SoundEvent, float pitch, float volume)` uses at line 27–30, with `SoundSource.UI`, `Attenuation.NONE`, `relative=true`. Note that `forUI` takes pitch first.
- KWAMSTER uses the constructor directly so it can pick the channel, and so a sound id never needs a registry lookup. Resource-pack sounds work as long as they exist in `sounds.json`.
- `SoundInstance.createUnseededRandom()` is at client/resources/sounds/SoundInstance.java:52.
- Sound ids are in sounds/SoundEvents.java: `experience_orb.pickup` at 603, `note_block.pling` 1136, `note_block.chime` 1131, `note_block.bell` 1130, `note_block.hat` 1135, `note_block.snare` 1137, `note_block.bass` 1129, `amethyst_block.chime` 46, `player.levelup` 1266.
- **No custom sound files ship.** That means no `sounds.json` and nothing to license.

`SoundPreview.preview(id, vol, pitch, channel)` plays the same way on `channel` (null = the saved `feedback.soundChannel`), ignoring the toggles and the throttle.

`SoundSuggestions.IDS` (CONFIGUI), each prefixed `minecraft:`:
- `entity.experience_orb.pickup`
- `block.note_block.pling`, `block.note_block.chime`, `block.note_block.bell`, `block.note_block.hat`, `block.note_block.bass`, `block.note_block.bit`, `block.note_block.xylophone`, `block.note_block.iron_xylophone`, `block.note_block.cow_bell`, `block.note_block.harp`, `block.note_block.snare`, `block.note_block.basedrum`, `block.note_block.flute`, `block.note_block.guitar`, `block.note_block.banjo`
- `block.amethyst_block.chime`, `block.amethyst_block.hit`
- `entity.arrow.hit_player`, `entity.item.pickup`, `entity.player.levelup`
- `entity.player.attack.crit`, `entity.player.attack.strong`, `entity.player.attack.knockback`, `entity.player.attack.weak`
- `ui.button.click`

All are verified in SoundEvents.java.

### 8.2 Chat output (FEEDBACK `ChatReporter`, `ChatFormat`)

- **Delivery.** `mc.player.displayClientMessage(component, false)` (client/player/LocalPlayer.java:413).
- **When chat is allowed.** `display.mode != HUD_ONLY`, `chatMode != OFF`, `general.enabled`, and `mc.player != null` (a fight finalised after a disconnect prints nothing).
- **Width budget.** Vanilla's default chat width is `floor(1.0·280 + 40) = 320` px (client/gui/components/ChatComponent.java:364–367). Every KWAMSTER line is designed to stay under about 300 px at default settings so nothing wraps mid-line.
- **Prefix.** `[KW]` in accent `0x8B5CF6` bold, then a space.
- **Separators.** ` · ` in `0x6B7280`. Labels use `0x9AA4B2`, values `0xF1F3F5`, and tier or result words use their colour.
- **Styling.** `Component.literal(..).withColor(int)` and `withStyle(Style.EMPTY.withColor(int).withBold(true)...)` (network/chat/Style.java:163, 183, 227, 293, 315).

**Per hit** (`chatMode` PER_HIT or BOTH), on `onHitResolved` and `onJumpReset`. A landed hit prints only if its tier is `enabled` (§3.4):
- Landed: `[KW] <LABEL> 2.97 · C3 · S5`. LABEL is the tier label in the tier colour, C is `comboAfter`, S is `streakAfter`. The hover is `HoverEvent.ShowText` (network/chat/HoverEvent.java:149): `Charge 100% · Aim HIGH · Sprint no · Interval 548 ms · Range 2.97 / 3.00`, where Interval is `HitResolvedEvent.intervalMs` ("—" when −1); FEEDBACK never recomputes S8.
- NO-REG: `[KW] NO-REG 2.97` in grey, hover "The target was still invulnerable from the previous hit; the server most likely ignored this one."
- Miss (if `chatHitMisses`): `[KW] MISS 3.42 (over reach)` in grey. "(over reach)" only appears when `reach > rangeLimit`, and the figure then never reads at or below the range (`StatFormat.reachVsRange`, §7.0). The hover is `Missed <name> · Range 3.42 / 3.00`.
- Jump reset (if `chatJumpResets`), in game ticks (§5.1): `[KW] Jump reset ✔ tick 1` in good colour; `✘ tick 4 · late` or `✘ 1 tick early` in bad colour. Hover: "≈ 42 ms after the hit (wall clock)".

**Per fight** (`chatMode` PER_FIGHT or BOTH), on `onFightEnded` with `alert == true`. Up to three lines, each under about 300 px:
- Line 1 (always): `[KW] WIN vs Kwamix · 0:42  [Copy link] [Open]` (about 230 px with a 6-character name; names are truncated to 16 characters).
  - The hover on the result text is multi-line full stats (every §4.7 group). Its misses line reads "Misses N · avg X · max Y · over reach K".
  - `[Copy link]`: `0x22D3EE`, underlined, `ClickEvent.CopyToClipboard(link.url())` (network/chat/ClickEvent.java:75), hover "Copy share link (N chars)".
  - `[Open]`: same style, `ClickEvent.OpenUrl(uri)` (ClickEvent.java:123, `record OpenUrl(URI uri)`), hover "Open the fight viewer". `uri` is built with `new URI(link.url())` inside `try/catch`; on failure `[Open]` is omitted and the rest of the line is still sent (the base URL is also validated up front, §3.8, §9.1).
  - Vanilla handles both for client messages: `Screen.defaultHandleClickEvent` (client/gui/screens/Screen.java:286–330) copies, or opens through `clickUrlAction` (347–363), which honours the chat-links and confirm-prompt options.
  - The actions are omitted when `chatShareActions == false` or the link is `null`. If the link was downsampled, `[Copy link]`'s hover adds "replay thinned to fit".
- Line 2 (`chatFightLines ≥ 2`): `   Hits 23/31 (74%) · Reach 2.84/3.00 · Crits 6 · JR 5/7` (about 270 px).
- Line 3 (`chatFightLines == 3`): `   Combo 5 · Streak 9 · Sprint 12 · 3-Block 4 · Taken 14` (about 280 px). Trades and advantage are in line 1's hover.
- The link comes from `Kwamster.services().archive().shareLink(record)`. That is a pure computation, independent of saving.

---

## 9. Share payload v1 and viewer

### 9.1 Transport

- **URL.** `url = base + "#" + base64url_nopad(deflateRaw(payloadBytes))`.
- **Base normalisation.** Start from `shareBaseUrl`, trimmed. Cut anything from the first `#`. Then append `/` only when the base contains no `?` **and** its last path segment contains no `.` (so `https://host/KWAMSTER` → `https://host/KWAMSTER/`, while `https://host/viewer/index.html` and `https://host/v?x=1` are kept as they are).
- **Base validation** (same check as the Cloth error supplier and `clampAndFill`, §3.8): `new URI(base)` must succeed (this rejects spaces, `|`, `^` and similar), the scheme must be `http` or `https` (case-insensitive), and the host must be non-empty. Otherwise `shareLink` returns `null`.
- **Deflate.** Java `new Deflater(Deflater.BEST_COMPRESSION, true)` (raw, like combat-tracker `SharePayload.deflate`).
- **Base64.** `Base64.getUrlEncoder().withoutPadding()`.
- **JavaScript side.** `atob` after mapping `-`→`+` and `_`→`/` and padding, then `new Blob([bytes]).stream().pipeThrough(new DecompressionStream('deflate-raw'))` (the combat-tracker `docs/index.html` `b64urlToBytes` and `inflateRaw` pattern).
- **Budget.** `url.length() ≤ recording.shareMaxChars` (default 1900, which fits one Discord message with room to spare).

### 9.2 Primitive encodings (Java writer = JS reader)

| Name | Encoding |
|---|---|
| `u8` | 1 byte, 0–255 |
| `i8` | 1 byte two's complement (JS: `b > 127 ? b − 256 : b`) |
| `varInt` / `varLong` | unsigned LEB128: 7 bits per byte, low group first, high bit = continue. JS uses multiplication rather than `<<`, to stay exact above 2^31. |
| `zig` | zigzag then varLong: `(n << 1) ^ (n >> 63)`. JS decode: `(v % 2 === 0) ? v / 2 : −(v + 1) / 2`. |
| `str` | varInt byte length, then UTF-8 bytes. The writer truncates to the field's byte cap **on a code-point boundary**. |

### 9.3 Byte layout, version 1

**Opponent table.** The payload names up to four opponents: index 0 is the primary (H4); indices 1–3 are the other **engaged** opponents, by descending (landed hits + hits taken), ties to the lower record index. With more than one entry the MULTI flag is set. Events against an opponent that is not in the table (a bystander who was only whiffed at, or a fifth engaged player) are left out of the event stream before E1 is counted; the summary block still counts them.

| # | Field | Type | Notes |
|---|---|---|---|
| H1 | version | u8 | `1` |
| H2 | flags | u8 | bit0 POSITIONS, bit1 DOWNSAMPLED, bit2 DAMAGE, bit3 SERVER, bit4 MULTI. Bits 5–7 are 0 in v1; readers ignore them. |
| H3 | playerName | str | cap 16 bytes |
| H4 | opponentName | str | primary opponent (table index 0), cap 16 bytes |
| H4a | extraCount | u8 | only if MULTI: 1–3 |
| H4b | extraNames | str × extraCount | only if MULTI: table indices 1…extraCount, cap 16 bytes each |
| H5 | server | str | only if SERVER. Cap 32 bytes. Omitted when `!shareIncludeServer`, empty, or an IP literal (IPv4 dotted quad or contains `:`/`[`). |
| H6 | startEpochSec | varLong | `startEpochMs / 1000` |
| H7 | durationMs | varInt | |
| H8 | resultReason | u8 | `result.code() \| (endReason.code << 2)` |
| H9 | pingMs | varInt | |
| H10 | threeBlockCm | varInt | `round(settings.threeBlockReach · 100)` |
| H11 | jumpMinMs | varInt | game ms (§5.1) |
| H12 | jumpMaxMs | varInt | game ms |
| H13 | jumpAttemptMs | varInt | game ms (viewer jump-strip axis) |
| H14 | comboTimeoutMs | varInt | for the viewer's combo counters and advantage line |
| H15 | advantageWindowMs | varInt | for the viewer's advantage line |
| S1–S6 | swings, landed, crits, sprintHits, hitsTaken, projectileHitsTaken | varInt ×6 | exact, over every event |
| S7–S11 | reachAvgCm, reachMaxCm, missReachAvgCm, missReachMaxCm, overReachMisses | varInt ×5 | S7–S10: cm = `floor((blocks + 1e-6)·100)` (`SharePayloadCodec.reachCm`, rounded down so "max 2.95" never appears without a 3-block reach). S10 is `reachCmVsRange(missReachMax, missReachMaxLimit)`: at least `reachCm(limit) + 1` when the longest miss was over its range (§7.0). S11 is a count |
| S12–S15 | aimCenter, aimEdge, aimHigh, aimLow | varInt ×4 | |
| S16–S22 | quality[0..6] | varInt ×7 | `HitQuality.code()` order |
| S23–S27 | bestCombo, combosDealt, bestComboTaken, combosTaken, bestStreak | varInt ×5 | |
| S28–S30 | intervalAvgMs, intervalSdMs, intervalCount | varInt ×3 | rounded |
| S31–S34 | jumpAttempts, jumpSuccess, jumpEarly, jumpOpportunities | varInt ×4 | |
| S35–S37 | trades, tradesWon, tradesLost | varInt ×3 | |
| S38 | avgMomentumPct | zig | rounded |
| S39 | hitsConfirmed | varInt | |
| S40 | immuneHits | varInt | NO-REG hits |
| S41–S42 | advYouPct, advThemPct | varInt ×2 | rounded |
| S43–S44 | damageDealtX10, damageTakenX10 | varInt ×2 | only if DAMAGE (`round(hp·10)`) |
| E1 | totalEvents | varInt | events in the stream before downsampling |
| E2 | eventCount | varInt | events encoded |
| E3… | events | — | see below |

**Event stream.** Build the list from the record: swings (kind HIT if landed, otherwise MISS), hitsTaken (TAKEN), jumps (JUMP), plus one DEATH at `t = durationMs` if the result is WIN or LOSS by death. Drop events whose opponent is not in the table. **Sort on the exact ms `t`, before quantisation,** by `(t, rank, record order)` with rank TAKEN 0 < HIT = MISS 1 < JUMP 2 < DEATH 3 (the record's own tie rule, §5). The viewer keeps stream order and never re-sorts. For each event:

| Field | Type | Notes |
|---|---|---|
| dtCs | varInt | `qt_i − qt_{i−1}`, where `qt = max(0, round(t/10))` (centiseconds) and `qt_{−1} = 0`. No drift accumulates. |
| tag | u8 | bits 0–2 kind: 0 HIT, 1 MISS, 2 TAKEN, 3 JUMP, 4 DEATH; 5–7 are reserved (the reader stops and renders what it has). Bits 3–5 `arg` (below). Bit 6 NO-REG (HIT only; 0 for other kinds). Bit 7 is 0 in v1; readers ignore it. |
| opp | u8 | only if MULTI, for HIT, MISS, TAKEN, and DEATH with arg bit0 = 0 (an opponent died): the table index 0–3. |
| HIT (arg = quality code) | `u8 reachQ`, `i8 aimH`, `i8 aimV` | `reachQ = clamp(floor((reach + 1e-6)·40), 0, 255)`: 1/40-block steps rounded down with HitGrader's epsilon (2.95 → 118, 2.9499 → 117), so a decoded reach never overstates. The viewer shows it as `floor(q·2.5)/100` (118 → 2.95, 117 → 2.92, 119 → 2.97). `aimH = clamp(round(h·100), −127, 127)`, aimV likewise. |
| MISS (arg bit0 = over reach) | `u8 reachQ`, `i8 aimH`, `i8 aimV` | misses may clamp at ±127. An over-reach miss carries `max(reachQ(reach), reachQ(rangeLimit) + 1)` (`SharePayloadCodec.overReachShown`), so the viewer never draws it at or inside the range (3.004 against 3.00 reads 3.02, not 3.00) |
| TAKEN (arg bit0 = selfOnGround) | `u8 reachQ` | attacker reach |
| JUMP (arg bit0 = success) | `zig deltaMs` | game ms, a multiple of 50 (§5.1) |
| DEATH (arg bit0 = you died) | — | |
| position (only if POSITIONS, kinds 0–2) | `zig sdx`, `zig sdz`, `u8 bearing`, `varInt distDm` | Own position in decimetres: `qs = (round(sx·10), round(sz·10))`, delta from the previous position-bearing event (the first is from (0,0)). `bearing = round(atan2(oz−sz, ox−sx)/(2π)·256) mod 256`. `distDm = clamp(round(hypot(ox−sx, oz−sz)·10), 0, 2000)`. The viewer reconstructs **that event's opponent** as `self + dist·(cos θ, sin θ)` with `θ = bearing·2π/256`. |

The viewer recomputes hit zones from (aimH, aimV) with the §5.3 rule. This is approximate at boundaries; the summary counts are exact.

### 9.4 Size budget (measured)

The figures below were measured in review round 1 with an exact simulation of the rev-1 layout (Python `zlib` level 9 raw ≙ Java `Deflater(BEST_COMPRESSION, true)`, jittered reach, aim and positions, 1v1, positions on, default 39-character base URL). Rev 2 adds about 8 raw bytes (H13–H15, S33 jumpEarly, S40 immuneHits), i.e. about +11 URL characters, which is not included below; 1v1 links carry no MULTI bytes.

| Fight | Events | Raw | Deflate ratio | Whole URL |
|---|---|---|---|---|
| 60 s, 40 swings (28 landed), 25 taken, 6 jump attempts (trades are summary-only) | 72 | ≈ 678 B | 0.99–1.01 (can expand slightly) | 934–951 |
| The worked case: 80 swings (55 landed), 40 taken, 12 jumps | 132 | ≈ 1155 B | 0.93–0.97 | 1477–1542 |
| 3 min at the first row's pace | 213 | — | — | full 2385–2401; thinned to f ≈ 0.76 (161–163 events) 1891–1897 |
| Heavy 3 min | 580 | — | — | ≈ 155 events kept (27 %) |
| 5 min test fight | 600 | — | — | ≈ 147 events kept |

- **Capacity.** About **160 events** fit unthinned (≈ 158 after the rev-2 additions). Deflate gains little on this quantised binary (ratio ≈ 0.93–1.0); the budget holds because the encoding is already dense (≈ 8 B per event).
- **Verification.** `ShareLinksTest` asserts the worked 132-event case unthinned (≤ 1900, DOWNSAMPLED off) and the 600-event case thinned (§12.2), rather than trusting these numbers.

### 9.5 Downsampling (Java only; `ShareLinks`)

1. Encode everything. If the URL fits, done (DOWNSAMPLED off).
2. Otherwise thin with a keep fraction `f ∈ [0, 1]` and set DOWNSAMPLED:
   - **Always kept:** the DEATH event, and the first and last event of each kind.
   - **Priority events** (every HIT whose quality is a 3-block tier or CRIT, codes 0–3): keep `round(min(1, 2f)·n_P)`, evenly spaced.
   - **Everything else**, per kind k ∈ {HIT, MISS, TAKEN, JUMP}: keep `round(f·n_k)`, evenly spaced.
   - Evenly spaced within a group of n keeping m: `idx_j = m == 1 ? 0 : floor(j·(n − 1)/(m − 1))`.
   - **Search.** Binary-search `f = i/4096` over 13 iterations, plus a final probe at `f = 0`. URL length is **not strictly monotone** in f, so every probe is recorded and the fitting probe that keeps the most events wins (not merely the last one tried).
3. If no probe fits, clear POSITIONS and repeat step 2.
4. If it still does not fit, drop SERVER and cap names at 10 bytes, then repeat.
5. Return the best result, even if it is over budget, with `ShareLink.length` reported so callers can warn. The summary block is always exact.

### 9.6 Versioning, the reference decoder and golden vectors

- The version byte is the contract. The viewer must decode every version ever released.
- Additive changes may only append fields after E-stream-independent sections inside a **new** version number. v1 has no optional trailing data.
- An unknown version gets the message "This link was made by a newer KWAMSTER. Refresh the page; if it persists, the site has not been updated yet."
- **Java mirror.** `SharePayloadCodec.decode(byte[]) → Decoded` is the reference decoder:

```java
public record Decoded(int version, int flags, String player, List<String> opponents /* index = table index */,
	@Nullable String server, long startEpochSec, int durationMs, int result, int endReason, int pingMs,
	int threeBlockCm, int jumpMinMs, int jumpMaxMs, int jumpAttemptMs, int comboTimeoutMs, int advantageWindowMs,
	int[] summary /* S1..S42 (+S43, S44 if DAMAGE) in table order; S38 signed */,
	int totalEvents, List<Ev> events, boolean truncated /* stopped at a reserved kind */) {
	public record Ev(int tCs /* absolute */, int kind, int arg, boolean noReg, int opp /* 0 unless MULTI */,
		int reachQ /* −1 if absent */, int aimH, int aimV /* 0 if absent */, int deltaMs /* JUMP, else 0 */,
		boolean hasPos, int sxDm, int szDm /* absolute */, int bearing, int distDm) {}
}
```

- **Decoder limits (Java = JS; each rejects what the other rejects, as MALFORMED unless noted).** A `varInt` above 2^31−1; a varint longer than 10 bytes or whose 10th byte is above 1; `startEpochSec` above 2^53−1 (`SharePayloadCodec.MAX_START_EPOCH_SEC`); a MULTI `extraCount` outside 1–3; an event `tCs` above 2^31−1; a zigzag field (S38, JUMP `deltaMs`, position delta) outside the int range; a running position outside the int range. The inflated payload is capped at 64 KiB in both (`SharePayloadCodec.MAX_PAYLOAD_BYTES` = `KwDecoder.MAX_INFLATED_BYTES` = `1 << 16`; TOO_LARGE).
- **JS decoder.** `docs/index.html` contains `<script id="kw-decoder">` that touches no DOM and defines `globalThis.KwDecoder = { isSupported(), b64urlToBytes(str), inflateRaw(bytes) /* Promise */, decode(bytes) /* plain object with exactly the Decoded field names */, decodeFragment(str) /* Promise */, validate(d) /* null or a short reason */, LIMITS, MAX_INFLATED_BYTES }`. `validate` is the viewer's stricter render gate and is not part of `decode`: events ≤ 20,000; `events.length ≤ totalEvents ≤ 1,000,000` (totalEvents is only a display number, so a long real fight is never refused); `durationMs` and event times ≤ 24 h; jumpMin/Max/AttemptMs ≤ 1000; comboTimeoutMs and advantageWindowMs ≤ 60,000; threeBlockCm ≤ 1000; distDm ≤ 2000; `opp` < number of opponents; names ≤ 64 characters; server ≤ 128. `isSupported()` returns false when `new DecompressionStream('deflate-raw')` throws or `DecompressionStream` is undefined (Chrome/Edge 80–102 have the class but reject `'deflate-raw'`). Strings are decoded with one `TextDecoder('utf-8', { ignoreBOM: true })`: a leading U+FEFF is part of the name, exactly as in Java's `new String(bytes, UTF_8)` (the default `TextDecoder` would drop it, and `PlayerRefs` only strips § codes and ASCII whitespace, so an NPC or bot name can start with one).
- **Golden vectors.** `SharePayloadCodecTest` writes `build/share-vectors/<name>.txt` (the fragment, without `#`) and `<name>.json` (Gson of `Decoded`) for: `empty`, every flag combination (POSITIONS × DAMAGE × SERVER × MULTI, plus DOWNSAMPLED), `thinned`, `reserved_kind`, `multibyte_name`, `bom_name` (a player name that is only U+FEFF and an opponent name that starts with it), `noreg`, `at_size_cap` (exactly 64 KiB inflated, accepted) and `example` (from `SampleFights.example()`). It also writes `build/share-vectors/rejected/<name>.txt` (12 malformed payloads plus `over_size_cap`), asserting the Java decoder rejects each.
- **Viewer check.** `tools/viewer-check.mjs` (§12.1) runs the JS decoder on every vector and deep-compares with the JSON, requires every golden vector to pass `KwDecoder.validate`, and requires every `rejected/*.txt` vector to be rejected. Every `KwDecoder` function above and `LIMITS` (with each of its nine keys) are required exports, and the vectors `empty`, `example`, `thinned`, `reserved_kind`, `multibyte_name`, `bom_name`, `noreg`, `downsampled`, `at_size_cap` and `rejected/over_size_cap`, `rejected/event_time_overflow` must exist: a missing export or vector fails the check instead of silently skipping a section. It runs 13 crafted DoS links (each must be refused by `decode` or `validate` within 2 s) and one link exactly at the limits (must be accepted). It also decodes the page's embedded example (`<script id="kw-example" type="text/plain">`, which RECORD fills with the `example` vector's fragment) and requires it to equal `example.json`, so the "Load example" payload is always valid. Options: `--vectors <dir>`; `--write-example` copies `example.txt` into the page's `kw-example` (run it when a stats change alters `example.json`, then run the check again).

### 9.7 Viewer page (`docs/index.html`, RECORD)

The page is a single self-contained file: inline CSS and JS, system font stack `ui-sans-serif, system-ui, "Segoe UI", Roboto, sans-serif`, **no external requests** (no fonts, no player heads, no analytics), and a `<noscript>` notice. It is dark, matching the HUD:
- `--bg #0B0D12`, `--panel #12151C`, `--line #232836`, `--text #E8EAED`, `--muted #9AA4B2`, `--accent #8B5CF6`, `--accent2 #22D3EE`, `--win #3DDC84`, `--loss #FF5A5F`, `--draw #FFC53D`
- tier colours as in §3.4 defaults; opponent colours (table index 0–3) `#FF7A59`, `#FFB347`, `#4DD4AC`, `#7AA7FF`

**Head.** Static `<meta property="og:title" content="KWAMSTER fight replay">`, `<meta property="og:description" content="Fight stats, timeline and replay from the KWAMSTER Minecraft mod. The data lives in the link.">`, and `<meta name="theme-color" content="#8B5CF6">`. Discord's crawler never sees the fragment, so the embed is generic by design. After a successful decode, `document.title = "WIN vs Kwamix · KWAMSTER"` (result and primary opponent).

**Startup.** If `KwDecoder.isSupported()` is false, show the "unsupported browser" card (Chrome 103+, Firefox 113+, Safari 16.4+) **before** trying to decode, so the link is never blamed. After a successful decode the page calls `KwDecoder.validate`; if it fails, the page shows the "This link could not be read" card with the detail "Out of range: <reason>" and draws nothing. Every untrusted string reaches the DOM through `esc()` or `textContent` (or canvas `fillText` / `document.title`).

Layout:
- Maximum width 1100 px, 16 px side gutters on phones, no horizontal page scroll.
- Cards: radius 12 px, 1 px `--line` border.

Sections, in order:
1. **Header.** "KWAMSTER" wordmark (accent). Result badge (WIN, LOSS or DRAW in its colour). "Player vs Opponent" (every table name: "Player vs A & B", each in its opponent colour). Local-time date. Duration. Server and ping chips. An initials avatar (CSS circle, no image).
2. **Stat cards** in a responsive grid (every reach figure rounded down, `reachQText(q) = floor(q·2.5)/100` and cm values as `floor`, so the viewer never overstates a reach; over-reach figures arrive already raised above the range by the encoder, §9.3): Accuracy (landed/swings), Reach avg/max (plus a second line "Misses avg X · max Y · N over reach" whenever swings > landed; with 0 landed it still shows "No hits landed" plus the miss line), Crits, Sprint hits, 3-block hits, Jump resets (with chances and early), Best combo (dealt/taken), Best streak, Hits taken, Avg interval ± SD, Trades W/L, Damage dealt/taken (only with the DAMAGE flag), Confirmed %, NO-REG hits, Advantage share.
3. **Timeline** (inline SVG, 220 px tall, inside its own card).
   - Time axis in seconds. Grid ticks are at most one per 20 px (step = `max(tickStep, span/(PW/20))`).
   - Lanes: "You hit" (dots in tier colour, 3-block hits get a ring, NO-REG hits are hollow), "Miss" (grey ticks, over-reach ticks red-tinted), "Taken" (`--loss` dots), "Jump reset" (✔/✘ markers).
   - Above the lanes, an advantage area line recomputed client-side with the §5.5 formula from the decoded events, using H14/H15 and skipping NO-REG hits (labelled approximate when DOWNSAMPLED).
   - Zoom and pan: wheel zoom while the pointer is over the timeline (the page does not scroll then), pinch zoom on touch, drag to pan, double-click/double-tap to reset. The SVG never widens the page; any overflow scrolls inside the card.
   - Hover tooltip per event. Clicking seeks the replay.
4. **Replay** (`<canvas>`, 16:9, DPR-aware).
   - Controls: play/pause, speed 0.5×/1×/2×/4×, scrubber synced with the timeline, a time readout, and live combo and streak counters (NO-REG hits excluded, H14 timeout).
   - Top-down view (x right, z down). The camera frames the bounding box of positions within ±2 s of the playhead: you, plus each opponent with an event in that window or in focus. Padding is 1.25 blocks, inside pixel margins for the name labels and pills above and the scale bar below. Maximum zoom shows 5 blocks across the shorter side; minimum zoom is 6 px/block, and if even that cannot hold everyone, you stay in view. While playing, the camera eases towards that fit (`easeCamera`): the zoom in log space, quickly when it widens (0.25 per frame) and gently when it tightens (0.06, so the view does not pump), the centre at 0.15 per frame, and the eased centre is clamped again at the eased zoom so you never leave the stage mid-ease (`fitCamera` only guarantees it at the target zoom; a 35-block pearl used to leave an empty grid for about 0.9 s). It is exact when paused; the camera samples at most 64 positions per track. The scale bar uses the camera's px/block.
   - Grid: 1-block lines `#1A1E28` (left out below 8 px/block), 5-block lines `#232836`.
   - Bounds: at most 48 event bursts per frame and 4 death marks.
   - Players: you are a violet disc (radius 0.3 block) with a wedge facing the opponent of the latest event; **each opponent is its own disc** in its opponent colour, positioned only from events against that opponent and linearly interpolated between them (never between two different players). Each has a fading 1 s trail.
   - Event bursts: HIT is an expanding ring at that opponent in the tier colour plus a floating label "2.97 3-BLOCK CRIT" (NO-REG: grey "NO-REG"); MISS is a grey dashed arc from you; TAKEN is a red ring at you; JUMP is a small "JR ✔/✘" above you; DEATH is an × with the result colour.
   - A distance line between you and the opponent of the latest event, labelled in blocks.
   - Without the POSITIONS flag, it shows a 1-D "distance over time" strip instead.
5. **Aim map** (SVG, **108 × 324**, the 1:3 front hitbox of a 0.6 × 1.8 player).
   - Guides at v = ±1/3 and h = ±0.5. Zone counts as bars beside it.
   - Hits are tier-coloured dots (r = 4). Misses are hollow grey circles at clamped positions, in a 27 % margin around the box.
6. **Reach histogram.**
   - A "<1.5" bin, then 0.1-block bins from 1.5 to 3.0, then a ">3.0" bin.
   - Landed in accent, misses grey, with a dashed threshold line at `threeBlockCm/100`.
7. **Quality breakdown.** Seven horizontal bars with count and %.
8. **Jump resets strip.** Game-ms deltas on a `−A … +A` axis with `A = clamp(jumpAttemptMs, 100, 1000)` (H13), a green band `[jumpMin, jumpMax]`, and tick marks every 50 ms (one game tick).
9. **Footer.** "Data lives entirely in this link. Nothing was uploaded." · "KWAMSTER by builtdoor" linking to the repo.

When the fragment is empty, the page shows a landing panel: what KWAMSTER is, how to get a link, and a "Load example" button that decodes the embedded `kw-example` payload (the golden `example` vector, §9.6). When decoding fails, it shows a friendly error card. Thinned data gets the note "Replay shows N of M events; the numbers above use all M".

---

## 10. Persistence (RECORD, plus CORE for the config)

The base directory is `FabricLoader.getInstance().getConfigDir()/kwamster/` (`Kwamster.dataDir()`).

| File | Owner | Format | Written |
|---|---|---|---|
| `config.json` | CORE | pretty Gson `KwamsterConfig` | on Cloth save, editor close, keybind toggles |
| `fights/<id>.json` | RECORD | compact Gson `FightRecord` (with `stats`) | once, at fight end |
| `fights/index.json` | RECORD | `{"schema":1,"fights":[FightSummaryRow…]}`, newest first | after every save, delete or pin |
| `lifetime.json` | RECORD | pretty Gson `StatsTotals` | after every counted fight, and on reset |

- **Id and file name.** `id = yyyyMMdd-HHmmss-SSS` (UTC, from `startEpochMs`) `+ "-" + opponent` sanitised to `[A-Za-z0-9_]`, at most 16 characters, empty → `unknown`. Example: `20261020-153012-123-Kwamix`. On a name collision, append `-2`, `-3`… and update `record.id`.
- **Atomic writes** (`AtomicFiles.writeString(Path, String)`, CORE):
  - Write `<file>.tmp` (UTF-8), then `Files.move(tmp, file, REPLACE_EXISTING, ATOMIC_MOVE)`.
  - On `AtomicMoveNotSupportedException`, retry without `ATOMIC_MOVE`.
  - This is screentime `UsageStore`'s pattern.
- **IO thread.** RECORD owns one daemon single-thread executor named `KWAMSTER-IO`.
  1. On the client thread, `onFightEnded` decides whether to save: `saveFights`, and `alert || saveTrivialFights` (it does not check `general.enabled`, so a DISABLED fight is saved, R4). If saving, it assigns the file, updates the **in-memory** index and lifetime totals (lifetime only if `alert`), and enqueues writes in order: fight file → index → prune deletions → lifetime.
  2. **Serialisation happens on the client thread.** Every payload (fight JSON, index JSON, lifetime JSON) is turned into an immutable `String` with `Json.GSON`/`Json.PRETTY` on the client thread before it is enqueued. The executor receives only `(Path, String)` write jobs and `Path` delete jobs and calls `AtomicFiles`; it never sees a live list, record or totals object. `SavedFightsScreen`'s pin and delete actions follow the same rule (mutate the in-memory index on the client thread, serialise, enqueue). This rules out `ConcurrentModificationException` and torn JSON.
  3. Shutdown: `FightStore.shutdown()` (the `FightArchive.shutdown()` contract) calls `executor.shutdown()` and `awaitTermination(2 s)`. It is called only by CORE's single `CLIENT_STOPPING` handler, **after** `forceEnd(GAME_EXIT)` has enqueued the last fight (§2.1). RECORD registers no lifecycle listener of its own. A `submit` after shutdown (should never happen) is caught and logged, not thrown.
- **Loading.** At `init()`, read `index.json`.
  - If it is missing or unparsable, rebuild it by scanning `fights/*.json`. Skip `index.json`. Move unparsable files to `fights/broken/`. Sort by `startEpochMs` descending.
  - Read `lifetime.json`. If it is missing, start empty with `sinceEpochMs = now`. If it is broken, back it up and start empty.
  - `load(id)` reads one file on demand, on the client thread; it is small.
- **Pruning.** If `maxSavedFights > 0` and the number of unpinned fights exceeds the limit, delete unpinned **trivial** fights (`FightSummaryRow.trivial`, i.e. saved with `alert == false`) oldest first, then unpinned real fights oldest first, and remove them from the index. A **real** fight just saved is never pruned. A **trivial** fight just saved never pushes out a real one: it goes last among the trivial fights, so when no older trivial fight is left to remove, the new one is removed itself (`FightStore` then writes neither its file nor an unchanged index), and the real-fight pass does not run for a trivial save at all (after a lowered limit, the next real save catches up). Pinned fights are never pruned. A lobby full of whiffs therefore never evicts a real fight, even when every slot holds one. Legacy rows and rows rebuilt from fight files count as real (a fight file does not record whether it alerted).
- **Session totals.** Kept in memory in CORE (`FightService.session()`), from game start, and resettable.
- **Saved-fights screen** (`SavedFightsScreen`, RECORD):
  - Title "Saved fights". Top line in dim text: lifetime summary, "132 fights · 78W 41L 13D · 71% acc · since Oct 20".
  - A `FightList` (`ObjectSelectionList`, rows 34 px). Each row has:
    - a 3 px result-colour strip
    - "WIN vs Kwamix" (bold result word; a multi-opponent fight shows "WIN vs Kwamix +1"; the name is truncated with `StatFormat.truncate` to the row width)
    - a second line in dim text: "Oct 20 15:30 · 0:42 · 74% · 2.84 · combo 5"
    - ★ when pinned
  - Bottom buttons, acting on the selected row:
    - "Copy link": `keyboardHandler.setClipboard`, button label becomes "Copied ✔" for 1.5 s.
    - "Open": `Util.getPlatform().openUri(URI)`, util/Util.java:1107.
    - "Pin" / "Unpin".
    - "Delete": two-click confirm, label "Click again to delete".
  - Always available: "Open folder" (`Util.getPlatform().openPath(folder)`, Util.java:1122) and "Done".
  - Empty state: "No fights saved yet. Fight someone!"

---

## 11. Keybinds (CORE `Keybinds`)

```java
public static final KeyMapping.Category CATEGORY =
	KeyMapping.Category.register(Identifier.fromNamespaceAndPath("kwamster", "main"));   // KeyMapping.java:217
// label key = "key.category.kwamster.main" (Category.label(): id.toLanguageKey("key.category"), KeyMapping.java:227)
new KeyMapping("key.kwamster.open_settings", GLFW.GLFW_KEY_K, CATEGORY);                  // KeyMapping(String,int,Category) KeyMapping.java:90
```

| Key id | Default | Action (handled in `Keybinds.handle`; each `while (key.consumeClick())` sets a flag; act after draining) |
|---|---|---|
| `key.kwamster.open_settings` | K | `screens().open(SETTINGS, null)` |
| `key.kwamster.edit_hud` | unbound | `screens().open(HUD_EDITOR, null)` (also reachable from the "Edit HUD layout…" button in settings) |
| `key.kwamster.toggle_hud` | unbound | flip `general.hudHidden`, save, action bar "KWAMSTER HUD hidden/shown" |
| `key.kwamster.saved_fights` | unbound | `screens().open(SAVED_FIGHTS, null)` |
| `key.kwamster.copy_last_link` | unbound | `archive().shareLink(fights().lastRecord())` → clipboard, action bar "Fight link copied (N chars)" or "No fight to share yet" |
| `key.kwamster.toggle_crosshair` | unbound | flip `crosshair.enabled`, save, action bar "Custom crosshair ON/OFF" |

- Action-bar messages use `mc.player.displayClientMessage(Component, true)`.
- Screens open only when `mc.screen == null`, so typing in chat cannot open them.
- **Default-key audit (1.21.11).** K is unbound in vanilla (checked against every default in client/Options.java). H is **not** free: it is `key.debug.showAdvancedTooltips` (F3+H) in the DEBUG category (Options.java:634–636), so `edit_hud` ships unbound. K clashes with combat-report's open-screen key (CombatReportClient.java:40); the README tells users to remove Combat Report (§13 risk 10).

`src/main/resources/assets/kwamster/lang/en_us.json`:

```json
{
	"key.category.kwamster.main": "KWAMSTER",
	"key.kwamster.open_settings": "Open settings",
	"key.kwamster.edit_hud": "Edit HUD layout",
	"key.kwamster.toggle_hud": "Toggle HUD",
	"key.kwamster.saved_fights": "Saved fights",
	"key.kwamster.copy_last_link": "Copy last fight link",
	"key.kwamster.toggle_crosshair": "Toggle custom crosshair"
}
```

---

## 12. Test plan

### 12.1 Build change (TEST appends to `build.gradle`)

```groovy
// Unit tests compile against the Minecraft-free client classes (DESIGN.md R6).
sourceSets {
	test {
		compileClasspath += sourceSets.client.output + sourceSets.client.compileClasspath
		runtimeClasspath += sourceSets.client.output + sourceSets.client.runtimeClasspath
	}
}
```

Run with `JAVA_TOOL_OPTIONS=-Djavax.net.ssl.trustStoreType=Windows-ROOT ./gradlew test`. The gametest runs with `./gradlew runClientGameTest`; screenshots land in `build/run/clientGameTest/screenshots/`.

**Viewer decoder check** (after `gradlew test`, which writes the golden vectors): `node tools/viewer-check.mjs` (Node 21.2+ for `DecompressionStream('deflate-raw')`; v24.16.0 is installed here). It extracts `<script id="kw-decoder">` from `docs/index.html`, evaluates it with `node:vm` in a context exposing only `DecompressionStream`, `Blob`, `atob`, `TextDecoder` and `Uint8Array`, decodes every `build/share-vectors/*.txt`, deep-compares each result with the matching `.json`, requires each to pass `validate`, requires every `rejected/*.txt` to be rejected, runs the crafted DoS links and the at-the-limits link (§9.6), checks the embedded `kw-example` payload against `example.json`, prints one line per check and exits non-zero on any mismatch. A missing `KwDecoder` export (including `LIMITS`) or required vector is a failure, never a skipped section. This is the only automated check of the JS decoder, so it is part of every release build.

### 12.2 JUnit 5 (`src/test/java/kwamster/...`, MC-free classes only)

| Test class | Cases (each one an assertion group) |
|---|---|
| `core/stats/HitGraderTest` | Uncharged at 3.0 → WEAK. Charged crit at 2.949/2.95/2.951 → CRIT/3B_CRIT/3B_CRIT. Sprint → SPRINT/3B_SPRINT. Plain and sweep → NORMAL/THREE_BLOCK. A custom threshold of 2.80 is respected. `customRange = true` at 3.2 → CRIT/SPRINT/NORMAL, never a 3-block tier. `threeBlock()` is true only for codes 0–2. Codes are stable (0..6 in declared order). |
| `core/stats/AimZonesTest` | v = 1/3 → HIGH; v = 0.3332 with h = 0 → CENTER; v = −1/3 → LOW; h = 0.5 → EDGE; h = −0.49 → CENTER; HIGH beats EDGE at (0.9, 0.9); LOW beats EDGE. |
| `capture/AimMathTest` | Standing box: a ray straight at the chest → CENTER (h≈0, v≈0.0–0.1). At the head → HIGH. At the feet → LOW. Grazing the right side → EDGE with h > 0. Sneaking box (1.5 tall): the same world-space ray that was CENTER on a standing box can become HIGH (normalisation uses the real height). Looking straight down uses yaw. Eye inside the box → CENTER and `eyeInside`. Miss offsets exceed 1. |
| `capture/JumpResetWatchTest` | All inputs are `(tick, nanos)` pairs. Grounded hit at tick n, jump at tick n+1 → success, `deltaMs` 0. Jump at n+2 → success (50 ms). Jump at n+3 → late (100 ms). Jump at tick n (processed before the hit) → early, `deltaMs` −50. Jump at n+6 (250 ms game time) → no attempt. Two grounded hits 12 ticks apart, then a jump 1 tick after the second → pairs with the second. A jump with no hit → nothing. **Airborne hit** (`selfOnGround = false`) followed by a landing jump 3 ticks later → no attempt. **Catch-up ticks:** hit at tick n, then ticks n+1…n+3 delivered with identical nanos and the jump in n+3 → late (decided by ticks, `wallMs` 0). Window settings are respected. Reset clears state. **Jump presses** (`onJumpPress`): a late press in the air is a late attempt; a press on tick 2 is a success; a real jump in the window decides and its own same-tick press is ignored; presses pair once; a press past the attempt window is nothing; an air press let go before a grounded hit is early; an air press **held** through a grounded hit makes the next tick's jump a 0 ms success (also a press on the landing tick), or becomes early at the end of that tick when no jump came (`onTickEnd`, once); a held press outside the early window just opens the window; a newer hit replaces the waiting one; an air press before an airborne hit is no attempt and is spent; a real early jump still counts before an airborne hit, also after a later air press (mashing); holding jump through a hit resets on tick 1. |
| `capture/NoRegEstimatorTest` | Anchor and window; two swings in one tick (gap 0, flagged); separate victims; the raw copy is ignored after your own hit, also when its event comes back late (within `lagTicks + 6`); the lagged copy is used after someone else's damage (your projectile counts as other damage), after your sweep on a bystander you never hit and after own damage long after the anchor; window 0 disables; `lagTicks = 1 + round(ping/50)`; stale victims forgotten. A tick-level client/server model at 0/50/100/150 ms over 5 tick phases: charged combos 11/12/13 ticks apart are never flagged (the old raw `> 10` rule is shown to misflag 11-tick combos at 100 ms); spam (20 CPS, 10 CPS, double clicks, a seeded mix) matches the server exactly without jitter; with 20 ms jitter combos have 0 errors and the spam error rate stays ≤ 1/12 and under a third of the old rule's; third-party immunity is flagged when clearly immune (≤ 7 server ticks) and not when clearly free (≥ 12). |
| `capture/RecentDeathsTest` | A body is a corpse for 2 s; a respawned instance is never a corpse; other players are unaffected; expiry and reset; nulls ignored. |
| `capture/SourcelessDamageTest` | Swing-age rule; dropped when nobody is swinging nearby; the nearest swinging player is picked; the fight opponent is preferred only if it qualifies; inclusive reach limit and NaN rejected; a confirmation needs a registered swing between `minConfirmAgeMs` (max(0, min(ping − 50, ping/2))) and ping + 300 ms old and spends it (one event per swing; a too-young event leaves it); knockback: a push away from the attacker counts (standing, sprinting at them, falling), a fire-tick velocity (no push, upward only, towards the attacker) and an unchanged or non-finite velocity do not; an attacker on top of you is judged by speed. |
| `core/stats/StatsCalculatorTest` | Scripted record covering: accuracy; landed reach avg and max; miss reach and over-reach; aim counts sum to landed; quality counts sum to landed; combo sequence D D K D D D → best 3, combosDealt 2, avg 2.5; timeout splits a chain; a miss resets the streak but a hit taken does not; **NO-REG hits** count in landed/quality/immuneHits but neither extend nor break combos or streaks and are skipped by intervals; intervals use `t − clickLeadMs`, exclude > maxGap and cross-opponent pairs; jump pct, early/late and avg delta recomputed from `deltaMs` and the settings snapshot (success + early + late = attempts even if the stored `success` flags disagree); jump opportunities derived from `hitsTaken[].onGround`; the miss maximum keeps the range of the miss that set it (`missReachMaxLimit`); **a WEAK sprinting hit** is not a sprint hit and is in the crit-rate denominator; `sprintHits == quality[1] + quality[4]`; trades win% excludes unknown damage and evens; confirmation matching (**earliest** unconfirmed swing within `latestPing + 300` ms; unmatched confirmations dropped; THORNS never confirms); damage linking windows; an empty record gives all zeros and no NaN; current combo and streak at `nowRel`, and 0 for final; **live replay:** feeding a scripted sequence event by event, `comboAfter`/`streakAfter`/`intervalMs` computed after each event equal `compute(record, tRel)` at that moment, including a same-tick trade (hit taken processed before your swing). |
| `core/stats/AdvantageModelTest` | No events → 0. 3 dealt and 0 taken, holding combo 3 → 1.0. 2 dealt and 2 taken, holding combo 1 → 0.1667. Events older than W are ignored. NO-REG hits are not in `d`. The label thresholds ±0.15 hold. A **0 ms fight** gives n = 1 sample and finite `advYouPct`/`advThemPct`. The final advantage uses the chain state at `durationMs`. |
| `core/fight/FightStateMachineTest` | A miss opens a fight with a provisional (non-engaged) opponent. A melee hit taken opens one (engaged). A projectile from an engaged opponent keeps a fight alive; from anyone else it is ignored and never opens a fight. Idle ends at `> fightIdleMs` (not at exactly 8000). OPPONENT_FAR at a 3-D distance of 16.01. OPPONENT_LEFT when not found. Death of an engaged opponent → WIN; death of a provisional (only-missed) opponent → OPPONENT_LEFT; death of self → LOSS; duration to the death time. Spectator within `deathInferenceMs` after a hit → WIN, after → LEFT. §5.13a: the opponent vanishing or teleporting right after your hit → WIN with a note (not after their hit, not outside the window, not with visible high health); an abrupt draw waits `EVIDENCE_GRACE_MS` and a hint during the wait decides it, a late hint does not; a hint while ACTIVE decides outright (a WIN needs an engaged opponent, a real death is never overridden); your teleport after their hit with low health → LOSS, after your hit → WIN unless you were low too (then a draw, unless their visible health stayed high); WORLD_CHANGE the same way, or a draw that waits; portal travel is never a result; the switches and the window are read live; reset clears the note. A non-authoritative miss at a third player does not retarget or engage; an authoritative hit does. While ENDING, swings, hits taken and spear swings are ignored and cannot open a fight; a second death is ignored. `forceEnding` while ENDING keeps the death reason. `resultOf` mapping. |
| `core/fight/FightAccumulatorTest` | Opponent indices keyed by UUID (a renamed UUID keeps its index and first name). `engaged` set by landed hits and melee hits taken, not by misses. Primary-opponent selection among engaged only; index 0 when nobody is engaged. Positions relative to the origin. Damage drops of non-opponents never add opponents; self drops have `opp = −1`. NaN/Infinity inputs are stored as 0 (−1 for `rayReach`) with `*Known = false`, and the record still serialises with `Json.GSON`. `finish` sets result, reason and duration. Gson round-trip of the finished record is equal field-for-field. |
| `core/fight/FightClockTest` | (Tests the MC-free helper `FightClock` used by FightManager: `pause(now)`, `resume(now)`, `toFight(nanos)`.) A 5 s pause between two events removes 5 s from their `t` difference and from `durationMs`; no idle end is triggered by paused time. |
| `record/SharePayloadCodecTest` | varint and zigzag edge values (0, 127, 128, 2^31, −1, −64, −65). Round-trip of a fixed record: header and summary are exact, events within quantisation (reach ±0.0125, aim ±0.005, t ±5 ms, positions ±0.05). All flag combinations, including MULTI with 2 and 4 opponents (per-event `opp` survives; a non-table bystander's events are omitted while the summary still counts them). The NO-REG bit survives. Tie order: a hit taken and a swing at the same ms decode TAKEN first. Reserved kinds stop decoding gracefully (`truncated = true`). A 4-byte multibyte name is truncated on a code-point boundary. An empty fight encodes and decodes. Quantisers round reach down (`reachQ`, `reachCm`; round-trip bound `0 ≤ reach − q/40 < 0.025`) and the viewer's reach text never overstates the 3-block threshold. Over-reach misses (3.004 → q 121, viewer 3.02) and an over-reach miss maximum (S10 301, not 300) never decode at or inside the range; far-over and in-range misses stay rounded down. A leading U+FEFF stays part of a name. Out-of-range fields are rejected as malformed; the inflated size is capped. **Writes the golden vectors** of §9.6 to `build/share-vectors/`, including `at_size_cap` and the `rejected/` set. |
| `record/FightIndexTest` | Oldest unpinned go first when none is trivial; trivial fights are pruned before any real fight (on a real save); a lobby of whiffs never evicts a real fight, also from a full index of real fights (each whiff removes itself, the next real save prunes as usual); a whiff takes an older whiff's slot before its own; a trivial save never prunes real fights even over a lowered limit; pinned trivial fights survive and a trivial save drops itself before a real fight; a real fight just saved is never pruned; no limit or under the limit removes nothing; legacy rows without the field count as real. |
| `configui/EntryChecksTest` | Label blank/length after trimming; text alpha ≥ 0x20; preview volume warning order (tier volume, Master, then the category) and vanilla names; NaN handling; a null channel means Players. |
| `hud/AnimTest` | Normal progress and age (before the start, zero duration); a never start (`Long.MIN_VALUE`) gives progress 1 (the combo pop lerp gives 1.0, not 1.25), a large positive age and an expired envelope. |
| `record/ShareLinksTest` | The worked synthetic fight (80 swings, 55 landed, 40 taken, 12 jumps = 132 events, positions on, realistic jittered values, fixed RNG seed) with the default base gives URL ≤ 1900 and DOWNSAMPLED **off**. A 5 min fight (600 events) gives ≤ 1900, DOWNSAMPLED on, summary counts identical to the full encode, every DEATH and the first and last event of each kind kept, and a higher kept share of 3-block/crit hits than of other hits. The chosen probe is the fitting one with the most events even when a smaller f produced a longer URL (inject a non-monotone size function). An invalid base (`ftp://x`, blank, `https://a b/`, `https://x/|`) → null. Base normalisation: missing `/` is added; trailing `#x` is cut; `https://h/viewer/index.html` is kept unchanged; `https://h/v?x=1` is kept unchanged. An IP-literal server is omitted. |
| `direction/RadialFilterTest` | Approach at 4.3 b/s → GREEN within 4 ticks. Standing → YELLOW. Retreat → RED. Pure strafe (perpendicular) → YELLOW. Speed oscillating ±0.2 around `enterBps` causes no flicker. A 10-block teleport produces no spike and reseeds. A knockback hold freezes the state for `kbHoldMs`. `smoothingMs = 0` passes the raw value. |
| `hud/HudLayoutMathTest` | Positions for all 9 anchors at W = 480, H = 270. Clamping. Auto-anchor on drop preserves the top-left exactly (±1 px). GUI-scale change (W 960→480) keeps an anchored distance. Snapping to margins, centre and another element; Shift disables it. **Default-layout overlap** (§7.0 validation) at 427×240, 480×270, 640×360 and 960×540 using sizes computed from `PreviewStrings` with `AsciiWidths`: no two default element rects intersect (panel/card pair exempt), none intersects the vanilla crosshair/indicator rect, and the panel and card intersect neither the title band nor the chat band. **Fit:** every `PreviewStrings` panel row and header fits the computed panel width (with the 6 px gap, `AsciiWidths`), at most 168 px; every card cell fits `colW` ≤ 88; the card footer truncates to end 6 px before the 24 px progress line. **Editor scale steps:** at GUI 1–9, coarse scrolling from 0.5 to 3.0 and back moves on every step, lands on a crisp value (or the 0.5 clamp) and takes exactly `3·gs − gs/2` steps each way; scales saved with 3 decimals (1.333, 0.667 at GUI 3; 1.167 at GUI 6; 1.571 at GUI 7) step both ways and count as crisp. **Combo number:** `comboNumberX` centres one and two digits, plain and broken ("4×"); the live number plus its 1 px outline fits the 30 × 26 box and stays clear of the caption. |
| `crosshair/CrosshairShapesTest` | For CROSS, T, X, CIRCLE, DOT, with and without centre dot and outline, for even and odd framebuffer W/H and even and odd t: the union bounding box of the fill is centred within 0.5 px of (W/2, H/2) (T: horizontally). Gap 0 adds the core. T has no top arm. X is pixel-aligned (every span edge is an integer; no rotation). Outline and fill spans are disjoint and every outline pixel is within o (Chebyshev) of a fill pixel; spans never overlap each other (no pixel painted twice). The centre dot is centred. |
| `core/config/KwamsterConfigTest` | `defaults().clampAndFill()` is idempotent. Out-of-range values clamp. Null sections, tiers and layouts are filled. An unknown enum string → default. Gson round-trip. Text colour with alpha 0 → forced 0xFF. An invalid `shareBaseUrl` (`https://a b/`) reverts to the default. Every tier's default sound id is distinct. A syntactically invalid tier sound id (`Minecraft:Ding`, a space, a second `:`) reverts to the tier default, while ids `Identifier.tryParse` accepts (no namespace, empty namespace, resource-pack paths) are kept trimmed. `noRegImmunityTicks` defaults to 10 and clamps to 0–20. `inferOutcomes` and `readOutcomeMessages` default on; `outcomeWindowMs` defaults to 1500 and clamps to 500–5000. |
| `capture/OutcomeTextTest` | Passive kill messages name the victim first (also with rank prefixes, health in brackets and server prefixes like "Duels »"); active ones after the verb; "died", "fell", "is dead" make the subject the victim; fancy "&lt;a&gt; was … by &lt;b&gt;" needs both players and no non-kill verb ("hit", "challenged", "tagged"); winner and loser announcements, "won/lost (the duel)", "won by"; one-word titles; chat spoken by anyone in the fight is ignored (a title never counts as chat); other players' kills, coins, win streaks, lost connection, hit reports decide nothing; names match as the words they become (Geyser's ".Name"); your own death needs no opponent names. |
| `core/config/HudPresetTest` | Every preset applies cleanly and survives `clampAndFill`; apply fills a never-clamped config and keeps the section objects; text colours stay readable; presets write no forbidden field and every look field exists; KWAMSTER is the shipped look; `matching` finds each preset after applying it and none for a custom look; presets are distinct and the last applied wins; crosshair states and bar colours stay apart; stepped values sit on their slider steps; only Minimal switches an element off; each preset shows its rows; names and descriptions; `sample()` is a fresh config each time. |
| `configui/ColorMathTest` | Channels unpack and pack; `withChannel` replaces one channel and clamps; the text-alpha floor only raises low alpha; hex is upper-case with eight digits, accepts six or eight digits with or without `#` and rejects everything else; hex text round-trips for every alpha and channel value; channel text is 1–3 digits; the hex field filter allows only hex input; primaries and secondaries have their hues; **every one of the 16.7 million RGB colours** survives RGB → HSV → RGB exactly; greys keep the previous hue (black also the saturation); HSV → RGB wraps the hue and clamps the rest; control fractions clamp outside the control. |
| `core/stats/StatFormatTest` | pct "—" on a zero denominator. reach 2 decimals, rounded down; agrees with HitGrader at the threshold. `reachVsRange`: an over-reach value never reads at or inside the range (3.0099 → "3.01"), far-over and in-range values stay rounded down, at the range is not over, a float limit and a lowered range work, no usable limit means no comparison. Duration "0:42" and "1:02:03". Signed values. `truncate("Opponent_Name_123", 60, 6-px stub)` → the longest prefix + "..." that fits in 60 px; a string that fits is returned unchanged. |

`FightClock` is a ★ helper (`core/fight/FightClock.java`, CORE, listed under R6 with the other fight classes) that FightManager delegates the pause arithmetic of §5.13 to, so it can be unit-tested.

### 12.3 Client gametest (`KwamsterClientGameTest`, TEST)

It uses the Fabric client gametest API: `ctx.worldBuilder().create()`, `waitForChunksRender`, `ctx.runOnClient`/`computeOnClient`, `ctx.takeScreenshot(name)`, `ctx.setScreen`, `ctx.waitTicks`, `ctx.getInput()`. Gametests run on the gametest thread (fabric/…/api/client/gametest/v1/package-info.java:21–25), so **every** `Kwamster.testHooks()`, `Kwamster.fights()` and config access is wrapped in `ctx.runOnClient(mc -> …)` or `ctx.computeOnClient(mc -> …)`; TestHooks throws otherwise (R2c). Gametests cannot spawn remote players, so combat is injected through `TestHooks` (§4.10).

1. Set GUI scale 2 and FOV 70. Create a flat world. Screenshot `00_idle` (panel shows "No fights yet", crosshair default).
2. `beginSyntheticFight("Kwamix")`, then inject, with 4 ticks between each:
   - a landed THREE_BLOCK_CRIT (reach 2.97, charged, crit)
   - THREE_BLOCK_SPRINT (2.96, sprint)
   - CRIT 2.40
   - SPRINT 2.80
   - NORMAL 2.50
   - WEAK 2.00
   - a miss at 3.40
   - `hitTaken(2.9, true)`
   - `jumpReset(1)` (success, tick 1)
   - `jumpReset(4)` (late)
   - three more landed hits (combo 3)

   Screenshots: `01_fight_panel`, and `02_indicator_combo` (taken one tick after a 3B crit). Assert that `fights().current().stats(now)` has swings 10, landed 9 and quality[0] == 1, and that the session is still 0.
3. Stoplight: `setStoplightOverride` GREEN, YELLOW, RED and OFF → `03_stoplight_green|yellow|red|off`.
4. Crosshair: `setCrosshairOverride` for each `CrosshairMode` → `04_crosshair_<mode>`. Then disable the custom crosshair in config → `04_crosshair_vanilla` (vanilla sprite visible).
5. `endSyntheticFight(WIN)` and wait 2 ticks → `05_summary_card` (with chat visible). Assert that `lastRecord().result == "WIN"` and that the FightEndedEvent had `alert == true`. Assert that a file exists under `config/kwamster/fights/` after `waitFor(...)` with up to a 100-tick timeout. Assert that the share link is non-null and `length() ≤ 1900`, and write it to `build/run/clientGameTest/kwamster-last-link.txt` for the manual viewer check.
6. Settings: `ctx.setScreen(() -> screens().create(SETTINGS, null))` → `06_settings`. Then `ctx.setScreen(() -> screens().create(SETTINGS, null, "Hit feedback"))` → `06_settings_feedback`, and the same with `"Crosshair"` → `06_settings_crosshair`. Tabs are **not** clicked: Cloth's `ClothConfigTabButton` extends `AbstractButton`, which `pressMatchingButton` does not match (it handles only `Button` and `CycleButton`, ClientGameTestContextImpl.java:277–285).
7. HUD editor → `07_hud_editor`. Simulate a drag of the panel: `setCursorPos` and `holdMouse`/`releaseMouse` (move the cursor once before the drag; the first move after a grab is swallowed). Assert that `layout(STATS_PANEL)` changed and was saved. Reopen the editor, drag again, press Cancel, and assert the layout equals the saved one.
8. Saved fights screen → `08_saved_fights`, with one row.
9. Hide checks with the render counter (no pixel comparison): read `hudDraws()`, set `options.hideGui = true`, wait 3 frames/ticks, assert `hudDraws()` is unchanged → screenshot `09_f1`. Restore, enable the F3 overlay with `hideInDebug` on, wait, assert unchanged again → `10_f3`. Then F3 off: the counter increases again.
10. Master switch off → `11_disabled` (vanilla crosshair, no HUD; `hudDraws()` unchanged).
11. Repeat the default-HUD screenshots of steps 2 and 5 at GUI scale 4 (`12_gui4_fight`, `13_gui4_card`).
12. Docs screenshots at 1920×1080 (`docs_*`, cropped into `docs/images` for the README). Among them: clicking the "Moving forward" colour swatch opens `ColorPickerScreen` and Esc returns to the settings (asserted); the picker from a tier colour and from the panel background; the HUD presets screen in both views; the live HUD after applying each preset, then KWAMSTER, after which `HudPreset.matching(config) == KWAMSTER` (asserted).
13. §5.13a: in a synthetic fight, `ChatListener.handleSystemMessage("<Kwamix> gg you died")` does not end it; "Kwamix was slain by <you>" ends it as a WIN whose `resultNote` quotes the message; in the next fight `Gui.setTitle("DEFEAT!")` ends it as a LOSS with the title note.

Failures throw `AssertionError`. Every screenshot is reviewed by eye after the run.

### 12.4 Manual in-game checklist (real server, two accounts or a duel with Kwamix)

1. **Counting.** Hits, misses and accuracy match a hand count over 20 swings, including whiffs with the opponent in front. Swings at empty air with nobody near do not count. Hitting a cow or crystal does not count. **Clicking a hub's queue NPC** does nothing: no sound, chat, fight or saved file.
2. **Reach.** Reach grows as you back off. A hit at the edge of reach reads about 2.9–3.0 and triggers the 3-block tier sound. A miss from 3.5 blocks shows `MISS 3.50 ↑`.
3. **Crits and sprint.** A jump crit plays the CRIT or 3B crit sound. A W-tap hit plays the SPRINT sound. The 3B crit and 3B sprint sound different. Spam-clicking into a fresh hit shows grey NO-REG indicators, no sounds, and the combo does not climb.
4. **Crosshair.** It is white normally, red on a player in reach, cyan while falling, and pink while falling on a player. It flashes the tier colour (and briefly widens) on a hit. Turning the attack indicator option off works. The vanilla attack indicator still shows below it when set to KEEP, and the hit indicator pill sits below it without covering it.
5. **Jump resets.** Jumping immediately on being hit gives "✔ tick 1". A late jump gives "✘ … late". Being hit in the air and jumping after landing gives no attempt. Being shot by a bow while jumping gives no attempt. A rod hit gives no attempt. Results do not change between 60 and 240 FPS.
6. **Combo and streak.** The combo counter rises and resets when hit. A miss resets the streak only.
7. **Stoplight.** The opponent walking at you is GREEN, standing or strafing is YELLOW, running away is RED. There is no flicker at the threshold. A pearl or teleport causes no spike. With nobody around, it is hidden.
8. **Fight end and results.**
   - Wait 8 s idle → DRAW card plus chat. Kill → WIN. Die → LOSS. A two-hit kill still shows the card. With health visible, the kill's final blow is included in damage dealt (the extra health sample in finalisation, §5.13; no gametest can cover this, because HealthWatch reads real players). Clicking the body right after the kill opens no new fight.
   - The opponent logging off or running 16+ blocks away ends the fight.
   - In an FFA, whiffing at a passer-by who then dies to someone else does not produce a WIN.
   - [Copy link] and [Open] work. The link opens the viewer with stats, timeline, replay and aim map. Its length is ≤ 1900. The chat summary lines do not wrap at default chat width.
9. **Disconnect.** Disconnecting mid-fight (pause menu, and a server kick) saves the fight with reason DISCONNECT and prints nothing in the title screen. Closing the game mid-fight saves it as GAME_EXIT. Restarting the game lists both under Saved fights. Copying a saved fight's link works. Respawning in the same dimension mid-fight ends it as WORLD_CHANGE.
10. **Rendering and HUD.**
    - F1 hides everything. F3 hides the HUD.
    - Changing the GUI scale (2 → 4 → auto) keeps elements anchored and the default layout free of overlaps.
    - A server title (VICTORY) and boss bars draw over KWAMSTER elements, never under them.
    - Run with Sodium and with Iris (shaders on): no visual or log errors.
    - In the Lunar Client Fabric 1.21.11 profile (with Combat Tracker, Combat Report and Direction Indicator removed), the mod loads, the log shows "Landed-hit hook active" after the first hit, the HUD works, and the crosshair works or falls back to vanilla; record the result in the README.
11. **Master off.** The game behaves exactly as vanilla: no sounds, chat, HUD or files, and a vanilla crosshair. Turning it off mid-fight saves that one fight silently (DISABLED) and nothing after.
12. **Viewer.** `node tools/viewer-check.mjs` passes. The link from `kwamster-last-link.txt` opens in Chrome, Firefox and a phone browser; the timeline zooms and pans inside its card; an old Chromium shows the unsupported-browser card.

---

## 13. Risks and open questions

| # | Risk / question | Mitigation / decision needed |
|---|---|---|
| 1 | The viewer lives at **GitHub Pages** `https://builtdoor1.github.io/kwamster/` (repo `builtdoor1/kwamster`, Pages from `/docs` on `main`). | The base URL is configurable, and old links keep working only while that host serves the viewer. |
| 2 | **Lunar Client** may replace the vanilla crosshair or the GUI path, so `GuiCrosshairMixin` (require = 0) might not run. | The game still boots. The README tells users to disable Lunar's own crosshair module. Verify in the manual checklist. |
| 3 | **Practice-server fake deaths** (instant respawn or teleport without a death event) give DRAW (OPPONENT_FAR/LEFT, or WORLD_CHANGE when you are respawned). | The spectator-switch inference is built in. Ask Kwamix which servers matter; add a server-specific heuristic (big teleport plus low last-seen health) in v1.1 if needed. |
| 4 | **Client-predicted "landed"** differs from server registration (i-frames, shields, reach rejection). | `confirmPct` is shown, and hits into hurt immunity are flagged NO-REG and kept out of combos, streaks, intervals, advantage and sounds (§5.4). Accuracy is defined as client-landed, as in both earlier mods. The NO-REG estimate uses your own send ticks, so steady latency cancels out; ping jitter still causes occasional spam-click errors (about 4.5 % in the jitter model versus about 18 % for the old raw rule), and the third-party rule depends on the lagged client copy. Servers with a reduced hit delay set `noRegImmunityTicks` (0 = off). |
| 5 | **Remote positions are interpolated**, so the attacker reach on hits taken and the stoplight lag the server by about 100–150 ms. | Labelled approximate. Our own reach uses tick positions (validated the same way vanilla does). |
| 6 | **Link size.** Deflate gains little (measured ratio 0.93–1.0, §9.4); about 160 events fit unthinned. | `ShareLinksTest` enforces the 132-event case unthinned and the 600-event case thinned. If either fails **before release**, add (to v1, before freezing) a flag that drops `aimH`/`aimV` on MISS events (−2 B each), then one that drops positions on MISS events. Reducing position precision does not help: the position deltas are already about 1 byte. |
| 7 | The viewer needs `DecompressionStream('deflate-raw')` (Chrome 103+, Firefox 113+, Safari 16.4+). Chrome/Edge 80–102 have `DecompressionStream` but throw on `'deflate-raw'`, so a `typeof` check (combat-tracker docs/index.html:769) is not enough. | `KwDecoder.isSupported()` tries `new DecompressionStream('deflate-raw')` in try/catch and shows the "unsupported browser" card before decoding (§9.6–9.7). There is no JS-inflate fallback (that would mean bundling a library). |
| 8 | **1.8-style combat servers** (no cooldown) make every hit "charged". Pre-1.19.4 servers reached through ViaVersion also send damage events with no source at all. | Hits into hurt immunity are NO-REG (silent, no combo), so only registering hits sound and count toward combos. NORMAL and WEAK sounds are off by default. Sourceless damage events use the guarded attacker/confirmation inference of §6.3 (approximations in §5.15). README note. |
| 9 | Health may be hidden by the server, so damage and trade results are unknown. | Gated by `opponentHealthVisible`; shown as "—". |
| 10 | **Duplicate hooks** if Combat Tracker, Combat Report or Direction Indicator are still installed (they are in the user's Lunar folder). Combat Report also binds **K** by default (CombatReportClient.java:40), the same as `open_settings`. | Injections stack safely (and KWAMSTER's optional injectors are `require = 0`), but HUDs duplicate and K opens two screens. The README says to remove them, and `fabric.mod.json` declares them under `"conflicts"` (§2.2), so Fabric Loader warns at startup without refusing to launch. |
| 11 | Jump-reset timing. The two hooks are not tick-aligned (damage packets are processed at frame start, jumps inside the tick), so wall-clock deltas depend on FPS and on catch-up ticks. | Decided in game time from the client tick index (§5.1): `deltaMs = 50·(deltaTicks − 1)`, so the default 0–80 ms means "first or second tick after the hit", FPS-independent. Wall-clock ms are shown only as hover information. Documented in the README and the Cloth tooltips. |
| 12 | The gametest cannot create real opponents, so the capture mixins are only exercised by the manual checklist (plus `-PmixinDebug` to prove they bind). | Accepted. |
| 13 | Each shipped link format is permanent (§9.6). | Freeze v1 before the public release. |
| 14 | Spears (1.21.11) are excluded from accuracy and reach, because the client never learns whether a stab landed. | Shown as a count. Revisit if Mojang adds client feedback. |
| 15 | Default key clashes. | Only `open_settings` has a default (K, unused in vanilla 1.21.11; clashes with Combat Report, see risk 10). `edit_hud` ships unbound because H is vanilla's F3+H (`key.debug.showAdvancedTooltips`, Options.java:634–636). All keys are rebindable. |
| 16 | **NPC heuristic.** "Not in the tab-list registry, or a v2 UUID" could misclassify a real player on a server that hides real players from the player-info list, or a plugin that gives real players v2 UUIDs. | `advanced.ignoreNpcs` (default on) turns it off. Manual checklist item 1 tests a hub NPC. |
| 17 | **HUD draw order changed** to below boss bars, the sidebar, titles and chat (§6.6). An element the user drags under the sidebar is hidden by it. | Intended ("never hide server information"); the README explains it. Boss bars beyond the first can overlap the default card. |

### 13.1 Open questions for builtdoor (decide before release)

1. **Repo and Pages** (risk 1, §2.2 `contact`): settled as `builtdoor1/kwamster` with Pages from `/docs`. A Modrinth slug is still open.
2. **Jump-reset success window.** The default 0–80 ms now means "jump on the first **or second** tick after the hit" (game time, §5.1). If Kwamix wants only first-tick resets to count, the default becomes 0–40 ms.
3. **Sprint-hit definition.** S6 now counts vanilla's extra-knockback hit (charged and sprinting), not every hit while the sprint flag is on (§5.6). Confirm with Kwamix, or switch to the raw definition and relabel the row "Hits while sprinting".
4. **NO-REG hits** (hits into hurt immunity) no longer count toward combos, streaks, intervals or advantage and make no sound (§5.4). This makes KWAMSTER's combo numbers lower than Combat Tracker's on spam-heavy fights. Confirm this is wanted.
5. **Card contents.** The compact card shows nine figures (Acc, Reach, Max, Crits, 3-Block, Sprint, Combo, Resets, Taken); streak, interval, trades and advantage moved to chat, hover and the viewer (§7.9). Confirm the selection.
6. **`edit_hud` default key.** Ships unbound (H is F3+H in vanilla). Pick another default if you want one (J and U are free in vanilla 1.21.11).
7. **Master switch mid-fight.** The running fight is saved once, silently, as DISABLED (R4). The alternative is to discard it.

---

## Appendix A. Review log (round 1)

Every issue was checked against `.ref/mc`, `.ref/fabric`, the Cloth jar (javap) and the earlier mods before it was applied. One line per issue; "alt" means the fix was applied with a different mechanism than the reviewer proposed, and why.

**API lens**
- DISCONNECT off the render thread (blocker): confirmed (ConnectionMixin.java:79–91, AbstractNetworkAddon.java:156–161, Minecraft.java:2175 and 2213). Applied: flag only, finalised in `onClientTick` (§2.1, §5.13, R2a).
- CLIENT_STOPPING order: confirmed. Applied: one CORE listener registered before modules; `FightArchive.shutdown()`; RECORD registers none (§2.1, §10).
- LivingEntityMixin on the server thread: confirmed (ServerPlayer.java:1514–1515). Applied: `isClientSide()` guard first (R2b, §6.3).
- `Pose.DYING` and death evidence: confirmed (LivingEntity.java:1451; ServerPlayer.java:862–916, broadcast at 910). Applied: clause dropped, evidence re-cited, rule 3 marked best-effort.
- Same-dimension respawn keeps the ClientLevel: confirmed (ClientPacketListener.java:1235–1258). Applied: player instance and id compared too (§5.13 step 1).
- Jump timing not tick-aligned: confirmed (Minecraft.java:1277). Applied with the tick index (§5.1). Alt: success is decided on game time `50·(deltaTicks−1)` against the existing ms window instead of a hard `deltaTicks ≤ 1`, so the user-facing ms setting keeps working.
- Capture injector `require`: confirmed. Applied: HEAD + `require = 0` + spectator gate. Alt: "log which injection bound" became a first-use INFO log, because a HEAD injector on an existing method always binds (§6.2).
- Hit indicator over the vanilla attack indicator: confirmed (Gui.java:442–450). Applied: default CENTER (0, 26) (the UX reviewer's value; 30 also clears, 26 keeps the cluster tighter) plus the KEEP/HIDE scope note.
- Click timing only for the left button: confirmed. Applied: compare with the bound Attack key; KeyboardHandlerMixin added; the macOS remap is accepted as rare.
- IO serialisation thread: applied (§10 step 2, R2d).
- Gametest thread and Cloth tab clicks: confirmed (package-info.java:21–25; ClientGameTestContextImpl.java:277–285; `ClothConfigTabButton extends AbstractButton` by javap). Applied: `runOnClient` + thread assert; `ScreenRegistry` arg + `setFallbackCategory` (§4.4, §12.3).
- H key is F3+H: confirmed (Options.java:634–636). Applied: `edit_hud` unbound; the K clash with Combat Report added to risk 10.
- `URI.create` on a user base URL: applied: validation in `clampAndFill`, the Cloth error supplier and `shareLink`; `[Open]` built in try/catch (§3.8, §8.2, §9.1).
- Reach and target gaps (crosshair pick only in reach, ATTACK_RANGE, server check path): confirmed (LocalPlayer.java:1169–1207, AttackRange.java:94–126, ServerboundInteractPacket.java:88–91, LivingEntity.java:2118–2121). Applied (§5.2, §5.9, §5.11, §5.12).
- Stale sound-lookup cache: confirmed (SoundManager.java:173–175 is a map lookup). Applied: no cache.

**Stats lens**
- DISCONNECT (blocker, same defect as the API one): applied the API reviewer's mechanism. The proposed `mc.execute(...)` hop is **rejected** because `Minecraft.disconnect` calls `dropAllTasks()` (Minecraft.java:2213) and can discard it. Kept from this issue: finalise outside any dispatch; flush, finish, FightEndedEvent, then `WorldLeaveEvent`; the `mc.level == null` fallback; `flushPending` null-tolerant; `postTick` defined as not a dispatch (§4.3).
- CLIENT_STOPPING (duplicate): applied as above.
- Hits into hurt immunity: confirmed (LivingEntity.java:1194, 1940, 455–456). Applied as NO-REG (§5.4, payload HIT bit 6, S40).
- Bystander opponents: applied the engaged model (§5.14, `FightRecord.engaged`, rules 1/3/4, payload table).
- ENDING drops trades: applied the enumerated rule. Alt: confirmations and damage drops are accepted regardless of `t` (they arrive after the killing blow); jump resets by `hitNanos ≤ endNanos` (§5.13).
- Click time vs processing order: applied: hook time as `nanos`, `clickLeadMs` for S8, the single-source-of-truth rule and a replay test.
- 1v2 payload (also a UX issue): applied the MULTI table + per-event `opp` (§9.3); the viewer draws one disc per opponent.
- Hub NPCs: applied `ignoreNpcs`. Alt: added the v2-UUID test next to the tab-list test, because NPC plugins can keep an unlisted player-info entry.
- THORNS confirmation and latest-vs-earliest matching: confirmed (DamageSources.java:247–249). Applied (§5.9).
- End-rule order, unreachable WORLD_CHANGE, DISABLED saving: applied the forced-reason pre-checks and the save decision (§5.13, R4).
- `jumpOpportunities` duplicated, airborne hits: applied (derived only; airborne hits open no window; `jumpEarly` shown).
- `addDamage` scope: applied (§4.9).
- Live duration and a 0 ms advantage: applied (§5 intro, §5.5).
- Viewer needs combo and advantage windows; tie order: applied H13–H15 and the explicit sort rank (§9.3).
- Sprint-hit definition and crit denominator: chose charged + sprinting and "over all landed" (§5.6, §5.7); open question 3.
- Decisive fights below `minFightEvents`: applied `|| result != DRAW`.
- Pause shifting only in the state machine: applied a single fight clock (`FightClock`); `shiftForPause` removed.
- Momentum guard and NaN in Gson: confirmed (TradeWatch.java:211–212). Applied the guard + mandatory sanitising (§4.9, §5.10, §2 Json).
- `HitResolvedEvent.nanos` and interval: applied (§4.2).
- Settings snapshot: applied (§3.9, §5.1, §5.9).
- PlayerRefs ownership, UUID keying, gametest `fabric.mod.json`: applied (§2).
- Rule 7 axes and asymmetric keep-alive: applied (3-D distance; confirmation and spear keep-alive; projectiles from unlisted players ignored).
- Catch-up ticks in jump timing: applied via the tick index. Alt: no intra-tick nanos offset, because hits are only ever processed between ticks, so the offset carries no game-time information (§5.1).

**UX lens**
- Panel width: applied dynamic width 128–168 (cap 168 rather than the proposed 176 so the panel's right edge, ≤ 174, stays clear of the default stoplight at W/2 − 35 = 178.5 on a 427-wide screen), the 6 px gap, truncation, the shorter advantage value and the SESSION chip. Alt: the name row was merged into the header (also needed for the chat-band height budget) (§7.4).
- Card columns overflow. Alt: the reviewer's compact direction, as a 3 × 3 cell grid with measured column width 72–88 and values right-aligned per cell; footer truncation and a fixed 24 px progress line (§7.9).
- Centre cluster overlaps: applied (§3.10 numbers, overlap test). STOPLIGHT kept at (−30, 0): the reviewer's (−28, 0) is not needed, both clear every rect.
- Card vs crosshair, title, boss bars; panel vs chat: applied the compact 56 px card at (0, 20), the panel row defaults (streak, interval, taken off) and the validation sizes, plus the root fix of drawing below the server's HUD text (§6.6). Boss bars beyond the first are documented, not asserted (risk 17).
- No shadow on backdrop-less text: applied the shadow rule (§7.0).
- Cloth polish: applied §3.12 (enum names confirmed via javap `EnumListEntry.DEFAULT_NAME_PROVIDER`; `setRequirement`, `Requirement.isTrue/isValue` and `setTextGetter` confirmed in the jar).
- 1v2 payload (duplicate): applied.
- JS decoder untested: applied golden vectors + `tools/viewer-check.mjs` (§9.6, §12.1).
- Size budget numbers: applied the measured figures, the best-probe search, the new thinning priority and the risk-6 lever.
- Viewer combo and advantage windows (duplicate): applied H14/H15.
- `deflate-raw` detection: applied `isSupported()`.
- Viewer details (aim ratio, histogram bin, jump axis, pan/zoom, meta, title, example): applied (§9.7).
- Base normalisation with a file name: applied (§9.1, ShareLinksTest).
- Crosshair centring: applied the pixel-edge centre and `L = cx − t/2` with a centring test.
- X shape and outline stacking: applied the staircase X and a dilated, disjoint outline mask.
- Crosshair colours, flash visibility, GUI scaling: applied (pink crit-on-player default, gap pop, `scaleWithGui`, config-driven colours).
- Fractional scales: applied (WIN at 1× bold; scroll step `1/guiScale`; crisp-scale tag).
- Silent chat-off combination and footer condition: applied `chatSummaryWithLink`, the tooltip and the Cloth requirement.
- Chat line widths: confirmed (ChatComponent.java:364–367). Applied the 3-line layout, `chatFightLines` 1–3.
- Stoplight size, label jitter and placement, clutter: applied (10 × 26, fixed label width beside the housing, `hideWhenNoTarget` default true, 40 % alpha when shown idle).
- Shadow band and border corners: applied (§7.0).
- NORMAL shares SPRINT's sound; disabled tier and chat: applied (snare; a disabled tier suppresses its chat line).
- Editor undo and discoverability: applied Cancel + snapshot, and "Position card…" / "Position stoplight…" with preselection.
- README, contact links, gametest step 9, GUI 4 screenshots, truncation test: applied (§2, §2.2, §12.2, §12.3).

No issue was rejected outright. The only rejected proposal is the `mc.execute(...)` hop in the stats-lens DISCONNECT fix (reason above).

---

## Appendix B. Review log (round 2)

Four fix groups worked in parallel, then one integration pass. Every fix was checked against `.ref/mc`, `.ref/fabric` or the Cloth jar, and the integrated tree passed `gradlew build test`, `node tools/viewer-check.mjs` and `runClientGameTest`.

**CAPTURE**
- NO-REG misflagged under latency (two findings: the raw client copy is a round trip late in both directions). Fixed with ★ `NoRegEstimator`: your own send ticks for your own hits, the lagged copy only after other damage (§5.4, §6.2). The window is the new `advanced.noRegImmunityTicks` (10, 0 = off, §3.9).
- Corpse clicks opened a phantom fight after a kill. Fixed: `SwingCapture.isLiving` plus ★ `RecentDeaths` (dropped swing, cone and attacker exclusion, landed re-check; §5.4, §5.15).
- Late jump resets were undetectable (an airborne press never reaches `jumpFromGround`). Fixed: jump-key rising edges sampled per tick and paired by `JumpResetWatch.onJumpPress` (§5.1).
- The killing blow's damage was lost at finalisation. Fixed: `flushPending` takes an extra `HealthWatch.sampleNow` first (§4.4, §5.13). Covered by the manual checklist (§12.4 item 8), not a gametest.
- ViaVersion pre-1.19.4 damage events carry no source. Fixed: guarded inference with ★ `SourcelessDamage` and a deferred `START_CLIENT_TICK` re-check (§6.3, §6.7, §2.1). Inferred events are not marked (§4.2).

**HUD**
- Editor scroll-to-resize stuck at GUI 3, 6 and 7 (3-decimal rounding of `k/gs`). Fixed: coarse steps unrounded, scale-aware crisp tolerance (§7.3).
- Combo counter stuck at 1.25× after a resync (overflow on a `Long.MIN_VALUE` start). Fixed in `Anim` and `ComboCounterElement` (§7.0, §7.6).
- Polish: outlined 2× combo number, "4× COMBO" broken tally, help-line pill (§7.0, §7.3, §7.6).

**RECORD**
- Viewer render DoS from a 47-character link. Fixed: matched Java/JS decoder limits, a 64 KiB inflate cap, `KwDecoder.validate` before rendering, bounded render loops; rejected-vector and crafted-link checks in `viewer-check` (§9.6, §9.7, §12.1).
- Reach display rounded up to the 3-block threshold. Fixed: reach is rounded down everywhere (StatFormat, `reachQ`, S7–S10, viewer) (§7.0, §9.3, §9.7).
- Trivial fights evicted real fights at the save limit. Fixed: `FightSummaryRow.trivial`, trivial-first pruning (§4.1, §10).
- Miss-reach aggregates were half wired. Fixed: viewer Reach card miss line and the chat hover "max" (§8.2, §9.7).
- Polish: the replay camera frames ±2 s around the playhead (§9.7).

**CONFIGUI**
- "▶ Test" ignored the pending Volume category. Fixed: `SoundPreview.preview(..., channel)` and silent-preview warnings (§3.4, §4.4, §8.1).
- A greyed-out entry with an error blocked Save (Cloth never consults `isEnabled()`). Fixed: `GatedEntries` and gated string checks; disabled entries never save invalid text (§3.12).
- Polish: "Stats output", "Volume category", "Indicator: …" and "Colour: …" labels; tooltip fixes (§3.2, §3.4, §3.6).

**Integration**
- `noRegImmunityTicks` added to `KwamsterConfig` (+ `clampAndFill`, test), wired into the landed hook, and given a Cloth slider under Advanced.
- `clampAndFill` reverts a syntactically invalid tier sound id to the default (`KwamsterConfig.isValidSoundId`, an MC-free copy of `Identifier.tryParse`), so a hand-edited config cannot block Save on every tab (CONFIGUI's CORE observation).
- `FightManager.presence` uses `!isRemoved() && !isDeadOrDying()` instead of `isAlive()`, matching CAPTURE, so NaN-hidden health no longer ends fights as OPPONENT_DIED (§5.13 rule 3).
- `fabric.mod.json` declares `"conflicts"` on `combat_tracker`, `combat_report` and `directionindicator`, the ids in the shipped jars (§2.2, risk 10).
- Not changed (noted): `CrosshairStateEval` and `OpponentMotionTracker` still use `isAlive()`, so on a NaN-health server the crosshair ON_PLAYER state and the stoplight target ignore such players (a degraded feature, never a wrong record). `ChatReporter` still prints "Confirmed 0/N" when a server sends no confirmations at all.

**Second review (after the round-2 fixes)**

Eleven confirmed findings, each re-verified against the code and `.ref/mc` before it was fixed. The tree passes `gradlew build test` (250 tests) and `node tools/viewer-check.mjs` (27 vectors). "Alt" marks a mechanism other than the reviewer's, and why.

- Holding jump through landing scored the hit EARLY and lost the 0 ms reset (major): a still-held key jumps on the first tick on the ground (LocalPlayer.java:639, LivingEntity.java:2926–2950). Fixed: `onHitTaken(…, jumpHeld)`; a held bare press inside the early window opens the window and is remembered, the next tick's jump decides, and `JumpResetWatch.onTickEnd` scores the press as early only when that tick ends with no jump (§5.1).
- On sourceless (1.8 via ViaVersion) servers, environment, arrow and teammate damage was confirmed as your melee, because landed NO-REG clicks kept the confirmation window open, which switched off the secondary NO-REG rule (major). Fixed: only swings judged registered feed `SourcelessDamage` (`onRegisteredSwing`); `claimOwnHit` spends the swing (one event per swing) and rejects events younger than `minConfirmAgeMs(ping) = max(0, min(ping − 50, ping/2))`, lenient so a stale tab-list ping never rejects your own hits (§6.3, §5.15).
- A deferred sourceless killing blow was dropped when your death arrived in the same frame. Fixed: your `DeathEvent` is held behind the pending hit and posted after it at the next tick start, with its own nanos (§6.3, §6.7).
- A later air press overwrote an unpaired real jump, so mashing erased early jumps before airborne hits. Fixed: `lastRealJump` is kept separately and looked at after an airborne hit discards a press (§5.1).
- Sweep damage marked a bystander as hit by your own melee with no anchor, so neither NO-REG rule could fire. Fixed: `onDamage(victim, ownMelee, tick, ping)` counts own damage as the anchor's only when it arrives within `lagTicks(ping) + 6` ticks after the anchor; anything else arms the secondary rule (§5.4). Alt: the reviewer's one-line form (`!hasAnchor || tick − anchorTick ≥ immunityTicks`) holds whenever the primary rule does not flag, so it would run the lagged-copy rule after every own hit and misflag registered hits at the 10-tick edge.
- Fire, poison and wither ticks on you were inferred as melee hits while an opponent swung nearby. Fixed with the knockback corroboration the reviewer proposed. Alt: no new mixin, and the check only applies while you burn (without Fire Resistance), are poisoned or are withered, so it cannot drop hits in normal fights. Such a hit is decided at the next tick start and needs `SourcelessDamage.showsKnockback`: your delta movement changed since the END_CLIENT_TICK snapshot (this catches the velocity packet in either order relative to the status) and points away from the attacker by at least 0.15 blocks/tick. The vertical part was left out because a 1.8 server runs `jump()` for players, so a fire tick soon after a jump carries upward velocity. The remaining approximation is documented (§5.15, README).
- A trivial fight just saved still deleted a real fight when no older trivial fight was left. Fixed: a trivial `keepId` is removed itself, last among the trivial fights, and the real-fight pass never runs for a trivial save; `FightStore` writes nothing for a fight that pruned itself (§10). "The fight just saved is never pruned" now applies to real fights only.
- Rounding down made over-reach misses read "MISS 3.00 (over reach)". Fixed: `StatFormat.reachVsRange` (chat line and hover, hit indicator, summary maximum via the new `FightStats.missReachMaxLimit`) and, in the payload, `overReachShown` for MISS events and `reachCmVsRange` for S10. An over-reach figure is at least one step above its range; everything else stays rounded down (§4.7, §7.0, §8.2, §9.3).
- Replay easing lost you off the canvas after a sudden spread. Fixed: `easeCamera` eases the zoom in log space (0.25 per frame widening, 0.06 tightening) and clamps the eased centre again at the eased zoom. Measured in the real page (rAF driven by hand, 1024×575, a 35-block pearl link): 54 off-canvas frames before, 0 after. A replay of the page's own functions gives 0 for the 30 and 35-block pearls, a 23-block disengage, 4× speed and a second opponent 120 blocks away, on desktop and phone sizes (§9.7).
- viewer-check skipped every crafted DoS check when `KwDecoder.LIMITS` was missing. Fixed: `LIMITS` and its keys are required exports, the crafted block fails instead of skipping, and named vectors (`at_size_cap`, `bom_name`, two rejected ones) are required. A copy with the export renamed now fails with two messages (§9.6, §12.1).
- The JS decoder dropped a leading U+FEFF from names; Java keeps it. Fixed: one `TextDecoder('utf-8', { ignoreBOM: true })`, plus a `bom_name` golden vector; a copy without `ignoreBOM` fails viewer-check (§9.6).
