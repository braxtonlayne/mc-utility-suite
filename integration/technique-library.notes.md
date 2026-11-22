# Technique library (corner, headhitter, neo, 45-degree)

Branch of the approach-control volley, base main 7b7d078. Implements `TechniqueSource` (contract on main, unchanged).

## Files

- `travel/movement/TechniqueLibrary.java`: `TechniqueLibrary implements TechniqueSource`. Budgeted search with a
  cursor, keeps the best script, re-bases it every call.
- `travel/movement/TechniqueTemplates.java`: the four candidate families as mixed-radix integer grids
  (`size`, `decode` into a preallocated `Candidate`).
- `travel/movement/TechniqueRoller.java`: rolls one candidate in `MovementPhysics`, fills a `FlightScript`,
  validates and scores it.
- `travel/movement/ParkourCourse.java`: new `TECHNIQUE_CASES` list + `techniqueCase(i)`, a constructor with extra
  full blocks, `padX`/`padZ0` getters. `STANDARD` and `EDGE_CASES` are untouched (ShapePhysicsTest indexes
  `EDGE_CASES.size() - 1`, so the technique scenes are a separate list instead of appended there).
- Tests: `TechniqueLibraryTest` (13), `TechniqueLibraryAllocationTest` (2), `TechniqueCourseTest` (3).

## Wiring (for the integrator; MovementAssist is owned by another task)

```java
brain.setTechniques(new TechniqueLibrary());
```

One instance per brain. Client thread only, no net.minecraft imports.

## How it works

- A candidate: ground ticks 0..J with forward (+ the user's strafe, or a shaped strafe for the last g ticks), the
  view moving toward a takeoff yaw; jump on tick J; then up to three air segments of (forward, strafe, yaw goal,
  ticks). The last segment runs to the touchdown. Yaw goals are reached at `maxYawDegPerTick`; NaN = hold.
- Takeoff ticks are relative to the plain edge tick (a straight W+sprint probe from now; a wall across the run-up
  also ends it). No edge within 20 ticks: no new search (only the kept script is re-verified).
- Families (keys-only grids first in every pass, then the yaw grids when `yawAllowed`):
  - CORNER: takeoff offsets {0, -1, +1, -2, -3, -4} x side x (ground outward strafe 0..3 ticks, or view angled away
    10..45 deg in 5 steps) x outward air ticks {1, 2, 3, 5} x inward sweep {off, to the aim yaw} x air brake
    {none, back key after 2 or 4 inward ticks}.
  - HEADHITTER: takeoff at the edge tick or up to 7 ticks earlier x air strafe {0, L, R} x takeoff yaw
    {user, aim}. The exact ceiling boxes of the replica do the rest. No momentum-stacking variant.
  - NEO: takeoff offsets x side x strafe-away for the last 1..6 ground ticks x parallel air ticks 0..5 before
    strafing back x sweep back in {off, on}.
  - FORTY_FIVE (yaw only): takeoff on the line to the target, then after 0..2 air ticks the view 45 deg off with
    the matching strafe key.
- Validity (TechniqueRoller): starts on the ground for a takeoff; no `horizontalCollision` on any tick; touchdown
  with `|y - top| < 1e-6` and `target.contains`; no fall damage with `safeOnly`, never lethal; view steps within
  the rate, no view change at all without `yawAllowed`; takeoff yaw reached by the takeoff tick; no-key stop point
  within `HOLD_REACH` (1.0 block) of the top. Stable stops outrank hold-dependent ones through the margin score.
  Sprint follows the user's sprint while forward is held and stays off after any tick without forward.
- Score: margin of the no-key stop point (0.05 buckets, capped at 0.5) x 1000, minus 2 per tick off the user's
  keys, 3 per key change, 0.1 per degree of yaw travel, 10 for any yaw.
- Kept script: re-based each call. When the body is on the predicted position of tick k (within
  `FlightScript.DRIFT_LIMIT`) the candidate is re-rolled from the real body with k + 1 ticks dropped; when it is
  still where the script started it is re-rolled unchanged; otherwise it is dropped. `plan` returns true only when
  the kept or a new script was rolled from `now` in this call, so index 0 is always the next tick.
- A changed target (top height, region bounds, rectangle count) or changed `yawAllowed` / rate / `safeOnly`
  clears the kept script and the cursor; so does `reset()`.
- Budget: a roll is started only while `ticks + 48 <= 600`; the edge probe and the re-verify roll count.

## Measured (unit scenes, this machine)

- About 110 to 185 us per `plan` call at 570 to 580 replica ticks; 0 bytes per call after warm-up.
- Corner column onto a block (straight line clips the column): found in 16 to 17 calls from 6 blocks before the
  edge, keys-only and with yaw. Corner onto a fence post: found from an approach line left of the column (x -1.5),
  keys-only and with yaw.
- Headhitter (2-high room over the last two takeoff blocks), 2 and 3 gap: found in 5 to 21 calls.
- 1-block neo (1-wide strip, wall column in the line, landing block right behind it): found keys-only and with yaw.

## Limits and open points

- Corner onto a fence post from the straight line behind the column (x 0.5, the LandingJointTest scene): no template
  in the grid lands it with sprint held; from a line left of the column it does. Likely physically too tight at
  sprint speed; a sprint-withhold variant (contract `5a14f77`) may open it.
- The headhitter tests do not assert that the plain jump fails there; the brain only asks when no plain plan
  lands, so the scene checks the template, not the need for it.
- The family name on a found script is the family whose grid produced it. A corner scene is sometimes landed by a
  NEO-grid candidate (strafe-away on the ground, strafe back in the air), which is the same motion.
- The 45-degree template is implemented and its grid tested, but no unit scene shows it winning over the others.
- Momentum chains (section 8.9) and ladder/water landings are out of scope here.

## Left for the playtest

- Corner and neo lanes in game.
- Feel of the yaw sweep on BALANCED and RELAXED (co-steer follows the recorded yaw; the script never writes it).
