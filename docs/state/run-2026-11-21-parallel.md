# Parallel feature run, 2026-11-21

Source list: `docs/research/ideas-2026-11-21.md` (A upgrades, B menu polish, C new modules). Eight packages were built
in parallel worktrees from f2fee72 and merged into `main` between 16:18 and 16:19 EST, then integrated.

**Nothing in this run was playtested in a game client.** No `runClient`, `runPlaytest` or `runUiPreview` was run.
Everything below is compiled and unit-tested only.

## What landed on main

All eight merged with `git merge --no-ff`, no conflicts, `compileJava` green after each.

| Package | Branch | Tip | Contents |
|---|---|---|---|
| render | `refs/heads/worktree-wf_6d3b99a6-9f3-8` | 889249a | Freelook, radar tracers + nametags, Light Levels, Blast Radius, Spawn Geometry, Render Filters (5 mixins) |
| player | `refs/heads/worktree-wf_6d3b99a6-9f3-9` | 2874f00 | Dot-Commands, Offhand Manager, Auto-Fish, Quick Pearl, Auto-Respawn, Auto-Reconnect |
| jobs | `refs/heads/worktree-wf_6d3b99a6-9f3-7` | 47c13dd | A9 strip-route fluid cost / torch cadence / wall glances; Auto-Torch, Lava Plug, Vein Work, Crop Work |
| ui | `refs/heads/worktree-wf_6d3b99a6-9f3-2` | 163e50a | B1, B3-B9, autopilot HUD line, Tab-GUI |
| input | `refs/heads/worktree-wf_6d3b99a6-9f3-4` | c9d9f01 | A7 Humanizer + PrimitiveRandom, A10 camera momentum release |
| autopilot | `refs/heads/worktree-wf_6d3b99a6-9f3-3` | d57af86 | A1 DangerModel + ReflexScheduler, A3 ReflexChain, A10 BlackBox, vitals feed |
| movement | `refs/heads/worktree-wf_6d3b99a6-9f3-6` | 5e6f23c | A2 staging, A6 clutch ladder, Elytra Swap, sprint/sneak toggles, Mount Jump, Fly, Velocity Scale (1 mixin) |
| combat | `refs/heads/worktree-wf_6d3b99a6-9f3-5` | a09aff2 | Danger feed, A4 dodge/escape, A5 shield, A8 tactic utility + opponent model, Trigger Assist, Target HUD (1 mixin) |

Integration commit on top of the merges:

- `en_us.json`: 36 keys merged from `integration/*.lang.json` (no key collisions).
- `utilitysuite.mixins.json`: 7 mixins added under `client` (now 19; every entry has a source file):
  `combat.ClientPacketListenerTotemPopMixin`, `travel.ClientPacketListenerVelocityMixin`,
  `world.GameRendererFiltersMixin`, `world.LevelRendererWeatherMixin`, `world.ParticleEngineFilterMixin`,
  `world.ClientLevelDataTimeMixin`, `world.LevelWeatherOverrideMixin`.
- `ui/tabs/ToolsTab.CORE_SPECS`: the three core input settings (Humanized timing, Humanized skill, Camera momentum
  on takeover).
- `core/LegitPresets.KNOBS`: `combat_reflexes.shield_turn` (false/false/true), `combat_reflexes.shield_front_cone`
  (70/70/90; Balanced kept at the module default 70 because an absent key reads as Balanced), and `relaxed`
  (false/false/true) for `auto_torch`, `lava_plug`, `vein_work`, `crop_work`.
  Side effect: a profile that was on **Relaxed** before this run now reads as "Custom" until Relaxed is applied
  again (the new knobs are absent). Fly and Velocity Scale require Relaxed, so re-apply it before testing them.
- The combat totem keeper already skips while `offhand_manager` is enabled (done inside the combat package).
- The ui note that reflex chips follow a stub `allows()` no longer applies: the autopilot package's per-reflex
  settings are merged.

## Unmerged branches

None. All eight branches are merged; the branches and worktrees (`.claude/worktrees/wf_6d3b99a6-9f3-{2..9}`) were
left in place and can be removed once the playtest is done.

## Test results

- `./gradlew test` on main after integration: **849 tests in 113 suites, 0 failures, 0 errors, 0 skipped**.
- Static input-rule grep: only `core/input/` and `mixin/` hits, plus two read-only `inv.selected ==` comparisons
  (`travel/movement/ParkourBlocks.java:100`, `travel/movement/FallStaging.java:194`), which the rule allows.
- New direct `gameMode.useItemOn` (`jobs/fieldwork/FieldHands.java`, Relaxed placement) re-checks the WORLD_INTERACT
  lease right before the call.

## Needs an in-game playtest (all of it)

1. `./gradlew runClient`: the mixin audit for the 7 new mixins (targets were checked with javap / against the 21.1.265
   sources, never loaded in a client).
2. Menu at GUI scale 3 and 4 (B1): column fit, text caret, tooltips at the edges, scissor edges, mouse hit boxes;
   Tab-GUI overlap with the top-left HUD.
3. Autopilot: scheduler picks under real hazards, chain lease release on manual input, black-box dump on death and on
   a big hit, "Autopilot: <reflex>" line, camera momentum on takeover (also with invert-mouse on).
4. Humanized turn timing feel (small turns get slower); no-snap cap still holds visibly.
5. Combat: shield raise/lower timing, dodge strafe speed (0.12 b/t guess), fuse back-off, tactic utility (flee/kite),
   Trigger Assist, Target HUD totem-pop count, visual range message.
6. Movement: early staging and slot restore, elytra deploy and Elytra Swap click plan, water steer, glide guard,
   sprint/sneak toggles at a ledge, mount jump release point, Fly on the integrated server without allow-flight,
   Velocity Scale.
7. Jobs: Auto-Torch placement and slot restore, Lava Plug, Vein Work order and stop conditions, Crop Work replant,
   strip route turning away from lava.
8. Render: light overlay rescans, blast overlay labels, slime chunks against a known seed, freelook in first and
   third person, each render filter.
9. Player: Auto-Fish bite detection (splash sound, also with volume 0; hook dip), Offhand Manager swaps, Quick Pearl
   restore, Auto-Respawn delay and coordinates line, Auto-Reconnect manual-disconnect heuristic and job resume.
10. Spark profile with the new overlays and scans on (mandate "Verify" beyond the allocation tests).

## Not done / deferred

- Takeover voice lines: needs the Zonos server on the GPU and a listen-through; not started.
- B2 goal placeholder: already done in 68f275a.
- Pearl clutch in game; dodge while the Combat Assistant is engaged; late-bid preemption of an acting reflex; black
  box while the autopilot is off; Tab-GUI key swallowing (needs a `KeyboardHandler.keyPress` mixin); TimelineScreen
  scale; strip-job torch/glance execution; Vein Work walking; cocoa replant; Fitts width from the hitbox; Toggle
  Sprint release on the real sprint key. Full list: ROADMAP "Parallel feature run" > Open, and each
  `integration/<package>.notes.md`.
- Hunter bot (A8 reuse): separate feature.
- `CLAUDE.md` layout block was not edited (project instructions are the owner's to change). Suggested additions:
  `core.autopilot`, `jobs.fieldwork`, `world.lighting / blast / spawn / filters / render`, `travel.movement /
  mobility`, `combat.reflex / tactics / trigger / hud`, `tools.commands / player`.

## Next steps

1. `./gradlew runClient` and fix whatever the mixin audit reports.
2. Menu check at GUI scale 4, then the playtest list above, most dangerous first (reflex chain, shield, dodge).
3. Re-apply the legit level in the menu if the profile was on Relaxed.
4. Spark pass; then pick up the Open items in the ROADMAP.

## In-game checks after the merge (16:22-16:36)

- Mixin audit (`runPlaytest`, audit on): all 19 mixins applied, no failures.
- Menu playtest PASS; the menu now shows three columns at GUI scale 4 (B1 works).
- Parkour playtest PASS: autopilot water clutch and parkour gap block with Movement Assist off still work under the
  new scheduler and chain lease.
- Combat playtest PASS, with a new autopilot lane (Combat Assistant off): the offhand shield is raised against a
  skeleton, and the player backs off a lit creeper (2.4 -> 6.3 blocks).
- Fixed a devtest bug that had broken the gap-ambush lane before this run: `s.cmd(Arena.fill(...))` built the command
  before the arena centre was picked, so it filled at (0, 0). Combat and Travel fills are now deferred lambdas.
- Still untested in-game: dodge, the black-box dump, the danger-model ranking under several hazards, humanized turn
  feel, and every new module outside combat reflexes.
- New `ModulesScenario` (`-PplaytestOnly=Modules`) PASS (16:42): `.t light_overlay` toggles locally and the line is
  never sent; Offhand Manager moves a totem to the offhand at 8 HP; Auto-Respawn presses respawn by itself (the
  harness's own respawn is off via `Scenario.deathExpected`); 14 new modules on at once for 2 s with a creeper in view
  (light overlay, slime chunks, spawn spheres and Tab-GUI visible in `shots/modules-smoke.png`).
- Tools playtest PASS. Goal playtest (16:43) FAILED on world luck: "Dev was blown up by Creeper" while strip-mining
  at y 40 (the creeper was in the world before `doMobSpawning false`; the autopilot and Combat Assistant are off in that
  scenario, so nothing was allowed to react). The 685 deg/s "fast turn" it also reported was the respawn resetting the
  camera, not a turn; the turn sampler now restarts its baseline when the player entity changes. Follow-up worth
  doing: let goal jobs ask the autopilot's THREAT_ESCAPE/DANGER model to pause the strip mine and back off a hissing
  creeper, the way a player would.
- Goal rerun (16:48): no death, no head snapping (fastest turn 306 deg/s), but the job paused on a real GLFW
  mouse press in the game window ("Manual input ATTACK_OR_USE preempted goal_engine") -- a click on the window
  during the run, which the manual-wins rule handles as designed. Rerun Goal with hands off the game window.

## Second volley (tiered) (16:50-17:00)

Eight tasks, sized by tier, each on its own worktree branch. All eight merged into main with `--no-ff`
(`compileJava` after each merge). No unmerged branches. The branches
`refs/heads/worktree-wf_3487b1d8-562-2` to `refs/heads/worktree-wf_3487b1d8-562-9` are kept, not deleted, not pushed.

| Tier | Task | What landed |
|------|------|-------------|
| opus | Job creeper back-off (`-562-2`) | `combat_reflexes` setting `escape_during_jobs` ("Back off during jobs", default on). THREAT_ESCAPE arms while a goal job is RUNNING/REPLANNING even with the Combat Assistant and autopilot off, on the module's own EMERGENCY MOVEMENT lease; the job is preempted to PAUSED and resumes through the arbiter's `onResumable`. Job state is polled every 10 ticks into a cached flag. `JobEscapeGate` + 7 tests. |
| opus | Tab-GUI key consumption (`-562-3`) | Client mixin `mixin/ui/KeyboardHandlerTabGuiMixin` (HEAD of `KeyboardHandler.keyPress`, cancels only when `TabGui.consumesKey` says so). Pure decision in `TabGuiNav.decide` (a release is swallowed exactly when its press was). 8 new tests. |
| opus | Black box while off (`-562-4`) | Setting `black_box_always` ("Record while off", default on). `Autopilot.tickWhileOff()` samples vitals and records rows marked `"off":true`, keeps the death / big-hit dumps, and never schedules, leases or acts. Driven by a `ClientTickEvent.Post` listener. 7 new tests incl. an allocation test (200k off ticks). |
| sonnet | Timeline at menu scale (`-562-5`) | `TimelineScreen` lays out in `MenuScale` virtual pixels, pose-scaled, clicks and hover converted. `TimelineScreenLayoutTest`. |
| sonnet | Travel/World quick rows (`-562-6`) | Both tabs share "Go back" label/title/description; World header pill 68 px; Freecam is a switch row. `QuickRowLabelsTest`. |
| sonnet | Momentum with invert-mouse (`-562-7`) | `MomentumRelease.start(yaw, pitch, invertMouse)` flips the pitch check. The branch left the caller unwired; the coordinator added `InputBackend.invertMouseY()` (default false, real value in `MinecraftInputBackend`) and passes it from `InputSmoother`, so the fix is live. |
| haiku | Legit knob test (`-562-8`) | `LegitPresetsRunKnobsTest` (5 tests) pinning shield_turn, shield_front_cone and the four relaxed-only field modules. Test only. |
| haiku | Docs open items (`-562-9`) | Seven open items added to the roadmap; `integration/ui.notes.md` stub caveat marked resolved. |

Results on main after the merges: `./gradlew test` passes, 885 tests, 0 failures, 0 errors, 0 skipped (was 849).
Static input-rule grep: only the two allowed read-only `inv.selected ==` comparisons in `travel/movement`.

Nothing in this volley has run in a game client. Needs an in-game pass:

- Mixin audit (`runClient`, audit on) for the new `KeyboardHandlerTabGuiMixin`; then Tab-GUI arrows/Enter with a
  key mapping bound to an arrow key, F1/F3/Clean-Screen hiding it.
- A lit creeper next to a running strip-mine job: the job pauses, the player backs off, the job resumes. Watch
  whether the resumed path walks straight back into the blast spot (nothing in `JobExecutor` changed).
- Black-box rows and a death dump with the autopilot off; that the tick listener registers in the real game.
- Timeline screen at GUI scale 3 and 4; World/Travel "Go back" rows and the Freecam switch.
- Camera momentum on takeover with invert-mouse on and off.

Known leftovers: `UiBootstrap` still registers the now-empty `TabGui.onKey`; the arbiter allows one lease per
module, so a shield lease and the job-escape move lease from `combat_reflexes` replace each other (older than this
volley); the new settings use inline labels, no lang keys.
- 17:00 check on main after the second volley: mixin audit clean (KeyboardHandlerTabGuiMixin applies), Menu and
  Modules playtests PASS.

## Third volley (tiered) (17:00-17:12)

Seven tasks, sized by tier, each on its own worktree branch. All seven merged into main with `--no-ff`
(`compileJava` after each merge, smallest first). No unmerged branches. The branches
`refs/heads/worktree-wf_4262cab8-ad7-2` to `refs/heads/worktree-wf_4262cab8-ad7-8` are kept, not deleted, not pushed.

| Tier | Task | What landed |
|------|------|-------------|
| opus | Reflex lease split (`-ad7-2`) | `CombatReflexes` requests its movement lease under `combat_reflexes.move` and its interact/turn lease under `combat_reflexes`, so a shield raise and an escape or dodge no longer replace each other (the arbiter replaces by module id; it does not validate ids against the registry). Release, disable and Stop-All work by lease reference and are unchanged. `ReflexLeaseSplitTest` (6 tests, real `ActionArbiter`). |
| opus | Job resume hazard hold (`-ad7-3`) | `jobs/goal/ResumeHoldGate` (pure HOLD/GO): after an EMERGENCY movement preemption, or any preemption while `Services.danger().has(HazardKind.EXPLOSION)`, `JobExecutor` does not re-request its lease while the explosion hazard is live, then waits 10 settle ticks, capped at 100 ticks from the preemption. Status reason "Waiting for hazard to clear". The user's resume, Stop-All and world unload disarm it. `ResumeHoldGateTest` (8 tests incl. a 200k-call allocation check). No `core.api` change. |
| sonnet | Setting lang keys (`-ad7-4`) | Four `en_us.json` keys (`setting.utilitysuite.autopilot.black_box_always`, `setting.utilitysuite.combat_reflexes.escape_during_jobs`, each with `.tooltip`) and `NewSettingLangKeysTest`. Nothing reads them yet: `SettingSpec` carries plain strings and the renderer uses `spec.label()` directly. |
| sonnet | Hot-path allocation tests (`-ad7-5`) | `JobEscapeGateAllocationTest`, `TabGuiNavAllocationTest`, `MomentumReleaseAllocationTest`: 200k calls each under a 1 KiB budget, all pass. Test only. |
| haiku | Drop `TabGui.onKey` (`-ad7-6`) | Removed the empty `InputEvent.Key` listener and its registration in `UiBootstrap`; keys reach the Tab-GUI only through the mixin and `consumesKey`. |
| haiku | Dodge planner tests (`-ad7-7`) | 8 more `DodgePlannerTest` cases (tap clamp, horizon and hit-radius boundaries, zero / straight-down velocity, both escape directions hazardous, state reset, blast-radius edge, blocked diagonal). No bug found, `DodgePlanner` unchanged. |
| haiku | Playtest checklist (`-ad7-8`) | `docs/state/playtest-checklist.md`: about 55 unchecked in-game items in seven sections, each with its source. |

Results on main after the merges: `./gradlew test` passes, 912 tests, 0 failures, 0 errors, 0 skipped (was 885).
Static input-rule grep: only the two allowed read-only `inv.selected ==` comparisons in `travel/movement`.

Nothing in this volley has run in a game client. Needs an in-game pass (also listed in the playtest checklist):

- Raise the shield while an escape or dodge is running: both leases should stay held, and manual keys still win.
- A lit creeper next to a running strip-mine job: the job pauses, the player backs off, the status line shows
  "Waiting for hazard to clear", and the job resumes about half a second after the hazard is gone.
- Tab-GUI arrows/Enter still work with the `InputEvent.Key` listener gone (mixin path only).

Known leftovers:

- The resume hold is not distance-aware: `IDangerModel` reports no hazard position, so any live EXPLOSION hazard
  holds the job. The distance overload of `ResumeHoldGate.decide` exists but has no caller.
- No planner no-go zone: if the hazard outlasts the 100-tick cap, the job resumes on its original route.
- Because of the live-hazard fallback, a non-emergency preemption during a fuse also arms the hold (100 ticks at
  most). There is no `JobExecutor`-level test of the arming, only of the gate.
- PacketLab lease log lines and other modules' `onPreempted` ids now show `combat_reflexes.move` for reflex moves.
- The setting lang keys need a lookup in `SettingsRenderer` (try the key, fall back to `spec.label()`).
- The checklist keeps the mixin audit as owed although the 17:00 check on main was clean; this volley added no
  mixin, so the 17:00 result still stands.
- Commit trailers on `-ad7-6` and `-ad7-8` name Claude Haiku 5.5 (their session attribution); not amended.

## Next session, first things

1. Work through `docs/state/playtest-checklist.md`, starting with a lit creeper next to a running strip-mine job
   (pause, back off, hold, resume) and the shield raised during an escape.
2. Tab-GUI arrows/Enter in a real game; camera momentum with invert-mouse; Timeline at GUI scale 4.
3. Dodge tuning in game; Fly/Velocity Scale after re-applying Relaxed.
4. Remove the 23 kept worktrees under `.claude/worktrees/` once nothing on them is needed.
