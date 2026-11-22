# Pearl intercept (volley 2026-11-22)

Two units so the Combat Assistant can already be running to where a fleeing target's ender pearl will land.

- `combat.tactics.PearlLandingSim` (engine free, allocation free). `simulate(x, y, z, vx, vy, vz, CombatVoxels, maxTicks)`
  returns true on impact; then `landX()/landY()/landZ()` and `flightTicks()`.
- `combat.assistant.mc.PearlWatch implements PearlSource` (client thread). Tracks `ThrownEnderpearl` from entity
  join/leave events in a 16-slot array (oldest overwritten); `pearlOf(id, out6)` fills position + velocity.

## Model

Per tick: move by velocity, then `v *= 0.99` (0.8 when the pearl's cell at the start of the tick is water), then
`vy -= 0.03` (same order as `world.overlays.TrajectoryIntegrator` THROWN; not imported). The segment is sampled in
sub-steps of at most 0.25 block. The first cell that is not AIR/WATER ends the flight: UNKNOWN and LAVA return false
(never route into unloaded terrain or lava), as does falling below y = -80 or exceeding `min(maxTicks, MAX_TICKS=200)`.
Landing = impact point backed off 0.3 along the incoming direction; when the pearl came down into the cell from above
(`vy < 0` and the previous sample was in a higher cell) landY is the block top (`cellY + 1`). HARM/COBWEB/SLOW cells
count as hits (they are blocks; the caller decides whether the landing is acceptable).

## Wiring (combat-wiring task)

1. Register `PearlWatch` on the NeoForge bus: `EntityJoinLevelEvent -> onJoin`, `EntityLeaveLevelEvent -> onLeave`,
   and call `clear()` on logout / world unload. Hand it to the assistant as its `PearlSource`.
2. Each engaged tick (target alive, fight on): `double[] p = new double[6]` (a field, allocated once);
   `if (pearls.pearlOf(target.getId(), p))` then
   `if (sim.simulate(p[0], p[1], p[2], p[3], p[4], p[5], arenaVoxels, PearlLandingSim.MAX_TICKS))` the landing is
   `(sim.landX(), sim.landY(), sim.landZ())` after `sim.flightTicks()` ticks. Keep one `PearlLandingSim` as a field.
   Re-simulate every tick (cheap, under 1 us measured) and re-issue `IRouteRunner.pursue` only when the landing moved
   more than about 1 block, to avoid route churn. A false `simulate` (unloaded, lava, void, nothing hit) means no
   intercept: fall back to normal chase of the target's last position.
3. Stop pursuing the landing when `pearlOf` returns false (pearl gone) AND the target has teleported (its position
   jumped more than about 4 blocks since the last tick, or it is now near the landing point); then resume normal
   targeting of the entity itself. Also stop when the pearl is gone and the target did NOT move (pearl was a miss or
   hit someone else), and after `flightTicks + 20` ticks as a hard timeout.
4. Do not treat pearls from other players as the target's: `pearlOf` only returns pearls whose owner id equals the
   target id (or, owner unset, whose spawn was within 2.5 blocks of that entity's eye at join).

## Left for playtest

- `PearlWatch` cannot be unit-tested: client-side owner sync (null for the first tick, or forever on some servers),
  first-tick velocity (the pearl may join with zero delta movement before the first motion packet; the wiring should
  ignore a result whose speed is 0).
- Landing accuracy against the real teleport position (the server places the thrower at the impact point with its own
  collision rules, so expect up to about a block of error near edges).
- Pearls thrown by other players near the target (the 2.5-block spawn fallback can pick the wrong pearl in a crowd).
