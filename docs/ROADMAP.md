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

## Next session, first: UI restyle to the three-column layout (user request 2026-11-20 18:00)

Reference screenshot: `docs/ui/reference/style-target.webp`. Copy its layout and look only (not its feature set).

- **Window:** large centered panel (~75% of the screen width), near-black (#141416-ish) with 1 px #2A2A2E hairline borders,
  square or 4 px corners, uppercase bold title top-left ("UTILITY SUITE") with a small version chip, and icon buttons
  top-right (settings, profile, close). Small, dense, crisp text; muted grey secondary text; white primary.
- **Column 1, category sidebar (~150 px):** icon + label rows (Jobs, World, Travel, Combat, Tools, plus HUD, Profiles,
  Keybinds, Rules), a count badge on the right showing how many modules in that category are on, active row
  highlighted with a lighter fill; a divider then secondary groups (Profiles >, Binds >, Overlays >).
- **Column 2, module list (~230 px):** search field at the top ("Search modules, settings…" + magnifier), a PRESET
  segmented control (e.g. Off / Light / Balanced / Full, or the visual presets) with "none applied" status, then
  small sub-group captions (dim uppercase) and one row per module: a tag chip on the left (e.g. "AUTO"/"ASSIST"/
  "VISUAL", or a risk chip), name, kebab menu at the right; the selected module row highlighted; enabled modules in
  white, disabled in grey.
- **Column 3, detail panel:** module name + enable switch at the top right, a one-paragraph description in grey,
  "SETTINGS" caption, then compact rows: label left; control right-aligned (small pill dropdown for enums, tiny
  switch for booleans, full-width thin slider with value text at the right for numbers, range sliders "40 ↔ 80" for
  min/max pairs, "0 selected >" pickers for lists). Collapsible sub-sections (e.g. TARGETS / TYPES).
- **Search:** filters modules and settings across all categories and highlights matches (merges with the
  Spotlight-style top bar idea above).
- **HUD Studio side panel** (later): a library of HUD elements (minimap, info strip, armor status, clock, active-modules
  list), each with a preview card, on/off and a favourite star; tabs Library / Layouts / Style; "Place overlays" enters
  the drag/resize HUD edit mode (built for the minimap tonight).
- Keep the existing generic SettingSpec → widget mapping; this is a new renderer for the same data. Keep the HUD pill.

## Direction: altoclef-style autonomy + full mod compatibility (user request 2026-11-20 18:26)

The user's target is the tool to work "basically like altoclef": you give it any goal, it plans and runs the whole
chain, and it works with modded content and JEI. The research and design notes are in
`docs/research/altoclef-and-mod-compat.md`.

- [ ] Survival task chains that interrupt a goal and resume it: food, mob defense, MLG bucket, lava/fire/drowning
      escape, death recovery. They sit on top of ActionArbiter priorities.
- [ ] Re-decide each tick with stable subtasks (altoclef's `isEqual` rule) instead of only planning up front and
      repairing the plan.
- [ ] Block tracker: remember block positions by type within a scan budget. It feeds detours and modded ores
      (`c:ores/*`).
- [ ] Planner recipe sources from the live RecipeManager for every type: add stonecutting, smithing, campfire, and
      generic modded types. Use tags for substitution and choose tools by `mineable/*` and `incorrect_for_*` tags.
- [ ] Learn drops: record what blocks and mobs drop (loot tables aren't on the client) so "kill/break X for Y" can
      be planned.
- [ ] Optional JEI integration (`compat.jei`, compileOnly, gated by ModList):
      - recipe lookup across all categories as a planner source or as manual steps;
      - recipe transfer for modded crafting stations;
      - a "plan this" keybind on the hovered item;
      - JEI bookmarks become a shopping list.
- [ ] EMI adapter behind the same `IRecipeIndex` contract.
- [ ] Compatibility:
      - Sodium/Embeddium + Iris overlay check;
      - Xaero/JourneyMap waypoint import/export;
      - modded storage indexing.
- [ ] Compatibility playtest: JEI plus one content mod; `gather <modded item>` plans and runs.
- [ ] Butler mode (opt-in): allow-listed players give the bot commands by whispering in chat.
- [ ] Stretch: "beat the game" chain (nether → blaze rods/pearls → stronghold → dragon).
- Optional: natural-language goals turned into GoalParser commands by the local LFM3 1.7B tool-calling server.
