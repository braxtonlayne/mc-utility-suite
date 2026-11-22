# inventory-smarts (2026-11-22)

## What changed

### A. Auto-Eat from the whole inventory (jobs/survival)
- `FoodSelector.choose` ranks any number of slots (36 in use), allocation free. New overload with `preferSaturation`
  (saturation weighted 3x, used while a goal job holds MOVEMENT); an equal score prefers the hotbar. Unsafe foods are
  still skipped everywhere.
- `FoodSelector.shouldEat(..., longLegAhead)`: also eats below 18 hunger when a goal job holds MOVEMENT (sprint leg).
- `SurvivalAutopilot` eat state machine: PULL, USE, BACK. Food in slots 9-35 is pulled with one `SWAP` click through
  `IContainerClicker` under the SURVIVAL lease (HOTBAR + WORLD_INTERACT), into an empty hotbar slot if there is one,
  otherwise the selected slot. Then it selects the slot and holds USE. After eating it queues the same SWAP again when
  "Swap back" is on. Nothing starts with a screen open, a non-player container menu, or a block break in progress
  (`mc.gameMode.isDestroying()`); a swap that did not land aborts quietly.
- Settings (survival module): `use_inventory_food` (on), `eat_swap_back` (on).

### B. Class-aware hotbar refill (tools/inventory)
- `HotbarRefillPlanner.ItemClasses` (int codes: pickaxe, axe, shovel, hoe, sword, bow, shield, totem, food, blocks,
  plus a score per slot) is filled by `McInventory.readClasses` (tool tier by uses, enchantment count, durability
  bucket; under 10% durability is -1 = never used; food by nutrition and saturation, foods with effects excluded;
  blocks by stack size).
- When a hotbar slot is empty: identical item first, otherwise the best same-class item from slots 9-35.
  `plan(inv, classes, mask, pct, pinnedMask, sameClassUnpinned, sameClassPinned, out)`. Pinned slots only take their own
  item unless "Allow same class (pinned)" is on. `observe()` still forgets slots the user emptied with a screen open.
  Food and block classes respect the Food/Blocks category switches. The old 4-argument `plan` is unchanged.
- Settings (inventory module): `refill_same_class` (on, unpinned slots), `refill_same_class_pinned` (off, advanced).
  A per-slot option is not separate: pin state plus the two switches cover it.
- Lang strings: `integration/inventory-smarts.lang.json` (settings use literal names today; keys ready if the
  coordinator switches them to translatables).

## Known limits
- If the eat lease is preempted between PULL and BACK, the pulled food stays in the hotbar (no lease to click with).
- Swap target when the hotbar is full is the selected slot, which can be a tool mid-job; it is restored afterwards.

## In-game checks for the owner
1. Steak in the pack, nothing edible in the hotbar, hunger at or below 14: the player eats and the hotbar slot is
   restored (empty slot goes empty again; otherwise the previous item returns).
2. Mine with a stack of pickaxes in the pack: when the held one breaks, the next best (highest tier, enchanted) one
   appears in that slot within about half a second without the job stopping.
3. Pin a slot holding a pickaxe: when it breaks, the slot stays empty (turn on "Allow same class (pinned)" to refill it).
4. Open a chest or the inventory with food needed: no clicks happen while any container screen is open, and no pull
   happens while a block is being broken.
