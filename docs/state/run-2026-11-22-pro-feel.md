# Pro-feel volley, 2026-11-22

Base: 68af800 (contracts for the route, hit-trade and intent-assist volley). Nine tasks were built in parallel
worktrees and merged into `main` on 2026-11-22 around 05:10 EST.

**Nothing in this run was playtested in a game client.** No `runClient`, `runPlaytest` or `runUiPreview` was run.
Everything below is compiled and unit-tested only. The mixin audit for the one new mixin has not run yet: it runs
at the first `./gradlew runClient`.

## What landed on main

All nine merged with `git merge --no-ff`, most isolated first, no conflicts (no file was touched by two branches),
`compileJava` green after each.

| Order | Task | Tier | Branch | Tip | Contents |
|---|---|---|---|---|---|
| 1 | hit-trade-solver | opus | `refs/heads/worktree-wf_b028634e-099-5` | 4f63ccd | `combat/tactics/HitTradeSolver` filled in: 9 key combinations (plus jump variants) rolled forward 1..12 ticks with cooldowns, reach, hurt immunity and vanilla knockback; hazard and drop rejection; hysteresis. `TradeInput` gained `sHurtTicks`, `oHurtTicks`, `resprintTicks`. About 10 us per solve, zero allocation. |
| 2 | intent-estimator | sonnet | `refs/heads/worktree-wf_b028634e-099-9` | 28dba67 | `travel/movement/IntentEstimator`: picks the block the user is trying to land on from look, movement and reach, with a confidence. About 0.74 us per call, zero allocation. |
| 3 | cooldown-read | sonnet | `refs/heads/worktree-wf_b028634e-099-6` | 9fe423a | `OpponentModel.noteSwing/attackReadyIn/attackCharge/cooldownTicks`; `MinecraftCombatWorld.sample` fills target velocity, sprint, body yaw, hurt time, attack speed and a swing rising edge; `SwingGate` holds swings for the first 2 ticks of the target's hurt window. |
| 4 | route-follower | opus | `refs/heads/worktree-wf_b028634e-099-3` | 01e3690 | `RouteFollower` filled in, plus `RouteCorridor` (corner cutting only where the swept box is safe) and `RouteJumpPlanner` (simulated gap and shortcut jumps that land on route cells with zero fall damage), sprint hops on flats, edge guard. |
| 5 | baritone-plan | opus | `refs/heads/worktree-wf_b028634e-099-2` | 4cf350a | Plan-only Baritone routes: `PathBackend.planTo/planAway/...`, a private `RoutePlanProcess` in `BaritoneBridge` (still the only `baritone.*` importer), `BaritoneAdapter.routePlanner()`, registered in `JobsBootstrap` only when `baritoe` is loaded. |
| 6 | turn-gain-core | opus | `refs/heads/worktree-wf_b028634e-099-8` | 37083b6 | `core/input/ManualLookAssist` (`IManualLookAssist`): scales the user's own mouse yaw by 0.5..1.5 toward a requested yaw, never overshoots, capped at 450 deg/s, nothing with a still mouse. New mixin `MouseHandlerTurnGainMixin` (21 mixins now, listed in `utilitysuite.mixins.json`). |
| 7 | route-runner | sonnet | `refs/heads/worktree-wf_b028634e-099-4` | e540cb1 | `RouteRunnerCore` + `MinecraftRouteRunner` (`IRouteRunner`): pursue/flee state machine, planner beyond 12 blocks, local plan otherwise; registered in `TravelBootstrap`. |
| 8 | combat-wiring | opus | `refs/heads/worktree-wf_b028634e-099-7` | 597310c | `TradeDriver` + `CombatAssistant`: solver drives spacing within spacingMax + 2.5, swing feed into `OpponentModel`, route pursuit beyond spacingMax + 6 or after 10 ticks without line of sight, route flee on FLEE / sustain RETREAT. Settings `hit_trade`, `hit_trade_horizon`, `cooldown_read`, `route_pursuit`. |
| 9 | parkour-assist | opus | `refs/heads/worktree-wf_b028634e-099-10` | 2ea59e9 | Target-aware `JumpSearch`, ideal takeoff yaw, precision air steer, stick-the-landing sneak, `TakeoffTiming`, `AuthorshipBudget`, opt-in `JumpBuffer`, turn-gain request (never on Legit), 8 settings, `LegitPresets` knob `movement_assist.turn_gain`. |

Integration commit on top of the merges:

- `CombatAssistantBehaviourTest.theProductionSolverStubFallsBackToTheBand` assumed `HitTradeSolver` was still the
  always-invalid stub. With the real solver merged it answers inside the band, so the test was replaced by
  `theProductionSolverDrivesInsideTheBandAndHazardsStillVeto`: beyond spacingMax + 2.5 the band still closes the
  distance and the solver is not consulted; inside it the production solver drives (`tradeTicks() > 0`); hazards on
  both sides still veto strafing. No main-code change was needed.
- This note and `docs/ROADMAP.md`.

Per-task design notes are in `integration/*.notes.md` (`baritone-plan`, `route-follower`, `route-runner`,
`hit-trade-solver`, `cooldown-read`, `combat-wiring`, `turn-gain-core`, `intent-estimator`, `parkour-assist`).

## Unmerged branches

None. All nine branches are merged. The branches are kept (not deleted) and nothing was pushed.

## Tests and static checks

- `./gradlew compileJava test`: **1094 tests, 0 failures, 0 errors, 0 skipped**, 145 test classes.
- Static input-rule grep: the only hits outside `core/input/` and `mixin/` are the two existing read-only
  `inv.selected ==` comparisons (`travel/movement/FallStaging`, `ParkourBlocks`), which are allowed.
- `import baritone.` appears only in `jobs/baritone/BaritoneBridge.java`.
- No `com.utilitysuite.travel` import in `combat` main code.

## Joins that have never run together

Each task was tested against the other tasks' stubs. These joins are covered on each side by unit tests but have
not run together, in a test or in game:

- Combat spacing with the real solver and the real opponent cooldown model (step in after their swing).
- Combat pursuit/flee through the real `MinecraftRouteRunner`, `RouteFollower` and Baritone plan.
- Parkour assist with the real `IntentEstimator` target and the real turn gain.

## Known gaps and risks

- Baritone plan: if a calculation finishes between Baritone's control-manager tick and its executor tick in the
  same game tick, the executor can tick once (one tick of Baritone-driven movement). A sturdier fix is a core input
  guard while a plan is CALCULATING (`integration/baritone-plan.notes.md`).
- Turn gain and freecam/freelook: `world.EntityTurnFreecamMixin` redirects `Entity.turn` after the gain is applied,
  so a live `movement_assist` request would also scale the detached camera's yaw. The module does not clear its
  request for freecam yet (core.api has no freecam state).
- Route follower: no joint multi-jump search (jumps chain by replanning at each landing); slabs, stairs, ladders
  and water are judged unwalkable; lava, fire and magma are not marked non-standable yet (needs a voxel adapter).
- Route runner: a `RotationTarget` is allocated whenever the wanted yaw moves more than 4 degrees (bounded, not
  every tick).
- `MinecraftCombatWorld.sample` changes cannot be unit-tested; `targetSwingStarted` is only true on the first
  sample of a game tick.
- Stick-the-landing sets `in.shiftKeyDown` in `MovementInputUpdateEvent`; whether vanilla edge back-off triggers on
  the landing tick is unverified.
- The 8 new movement settings and 4 new combat settings have inline labels only (no `en_us.json` keys).
- No test yet for step-in-after-swing with the real `OpponentModel` behind `TradeDriver`.

## In-game playtest checklist

General setup: `./gradlew runClient` (Baritone loads in dev). First check the log: the mixin audit must pass with
21 mixins, including `MouseHandlerTurnGainMixin`. Use a creative superflat test world plus one survival world with
terrain. Keep Spark in `run/mods` for the profile items. Stop-All must end every item below at once.

### 1. Hit-trade solver and combat wiring (spacing dance)

Setup: Combat Assistant on, `hit_trade` on, `hit_trade_horizon` 7. Fight (a) a zombie, (b) a skeleton, (c) a second
account or a friend on the whitelisted server with a sword, then an axe.

- [ ] Inside about 5.5 blocks the assistant steps in and out instead of holding a fixed band, and it does not
      switch keys more often than every 3 ticks.
- [ ] It backs out of reach while its own attack is charging and steps in as the attack comes ready.
- [ ] When both could hit, it takes the first hit instead of backing off.
- [ ] Near a ledge, lava or a wall behind: it never steps toward the hazard; with a wall behind it strafes or
      holds and does not back into the wall.
- [ ] Sprint-reset taps, crit hops and shield break still happen and are not fought by the solver (no jump at
      HOLD_GAP, no solver jump during a crit hop).
- [ ] Turn `hit_trade` off: the old band + strafe behaviour returns exactly.
- [ ] Try horizons 6 and 8 and Legit / Balanced / Relaxed; note any difference in feel.
- [ ] Every camera turn stays smooth (no snap) while the dance runs.
- [ ] Spark: `/spark profiler start`, fight for 60 s, stop. `HitTradeSolver.solve` should be far below 1% of the
      client tick, with no allocation from `TradeDriver` or the solver.

### 2. Cooldown read

Setup: as above, `cooldown_read` on. Opponent holding a sword, axe, trident, mace, and bare hand.

- [ ] Against a player: right after their swing the assistant steps in; it stays out while their attack is charged.
      Axe holders give a visibly longer window than sword holders.
- [ ] Against mobs the swing edge registers (zombie arm swing), and nothing breaks for mobs without a swing.
- [ ] Switching target mid-fight does not carry the old target's cooldown over.
- [ ] Swings are held for the first 2 ticks after the target was hit (no swings wasted into hurt immunity).
      Compare kill time on a zombie with the previous build if possible.
- [ ] `cooldown_read` off: the opponent is treated as always ready (more cautious spacing).
- [ ] Spark: no allocation from `MinecraftCombatWorld.sample`.

### 3. Baritone plan-only routes

Setup: Baritone loaded. Trigger a plan through pursuit (item 5) with the target 20 to 60 blocks away and obstacles
between.

- [ ] While a plan is CALCULATING the player does not move, turn, break or place anything on Baritone's account.
      Watch closely on the first plan of a fresh session (the same-tick race above).
- [ ] Time to READY for 20, 40 and 60 block routes; note anything over half a second.
- [ ] Target in unloaded chunks: a partial route or a clean FAILED, never a hang past 10 seconds.
- [ ] Flee (`planAway`) gives a route that actually leads away.
- [ ] Start a Baritone job (goal console mine/goto), then trigger a plan: the planner reports unavailable and the
      job is untouched. Starting a job drops a running plan.
- [ ] Stop-All, death/respawn, dimension change and leaving the world all drop the plan, and no Baritone path
      rendering is left behind.
- [ ] Plan while walking manually: your own movement is not interrupted.
- [ ] Without Baritone installed (remove the jar from the run): no crash, pursuit uses the local plan.

### 4. Route follower

Setup: build a test course on superflat: a 30-block straight; an L corner in the open; the same corner with a wall
on the inside and with a hole on the inside; 2-, 3- and 5-block gaps; a U-shaped detour around a 3-block gap; a
1-block step up; a jump that would cause fall damage. Drive it through pursuit of a mob placed at the end.

- [ ] Straight: sprint hops in a steady rhythm, faster than plain sprinting, stopping before corners.
- [ ] The open corner is cut; the walled and holed corners are walked around, never clipped.
- [ ] 2- and 3-block gaps are jumped and landed on route cells; the 5-block gap is not attempted.
- [ ] Detour shortcut: the jump is taken instead of walking the U. Note whether it goes diagonally from the lane
      edge (known) and whether that looks wrong.
- [ ] The damaging jump is not taken. No jump is pressed at a lip without a landing; the body brakes at edges.
- [ ] The camera looks along the movement direction and turns smoothly; check corner cutting with real camera lag.
- [ ] Routes over slabs, stairs, ladders and water: expect it to stall or fall back; note what happens.
- [ ] Knock the player off the route (push or teleport): it replans instead of walking blind.

### 5. Route runner and combat pursuit / flee

Setup: `route_pursuit` on. A target that runs (a second player, or a mob lured away) on terrain with obstacles.

- [ ] Target more than about spacingMax + 6 blocks away, or out of sight for half a second: the runner takes over
      (one status line) and hands back to the melee code about 1 block earlier than it took over. No key flicker
      at the boundary.
- [ ] At hand-over and hand-back no key stays stuck and the camera does not snap; aim returns to the target after
      hand-back.
- [ ] The 12-block switch between the local plan and the Baritone plan is not visible as a stop.
- [ ] Low health (FLEE): it runs a route away instead of backpedalling. Sustain RETREAT runs only while the
      opponent keeps closing. Eating never starts a route.
- [ ] Press any movement key or move the mouse during a run: manual input wins at once and the run ends.
- [ ] Target dies, disengage, disarm, world unload: the runner stops.
- [ ] With `route_pursuit` off, or without Baritone: the old chase behaviour, no errors.

### 6. Turn gain (core)

Setup: Movement Assist on, tuning Balanced or Relaxed, `turn_gain` on, a parkour course with angled jumps. Test at
low and high mouse sensitivity, raw input on and off, a 60 fps cap and uncapped.

- [ ] With the mouse still, the view never moves. This is the most important check.
- [ ] Turning toward the right takeoff direction feels slightly faster and away slightly slower; it never carries
      the view past the ideal direction and never feels like a pull.
- [ ] On Legit the gain is off even if the setting is on.
- [ ] No gain while a screen is open, while riding, in spectator, or while any automated look is running.
- [ ] Pitch, invert mouse, cinematic camera and spyglass behave as in vanilla.
- [ ] Manual-override detection still works: moving the mouse still preempts automation.
- [ ] Freecam / freelook with a jump lined up: check whether the detached camera's yaw gets scaled (known risk).
- [ ] Stop-All and world unload clear the gain.

### 7. Intent estimator and parkour assist

Setup: Movement Assist on. Course: 1-block-wide landings at 2, 3 and 4 blocks, some diagonal, some one block up or
down, one with a wide landing area, one beside a damaging drop. Run each part with the assist off first.

- [ ] The assist helps toward the block you are actually going for; when turning past several candidates the
      target does not flicker between them.
- [ ] Jumps at 1-block targets land more often than with the assist off, and it still feels like your jump.
- [ ] Stick the landing: on a 1-block landing a brief sneak stops you walking off; it releases at once when you
      press a key; it never fires while jump is held. Check with toggle-sneak on too.
- [ ] Air steer: small late trims only; never a trim that ends over the gap.
- [ ] `AuthorshipBudget`: set the cap to 0 and confirm there are no corrections at all, including edge jumps. At
      the default 6, land ten clean unassisted jumps and confirm the help fades.
- [ ] Jump buffer (opt-in, default off): turn it on and press jump up to 3 ticks early near an edge; the jump fires
      on the last ground tick. On flat ground and in the air the jump key is untouched.
- [ ] With a still mouse and no target the behaviour matches the previous build (edge jump only).
- [ ] SafeWalk on: no assisted takeoff into a damaging fall.
- [ ] The Legit profile shows as Legit (not Custom) after this update; the autopilot PARKOUR reflex works with the
      module off.
- [ ] Slabs, fences and ladders as landings; neo and diagonal jumps: note what the estimator picks.
- [ ] Spark: `MovementAssist` tick cost and allocation during a 60 s parkour run.
