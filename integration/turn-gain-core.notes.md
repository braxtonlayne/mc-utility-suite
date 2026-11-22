# turn-gain-core: turn gain on the user's own mouse

Implements `core.api.IManualLookAssist` as `core.input.ManualLookAssist`, registered in `CoreBootstrap` with
`Services.registerManualLook(...)`. "Turn gain, not turn injection": the core only rescales the yaw delta the
player's hand produced this frame; it never writes a rotation.

MIXIN: MouseHandlerTurnGainMixin (listed under `client` in `utilitysuite.mixins.json`)

## Files

- `core/input/ManualLookAssist.java`: pure math (`static scale(...)`), the request table, per-frame `onFrameYaw`,
  per-tick `beginTick` (ttl and read-outs), `clearAll`.
- `core/input/ManualInputHooks.java`: `onTurnFrame(movementTime)` and `onTurnYaw(yawArg)` static hooks (turn units
  <-> degrees, screen/player gating); `beginTick` also ticks the assist. Manual-override detection (`onMouseTurn`,
  fed from `MouseHandlerManualLookMixin` at HEAD with the RAW accumulated deltas) is unchanged.
- `core/input/InputSmoother.java`: package-level `isAutomatedLookRunning()` (VISIBLE rotation moving, SILENT active,
  or momentum release decaying); `setManualLookAssist`; `releaseAll()` now also calls `ManualLookAssist.clearAll()`.
  `releaseAll` is what `ActionArbiter.stopAll` and `ActionArbiter.resetForWorldUnload` call, and `CoreBootstrap.onLogout`
  calls it again, so Stop-All and logout/unload both clear every request.
- `core/CoreBootstrap.java`: creates, wires and registers the assist.
- `mixin/MouseHandlerTurnGainMixin.java`: HEAD inject on `turnPlayer(D)V` (records the frame time) + `@ModifyArg`
  index 0 on the single `LocalPlayer.turn(DD)V` call in `turnPlayer`.

## javap evidence (NeoForge 21.1.265, `build/moddev/artifacts/neoforge-21.1.265.jar`, Mojang names)

`javap -c -p net/minecraft/client/MouseHandler.class`:

```
  private void turnPlayer(double);
    Code:
       ...
      29: invokestatic  #366  // Method net/neoforged/neoforge/client/ClientHooks.getTurnPlayerValues:(DZ)Lnet/neoforged/neoforge/client/event/CalculatePlayerTurnEvent;
      32: astore_3
      33: aload_3
      34: invokevirtual #370  // Method net/neoforged/neoforge/client/event/CalculatePlayerTurnEvent.getMouseSensitivity:()D
      37: ldc2_w        #375  // double 0.6000000238418579d
      40: dmul
      41: ldc2_w        #377  // double 0.20000000298023224d
      44: dadd
       ...                    (d4 = s*0.6+0.2; d6 = d4^3; d8 = d6*8)
      66: invokevirtual #381  // Method .../CalculatePlayerTurnEvent.getCinematicCameraEnabled:()Z
      69: ifeq          123
       ...                    cinematic: SmoothDouble.getNewDeltaValue(accumulatedDX*d8, movementTime*d8) for x and y
     123: ... Options.getCameraType ... CameraType.isFirstPerson ... LocalPlayer.isScoping
       ...                    spyglass: accumulatedDX*d6, accumulatedDY*d6 (smoothTurnX/Y reset)
     187: ...                 normal: accumulatedDX*d8, accumulatedDY*d8 (smoothTurnX/Y reset)
     219: iconst_1
     220: istore        14
     229: invokevirtual #403  // Method net/minecraft/client/Options.invertYMouse:()Lnet/minecraft/client/OptionInstance;
     241: ifeq          247
     244: iconst_m1
     245: istore        14    (invert factor: -1 when inverted)
     258: invokevirtual #410  // Method net/minecraft/client/tutorial/Tutorial.onMouse:(DD)V
     265: getfield      #191  // Field net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;
     268: ifnull        289
     278: dload         10    (yaw delta, turn units)
     280: dload         12
     282: iload         14
     284: i2d
     285: dmul                (invert factor multiplies the PITCH argument only)
     286: invokevirtual #416  // Method net/minecraft/client/player/LocalPlayer.turn:(DD)V
     289: return
```

Exactly one `LocalPlayer.turn(DD)V` call in `turnPlayer`, so the `@ModifyArg` target
`Lnet/minecraft/client/player/LocalPlayer;turn(DD)V` (owner as in the bytecode, LocalPlayer) with `index = 0` hits the
yaw argument (local 10) and never the pitch (local 12 x invert). It sits after every branch (cinematic, spyglass,
normal), so all three are covered with the same code. The yaw argument is not touched by the invert factor.

Caller (`handleAccumulatedMovement()V`): `movementTime = Blaze3D.getTime() - lastHandleMovementTime` (seconds),
`turnPlayer` only runs when `isMouseGrabbed()` and `minecraft.player != null`, and `accumulatedDX/DY` are zeroed
after it.

```
     265: aload_0
     266: invokevirtual #354  // Method isMouseGrabbed:()Z
     269: ifeq          287
     276: getfield      #191  // Field net/minecraft/client/Minecraft.player:Lnet/minecraft/client/player/LocalPlayer;
     279: ifnull        287
     283: dload_3
     284: invokevirtual #357  // Method turnPlayer:(D)V
     289: putfield      #335  // Field accumulatedDX:D   (dconst_0)
     294: putfield      #338  // Field accumulatedDY:D   (dconst_0)
```

`javap -c -p net/minecraft/world/entity/Entity.class`, `turn(double, double)`: yaw `(float)d0 * 0.15f` added via
`setYRot`, pitch `(float)d1 * 0.15f` via `setXRot` then clamped to [-90, 90]. Hence 1 turn unit = 0.15 deg
(`ManualLookAssist.DEG_PER_TURN_UNIT`).

Why no event: `CalculatePlayerTurnEvent` (fired by `ClientHooks.getTurnPlayerValues` at offset 29) only carries
mouse sensitivity and the cinematic flag; changing sensitivity scales yaw AND pitch and cannot depend on the turn
direction. Nothing else fires between the delta and `Entity.turn`.

## Math (`ManualLookAssist.scale`)

1. `e = wrapDegrees(ideal - yaw)`; `|e| <= 0.5 deg` (dead zone) -> raw.
2. Toward = sign(raw) == sign(e): toward gain, else away gain; both clamped to [0.5, 1.5] (NaN gain = 1).
3. Fade: `gain = 1 + (g - 1) * min(1, (|e| - 0.5) / (10 - 0.5))` (both directions), so the gain is 1 at the dead zone
   edge and full from 10 deg out.
4. `change = |raw| * (gain - 1)` (degrees added or removed along the motion).
5. No overshoot: toward with change > 0 -> `change <= max(0, |e| - |raw|)`: the gain at most carries the camera to
   the ideal yaw; a raw delta that already reaches or crosses it is not amplified.
6. Cap: `|change| <= 450 deg/s * min(frameSeconds, 0.1)` (`RotationLimits.MAX_DEG_PER_SEC`).
7. `out = sign(raw) * (|raw| + change)`.

Guarantees (tested with a 200k random-input property loop, extreme deltas and dt up to 1 s): `sign(out) == sign(raw)`,
`|out - raw| <= cap`, `0.5|raw| <= |out| <= 1.5|raw|`, zero in -> zero out exactly, NaN/infinite/<=0 frame time or
non-finite yaw/ideal -> raw passes through.

## Limits and gating

- Not applied (raw passes, read-outs still count) while: a screen is open, the player is missing/spectating/riding
  (`isPassenger`), the smoother runs a VISIBLE rotation, a SILENT rotation is active, or momentum release decays.
- Requests: fixed table of 8 (module id compared by reference then `equals`, no allocation); latest set live request
  applies; table overflow replaces the oldest; `ttl <= 0` clears; ttl counts down in `ManualInputHooks.beginTick`
  (ClientTickEvent.Pre). A module that sets ttl 1 each Post keeps it alive, since frames never run between a tick's
  Pre and Post.
- Read-outs roll over at ClientTickEvent.Pre: `manualYawRateDegPerTick()` = signed after-gain hardware yaw summed
  over the last completed tick; `assistedYawDegLastTick()` = summed |out - raw|. `clearAll` zeroes them.
- Hot path: `onTurnYaw` returns at once for a zero yaw argument; with no live request it does two multiplies and
  two adds (read-outs) and returns the same double. Allocation test: 1M calls with and without a live request
  (`ManualLookAssistAllocationTest`).

## Tests

`ManualLookAssistTest` (20 tests) and `ManualLookAssistAllocationTest` (2). Full `./gradlew compileJava test` green.
Static input-rule grep: only the pre-existing read-only `inv.selected ==` reads in travel/movement hit outside
core/input and mixin (allowed); nothing new.

## Left for the in-game playtest

- Mixin audit at startup (`runClient` with `-Dutilitysuite.mixinAudit=true`): MouseHandlerTurnGainMixin must apply.
- Feel at low/high sensitivity and at 30/60/144/240+ fps; raw input on and off.
- Smooth camera (cinematic) and spyglass: the gain scales the already-smoothed yaw per frame.
- Invert mouse: pitch inverted as before, yaw unaffected by the invert factor.
- Nothing moves with a still mouse (including cinematic tail with no request vs with a request).
- Manual-override detection unchanged (threshold, MOUSE_LOOK report, momentum hand-over).
- Interplay with camera momentum release (gain off while momentum decays).
- Third person and freelook/freecam (`world.EntityTurnFreecamMixin` redirects `Entity.turn` downstream; the gain
  would also scale the freecam's yaw if a request is live while freecam is on: decide whether the movement assist
  should clear its request then).
- Riding (boats/horses) and spectator: gain must be off.
