# Intent estimator v2 notes (task intent-v2, 2026-11-22)

`travel/movement/IntentEstimator`, design `docs/design/parkour-control-model-2026-11-22.md` section 9, items 1-5 and 8.
Every existing public signature is unchanged (estimate, noteJump(4), reset, hasTarget, targetX/Y/Z, confidence,
idealYaw, lastCandidateCount, lastLookups), so `MovementAssist` compiles and behaves as before without changes.
`JumpReach` was not changed (the estimator keeps its own reach table as the pre-filter).

## What changed
1. **Real tops.** Each scanned column reads its box counts once (cells from 4 below to 4 above the block the player stands on), takes the
   highest box top of every cell (fence/wall 1.5, slab 0.5, pane/bars 1.0 on the thin post, full 1.0) and its extent,
   and requires no box of the cells above to overlap [top, top + 1.8]. Tops more than 1.26 above the feet are left
   out. Surface of the cell (and of the cell the top rises into): HARM and STUCK are not candidates; UNMODELLED is a
   candidate with score x0.8 and confidence capped at 0.3 (below CARRY and GUIDE). The published target is still the
   block cell; `targetTopY()` gives the true landing height and `targetSurface()` the kind.
2. **Simulated reach.** The table (up 1 3.6, flat 4.3, down 4.6..5.3, +0.4, x0.8 walking; now interpolated for
   half heights) is a hard pre-filter at table reach + 1.5 and still the soft REACH term. The best 6 by score get one
   roll each in `MovementPhysics`, at most 2 rolls per estimate (`MAX_ROLLS_PER_ESTIMATE`), 48 ticks per roll,
   `MAX_SIM_TICKS` = 96 per estimate. Roll policy: run toward the top centre with sprint as given; jump at the coming
   edge (a no-jump probe tick leaves the ground) or a wall, or earlier as soon as a coast with no keys would already
   carry the body to the top; in the air hold forward, release, or hold back (drift vs distance) aimed at the top
   centre. Feasible = touches down at the top's height inside the top grown by 0.3, fall <= 3. Results are cached per
   cell (24 slots); a result made from a stance more than 1 block / 0.1 speed away (or sprint / ground changed) is
   re-rolled, and the old result is used until then (no flicker). Infeasible tops drop out.
3. **Gaze over time.** The look ray is marched through the cells against exact boxes (the cell below is also tested
   on shaped grids: fences rise into the next cell), up to 8 blocks; no hit = where the ray crosses the feet level.
   The hit point is averaged with alpha 0.25 (about 6 ticks). The look term scores 1 when the averaged point is on a
   top face (margin 0.1, height within 0.3), else 1 - angle/30 deg from the eye. Updated even when standing still.
4. **Course line.** Ring of the last 8 landing points (+ the last takeoff). Tops within one cell of the newest
   landing or the takeoff: score x0.5; older ring entries: x0.75. Heights are matched within 0.6 when known: the
   5-arg `noteJump(..., landY, ...)` gives it, the 4-arg form fills it from the next ground estimate near the landing;
   the takeoff height is the ground the estimator saw last before the flight. The jump-vector LINE term is unchanged.
5. **Not a dead end.** When the two best (not neighbours) are within 0.06, each gets one more roll (shares the
   2-roll budget): from its top centre with the current speed (0.1..0.28) along the movement direction, sprint, jump
   at the edge; it continues if it touches down on another top with a safe fall. A dead end scores x0.85.
6. **Thresholds by mode.** `setCommitThreshold(double)`, constants `GUIDE_THRESHOLD` 0.5 (default, = MIN_CONFIDENCE)
   and `CARRY_THRESHOLD` 0.35; `hasTarget()` uses it.
- **Hysteresis.** The incumbent stays unless a challenger leads it by 0.05 on 2 consecutive ticks; an incumbent that
  is no longer a candidate (out of cone, infeasible) is replaced at once.

## Wiring for the integrator (MovementAssist / MovementAssistBrain are not mine)
- Mode threshold, each tick before `estimate`: `intent.setCommitThreshold(mode == CARRY ? IntentEstimator.CARRY_THRESHOLD
  : IntentEstimator.GUIDE_THRESHOLD)`. **Also** `MovementAssistBrain` line ~700 compares
  `intentConfidence >= IntentEstimator.MIN_CONFIDENCE`; it must use the same threshold (or trust `intent.hasTarget()`),
  or CARRY's 0.35 never gets through.
- Course heights: `intent.noteJump(takeoffX, takeoffZ, now.x, now.y, now.z)` at touchdown (MovementAssist line ~370)
  instead of the 4-arg call (the 4-arg call still works, height filled lazily).
- Landing height for the brain / jump search: `intent.targetTopY()` with the cell (for `JumpSearch.setTargetTop`).
- Lock at takeoff (item 6) is the brain's: it must stop reading the estimator after TAKEOFF. The estimator itself
  keeps running; its cache refreshes on the ground flag change.
- Diagnostics: `lastSimTicks()`, `lastRolls()`, `feasibility(x,y,z)`, `continues(x,y,z)`.

## Cost
- Unit test, JIT warm: pillar field (cubes) about 1.1-1.6 us/call (rolls cached); shaped course with the stance moving
  every call (worst case, about 1.4 rolls per call, max 96 sim ticks, max 532 lookups): about 17 us/call.
  0 bytes allocated in both (`IntentEstimatorAllocationTest`).
- Caps: MAX_CANDIDATES 96, MAX_LOOKUPS 1200 (now a hard cap: each column reserves 72 lookups before it is scanned),
  MAX_SIM_TICKS 96 (asserted in the allocation and shapes tests).

## Tests
- `IntentEstimatorShapesTest` (ShapeGrid): fence post across a 2 gap with landing height 64.5 feasible; wall and slab
  heights; pane post over the block behind it; HARM top not a candidate; UNMODELLED capped below CARRY; a top the
  table allows but the replica cannot reach is dropped; one-tick glance keeps the target, sustained look moves it
  (within 7 ticks); the platform just left is not committed to when turning around for 3 ticks (control without the
  ring commits); dead-end tie-break both ways; no flapping over 40 ticks of +-2 deg jitter; caps on a noisy shaped grid.
- `IntentEstimatorTest`: unchanged and green.

## Limits / left for the playtest
- Target pick on a real course (Twisted Parkour) and the drawn lock following the look.
- The roll is one policy (no jump-timing family): a top only reachable with an unusual takeoff (neo, a run-up away
  from the top, a strafe jump) can be judged infeasible. Diagonal 45-degree momentum is approximated by aiming yaw.
- Dead-end check looks along the movement direction only; a course that turns at the finalist reads as a dead end
  for both, so it decides nothing there (by design: it only breaks ties).
- Ladders/climbables are not landing tops yet (no boxes on the cell top in most cases); water is not a candidate.
- Deferred choice (item 7) is out of scope.
