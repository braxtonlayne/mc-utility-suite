# Research: altoclef-style autonomy and full mod compatibility

2026-11-20. This is a quick pass (about 15 minutes) before the user left for work. The details on altoclef's design come
from my knowledge of its source; the project status comes from GitHub and Modrinth searches. Before porting any design,
check it against the current fork's code.

## What the user wants

Something like [altoclef](https://github.com/gaucho-matrero/altoclef): you give it a goal ("get a diamond pickaxe",
"beat the game") and it works out and runs the whole chain itself, using Baritone to move. It should also work fully
with modded content and JEI.

## The altoclef family

| Project | Status | Notes |
|---|---|---|
| gaucho-matrero/altoclef | Archived | The original. Fabric only. Its README points to the Meloweh fork. |
| [Meloweh/altoclef](https://github.com/Meloweh/altoclef/releases) | Fabric, active fork | The successor the original README points to. |
| [MiranCZ/altoclef](https://github.com/MiranCZ/altoclef/releases) | Multi-version fork | beta-0.18.x adds 1.21 and back-ports to 1.16.5–1.20.5. |
| chris2rich / jmcrafter26 forks | Smaller forks | Not reviewed. |
| [Ladysnake/Automatone](https://github.com/Ladysnake/automatone) | Server-side Baritone fork | Drives entities on the server. Not relevant to a client tool. |
| [Mineflayer](https://npmjs.com/package/mineflayer), Voyager, Mindcraft | External JS bots | A headless JS bot, not a mod. Voyager and Mindcraft put an LLM on top to plan and build up skills. |

None of these run on NeoForge 1.21.1, and none of them know modded content beyond what Baritone can path through.
**Conclusion:** keep building our own suite (it already has the goal engine, planner and Baritone adapter) and borrow
altoclef's design ideas, not its code. Its license and Fabric-only mixins make a straight port a poor fit anyway.

### altoclef design ideas worth adopting

1. **Task tree with stable subtasks.** Each `Task` returns its current subtask every tick. A subtask is kept only if
   `isEqual()` says it's the same goal, so the bot doesn't thrash between choices. Our JobExecutor plans the whole job
   up front and then replans; a tree that re-decides each tick handles surprises better (a mob, a lost item, a block
   that wasn't where we expected).
2. **Priority task chains.** Several chains compete every tick, and the highest priority wins:
   - user task (the goal);
   - food;
   - mob defense;
   - MLG bucket on falls;
   - world survival (leave lava or fire, don't drown, free yourself when stuck);
   - death and respawn recovery.

   Our ActionArbiter leases already model priority. What's missing is the automatic survival chains that interrupt a
   goal and then hand control back.
3. **Trackers.**
   - Item storage tracker: inventory plus containers seen.
   - Block tracker: remembers block positions by type, scanned within a budget.
   - Entity tracker: dropped items, hostiles, projectiles.

   We have StorageMemory and the waypoint stores. A block tracker would also power "opportunistic detours".
4. **Resource catalogue: "get N of any item".** In altoclef this is a hard-coded table mapping each item to the task
   that gets it (mine, kill, craft, smelt). That table is altoclef's biggest weakness with mods: it doesn't know modded
   items. We should derive it from the game's data (see below).
5. **Behaviour settings.**
   - Protected items: never thrown away or used up.
   - Throwaway blocks: used for pillaring and bridging.
   - Avoid-break zones: never broken.
   - Conservative mode: the bot takes fewer risks.

   These map onto our Local Rules and cost profiles.
6. **Butler mode.** Allow-listed players can give the bot commands by whispering in chat. It could be fun on the
   friend's server, but it should be opt-in and limited to the allow list.
7. **"Beat the game".** A speedrun task: overworld progression → nether (blaze rods, pearls) → stronghold → dragon. This
   is a long-term stretch goal that sits on top of everything above.

## Full mod compatibility: what's possible on a 1.21.1 client

| Data | On the client in 1.21.1? | How we use it |
|---|---|---|
| Recipes (all types, including modded) | **Yes.** The server syncs its whole RecipeManager to the client in 1.21.1 (this stopped in 1.21.2). | The planner already reads the live RecipeManager (`MinecraftRecipeSource`), but only for crafting, smelting, blasting and smoking. Add stonecutting, smithing and campfire, and handle modded recipe types generically where the ingredients and result can be read. |
| Item and block tags (`c:` common tags, `minecraft:logs`, `mineable/*`) | Yes, synced | Use tags for substitution (any log, any `c:ingots/iron`) and to choose the right tool (`mineable/pickaxe`, `incorrect_for_*_tool`), never item-name lists. |
| Block properties and tool tiers | Yes | Use `BlockState.requiresCorrectToolForDrops` and `ItemStack.isCorrectToolForDrops` / `getDestroySpeed` so modded tools and blocks work. |
| Loot tables (block drops, mob drops) | **No** (server only) | Learn drops by watching what drops when the bot breaks or kills something, and cache them per block or entity. JEI loot plugins are a hint when they're installed. |
| Modded worldgen (ores) | No | OreSim stays vanilla-only. For modded ores, use a block tracker of ores seen (`c:ores/*` tags). |
| Modded machine recipes (Create, Mekanism, etc.) | Partly. In RecipeManager, but each machine has its own way of being used. | Through JEI: show them in plans as "manual step" or "needs machine X". Driving machines needs an adapter per mod, so that's later. |
| Containers | Yes, through the menu's slots | Already generic through `AbstractContainerMenu` slots; check that modded chests, barrels and backpacks just work. |

### JEI / EMI integration (optional, like Baritone)

- JEI is `compileOnly`, plus `localRuntime` for dev. Only `com.utilitysuite.compat.jei` may import `mezz.jei.*`, and only
  after checking `ModList.isLoaded("jei")`.
- A `@JeiPlugin` IModPlugin grabs `IJeiRuntime` in `onRuntimeAvailable`.
- **Recipe lookup:** use JEI's recipe manager to look up recipes of every category for an item. This includes modded
  categories that RecipeManager can't describe generically. The planner uses them as extra recipe sources (tagged with
  their category) or shows them as manual steps.
- **Recipe transfer:** for modded crafting stations, use JEI's recipe-transfer handlers to fill the grid. This is the
  same thing as the player clicking "+" in JEI, so it's server-legal. Run it through IContainerClicker or a lease so the
  input rules still hold.
- **UI hooks:**
  - "Plan this" from a JEI item: alt-click or a keybind on the hovered ingredient sends `gather <item>` to GoalConsole.
  - JEI's bookmark list becomes a shopping list.
- **EMI** (common on NeoForge packs) gets the same adapter behind one `IRecipeIndex` interface in core.api. REI can come
  later.

### Other compatibility targets

- **Rendering:** overlays must draw correctly with Sodium/Embeddium and Iris shaders. Test with them on.
- **Map mods:** import and export waypoints with Xaero's Minimap/World Map and JourneyMap.
- **Modded storage:** Sophisticated Backpacks/Storage, Storage Drawers and AE2/RS terminals as later StorageMemory
  adapters. Start with read-only indexing.
- **Compat test bed:**
  - A `runPlaytest` variant with JEI plus one content mod (for example Farmer's Delight, or a small ore mod) that checks
    `gather <modded item>` plans from synced recipes and tags.
  - The user's ATM10 CurseForge instance is a good real-world smoke test, as long as it's run manually.

## Sources

- https://github.com/gaucho-matrero/altoclef
- https://github.com/MiranCZ/altoclef/releases
- https://github.com/Meloweh/altoclef/releases
- https://github.com/Ladysnake/automatone
- https://npmjs.com/package/mineflayer
- https://www.curseforge.com/minecraft/mc-mods/jei (JEI says recipes moved server-side from 1.21.2)
- https://docs.neoforged.net/docs/1.21.1/resources/server/recipes/
