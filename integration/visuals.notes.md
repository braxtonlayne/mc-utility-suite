# Visuals: The Read ring and Ghost Line

Both modules are off by default, have no mixins, draw in `RenderLevelStageEvent` (AFTER_PARTICLES) with one batched
`RenderType.lines()` buffer, and draw nothing when idle or in Clean-Screen.

## Wiring
- `ghost_line`: registered in `TravelBootstrap` (module + render listener), done.
- `the_read`: `ReadRingModule` is filled in; the combat-wiring task still registers it and its `onRenderLevel` listener
  in `CombatBootstrap` (not touched here).
- No lang or mixin fragments. Setting labels are inline.
- Read-only route accessors added: `RouteRunnerCore.followingRoute()`, `MinecraftRouteRunner.isFollowingRoute()`,
  `copyRoute(long[])` (once per tick) and `routeIndex()`. `Steering` and `MovementAssist` were not edited.

## Behaviour notes
- The Read: ring 0.25 above the target's box top at its render-interpolated position; dim full outline plus a bright
  arc over `charge` (red to yellow to green as it fills; whiter and fully opaque while `steppingIn`). Tilt up to 25
  degrees; the side the strafe points to dips. Settings: radius, opacity, show predicted strafe.
- Ghost Line: route mode puts the ghost at max(3, speed x lead ticks) blocks of horizontal arc length along the route
  (clamped to its end); otherwise, with Movement Assist on and a predicted landing while moving or airborne, the ghost
  stands at the landing and a parabola arc (apex 1.0 above the chord midpoint) is drawn. Hidden under 1.5 blocks.
  Settings: lead time 0.5-2.0 s, opacity, trail. The jump arc is always drawn; the trail setting only governs the route
  trail.

## Playtest list (left for the owner; nothing visual was run)
- Colours, line thickness and opacity of both overlays; whether the dim outline reads against bright sky and grass.
- Depth test through walls (RenderType.lines is depth tested; decide if the ring should show through blocks).
- Sodium and Iris compatibility of the line buffers.
- Whether the ghost reads as "follow yourself" and the 1 s lead feels right at walk, sprint and sprint-jump speeds.
- Whether the tilt direction (dip toward the strafe) reads as "going that way".
- Ghost box on stairs and slabs (route cell y is the block y, so it may float or sink by a fraction).
- First-person: the 1.5 block hide rule and the trail start (follows the interpolated player position).
- Frame cost in Spark with both overlays on; the mixin audit is unaffected.
