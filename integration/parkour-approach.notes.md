# parkour-approach: approach and takeoff control (volley 2026-11-22)

Branch from main at 7b7d078. Files: `travel/movement/ApproachController.java` (new), `MovementAssistBrain.java`,
`FlightController.java`; tests `ApproachControlTest`, `ApproachControlAllocationTest` (new).

## What changed

- **ApproachController** (engine-free, preallocated, zero allocation). Runs on ground ticks while the commitment is
  ARMED in GUIDE / CARRY (never NUDGE) with a locked, safe target and W held. It anchors on the user's jump press, or
  (edge jump on) when the edge is within 8 ground ticks, and walks a sorted family of takeoff plans with a cursor
  (sorted by the tick each must start on, then by cost), each rolled in the exact-shape replica and verified with the
  FlightController arrival family:
  1. the user's own press / the default edge tick as is (cost 0: nothing is written);
  2. another takeoff tick: the user's press held back (at most `MAX_WITHHOLD_TICKS` = 4, only while the later takeoff
     is still supported; with no plan yet the press is held only while candidates remain and the body is still
     supported next tick, otherwise it goes through as pressed), or the assist's jump moved within the window;
  3. ease W: forward 0 for 1-4 ticks up to and including the takeoff (vanilla ends the sprint on that tick, no
     sprint-jump boost; sprint comes back with W because the sprint key is still held, also in the air);
  4. walking run-up (sprint withheld, W kept) for 2/4/6/9 ticks, only when `sprintWithholdAvailable`.
  The chosen plan is re-verified from the real body every tick; keys / yaw / unexpected body state restart the scan.
  Letting go of W resets everything at once (tested: no jump, no key writes).
- **Plan region**: flight and approach plans now land on the target's own top when the target is narrow
  (`planRegion`), not on the merged region that also holds neighbouring tops at the same height. Without it, a pane
  level with the run-up counted a landing back on the platform as a landing on the pane.
- **FlightController**: a "brake from now" candidate (the continuation of a brake that already started; the family
  anchored on `n` only offered later starts once the first had begun), an early exit when a roll falls past the top's
  height beside it (it can never land there), `minBrakeTick` (the level's reaction delay when the approach verifies a
  takeoff) and `simCap` / `truncated()` (hard budget for the approach).
- **Target freeze** (design 9.6): from TAKEOFF through SETTLE the locked target ignores `setIntentTarget`, except one
  trim before the apex when the estimator's pick changes (look sweep, or forward released and a nearer pick). RELEASE
  is not frozen, so the next jump's target is free during the hand-back. `continuing()` (the owner's going-on rule)
  reads the raw pick, so chains still flow (MovementAssistBrainTest.chainsSingleBlocksOverOneGapsWithoutStopping).
- **Co-steer outputs**: `wantsCoSteer()/coSteerYaw()` set only when `coSteerAllowed`: while ARMED from the ideal
  takeoff yaw, in a committed flight toward the target centre when the keys at the current yaw do not land, and from a
  technique script's yaw. NATURAL clamp: within 30 degrees of the horizontal velocity direction (`freeLook` lifts it).
- **Technique scripts**: when ARMED, the approach family is exhausted and the budget has room for
  `TechniqueSource.MAX_SIM_TICKS_PER_CALL`, the brain asks `techniques.plan(...)`, copies the script and replays it
  (keys, jump, sprint, yaw to co-steer); in the air the keys go through the commitment's resolve/limit rules. Dropped
  on W released, any classifier override, leaving the predicted line, the end of the script or the commitment ending.
  Scripts with `usesYaw` are skipped without co-steer. The source's `lastSimTicks()` counts into the commit budget.
- Sprint intent: the brain keeps "the user means to sprint" for up to 10 ticks after the sprint went off while an
  approach / commitment / script runs (the module passes `isSprinting()`, which an eased tick turns off).
- SafeWalk: the assist's own takeoff is only planned onto a top no more than 2.5 blocks below.
- `approachControl` (public boolean, default on) turns the controller off.

## Measured budget

`lastCommitSimTicks()` max over the whole ApproachControlTest matrix: **1121** (approach hard cap 1120 + the shadow
tick; the hold's checks fit the rest). `MAX_COMMIT_SIM_TICKS` is unchanged (1200). Typical ticks: a re-verify of the
chosen plan costs 55-130 replica ticks.

## Wiring the integrator must do (MovementAssist, module task)

- `brain.sprintOff()` -> `SharedGrant.withholdSprint()` for that tick; set `brain.sprintWithholdAvailable` every tick
  when the grant works. Without it the controller uses forward easing only (tests pass both ways).
- `brain.coSteerAllowed`, `brain.coSteerMaxDegPerTick`, and `brain.freeLook` (look style FREE) from settings / legit
  level; feed `wantsCoSteer()` / `coSteerYaw()` / `coSteerTotalDeg()` to the core `YawCoSteer`.
- `brain.setTechniques(...)` once the technique planner exists.
- Already wired: `forward()` 0 on the ground (the existing `in.forwardImpulse` write also ends the sprint in vanilla),
  `withholdJump()`, `jump()`.
- Optional setting: `approach_control` (bool, default true) -> `brain.approachControl`. Lang keys if added:
  `utilitysuite.movement_assist.approach_control` = "Approach control",
  `utilitysuite.movement_assist.approach_control.tooltip` = "Co-pilot and Takeover ease the run-up and pick the
  takeoff tick so a near, narrow landing can be stopped on".

## Design notes / deviations

- Design 4 table says GUIDE withholds nothing on the ground in ARMED; this volley's brief asks GUIDE to ease W too
  (W held through the run-up is the stale key). Owner decision to confirm.
- In flight the co-steer ideal is the yaw to the target centre (clamped); design 6.1 step 3's "yaw that makes the
  key acceleration exact" is not implemented.
- LEGIT limits: an eased / walking plan counts as one key change, a brake as one more; plans that do not fit
  `maxKeyChanges` are not offered; brakes before the reaction ticks are not offered; after the user's press, easing
  cannot start before the reaction ticks.

## In-game playtest (cannot be checked in unit tests)

- The 17 control lanes in devtest ParkourScenario (not edited here). Wanted lane changes for the replica-parity owner:
  none required; the lanes' "first ground tick with z >= 10.85" press lands anywhere between 10.85 and ~11.13, which
  the controller handles (the tests cover the same phase on a -8 start). Consider logging `approach().planKind()` /
  `ticksToTakeoff()` in the lane status to see which plan ran.
- Feel of the eased run-up (forward released for 1-4 ticks right before the jump) and of the held-back press (up to
  4 ticks): does it read as the assist's, and is it acceptable in GUIDE?
- LEGIT reaction delay with the approach (ease after a press waits the reaction ticks).
- Real sprint behaviour on the eased tick (vanilla `LocalPlayer.aiStep` sprint end and restart with the key held)
  against the replica, and the module's `SharedGrant.withholdSprint` path.
