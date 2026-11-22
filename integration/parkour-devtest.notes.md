# Parkour devtest lanes (2026-11-22)

None of this has been run in game yet. It compiles (`compileJava`, `compileDevtestJava`) and nothing more. The owner or
the integration step runs `./gradlew runPlaytest -PplaytestOnly=Parkour`. Geometry, the jump trigger (jump pressed at
z >= 10.85 of the platform) and the corner strafe tap (LEFT held flight ticks 6-7) are first guesses to tune in game.

All lanes are in `ParkourScenario.controlModelLanes`, built after the block-clutch lanes and before the escape path,
in a strip beyond the course (`course.bounds()[5] + 20`). Scripted keys go through a lease and `IInputSmoother`
(FORWARD, SPRINT, JUMP, LEFT, SNEAK). The one exception is the physical-sneak override lane, which uses the GLFW key
path like ArbiterScenario.

Lane names are `<kind> <MODE>`: 15 landing lanes plus 2 override lanes.

| Kind | Landing | Gap | Asserts |
|---|---|---|---|
| fence-2gap | oak fence post | 2 | on top 40 ticks after landing, W held throughout |
| wall-3gap | cobblestone wall post | 3 | same |
| pane-1gap | glass pane | 1 | same (top is 1.0, not 1.5) |
| corner-fence | oak fence, pillar clips the line | 3 | same, with one LEFT tap |
| corner-wall | cobblestone wall, same layout | 3 | same |

"On top" means onGround, feet y within 0.01 of the post top (FLOOR + 1.5; pane FLOOR + 1.0) and the larger of the x
and z distances to the post centre under the support limit (fence 0.425, wall 0.55, pane 0.3625; the brief gave 0.425,
the wall and pane limits are post half width + 0.3). Axis distance is used, not Euclidean, because support is box
overlap. Then forward is released 2 ticks and pressed again; the lane asserts the body leaves the top within 10 ticks.

Each kind runs for MODE = CORRECTIONS, CO_PILOT, TAKEOVER, written with the string profile keys `control_mode`
(plus `landing_hold` true and `hold_release` FRESH_INPUT for the run). Previous values of `control_mode`,
`landing_hold`, `hold_release`, `hold_limit_ticks`, and the TerrainAssist and Movement Assist enabled flags are
restored by the always-step. If a key was absent from the profile its default is written back, not removed.

Override lanes (fence-2gap CO_PILOT, `override-SCRIPTED` and `override-PHYSICAL`): sneak goes down 4 ticks after
the jump. For the next 6 ticks the player's effective input (`input.up/down/left/right/jumping/shiftKeyDown`) must
equal the raw option keys, i.e. the assist wrote nothing. Caveat: a physical key may end the lease with
MANUAL_OVERRIDE, which also stops the scripted keys; the check still holds but is weaker there, hence the scripted
variant.

Parity (information only, never a failure): every lane steps a `MovementPhysics` replica with `LevelShapes` from the
start of the run, fed the inputs the body used each tick, and records the max position error before touchdown and
after it. A final note lists the max per landing class and names every class above 0.001 block. Until the shape work
lands in `LevelShapes` the replica uses the cube approximation, so fence, wall and pane classes are expected above
target. The replica starts at rest; a mismatch on the first ticks may be a sampling-order artefact, check that first.

Not done: no unit test (devtest has no test source set); no run in game; the chain, slab and ladder lanes of the
design's edge-case list.
