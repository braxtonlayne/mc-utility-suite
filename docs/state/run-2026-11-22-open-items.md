# Approach-control volley, 2026-11-22: what landed and what is open

Base: 7b7d078 (contracts 5a14f77 plus the `HoloTheme` font and marker fields). Eleven tasks were built in parallel
worktrees and merged into `main` on 2026-11-22 around 09:00 EST.

**Nothing in this run was playtested in a game client.** No `runClient`, `runPlaytest` or `runUiPreview` was run.
Everything below is compiled and unit-tested only. One new mixin was added (22 in the client list); the mixin audit
runs at the first `./gradlew runClient`.

## What landed on main

All eleven merged with `git merge --no-ff`, no conflicts (no file was touched by two branches). `compileJava` and
`compileDevtestJava` were green after each merge. No branch was left unmerged.

| Order | Task | Tier | Branch | Tip | Contents |
|---|---|---|---|---|---|
| 1 | technique-library | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-5` | 91121e8 | `TechniqueLibrary` (a `TechniqueSource`): budgeted search (600 replica ticks per call) over corner, headhitter, neo and 45-degree templates, keys-only grids before yaw grids, kept script re-based each call. `TechniqueTemplates`, `TechniqueRoller`, `ParkourCourse.TECHNIQUE_CASES`. |
| 2 | intent-v2 | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-6` | d915a09 | `IntentEstimator`: real top heights from collision boxes, reach checked by a replica roll (2 rolls, 96 sim ticks per estimate), gaze averaged over about 6 ticks, course ring of the last 8 landings, dead-end tie-break, hysteresis, commit threshold per mode (GUIDE 0.5, CARRY 0.35). |
| 3 | replica-parity | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-3` | 24397ad | The 0.30 lane error was the measurement (replica seeded with zero velocity). `ParkourScenario` parity measurement rebuilt. `MovementPhysics` full path now uses vanilla float arithmetic and the `Mth` sin/cos table. `VanillaParityTest`. Cube path unchanged. |
| 4 | parkour-approach | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-2` | 2974877 | `ApproachController`: takeoff plans by least intervention (the user's press, a held-back press up to 4 ticks, eased W 1-4 ticks, walking run-up with sprint withhold), 1120 replica ticks per tick. Brain: target freeze from takeoff with one trim, narrow-target plan region, co-steer outputs, technique script replay. `FlightController` brake-from-now and sim cap. |
| 5 | movement-authority | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-4` | d3e17ab | `SharedGrant.withholdSprint()` in the core plus `LocalPlayerSprintWithholdMixin`. `MovementAssist` writes only through `ISharedControl` (no direct `Input` or `setSprinting` writes left). Yaw co-steer on Balanced and above in Co-pilot/Takeover (`co_steer`, default on). Four `LegitPresets` co-steer knobs. Hold-to-take-over key, unbound. |
| 6 | render-font-world | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-7` | 50e22fa | `SuiteFonts`: suite font pipeline that switches on only when the three TTF files are present (in-memory pack, no access transformer, no mixin). `HoloWorldKit` (`IHoloWorld`): ribbons, dashed polylines, rings, brackets, ground marks, nearest 512 segments per frame. Ghost Line moved onto it. Appearance settings `font` and `armed_marker`. |
| 7 | notices-autopilot-hud | sonnet | `refs/heads/worktree-wf_9c40d8a5-5d2-8` | fbaba4f | `NoticeBoard` and `NoticeLayer` (bottom-right toasts, max 3). `AutopilotHud` (engage pulse, ONLINE/OFFLINE notices, takeover brackets, STEM status line). Status messages of 14 modules moved to the notice board. |
| 8 | ui-restyle-checks | sonnet | `refs/heads/worktree-wf_9c40d8a5-5d2-9` | c6e3f88 | Fight Coach cooldown arc at the crosshair, `StyleScanTest` with an allow-list, `UiPreview` 1x1 capture fix, settings resolved through lang keys, Timeline and minimap restyled, `GoalConsole.errorLine` / `previewCard` nodes. |
| 9 | combat-threat-fights | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-11` | e021a19 | `FightStageSeamTest`, two seam fixes (`requestTarget` never picks an ally; `widenSpacing` beats the positional move). `ThreatFightStage` with `NearbyThreats` and `MobPlaybook` (`threat_fights`, `mob_playbook`), `ComboEscapeStage` (`combo_escape`). |
| 10 | combat-shield-duel | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-10` | 5131311 | `ShieldDuel` / `ShieldDuelStage`: no swings into a frontal shield, circle to the open side, block-and-answer with our own shield (`shield_duel`, `shield_duel_block`). `ShieldBreakSequencer` back-off 10/20/40/80 ticks. Disabled-shield window in `OpponentModel`. Blocked hits in `HitTradeSolver`. |
| 11 | combat-heal-windows | opus | `refs/heads/worktree-wf_9c40d8a5-5d2-12` | a79b87b | `HealWindow` / `HealWindowStage`: heal at hits-left, score the window, make a window with a retreat route, top-ups (`heal_windows`, `heal_hits_left`, `heal_top_up`). `FoodSelector.SwapBack` closes the eat-from-pack swap-back gap. Raw chicken is now in the unsafe list under its real id. |

Integration commit on top of the merges:

- `MovementAssist`: `brain.setTechniques(new TechniqueLibrary())`. The estimator's commit threshold is set per mode
  each tick (Takeover or the take-over key: 0.35, otherwise 0.5), and the brain reads the same value through the new
  `MovementAssistBrain.intentThreshold`. Before, the brain compared against a fixed 0.5, which would have hidden the
  Takeover threshold. Touchdown heights go to the estimator (`noteJump` with y). `brain.freeLook` follows the
  "Look style" setting. New setting `approach_control` (default on) sets `brain.approachControl`.
- `CombatBootstrap`: the four stages are added before the assistant is registered, in this order:
  `ThreatFightStage(new McNearbyThreats())`, `ShieldDuelStage(new McShieldFacts())`,
  `HealWindowStage(new McHealFacts())`, `ComboEscapeStage()`.
- `en_us.json`: 41 keys merged from the seven `integration/*.lang.json` files of this run plus the two
  `approach_control` keys. No key conflicted with an existing one.

## Unmerged branches

None. All eleven `refs/heads/worktree-wf_9c40d8a5-5d2-2` to `refs/heads/worktree-wf_9c40d8a5-5d2-12` are merged.
Branches and worktrees were not deleted and nothing was pushed.

## Checks

- `./gradlew compileJava compileDevtestJava test --offline`: **1797 tests in 235 classes, 0 failures, 0 skipped**
  (1542 before this run).
- Static input-rule grep: only `core/input/` and `mixin/`, plus the two allowed read-only `inv.selected ==` reads
  (`FallStaging`, `ParkourBlocks`).
- Mixins in `utilitysuite.mixins.json` `client`: 22 (new: `input.LocalPlayerSprintWithholdMixin`).
- `HudElementsTest.fullFrameAllocatesNothing` (asserts exactly 0 bytes) failed once at 240 bytes in one task's own
  full run and passed on rerun. It passed in both integration runs. Treat a single failure there as JIT noise first.

## Things to know

Behaviour changes to review (details in each task's `integration/*.notes.md`):

1. **Co-pilot now eases W on the ground while ARMED.** The brief asked for it, but design table 4 says Co-pilot
   withholds nothing on the ground. Owner to confirm, or turn `approach_control` off.
2. Approach plans accept a landing margin of 0.02 block (the design said 0.05): the late 3-gap fence press lands at
   0.046.
3. With "Assist automation" on and another module holding the MOVEMENT lease, Movement Assist now writes nothing.
   Before, it wrote over the job's keys.
4. The TRAVEL grant suspends toggle-sprint's IDLE lease while a commitment is live; the arbiter resumes it after.
5. During a parkour-blocks placement the brain's axis writes are dropped for those ticks.
6. Written axes get vanilla's sneaking multiplier while the player moves slowly (landing hold).
7. Takeover now commits at intent confidence 0.35 (integration wiring). UNMODELLED tops are capped at 0.3, so they
   still never commit.
8. `MovementPhysics` full path: four pinned test values moved by about 1e-8, and a coasting body now stops at
   vanilla's 0.003 floor (about 0.004 block difference at most).
9. Shield knockback: in 1.21.1 a blocked hit moves the shield holder toward the attacker
   (`knockback(0.5, holder - attacker)`). The solver follows the source, not the brief's word "pushback".
10. `McShieldFacts` registers its own `PlayLevelSoundEvent.AtPosition` listener in its constructor.
11. The suite-font path draws through `Font.drawInBatch(FormattedCharSequence)`, which skips bidi reordering. It
    only matters for right-to-left text with the suite font on.
12. World-space kit users must draw at `RenderLevelStageEvent` AFTER_PARTICLES, normal priority, through
    `Services.visuals().world()`.
13. Dot-command feedback is now a toast, so it is dropped in Clean-Screen. Devtest `ChatCapture` no longer sees the
    migrated messages (no scenario asserts on them).
14. AutoRespawn keeps its chat line with the death coordinates in addition to the toast.

## Font files the owner must approve

Nothing was downloaded. The pipeline stays on the vanilla font until all three files are in
`src/main/resources/assets/utilitysuite/font/` with these exact names. The licences and links below were **not
checked online**; verify them before downloading.

| File name | Family, weight | Licence (to verify) | Source (to verify) |
|---|---|---|---|
| `rajdhani-medium.ttf` | Rajdhani Medium (500) | SIL Open Font License 1.1, Indian Type Foundry | https://fonts.google.com/specimen/Rajdhani or https://github.com/itfoundry/rajdhani |
| `rajdhani-semibold.ttf` | Rajdhani SemiBold (600) | SIL Open Font License 1.1, Indian Type Foundry | same |
| `jetbrainsmono-regular.ttf` | JetBrains Mono Regular (400) | SIL Open Font License 1.1, JetBrains | https://github.com/JetBrains/JetBrainsMono releases (`fonts/ttf/JetBrainsMono-Regular.ttf`) |

With the files: use the unmodified TTFs (renaming the file is fine, changing the font is not), add
`LICENSE-Rajdhani.txt` and `LICENSE-JetBrainsMono.txt` (each source's OFL.txt) beside them, and add both families to
a top-level `THIRD-PARTY-NOTICES.md`. See `src/main/resources/assets/utilitysuite/font/README.md`.

## Open items (not done in this run)

Movement:

- In-flight co-steer aims at the target centre. The yaw that makes the key acceleration exact (design 6.1 step 3) is
  not implemented.
- The CHAIN phase is still a stub.
- Corner onto a fence post from the straight line behind the column: no template lands it with sprint held. A
  sprint-withhold template variant may open it. The replica-parity notes argue the scripted corner lanes are
  probably impossible as built; decide after the lane run prints the touchdown x offset.
- The headhitter tests do not show that the plain jump fails there, and no scene shows the 45-degree template
  winning a jump.
- Intent: one roll with one jump policy per top, so a top that needs a technique can be marked infeasible. Ladders
  and water are not landing candidates. The jump search does not use `intent.targetTopY()` yet.
- Replica: climbable cells and water are flags only. The cube path keeps double arithmetic (within 1e-5 of vanilla).
- The 17 devtest lanes were not run.

Combat:

- The `combat_reflexes` shield reflex and `ShieldDuelStage` can both hold USE in a fight. They agree on the cone but
  not on timing. The reflex should stand down for melee while the stage blocks. It was not changed here, because the
  reflex also covers projectiles and other attackers.
- A shield disable is only detected by the shield-break sound. Seam request: `CombatAssistant` records
  `noteShieldDisabled` after the sequencer's swing.
- `FightContext` seam requests: `takenWeightScale` and `holdGround` for the disabled-shield window; `pullConsumable`
  for the staged pull (the decision is computed, nothing clicks); a way to turn off the broad tactic's KITE spacing
  (witch); a PRE_SWING veto of crit hops during a combo escape.
- Not built: offhand totem/shield policy, splash healing, pre-fight absorption, top-up after a kill, predicted jump
  reset.
- The shield stage has no legit / less-legit setting; it always uses the 70-degree front cone.

UI:

- `JobsTab` still draws the old card text. `GoalConsole.errorLine` / `previewCard` exist but are not placed (exact
  change in `integration/ui-restyle-checks.notes.md`).
- `HudPill.draw` should use `FrameLevel.HERO` for the takeover status line (one line, needs a look in game).
- `CoreBootstrap.setAutopilot` still shows "Autopilot on/off" on the action bar, which now duplicates the
  ONLINE/OFFLINE toast. `SurvivalAutopilot`, `CombatEventBridge` and `TargetHud` still use chat or the action bar.
- `show_swing` no longer hides the coach arc (`FightCoach.onTick` was not touched).
- A 33-row module list plus 3 detailed toasts measures 44 text runs, over the 40 budget.
- No `world_lines` setting (the plain-line fallback is automatic). No see-through world render type. Only Ghost Line
  uses the world kit.
- Style-scan allow-list backlog: `InfoStripText` colours, direct `g.fill` in `SuiteMenuScreen`, `AutopilotBar` and
  `TabGui`.

## In-game playtest checklist

Run in this order. Stop at the first failure in section 0.

### 0. Startup

1. `./gradlew runClient`. The log must show the mixin audit applying 22 mixins, including
   `LocalPlayerSprintWithholdMixin`, with no error.
2. No warn or error line about `utilitysuite:font/...`. One INFO line says the suite font files are absent.
3. Open a world and the menu. Combat Assistant settings list the new entries: Threat-aware fights, Mob playbook, Combo
   escape, Shield duel, Block with shield, Heal in windows, Heal at hits left, Top up hunger between exchanges. Movement Assist lists Approach control
   and Co-steer. Appearance lists Font and Armed marker. Each has a name and a tooltip (no raw keys).

### 1. Replica parity

4. `./gradlew runPlaytest -PplaytestOnly=Parkour`. Per lane, read: ground run, flight, after touchdown, one-step
   max. Expected: one-step about 1e-6, flight and after-touchdown under 0.001 on fence, wall and pane lanes.
5. A one-step line above 1e-4 names the tick and inputs. Note it: it is a rule gap, not drift.
6. Note each corner lane's touchdown x offset (it decides whether the corner lanes are possible).

### 2. Approach control and sprint withhold (Balanced, Co-pilot)

7. Fence post across a 3 gap, W + sprint held, press jump about 0.15 block before the edge: lands and stays.
8. Same, press jump at the very edge: the press is held back at most 4 ticks or W eases; lands and stays.
9. Pane level with the run-up, 1 and 2 gap: lands on the pane, not back on the platform.
10. Let go of W during the run-up: nothing is written, no jump.
11. Sneak during the run-up: the approach ends.
12. Watch the sprint during an eased tick: it stops for exactly those ticks and your held sprint key takes over
    after. Compare the feel with `approach_control` off.
13. Toggle-sprint (MoveToggles) and double-tap-W sprint still work before, during and after a commitment.
14. Corrections mode: no eased W, no held-back press.
15. Legit level: the reaction delay is visible; no co-steer.
16. With "Assist automation" on and a goal job walking: the assist writes nothing. Decide whether that is wanted.

### 3. Co-steer and take-over key

17. Balanced, Co-pilot, committed jump with the mouse still: the camera eases toward the jump line, never jumps,
    and stays within about 25 degrees per jump.
18. Move the mouse against it: the view is yours for the rest of that jump; the keys continue.
19. Legit level, Corrections mode, autopilot on, a screen open: the camera never moves.
20. Look style FREE: the co-steer may leave the movement direction. NATURAL keeps it within 30 degrees.
21. Bind the take-over key. Hold it mid-approach with W toward a block: it flies the jump. Release: the selected
    mode is back on the next tick.
22. Takeover mode on a real course (for example Twisted Parkour): the target pick follows your look, does not flap
    on small mouse jitter, and does not pick the platform you just left when you turn around.

### 4. Techniques

23. Corner jump onto a block and onto a fence post (approach from the side of the column), keys only (Legit) and
    with yaw (Balanced or Relaxed).
24. Headhitter 2 and 3 gap. 1-block neo.
25. Release W or turn hard during a script: it is dropped at once.

### 5. Combat

26. Player with a shield (second client): no swings into the raised shield from the front. The assistant circles
    to the open side and hits on the tick the shield drops.
27. Hold an axe against a shielding player: the disable is followed by crits and pressure for the window. After a
    failed break, retries back off (10, 20, 40, 80 ticks).
28. Our own shield in the offhand: raises just before their hit lands, lowers inside their cooldown, never against
    an axe holder, never while eating. Check under ping.
29. Check for a double shield raise or stutter between the shield reflex and the shield stage.
30. Survival night: zombie pack with a creeper and a skeleton. Target switches are calm (no flapping), a swelling
    creeper causes a back-off, arrows cause a side strafe, 3 or more mobs widen the spacing.
31. Warden and wither are never targeted. Spiders and baby zombies: no crit hops.
32. Combo escape against a second client: after two hits taken without a landed one, a side strafe out of the
    knockback line. It hands back after our hit or 10 ticks.
33. Low health against a zombie and against a player: heals earlier than the old threshold once a hit has been
    measured, backs off around cover to make a window, eats, comes back.
34. Auto-Eat from the pack interrupted by a fight or Stop-All: the displaced item returns to its slot later, and
    not while a container is open.
35. `/spark profiler` during a mob fight: check the 5-tick `McNearbyThreats` refresh and the stages.

### 6. HUD and UI

36. Toasts: bottom right, max 3, repeated messages merge with a counter, readable at GUI scale 1 to 4, nothing in
    Clean-Screen.
37. Autopilot on/off: engage pulse, ONLINE/OFFLINE toast (note the duplicate action-bar line), takeover brackets.
38. Fight Coach arc at the crosshair in a fight; flash on ready.
39. Timeline screen and minimap (also over bright terrain); minimap layout screen.
40. Ghost Line ribbons: look with Sodium, with a shader pack (plain lines should engage), under Fabulous graphics,
    at GUI scale 1 to 4. Dashes advance only with Motion FULL.
41. `./gradlew runUiPreview`: captures are full size, not 1x1.
42. After the fonts are approved and added: text renders in the suite faces, falls back with Force Unicode Font on
    and with Font = Vanilla. Tune size, oversample and shift at GUI scale 1 to 4.
