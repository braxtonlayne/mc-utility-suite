# Utility Suite roadmap

Status as of 2026-11-20 evening. Phase 2 directive ("Capability Expansion, Performance & UI Polish") plus the user's
additions. Items gated "SP/allow-list" run only in single-player or on servers listed in the allow list (Tools tab,
shared with the Latency Simulator), same as today.

## Phase 2: Milestone 1 (done 2026-11-20, merged on main)

- [x] Every Baritone run (waypoint Go, Take Me Back, schematic walk-to-site, autopilot overland) holds a lease; Stop-All
      cancels every Baritone process.
- [x] OreSim: per-cell height cache (worker allocation ~680 → ~115 MB/s); large ore veins (deepslate iron recall
      ~30% → ~100% in playtest; overall recall 0.97, precision 0.68).
- [x] Cost profiles produce distinct plans; typed goals use the World tab routing source unless `source=`; `travel to
      <waypoint>` converts 8:1; cross-dimension travel fails with a clear message.
- [x] UI: title/goal-bar gap, two-line waypoint rows, opaque window (chat bled through = the "ghost text"), menu
      auto-close fix + open/close logging, direct controls for Water Walk, Freecam, Step, NoSlowdown, Take Me Back,
      Combat arm/retreat, overlays, Add waypoint here.
- [x] Goal engine: replan budget actually caps repairs; waits for recipe unlocks; any wood type for planks; wider
      crafting-table placement search. `gather 4 coal` from an empty inventory is now the playtest.

## Phase 2: Milestone 1 leftovers

- [ ] Schematic playtest regression after the merge: Build Assist stuck at 9/10 ("hidden" cell, empty main hand).
- [x] `gather 4 coal` from nothing: full chain (wood → planks → table → sticks → pick → coal) passes in playtest.
- [ ] World tab Quick actions: "Start freecam" button text is truncated ("Start f…").
- [ ] Portal routing for cross-dimension waypoints (travel goals + autopilot).
- [ ] SAFEST: pass a hazard-averse hint to the miner (MiningDriver has no hook yet).
- [ ] Duplicate Water Walk / Step / NoSlowdown controls (Quick actions + Terrain card) can show stale values until
      the tab refreshes.
- [ ] Schematic walk-to-site is refused while a TRAVEL run is active and isn't retried.
- [ ] Craft fallback when a recipe is locked: place ingredients in the grid by clicks instead of the recipe book.

## Phase 2: Milestone 2 (combat, network tools)

- [ ] Combat engagement mode: REACTIVE / PROACTIVE with target priority CLOSEST, LOWEST_HEALTH, THREAT (SP/allow-list).
- [ ] Silent (decoupled) aim through InputSmoother SILENT rotation for combat (SP/allow-list).
- [ ] Auto-mace swap when falling > 1.5 blocks; wind-charge fall clutch and optional mid-fight boost.
- [ ] Jump-reset on knockback; totems, golden apples and potions from the whole inventory; offhand totem replenish.
- [ ] Ranged assist: bow/crossbow charge timing and velocity-compensated aim using Fight Coach's ballistics.
- [ ] FastUse / FastPlace (configurable right-click cooldown) and direct container access within reach (SP/allow-list).
- Not building: Blink (outgoing packet hold), Backtrack (inbound entity delay), Packet NoFall.

## Phase 2: Milestone 3 (movement, world, schematics, planner)

- [ ] Travel tab: Step 1.0–2.0 slider, NoSlowdown modes (eating, bow, cobweb, soul sand), Omni-Sprint, FastLadder,
      Anti-Cobweb (SP/allow-list).
- [ ] Scaffold mode for Bridge Assist: forward, diagonal and stair-step at walk/sprint speed, silent placement,
      automatic block switching, pitch and delay settings.
- [ ] Water Walk modes SOLID / BOBBING / PACKET, separate water and lava toggles, configurable rubber-band guard.
- [ ] Auto-Mend with XP bottles during elytra/Baritone travel when durability < 15%.
- [ ] Enhanced boat traversal; portal routing (shared with Milestone 1 leftovers).
- [ ] Overlay readability: single-layer focus, opacity and line-width sliders, dimming of background layers.
- [ ] Schematic printer: enforce block states (facing, half, axis, waterlogged); optional break-and-replace of wrong
      blocks; Baritone walks around the build to reach hidden or out-of-range cells; pull staged materials from
      project chests.
- [ ] Planner sources: crops/farming, mob drops, villager trading, stonecutting, smithing.
- [ ] Smart Inventory container looter (gaussian-timed take-all for chests/shulkers).
- Not building: seed deduction from observed terrain; stash/base scanner that logs storage-heavy chunks.

## User additions (2026-11-20)

### Full progression planner

- [ ] `brew <potion> [qty]` (e.g. `brew fire_resistance 3`, `brew strength II`): brewing stand (find/craft), blaze
      powder fuel, glass bottles, fill at nearest water, nether wart + base ingredient + modifier
      (redstone/glowstone/gunpowder), load, wait, collect.
- [ ] `enchant <item>` with an XP-aware planner: route to a registered enchanting table, bring lapis, check levels;
      if short, queue an XP sub-goal (quartz/coal mining or mob hunting) until level 30.
- [ ] Multi-furnace array: when StorageMemory knows several furnaces at a base, split ore and fuel evenly across them,
      do a nearby task meanwhile, then come back and collect.

### Opportunistic detours ("while I'm passing by")

- [ ] While Baritone walks toward objective A, take a bounded detour (configurable radius, 8–12 blocks) for: items on
      the current or queued shopping list (exposed coal/iron, sugar cane, a needed tree), exposed high-value ores
      (diamond, ancient debris, emerald), dropped items matching needed materials. Update surplus accounting, resume.
- [ ] Opportunistic chest check: optionally peek into unindexed or stale chests/barrels passed on the way (villages,
      dungeons) to update the storage index.

### Visual, dimension and HUD presets

- [ ] One-click overlay presets: Cave Run (exposed ores + fluid hazard warning + fullbright), Ancient Debris Run
      (Y=15 debris filter + lava pocket warning), Builder Vision (schematic ghost + fullbright + fog/weather off),
      Clean (all overlays off).
- [ ] Dimension auto-switch: swap the active preset on entering Overworld / Nether / End.
- [ ] Optional info HUD strip (hidden in Clean-Screen): coordinates with live Nether/Overworld conversion, biome, TPS,
      ping, armor/tool durability bars.
- [ ] Visual rule editor for Local Rules plus starter presets: "Inventory ≥ 90% full → deposit at base and resume",
      "Tool durability < 10 → pause and mend/swap", "Health < 6 hearts → retreat / pause job".
- [ ] Spotlight-style top bar: typing a word ("water", "mace") filters and highlights matching settings across all
      tabs; typing a goal verb (gather, craft, go) runs a goal.
