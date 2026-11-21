# Render package: integration notes

## What landed (all in `world/**`, registered in `WorldBootstrap`)

| Module id | Class | Notes |
|---|---|---|
| `light_overlay` | `world/lighting/LightOverlayModule` | Budgeted column scan on the client tick (`ColumnScanScheduler`, default 96 columns/tick), rule in `SpawnableRule`, results in a `Long2ByteOpenHashMap`, frame walks a flat array rebuilt on change. Re-queued on block updates (WorldHooks block listener, now fans out to runtime + light overlay), chunk loads near the player, and moving more than 4 blocks. |
| `blast_overlay` | `world/blast/BlastOverlayModule` | Swelling creepers, primed TNT, end crystals (setting). Ring or sphere at 2 x power, coloured by fuse (`BlastMath`), cached countdown labels. Capped at 32 sources, gathered every 2 ticks. |
| `spawn_geometry` | `world/spawn/SpawnGeometryModule` | Slime chunks (`SlimeChunks`, inline Random, salt 987234911) from: integrated server seed, else the Seed Workbench profile (`oreSim::profile`), else a manual seed setting. "Seed needed" one-line label otherwise. 24/128 spawn spheres around the point where the module was enabled (lat/long globe from the precomputed unit circle). |
| `freelook` | `world/camera/FreelookModule` | Hold key `key.utilitysuite.freelook` (default Left Alt), registered in WorldBootstrap. Turn absorbed via the existing `EntityTurnFreecamMixin` hook (redirect now `camera.turn || freelook.turn`), offset applied in `ViewportEvent.ComputeCameraAngles`; eased return capped at 450 deg/s (`FreelookOrbit`). Never writes player rotation. |
| `render_filters` | `world/filters/RenderFiltersModule` | Hide hurt tilt, rain/snow, item-activation (totem) pop, explosion particles; client-side fixed time and weather override. All default off. |
| (radar) | `world/overlays/WorldRadarModule` | New settings `mobs` (hostile mob boxes + tags), `tracers`, `max_mobs`. Player and mob tags use `NametagCache` (string rebuilt only when rounded health/armour/distance changes). |

Shared geometry: `world/render/LineSink`, `PoseLineSink`, `Shapes` (cross, ring, sphere outline, globe, square), all allocation-tested.

## Integrator actions

- Merge `render.lang.json` into `en_us.json`.
- Add the five lines of `render.mixins.txt` to `utilitysuite.mixins.json` under `client`.
- Optional legit-preset entries: none needed (everything is render-only and off by default).

## New mixins: unverified in game

Each target and descriptor was checked with `javap` against `build/moddev/artifacts/neoforge-21.1.265-merged.jar`
(1.21.1 Mojang names), but none has run in a client yet. The dev-client mixin audit (`-Dutilitysuite.mixinAudit=true`)
will confirm them on the next `runClient`:

- `world.GameRendererFiltersMixin`: `GameRenderer.bobHurt(PoseStack, float)` HEAD cancel; `GameRenderer.displayItemActivation(ItemStack)` HEAD cancel.
- `world.LevelRendererWeatherMixin`: `LevelRenderer.renderSnowAndRain(LightTexture, float, double, double, double)` and `LevelRenderer.tickRain(Camera)` HEAD cancel.
- `world.ParticleEngineFilterMixin`: `ParticleEngine.createParticle(ParticleOptions, double x6)` HEAD, returns null for EXPLOSION / EXPLOSION_EMITTER.
- `world.ClientLevelDataTimeMixin`: `ClientLevel.ClientLevelData.getDayTime()` RETURN override (client data only).
- `world.LevelWeatherOverrideMixin`: `Level.getRainLevel(float)` / `getThunderLevel(float)` HEAD, guarded by `instanceof ClientLevel` (Level is shared with the integrated server).

## Unverified in game / known limits

- Nothing in this package has been seen in a running client today (no runClient allowed). Worth checking first: light overlay
  marks on slabs/carpet/snow layers (rule uses vanilla `isValidSpawn` + `NaturalSpawner.isValidEmptySpawnBlock` for a zombie),
  freelook feel and the release ease, tracer start point at the crosshair with view bobbing on.
- Freelook in third person turns the view in place rather than orbiting the player (the camera position is computed from the
  player's rotation before `ComputeCameraAngles`); orbiting there would need a `Camera.setup` hook.
- Freelook key state is sampled on the client tick (up to 50 ms latency on press/release).
- Blast overlay: beds and respawn anchors in the wrong dimension are not covered (they are blocks, not entities; would need the
  shared scanner to report them). Wither skulls / fireballs not covered either.
- Light overlay re-scans the whole square a few ticks after any light-relevant change; a smarter 15-block light-radius re-queue
  would cut the work further.
- `render_filters` "hide rain and snow" also suppresses rain splash sounds (both come from `tickRain`).
