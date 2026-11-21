# jobs package: integration notes

## Done (all inside owned paths)

- **A9 strip routes** (`jobs/planner/StripRoutePlanner`): `setFluids(lava, water)` adds a hazard cost (lava 3.0, water
  0.75 per fluid cell within 2 blocks of a tunnel cell); a tunnel whose fluid cost outweighs its ore is dropped.
  `JobExecutor.startStrip` now queries the world knowledge for `minecraft:lava` / `minecraft:water` around the route
  area once per route. Torch cadence: `torchSpacingFor(minLight)` (light falloff, default 12 =
  `DEFAULT_TORCH_SPACING`) and `isTorchStep(t, spacing)`. Wall glances: `wallGlances(seed, length, meanGap, out)`
  (sparse, deterministic, side as sign).
- **New package `jobs/fieldwork`**, four modules registered in `JobsBootstrap` (all off by default, JOBS tab, each
  `IModule + IConfigurable`, each with an advanced "Relaxed (less legit)" setting, key `relaxed`):
  - `auto_torch` (`AutoTorchModule`), `lava_plug` (`LavaPlugModule`, lease at SURVIVAL), `vein_work`
    (`VeinWorkModule`), `crop_work` (`CropWorkModule`).
  - Shared: `FieldHands` (lease WORLD_INTERACT+CAMERA+HOTBAR, slot select via `IInputSmoother`, swap from main
    inventory via `IContainerClicker` SWAP, smoothed visible look + USE/ATTACK via the core; Relaxed: silent look and a
    direct `useItemOn` / destroy call after re-checking the WORLD_INTERACT lease), `FieldRules` (torch decision, filler
    block whitelist), `VeinCollector`, `CropPlanner` (engine free, unit tested incl. allocation probes).
- Lang entries: `integration/jobs.lang.json`. No mixins.

## Integrator wiring (outside the jobs paths)

- Optional: add `LegitPresets` knobs so the global Legit/Balanced/Relaxed control drives the new modules:
  `knob("auto_torch", "relaxed", false, false, true)`, same for `lava_plug`, `vein_work`, `crop_work`.

## Not done / follow-ups

- Torch cadence and wall glances are computed by the planner but the strip-mining phase of `JobExecutor` does not yet
  place torches at `isTorchStep` cells or look at `wallGlances` points while walking (Auto-Torch covers torches during
  jobs by light level in the meantime). Wiring: in the STRIP phase, after each segment, call the Auto-Torch hands or
  queue a placement; glance through `IInputSmoother` only while standing (look where you move).
- Auto-Torch places on the floor (behind the player, else at the feet); wall placement is not implemented.
- Vein Work mines only what is within reach from where the player stands; walking to the rest through the mining
  driver (`MiningDriver.gotoCoord`) is not wired.
- Crop Work does not replant cocoa (needs a jungle-log face); cocoa harvest is opt-in.
- The "inventory full" stop in Vein Work uses `Inventory.getFreeSlot() < 0` (a full inventory may still stack drops).
- Not play-tested in game (no runClient in this run). Things to check: the manual-override pause after the user's own
  break delays Vein Work's start by the configured pause; Lava Plug's support-face choice when the lava is below the
  player.
