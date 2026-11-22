# Parkour intent assist (volley 2026-11-22, task parkour-assist)

Branch from the contracts commit 68af800. Background: `docs/research/baritone-for-parts-2026-11-21.md` section 3,
items 1, 2, 3, 5, 6, 8. The player supplies direction and commitment; the assist supplies tick precision and corrects
only when the simulation says the jump will miss. No rotation is written anywhere by this change: the only camera
effect is a turn-gain request through `Services.manualLook()`, which scales the user's own mouse.

## What each item does

| Item | Where | What it does |
|---|---|---|
| Intent target | `MovementAssistBrain.setIntentTarget/clearIntentTarget`, `JumpSearch.setTarget` | With a target at or above `IntentEstimator.MIN_CONFIDENCE` (and "Follow my intent" on), landings are scored by how close they get to the target block's top (`JumpSearch.missDistance`: 0 = the footprint stands on it) instead of by progress along the heading. Edge jumps, the tick-phase check and air steering all use that score. No target: the old progress score, unchanged. |
| Precision air steer | `MovementAssistBrain.decideAir` | The user's keys land safely but beside the target, and the best keys land on it: fill the free axis, but only when the remaining budget covers the rest of the flight. A trim cut off halfway can leave the player worse off than their own keys (seen in the sim: a cut trim dropped the player into the gap). Safety corrections (lethal, damage, much lower) work as before. |
| Ideal takeoff yaw | `MovementAssistBrain.updateIdealYaw`, `JumpSearch.edgeRun` | With a target, on the approach and before the apex: score the current yaw, the previous best and 2 new candidates per tick (`YAW_OFFSETS` around the yaw to the target, spread over ticks). An edge run walks to the last supported tick (at most 20 ticks) and jumps there. Exposes `hasIdealTakeoffYaw()`, `idealTakeoffYaw()` and `missDistance()` (the miss at the current yaw). |
| Turn gain request | `MovementAssist.onMovementInput` | Setting on, level not Legit, an ideal yaw found, a miss above 0.1 block at the current yaw, budget allows, on the approach or before the apex: `setYawGain("movement_assist", idealYaw, 1 + strength*help, 1 - strength*help, 2)` each tick. Otherwise nothing; the 2-tick ttl lets it lapse. `clearYawGain` on disable and world unload. No CAMERA lease and no rotation target. `assistedYawDegLastTick() > 0` counts a corrected tick in the budget. |
| Stick the landing | `MovementAssistBrain.sneak()`, applied as `in.shiftKeyDown = true` | On the touchdown tick and up to 3 more: when the landing block has no standable neighbour in the direction of travel and the un-sneaked continuation with the user's keys leaves support within 6 ticks. Released as soon as horizontal speed is under 0.03 b/t or the user changes keys. Never while the user holds jump (a chained jump) or the jump buffer is pending. |
| Tick-phase check | `TakeoffTiming`, brain `decideGround` | One tick before the last supported tick: compare jumping now at the current yaw with jumping on N+1 at `yaw + yawRate` (from `manualYawRateDegPerTick()`), and jump early when now is better. N+1 is an option only while the body is still supported on N+1, so it never walks off waiting. It runs only with a target or a moving mouse (at least 0.5 deg/tick), so a still mouse with no target behaves exactly as before. SafeWalk (`safeOnly`) rules out damaging early jumps too. |
| Authorship budget | `AuthorshipBudget` | Corrected ticks per jump capped by the setting (default 6). A jump runs from one takeoff to the next, and an assisted takeoff is its first tick. No help when the jump already lands. `help(miss)` = min(1, miss) x fade scales graded help (turn-gain strength). Key ticks are all-or-nothing, so the miss only gates them. A ring of the last 10 jumps records "landed clean without any correction" (on the target, or with no fall damage). Above 80% unassisted success (after 5 jumps), the fade drops linearly to 0.25 at 100%. Every correction asks first: edge jump, early jump, air-steer key fill, sneak tap, turn gain (the module asks through `turnGainHelp`, and a reported gain tick counts). 0 = no corrections at all, not even edge jumps. |
| Jump buffer | `JumpBuffer`, applied as `in.jumping = false` while withholding | Opt-in (default OFF). A jump press that comes 1..N ticks (default 2, range 1..3) before the last supported tick is withheld and fired on that tick (also after a quick tap). It is never held longer than N ticks, never when no edge is that close, and is untouched on flat ground or in the air. |
| Estimator plumbing | `MovementAssist` | `IntentEstimator.estimate` runs each tick while the module (or the autopilot PARKOUR reflex) is active and the user is moving or airborne. `noteJump(takeoff, landing)` runs on takeoff/touchdown transitions. `reset()` (and `brain.reset()`) runs on world unload and on a teleport-size position change (more than 8 blocks in one tick). |

Bounds: `MovementAssistBrain.MAX_SIM_TICKS = 1800` physics ticks per decision, tested (about 230 measured with a target
at an edge). Allocation tests (AllocationProbe, under 1 KiB per 500 decisions / 256 B per 1000 calls) cover `decide` with a
target, yaw rate and buffer on, the budget and the buffer.

## Settings (module `movement_assist`)

| Key | Type / default | Label | Notes |
|---|---|---|---|
| `intent_assist` | bool, true | Follow my intent | Aim at the estimated block instead of just going far. |
| `turn_gain` | bool, true | Turn gain | Only scales your own mouse; nothing with a still mouse; off on Legit. LegitPresets knob (false / true / true). |
| `turn_gain_strength` | decimal 0.25, 0.0..0.5 | Turn gain strength | Gains are 1 +/- strength x help; the core clamps them to 0.5..1.5. |
| `stick_landing` | bool, true | Stick the landing | |
| `tick_phase` | bool, true | Tick-perfect takeoff | |
| `authorship_budget` | int 6, 0..20 | Corrections per jump | 0 = no corrections. |
| `jump_buffer` | bool, **false** | Jump buffer | The description says it can delay your jump key by up to 150 ms. |
| `jump_buffer_ticks` | int 2, 1..3 | Jump buffer ticks | |

Labels are inline (`SettingSpec`); `integration/movement.lang.json` was not touched (not an owned path). Add entries
there if the menu reads translations for these keys.

## Legit level and turn gain

- `LegitPresets.KNOBS` gains `knob("movement_assist", "turn_gain", false, true, true)`.
- A missing key reads as the Balanced value, so an existing profile left on LEGIT would silently get turn gain.
  `LegitPresets.currentIgnoring(profile, global, module, key)` finds the level the rest of the profile is on, and
  `LegitPresets.boolKnob(...)` returns that level's value for an absent key (Balanced for a Custom profile). The
  module reads `turn_gain` through `boolKnob` (the global profile comes from `Services.modState().global()`). It also
  keeps turn gain off whenever `current` or `currentIgnoring(turn_gain)` reads LEGIT.
- **Legit profiles saved before this change read as "Custom" in the level picker until the level is applied again**,
  because the absent `turn_gain` key compares as Balanced. Applying Legit writes `turn_gain = false` and shows Legit
  again. Turn gain stays off in the meantime.

## Inert until other tasks merge

- **IntentEstimator** is still the contract stub on this branch: it never has a target. Until the estimator task
  merges, every target-dependent item stays at today's behaviour: precision air steer, ideal takeoff yaw, turn-gain
  requests, target-aware edge jumps, and the tick-phase check with a still mouse. Tests drive them through
  `brain.setIntentTarget(...)`.
- **Turn-gain core** (`IManualLookAssist`) is `NONE` until the core task merges. `setYawGain` does nothing, and
  `manualYawRateDegPerTick()` returns 0, so the tick-phase check only runs with a target.
  `assistedYawDegLastTick()` returns 0, so nothing is counted.
- Active now without either: stick the landing, the authorship budget on edge jumps, air steer and sneak, the jump
  buffer (opt-in), and the Legit-safe turn_gain knob.

## Tests

- `ParkourIntentAssistTest`:
  - Target pillar off the heading: assisted lands on it, unassisted lands beside it.
  - Low confidence or intent off: no change.
  - **No target: identical decisions to a test-only snapshot of the pre-change brain**
    (`LegacyMovementAssistBrain`), checked along the whole benchmark course and over 27 air states.
  - Ideal yaw within 2 degrees of brute force.
  - No turn gain when the jump lands.
  - Sneak: fires on a 1-block landing, not on a long platform, not when chaining a jump, and releases.
  - SafeWalk still blocks a damaging assisted jump, early or last-tick.
  - Allocation test and a bound on simulated ticks.
- `TakeoffTimingTest`:
  - The yaw-rate sign flips N vs N+1.
  - Never waits when N+1 is unsupported.
  - Brain-level early jump: on with the setting, off without it.
  - A still mouse with no target skips the check.
- `AuthorshipBudgetTest`:
  - The cap per jump, and an assisted takeoff counts.
  - Help scales with the miss.
  - Fade with success rate, and no fade below 5 samples.
  - 0 = no corrections, including edge jumps.
  - The brain stops air steering at the cap.
  - Allocation test.
- `JumpBufferTest`:
  - Off by default.
  - Fires on the last ground tick, and also after a tap.
  - Max delay and the clamp to 3 ticks.
  - Flat ground and the air are untouched.
  - Brain-level withhold and fire.
  - Allocation test.
- `LegitPresetsTurnGainTest`:
  - An absent key on Legit reads off, and the profile shows Custom until reapplied.
  - Balanced and Relaxed read on.
  - A stored value wins.
  - Custom uses Balanced.
- `LegitPresetsTest` and `LegitPresetsRunKnobsTest` are extended for the knob.

## Playtest list

1. Turn gain feel: strength 0.25 vs 0.5, when it engages (approach and up to the apex), whether it is noticeable,
   whether it ever feels like the mouse "sticks". Needs the estimator and the core gain merged.
2. Intent target choice on real courses (side pillars, diagonal chains, drop-downs).
3. Sneak tap timing on 1-block landings, with toggle-sneak (MoveToggles) on, and with the bridge assist.
   Check that `in.shiftKeyDown` set in MovementInputUpdateEvent triggers vanilla's edge back-off on the landing tick.
4. Tick-phase choice at real frame rates (mouse rate per tick is noisy at low FPS).
5. Jump buffer feel (1, 2, 3 ticks); no missed jumps on flat ground or stairs.
6. The budget fading for a good player (does help really fade after about 10 clean jumps?) and that 6 ticks is enough
   for a drop-down air save.
7. A Legit profile shows turn gain off (also an old profile without the key), and re-applying Legit shows "Legit" again.
8. Autopilot PARKOUR reflex with Movement Assist off (no turn gain, edge jumps and air steer as before).
9. Spark profile of the movement tick with a target at an edge (about 230 simulated ticks per decision measured in the
   sim).
