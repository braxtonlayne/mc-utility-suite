# Next-layer volley, 2026-11-22

Base: bf4c67d (contracts: ArenaQuery, plus 50995db contracts for the combo, anticipation, arena and visuals volley).
Nine tasks were built in parallel worktrees and merged into `main` on 2026-11-22 around 06:20 EST.

**Nothing in this run was playtested in a game client.** No `runClient`, `runPlaytest` or `runUiPreview` was run.
Everything below is compiled and unit-tested only. No mixin was added in this run (still 21).

## What landed on main

All nine merged with `git merge --no-ff`, most isolated first, with no textual conflicts; `compileJava` was green
after each merge.

| Order | Task | Tier | Branch | Tip | Contents |
|---|---|---|---|---|---|
| 1 | combo-keeper | opus | `refs/heads/worktree-wf_c9e7e817-887-2` | 1414167 | `combat/tactics/ComboKeeper`: after a hit, rolls 36 candidates (reset none/W1/W2/W3/S1/S2 x strafe x hold) on the predicted knockback arc and plays the keys for the earliest full-charge hit in reach. Rejects hazards and falls; ends on a deviation over 0.6 blocks. About 55-65 us per `begin`, zero allocation. |
| 2 | opponent-reads | opus | `refs/heads/worktree-wf_c9e7e817-887-6` | 1368806 | `FeintPlanner` (aim lead, pre-aim and bait-rate reads; BREAK_PATTERN, BAIT_RETREAT, PUNISH state machine with a randomised 40-80 tick cooldown) and `SkillEstimator` (accuracy, combo, reaction, evasion; UNKNOWN until 8 samples; at most 0.02 per event). |
| 3 | pearl-intercept | sonnet | `refs/heads/worktree-wf_c9e7e817-887-7` | 21f6e74 | `PearlLandingSim` (vanilla pearl motion over voxels; unknown, lava and void give no landing) and `PearlWatch` (16-slot tracking of thrown pearls by owner). About 0.8 us per simulation. |
| 4 | arena-field | opus | `refs/heads/worktree-wf_c9e7e817-887-4` | a7e6b52 | `ArenaField`: 33x33 column grid with a budgeted spiral scan, `evalKnock` over 16 directions, `findHighGround`, `findLowCeiling`; `ArenaVoxels` maps block states. Zero allocation; UNKNOWN is never treated as safe. |
| 5 | anticipatory-aim | opus | `refs/heads/worktree-wf_c9e7e817-887-3` | 6e0fe28 | `StrafePhaseModel` (strafe period and phase per opponent, confidence from regularity), `predictOffset` blended with the plain lead, `StrafePlanner` 6-argument update (strafe with the opponent), `AimSolver.neededYawRateDegPerTick`. |
| 6 | arena-tactics | sonnet | `refs/heads/worktree-wf_c9e7e817-887-5` | 8e65e4e | `TacticUtility` scores HIGH_GROUND and CEILING_DENY; `ArenaTactics` (assess, steer to the spot, hold, 100-tick give-up); `TacticsPlanner.KNOCK_VECTOR` (shares the EDGE_KNOCK circling; a wall pin walks in without sprint). |
| 7 | takeoff-shaping | opus | `refs/heads/worktree-wf_c9e7e817-887-9` | c9af605 | `travel/movement/TakeoffShaper`: when the plain run-up misses the intent target, the cheapest of sprint timing, a strafe key for the first ground ticks, or a turn-gain ideal yaw (never on Legit). Setting `takeoff_shaping` (default on) on Movement Assist. About 10-12 us per decide. |
| 8 | visuals | sonnet | `refs/heads/worktree-wf_c9e7e817-887-8` | 3926fa7 | `ReadRingGeometry` + `ReadRingModule` (`the_read`), `GhostLine` + `GhostLineModule` (`ghost_line`, off by default, registered in `TravelBootstrap`), read-only route accessors on the route runner. |
| 9 | combat-wiring | opus | `refs/heads/worktree-wf_c9e7e817-887-10` | a3b6856 | `ProFeelLayer` (one facade over the units, behind seams) and `PearlIntercept`, call sites in `CombatAssistant`, registration in `CombatBootstrap` (ArenaVoxels, PearlWatch, ReadState, `the_read` ring). Seven settings in group Tactics: `combo_keeper`, `anticipatory_aim`, `strafe_with`, `arena_read`, `feints`, `pearl_intercept` (on), `rank_dial` (off). |

Integration commit on top of the merges (test changes only, no main-code change):

- `PearlInterceptTest.theProductionLandingStubNeverStarts` assumed `PearlLandingSim` was still the stub. With the
  real sim merged the scripted pearl lands far away and the intercept starts, so the test is now
  `theProductionLandingSimStartsOnAFarLanding`.
- `ProFeelAllocationTest.busyScriptedUnitsOnTheSolverPathAllocateNothing` scripted a knock value of 0.8. With the
  real `TacticsPlanner` merged that selects KNOCK_VECTOR, which drives the movement itself, so the solver path (and
  the feints on top of it) never ran. The test now scripts 0.3 (below `KNOCK_VECTOR_MIN_VALUE`) and stays on the
  solver path. Two tests were added for the paths that only exist after the merge:
  `theKnockVectorDriveAllocatesNothing` and `thePositionalDriveAllocatesNothing` (HIGH_GROUND chosen by the real
  `TacticUtility` and driven through `positionalMove`). This closes the wiring task's "no assistant-level test for
  the positional drive" gap for allocation and precedence; the steering itself is tested in `ArenaTacticsTest`.
- This note and `docs/ROADMAP.md`.

Per-task design notes: `integration/combo-keeper.notes.md`, `opponent-reads.notes.md`, `pearl-intercept.notes.md`,
`arena-field.notes.md`, `anticipatory-aim.notes.md`, `arena-tactics.notes.md`, `takeoff-shaping.notes.md`,
`visuals.notes.md`, `combat-wiring-2.notes.md`.

## Unmerged branches

None. All nine branches are merged. The branches and their worktrees still exist (nothing was deleted or pushed):
`refs/heads/worktree-wf_c9e7e817-887-2` through `refs/heads/worktree-wf_c9e7e817-887-10`.

## Checks

- `./gradlew compileJava test`: **1257 tests, 0 failures, 0 errors, 0 skipped** (1094 after the pro-feel volley).
- Static input-rule grep: only the two existing read-only `inv.selected` reads (`travel/movement/FallStaging.java`,
  `ParkourBlocks.java`). Nothing new.
- No `travel` or `world` imports in `combat` main code.

## Things to know before the playtest

1. **Sprint start outside the core.** Takeoff shaping engages sprint with `LocalPlayer.setSprinting(true)` inside
   `MovementInputUpdateEvent` (`MovementAssist.java:292`). It is not on the static grep list and touches no key
   mapping, but it is a player state change outside `core.input`. A leased SPRINT key was not usable because the
   user's held W preempts MOVEMENT leases. A core "fill-only sprint start" call would be the clean home; left as is
   and listed as open in the roadmap.
2. **Pearl intercept runs ahead of normal pursuit** in the precedence order, so a straight chase does not take over
   the run to the landing. It hands back once the target is inside the disengage radius again.
3. **Knock kinds.** The contract has no kind for damaging or slow floors: HARM reports as `KNOCK_LAVA` (value 0.35)
   and SLOW as `KNOCK_COBWEB` (0.15). Tactics tell them apart by `floorKind`.
4. **Slabs and fences read as full SOLID blocks** in the arena read.
5. **Takeoff shaping does not help the 4-block gap from a 2-block run-up** as first described: in the physics model
   the plain sprint jump already lands it, and from 1 or 3 blocks no keys-only shape lands it. A 45-degree strafe
   gains about 0.02 block; the shaper's real lever is the takeoff tick (about 0.17 block).
6. **`StrafePhaseModel` uses one period for both sides**; uneven holds (right 10, left 14) lower its confidence.
7. One `CombatAssistantBehaviourTest` allocation test got a warm-up window (a one-time JIT recompile, not a per-tick
   allocation); its threshold is unchanged.

## In-game playtest checklist

General setup: `./gradlew runClient`, a flat creative test world plus a small arena you build once (below). For
player tests use a second account or a friend on the whitelisted server; mobs do for the rest. Enable Combat
Assistant; the new settings are in its **Tactics** group. Test one feature at a time with the others off, then all
on. Watch the status line for the notes quoted below.

### 0. Startup

- [ ] The client starts with the mixin audit clean (21 mixins) and no errors in the log about `the_read`,
      `ghost_line`, `ArenaVoxels` or `PearlWatch`.
- [ ] Nothing new renders while idle; Clean-Screen hides the ring and the ghost line.

### 1. Combo keeper (`combo_keeper`, needs Sprint reset not OFF)

Setup: flat ground, a zombie, then a player. Sword, sprint-hit it.
- [ ] After a sprint hit the follow-up hit lands as the target comes down, about 12 ticks later, still sprinting.
- [ ] The reset looks like a W release or a short S-tap, not a stutter. No S-tap with a drop behind you.
- [ ] Against a wall behind the target, the reset is shorter and the hits come faster.
- [ ] Knock the target off a ledge: you do not follow it down.
- [ ] Get shoved sideways mid-combo (second mob): the plan ends and normal spacing takes over.
- [ ] On the server with real ping: does sprint knockback still apply on every hit? Note the ping.
- [ ] Set Sprint reset to OFF: the keeper does nothing.

### 2. Anticipatory aim and strafe-with (`anticipatory_aim`, `strafe_with`)

Setup: a player who strafes left-right in a steady rhythm at 3 blocks, then one who strafes at random, then one
who circles.
- [ ] Steady rhythm: after about three reversals the crosshair stays on the body through the reversal instead of
      trailing it. Compare crosshair-on-target with the setting off.
- [ ] Random strafing and a standing target: aim looks the same as with the setting off (plain lead).
- [ ] Circle-strafer: no odd lead; behaviour falls back to plain lead.
- [ ] Camera never snaps; turns stay smooth (450 deg/s cap) in every case.
- [ ] `strafe_with` on: you tend to move the same way as the opponent, but not as an obvious mirror. Hazards at
      the side still stop the strafe.

### 3. Arena read (`arena_read`)

Setup arena: a 15x15 floor with (a) a lava pool on one side, (b) a 4-deep pit, (c) a wall, (d) a 2-block-high
stair mound, (e) a strip with a roof 2 blocks above the floor.
- [ ] Fight a mob near the lava: the note "Knock vector: lining up to knock ..." appears and you circle so your
      hits send it toward the lava. Same for the pit. EDGE_KNOCK still wins right at an edge.
- [ ] Near the wall only: you walk in to about 2.8 blocks without sprinting (wall pin).
- [ ] Near the mound against a player at about 4+ blocks: "Taking the high ground over ..." and you step up and
      fight from there; you do not give up free hits walking there. If you are clearly winning within 3 blocks you
      stay and fight.
- [ ] Against a player who crit-hops, near the roofed strip: "Backing under a low ceiling against ..." and no hops
      of your own under the roof.
- [ ] You never step into lava, fire, cactus or off a drop while repositioning.
- [ ] Fight at a chunk border and at the edge of render distance: no odd knock choices from unloaded columns.
- [ ] Slabs and fences in the arena: note anything strange (they read as full blocks).
- [ ] Spark (`/spark profiler start`, fight 30 s, stop): the arena scan is well below 1% of the client tick.

### 4. Feints (`feints`)

Setup: a player at 3 blocks, both with swords; then a zombie.
- [ ] Against the player: every few seconds, not on a fixed beat, the note "Feint: breaking the strafe pattern";
      sometimes "Feint: backing off to draw a sprint-in" followed by "Feint: stepping in on the bait" when the
      player sprints in.
- [ ] Do the feints look like a player's, or like a glitch? Note which.
- [ ] No bait retreat with a drop or lava behind you; a bait stops at once when one appears.
- [ ] Against the zombie: no feint notes at all over a full fight.
- [ ] No feints while eating, during a shield break, during a combo, or during a route chase.

### 5. Rank dial (`rank_dial`, off by default)

Setup: turn it on; fight a weak player, then a strong one.
- [ ] After a few exchanges your aim and timing settle about one notch above the opponent: not crushing the weak
      one, not losing to the strong one by default. Say whether it feels too easy or too hard.
- [ ] Never above a fixed skill you set yourself.
- [ ] The request is dropped on disengage, Stop-All, module off and leaving the world (skill returns to your
      setting).
- [ ] Against mobs it stays quiet.

### 6. Pearl intercept (`pearl_intercept`, needs `route_pursuit`)

Setup: a player with ender pearls, open terrain with some obstacles; then a pearl thrown over lava and one into
the void or unloaded chunks.
- [ ] The player pearls 20+ blocks away mid-fight: you turn (smoothly) and run to the landing while the pearl is
      still in the air, and arrive close to where they appear.
- [ ] You do not disengage when they teleport; melee resumes on arrival.
- [ ] A short pearl (landing within about spacing max + 2): nothing special happens.
- [ ] Pearl into lava, the void or unloaded chunks: no run.
- [ ] Another player's pearl near the target is ignored.
- [ ] You take over the keys mid-run: your input wins at once.
- [ ] Compare the predicted landing with the real teleport spot (note the error in blocks).

### 7. The Read ring (`the_read` module)

Setup: enable the module, fight a player.
- [ ] A ring at the target's feet fills as their attack charges and empties when they swing.
- [ ] With "Show predicted strafe" on, it tilts toward the side they are about to move to.
- [ ] Radius and opacity settings work. Nothing draws when not engaged or in Clean-Screen.
- [ ] Looks right with Sodium and with Iris shaders on; no FPS drop (Spark or F3).

### 8. Ghost line (`ghost_line` module, off by default)

Setup: enable it; follow a route (a combat chase or pearl run) and do some Movement Assist jumps.
- [ ] A faint line shows the route ahead for about the lead time; an arc shows the coming jump and its landing.
- [ ] The first 1.5 blocks in front of you are hidden. "Show trail" off hides the route but keeps the jump arc.
- [ ] Nothing draws when no route or jump is active.

### 9. Takeoff shaping (Movement Assist, `takeoff_shaping`, default on)

Setup: 3-block gaps onto a 1-block pillar, pillar offset 1 block left or right of your line, run-ups of 1 to 4
blocks; tuning on Legit, then one level up. Keystrokes overlay on.
- [ ] Off-axis pillar from a short run-up: you land it with the setting on and miss with it off.
- [ ] Walking run-up: sprint starts at the right moment and you land it.
- [ ] When the plain jump would land anyway, nothing extra happens.
- [ ] It still feels like your jump. Note whether the strafe key and the early sprint show on the keystrokes
      overlay and whether that bothers you.
- [ ] On Legit: no yaw help at all. With a still mouse: the camera never moves.
- [ ] You hold a strafe key yourself: it is never overridden. SafeWalk on: no shaping into a damaging jump.
- [ ] Toggle-sprint and low hunger (6 or less): no stuck sprint, no sprint when vanilla would not allow it.
- [ ] Works together with the jump buffer and stick-the-landing.

### 10. Everything on

- [ ] A full fight against a player with all seven Tactics settings on: no fighting over the keys between the
      combo keeper, feints, the solver, knock vector and the positional tactics (watch for stutter steps).
- [ ] Flee or retreat-heal triggers during a pearl chase: the retreat wins.
- [ ] Spark profile of an engaged tick with everything on.
