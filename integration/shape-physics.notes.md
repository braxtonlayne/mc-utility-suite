# shape-physics (volley 2026-11-22)

Exact collision shapes and the 7.3 replica rules in `MovementPhysics`, a real `LevelShapes`, shaped landings in
`ParkourCourse`. Design: `docs/design/parkour-control-model-2026-11-22.md` sections 1, 5.2, 7.2, 7.3.

## Contract kept

- `MovementPhysics.Voxels` (`shaped`, `boxCount`, `box`, `surface`, `slipperiness`), the `SURFACE_*` bytes, the
  seven-argument `tick(forward, strafe, yaw, sprint, jump, sneak, w)` and `LevelShapes.begin(Level)` are unchanged in
  signature. `LevelVoxels`, `WorldBodySims` and `MinecraftRouteRunner` are untouched.
- New public state on `MovementPhysics` (copied by `copyFrom`, not reset by `set`): `crouching`, `jumpDelay`,
  `sprintBlocked`, `sprinting`. Constants `CROUCH_HEIGHT`, `SNEAK_INPUT_SCALE`, `STEP_HEIGHT` (0.6f),
  `JUMP_DELAY_TICKS`. When building the "now" body from the real player, the integrator should set
  `crouching = player.isCrouching()` and, if available, `sprinting = player.isSprinting()`.
- `PhysicsBodySim` gained a non-interface `tick(..., sneak)` overload; `set` resets the new state. `core.api.BodySim`
  is untouched.

## Two paths in `MovementPhysics.tick`

- **Cube path**: grid not `shaped()`, no sneak, not crouched. Bit-identical to the replica on main at 0e29eea
  (`MovementPhysicsRulesTest.cubeGridsGiveBitIdenticalResults` runs 12 seeded random-input walks of 1500 ticks against
  a frozen copy, `LegacyCubePhysics`). All existing benchmarks and sim-tick bounds pass untouched.
- **Full path**: everything else. All 7.3 rules below apply only here. So a planner on `LevelShapes` (always
  `shaped()`) gets the full rules; a hand-built cube grid keeps the old numbers unless the body sneaks.

## Modelled (full path)

- Exact boxes for clipX/Y/Z, `supported` and standing; one cell below the body range is scanned for boxes that rise
  above their cell (fence and wall 1.5). One preallocated `float[6]` scratch and a `float[16]` step-height buffer per
  instance; no allocation per tick (`LevelShapesAllocationTest.shapedTickAllocatesNothing`).
- Vanilla axis order (`Entity.collideWithShapes`): Y, then Z before X when |dz| > |dx|. The cube path keeps X then Z.
- `Mth.equal` (1e-5) for the wall-contact flags; moves shorter than sqrt(1e-7) are not applied (`Entity.move`).
- Step-up 0.6 (`Entity.collide`, `collectCandidateStepUpHeights`): when on the ground or landing this move and blocked
  horizontally, candidate heights are the distinct box Y bounds in [0, 0.6] of shapes touching the expanded box,
  tried in ascending order; the first that gets further horizontally wins. Slabs, stairs and walking onto a fence from
  the block beside it work.
- Sneak (`Player.maybeBackOffFromEdge`, `isAboveGround`, `canFallAtLeast`), with the real shapes, step height 0.6f and
  vanilla's 0.05 back-off steps; only when vy <= 0 and the body is "above ground": `onGround` at the start of the
  move, or `fallDistance < 0.6` with ground within `0.6 - fallDistance`. So the touchdown tick of a normal jump is not
  guarded. The back-off shortens the move, not the velocity (vanilla keeps `deltaMovement` unless a wall is hit).
- Sneak input scale 0.3 and the 1.5-high hitbox: one tick after the key, because `LocalPlayer.aiStep` computes
  `crouching` from `input.shiftKeyDown` (last tick's value) before `input.tick`, and `Player.tick` runs
  `updatePlayerPose` after `aiStep`. The body stays crouched while standing up would collide.
- Sprint loss (`LocalPlayer.aiStep`, `isHorizontalCollisionMinor`): a wall contact whose move is more than 8 degrees
  (0.13962634 rad) off the input direction stops the sprint on the next tick; with the sprint input still on it comes
  back the tick after if that tick had no hard contact. Restart needs forward impulse >= 0.8, so a crouched body does
  not restart a lost sprint. The tick that first reaches a wall straight on is a minor contact (it still moved along
  the input); the next push stops the sprint.
- Slipperiness from `Voxels.slipperiness` at `getOnPos(0.500001)` under the main supporting block (nearest block whose
  box touches the 1e-6 slab under the feet), read every ground tick. On a bottom slab this is the block under the
  slab, like vanilla.
- Jump delay (`LivingEntity.aiStep`, `noJumpDelay`): counts down first, a jump sets 10, a released key clears it.

## Not modelled (next volley)

Slime bounce and its sneak exception, honey and soul sand speed/jump factors (`getBlockSpeedFactor`,
`getBlockJumpFactor`), climbables (0.15 clamp, descent clamp, sneak hold, climb on contact/jump), water as a landing
and swimming, cobweb and powder snow `makeStuckInBlock`, block-specific `updateEntityAfterFallOn`/`stepOn`, the
`Mth.sin` lookup table (replica uses `Math.sin`; error under 1e-5 per tick), float rounding of vanilla's
`getFrictionInfluencedSpeed` and `0.42F` (about 1e-8), entity collisions, the world border, `moveTowardsClosestSpace`.

## LevelShapes

- Session table per `BlockState` (first sight only, the only allocation): shape id (`short`: 0 none, 1 full cube via
  `Block.isShapeFullBlock`, others a `float[6n]` from `VoxelShape.toAabbs()`; identical `VoxelShape` instances share
  one id), surface byte, `Block.getFriction()`. Tick cache `Long2IntOpenHashMap` cell -> entry, cleared by `begin`.
- Surface: UNMODELLED for `hasOffsetFunction()`, `hasDynamicShape()`, scaffolding and powder snow (the brief puts
  powder snow under UNMODELLED; the design listed it under STUCK; both are never committed to); HARM for lava, fire
  tag, cactus, sweet berry bush, magma, wither rose, campfires; STUCK for cobweb; CLIMBABLE for the climbable tag;
  WATER for any water fluid (waterlogged blocks included); SLIME, HONEY; SOUL_SAND for any block with speed or jump
  factor under 1; ICE for any friction other than 0.6; else NORMAL (NONE when it has no collision).
- Test seam (package-private): `begin(CellSource)` and `defineEntry(...)`; `LevelShapesAllocationTest` checks the
  steady state allocates nothing.

## Vanilla facts verified in the 1.21.1 sources (NeoForge 21.1.265 sources jar, Mojang names)

- `Entity.move`: `maybeBackOffFromEdge` runs before `collide`; `horizontalCollision` uses `Mth.equal`; velocity is
  zeroed per axis only on a wall hit; `verticalCollisionBelow` sets onGround.
- `Player.maybeBackOffFromEdge`: needs `!abilities.flying`, `vec.y <= 0`, MoverType SELF/PLAYER,
  `isStayingOnGroundSurface()` (= `isShiftKeyDown()`) and `isAboveGround(maxUpStep())`.
- `LocalPlayer.isShiftKeyDown()` returns `input.shiftKeyDown` directly. `LocalPlayer.aiStep` fires
  `ClientHooks.onMovementInputUpdate` (`MovementInputUpdateEvent`) right after `input.tick(...)` and before
  `super.aiStep()` (travel and move). **So `shiftKeyDown = true` set in `MovementInputUpdateEvent` gives the edge
  back-off on that same tick.** The 0.3 input scale and the crouched hitbox follow one tick later (see above). The
  server sees the same key through `ServerboundPlayerInputPacket` sent in `LocalPlayer.tick`.
- `LivingEntity.maxUpStep()` reads `Attributes.STEP_HEIGHT` (0.6 for players); the Forge comment keeps
  `maybeBackOffFromEdge` on `maxUpStep()`.
- Collision boxes: `FenceBlock` = `CrossCollisionBlock(2, 2, 16, 16, 24)` -> post and arms 6..10, 24 high;
  `IronBarsBlock` (panes) = `(1, 1, 16, 16, 16)` -> 7..9, 16 high; `WallBlock` collision = `makeShapes(4, 3, 24, 0,
  24, 24)` -> post 4..12, sides 5..11, 24 high, no post on a straight run.

## For the integrator

- Assist wiring: switch the assist's planner grid from `LevelVoxels` to `LevelShapes` (one `begin(level)` per tick)
  and feed `sneak` to the seven-argument tick. Expect planner sim costs a little above the cube path on shaped grids
  (box lookups instead of `solid`); the sim-tick bounds are counts, not time, so they are unaffected.
- `ParkourCourse.EDGE_CASES` (run each with `ParkourCourse.edgeCase(i)` as a one-segment course): 2 gap onto a fence
  post, 2 gap onto a fence row, 3 gap onto a wall, corner onto a block, corner onto a fence, 1 gap onto a pane, slab
  up. `STANDARD` is unchanged and stays a cube grid. `padTop(pad)`, `padLanding(pad)` and `boxLanding(i)` let the
  playtest build fences, walls, panes and slabs instead of stone for shaped pads (ParkourScenario still /fills stone).
- Benchmark assertions that need the landing hold (other task): on every EDGE_CASES course with the sloppy runner
  that holds W through the landing, zero walk-offs after a landing that was made (NUDGE), at least 95 % clear rate on
  the shaped and corner cases (GUIDE). No such assertion was added here.
- In-game parity run (ParkourScenario, not by build agents): replica vs real body under 0.001 block per flight on
  fence, wall, pane and bottom-slab landings, and on the slab step-up; sneak back-off on a fence post (forward held
  100 ticks, the real body and the replica both stay on the top with the same final position within 0.001); the
  touchdown tick of a landing past the post edge falls in both; sprint loss after a straight wall hit (sprint off on
  the second contact tick, back one tick after leaving the wall); jump delay under a 2-block ceiling (jumps at ticks
  0, 10, 20 with jump held). Any class that fails is switched to UNMODELLED until fixed.
