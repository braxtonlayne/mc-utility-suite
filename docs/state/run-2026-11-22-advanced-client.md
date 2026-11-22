# Advanced-client volley, 2026-11-22

Base: 0e29eea (contracts 820ee9b plus the `RecordingCanvas` test fake). Ten tasks were built in parallel worktrees
and merged into `main` on 2026-11-22 around 08:00 EST. Designs: `docs/design/parkour-control-model-2026-11-22.md`,
`docs/design/stem-ui-2026-11-22.md`, `docs/design/upgrade-pass-2026-11-22.md`.

**Nothing in this run was playtested in a game client.** No `runClient`, `runPlaytest` or `runUiPreview` was run.
Everything below is compiled and unit-tested only. No new mixin was added (still 21), but the mixin audit has not
run since the previous volley either: it runs at the first `./gradlew runClient`.

## What landed on main

All ten merged with `git merge --no-ff`, no conflicts (no file was touched by two branches), `compileJava` green
after each.

| Order | Task | Tier | Branch | Tip | Contents |
|---|---|---|---|---|---|
| 1 | inventory-smarts | sonnet | `refs/heads/worktree-wf_fa773fec-5a4-14` | d1e70b4 | `FoodSelector` ranks all 36 slots; `SurvivalAutopilot` pulls food from the pack into the hotbar with one SWAP click through `IContainerClicker`, eats, swaps back (`use_inventory_food`, `eat_swap_back`, both on). Hotbar refill takes the identical item first, then the best item of the same class (`refill_same_class` on, `refill_same_class_pinned` off). |
| 2 | combat-hit-planner | opus | `refs/heads/worktree-wf_fa773fec-5a4-13` | c584b31 | `HitPlanner` picks SPRINT_KB / CRIT / SWEEP / PLAIN and latches it 8 ticks ahead ("Hit choice": AUTO, KNOCKBACK, CRITS, PLAIN). Crit hops are timed from ticks-until-ready and release sprint before the swing. Sweep guard for protected entities. `GearRead` replaces the constant taken-weights. "Never disengage (duel mode)", default off. `fallingForCrit` now requires not sprinting. |
| 3 | kit-widgets | sonnet | `refs/heads/worktree-wf_fa773fec-5a4-10` | 670bf41 | `UiTheme` colours alias the Holo tokens; every kit widget restyled square with brackets; hover/press/toggle motion through `AnimStore` with stable widget keys; Tab GUI restyled with a 120 ms unfold. |
| 4 | shape-physics | opus | `refs/heads/worktree-wf_fa773fec-5a4-6` | b041f06 | `MovementPhysics` full path with exact collision boxes, vanilla axis order, 0.6 step-up, sneak edge back-off, crouched hitbox, sprint loss on walls, jump delay. `LevelShapes` is real (per-BlockState shape table, per-tick cell cache). `ParkourCourse` shaped landings and `EDGE_CASES`. Cube grids stay bit-identical. |
| 5 | core-shared-control | opus | `refs/heads/worktree-wf_fa773fec-5a4-5` | bc977e0 | `ISharedControl` implemented: `SharedInputOverlay` (one-tick writes at `MovementInputUpdateEvent` LOW, fill-only sprint start), `ManualFactsTracker`, `YawCoSteer` (rate, acceleration and 450 deg/s capped, zero on LEGIT), shared grants in `ActionArbiter`. Registered in `CoreBootstrap`. **No module uses it yet.** |
| 6 | holo-hud-core | opus | `refs/heads/worktree-wf_fa773fec-5a4-9` | d57afb1 | `HoloKit` canvas, `HoloRenderTypes`, code-drawn `HoloAtlas`, `HudHost` (`IHudHost`), `Visuals` (`IVisuals`), the `appearance` module (effects, motion, accent, HUD opacity 40-100, HUD scale 75-150). Status line, stopped badge, Clean badge, info strip and module list ported to `HudElement`s. One HUD layer now. |
| 7 | menu-chrome | opus | `refs/heads/worktree-wf_fa773fec-5a4-11` | f715332 | Menu window on blurred glass with a bracket frame, hairline columns, sliding sidebar bar, state marks in the module list, framed tooltip with tested placement, 2 px scrollbars, open/switch motion, the STEM autopilot strip, a 900 ms boot sequence on the first open per session. |
| 8 | reticle-authority-hud | opus | `refs/heads/worktree-wf_fa773fec-5a4-12` | ddcafa5 | `ScreenProjector`, `ReticleLayer` (target brackets, health bar, charge arc, tag, threat diamonds and edge chevrons, ally and landing marks), `AuthorityIndicator` (split bar under the crosshair). `TargetHud`, `ReadRingModule` ("Ring" setting, default off) and `FightCoachHud` publish marks instead of drawing. |
| 9 | parkour-devtest | sonnet | `refs/heads/worktree-wf_fa773fec-5a4-8` | 4ecb433 | `ParkourScenario`: 15 landing lanes (fence 2-gap, wall 3-gap, pane 1-gap, corner fence, corner wall, each in all three control modes) and 2 manual-override lanes, with a replica parity recording. `docs/state/playtest-checklist.md` section 0. |
| 10 | commit-control | opus | `refs/heads/worktree-wf_fa773fec-5a4-7` | eccaab0 | `SupportRegion`, `LandingHold` (replaces the stick tap), `OverrideClassifier`, `JumpCommit` state machine, `FlightController` air brake, three control modes in `MovementAssist` (`control_mode`, default CO_PILOT), `landing_hold`, `hold_release`, `hold_limit_ticks`, `override_sensitivity`, three `LegitPresets` knobs, authority publishing. |

Integration commit on top of the merges:

- `UtilitySuiteMod`: `MarksBootstrap.init(modBus)` after `UiBootstrap.init` (projector, reticle layer, authority bar).
- `CombatBootstrap`: `hud.useThreats(threats)`, so projectile and blast threats are screen marks. The `target_hud`
  GUI layer is still registered; its `render` is a no-op now.
- `ToolsTab`: the Appearance card under "Suite" (no toggle; the module is always on), with a blurb.
- `SuiteHud`: shows `MovementAssist.parkourStatus()` as the one status line while it is non-null ("Parkour: locked",
  "Parkour: hold (release W to move)", "Parkour: yours"). It outranks the job line and ranks below an acting
  autopilot reflex. Constant strings, compared by reference each tick.
- `MovementAssist.fillNow`: mirrors `crouching` and `sprinting` from the real player into the replica body
  (`MovementPhysics.set` does not reset them).
- `en_us.json`: 63 keys merged from the six lang files of this volley. Settings and HUD text still use literals in
  code; the keys are there for the switch to translated text.
- **`FlightController` fix found by the first joint run.** `LandingJointTest` could not run on either branch alone
  (it needs the shape replica). On main the pane case failed in every mode: a sprint jump with W held overflies a
  narrow top that is level with the takeoff, and the brake family was anchored on the landing tick of the current
  keys, which in that case is a floor far below. The family is now anchored on the tick the body comes down
  through the top's height (air time to a height does not depend on the horizontal keys). Co-pilot and Takeover
  now land and hold the 2-gap pane.
- `LandingJointTest`: the scripted player no longer re-aims within one block of the target (the aim flipped 180
  degrees when the body passed the centre, which aborted the commitment as a hard turn). For the pane, Corrections
  (NUDGE) is allowed to miss: it has no air brake by design. A landing that is made must still hold.

Per-task notes are in `integration/*.notes.md` (`core-shared-control`, `shape-physics`, `commit-control`,
`parkour-devtest`, `holo-hud-core`, `kit-widgets`, `menu-chrome`, `reticle-authority-hud`, `combat-hit-planner`,
`inventory-smarts`).

## Unmerged branches

None. All ten branches of this volley are merged. Branches and worktrees were left in place (nothing pushed, nothing
deleted).

## Tests

- `./gradlew test`: **1542 tests, 0 failures, 0 skipped** (the base had about 1,290). `compileJava` and
  `compileDevtestJava` pass.
- Static input-rule grep: outside `core/input/` and `mixin/` it hits only two read-only `inv.selected ==` reads
  (`travel/movement/FallStaging`, `ParkourBlocks`). No `baritone.*` import outside `jobs/baritone`.
- New allocation tests: shared overlay, co-steer, shaped physics tick and `LevelShapes` lookups, landing hold, jump
  commitment, flight controller, support region, brain `decide` per mode, HoloKit emitters, HUD host, full HUD
  frame, reticle layer, hit planner, gear read, food selector, hotbar refill planner.
- Budgets asserted: commitment at most 1,200 simulated ticks per client tick (measured NUDGE 1, GUIDE 56,
  CARRY 139 before the brake fix; the fix can add up to 8 rolls on an overflown target, still under the bound and
  tested); worst-case HUD frame under 600 quads and 40 text runs; 16 reticle marks under 250 quads.

## Things to know

1. **Parkour default changed.** Movement Assist now defaults to **Co-pilot** (`control_mode = CO_PILOT`), and a
   profile without the key reads Co-pilot. Corrections is the old behaviour. Landing hold is on.
2. **Corrections cannot brake.** On a narrow top level with the takeoff (a pane across a 2 gap), Corrections with W
   held overflies it. Co-pilot and Takeover brake in the air.
3. **Keys only.** No mode turns the camera yet. `YawCoSteer` exists in core but nothing requests it (owner decision
   D1 is open). Movement Assist still writes keys in its own `MovementInputUpdateEvent` listener and still calls
   `LocalPlayer.setSprinting(true)` for takeoff shaping; moving both onto `ISharedControl` is the next step.
4. **Shape rules run everywhere in game.** `LevelShapes.shaped()` is always true, so the planner now uses step-up,
   sprint loss on walls, per-tick slipperiness and jump delay. Hand-built cube grids in tests keep the old numbers.
5. Not modelled in the replica yet: slime bounce, honey and soul sand factors, climbables, water as a landing,
   cobweb and powder snow. Those cells are never committed to.
6. **Devtest lanes are untuned guesses.** The 17 new lanes in `8b-parkour` were never run: geometry, the jump
   trigger (z >= 10.85) and the corner strafe tap timing will need tuning. They add about 1,700 ticks to the run.
7. `LegitPresets` level is read once per shared grant, so a level change applies from the next grant.
8. The hit planner follows the 1.21.1 source where it differs from the design: a sweep also needs the player
   moving slowly, and sprint comes back in the air when forward is pressed again with sprint held.
9. Every non-hostile entity in the sweep box counts as protected, other players included, so a sweep never touches
   a bystander.
10. The sweep-box count allocates one AABB and a section walk every 5 samples (same as the existing proactive scan).
11. Eat-from-pack: if the survival lease is preempted between the pull and the swap-back, the food stays in the
    hotbar. With a full hotbar the swap target is the selected slot; it is restored afterwards.
12. UI: `UiTheme` colour fields are no longer final (the accent is user-chosen). Some tab code still passes old
    literal ARGB colours. The menu has no close animation. Section headings inside content cards keep the old look.
    The HERO glow on the goal card may be clipped by the content scissor. `ToggleSwitch.warn(true)` has no caller.
13. HUD: two render types (`holo_gui` linear, `holo_gui_glyph` nearest). The status line text change fades the new
    text in only. `TargetLine` is unused now and can be deleted with its test.
14. The reflex "acted in the last 10 s" mark on the autopilot strip only sees reflexes while the menu is open.

## In-game playtest checklist

Run `./gradlew runClient` (dev client with Baritone). Use a creative superflat test world unless a step says
otherwise. Do the steps in order; stop at the first one that fails and note what you saw.

### 0. Startup

1. The client reaches the title screen. The log has no mixin audit failure and no exception mentioning
   `holo_gui`, `HoloAtlas` or `MarksBootstrap`.
2. Join the world. With nothing running the HUD is empty (no pill, no marks, no bar under the crosshair).
3. F3 with the menu closed for a minute of idle play: no stutter, frame time flat.

### 1. Menu (STEM UI)

Set up: default settings, GUI scale Auto.

1. Press Right Shift. First open of the session: a 900 ms boot (centre line, frame unfold with a bracket snap,
   columns left to right, a readout with the module count). Click during it: it skips at once and the click lands.
2. Close and reopen: a 110 ms frame unfold and a content fade, no boot.
3. The world behind is blurred and darkened. Set Options > Accessibility > Menu Background Blur to 0: the window
   still hides chat and HUD text behind it.
4. Window: one glass surface with a bracket frame and chamfered corners, hairlines between columns, no solid grey
   panels. Title bar: 2 px accent bar, caps title, version and profile in bracketed readouts.
5. Click through Jobs, World, Travel, Combat, Tools: the sidebar accent bar slides to the new row; content fades in
   and slides 6 px; text is readable on the glass everywhere.
6. Module list: a 2 px category hue bar per row; an accent dot when on, a dim dot when off. Right-click a module to
   toggle it. Start a job (`gather 4 coal`): the module holding a lease shows a diamond.
7. Widgets: toggles slide over about 140 ms and keep their animation when the page rebuilds; sliders show a default
   tick; hover and press give a visible response; nothing is rounded.
8. Hover a setting for 350 ms: a framed tooltip fades in, never covers its control, never leaves the screen (try a
   control at the right and bottom edges).
9. Type in the search box: rows reflow, no jump.
10. Tools > Suite > **Appearance**: change Accent (menu and HUD recolour at once), Effects OFF (flat fills, 1 px
    lines, no glow), Motion REDUCED (fades only) and OFF (everything instant), HUD opacity 40 and 100, HUD scale 75
    and 150. Restart the client: the values persist.
11. GUI scale 1, 2, 3, 4 and a small window (two-column layout): frame, corners, hairlines, list rows and scrollbars
    line up; nothing is cut by the scissor. Check the goal card's glow is not clipped.
12. Autopilot strip: off = quiet. Turn the autopilot on: a 300 ms unfold with a chip sweep, a 2 px accent bar at the
    left, lit chips, no solid green. Trigger a reflex (walk off a 6-block drop with a water bucket) with the menu
    open: its chip shows a diamond for 10 s.
13. Tab GUI (if you use it): two framed plates, bracketed selected row, the module column unfolds.

### 2. HUD

1. Start `gather 4 coal`: one status pill appears with a fade; the text is crisp at GUI scale 2 and 4.
2. Press Stop-All: the stopped badge snaps in (brackets, red) with no fade delay.
3. Clean-Screen on: only the Clean badge shows. Off: the HUD returns.
4. Turn on the info strip and the module list: captions over values, hairline separators, durability bars; toggling
   a module slides its row in over about 160 ms and the others reflow.
5. Look at snow, sky and lava: the glass plates stay readable.
6. With Sodium and with a shader pack, if installed: HUD and menu draw correctly.
7. `/spark profiler start`, play two minutes with a job running and the module list on, `/spark profiler stop`:
   `HudHost` and `HoloKit` are not near the top.

### 3. Parkour control modes (Travel > Movement Assist > Control)

Set up: a stone run-up at least 8 blocks long. Across a 2-block gap at the same height place, in separate lanes: an
oak fence post, a cobblestone wall post (3-block gap), a glass pane (1-block gap), and a full block. Survival mode,
Movement Assist on, landing hold on. Confirm **Control reads CO_PILOT by default** on a fresh profile.

For each of CORRECTIONS, CO_PILOT, TAKEOVER:

1. Sprint at the fence post holding W and jump at the edge. Keep W held after landing. Expected: you stay on top
   (feet at post top, y + 1.5). CORRECTIONS may miss a narrow top more often; when you land, you stay.
2. Same onto the wall and the pane (pane top is y + 1.0).
3. On the post with W still held, the HUD line reads "Parkour: hold (release W to move)". Release W, press W again:
   you walk off at once.
4. Jump onto the full block: nothing is held, you keep running.
5. During a flight and during a hold, press sneak: the assist stops writing keys that tick ("Parkour: yours").
6. Press S against the jump line, or turn the mouse more than 25 degrees off the line in flight: the commitment
   ends and the keys are yours.
7. Authority bar under the crosshair: appears only during a commitment or hold, shows the assist share, drains over
   the hand-back (6 ticks Co-pilot, 8 Takeover), a lock mark while keys are latched, an amber pulse when you
   override. It fades out about 200 ms after.
8. The locked landing shows corner brackets on the real top (fence 1.5 high), not a full box.
9. The camera never moves on its own in any mode.
10. TAKEOVER on the 2-gap pane with W held: you see S pressed for you in the air and you stop on the pane.
11. Stop-All during a flight: everything releases at once. Open the inventory during a flight: same.
12. Legit level LEGIT vs the others: on LEGIT the assist changes fewer keys per jump and reacts 2-4 ticks late.
13. Sneak on a fence post edge and walk: you never fall off (the game's own rule, checks the replica matches).
14. Autopilot on with Control set to CORRECTIONS, then jump a gap toward the fence post: the PARKOUR reflex runs
    at Co-pilot strength at least (you hold the post as in step 1) and its chip lights while it acts.

Then, optionally, `./gradlew runPlaytest -PplaytestOnly=Parkour` and read the 17 control-model lanes and the parity
note (classes above 0.001 block are listed). Expect to tune lane geometry first.

### 4. Combat

Set up: survival, diamond sword, iron armour, Combat Assistant armed, Target HUD on. A flat area, one zombie.

1. Target a zombie: brackets contract onto it over about 180 ms, a 5-segment health bar, a tag on a leader line
   (name, HP %, distance, state word). No old text pill. Break line of sight: the brackets go dashed and dim.
2. Read ring: with "Ring" off (default) a charge arc and a strafe chevron sit on the brackets; turn "Ring" on for
   the old world ring.
3. Skeleton shooting at you: an amber diamond on the arrow, red when the hit would kill; turn away and an edge
   chevron with a distance shows. Creeper or TNT: same for the blast.
4. Hit choice AUTO, one zombie: the first hit is a sprint knockback hit on the ground. Later hits include crits:
   the assistant hops as the cooldown finishes and you see **crit particles** on the swing.
5. Hit choice CRITS under a 2-block ceiling: no hop. With lava or a drop behind you: no hop.
6. Hit choice KNOCKBACK: no hops, sprint hits. PLAIN: neither.
7. Three zombies close together, a sword in hand, standing nearly still: sweep hits. Add a tamed wolf or a villager
   inside the group: **no sweep ever touches it**.
8. An axe: hops are timed to the slower cooldown and still crit.
9. Gear read: fight in leather with a wooden sword against a player or a zombie in iron: the assistant keeps more
   distance and trades less than in even gear.
10. "Never disengage (duel mode)" on, drop to low health with a golden apple in the hotbar: it backs off 6 or more
    blocks, eats, and comes back; it does not flee the fight, and losing sight or range does not end it.
11. Rotations stay smooth through all of this (no snap on target switch or on a crit hop).

### 5. Inventory

1. Survival, steak in the pack only, nothing edible in the hotbar, hunger at or below 14: the player pulls the
   steak into the hotbar, eats, and the slot is restored. With a chest open or while breaking a block: it waits.
2. Several food types: it prefers the better one; before a long sprint leg it tops up below 18.
3. Smart Inventory on, mine until a pickaxe breaks with other pickaxes in the pack: the best one (highest tier,
   enchanted) moves into the same slot. A pinned slot only takes its own item unless the pinned option is on.
4. Clear a hotbar slot by hand: it is not refilled.

### 6. Everything together

Autopilot on, Movement Assist Co-pilot, Combat armed, module list and info strip on, a goal job running:
`/spark profiler start`, five minutes of mixed play including a fight and a few jumps, `/spark tps`,
`/spark profiler stop`. No `utilitysuite` frame should dominate; no allocation spikes in `/spark heapsummary`
deltas.
