# Intent estimator notes

`travel/movement/IntentEstimator` (engine-free, allocation free). Public signatures unchanged from the contract stub;
added diagnostics `lastCandidateCount()` and `lastLookups()`.

## Algorithm
- No keys and speed < 0.03 -> no target (standing still).
- Movement direction: held keys rotated by yaw (forward = (-sin, cos), left = (cos, sin)); no keys but coasting
  (speed >= 0.05) uses velocity; else the look direction.
- Candidates: standable cells (solid, two clear above) in columns within 1.2..6.0 blocks of the player (block centre),
  inside a 60 degree cone around the movement direction, support-block y from +1 up to -4 down. Columns are scanned
  nearest first from a precomputed offset table, so caps keep the nearer cells. Same-level cells reachable over an
  unbroken floor (samples every 0.5 block along the straight line, all solid at the support level) are skipped; any
  height change or air gap makes a cell a jump target.
- Terms (all 0..1) and weights: LOOK 0.40 (ray through the top face scores 1, else 1 - angle/30 deg to the face centre),
  KEYS 0.20 (alignment of key direction, (cos-0.3)/0.7; neutral 0.5 with no keys), VELOCITY 0.15 (same, blended with
  neutral by speed/0.2), LINE 0.15 (distance from player + mean of the last <=4 jump vectors to the cell centre, 0 at
  2.5 blocks; neutral 0.5 with no history), REACH 0.10 (table by height difference: up1 3.6, flat 4.3, down 4.6/4.9/5.1/5.3
  plus 0.4; x0.8 when not sprinting; soft falloff over 1.5 blocks). The total is also multiplied by (0.5 + 0.5 * reach)
  so an unreachable block cannot win on look alone.
- Result: best score; runner-up ignores cells within 1 block (Chebyshev) of the winner (same landing area).
  confidence = min(1, (best - runnerUp) / 0.25) * best. hasTarget = confidence >= 0.5.
- Hysteresis: the previous target is kept unless a challenger beats it by 0.05.
- idealYaw = atan2(-dx, dz) in degrees to the top-face centre.

## Caps and cost
- MAX_CANDIDATES 96, MAX_LOOKUPS 1200 (checked per column; walk-line samples may overshoot slightly, bounded by
  the candidate cap). About 8 lookups per scanned column.
- Measured (unit test, JIT warm, pillar field): about 0.74 us per call, 290 lookups; 0 bytes allocated over 200k calls.
- No Minecraft classes used. GridRay was not reused: it needs a BlockQuery object and JumpReach.compute runs a
  heavier search, so a small inline walk and a reach table are used instead.

## Playtest list
- Weight tuning on real parkour courses: does it pick the block the player meant.
- Slabs, fences, ladders, stairs (only `solid` is used, no partial-block model).
- Diagonal and neo jumps (cone is 60 degrees around the key direction; neo needs checking).
- Target flicker while turning (hysteresis 0.05, margin scale 0.25).
- Cost in a Spark profile.
