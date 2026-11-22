# core-shared-control: control shared with the user (core side)

Implements `core.api.ISharedControl` / `SharedGrant` / `ManualFacts` (contracts from 0e29eea, unchanged) per
`docs/design/parkour-control-model-2026-11-22.md` sections 4.1, 6.2 and 7.1. Registered in `CoreBootstrap` with
`Services.registerSharedControl(...)`. Nothing in `travel/` calls it yet; MovementAssist still writes `Input` itself.

MIXIN: none added. `MouseHandlerTurnGainMixin` is unchanged (see the zero-delta answer below).

## Files

- `core/input/ManualFactsTracker.java` (`ManualFacts`): per `SimKey` down flag, last press tick, last release tick;
  client tick counter; hand yaw per tick. Engine free.
- `core/input/ManualInputHooks.java`: presses AND releases from `InputEvent.Key` / `InputEvent.MouseButton.Pre`
  (GLFW_REPEAT ignored) mapped to every matching `SimKey` (the 7 movement keys, attack, use; key or mouse bindings).
  Presses count only in game (no screen), releases always count. `beginTick(boolean screenOpen)` advances the facts
  tick and releases every key while a screen is open. `onTurnYaw` now records the raw hand yaw, applies turn gain,
  then adds the co-steer step.
- `core/input/YawCoSteer.java`: the follower (pure math, per-frame `onFrame`, per-tick `beginTick` lapse).
- `core/input/SharedInputOverlay.java` (`ISharedControl`): the one reused grant, the write buffer, `apply(MoveFields,
  slow)` (engine-free core), the MovementInputUpdateEvent glue (nested `GameSide`, so the overlay loads in unit
  tests), `sprintAllowed(...)`, screen handling.
- `core/arbiter/ActionArbiter.java`: `beginShared / endShared / sharedModuleId / sharedPriority` and the
  `SharedHolder` callback; request/isHeldBy/channelsFree/stopAll/resetForWorldUnload know the grant.
  `IActionArbiter` is unchanged; the overlay talks to the concrete arbiter.
- `core/CoreBootstrap.java`: creates tracker + co-steer + overlay, registers it; listeners:
  `MovementInputUpdateEvent` at `EventPriority.LOW` (overlay apply), `ScreenEvent.Opening` (ends the grant),
  tick Pre calls `manualInput.beginTick(screenOpen)` then `sharedControl.beginTick(screenOpen)`.
  `CoreBootstrap.sharedControl()` returns the concrete overlay.

## Zero-delta frame question (answered from the mapped 1.21.1 sources)

`MouseHandler.handleAccumulatedMovement()` runs once per rendered frame (`Minecraft.runTick`) and calls
`turnPlayer(d1)` whenever `minecraft.isWindowActive() && isMouseGrabbed() && minecraft.player != null`, with no
check on the accumulated delta. `turnPlayer` always ends in `this.minecraft.player.turn(d0, d1 * i)`, so with a still
mouse it calls `turn(0.0, 0.0)` and our `@ModifyArg` on index 0 still runs every frame. No mixin change was needed:
`ManualInputHooks.onTurnYaw` no longer early-outs on `yawArg == 0` while a co-steer request is live (it still does when
none is live, so the idle path stays a null check + two field reads). Consequences: no co-steer while the window is
unfocused or the mouse is not grabbed (a screen), which is what we want. `Entity.turn` adds to both `yRot` and `yRotO`,
exactly like hand motion.

## Behaviour summary

Grant lifecycle (`SharedGrantArbiterTest`):
- `begin` -> null when: a screen is open, automation stopped, another module's grant is live, a lease at or above the
  priority holds MOVEMENT (the module's own lease included). A lower lease is preempted (suspended, resumes when the
  grant ends); the module's own lower lease is released. The same module calling `begin` again restarts the same
  instance (allowance and opposing sum reset). The manual-override pause does NOT refuse a grant.
- While live: `isHeldBy(MOVEMENT, module)` is true, `holder(MOVEMENT)` stays empty (it is not a lease); other MOVEMENT
  requests at or below are refused, strictly higher preempt it (reason PREEMPTED), EMERGENCY always wins (even over an
  EMERGENCY grant), the module's own MOVEMENT lease replaces it (RELEASED).
- Manual MOVEMENT_KEY: the grant stays; ordinary leases still end and the override pause still starts for others.
- Ends: `end()` (RELEASED), Stop-All (STOP_ALL), world unload (RELEASED), screen (PREEMPTED, by the tick check, the
  apply path and `ScreenEvent.Opening`), winning lease. `isActive()` turns false at once; there is no per-tick callback
  to the module (it polls `isActive()`). `SharedInputOverlay.lastEndReason()` exposes the reason for diagnostics.

Writes (`SharedInputOverlayTest`): applied once at MovementInputUpdateEvent LOW, then cleared. Accepted when stamped in
the current or previous facts tick (so a write from ClientTick.Post of tick t or from a NORMAL movement listener of
tick t+1 both apply to tick t+1's input); older ones are dropped. `axes` sets impulse and the matching up/down/left/right
together, multiplied by the sneaking-speed attribute when the player `isMovingSlowly()` (the factor vanilla already
applied to the user's impulse). `jump` ON/OFF, `sneak(true)` only ever sets. `startSprint` calls `setSprinting(true)`
only when `sprintAllowed(...)` holds: not sprinting, forward impulse >= 0.8 (any forward impulse under water), food > 6
or riding or may fly, not using an item, not blind, a vehicle that can sprint and is locally controlled, not gliding,
not in surface water unless able to start swimming, not against a wall (`horizontalCollision &&
!minorHorizontalCollision`). Verified against `LocalPlayer.aiStep` / `canStartSprinting` /
`hasEnoughImpulseToStartSprinting` / `hasEnoughFoodToStartSprinting` in the 21.1.265 sources. Modded fluid types are
treated as land (vanilla's `isInFluidType` lambda would allocate). Item use: the 0.2 slow-down is applied by vanilla
after the event, so a written impulse is slowed like the user's.

Co-steer (`YawCoSteerTest`): see the `YawCoSteer` javadoc. Rate `min(maxDegPerSec, 450) * authority`, acceleration
1500 deg/s^2 * authority, critically damped tail (omega 8/s * authority), discrete-time braking so it stops on the
ideal or at the end of `maxTotalDeg`, never past the ideal, never pushes back when the hand carried the camera past it,
added part hard-capped at 450 x min(frame time, 0.1 s). Zero when: a screen is open, the player rides or spectates,
`InputSmoother.isAutomatedLookRunning()`, automation stopped (the grant ended), the grant was begun without
`wantsYaw`, or the legit level was LEGIT when the grant began (`LegitPresets.current(profile, global) == LEGIT`, read
once per `begin` because `current()` walks every knob; a level change mid-grant applies from the next grant). Lapses
when `coSteer` was not called during the previous tick (frames of the tick after the call still steer, like turn
gain's ttl 1). `opposingYawDeg()` sums raw hand degrees whose sign opposes the follower's direction (its speed, or the
error when at rest) since `begin`.

Order with turn gain (documented in `ManualInputHooks.onTurnYaw`): raw hand yaw -> facts; turn gain scales the hand's
motion; the co-steer adds its step computed from the GAINED hand motion (a hand already turning toward the ideal
leaves it less to add); the co-steer's own part is never scaled by the gain. The hand's motion always passes through.

## For the next volley (wiring MovementAssist onto the grant)

1. `SharedGrant g = Services.sharedControl().begin(ID, ControlPriority.TRAVEL, wantsYaw)` at commitment start
   (ARMED/TAKEOFF); null means stand down this jump. Keep the reference, check `g.isActive()` every tick, call
   `g.end()` on landing/abort. Module id must be a constant string.
2. Move the `Input` writes in `MovementAssist.onMovementInput` (forward/strafe/withholdJump/sneak) to
   `g.axes/jump/sneak` and replace the direct `p.setSprinting(true)` (MovementAssist line ~292, flagged in the run
   note) with `g.startSprint()`. The module's listener runs at NORMAL, the overlay at LOW in the same event, so writes
   made there apply in the same tick. Writes must be repeated every tick (they lapse).
3. Read `Services.sharedControl().manual()` for the OverrideClassifier: `heldSince(key, takeoffTick)` for stale keys,
   `heldTicks` for the 3-tick axis override, `lastPressTick/lastReleaseTick` for bounce, `handYawDegLastTick()` for
   the mouse rules, `g.opposingYawDeg()` for the 6-degree yaw override. Stamp the takeoff tick with
   `manual().tick()`.
4. `g.coSteer(ideal, maxDegPerSec, maxTotalDeg, authority)` every tick while steering (NATURAL clamp of the ideal is
   the module's job). Stop calling it (or call with authority ramping down) to hand back.
5. Keep the module's own `setYawGain` request if wanted: it stacks as described above.
6. TerrainAssist pre-scales impulses for item use at NORMAL; a grant `axes` write at LOW replaces that pre-scale for
   the written axis. Decide whether the parkour path should account for it (rare: item use during a jump).
7. Freecam (`world.EntityTurnFreecamMixin` redirects `Entity.turn`): with a live co-steer the step would move the
   freecam like hand motion does. The module should not ask for yaw while freecam is on.

## Open points / contract remarks

- No signature problems in `core.api`. One remark: `SharedGrant` has no way to tell the module WHY it ended; the
  overlay exposes `lastEndReason()` on the concrete class if the HUD needs it.
- A key physically held through a screen counts as up until pressed again (no hardware event fires on close; vanilla
  re-reads the key state, we deliberately do not read `KeyMapping` state).

## In-game checks left for the owner

- Grant survives W/A/S/D presses during a commitment (only the module's classifier decides); a normal MOVEMENT lease
  still ends on a key press.
- Co-steer feels smooth at 60 and 240 fps (and 30, 144); no step when it starts or stops; a still mouse still turns.
- LEGIT level: no camera motion at all from the co-steer.
- Stop-All drops the grant at once (keys back to the user's, co-steer stops mid-turn).
- Opening chat/inventory mid-jump ends the grant; keys held through the screen are not "held" afterwards.
- Sprint start via the grant: does not start while eating, blind, hungry (<= 6), against a wall, in surface water.
- Mixin audit (`-Dutilitysuite.mixinAudit=true`): `MouseHandlerTurnGainMixin` still applies (unchanged).
