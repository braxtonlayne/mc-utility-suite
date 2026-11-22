# Replica parity (2026-11-22)

Finding from the playtest: every control-model lane of `ParkourScenario` reported `replica error flight ~0.30, after
touchdown 0.000`. Result: the 0.30 was the measurement, not the physics. The replica also had float-level
differences from vanilla, now fixed on the full path (the in-game path). Nothing here has been run in game yet.

## 1. The measurement (ParkourScenario.laneTick)

Lane steps run on `ClientTickEvent.Post` at LOWEST priority (`Harness.register`), right after the player tick.

| Point | What was wrong | Fix |
|---|---|---|
| (a) seed | `sim.set(x, y, z, 0, 0, 0, onGround)` on the first lane tick. W + sprint were pressed in the start step, so the body had already moved and had velocity (and the resting vy is -0.0784, not 0). The replica's velocity series ran one tick behind, so its position lagged by about one tick of travel: sprint steady speed is 0.2806 block/tick on the ground, more in the air. **That is the ~0.30.** It never re-synced. | Seeded with `getDeltaMovement()`, onGround, fallDistance, sprint, wall-contact flags and pose. |
| (b) `maxFlight` | It covered everything before `landedTick`, ground run-up included. | Separate maxima: ground run, flight, after touchdown. |
| (c) `after touchdown 0.000` | In the lanes that fell, `landedTick` was never set, so nothing was measured. | Prints `not measured (no touchdown)`. |
| (d) inputs | `in.up/down/left/right` flags; `p.getYRot()` after the tick. `jumping`, `shiftKeyDown` and `isSprinting()` read after the tick ARE the values the tick used: `LocalPlayer.aiStep` runs `input.tick` + `MovementInputUpdateEvent`, then sprint start/stop, all before `super.aiStep()` (jump, travel), and nothing later in the tick writes them. The yaw is not safe: a later Post handler or a render frame can turn the camera (`setYRot`). | Uses `input.forwardImpulse/leftImpulse` (what moved the body) and `yRotO`, which `LivingEntity.tick` sets to the yaw after `aiStep` and which `setYRot` leaves alone. |
| (e) event | Post, LOWEST: after the player tick. Keys pressed in a step reach the next tick (KeyboardInput reads them at the start of `LocalPlayer.aiStep`). | Documented on `parityTick`. |

New per-lane measurement (`parityTick`):
- drift replica seeded from the real state at the first lane tick; ground-run error until takeoff;
- at takeoff (real onGround -> airborne) it is re-seeded from the real state before that tick and stepped through
  it, so the flight error is the flight's alone; it then continues after touchdown (post-touchdown error);
- one-step replica: the real state of the previous tick stepped once with this tick's inputs. This isolates rule
  errors from drift; expected about 1e-6 or less. The first tick above 1e-4 is printed with its inputs, the state
  before, and the real and replica states after. noJumpDelay (private in `LivingEntity`) is rebuilt from the real
  inputs with the vanilla rule.
- each lane's result note now includes: takeoff lane tick (and the tick the jump key went down), takeoff z, x
  offset from the post centre, takeoff speed (block/tick of the takeoff move), sprint and jump on the takeoff tick,
  touchdown flight tick, z and x offset, and the `JumpCommit` phase per flight tick (run-length coded, for example
  `1 TAKEOFF, 2-9 FLIGHT, 10 TOUCHDOWN`).
- the parity report lists ground run, flight, after touchdown and one-step per landing class. ABOVE TARGET is
  decided by flight and after touchdown only.

The scripted player is unchanged (W + sprint held, one jump press at z >= 10.85).

## 2. The replica against vanilla 1.21.1

Sources: the NeoForge 21.1.265 sources jar (Mojang names). "Full" is the shaped path (`LevelShapes` is shaped, so
Movement Assist always runs it in game); "cube" is the cube path of unshaped grids (tests, benchmarks, `LevelVoxels`
users: `WorldBodySims`, `MinecraftRouteRunner`).

| Rule | Vanilla location | Full path before | Full path now | Cube path |
|---|---|---|---|---|
| noJumpDelay counts down first, jump needs 0, set to 10, reset to 0 without jump input | `LivingEntity.aiStep` | yes | yes | no delay (held jump re-jumps on landing; same result for 10+ tick flights) |
| velocity components under 0.003 zeroed before jump and travel | `LivingEntity.aiStep` | **missing** | yes | missing |
| input `xxa/zza *= 0.98F` (float) | `LivingEntity.aiStep` | 0.98 double | 0.98F | 0.98 double |
| sneak impulse `* 0.3F` before the 0.98F | `KeyboardInput.tick`, `LocalPlayer.aiStep` (`isMovingSlowly`) | 0.3 * 0.98 double | float | n/a (sneak takes the full path) |
| jump vy = `(double) getJumpPower()` = 0.42F (+ jump boost, float) | `LivingEntity.jumpFromGround`, `getJumpPower` | 0.42 double | 0.42F | 0.42 double |
| sprint jump `+ (double)(-Mth.sin(yRot * (float)(PI/180))) * 0.2`, `Mth.cos(..) * 0.2`, only when sprinting | `LivingEntity.jumpFromGround` | Math.sin of double radians | Mth table | Math.sin |
| friction f2 of the block at `getOnPos(0.500001F)`, read at the start of travel | `LivingEntity.travel`, `Entity.getBlockPosBelowThatAffectsMyMovement` | yes | yes | caller's `slipperiness` |
| f3 = onGround ? f2 * 0.91F : 0.91F (float), onGround from the start of the tick | `LivingEntity.travel` | double | float | double |
| ground accel `getSpeed() * (0.21600002F / (f2*f2*f2))`, float | `LivingEntity.getFrictionInfluencedSpeed` | double (0.1 * 1.0000000926) | float (exactly 0.1F / 0.13F x ~1) | double |
| speed = (float) MOVEMENT_SPEED; base 0.1F, sprint modifier 0.3F ADD_MULTIPLIED_TOTAL, no one-tick lag (`Player.getSpeed` overrides the field getter) | `Player.getSpeed`, `LivingEntity.SPEED_MODIFIER_SPRINTING` | 0.1 * 1.3 double | (float)(0.1F * (1 + 0.3F)) | 0.1 * 1.3 double |
| air accel 0.025999999F sprinting, else 0.02F | `Player.getFlyingSpeed` | double literals | float | double literals |
| input vector: lengthSqr < 1e-7 none, > 1 normalised (divide by length), scale by speed, rotate by `Mth.sin/cos` of `yaw * (float)(PI/180)` | `Entity.moveRelative`, `Entity.getInputVector` | multiply by 1/len, Math.sin/cos | divide, Mth table | multiply, Math.sin/cos |
| move: back-off, collide (Y, then the larger horizontal axis last), step-up, `Mth.equal` wall flags, vy zeroed on a vertical hit | `Entity.move`, `Entity.collide`, `Player.maybeBackOffFromEdge` | yes | yes | Y, X, Z order, exact flags |
| vy = (vy - 0.08) * 0.98F; horizontal * f3 | `LivingEntity.travel` | 0.98 double | 0.98F | 0.98 double |
| sprint stops on a non-minor wall contact (next tick), on losing forward impulse; starts with impulse >= 0.8 | `LocalPlayer.aiStep`, `isHorizontalCollisionMinor` | yes | yes (minor check now uses the table sin/cos like vanilla) | not modelled |
| Player.travel adds nothing on foot (swimming and flying only) | `Player.travel` | n/a | n/a | n/a |

Size of the arithmetic differences: about 1e-8 relative per tick (float constants), up to about 5e-5 relative for
the table sin/cos at non-axis yaws, and the 0.003 floor (worth up to ~0.004 block when a body coasts to a stop).
None of them explains 0.30. The full path now matches the vanilla formulas to 1e-12 in `VanillaParityTest`.

### Changed pinned values (full path only; the vanilla source proves the old numbers wrong)

All four are float-vs-double changes of about 1e-8; the tests now state the vanilla float formula:
- `MovementPhysicsRulesTest.sneakScalesInputByPointThreeOneTickAfterTheKey`: accel `0.1f * (0.21600002f / f^3)`,
  friction `0.6f * 0.91f`, first tick `0.98f * a`, second `(0.3f * 0.98f) * a` (was the double forms).
- `MovementPhysicsRulesTest.sprintStopsAfterAWallHitAndReturnsTheTickAfter`: sprint accel
  `(float)(0.1f * (1 + 0.3f)) * (0.21600002f / f^3)` times `0.98f` (was `0.1 * 1.3 * ... * 0.98`).
- `MovementPhysicsRulesTest.slipperinessIsReadFromTheBlockUnderTheFeetEveryTick`: ice friction ratio
  `(double)(0.98f * 0.91f)` = 0.89180004597 (was `0.98f * 0.91` = 0.89180001736).
- `ShapePhysicsTest.trapdoorCeilingClipsTheJump`: vy after the head hit `-0.08 * 0.98F` = -0.0784000015 (was -0.0784).

Cube grids are bit-identical (`cubeGridsGiveBitIdenticalResults` still passes): the cube path was not touched, by
choice. Its numbers are pinned by the benchmarks and planner bounds, it is within 1e-5 block of vanilla over a
60-tick run (checked in `VanillaParityTest`), and the in-game assist does not use it. Known cube-path deviations,
left as they are: no 0.003 floor, no held-jump delay, double constants, Math.sin.

## 3. Tests

`VanillaParityTest` (8 tests): a reference integrator written line by line from the vanilla methods above (its own
copy of the Mth table), plus literal cross-checks. Every tick of every script is compared with the full path to
1e-12 (position, velocity, onGround) and with the cube path to 1e-5 (position, onGround):
- standing jump: apex 1.252203340253729, back on the ground at the end of tick 12, first tick y += 0.42F;
- walking run-up from rest: first tick 0.98F * 0.1F accel, second tick by hand, steady 0.21586 block/tick;
- sprinting run-up: steady 0.28062 block/tick;
- sprint jump from steady sprint: 12 flight ticks; the jump tick and the second flight tick by hand (ground drag of
  the start-of-tick onGround, then 0.025999999F air accel);
- sprint off on the jump tick: no 0.2 boost, walking accel;
- W released in the air: the move is the carried velocity, drag 0.91F, no input;
- 45-degree strafe run (W + A at yaw 45): normalised input, table sin/cos, first tick by hand;
- `MovementPhysics.mthSin/mthCos` equal the table formula from -720 to 720 degrees.

Whole suite: `./gradlew compileJava compileDevtestJava test --offline`: 1554 tests, 0 failures (all of
travel/movement included; allocation tests green: the table is built once at class load, the tick only indexes it).

## 4. Lane geometry

Not changed. One concern for the in-game run: the corner lanes look infeasible for the scripted player. The runner
starts at x = dx + 0.5; the post is in column dx + 1 (centre dx + 1.5), so support needs the body centre past
dx + 1.075 (fence) or dx + 0.95 (wall). The pillar (column dx + 1, z 11..13) blocks any sideways move until the body
is past z 13.3, which leaves about 3 flight ticks before the post (z 14.1-14.9). Sideways air acceleration with A is
at most 0.0255 block/tick per tick (0.018 with W + A), about 0.15 block in 3 ticks, against the 0.45-0.58 needed,
and the scripted LEFT tap is only flight ticks 6-7. If the corner lanes fail with `x` at touchdown around -0.9 to -1.0
from the post centre (now printed), that is the geometry, not the assist. A fix would be a post at x offset of about
0.3 (or a start x near dx + 0.7 with the pillar one column further), decided with the approach-control task.

## Not done

- Climbable cells and water as landings in `MovementPhysics` (optional item): not done; both stay
  `SURFACE_CLIMBABLE` / `SURFACE_WATER` flags only, not modelled by the physics.
- `WorldBodySims` / `LevelVoxels` (cube path) unchanged; pursuit and route bodies keep the cube approximation.
- Speed and jump-boost effects: the replica's `speed` / `jumpBoost` doubles are cast to float the vanilla way for the
  default values; with an effect the order of the attribute multipliers can differ by about 1e-9.

## Left for the in-game playtest

`./gradlew runPlaytest -PplaytestOnly=Parkour` (the owner runs it). Read per lane: ground run, flight, after
touchdown, one-step max and the first one-step line above 1e-4 if any. Expected: one-step about 1e-6 or less,
flight and after-touchdown under the 0.001 target on the fence/wall/pane classes. A one-step line above 1e-4 names
the tick and its inputs; that is a rule gap (likely candidates: the assist turning the body inside the tick, block
speed factors, entity push), not drift.
