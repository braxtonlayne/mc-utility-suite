# Takeoff shaping (volley 2026-11-22, task takeoff-shaping, review 3.4)

Branch from the contracts commit bf4c67d. When the user's run-up would miss the intent target, the assist shapes the
last ground ticks before the edge the way a player who knows strafe jumps would. No rotation is written: a shape that
needs the view turned only becomes the ideal yaw of the existing turn-gain request.

## What was built

| Piece | Where | What it does |
|---|---|---|
| Shaper | `travel/movement/TakeoffShaper` (new, engine-free, allocation free) | Within `WINDOW_TICKS` (6) ground ticks of the edge, simulates the plain run-up (the user's keys and yaw, a jump on the last supported tick like the edge jump, the user's keys in the air). If it lands on the target with `SAFE_MARGIN` (0.05 block), nothing happens. Otherwise it tries shapes and picks the cheapest that lands (ties: the larger margin). Outputs: `needed()`, `found()`, `strafe()`/`strafeNow()`, `sprintTick()`/`sprintNow()`, `yawShape()`/`idealYaw()`, `jumpNow()`, `cost()`, `gainBlocks()` (shaped margin minus plain margin; a miss counts as a negative margin), plain/shaped takeoff speeds. |
| Shapes | `TakeoffShaper.evaluate` | (1) **Sprint timing** (user holds forward, not sprinting, vanilla could start a sprint): engage on ground tick k = 0..plain takeoff; among the k that land, the one with the highest takeoff speed (earliest on a tie). Cost 1 (vanilla keeps sprinting). Only k = 0 acts; a later k waits and is re-decided next tick. (2) **Strafe at the current yaw** (user holds no strafe key): left or right for the first 1, 2 or all ground ticks. Cost = strafe ticks. (3) **45-degree strafe** (only with `shapeYawAllowed`, a mouse already turning at 0.5 deg/tick or more in that direction, and the 45 degrees reachable before takeoff with at most 1.5x gain): view yaw +/- 45 with the matching strafe key. Reported only as an ideal yaw; it fills no keys. Once the user's own yaw gets close, shape (2) covers it. Cost = ground ticks + 2. |
| Brain | `MovementAssistBrain.decideGround` -> `shapeTakeoff` | Gates: `takeoffShaping` on, the brain's own edge jump on, forward held, jump not held, budget cap > 0, an active intent target, an edge within 6 ticks. The whole shape's cost must fit `budget.remaining(miss)` before it starts (a shape cut off halfway is worse than the user's run). Each acting tick costs one `tryUse(miss)`, where miss = the plain miss, or (narrow landing) the margin still wanted. Fills `strafe` through the existing output (KEEP otherwise). The new `sprint()` output asks for sprint. While a key shape acts, the jump is the shape's own (`shaper.jumpNow()`, the shape's last supported tick), not the plain edge jump. A yaw shape overrides `idealTakeoffYaw()`/`hasIdealTakeoffYaw()` for this tick, so `turnGainHelp` asks for it through the existing path and budget. New getters: `sprint()`, `takeoffShaped()`, `shaper()`. `hasLanding()`/`landX/Y/Z` are unchanged. |
| Module | `MovementAssist.onMovementInput` | Before `decide`: `brain.shapeYawAllowed = !auto && turnGain && turnGainLevelOk` (the same gate as turn gain, so off on Legit), `brain.sprintFillAllowed` = not sprinting and vanilla's start-sprint conditions hold (forward impulse >= 0.8, on the ground, food > 6 or may fly, no item in use, not blind, not gliding, not sneaking or crawling, not riding, not in water). Strafe goes through the same `in.leftImpulse/left/right` path as air steering. Sprint: `p.setSprinting(true)` in the movement-input event, only when `brain.sprint()` and those conditions hold (see below). Shaping is off under the autopilot reflex (saved and restored like the other toggles). |
| Setting | `takeoff_shaping` | bool, default **on**, inline label "Shape short takeoffs". |
| JumpSearch | `addTicks(int)` | The shaper's simulated ticks count toward `MAX_SIM_TICKS`. |
| LegitPresets | unchanged | Keys-only shaping needs no Legit knob. The only rotation-related part (the yaw shape) rides the existing `turn_gain` knob and `turnGainLevelOk`, so Legit stays keys-only and still shows as Legit. |

### The sprint call (for review)

`IInputSmoother.setKeyHeld(SPRINT)` needs a MOVEMENT lease, and any manual movement key preempts MOVEMENT leases
below EMERGENCY. The user is holding W the whole time shaping runs, so a lease-held sprint key would be preempted at
once. Instead, the module does in the movement-input event what vanilla `LocalPlayer.aiStep` does a few lines later
for a held sprint key: `setSprinting(true)`, under the same start conditions. The server sees an ordinary
START_SPRINTING. It is not on the static input-rule grep and touches no key mapping, but it is a player state change
outside `core.input`. If the coordinator prefers, move it behind a core call (for example an `IInputSmoother` "request
sprint start this tick" that skips the lease for fill-only requests).

## What the model says (MovementPhysics, measured, no folklore)

- **Diagonal input**: vanilla scales each key axis by 0.98 and normalises the input only when it is longer than 1.
  W alone is 0.98 long. W+A is (0.98, 0.98), length 1.386, normalised to 1. So W+A with the view 45 degrees off the
  travel line accelerates **1/0.98 = +2.04 %** along the line, on the ground (0.1274 -> 0.1300 b/t^2 sprinting) and in
  the air (0.02548 -> 0.026).
- **But the sprint-jump boost (0.2) goes along the view, not the travel direction.** Jumping with the view 45 degrees
  off sends 0.141 forward and 0.141 sideways. Measured flight reach past the edge, sprinting from standstill:

  | Run-up | Plain W | W+A at 45 the whole time | W+A at 45 ground, W air | W ground, W+A at 45 air (instant turn at takeoff) |
  |---|---|---|---|---|
  | 2 blocks | 3.926 | 3.737 | 3.382 | 3.949 (+0.023) |
  | 4 blocks | 3.894 | 3.744 | 3.389 | 3.917 (+0.023) |
  | 12 blocks | 3.751 | 3.762 | 3.407 | 3.774 (+0.023) |

  So the 45-degree strafe gains about 0.02 block, and only with the view straight at the jump and turned in the air
  (a 45-degree turn in one tick is 900 deg/s, twice the head-snap cap). Held through the jump it loses 0.1 to 0.2
  block. That is why yaw shapes almost never win, and they are offered only as a turn-gain ideal yaw.
- **What actually moves landings is the takeoff phase**: where in the last supported tick the body jumps. Reach
  varies by about 0.17 block with run-up length (3.77 / 3.93 / 3.77 / 3.89 for 1 / 2 / 3 / 4 blocks). Starting sprint
  3 ticks late from a 2-block run-up reaches 4.012 (vs 3.926). A strafe tick or two also shifts the phase and bends
  the line toward an off-axis target. This is what the shaper finds.

### The 4-block gap from a 2-block run-up (the brief's example)

Honest result: in this model, plain W + sprint from a 2-block run-up (edge jump on) **already lands** a 4-block gap
onto a 1-block pillar straight ahead (lands z = 5.115), so nothing is shaped. From 1- and 3-block run-ups it misses by
about 0.23 block, and **no keys-only shape at the current yaw lands it**: the shaper looks (`needed`) and reports
nothing (`found` = false). Walking (not sprinting) from 2 blocks, no sprint timing reaches 4 blocks.
`TakeoffShaperTest.fourBlockGapToAPillarIsWhatTheModelSaysItIs` asserts exactly this. The shaped-lands examples use a
3-block gap: an off-axis pillar (strafe) and a walking run-up (sprint timing).

### Coverage over a grid of jumps

Run-ups 1..4, gaps 1..4, pillar offset -2..2, sprinting or walking (160 jumps, pillar target, lethal pit, yaw 0,
still mouse): plain (edge jump + air steer + intent, shaping off) lands **14/160**; with shaping **50/160**, and **no
jump that landed before misses now**. 33 runs used a strafe shape, 4 used sprint timing.

## Tests

- `TakeoffShaperTest` (course: run-up platform, 1-block pillar, lethal pit):
  - The 4-block-gap verdict above.
  - A strafe key lands an off-axis pillar the plain run misses (both sides, and the right key for each).
  - Sprint timing lands a walking run-up. Brute force at the first sprint-shape tick: the chosen engage tick has the
    highest takeoff speed among the engage ticks that land.
  - No shaping when the plain jump lands (5 cases, sprinting and walking).
  - Still mouse or Legit: no yaw shape and no yaw change over the whole 120-jump grid.
  - A held strafe key, held sprint and forward are never overridden.
  - SafeWalk veto: a shape onto a pillar 3 blocks down lands without SafeWalk, and is invalid with it, at both the
    shaper and the brain level.
  - Budget: cap 0, or a spent budget, means no shaping and no sprint.
  - Setting off or no target: nothing.
- `TakeoffShaperAllocationTest`: `decide` with shaping active (a strafe tick on the 2-block run-up, yaw shapes allowed,
  mouse turning), 20,000 calls under 1 KiB (AllocationProbe). **Measured: about 10 to 12 us per decide** (308
  simulated ticks, 57 of them the shaper's). Also a bound over shaped run-ups: **max 530 simulated ticks per decision**
  (limit `MAX_SIM_TICKS` 1800).
- `ParkourIntentAssistTest`: the no-target equivalence with the pre-change brain now also asserts no `sprint()` and no
  `takeoffShaped()`; a new test lands the pillar run with shaping on and off.
- `MovementAssistBrainTest`: the new `sprint()` output: none without a target, at most one request on a walking
  run-up, never while sprinting, forward never touched.
- **ParkourBenchmarkTest: 19/19 before, 19/19 after.** The benchmark has no intent target, so shaping is inert there.
  The no-target equivalence test checks every decision along that course against the pre-change brain.

Static input-rule grep: clean (only the existing read-only `inv.selected ==` reads in `FallStaging`/`ParkourBlocks`).

## Left for playtest

1. Feel: does a shaped takeoff still feel like the user's own jump? Watch for the edge jump landing one tick
   later or earlier than the user expects, because the shape takes off on its own last supported tick.
2. The strafe key appears on a keystrokes overlay for someone who did not press it (1 to 6 ticks). Same for the
   sprint state starting a few ticks early.
3. Turn gain during a shaped approach (yaw shapes need a mouse already turning 45 degrees' worth before takeoff, so
   they should be rare: confirm they never pull noticeably).
4. Interaction with the jump buffer (shaping stands down while jump is held or buffered) and stick-the-landing
   (no sneak tap on a shaped tick).
5. The `setSprinting(true)` path: that the server accepts it like a key press, with toggle-sprint (MoveToggles) on
   and off, and that it never starts a sprint while hungry or using an item.
6. Spark cost of the movement tick on a shaped approach (the sim says about 12 us and at most 530 physics ticks).
7. The IntentEstimator target must be the pillar, not the ground behind it, for shaping to act at all.
