# Route runner (pursuit / flee) notes

Package `com.utilitysuite.travel.pursuit`; registered in `TravelBootstrap` via `Services.registerRouteRunner`.

## Design
- `RouteRunnerCore`: engine-free state machine (IDLE, PLANNING, RUNNING, ARRIVED, FAILED). Collaborators: `BodySource`,
  `Steering` (RouteFollower-shaped), `LocalFlee`, plus `IInputSmoother` / `IRoutePlanner` bound late via `bind(...)`.
- `MinecraftRouteRunner`: fills a `MovementPhysics` + `LevelVoxels` from the LocalPlayer (like `MovementAssist.fillNow`,
  but with a mutable BlockPos), adapts `RouteFollower` and `EscapePlanner`, ticks the core from `ClientTickEvent.Post`
  (returns at once when idle/arrived/failed).
- Planner use: pursuit asks `planTo(target, 1)` only when horizontal distance > 12; flee asks `planAway` only when the
  distance still to gain (`minDistance - distance`) > 12. Otherwise never. While the plan is pending the run walks a
  local plan (pursuit: 2-cell direct route; flee: `EscapePlanner`, threat speed 0.25, assumed able to parkour). A held
  planner route keeps being followed while a refresh is pending.
- Replan throttle: at least 10 ticks apart; reasons: no plan yet, `offRoute()`, `finished()`, target drift > 3 blocks
  since the last plan, 30-tick cadence. A READY planner route (new `planSerial`) is taken immediately.
- Keys: cached per `SimKey`, `setKeyHeld` only on change; a refused call (lease lost) ends the run (FAILED).
  FAILED/ARRIVED/stop/unload release keys + cancel plan. Camera: `RotationTarget.visible(wantYaw, 0)` only when
  the wanted yaw moved > 4 deg (the record allocation is the only one, off the every-tick path). Rotation is cleared on
  stop. Caps (450 deg/s) stay in the core.
- ARRIVED resumes when the next call finds the target beyond range + 0.5 (pursuit) / inside range - 0.5 (flee).
- A new lease or mode ends the previous run (keys released with the old lease).

## Depends on the RouteFollower task
Only the signatures on main: `setRoute/clear/hasRoute/update(MovementPhysics,yaw,health,Voxels)/forward/strafe/jump/
sprint/wantYaw/finished/offRoute`. With the stub, the follower gives no keys, so the run holds nothing until filled in.
The follower is expected to treat a 2-cell route as "walk straight to the second cell if reachable" and to signal
`offRoute()` when no corridor fits; `finished()` triggers a replan, so a follower that reports finished at the route
end makes pursuit re-plan every 10 ticks near the target.

## Playtest list
- Pursue a moving player/mob across terrain; hand-over between the Baritone route and the local plan at 12 blocks.
- Flee from a mob (local plan and planner `planAway`); behaviour when Baritone is absent (local plan only).
- Camera turning along the route: no snap, no fight with the combat aim (look off while combat aims).
- Manual key/mouse input and Stop-All end the run; logout resets.
- Replan frequency in practice (10/30 tick tuning), `finished()` replan loop at the stop range.
