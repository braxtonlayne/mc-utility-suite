# baritone-plan: plan-only Baritone ("Baritone plans, we drive")

Branch `worktree-wf_b028634e-099-2`, from the contracts commit 68af800.

## What landed

- `PathBackend` gained plan-only calls with Baritone-free defaults: `planTo`, `planAway`, `planState`, `planSerial`,
  `copyPlannedPath(long[])`, `cancelPlan`.
- `BaritoneBridge` (still the only class that imports `baritone.*`) implements them with a private
  `RoutePlanProcess implements IBaritoneProcess`, registered lazily with `IPathingControlManager.registerProcess` on the
  first plan request. Goals: `GoalBlock` (range 0), `GoalNear` (range > 0), `GoalRunAway(distance, pos)` for
  `planAway`. The route is copied once, when it arrives, into a reused `long[]` (index loop over `positions()`, packed
  with `BlockPos.asLong(x, y, z)`; no `BlockPos` allocated). `copyPlannedPath` is a `System.arraycopy`.
- `BaritoneAdapter.routePlanner()` is the `IRoutePlanner` view:
  - `isAvailable()` is false with no backend, while the adapter has any command (running or paused, `mode != NONE`),
    while Baritone mines, builds or runs its custom-goal process, and while Baritone executes a path that is not the
    plan's own momentary executor.
  - `planTo`/`planAway` refuse (return false) when unavailable. A request while a plan runs replaces it.
  - `cancelPlan()` only calls `backend.cancelPlan()`, never `backend.cancel()`.
  - Stop-All (`cancelAll`) now also drops the plan. Starting any adapter job drops the plan as well (the job wins).
- `JobsBootstrap` registers it with `Services.registerRoutePlanner` only when `ModList.isLoaded("baritoe")`, and drops
  the plan on `ClientPlayerNetworkEvent.Clone` (respawn/dimension change), `LoggingOut` and client `LevelEvent.Unload`.
- `configure()` look settings are unchanged.
- Tests: `RoutePlannerTest` (13 cases, fake backend through the package-private seam): NONE → CALCULATING →
  READY/FAILED, one serial bump per new route, copy truncation, unavailable during a running/paused job and when
  Baritone drives on its own, cancelPlan/Stop-All clear the plan without cancelling a job, replacement, job start drops
  the plan, and an `AllocationProbe` test for `isAvailable/state/planSerial/copyPath` polling (< 4 KB over 100k calls).
  `LeasedNavigatorRunTest` unchanged and green.

## Verified by bytecode (javap -c -p on libs/baritone-api-neoforge-1.11.3.jar)

Implementation classes are obfuscated. Mapping found via `implements`: `baritone.fm` = PathingControlManager,
`baritone.fn` = its tick listener, `baritone.i` = PathingBehavior, `baritone.ea` = CustomGoalProcess,
`baritone.dm` = PathExecutor, `baritone.bw`/`bv` = path finder, `baritone.fo` = switch map for PathingCommandType.

1. `IBaritoneProcess`: `isActive`, `onTick(boolean calcFailed, boolean safeToCancel)`, `isTemporary`,
   `onLostControl`, `displayName0` abstract; `priority()` default `-1.0` (`DEFAULT_PRIORITY`).
2. Control manager pre-tick (`fm.b()`): active processes are sorted by priority, descending; the first that returns
   non-`DEFER` is in control; if it is not temporary, every later (lower-priority) process gets `onLostControl`. An
   active process returning `null` throws. `calcFailed` is passed only to the process that was in control last tick.
3. Command handling in `fm.b()`:
   - `SET_GOAL_AND_PAUSE`: calls the behaviour's internal set-goal-and-path (`i.a(PathingCommand)`), then sets the
     pause-request flag (falls through into the `REQUEST_PAUSE` case).
   - `REQUEST_PAUSE`: sets the pause-request flag only.
   - `CANCEL_AND_SET_GOAL`: sets the goal, then the internal cancel `i.b()` (posts CANCELED, cancels the in-progress
     calculation, drops current and next executor, clears Baritone's keys if an executor existed).
   - `SET_GOAL_AND_PATH`: internal set-goal-and-path when the goal is non-null.
   - Before the switch: when the process in control changes and the previous one was not temporary (and the command
     is not `REQUEST_PAUSE`), the internal cancel runs.
   - No process active: internal cancel and goal = null.
4. Internal set-goal-and-path (`i.a(PathingCommand)`): sets the goal; returns without calculating when the player is
   already in the goal, when an executor exists, or when a calculation is already in progress; otherwise starts a
   calculation on Baritone's executor thread. So `SET_GOAL_AND_PAUSE` DOES start a calculation while paused.
5. Calculation callback (off-thread, under the path-plan lock): if no executor exists and the result has a path whose
   positions contain the player's current path start, it becomes the current executor (`CALC_FINISHED_NOW_EXECUTING`);
   otherwise it is discarded silently ("orphan path segment"), or `CALC_FAILED` is posted when there is no path (not for
   cancellation/exception). The in-progress reference is cleared at the end.
6. Behaviour tick (`i.onTick`, IN phase): runs the control manager first, then: if pause was requested AND the
   `safeToCancel` value recorded at the last executor tick is true, the executor is not ticked this tick (keys cleared
   on the first paused tick). Otherwise the executor ticks (under the path-plan lock). The recorded `safeToCancel`
   field is only written while an executor ticks; its initial value is false.
7. `IPathingBehavior.getPath()` = `getCurrent().getPath()`; `getInProgress()` is the running path finder;
   `isPathing()` = has an executor and not paused this tick. `onPlayerUpdate` only toggles the auto-jump option while an
   executor exists. `cancelEverything()` cancels and calls `onLostControl` on every process (it throws if a
   non-temporary process stays active, so `onLostControl` must make the process inactive).
8. `GoalRunAway(double, BlockPos...)`, `GoalNear(BlockPos, int)`, `GoalBlock(int,int,int)`, `GoalInverted(Goal)`,
   `PathingCommand(Goal, PathingCommandType)`, `IPathExecutor.getPath()`, `IPath.positions()` exist with these
   signatures.

## Chosen mechanism

`RoutePlanProcess` (priority -1000, not temporary):

1. REQUESTED: if a calculation or executor is still there (a replaced plan), return `CANCEL_AND_SET_GOAL` until it
   clears; then return `SET_GOAL_AND_PAUSE(goal)` → calculation starts, pause flag raised.
2. CALCULATING: keep returning `SET_GOAL_AND_PAUSE` (keeps the pause flag up; restarts the calculation if Baritone
   discarded an orphan result, capped at 8 restarts). As soon as `getCurrent()` is non-null (the calculation produced
   the executor), copy `getPath().positions()` and return `CANCEL_AND_SET_GOAL(null)`. That runs in the control
   manager tick, which precedes the executor tick of the same game tick, so the executor is dropped before it runs.
   `calcFailed` or 200 ticks → FAILED. Already inside the goal (Baritone does not calculate) → READY with the feet
   position as a one-node route.
3. Cancel while calculating → one more tick returning `CANCEL_AND_SET_GOAL(null)`. `onLostControl` (a job or
   cancel-everything took over) → NONE.

The route is Baritone's first segment, so it can be partial (ends at the best node when the calculation timed out),
which matches the `IRoutePlanner` contract.

## Assumptions and residual risk (not verifiable by bytecode alone)

- **One-tick race.** The calculation can finish in the short window between the control-manager tick and the executor
  tick of the same game tick. Then the executor ticks once, unless the pause flag applies, which needs the recorded
  `safeToCancel` to be true. That value starts false in a fresh session and is only updated by an executor tick. So the
  worst case is one executor tick (one tick of Baritone's keys and a look target) before the plan drops it on the
  next tick. The core's foreign rotation guard may not catch that turn. The adapter turns the guard on when it sees `isPathing()`
  at its own client-tick post, which can be one tick late. Playtest whether this is ever visible. Fallback if it is: hold a MOVEMENT/CAMERA lease while a plan
  is CALCULATING, so the core input guard owns the keys (needs a core change; out of scope here).
- A cancelled calculation is assumed to return no path (`CANCELLATION` type), so it never creates an executor. If it
  did, the REQUESTED/CANCELLING ticks cancel it again.
- If the player moves off the calculation's start, Baritone discards the result or cancels the calculation itself. The
  plan then restarts from the new position, up to 8 times, before FAILED. A fast-moving player may therefore see
  FAILED or slow plans.
- Path rendering: while calculating, Baritone draws its in-progress path and goal; after READY, nothing (the executor
  and goal are cleared).
- `LevelEvent.Unload` firing for the client level on dimension change is assumed; `ClientPlayerNetworkEvent.Clone`
  covers respawn and dimension change in any case.

## Fallback mechanism (if the playtest shows movement)

`SET_GOAL_AND_PATH` with an immediate `CANCEL_AND_SET_GOAL` has the same race, so it is no better. The other option is
reading `getInProgress().bestPathSoFar()` while calculating and cancelling once the finder ends. That still lets the
final result become an executor, so the drop-before-tick step stays the same. The real fallback is the lease idea above:
keep the core's input guard engaged during CALCULATING.

## Playtest list (in game, Baritone installed)

1. Baritone computes a route and never moves, turns, breaks or places during a plan. Watch for a one-tick twitch at
   READY, especially in the first plan of a session.
2. Time to READY for 20, 40 and 60 block routes (flat, hilly, cave).
3. Partial routes toward unloaded chunks: READY with a route that ends early. Repeated plans extend it.
4. `planAway` (GoalRunAway) results: direction, length, whether it ends at least `distance` away.
5. A running mine/goto/clear job is untouched by plan requests: `planTo` returns false and the job keeps going.
6. Baritone's path rendering while plan-only (in-progress path and goal visible while CALCULATING, nothing after).
7. Stop-All, respawn and dimension change while CALCULATING: state NONE, nothing executes afterwards.
8. Planning while the player walks (orphan discards): time to READY and FAILED rate.
