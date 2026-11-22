# RouteFollower: integration notes

Engine-free corridor follower for "Baritone plans, we drive" (`docs/research/baritone-for-parts-2026-11-21.md` §1).
Files: `travel/movement/RouteFollower.java` (frozen public surface kept, members added), `RouteCorridor.java`
(straight-walk check), `RouteJumpPlanner.java` (simulated jumps). No core.api change, no mixin, no input API touched:
the caller applies `forward()/strafe()/jump()/sprint()` and turns the camera toward `wantYaw()` through the core
(InputSmoother, 450 deg/s cap).

## What is modelled

- **Progress**: farthest cell in a 16-cell window ahead within 0.8 blocks (y -0.5..+1.5), plus cells a validated
  straight chord skips (corner cuts) once the body is nearer the steering cell than they are. Never goes backwards.
  `finished()` latches when standing (on ground) in the last cell. `offRoute()` (per update, not latched) when the
  body is > 3 blocks horizontally, > 3 up or > 4 down from every cell in [progress-2, progress+16] and > 2 blocks from
  the chord it was running; keys are zero while off route.
- **Steering**: farthest cell within 12 ahead reachable by a straight walk (`RouteCorridor.straight`: samples every
  0.25 block; centre column standable at the level, one step up / one step down, deeper drops only onto route cells;
  every column of the 0.6-wide box body-clear with floor at most 2 below). Scan is nearest-first and stops after two
  consecutive misses. `wantYaw()` faces the steering cell (look where you move); during a planned jump it faces the
  landing cell, mid-flight it holds the take-off yaw.
- **Keys for the current yaw**: the forward/strafe pair whose push (vanilla input rotation) points closest to the wanted
  direction, so the body follows the route while the head is still turning. No sprint above 25 deg yaw error or with a
  sharp corner (> 60 deg) within 2 blocks; the last cell is approached with a stopping-distance brake.
- **Sprint hops** (flats): camera within 8 deg of the chord, >= 4 blocks of straight run, and a simulated sprint jump
  lands at the same height on the chord (within 0.45 sideways, before the steering cell) and gains >= 0.15 over
  sprinting (same rule as `MovementAssistBrain.hopGains`, reimplemented since that one is private).
- **Step up**: one-block step just ahead and the route rises there -> jump.
- **Jumps / shortcuts**: candidates are route cells beyond the walkable steering cell, 1.5..6.5 blocks away, landing
  -2..+1, standable. Taken when the route needs it (the next cell is across a gap) or as a detour shortcut (route
  length from the progress cell >= 1.6x the straight distance and >= 2 blocks longer; an L corner is at most 1.41x,
  so corners are never "jumped"). `RouteJumpPlanner.searchTakeoff` forward-simulates 0..14 run-up ticks then a sprint
  jump; it counts only landings on the aimed route cells (k-1..k+2, k..k+2 for a gap the route itself jumps) with
  **zero fall damage** (apex-to-floor fall, as vanilla). The jump key goes down on the latest-but-one tick that still
  lands (current yaw and keys). If the next step would leave the ground before the jump lands, the plan is dropped,
  the target blocked until progress moves, and the body brakes. Mid-air the held keys are kept when they land on
  target, otherwise the 9 key combos are searched for the landing closest to the target cell.
- **Chaining**: one jump is planned at a time; each landing is a route cell, so the next update plans the next jump
  (2-3 jumps in a row along a parkour stretch). There is no joint multi-jump search (see gaps).
- **Edge guard**: walking keys are simulated two ticks ahead; stepping off where the floor is more than one block down
  and not a route cell brakes (keys against the velocity). Jumps are only pressed with a simulated landing on a route
  cell (or a same-height hop on the validated chord), so no jump is ever pressed at the lip of a drop the route does
  not take.

## Budgets (per update, one update per client tick)

- `SIM_BUDGET` = 480 simulated `MovementPhysics` ticks in total (3 reserved for the edge-guard probes);
  `PLAN_BUDGET` = 240 of them for finding a new jump target. The candidate scan keeps a cursor and resumes on the next
  tick where the budget ran out. A search that runs out of budget answers "no" (walk as planned), except that an
  existing plan is not dropped just because its re-check ran out of budget.
- `SAMPLE_BUDGET` = 600 corridor samples (each about 5 column checks).
- Exposed for tests / overlays: `lastSimTicks()`, `lastSamples()`, `steerIndex()`, `jumpTarget()`, `jumpsTaken()`.
- Allocation free after construction: `RouteFollowerAllocationTest` runs 100k updates (plus the body ticks) on a
  looping course with a 2-block gap and asserts < 1 KiB allocated, no resets, and both budgets respected.

## Known gaps

- `MovementPhysics` has no step-up onto slabs/stairs (treated as full blocks: a slab step costs a jump), no ladders,
  vines, water, lava, soul sand, ice slipperiness per block, or effects beyond the speed/jump-boost fields. Routes
  through water or up ladders will be judged unwalkable by the corridor check; the follower falls back to steering at
  the next cell and the edge guard may stop it. Wire those through the caller (let Baritone drive such segments) or
  extend `MovementPhysics` first.
- Hazards: the corridor only knows solid / not solid. A Voxels adapter that reports lava, fire, cactus, magma, berry
  bushes or powder snow as non-standable is needed for "hazard-unknown" cells; `LevelVoxels` today does not.
- No joint 2-3 jump search: each jump is planned on landing. A run-up that needs momentum from the previous jump is
  found only if the landing leaves enough run-up.
- A sprint hop can carry the body into a detour lane before the shortcut is in range (seen in the detour test: the
  jump is then taken from the lane edge, diagonally; still no detour walked).
- The latest-but-one take-off tick is exact in simulation; in game, packet/frame jitter may shift it. If takeoffs fall
  short, move to the middle of the feasible take-off window.
- Head bumps under 2-high ceilings are simulated (collision), but the corridor check does not penalise them.
- Diagonal routes on 1-wide paths may lose sprint more often than needed (yaw error rule).

## Tests

`RouteFollowerTest` (body driven with the follower's keys, camera turned toward `wantYaw` at <= 22.5 deg/tick):
straight flat route finishes faster than sprinting with sprint hops; open L corner cut diagonally; L beside a wall
(<= 2 wall bumps) and beside a void (never falls) not cut; 2- and 3-block gaps on the route jumped and landed with no
damage; a detour around a 2-block gap shortcut by a jump (detour lane never stood in); a 5-block gap detour walked;
a jump landing 2 lower (1 damage) not taken, the side stair walked; one-block step up; off route after a teleport and
after falling 6 below, keys released, progress kept; finished latches; no jump at the lip of a drop the route ends at;
progress monotonic and budgets respected in every run. `RouteJumpPlannerTest`: take-off search, 5-gap infeasible,
damaging landing rejected, budget is a hard cap, hop gain and chord limit.

## Playtest list (in game, real camera lag)

1. Corner cutting vs clipping: L corners in open fields, beside walls, beside holes and lava (once hazards are wired).
2. Sprint-hop rhythm on real terrain (grass, paths, bumpy ground, slabs in the way).
3. Baritone routes through slabs/stairs, water, ladders: check the follower stops cleanly instead of guessing.
4. Pursuit of a moving target: frequent `setRoute` calls (progress resets to 0 each time; confirm no stutter).
5. Feel of the shortcut jumps (detour gaps, chained parkour), and the take-off timing with real input latency.
6. Knockback mid-route: off-route detection and replanning latency.
