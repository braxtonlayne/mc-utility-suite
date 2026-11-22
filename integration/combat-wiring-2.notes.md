# Combat wiring 2: combo keeper, strafe phase, arena read, feints, rank dial, pearl intercept, the read

Volley 2026-11-22, task combat-wiring. Base: contracts commit bf4c67d. The units are signature-only stubs on this
branch; every one sits behind a small seam so tests script it, and with the stubs (or a setting off) the assistant
behaves as before (the old band / solver / route tests pass unchanged; `theProductionStubsChangeNothing` reruns the
band test with every feature on and the production stubs).

Code: new `combat/assistant/ProFeelLayer` (engine free; owns the unit instances, per-tick `observe()`, small decision
methods), new `combat/assistant/PearlIntercept` (engine free), call sites in `CombatAssistant`, keys in
`CombatSettings`, `TradeDriver.scoreOf`, wiring in `CombatBootstrap`. No `travel.*` or `world.*` import in combat main
code; the static input-rule grep only hits the two pre-existing read-only `inv.selected ==` reads in `travel/`.

## Seams (ProFeelLayer)

| Seam | Unit (production adapter, built once) | Test double |
|---|---|---|
| `Combo` | `ComboKeeper` (built lazily once `Services.bodySimsOrNull()` exists; `useCombo` fixes a scripted one) | `ProFeelLayerTest.ScriptedCombo` |
| `Phase` | `StrafePhaseModel` | `ScriptedPhase` |
| `Arena` (+ `query()` = `ArenaQuery`) | `ArenaField` | `ScriptedArena` |
| `Positional` | `ArenaTactics` | `ScriptedPositional` |
| `Feints` | `FeintPlanner` | `ScriptedFeints` |
| `Skill` | `SkillEstimator` | `ScriptedSkill` |
| `PearlIntercept.Landing` | `PearlLandingSim` | `PearlInterceptTest.ScriptedLanding` |
| `PearlSource` | `mc.PearlWatch` (bootstrap) | `PearlInterceptTest.ScriptedPearls` |
| `CombatVoxels` | `mc.ArenaVoxels` (bootstrap; null = arena read and pearl intercept idle) | `ProFeelLayerTest.FLAT` |

Public hooks on the assistant: `useArenaVoxels`, `usePearlSource`, `useReadState`, `proFeel()` (tests reach the seams
through it: `useCombo`, `usePhase`, `useArena`, `usePositional`, `useFeints`, `useSkill`, `usePearlLanding`).

## Settings (CombatSettings, group "Tactics", labels inline)

| Key | Default | Label |
|---|---|---|
| `combo_keeper` | on | "Keep combos going" (needs `sprint_reset` not OFF) |
| `anticipatory_aim` | on | "Aim where they strafe to" (needs `velocity_lead`) |
| `strafe_with` | on | "Strafe with them" |
| `arena_read` | on | "Read the arena" |
| `feints` | on | "Feints against players" (never against mobs, whatever the setting) |
| `rank_dial` | **off** | "Match the opponent's rank (one notch above)" (changes the shared humanizer skill) |
| `pearl_intercept` | on | "Chase ender pearls" (needs `route_pursuit` and a registered route runner) |

## Call sites in CombatAssistant

| Where | What |
|---|---|
| `onLocalDamaged` | engaged and the cause is the target: `feel.noteOpponentHit` (rank dial) |
| `onOwnHitConfirmed` | engaged and the victim is the target: `feel.noteOwnHit` (rank dial; one outcome per swing) |
| `engage` | `feel.engage(id, isPlayer)`: combo plan, feints, positional plan and pearl run end; arena eval on the first tick |
| `tickEngaged`, before the disengage radius check | `feel.pearlTick(...)`; the radius becomes `max(disengage_radius, landing distance + 8)` while a run lasts |
| `tickEngaged`, after `observeOpponent` | `feel.observe(...)`: strafe phase, trade input for the keeper (+ `step()` while active), arena scan (12 columns per tick) and every 5 ticks `evalKnock` + `ArenaTactics.assess`, snapshot `knockValue/knockFieldX/knockFieldZ/knockKind/headroom`, feints observe (players), skill feed (swing edges with in-reach, reaction to our step-in, misses after 10 ticks), rank-dial request, ReadState |
| `observeOpponent` | target health dropped: `feel.noteOwnHit` |
| `drive` 1c | `feel.pearl().active()` -> `runRoute(s, ROUTE_INTERCEPT)` (third route mode, same lease and hand-over rules) |
| `drive` 5 | crit sync off when `s.headroom <= 2` |
| `drive` 6 | terrain tactic (TacticsPlanner, incl. KNOCK_VECTOR) -> `positionalMove` (HIGH_GROUND / CEILING_DENY) -> `movement(s, true)` |
| shield break | `movement(s, false)` (no feints) |
| `tapAttack` | `feel.onSwingSent`: in reach -> pending swing for the skill read; combo keeper `begin(in, sprinting, !hazardBack)`; a plan sets `resetKind` / `sprintResetTicks` (RESET_NONE = no reset), else the fixed W-release / S-tap timing |
| `movement` | see precedence below; `feel.afterMovement` (step-in tick, `ReadState.steppingIn`) |
| `aimAtTarget` | `velocity_lead` + `anticipatory_aim`: `predictOffset` for x/z, `tvy * lead` for y, `AimSolver.solve(s, ox, oy, oz, out)`; the stub answers the exact old velocity lead |
| `chooseTactic` | `utility.highGround` / `utility.ceilingDeny` from the last arena assessment (0 when off or idle) |
| `runRoute` | ROUTE_INTERCEPT: `pursue(lease, landX, landY, landZ, 1.5, true)`; ARRIVED ends the intercept without the retry wait; note on the hand-over only |
| `noteTactic` | KNOCK_VECTOR note on the transition |
| `disengage` (every reason) | `feel.disengage(snap)`: plans end, arena cleared and the snapshot arena fields reset, ReadState cleared, rank-dial request dropped |
| `setEnabled(false)`, `onStopAll` | `feel.clearSkillRequest()` (also through disengageNow) |
| `onWorldUnload` | `feel.worldUnload(snap)` (also forgets the strafe phases and skill estimates) |

`TradeDriver.scoreOf(combo)`: the solver's score of one combination this tick, for the strafe-with tie-break.

## Order of precedence (engaged tick)

1. **Sustain** (totem click, eating, the low-health retreat / route flight). Nothing below runs.
2. **Flee** (broad tactic FLEE: route flight, else backpedal).
3. **Pearl intercept** (route mode 3) while a run is active. Placed ahead of normal pursuit because it is the more
   specific chase: after the target teleports it is far away, and the run to the landing must not be replaced by a
   straight pursuit of the old position. It hands over to normal pursuit when the target is inside the disengage
   radius again (the "normal pursuit range": from there the old pursuit handles it), 60 ticks after the pearl vanished,
   or when the runner reports ARRIVED.
4. **Pursuit route** (far away or out of sight).
5. Wind charge, **aim** (anticipatory), shield break (keys from `movement` without feints), swing / crit sync.
6. **Tactics**: TacticsPlanner terrain tactic (HOLD_GAP, EDGE_KNOCK, KNOCK_VECTOR), then the positional arena tactic
   (HIGH_GROUND / CEILING_DENY via `ArenaTactics.plan`; hazards veto; the step-up jump is a 1-tick tap). Aim and swings
   carry on underneath.
7. `movement()`:
   1. **Combo keeper** while a plan runs (its forward / strafe / sprint; the solver is not consulted).
   2. else the **solver** (inside spacing max + 2.5; strafe-with only on a tie or better score for the with-direction),
   3. else the **band** (StrafePlanner gets `mirrorOf(lateralSignAt(3))` and the confidence).
   4. **Feints** on top of 2 or 3 (players only; not during the keeper, shield break, eating or a route run).
   5. **Hazard veto** over everything above (never forward into hazardFront, back into hazardBack, strafe into a side
      hazard), sprint only with forward.
   6. **Sprint-reset taps** last (the keeper's kind and length when it planned, else the setting's).

## Allocation

`ProFeelAllocationTest` (50k engaged ticks under 1 KB): production stubs with every feature on and a swing every tick,
busy scripted units on the solver path (strafe-with ties, feints every tick, arena eval, rank dial requests), an active
combo plan, and a pearl intercept run. Each measures a second 50k window after a first one: with the scripted tests in
the same JVM the seam call sites turn polymorphic and a one-time deopt in the window can rematerialize a few objects
(seen once as 4,984 bytes, never per tick). The existing `engagedTickWithTheProductionSolverAllocatesNothing` got the
same warm window for that reason (its assertion is unchanged).

## Tests

- `ProFeelLayerTest` (16): keeper gating (reach, setting, sprint reset OFF, hazard behind) and end on target change /
  disengage; strafe-with mirror, confidence gate, setting off; production phase stub = velocity lead; arena idle
  without voxels, recentre on the midpoint, 12-column budget, eval every 5 ticks, snapshot fields, off/disengage clear;
  positional needs voxels; feints never against a mob (not observed, not planned, no random drawn), overrides against
  a player, setting off; rank dial off by default, sample gate, once per 20 ticks, one notch above and clamped, cleared
  on disengage / setting off; skill feed (swing in reach, reaction, hit, miss, hit taken); ReadState charge / readyIn /
  tilt / steppingIn / clear, cooldown_read off.
- `PearlInterceptTest` (8): start, near landing / failed sim / no voxels never start, retarget, the thrower near us
  during the flight, stop in range after the pearl vanished, stop 60 ticks after, other throwers, production stub.
- `CombatRouteHandoverTest` (+5): run to the landing with stop range 1.5, retarget, no disengage on the teleport,
  hand-over to normal pursuit, end after 60 ticks then the normal radius applies, no intercept without route_pursuit or
  with the setting off, near landing, end on disengage. All old hand-over tests unchanged and green.
- `CombatAssistantBehaviourTest` (+12): keeper ahead of the solver after its reset, hazard veto, back to the solver;
  keeper S-tap replaces the setting; keeper off / sprint reset OFF; anticipatory aim yaw and off; strafe-with tie only;
  headroom 2 suppresses crit hops and solver hops; arena scans only with voxels and the setting; feints override the
  band against a player, hazards veto, note on transition, off; never against a mob; rank dial request / clear on
  disengage, Stop-All, disable, world unload; the read published and cleared; production stubs change nothing.
- Full suite: 1141 tests green.

## Not covered by tests (needs the real TacticUtility scoring)

- The positional drive inside the assistant (`positionalMove`) only runs when `TacticUtility` picks HIGH_GROUND or
  CEILING_DENY; the stub never scores them, so this branch is covered at the layer level only
  (`positionalPlanNeedsVoxels`). Once the arena-tactics task scores them, add an assistant-level test.

## Left for the in-game playtest

- [ ] Combo keeper feel against a real player and against mobs: are the reset kinds and step timings human, does it
      keep a combo or overrun the target, conflict with crit hops and the solver hand-back.
- [ ] Anticipatory aim: does the lead follow real strafe reversals without visible snapping (all through the smoother at
      <= 450 deg/s), and does strafing with the opponent feel natural or obviously mirrored.
- [ ] Arena read: knock vectoring toward lava / drops / walls in a real arena, high ground and ceiling deny once
      TacticUtility scores them; no crit hops under 2-block roofs; Spark cost of the 12-column scan.
- [ ] Feints against a player (pattern breaks, bait retreats, punish timing); confirm none against mobs in a mob fight.
- [ ] Rank dial (off by default): request changes visible aim / timing skill, never above a fixed skill set by the user,
      dropped after the fight.
- [ ] Precedence conflicts in a live fight: keeper vs feints vs solver vs band, terrain tactic vs positional tactic,
      sustain retreat or flee during a pearl chase.
- [ ] Pearl chase on real terrain: start on a thrown pearl, route to the landing, no disengage on the teleport, clean
      hand-back to melee / normal pursuit, camera turns under the cap.
- [ ] "The Read" ring shows up over the target when the `the_read` module is enabled (and nothing in Clean-Screen or
      when idle).
- [ ] Spark profile of an engaged tick with every feature on (solver + keeper + arena scan + pearl sim).
