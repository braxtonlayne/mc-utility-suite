# mc-utility-suite

Client-side automation, resource-planning and accessibility mod for **Minecraft 1.21.1** on **NeoForge 21.1.265**,
**Java 21**, **Mojang mappings** (Parchment 2024.11.17 for parameter names). Intended for the user's single-player
worlds and their own whitelisted server. Mod id `utilitysuite`, root package `com.utilitysuite`.

## Build

- `./gradlew compileJava` / `./gradlew test` / `./gradlew build`. Unit tests are plain JUnit 5 (no game bootstrap):
  keep planning logic engine-agnostic (string registry ids, packed `long` positions, `IInventoryView`) so it is testable.
- Baritone **1.11.3** (cabaletta, LGPL-3.0, NeoForge build for MC 1.21–1.21.1) is in `libs/` because it has no Maven
  artifact. It is `compileOnly` + dev runtime (`localRuntime`) and **optional** for players. Baritone's mod id is
  `baritoe` (sic). Only `com.utilitysuite.jobs.baritone` may import `baritone.*`, and only after checking
  `ModList.get().isLoaded("baritoe")`.
- `./gradlew runClient` starts a dev client with Baritone loaded. The dev run sets `-Dutilitysuite.mixinAudit=true`,
  which force-applies every mixin after load (`UtilitySuiteMod.auditMixins`) so broken mixin targets fail at startup,
  not the first time a class loads in a world. Unit tests run in a SERVER-dist FML env, so client mixins never apply
  there: the audit is the mixin check.
- Acceptance scenarios (spec Step 8): 1 and 2 in `jobs/goal/AcceptanceScenario{1,2}Test`, 3 in
  `core/AcceptanceScenario3Test`, 4 in `combat/AcceptanceScenario4Test`, 5 under `jobs/schematic/`.
- Static input-rule check: `grep -rnE 'options\.key|setYRot|setXRot|\.setDown\(|\.selected *=|handleInventoryMouseClick|handlePlaceRecipe' src/main/java`
  must only hit `core/input/` and `mixin/` (read-only `inv.selected` reads are allowed). Direct `gameMode.useItemOn`
  (silent-mode placement) must re-check the WORLD_INTERACT lease right before the call.

## Architecture rules (non-negotiable)

1. **Contracts first.** Modules talk only through `com.utilitysuite.core.api` (interfaces, enums, records) and
   `Services` (service locator). Never reach into another module's internals.
2. **Input goes through the core.** No module touches `Minecraft.getInstance().options.key*`, `KeyMapping.setDown`,
   `LocalPlayer.setYRot/setXRot`, `Inventory.selected` or `MultiPlayerGameMode.handleInventoryMouseClick` directly.
   Request a `ControlLease` from `IActionArbiter`, then drive keys/camera/hotbar via `IInputSmoother` and container
   clicks via `IContainerClicker`. Only `com.utilitysuite.core.input` (and its mixins) may touch those APIs.
3. **Manual input wins.** The user's own keys/mouse preempt any non-emergency automation. Stop-All revokes everything.
4. **NeoForge events first, mixins last.** Mixins only when no event exposes the hook; each small, commented, listed
   in `utilitysuite.mixins.json` under `client`.
5. **Threads.** World/entity/render calls only on the client thread. Heavy work (schematic parsing, recipe search,
   ore simulation) on a dedicated executor, publishing immutable snapshots back to the client thread.
6. **HUD discipline.** Nothing renders when idle. One status line while a job runs. Clean-Screen suppresses everything.
7. **Vocabulary.** Neutral, descriptive engineering names in code, comments and commits (see the subsystem list in the
   master spec: ActionArbiter, InputSmoother, GoalConsole, SchematicToolkit, WorldOverlays, TerrainAssist,
   CombatAssistant, SmartInventory, PacketLab, Shell).

## Performance mandate (the user's, non-negotiable)

1. **Zero-allocation hot paths:** no `BlockPos`, `Vec3`, collections, lambdas or streams allocated inside `onTick()`,
   render callbacks or tight loops. Reuse `BlockPos.MutableBlockPos`; key spatial data by `BlockPos.asLong()` in
   fastutil primitive collections (`Long2ObjectOpenHashMap`, `LongOpenHashSet`).
2. **No polling** where an event exists; cheap early-outs when a module is disabled.
3. **Budgeted work:** cap scans per tick (e.g. N blocks/chunk sections per tick) or compute off-thread on snapshots.
4. **Sodium/FPS safe rendering:** draw overlays in `RenderLevelStageEvent` with batched buffers; never rebuild chunk
   meshes unless a mode explicitly requires it.
5. **Small disk footprint:** persist compact JSON / primitive arrays, debounced writes off-thread.
6. **Verify:** add a micro-benchmark or allocation-conscious unit test for hot paths before calling a feature done.

## Layout

```
com.utilitysuite
├── UtilitySuiteMod            entry point (wires core + module bootstraps)
├── core.api                   shared contracts (orchestrator-owned; change only with care)
├── core.arbiter / input / config      ActionArbiter, InputSmoother, ConfigStore, ModState, KeybindManager
├── jobs.goal / planner / baritone / storage / survival / schematic
├── world.overlays / oresim / seed / camera / waypoints
├── travel.terrain / autopilot / bridge
├── combat.assistant / allies / coach
├── tools.inventory / packetlab / rules
├── ui                         Shell: menu screen, HUD status line
└── mixin                      all mixins
```
