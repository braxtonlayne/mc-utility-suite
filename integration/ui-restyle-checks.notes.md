# ui-restyle-checks (STEM UI remainder, 2026-11-22)

Timeline, Goal console, minimap, fight-coach cooldown arc, UI preview capture fix, style-scan test, setting labels
through lang keys. Design `stem-ui-2026-11-22.md` 8.1, 8.8, 8.11, item 17. Nothing was run in game (no game window
was opened); `./gradlew compileJava compileDevtestJava test --offline` passes.

## Wiring the integrator must do

1. **Jobs tab (`ui/tabs/JobsTab.java`, not mine)**: place the new nodes instead of the old card text, in
   `preview(Panel p, IGoalEngine goals)`:
   - error: replace the `p.card("Could not read that goal"...)` + `p.paragraph(err, DANGER_TEXT)` pair with
     `p.place(GoalConsole.errorLine(p.font(), err, p.width()), 0)` (+ the examples paragraph unchanged);
   - preview: the `p.card("Dry-run preview", ...)` block with steps / missing / warnings can be replaced by
     `p.place(GoalConsole.previewCard(p.font(), pv, p.width(), MAX_STEPS), 0)` and the Start pill kept as a normal
     `p.button(...)` row below it. Until then the Jobs tab looks as before (no regression; the nodes are unused).
   - `GoalConsole` itself has no drawing of the prompt field: that is `SuiteMenuScreen` (FIELD plate + StyledTextField
     prompt), already in the menu title-bar style.
2. **Fight coach**: nothing to wire. `FightCoachHud` registers itself as a `HudElement` (slot CROSSHAIR, id
   `fight_coach`) with `Services.visuals().hud()` the first time its GUI layer renders after the Shell has a real host.
   `CombatBootstrap` keeps registering it as a GUI layer (the layer is now the logic half and draws nothing).

## What changed

### 4. Fight coach cooldown arc (`FightCoachHud`, `CoachReadout`)
- The old `g.fill` range bar / swing tick / `drawString` cues are gone. The HUD is now a `HudElement`: a cooldown arc
  (r 9 px around the crosshair): faint `LINE_FAINT` track, `ACCENT` fill clockwise from the top proportional to the
  attack recharge, and on ready (charge crosses 1) a 260 ms full ring that thins from 2 px larger back to the resting
  ring (resting ring at 55 % alpha). `ALLY` ring when ready, `WARN` when the swing would crit (`critReady`).
- The coach has no mark of its own (the primary target mark belongs to `TargetHud`), so it uses the canvas `arc`
  primitive. Range bar = 3 plates (DANGER too close / ALLY in range / WARN too far, inactive `LINE`), "AXE" (WARN) and
  "BACK" (DANGER) in MICRO text; the "!" fallback when no tracker is wired.
- `CoachReadout.charge01` (new) is written each frame by the layer from `player.getAttackStrengthScale(0)`.
  `FightCoach.onTick` is not mine, so the `show_swing` setting ("Swing-ready tick") no longer hides anything: the arc is
  always drawn while the coach shows a fight. If the setting should keep meaning something, `FightCoach` can publish
  `swingReady`-independent `showSwing` into the readout and `draw` can skip `drawArc`.
- Threat marks (projectile / blast) unchanged. Clean-Screen and F1 are gated by the host; screen open by `wantsDraw`.
- Tests: `FightCoachHudTest` (idle = no draw, arc sweep 360 / 180 / 90 per charge, flash then rest, crit = WARN, text
  cues, allocation < 1 KiB over 50 000 draws).

### 5. Style scan (`StyleScanTest`)
Scans `ui/`, `combat/hud`, `combat/coach`, `world/minimap`: rule COLOUR = a 6 or 8 digit hex literal that is not a bit
mask operand (`& 0xFFFFFF`); rule DRAW = `g.fill(`/`fillGradient(`/`drawString(`/`drawCenteredString(`/`renderOutline(`
outside `ui/holo` and `ui/kit`. An allow-list (file + rule + reason) is in the test; a stale entry (file now clean)
fails the test, so the list can only shrink. A self-test covers the scanner (masks ignored, holo/kit exempt).

**Remaining adoption backlog (allow-listed, files not mine):**

| File | Rule | Reason |
|------|------|--------|
| `ui/holo/HoloTheme.java` | COLOUR | the token tables (permanent) |
| `ui/holo/HoloAtlas.java` | COLOUR | atlas pixel work (permanent) |
| `ui/holo/VisualSettings.java` | COLOUR | accent preset swatches (permanent) |
| `world/minimap/MinimapPalette.java` | COLOUR | map tile colours (permanent) |
| `ui/InfoStripText.java` | COLOUR | GREEN/YELLOW/RED health traffic light constants: move to tokens (`ALLY`/`WARN`/`DANGER`) |
| `ui/SuiteMenuScreen.java` | DRAW | menu window draws with `g.fill` on top of the kit |
| `ui/AutopilotBar.java` | DRAW | `g.fill` bar |
| `ui/TabGui.java` | DRAW | `g.fill` rows |
| `ui/UiPreview.java` | COLOUR, DRAW | dev-only fake sky/terrain backdrop (should stay) |

Removed from the backlog by this task: `TimelineScreen`, `MinimapHud`, `MinimapLayoutScreen` (both rules),
`FightCoachHud` (it never held literals in the scanned form but used `g.fill`/`drawString`).
Note: `SuiteMenuScreen`, `TabGui`, `AutopilotBar` also appear in the design's adoption list; once they draw through
`HoloFrames` helpers only, delete their entries.

### 6. UI preview capture (`UiPreview`)
Cause (from the 1.21.1 sources): `Screenshot.takeScreenshot(target)` allocates `target.width x target.height`, and
`Window.refreshFramebufferSize()` (run once in the Window constructor) replaces a GLFW framebuffer size of 0 by 1. The
main render target is created from that, and is only corrected by a later non-zero framebuffer-size callback
(`Window.onFramebufferResize`, which ignores 0). When the window is created hidden, minimised or unfocused (as under
the preview run) that callback never comes, so the target stays 1x1 and every PNG was 68 bytes.
Fix:
- before the first step, wait until the render target and the window agree on a size above 1 px. If not, call
  `glfwRestoreWindow`, `glfwShowWindow` and `glfwSetWindowSize(1280, 720)` (the callback then resizes the display),
  re-apply `mc.resizeDisplay()` every second, and abort the run with an error after 10 s (nothing written) instead of
  writing 1x1 files;
- a capture is queued by the step and taken only after at least 2 rendered frames (`RenderFrameEvent.Post` counter)
  since that step, with a 5 s timeout, and skipped with an error if the target is 1 px;
- every capture logs `UI preview saved <file> (WxH, window WxH, gui scale s, n frames rendered)`.
On the next `runUiPreview` the owner should see, in `latest.log`, `UI preview window 1280x720...` (or the "restoring
and resizing" warning first) and one "saved ... (1280x720 ...)" line per image; `run/ui-preview/*.png` should be real
sized (hundreds of KB), not 68 bytes. If it prints "aborted: the window never reached a usable size", the window really
has no framebuffer (headless/minimised session) and the capture needs a visible window. Not testable without a window.

### 7. Setting labels through lang keys (`SettingsRenderer`)
`row()` resolves `setting.utilitysuite.<moduleId>.<key>` and `.tooltip` through `Language.getInstance()` once per page
build (rows are built, not drawn per frame), falling back to `spec.label()` / `spec.tooltip()` when the key is missing
or blank. The logic is the static `resolve(moduleId, keyPart, fallback, lookup)` (`SettingLangKeyResolveTest`: key
present, absent, blank, tooltip absent, key building). The module id is `access.moduleId()`, already available there.
To add translations, add entries to `en_us.json` (other files' job; only 42 setting keys exist today).

### 1. TimelineScreen
Menu window chrome (VOID backdrop, QUIET base + HERO frame with chamfer, scanlines, `LINE_FAINT` hairline under the
title bar, accent side bar and title, `[ LIVE ]`/`[ FROZEN ]` hairline-bracket readout). Stats / graph / events in quiet
glass cards. Graph in tokens (in ACCENT, out ALLY, chunks WARN, corrections DANGER, leases SYNC, jobs TEXT; burst
seconds tinted DANGER 25 %). Events on a vertical `LINE` hairline spine with a glyph node per kind (diamond lease,
cross correction, square stop-all, box chunk burst, dot job/diagnostic), timestamp in a fixed 7-cell digit column
(`timeText`; vanilla digits are tabular, so this is the mono role until the suite font exists), label in the kind
token. Layout maths (`virtualSize`, `windowSize`, `toVirtual`) untouched; `TimelineScreenLayoutTest` green plus two new
cases (timestamp grid, every kind has a token). Pill buttons are the kit's.

### 2. GoalConsole
Logic untouched. Added kit nodes `GoalConsole.errorLine` (DANGER hairline + 6 % tint + wrapped DANGER message) and
`GoalConsole.previewCard` (FRAMED glass card: accent caption with hairline, target, numbered steps, WARN notes), both
sized at build time, wrapping by the pure `wrap` (`GoalConsoleWrapTest`). They are not placed anywhere until the Jobs
tab is switched over (see Wiring 1).

### 3. Minimap
`MinimapHud`: tile texture path untouched. Square map = QUIET glass frame in the bezel + corner brackets; round map =
VOID underlay ring + `LINE` hairline ring + corner brackets around it. Hairline range ring at half radius
(`LINE_FAINT`), four cardinal ticks on the edge (north 5 px in the north token, others 3 px in `LINE`, rotating with
the map, one triangle batch), compass letters in tokens (north accent), player = `ACCENT` chevron over a `VOID`
outline (the existing 4-point arrow), entity dots DANGER / WARN / ALLY on a `VOID` plate, coordinate plate = QUIET
frame + `TEXT_SECONDARY`. The three styles now only choose tokens: CLASSIC (LINE_STRONG brackets, TEXT), CLEAN (LINE
brackets, secondary text, dark glass tint over the terrain kept), MILITARY (LINE_STRONG brackets, WARN north). Per
frame: one extra `BufferBuilder` (ticks) and one more annulus (range ring), same kind of allocation the GUI itself
makes; no new objects. `MinimapLayoutScreen`: token backdrop, dashed `LINE_STRONG` outline, bracketed handle (accent
while dragged), kit `PillButton` "Done" instead of the vanilla grey button (input handled in `mouseClicked`).

## Left for the playtest
All restyled screens at GUI scale 1-4; the minimap over bright terrain (the VOID underlay ring and brackets may need
more contrast on snow/sand) and in all three styles and both shapes; the coach arc in a fight (flash timing, WARN on
crit), including that registering the element from the GUI layer lands in the HUD host in the right order;
`runUiPreview` captures (see 6); the Jobs tab once switched to the new nodes.

## Deviations / not done
- No Jobs-tab switch-over (file not mine); no `en_us.json` edits (new keys in `ui-restyle-checks.lang.json`, the
  code still uses literals for the new strings, like the menu).
- Minimap and Timeline are not covered by allocation tests (they draw through `GuiGraphics` and raw buffers, which
  need the game); the coach element and the wrap logic are.
- Timeline has no open/close motion; the minimap range ring is a circle for the square shape too.
