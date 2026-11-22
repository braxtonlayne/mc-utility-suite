# movement-authority (volley 2026-11-22, approach-control)

Movement Assist's input now goes through `ISharedControl`; sprint withholding is implemented in the core; yaw
co-steer (D1) and the hold-to-take-over key (D3/D4) are wired. Base: main at 7b7d078.

## Wiring for the integrator

- **One line still to add** (left out on purpose: `TechniqueLibrary` belongs to the parkour-approach task): in the
  `MovementAssist` constructor (or field initializer), `brain.setTechniques(new TechniqueLibrary());`.
- Lang: merge `integration/movement-authority.lang.json` into `en_us.json` (the take-over key name, the `co_steer`
  setting and its tooltip).
- Nothing else to register: the key mapping is registered by the existing `TravelKeys.onRegisterKeyMappings`
  listener, the mixin is listed in `utilitysuite.mixins.json`, and the overlay's listeners are unchanged in
  `CoreBootstrap`.

## Mixins

One new mixin, so the total in `utilitysuite.mixins.json` `client` goes from 21 to 22:

- `input.LocalPlayerSprintWithholdMixin`: target `LocalPlayer.aiStep`, `@WrapWithCondition` (MixinExtras v2) on
  `INVOKE Lnet/minecraft/client/player/LocalPlayer;setSprinting(Z)V`. It skips a call only when the argument is
  `true` and `SharedInputOverlay.isSprintStartBlocked()` is set. Checked against the 21.1.265 sources and the compiled
  class (`javap`): `aiStep` has four `invokevirtual LocalPlayer.setSprinting(Z)V` calls. Two pass `true` (the
  double-tap-forward start and the sprint-key start, both after `ClientHooks.onMovementInputUpdate`) and two pass
  `false` (vanilla's stop rules). The `false` calls always run.
- Why no event-only solution: in 1.21.1 `aiStep` reads `options.keySprint.isDown()` after the input event and calls
  `setSprinting(true)` with no event in between. A listener can end a running sprint, but it cannot stop the held key
  from starting the sprint again in the same tick. Lifting `keySprint` with `setDown(false)` would lose the
  physical hold, which a regular mapping only restores on the next press event, and would break toggle-sprint mode.

## Core: `SharedGrant.withholdSprint()` (SharedInputOverlay)

- `Grant.withholdSprint()` marks the grant's write for the next tick, like every other write. `canWithholdSprint()`
  returns true.
- `apply()`: a fresh withhold sets `sprintBlocked` and suppresses a `startSprint` in the same tick (withhold wins,
  in either call order). `GameSide.apply` then calls `setSprinting(false)` on a running sprint, and the mixin blocks
  both vanilla start paths for the rest of that `aiStep`.
- The flag lapses at the next `beginTick` (ClientTick.Pre) and at the start of the next movement event. It is also
  cleared by `end()`, Stop-All and world unload (both through `finish`), and by a screen opening (`onScreenOpening` and
  the per-tick screen check). It is never set while the grant is inactive, because writes on an inactive grant are
  ignored. A withhold written two or more ticks earlier is dropped like any stale write.
- The mixin reads the flag through a static field of the registered overlay (`SharedInputOverlay.active`, set in the
  constructor). When no withhold is set, the hook costs one null check and one field read.
- Side effect, documented as vanilla's own behaviour: while sprint is withheld and forward is pressed, the
  double-tap path may arm `sprintTriggerTime` exactly as it would with the sprint key up. That matches "as if the
  key were up".

## MovementAssist on the shared grant

- The direct writes to `in.forwardImpulse/up/down`, `in.leftImpulse/left/right`, `in.jumping` (brain jump, jump
  buffer withhold, parkour-blocks edge jump), `in.shiftKeyDown` (landing hold) and `p.setSprinting(true)` are gone.
  Each tick the assist collects them (jump ON/OFF/KEEP, axes, sneak, startSprint, withholdSprint, co-steer) and hands
  them to the grant in `applyWrites`. `grep setSprinting src/main/java/com/utilitysuite/travel` finds nothing. The
  static input grep is clean (only the read-only `inv.selected ==` reads in `FallStaging` and `ParkourBlocks` remain).
- Grant lifecycle: `begin(ID, TRAVEL, wantsYaw)` when there is something to write or the commitment is live
  (`JumpCommit.live()`, so the whole ARMED..RELEASE/ABORT span). The grant ends on the first tick with neither, and on
  every early return: module off, no player, autopilot clutch only, skipped (Stop-All, screen, lease, not on foot) and
  sneaking. It also ends on `setEnabled(false)` and world unload. A refused `begin` writes nothing that tick. If a
  grant was begun without yaw and co-steer becomes allowed, it is begun again with `wantsYaw`. Nothing had been
  steered yet, so restarting the allowance loses nothing.
- Never preempts a job: with "Assist automation" on and another module's MOVEMENT lease live (anything but
  MoveToggles), the assist does not begin a grant and writes nothing. Before this change it wrote over the job's keys.
  This is a behaviour change, made on purpose, because a TRAVEL grant would otherwise suspend a GOAL job's lease.
- **MoveToggles' latched toggle-sprint** holds an IDLE MOVEMENT lease. The TRAVEL grant outranks it and suspends it
  while the grant is live; the arbiter resumes it when the grant ends. A sprint that is already running keeps going,
  because vanilla needs the key only to start a sprint. If something ends the sprint mid-commitment, for example a
  wall, the toggle cannot restart it until the grant ends, though takeoff shaping's `startSprint` can still fill it.
  This needs a playtest.
- Parkour blocks: while a placement runs (`blocks.active()` after `blocks.tick`), the brain's axis writes are dropped
  for that tick. `compensate` rotates the user's keys back onto the heading, and the brain's camera-relative axes
  would point the wrong way once applied after it. Before this change, compensate rotated the brain's keys too.
  Small behaviour change, limited to the few ticks of a placement.
- Unchanged: the autopilot branch (mode at least GUIDE; the module's toggles swapped in and out around `decide`),
  the clutch and block-assist leases and their `compensate` calls, `brain.sneak()` only when the user is not sneaking
  (the sneaking path returns before the writes), status strings, `publishCommit`, `corrections()`. `edgeJumps`,
  `hops`, `jumpingThisTick` and the blocks jump's `brain.noteApplied(true)` now count only when the grant took the
  write.
- Written axes now get vanilla's sneaking-speed multiplier while the player `isMovingSlowly()` (the overlay applies
  it). Before, Movement Assist wrote a raw +-1 there. This only matters during the landing hold's sneak, where it
  is closer to vanilla.
- `brain.sprintWithholdAvailable` = the live grant's `canWithholdSprint()`, or (no grant yet) what the last grant
  reported. It is false until the first grant of the session, so the first ARMED tick of the first commitment plans
  without a withhold.
- `ManualFacts`: no extra reads were needed. The brain still gets the user's keys from the event's `Input`, which the
  assist now only reads (writes land at LOW), so its fresh-versus-stale tracking is unchanged.

### Same-tick ordering (how it was verified)

`TravelBootstrap` registers `movement::onMovementInput` with `addListener` (EventPriority.NORMAL), and
`CoreBootstrap` registers `sharedControl::onMovementInput` at `EventPriority.LOW`. Both listen to the same
`MovementInputUpdateEvent` fired from `LocalPlayer.aiStep`. The grant stamps each write with `ManualFacts.tick()`,
which `ManualFactsTracker.beginTick` advances in ClientTick.Pre, before `aiStep` runs. When the overlay applies at LOW,
`writeTick == facts.tick()`, so the brain's decision lands on the movement input of the tick it was made for, not
one tick late. Checked by reading the registrations and the overlay's freshness rule (`writeTick >= tick - 1`). The
unit tests cover the freshness and lapse rules (`SharedInputOverlayTest.writeFromThePreviousTickAppliesOlderOnesAreDropped`,
`SharedSprintWithholdTest.aStaleWithholdIsDropped`). Other NORMAL listeners that run after Movement Assist
(`MoveToggles`, mount jump) now see the user's input instead of the assist's. Only `jumpingThisTick()` was consumed
across modules (SafeWalk), and it keeps its meaning.

## Yaw co-steer (D1)

- Allowed (`brain.coSteerAllowed`, set every tick before `decide`): the `co_steer` setting is on (default on), the
  autopilot branch is not running, no screen is open, no foreign MOVEMENT lease is live, the effective mode is
  GUIDE or CARRY (never Corrections/NUDGE), the level's rate and allowance are both above 0 (LEGIT: 0), and the hand
  has not overridden the co-steer in this commitment.
- Rates (new LegitPresets knobs next to the three key-change knobs, LEGIT / BALANCED / RELAXED):
  `co_steer_deg_per_sec` 0 / 150 / 450, `co_steer_max_deg` 0 / 25 / 180, `co_steer_carry_deg_per_sec`
  0 / 240 / 450, `co_steer_carry_max_deg` 0 / 90 / 180. The rate is clamped to `IInputSmoother.MAX_TURN_DEG_PER_SEC`
  (450) in the module, and `YawCoSteer` caps it again in the core. `brain.coSteerMaxDegPerTick = rate / 20`.
- Read with the new `LegitPresets.numKnob`, which uses the stored value or, when the key is absent, the value for
  `LegitPresets.storedLevel`: the level all stored knobs match, with absent keys ignored. A profile left on Legit
  before these knobs existed therefore reads 0. Without this, `current()` would call that profile Custom and
  co-steer would have run on Legit. As a second guard, the module zeroes the rates whenever `current()` or
  `storedLevel()` reads LEGIT. The overlay also refuses yaw at `begin` on LEGIT, as before.
- On a tick where `brain.wantsCoSteer()`: `grant.coSteer(brain.coSteerYaw(), rate, min(allowance,
  brain.coSteerTotalDeg()), commit.authority())`. Authority is the commitment's own (GUIDE 0.6, CARRY 1, and it ramps
  down through RELEASE as the hand-back). The hand's own motion always passes through, since `YawCoSteer` only adds.
- Override: each tick `grant.opposingYawDeg()` goes into a 5-tick ring. When the opposing degrees within 5 ticks
  reach `classifier.yaw(0, 0, 0, opposing5) == AXIS_OVERRIDE` (6 degrees, scaled by override sensitivity), co-steer
  turns off for the rest of the commitment. The keys go on (design 4.1: "the yaw is the user's; keys continue").
  The existing hard-turn rules (25 degrees off line, 20 degrees per tick) still abort through `JumpCommit`. The flag
  resets once the commitment is IDLE.
- `YawCoSteer` was not modified. Its rate cap, acceleration limit and 450 hard cap are covered by the existing
  `YawCoSteerTest` (`rateCapIsRespected`, `accelerationCapIsRespectedAtAnyFrameRate`,
  `absurdRequestIsHardCappedAt450`). The LEGIT refusal is covered by
  `SharedInputOverlayTest.coSteerOnlyWithWantsYawAndNotOnLegit`.

## Hold-to-take-over key (D3, D4)

`TravelKeys.PARKOUR_TAKEOVER = "key.utilitysuite.parkour_takeover"`, registered unbound (`InputConstants.UNKNOWN`)
in the Utility Suite category with the IN_GAME conflict context. `TravelKeys.takeoverHeld()` returns
`!isUnbound() && isDown()`, read only inside TravelKeys. While it is held, Movement Assist runs CARRY for that tick in
both the user and autopilot branches, whatever `control_mode` says. CARRY's own rule then applies: it commits on
forward held toward a target (D4). Co-steer under the key uses the CARRY rates. `MovementAssist.takeoverHeld()` exposes
it for read-outs.

## Tests

- New `core/input/SharedSprintWithholdTest` (12): it can withhold; a withhold held for N ticks then lapses; the
  block ends with the tick even without an apply; withhold beats startSprint in either order and a fill works again
  the next tick; a stale withhold is dropped; nothing after `end()`; a withhold written before `end()` in the same tick
  writes nothing; Stop-All, a screen opening and a screen at tick start clear it; other writes are kept; and the
  path with withhold and co-steer allocates under 1 KiB over 1M iterations.
- New `core/LegitPresetsCoSteerTest` (5): the level values, older Legit profiles (co-steer keys absent, or the whole
  movement section absent) read 0, older Balanced/Relaxed profiles read their level, an empty profile reads
  BALANCED, and a stored value wins.
- `./gradlew compileJava compileDevtestJava test --offline` passes: 205 suites, no failures.

## Left for the playtest

- The mixin audit at the first `runClient` (`-Dutilitysuite.mixinAudit=true` applies `LocalPlayerSprintWithholdMixin`
  at startup).
- Co-steer feel: the camera never jumps; it never moves on LEGIT, in Corrections or under the autopilot; mouse motion
  against it hands the yaw back for the rest of the jump; the HUD authority bar matches.
- The take-over key once the owner binds it (D3): held mid-approach it should commit on forward toward a target and
  fly the jump; released, the selected mode returns on the next tick.
- Toggle-sprint (MoveToggles, suspended while the grant is live) and double-tap-W sprint still work with the
  overlay. Sprint withholding ends a run-up sprint for exactly the withheld ticks and lets the user's held sprint key
  take over again after them.
- "Assist automation" with a job driving: the assist now stands down (writes nothing). Decide whether that is wanted.
