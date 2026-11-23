# Unified sim: Fable's recommendation (2026-11-23)

The owner asked for one unified sim for every subsystem (threat vectors, escape paths, edge detection, parkour
intent). Fable read the code (read-only) and judged three options: migrate to a new system, keep building, or
something else.

**Verdict: a hybrid.** Unify the inputs: one world view, one kernel configuration, one sim budget and one baseline
rollout. Do NOT build a per-tick "forecast everything" layer, and do NOT rewrite the planners as queries over it.

## Findings (checked by Claude on 2026-11-23 where marked)
- **The kernel is already shared.** Every body sim is `MovementPhysics`; combat reaches it through `BodySim` ->
  `PhysicsBodySim` -> `WorldBodySims`.
- **The real split is the world view, and it changes behaviour** (checked):
  - `WorldBodySims` wraps `LevelVoxels`, the full-cube view, and `MinecraftRouteRunner` (pursuit) does the same.
    `MovementPhysics.tick` takes the cube path whenever the view is not `shaped()`.
  - So `HitTradeSolver`, `ComboKeeper` and pursuit simulate slabs, fences, panes and stairs as cubes, with no step-up
    and no surface slipperiness. Movement Assist uses `LevelShapes`, the exact shapes.
  - `BodySim.set` carries no speed attribute or jump boost, so a Speed II opponent is mispredicted.
- **Four hazard and edge readers can disagree:** `MinecraftCombatWorld.isHazard`, `ArenaField.scan`, the hit-trade
  fall check, and `LevelTerrainView` (SafeWalk).
- `EscapePlanner` uses hard-coded jump limits that the kernel never checks.
- There are two projectile integrators with duplicated constants: `PearlLandingSim` and `TrajectoryIntegrator`.
- Sim budgets are per class, not global. The brain rolls the user-keys trajectory several times per decision, and
  `BlockAssistPlanner` and `FallClutchPlanner` roll it again.
- **The open parkour bugs are not caused by the split.** Held press and tick phase, the old technique families'
  view model, and replica vs game drift all live inside single classes. Unifying would not fix them.

## Steps, each useful on its own
1. **One world view per tick.** `WorldBodySims` serves the same `LevelShapes` instance Movement Assist uses, and
   `BodySim` seeding gains speed, jump boost and sprint state. This puts combat and pursuit on exact physics. Combat
   tuning will shift, so put it behind a flag and A/B it in the arena.
2. **Block kinds in the same view.** The `LevelShapes` session table answers `CombatVoxels.kind`, and the hazard
   directions and the SafeWalk edge come from it. That gives one answer to "is that a drop or a hazard", from one
   read per cell per tick.
3. **A shared tick ledger plus a baseline rollout.** One global sim-tick budget, and one cached "body under the
   current keys" trajectory that the brain, `BlockAssistPlanner` and `FallClutchPlanner` all read.

## Other ideas
- **Always-on replica drift recorder** (dev runs): log the first tick where the one-step prediction and the real
  body diverge. Cheap, and it turns "replica vs game" into concrete cases.
- **Tick-phase sweep harness** for every lane replica (test-only).
- **Threat tracks as a small shared service:** entity and projectile predicted paths, one integrator, fixed arrays.
  Build it when the hunter bot needs it.
- **Kernel-verified escape edges:** check `EscapePlanner`'s chosen route with `JumpReach` or the kernel.

## Not to do
- Rewrite `JumpSearch`, `IntentEstimator` or `TechniqueRoller` while combat is mid-tuning and lanes are marginal.
- Compute a reachability graph or danger field every tick.
- Delete the cube path before the benchmark and parity tests are re-pinned.

## Open questions for the owner
1. Is a one-time combat re-tune OK when combat moves to exact shapes, or should the cube path stay selectable?
2. Does the hunter bot run in this client (same `Level`), or does it need an engine-free snapshot that can be used
   off-thread?
3. Should the benchmark numbers stay pinned to the cube path, or be re-baselined on full physics?
4. Do you want a hard global sim-tick cap per client tick, and if so what number?
