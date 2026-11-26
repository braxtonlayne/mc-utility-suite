package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.NestedContainer;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.jobs.planner.BrewingPlanner;
import com.utilitysuite.jobs.planner.CrafterTemplate;
import com.utilitysuite.jobs.planner.EnderChestPlanner;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.planner.WaterFill;
import com.utilitysuite.jobs.planner.FuelTable;
import com.utilitysuite.jobs.planner.FurnaceBatchPlanner;
import com.utilitysuite.jobs.planner.McRecipeCatalog;
import com.utilitysuite.jobs.planner.RecipeKind;
import com.utilitysuite.jobs.planner.SlotTransfer;
import com.utilitysuite.jobs.planner.StepKind;
import net.minecraft.world.inventory.BrewingStandMenu;
import com.utilitysuite.jobs.planner.Items;
import com.utilitysuite.jobs.planner.PlanStep;
import com.utilitysuite.jobs.storage.ShulkerItems;
import com.utilitysuite.jobs.storage.StorageMemoryModule;
import com.utilitysuite.jobs.storage.hall.HallSlotPick;
import com.utilitysuite.jobs.storage.hall.HallSortSteps;
import com.utilitysuite.jobs.storage.hall.InChestMerge;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.util.context.ContextMap;
import net.minecraft.world.item.crafting.display.ShapedCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.ShapelessCraftingRecipeDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplay;
import net.minecraft.world.item.crafting.display.SlotDisplayContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * In-game {@link WorldActions}: opens stations and containers (pathing next to them through the driver, then a real
 * right-click via the input smoother), places a crafting table or furnace from the inventory when none is nearby, and
 * moves items with {@link IContainerClicker} QUICK_MOVE clicks under the executor's lease.
 * <p>
 * Recipe placement uses the vanilla recipe-book request through {@code IContainerClicker.placeRecipe} (needs an
 * active lease with HOTBAR); the crafted result is then taken with
 * a clicker QUICK_MOVE. The server only places recipes the player has unlocked in the recipe book; for a locked
 * shaped or shapeless recipe the grid is filled by hand with PICKUP clicks planned by {@link ManualCraftPlanner}
 * (a manual craft also unlocks the recipe, so later rounds go back to the recipe book).
 */
public final class MinecraftWorldActions implements WorldActions {

    private enum Op { NONE, CRAFT, SMELT, FETCH, DEPOSIT, SHULKER, STATION, ENDER, HALL }

    private enum Phase { FIND, SELECT_ITEM, CLEAR_OBSTRUCTION, CLEAR_WALK, AIM_PLACE, WAIT_PLACED, OPEN, WAIT_OPEN, WORK, WAIT_CLICKS, COLLECT, BREAK, PICKUP, WATER_WALK, WATER_AIM, WATER_WAIT, DONE }

    private static final int OPEN_TIMEOUT = 200;

    private final Supplier<MiningDriver> driver;
    private final MinecraftInventoryView inv;
    private final FuelTable fuels = FuelTable.vanilla();
    private final IdentityHashMap<Block, String> blockIds = new IdentityHashMap<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private ResourceKey<Level> dimKey;
    private String dimId = "minecraft:overworld";

    private Op op = Op.NONE;
    private Phase phase = Phase.DONE;
    private Result result = Result.DONE;
    private String message = "";
    private ControlLease lease;
    private int timer;
    /** Timer value when the recipe was placed for the current craft; -1 until placed. */
    private int placedAt = -1;
    /** True once this action walked to open ground to find a station spot (only tried once per action). */
    /** Walks to open ground made while looking for a station spot (each one searches farther out). */
    private int spotWalks;
    private static final int MAX_SPOT_WALKS = 3;
    private int obstructionClears;
    private static final int MAX_OBSTRUCTION_CLEARS = 3;
    private long clearPos = Long.MIN_VALUE;
    /** Ticks to wait for a recipe unlock packet before giving up on a locked recipe. */
    private static final int RECIPE_UNLOCK_WAIT = 20;
    private PlanStep step;
    private McRecipeCatalog.Entry recipe;
    /** The craft recipe reduced to ids for hand placement; null for special recipes (those need the recipe book). */
    private ManualCraftPlanner.Shape manualShape;
    /** The current craft round fills the grid by hand. */
    private boolean manual;
    /** Recipe-book placement produced no result once this action; place by hand from then on. */
    private boolean bookFailed;
    /** Grid-clearing rounds this action (guards against a grid that never empties, e.g. a full inventory). */
    private int gridClears;
    private static final int MAX_GRID_CLEARS = 3;
    private String station;
    private long stationPos = Long.MIN_VALUE;
    private long supportPos;
    /** Face of {@link #supportPos} that gets clicked (points toward the station spot). */
    private Direction supportFace = Direction.UP;
    private double aimX, aimY, aimZ;
    private int craftsLeft;
    private int startCount;
    /**
     * CRAFT: items the step's crafts make in all. The planner's step count is only what it asked for beyond the
     * virtual spares of the same node (planks for the table, then the pickaxe, then the sticks that reuse the
     * pickaxe's spare plank: count 8, 3 crafts = 12), so stopping at the count left the last plank craft undone.
     */
    private int craftTarget;
    private Map<String, Integer> wanted = Map.of();
    private final Map<String, Integer> before = new LinkedHashMap<>();
    private Map<String, Integer> transferred = Map.of();
    /** SHULKER: the item being unpacked and how many boxes of the placed colour were carried before placing. */
    private String restockItem;
    private int boxBefore;

    public MinecraftWorldActions(Supplier<MiningDriver> driver, MinecraftInventoryView inv) {
        this.driver = driver;
        this.inv = inv;
    }

    // ---- begin ------------------------------------------------------------------------------------------------------

    @Override
    public void beginCraft(PlanStep s, ControlLease l) {
        start(Op.CRAFT, s, l);
        Optional<McRecipeCatalog.Entry> r = lookupRecipe(s.recipeId());
        if (r.isEmpty()) {
            fail("Unknown recipe " + s.recipeId());
            return;
        }
        recipe = r.get();
        manualShape = shapeOf(recipe);
        craftsLeft = Math.max(1, s.crafts());
        startCount = inv.count(s.itemId());
        craftTarget = Math.max(s.count(), craftsLeft * recipe.resultCount(Minecraft.getInstance().level));
        boolean table = recipe.needsTable();
        station = table ? Items.CRAFTING_TABLE : null;
        phase = table ? Phase.FIND : Phase.WORK;
    }

    @Override
    public void beginSmelt(PlanStep s, ControlLease l) {
        start(Op.SMELT, s, l);
        Optional<McRecipeCatalog.Entry> r = lookupRecipe(s.recipeId());
        if (r.isEmpty()) {
            fail("Unknown recipe " + s.recipeId());
            return;
        }
        recipe = r.get();
        RecipeKind t = recipe.kind();
        station = t == RecipeKind.BLASTING ? Items.BLAST_FURNACE : t == RecipeKind.SMOKING ? Items.SMOKER
                : Items.FURNACE;
        startCount = inv.count(s.itemId());
        craftsLeft = Math.max(1, s.crafts());
        phase = Phase.FIND;
    }

    @Override
    public void beginFetch(long packedPos, Map<String, Integer> items, ControlLease l) {
        if (EnderChestPlanner.isCarried(packedPos)) {
            beginCarriedEnderFetch(items, l);
            return;
        }
        start(Op.FETCH, null, l);
        beginTransfer(packedPos, items);
    }

    /**
     * EnderChestPlanner PLACE_CARRIED: places the carried ender chest on a safe floor spot next to the player (the
     * shulker-restock placement path), takes the items, then mines the chest back with the Silk Touch pickaxe and picks
     * it up. Fails before placing anything without a Silk Touch pickaxe: an ender chest mined without it is lost.
     */
    private void beginCarriedEnderFetch(Map<String, Integer> items, ControlLease l) {
        start(Op.ENDER, null, l);
        int silk = inv.silkTouchPickaxeSlot();
        if (inv.count(EnderChestPlanner.ENDER_CHEST) <= 0) {
            fail("No ender chest carried");
            return;
        }
        if (silk < 0) {
            fail("No Silk Touch pickaxe: not placing the ender chest (it could not be picked back up)");
            return;
        }
        if (silk >= 9) {
            // bring the pickaxe to the hotbar now (inventory screen, before anything opens) so the break never waits
            Services.clicker().queueClick(lease, silk, SILK_HOTBAR_SLOT, IContainerClicker.Click.SWAP);
        }
        station = EnderChestPlanner.ENDER_CHEST;
        boxBefore = inv.count(station);
        wanted = Map.copyOf(items);
        before.clear();
        for (String id : wanted.keySet()) {
            before.put(id, inv.count(id));
        }
        phase = Phase.SELECT_ITEM;
        timer = 0;
    }

    /** Hotbar slot the Silk Touch pickaxe is swapped into when it is in the main inventory (8 holds the station). */
    private static final int SILK_HOTBAR_SLOT = 7;

    @Override
    public void beginDeposit(long packedPos, Map<String, Integer> items, ControlLease l) {
        start(Op.DEPOSIT, null, l);
        beginTransfer(packedPos, items);
    }

    @Override
    public void beginShulkerRestock(String boxItemId, String itemId, int count, ControlLease l) {
        start(Op.SHULKER, null, l);
        LocalPlayer player = Minecraft.getInstance().player;
        String chosen = null;
        if (player != null) {
            Inventory pinv = player.getInventory();
            for (int i = 0; i < 36; i++) {
                NestedContainer box = ShulkerItems.read(pinv.getNonEquipmentItems().get(i));
                if (box != null && box.count(itemId) > 0
                        && (chosen == null || box.containerItemId().equals(boxItemId))) {
                    chosen = box.containerItemId();
                }
            }
        }
        if (chosen == null) {
            fail("No carried shulker box holds " + itemId);
            return;
        }
        station = chosen;
        restockItem = itemId;
        boxBefore = inv.count(chosen);
        wanted = Map.of(itemId, count);
        before.clear();
        before.put(itemId, inv.count(itemId));
        phase = Phase.SELECT_ITEM;
        timer = 0;
    }

    /** True when the stack is a shulker box holding {@link #restockItem} (SELECT_ITEM phase only, not per tick). */
    private boolean boxHolds(ItemStack stack) {
        NestedContainer box = ShulkerItems.read(stack);
        return box != null && box.count(restockItem) > 0;
    }

    private void beginTransfer(long packedPos, Map<String, Integer> items) {
        wanted = Map.copyOf(items);
        before.clear();
        for (String id : wanted.keySet()) {
            before.put(id, inv.count(id));
        }
        stationPos = packedPos;
        phase = Phase.OPEN;
    }

    private void start(Op o, PlanStep s, ControlLease l) {
        op = o;
        step = s;
        lease = l;
        result = Result.RUNNING;
        message = "";
        timer = 0;
        placedAt = -1;
        manual = false;
        bookFailed = false;
        gridClears = 0;
        spotWalks = 0;
        obstructionClears = 0;
        clearPos = Long.MIN_VALUE;
        stationPos = Long.MIN_VALUE;
        transferred = Map.of();
    }

    // ---- poll -------------------------------------------------------------------------------------------------------

    @Override
    public Result poll() {
        if (result != Result.RUNNING) {
            return result;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || lease == null || !lease.isActive()
                || !lease.covers(ControlChannel.HOTBAR)) {
            fail("Lost control of the inventory");
            return result;
        }
        timer++;
        switch (phase) {
            case FIND -> find(mc);
            case SELECT_ITEM -> selectStationItem(player);
            case CLEAR_OBSTRUCTION -> clearObstruction(mc);
            case CLEAR_WALK -> {
                MiningDriver.Status st = driver.get().status();
                if (st == MiningDriver.Status.ARRIVED) {
                    phase = Phase.SELECT_ITEM;
                    timer = 0;
                } else if (st == MiningDriver.Status.FAILED || st == MiningDriver.Status.UNAVAILABLE || timer > 400) {
                    if (spotWalks < MAX_SPOT_WALKS && st != MiningDriver.Status.UNAVAILABLE) {
                        driver.get().cancel();
                        phase = Phase.SELECT_ITEM; // look again; the next walk goes to a farther ring
                        timer = 0;
                    } else {
                        fail("No free spot to place " + station + " (could not walk to open ground after "
                                + spotWalks + " tries)");
                    }
                }
            }
            case AIM_PLACE -> aimAndPlace(mc);
            case WAIT_PLACED -> {
                if (station.equals(blockAt(mc.level, stationPos))) {
                    phase = Phase.OPEN;
                } else if (timer > 40) {
                    fail("Could not place " + station);
                }
            }
            case OPEN -> {
                driver.get().interactBlock(stationPos, lease);
                phase = Phase.WAIT_OPEN;
                timer = 0;
            }
            case WAIT_OPEN -> waitOpen(player);
            case WORK -> work(mc, player);
            case WAIT_CLICKS -> {
                if (Services.clicker().pending() == 0 && timer > 3) {
                    afterClicks(player);
                }
            }
            case COLLECT -> {
                if (op == Op.HALL) {
                    hallMergeTick(player);
                } else if (op == Op.STATION) {
                    stationTick(player);
                } else {
                    collectSmelted(player);
                }
            }
            case BREAK -> breakBox(mc, player);
            case PICKUP -> pickUpBox(player);
            case WATER_WALK -> waterWalk(player);
            case WATER_AIM -> waterAim(player);
            case WATER_WAIT -> waterWait(player);
            case DONE -> {
            }
        }
        return result;
    }

    private void find(Minecraft mc) {
        long found = findBlockNear(mc, station, 4);
        if (found != Long.MIN_VALUE) {
            stationPos = found;
            phase = Phase.OPEN;
            return;
        }
        if (inv.count(station) <= 0) {
            fail("No " + station + " nearby or in the inventory");
            return;
        }
        phase = Phase.SELECT_ITEM;
        timer = 0;
    }

    /** Puts the station item in the hand (swapping it into hotbar slot 8 when it is in the main inventory). */
    private void selectStationItem(LocalPlayer player) {
        Inventory pinv = player.getInventory();
        net.minecraft.world.item.Item item = inv.itemOf(station);
        int hotbar = -1;
        int main = -1;
        for (int i = 0; i < 36; i++) {
            if (pinv.getNonEquipmentItems().get(i).getItem() == item && (op != Op.SHULKER || boxHolds(pinv.getNonEquipmentItems().get(i)))) {
                if (i < 9) {
                    hotbar = i;
                    break;
                } else if (main < 0) {
                    main = i;
                }
            }
        }
        if (hotbar < 0 && main >= 0 && timer == 1) {
            if (!(player.containerMenu instanceof InventoryMenu)) {
                player.closeContainer();
            }
            Services.clicker().queueClick(lease, main, 8, IContainerClicker.Click.SWAP);
            return;
        }
        if (hotbar < 0) {
            if (timer > 20) {
                fail("Could not move " + station + " to the hotbar");
            }
            return;
        }
        Services.input().selectHotbarSlot(lease, hotbar);
        long spot = findPlacementSpot(Minecraft.getInstance());
        if (spot == Long.MIN_VALUE && (op == Op.SHULKER || op == Op.ENDER)) {
            // Nothing placed yet: abort cleanly rather than digging or wandering with a box in hand.
            fail("No safe spot next to the player to place the shulker box");
            return;
        }
        if (spot == Long.MIN_VALUE) {
            // Typically inside a tree canopy after chopping. Like a player: first break a leaf block next to you to make
            // room, then walk to open ground and look again, farther each try.
            if (obstructionClears < MAX_OBSTRUCTION_CLEARS && findSoftObstruction(Minecraft.getInstance())) {
                obstructionClears++;
                phase = Phase.CLEAR_OBSTRUCTION;
                timer = 0;
                return;
            }
            long ground = spotWalks >= MAX_SPOT_WALKS ? Long.MIN_VALUE
                    : findOpenGround(Minecraft.getInstance(), spotWalks == 0 ? 0 : 2 + spotWalks * 3, 8 + spotWalks * 5);
            if (ground != Long.MIN_VALUE && driver.get().isAvailable()) {
                spotWalks++;
                driver.get().gotoCoord(ground, 0);
                phase = Phase.CLEAR_WALK;
                timer = 0;
                return;
            }
            fail("No free spot to place " + station + (spotWalks > 0 ? " after " + spotWalks + " walks" : ""));
            return;
        }
        // findPlacementSpot chose the support block, its clicked face and a visible aim point on that face
        stationPos = spot;
        Services.input().setRotationTarget(lease, aimAt(player, aimX, aimY, aimZ));
        phase = Phase.AIM_PLACE;
        timer = 0;
    }

    private void aimAndPlace(Minecraft mc) {
        IInputSmoother input = Services.input();
        if (!input.isRotationSettled(2f) && timer < 30) {
            return;
        }
        HitResult hit = mc.hitResult;
        if (hit instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().asLong() == supportPos && bh.getDirection() == supportFace) {
            input.scheduleKeyTap(lease, SimKey.USE, 1, 2);
            input.clearRotation(lease);
            phase = Phase.WAIT_PLACED;
            timer = 0;
        } else if (timer > 40) {
            fail("Could not aim at a placement spot for " + station);
        }
    }

    private void waitOpen(LocalPlayer player) {
        MiningDriver d = driver.get();
        if (d.status() == MiningDriver.Status.FAILED || d.status() == MiningDriver.Status.UNAVAILABLE) {
            fail("Could not reach " + (station != null ? station : "container") + ": " + d.failureReason());
            return;
        }
        if (!(player.containerMenu instanceof InventoryMenu) && d.status() == MiningDriver.Status.ARRIVED) {
            phase = Phase.WORK;
            timer = 0;
        } else if (timer > OPEN_TIMEOUT) {
            fail("Container did not open");
        }
    }

    private void work(Minecraft mc, LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        switch (op) {
            case CRAFT -> {
                if (craftsLeft <= 0 || inv.count(step.itemId()) - startCount >= craftTarget) {
                    finish(player, "Crafted " + step.itemId());
                    return;
                }
                if (placedAt < 0) {
                    if (mc.gameMode == null) {
                        fail("No game mode");
                        return;
                    }
                    int bookId = bookFailed ? -1 : McRecipeCatalog.bookIndex(mc, recipe);
                    if (bookId >= 0) {
                        // 26.3: the recipe-book request names the display id (core parses the decimal string)
                        if (!Services.clicker().placeRecipe(lease, Integer.toString(bookId), false)) {
                            fail("Could not place recipe " + recipe.id());
                            return;
                        }
                        manual = false;
                        placedAt = timer;
                        return;
                    }
                    if (manualShape == null) {
                        // Special recipe without a fixed pattern: only the recipe book can place it. Vanilla unlocks
                        // a recipe when an ingredient reaches the inventory and the packet can lag, so wait briefly.
                        if (timer > RECIPE_UNLOCK_WAIT) {
                            fail("Recipe " + recipe.id() + " is not unlocked in your recipe book");
                        }
                        return;
                    }
                    // Locked recipe (fresh world, or the unlock packet is late): fill the grid by hand.
                    placeByHand(player, menu);
                } else if (timer >= placedAt + 5) {
                    Slot result = menu.getSlot(0);
                    if (result.getItem().isEmpty()) {
                        if (manual) {
                            int size = gridSizeOf(menu);
                            String[] grid = gridIds(menu, size);
                            fail(grid != null && ManualCraftPlanner.matches(manualShape, size, grid)
                                    ? "Filled the grid for " + step.itemId() + " but got no result (recipe locked by"
                                    + " limited crafting?)"
                                    : "Missing ingredients for " + step.itemId());
                        } else if (manualShape != null && !bookFailed) {
                            bookFailed = true; // the server refused the book request: place by hand next round
                            placedAt = -1;
                            timer = 0;
                        } else {
                            fail("Missing ingredients for " + step.itemId());
                        }
                        return;
                    }
                    Services.clicker().queueClick(lease, 0, 0, IContainerClicker.Click.QUICK_MOVE);
                    craftsLeft--;
                    placedAt = -1;
                    manual = false;
                    phase = Phase.WAIT_CLICKS;
                    timer = 0;
                }
            }
            case SMELT -> {
                if (!(menu instanceof AbstractFurnaceMenu)) {
                    fail("Opened block is not a furnace");
                    return;
                }
                loadFurnace(player, menu);
                phase = Phase.WAIT_CLICKS;
                timer = 0;
            }
            case FETCH, DEPOSIT, SHULKER, ENDER -> {
                if (op == Op.SHULKER && !(menu instanceof ShulkerBoxMenu)) {
                    fail("Opened block is not a shulker box");
                    return;
                }
                if (op == Op.ENDER && !(menu instanceof net.minecraft.world.inventory.ChestMenu)) {
                    fail("Opened block is not the ender chest");
                    return;
                }
                Inventory pinv = player.getInventory();
                Map<String, Integer> left = new LinkedHashMap<>(wanted);
                for (Slot s : menu.slots) {
                    boolean playerSlot = s.container == pinv;
                    if (playerSlot == (op != Op.DEPOSIT) || !s.hasItem()) {
                        continue;
                    }
                    String id = inv.idOf(s.getItem().getItem());
                    int need = left.getOrDefault(id, 0);
                    if (need <= 0) {
                        continue;
                    }
                    Services.clicker().queueClick(lease, s.index, 0, IContainerClicker.Click.QUICK_MOVE);
                    left.put(id, need - s.getItem().getCount());
                }
                phase = Phase.WAIT_CLICKS;
                timer = 0;
            }
            case STATION -> stationWork(player, menu);
            case HALL -> hallWork(player, menu);
            default -> fail("No operation");
        }
    }

    // ---- storage hall sort steps ---------------------------------------------------------------------------------------

    /** Hotbar + main inventory slots ({@code Inventory.items}). */
    private static final int PLAYER_SLOTS = 36;
    /** Merge passes per container before giving up on what is left (a pass replans from the live menu). */
    private static final int MAX_MERGE_PASSES = 3;
    /**
     * Items the last HALL_TAKE brought into each player inventory slot. HALL_PUT only moves these, so the player's own
     * tools and stacks never end up in a hall chest. Reset by the next take.
     */
    private final int[] hallLedger = new int[PLAYER_SLOTS];
    /**
     * Player slots when the current hall step was first started, and the ledger then. A step retried after a pause or
     * a failed open measures against these, so items moved by an interrupted attempt are neither lost from the ledger
     * nor moved twice.
     */
    private final int[] hallBefore = new int[PLAYER_SLOTS];
    private final String[] hallBeforeKey = new String[PLAYER_SLOTS];
    private final int[] hallLedgerStart = new int[PLAYER_SLOTS];
    private PlanStep hallBaseStep;
    private final Map<String, Integer> hallMoved = new LinkedHashMap<>();
    private int hallMergePasses;
    private int hallMerged;

    @Override
    public void beginHallStep(PlanStep s, ControlLease l) {
        start(Op.HALL, s, l);
        hallMoved.clear();
        hallMergePasses = 0;
        hallMerged = 0;
        beginTransfer(s.storagePos(), Map.of());
    }

    private static boolean isStorageMenu(AbstractContainerMenu menu) {
        return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu;
    }

    /** WORK: the container is open. Queues the take or put clicks (merge-only steps go straight to merging). */
    private void hallWork(LocalPlayer player, AbstractContainerMenu menu) {
        if (!isStorageMenu(menu)) {
            fail("Opened block is not a chest, barrel or shulker box");
            return;
        }
        if (!menu.getCarried().isEmpty()) {
            fail("An item is held on the cursor");
            return;
        }
        Inventory pinv = player.getInventory();
        if (step.kind() == StepKind.HALL_MERGE) {
            startMerge();
            return;
        }
        if (step != hallBaseStep) { // first attempt of this step (identity: a retry is the same object)
            hallBaseStep = step;
            if (step.kind() == StepKind.HALL_TAKE) {
                java.util.Arrays.fill(hallLedger, 0); // a new trip: leftovers of earlier trips are the player's again
            }
            System.arraycopy(hallLedger, 0, hallLedgerStart, 0, PLAYER_SLOTS);
            snapshotPlayer(pinv);
        }
        if (step.kind() == StepKind.HALL_TAKE) {
            hallTake(menu, pinv);
        } else {
            hallPut(menu, pinv);
        }
        phase = Phase.WAIT_CLICKS;
        timer = 0;
    }

    /** Menu slot indices of the container's own slots, in container order. */
    private static int[] containerSlots(AbstractContainerMenu menu, Inventory pinv) {
        int n = 0;
        for (Slot s : menu.slots) {
            if (s.container != pinv) {
                n++;
            }
        }
        int[] idx = new int[n];
        int k = 0;
        for (Slot s : menu.slots) {
            if (s.container != pinv) {
                idx[k++] = s.index;
            }
        }
        return idx;
    }

    private void snapshotPlayer(Inventory pinv) {
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            ItemStack st = pinv.getNonEquipmentItems().get(i);
            hallBefore[i] = st.isEmpty() ? 0 : st.getCount();
            hallBeforeKey[i] = st.isEmpty() ? null
                    : HallSortSteps.key(inv.idOf(st.getItem()), StorageMemoryModule.variantKey(st));
        }
    }

    /**
     * Slot-precise take: each listed slot is checked (item id and stored enchantments) right before it is clicked;
     * a slot that changed is replaced by another holding the same item+variant (see {@link HallSlotPick}). Split
     * stacks go into reserved empty player slots first, whole stacks follow by shift-click.
     */
    private void hallTake(AbstractContainerMenu menu, Inventory pinv) {
        int[] idx = containerSlots(menu, pinv);
        int n = idx.length;
        String[] item = new String[n];
        String[] variant = new String[n];
        int[] count = new int[n];
        for (int k = 0; k < n; k++) {
            ItemStack st = menu.getSlot(idx[k]).getItem();
            variant[k] = "";
            if (!st.isEmpty()) {
                item[k] = inv.idOf(st.getItem());
                variant[k] = StorageMemoryModule.variantKey(st);
                count[k] = st.getCount();
            }
        }
        // what an interrupted attempt of this step already took is not taken again
        Map<String, Integer> already = new LinkedHashMap<>();
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            ItemStack st = pinv.getNonEquipmentItems().get(i);
            int d = (st.isEmpty() ? 0 : st.getCount()) - hallBefore[i];
            if (d > 0) {
                already.merge(HallSortSteps.key(inv.idOf(st.getItem()), StorageMemoryModule.variantKey(st)), d,
                        Integer::sum);
            }
        }
        List<HallSortSteps.Take> wanted = new ArrayList<>();
        for (HallSortSteps.Take w : HallSortSteps.takes(step)) {
            String key = HallSortSteps.key(w.item(), w.variant());
            int got = Math.min(w.count(), already.getOrDefault(key, 0));
            already.merge(key, -got, Integer::sum);
            if (w.count() > got) {
                wanted.add(new HallSortSteps.Take(w.slot(), w.count() - got, w.item(), w.variant()));
            }
        }
        List<HallSlotPick.Pick> picks = HallSlotPick.pick(wanted, item, variant, count);
        boolean[] reserved = new boolean[menu.slots.size()];
        for (HallSlotPick.Pick p : picks) {
            if (p.count() >= count[p.slot()]) {
                continue;
            }
            int dst = emptyPlayerSlot(menu, pinv, reserved);
            if (dst < 0) {
                continue; // no free slot to split into: this part stays (reported short)
            }
            reserved[dst] = true;
            for (SlotTransfer.Step c : SlotTransfer.exact(idx[p.slot()], count[p.slot()], dst, p.count())) {
                Services.clicker().queueClick(lease, c.slot(), c.button(), c.click());
            }
        }
        for (HallSlotPick.Pick p : picks) {
            if (p.count() >= count[p.slot()]) {
                Services.clicker().queueClick(lease, idx[p.slot()], 0, IContainerClicker.Click.QUICK_MOVE);
            }
        }
    }

    private static int emptyPlayerSlot(AbstractContainerMenu menu, Inventory pinv, boolean[] reserved) {
        for (Slot s : menu.slots) {
            if (s.container == pinv && s.getContainerSlot() < PLAYER_SLOTS && !s.hasItem() && !reserved[s.index]) {
                return s.index;
            }
        }
        return -1;
    }

    /** Puts only ledger items (what the last take brought) of each listed item+variant into the open container. */
    private void hallPut(AbstractContainerMenu menu, Inventory pinv) {
        int[] idx = containerSlots(menu, pinv);
        boolean[] reserved = new boolean[idx.length];
        List<Integer> whole = new ArrayList<>();
        // ledger now = ledger at the first attempt minus what an interrupted attempt already put
        Map<String, Integer> already = new LinkedHashMap<>();
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            ItemStack st = pinv.getNonEquipmentItems().get(i);
            int d = Math.max(0, Math.min(hallBefore[i] - (st.isEmpty() ? 0 : st.getCount()), hallLedgerStart[i]));
            hallLedger[i] = hallLedgerStart[i] - d;
            if (d > 0 && hallBeforeKey[i] != null) {
                already.merge(hallBeforeKey[i], d, Integer::sum);
            }
        }
        for (HallSortSteps.Put p : HallSortSteps.puts(step)) {
            String key = HallSortSteps.key(p.item(), p.variant());
            int done = Math.min(p.count(), already.getOrDefault(key, 0));
            already.merge(key, -done, Integer::sum);
            int left = p.count() - done;
            for (Slot s : menu.slots) {
                if (left <= 0) {
                    break;
                }
                int i = s.getContainerSlot();
                if (s.container != pinv || i < 0 || i >= PLAYER_SLOTS || hallLedger[i] <= 0 || !s.hasItem()) {
                    continue;
                }
                ItemStack st = s.getItem();
                if (!p.item().equals(inv.idOf(st.getItem()))
                        || !p.variant().equals(StorageMemoryModule.variantKey(st)) || whole.contains(s.index)) {
                    continue;
                }
                int k = Math.min(left, Math.min(hallLedger[i], st.getCount()));
                if (k >= st.getCount()) {
                    whole.add(s.index);
                } else {
                    int dst = containerTarget(menu, idx, st, k, reserved);
                    if (dst < 0) {
                        continue;
                    }
                    reserved[dst] = true;
                    for (SlotTransfer.Step c : SlotTransfer.exact(s.index, st.getCount(), idx[dst], k)) {
                        Services.clicker().queueClick(lease, c.slot(), c.button(), c.click());
                    }
                }
                left -= k;
            }
        }
        for (int w : whole) {
            Services.clicker().queueClick(lease, w, 0, IContainerClicker.Click.QUICK_MOVE);
        }
    }

    /** A container slot that takes {@code k} of the stack: a matching partial with room first, else an empty one. */
    private static int containerTarget(AbstractContainerMenu menu, int[] idx, ItemStack st, int k,
                                       boolean[] reserved) {
        int empty = -1;
        for (int c = 0; c < idx.length; c++) {
            if (reserved[c]) {
                continue;
            }
            Slot slot = menu.getSlot(idx[c]);
            ItemStack in = slot.getItem();
            if (in.isEmpty()) {
                if (empty < 0) {
                    empty = c;
                }
            } else if (ItemStack.isSameItemSameComponents(in, st)
                    && Math.min(in.getMaxStackSize(), slot.getMaxStackSize(in)) - in.getCount() >= k) {
                return c;
            }
        }
        return empty;
    }

    /** WAIT_CLICKS done: what moved per player slot, then the merge pass or finish. */
    private void hallAfterClicks(LocalPlayer player) {
        Inventory pinv = player.getInventory();
        boolean take = step.kind() == StepKind.HALL_TAKE;
        for (int i = 0; i < PLAYER_SLOTS; i++) {
            ItemStack st = pinv.getNonEquipmentItems().get(i);
            int now = st.isEmpty() ? 0 : st.getCount();
            if (take) {
                int d = Math.max(0, now - hallBefore[i]);
                hallLedger[i] = d;
                if (d > 0) {
                    hallMoved.merge(HallSortSteps.key(inv.idOf(st.getItem()), StorageMemoryModule.variantKey(st)), d,
                            Integer::sum);
                }
            } else {
                int d = Math.max(0, Math.min(hallBefore[i] - now, hallLedgerStart[i]));
                hallLedger[i] = hallLedgerStart[i] - d;
                if (d > 0 && hallBeforeKey[i] != null) {
                    hallMoved.merge(hallBeforeKey[i], d, Integer::sum);
                }
            }
        }
        if (HallSortSteps.mergeAfter(step)) {
            startMerge();
            return;
        }
        hallFinish(player, "");
    }

    private void startMerge() {
        phase = Phase.COLLECT;
        timer = 0;
    }

    /**
     * COLLECT for hall steps: one merge pass once the previous clicks are done. Stacking kinds come from the live
     * menu (same item and components), {@link InChestMerge} plans the pass and its clicks are queued at once; the
     * next pass re-reads the menu. Done when nothing is left to merge or after {@link #MAX_MERGE_PASSES}.
     */
    private void hallMergeTick(LocalPlayer player) {
        if (Services.clicker().pending() > 0 || timer < 3) {
            return; // let the server echo the last pass
        }
        AbstractContainerMenu menu = player.containerMenu;
        if (!isStorageMenu(menu)) {
            fail("Container closed while merging stacks");
            return;
        }
        Inventory pinv = player.getInventory();
        int[] idx = containerSlots(menu, pinv);
        int n = idx.length;
        if (!menu.getCarried().isEmpty()) {
            // a click landed differently than planned: put the held stack down in an empty container slot
            for (int c = 0; c < n; c++) {
                if (!menu.getSlot(idx[c]).hasItem()) {
                    Services.clicker().queueClick(lease, idx[c], 0, IContainerClicker.Click.PICKUP);
                    timer = 0;
                    return;
                }
            }
            fail("Merging left an item on the cursor and the container is full");
            return;
        }
        int[] kind = new int[n];
        int[] count = new int[n];
        int[] max = new int[n];
        for (int c = 0; c < n; c++) {
            Slot slot = menu.getSlot(idx[c]);
            ItemStack st = slot.getItem();
            kind[c] = -1;
            if (st.isEmpty()) {
                continue;
            }
            count[c] = st.getCount();
            max[c] = Math.min(st.getMaxStackSize(), slot.getMaxStackSize(st));
            kind[c] = c;
            for (int j = 0; j < c; j++) {
                if (kind[j] >= 0 && ItemStack.isSameItemSameComponents(menu.getSlot(idx[j]).getItem(), st)) {
                    kind[c] = kind[j];
                    break;
                }
            }
        }
        List<InChestMerge.Merge> merges = InChestMerge.plan(kind, count, max);
        if (merges.isEmpty()) {
            hallFinish(player, hallMerged > 0 ? "merged " + hallMerged + " items" : "");
            return;
        }
        if (hallMergePasses++ >= MAX_MERGE_PASSES) {
            hallFinish(player, "partial stacks left after " + MAX_MERGE_PASSES + " merge passes");
            return;
        }
        hallMerged += InChestMerge.moved(merges);
        for (SlotTransfer.Step c : InChestMerge.clicks(merges)) {
            Services.clicker().queueClick(lease, idx[c.slot()], c.button(), c.click());
        }
        timer = 0;
    }

    /**
     * Closes the container (Storage Memory re-records it from the close event: the open came from a real USE key
     * press, so NeoForge fired RightClickBlock for it) and reports what moved.
     */
    private void hallFinish(LocalPlayer player, String note) {
        transferred = Map.copyOf(hallMoved);
        finish(player, step.label() + ": " + (transferred.isEmpty() ? "nothing moved" : transferred.toString())
                + (note.isEmpty() ? "" : " (" + note + ")"));
    }

    // ---- station steps (furnace arrays, brewing) ----------------------------------------------------------------------

    /** BREW: index of the next ingredient in {@code step.blockIds()} (0 is the start potion key). */
    private int brewIndex;
    /** Ticks the current station wait may take before it fails. */
    private int stationLimit;
    private final Map<String, Integer> stationGot = new LinkedHashMap<>();

    @Override
    public void beginStation(PlanStep s, ControlLease l) {
        start(Op.STATION, s, l);
        stationGot.clear();
        brewIndex = 1;
        if (s.kind() == StepKind.FILL_BOTTLES) {
            beginFillBottles(s);
            return;
        }
        beginTransfer(s.storagePos(), Map.of());
    }

    // ---- FILL_BOTTLES: walk to water, glass bottle in hand, smooth aim at the surface, use -------------------------

    private static final String GLASS_BOTTLE = "minecraft:glass_bottle";
    /** Water search box around the player: horizontal radius, blocks below and above. */
    private static final int WATER_RADIUS = 16;
    private static final int WATER_DOWN = 6;
    private static final int WATER_UP = 3;
    /** Uses in a row that filled nothing before the water is looked for again (a cauldron ran dry), then failure. */
    private static final int MAX_DRY_USES = 3;
    private long waterPos = Long.MIN_VALUE;
    private int waterStart;
    private int waterBefore;
    private int dryUses;
    private int waterRescans;
    /** Reused scan probe (one lambda for the action's lifetime, not per scan). */
    private final WaterFill.Probe waterProbe = this::waterKind;

    private int waterKind(int x, int y, int z) {
        Level level = Minecraft.getInstance().level;
        probe.set(x, y, z);
        BlockState st = level.getBlockState(probe);
        if (st.is(net.minecraft.world.level.block.Blocks.WATER_CAULDRON)) {
            return WaterFill.CAULDRON;
        }
        var fluid = st.getFluidState();
        return fluid.is(net.minecraft.tags.FluidTags.WATER) && fluid.isSource() ? WaterFill.SOURCE : WaterFill.NONE;
    }

    private void beginFillBottles(PlanStep s) {
        int have = inv.count(BrewingPlanner.WATER);
        waterStart = have;
        dryUses = 0;
        waterRescans = 0;
        if (WaterFill.usesNeeded(s.count(), have, Integer.MAX_VALUE) == 0) {
            transferred = Map.of(s.itemId(), have);
            result = Result.DONE;
            phase = Phase.DONE;
            op = Op.NONE;
            return;
        }
        if (inv.count(GLASS_BOTTLE) <= 0) {
            fail("No glass bottles to fill (" + have + "/" + s.count() + " water bottles carried)");
            return;
        }
        if (!scanWater()) {
            return;
        }
        driver.get().gotoCoord(waterPos, 2);
        phase = Phase.WATER_WALK;
        timer = 0;
    }

    /** One bounded scan around the player; fails the action cleanly when there is no water in range. */
    private boolean scanWater() {
        LocalPlayer p = Minecraft.getInstance().player;
        BlockPos c = p.blockPosition();
        waterPos = WaterFill.nearest(waterProbe, c.getX(), c.getY(), c.getZ(), WATER_RADIUS, WATER_DOWN, WATER_UP);
        if (waterPos == PlanStep.NO_POS) {
            fail("No water source or water cauldron within " + WATER_RADIUS + " blocks to fill bottles");
            return false;
        }
        return true;
    }

    private void waterWalk(LocalPlayer player) {
        MiningDriver.Status st = driver.get().status();
        double dx = BlockPos.getX(waterPos) + 0.5 - player.getX();
        double dy = BlockPos.getY(waterPos) + 0.9 - player.getEyeY();
        double dz = BlockPos.getZ(waterPos) + 0.5 - player.getZ();
        double reach = Math.min(4.5, player.blockInteractionRange()) - 0.3;
        if (st == MiningDriver.Status.ARRIVED || dx * dx + dy * dy + dz * dz <= reach * reach) {
            driver.get().cancel();
            if (!selectHotbarItem(player, GLASS_BOTTLE)) {
                if (timer > 20) {
                    fail("Could not move glass bottles to the hotbar");
                }
                return;
            }
            // the water surface (a source fills to ~0.89 of the block; a cauldron's water sits inside the rim)
            Services.input().setRotationTarget(lease, aimAt(player, BlockPos.getX(waterPos) + 0.5,
                    BlockPos.getY(waterPos) + 0.85, BlockPos.getZ(waterPos) + 0.5));
            phase = Phase.WATER_AIM;
            timer = 0;
        } else if (st == MiningDriver.Status.FAILED || st == MiningDriver.Status.UNAVAILABLE || timer > 600) {
            driver.get().cancel();
            fail("Could not reach water at " + PackedPos.format(waterPos));
        }
    }

    /** Glass bottle in hand (SWAP into hotbar slot 8 from the main inventory once); true when selected. */
    private boolean selectHotbarItem(LocalPlayer player, String itemId) {
        Inventory pinv = player.getInventory();
        net.minecraft.world.item.Item item = inv.itemOf(itemId);
        int main = -1;
        for (int i = 0; i < 36; i++) {
            if (pinv.getNonEquipmentItems().get(i).getItem() == item) {
                if (i < 9) {
                    Services.input().selectHotbarSlot(lease, i);
                    return true;
                }
                if (main < 0) {
                    main = i;
                }
            }
        }
        if (main >= 0 && Services.clicker().pending() == 0 && timer % 10 == 1) {
            if (!(player.containerMenu instanceof InventoryMenu)) {
                player.closeContainer();
            }
            Services.clicker().queueClick(lease, main, 8, IContainerClicker.Click.SWAP);
        }
        return false;
    }

    private void waterAim(LocalPlayer player) {
        if (!Services.input().isRotationSettled(2f) && timer < 30) {
            return;
        }
        if (!GLASS_BOTTLE.equals(inv.idOf(player.getMainHandItem().getItem()))) {
            if (inv.count(GLASS_BOTTLE) <= 0) {
                finishBottles(player, "Out of glass bottles");
            } else if (!selectHotbarItem(player, GLASS_BOTTLE) && timer > 40) {
                fail("Could not hold a glass bottle");
            }
            return;
        }
        if (!lease.isActive() || !lease.covers(ControlChannel.WORLD_INTERACT)) {
            fail("Lost world-interaction control while filling bottles");
            return;
        }
        waterBefore = inv.count(BrewingPlanner.WATER);
        Services.input().scheduleKeyTap(lease, SimKey.USE, 1, 2);
        phase = Phase.WATER_WAIT;
        timer = 0;
    }

    private void waterWait(LocalPlayer player) {
        if (timer < 6) {
            return;
        }
        int now = inv.count(BrewingPlanner.WATER);
        if (now >= step.count()) {
            finishBottles(player, "Filled " + (now - waterStart) + " water bottles");
            return;
        }
        if (inv.count(GLASS_BOTTLE) <= 0) {
            finishBottles(player, "Out of glass bottles");
            return;
        }
        if (now > waterBefore) {
            dryUses = 0;
        } else if (++dryUses >= MAX_DRY_USES) {
            // a cauldron ran dry or the aim misses: look for water once more, then give up
            dryUses = 0;
            if (waterRescans++ >= 1 || !scanWater()) {
                if (result == Result.RUNNING) {
                    fail("Water bottles did not fill at " + PackedPos.format(waterPos) + " (" + now + "/"
                            + step.count() + ")");
                }
                return;
            }
            Services.input().clearRotation(lease);
            driver.get().gotoCoord(waterPos, 2);
            phase = Phase.WATER_WALK;
            timer = 0;
            return;
        }
        phase = Phase.WATER_AIM; // rotation target is still held: use again
        timer = 0;
    }

    private void finishBottles(LocalPlayer player, String msg) {
        Services.input().clearRotation(lease);
        int got = inv.count(BrewingPlanner.WATER);
        transferred = got > 0 ? Map.of(step.itemId(), got) : Map.of();
        finish(player, msg);
    }

    private void stationWork(LocalPlayer player, AbstractContainerMenu menu) {
        Inventory pinv = player.getInventory();
        switch (step.kind()) {
            case FURNACE_LOAD -> {
                if (!(menu instanceof AbstractFurnaceMenu)) {
                    fail("Opened block is not a furnace");
                    return;
                }
                int moved = moveFromInventory(menu, pinv, step.itemId(), step.count(), FurnaceBatchPlanner.INPUT_SLOT);
                if (moved < step.count()) {
                    message = "only " + moved + "/" + step.count() + " " + step.itemId() + " carried";
                }
                if (!step.blockIds().isEmpty() && step.crafts() > 0) {
                    moveFromInventory(menu, pinv, step.blockIds().get(0), step.crafts(), FurnaceBatchPlanner.FUEL_SLOT);
                }
                if (moved == 0) {
                    fail("No " + step.itemId() + " carried to load");
                    return;
                }
                phase = Phase.WAIT_CLICKS;
                timer = 0;
            }
            case FURNACE_COLLECT -> {
                if (!(menu instanceof AbstractFurnaceMenu)) {
                    fail("Opened block is not a furnace");
                    return;
                }
                stationLimit = Math.max(200, step.crafts()) + 400;
                phase = Phase.COLLECT;
                timer = 0;
            }
            case BREW -> {
                if (!(menu instanceof BrewingStandMenu bm)) {
                    fail("Opened block is not a brewing stand");
                    return;
                }
                String startKey = step.blockIds().isEmpty() ? BrewingPlanner.WATER : step.blockIds().get(0);
                int placed = 0;
                for (int b = 0; b < BrewingPlanner.BOTTLE_SLOTS.length && placed < step.count(); b++) {
                    if (menu.getSlot(BrewingPlanner.BOTTLE_SLOTS[b]).hasItem()) {
                        continue;
                    }
                    int src = findPotion(menu, pinv, startKey, placed); // clicks are queued: skip slots already used
                    if (src < 0) {
                        break;
                    }
                    Services.clicker().queueClick(lease, src, 0, IContainerClicker.Click.PICKUP);
                    Services.clicker().queueClick(lease, BrewingPlanner.BOTTLE_SLOTS[b], 0,
                            IContainerClicker.Click.PICKUP);
                    placed++;
                }
                if (placed == 0) {
                    fail("No " + startKey + " bottles carried");
                    return;
                }
                if (bm.getFuel() <= 0 && !menu.getSlot(BrewingPlanner.FUEL_SLOT).hasItem()) {
                    moveFromInventory(menu, pinv, BrewingPlanner.BLAZE_POWDER, 1, BrewingPlanner.FUEL_SLOT);
                }
                stationLimit = 60; // first ingredient goes in once the bottle clicks are done
                phase = Phase.COLLECT;
                timer = 0;
            }
            case CRAFTER_SAVE -> {
                if (!(menu instanceof net.minecraft.world.inventory.CrafterMenu cm)) {
                    fail("Opened block is not a crafter");
                    return;
                }
                CrafterTemplate t = CrafterTemplate.capture(step.itemId(), null, crafterMask(cm),
                        crafterItems(menu));
                java.util.function.Consumer<CrafterTemplate> sink = crafterSink;
                if (sink != null) {
                    sink.accept(t);
                }
                finish(player, "Saved crafter template " + t.name());
            }
            case CRAFTER_APPLY -> {
                if (!(menu instanceof net.minecraft.world.inventory.CrafterMenu)) {
                    fail("Opened block is not a crafter");
                    return;
                }
                try {
                    crafterTarget = CrafterTemplate.decode(step.blockIds().get(0));
                } catch (RuntimeException e) {
                    fail("Bad crafter template " + step.itemId());
                    return;
                }
                crafterPasses = 0;
                phase = Phase.COLLECT; // stationTick converges the crafter on the template, one fix round per pass
                timer = 0;
            }
            default -> fail("Unsupported station step " + step.kind());
        }
    }

    /** Polled in COLLECT: furnace output collection, or the brew sequence (one ingredient per finished brew). */
    private void stationTick(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (Services.clicker().pending() > 0) {
            return;
        }
        if (step.kind() == StepKind.CRAFTER_APPLY) {
            crafterApplyTick(player, menu);
            return;
        }
        if (step.kind() == StepKind.FURNACE_COLLECT) {
            if (!(menu instanceof AbstractFurnaceMenu)) {
                fail("Furnace closed while collecting");
                return;
            }
            Slot out = menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT);
            boolean inputLeft = menu.getSlot(AbstractFurnaceMenu.INGREDIENT_SLOT).hasItem();
            if (timer % 10 == 0 && out.hasItem()) {
                stationGot.merge(inv.idOf(out.getItem().getItem()), out.getItem().getCount(), Integer::sum);
                Services.clicker().queueClick(lease, AbstractFurnaceMenu.RESULT_SLOT, 0,
                        IContainerClicker.Click.QUICK_MOVE);
                return;
            }
            int got = 0;
            for (int v : stationGot.values()) {
                got += v;
            }
            if (got >= step.count() || (!inputLeft && !out.hasItem() && timer > 10)) {
                transferred = Map.copyOf(stationGot);
                finish(player, "Collected " + transferred);
            } else if (timer > stationLimit) {
                transferred = Map.copyOf(stationGot);
                finish(player, "Collect timed out with " + got + "/" + step.count());
            }
            return;
        }
        // BREW
        if (!(menu instanceof BrewingStandMenu bm)) {
            fail("Brewing stand closed while brewing");
            return;
        }
        if (bm.getBrewingTicks() > 0 || menu.getSlot(BrewingPlanner.INGREDIENT_SLOT).hasItem()) {
            if (timer > stationLimit) {
                fail("Brew did not finish (no fuel, or the ingredient does not apply)");
            }
            return;
        }
        if (brewIndex < step.blockIds().size()) {
            if (bm.getFuel() <= 0 && moveFromInventory(menu, player.getInventory(), BrewingPlanner.BLAZE_POWDER, 1,
                    BrewingPlanner.FUEL_SLOT) == 0 && !menu.getSlot(BrewingPlanner.FUEL_SLOT).hasItem()) {
                fail("Out of blaze powder");
                return;
            }
            String ing = step.blockIds().get(brewIndex++);
            if (moveFromInventory(menu, player.getInventory(), ing, 1, BrewingPlanner.INGREDIENT_SLOT) == 0) {
                fail("Missing " + ing + " for the brew");
                return;
            }
            stationLimit = timer + BrewingPlanner.BREW_TICKS + 200;
            return;
        }
        // all brews done: take the bottles
        int bottles = 0;
        for (int b : BrewingPlanner.BOTTLE_SLOTS) {
            Slot s = menu.getSlot(b);
            if (s.hasItem()) {
                String id = inv.idOf(s.getItem().getItem());
                String key = MinecraftInventoryView.potionKey(s.getItem(), id);
                if (step.itemId().equals(key)) {
                    bottles++;
                }
                Services.clicker().queueClick(lease, b, 0, IContainerClicker.Click.QUICK_MOVE);
            }
        }
        transferred = bottles > 0 ? Map.of(step.itemId(), bottles) : Map.of();
        phase = Phase.WAIT_CLICKS;
        timer = 0;
    }

    // ---- crafter templates ------------------------------------------------------------------------------------------

    private volatile java.util.function.Consumer<CrafterTemplate> crafterSink;
    private CrafterTemplate crafterTarget;
    private int crafterPasses;
    /** Fix rounds before giving up (clear, then toggle + seed, then a check; one spare). */
    private static final int MAX_CRAFTER_PASSES = 4;

    /** Where CRAFTER_SAVE puts captured templates (StorageMemory, persisted with the storage JSON). */
    public void setCrafterTemplateSink(java.util.function.Consumer<CrafterTemplate> sink) {
        this.crafterSink = sink;
    }

    private static int crafterMask(net.minecraft.world.inventory.CrafterMenu cm) {
        int mask = 0;
        for (int i = 0; i < CrafterTemplate.SLOTS; i++) {
            if (cm.isSlotDisabled(i)) {
                mask |= 1 << i;
            }
        }
        return mask;
    }

    private String[] crafterItems(AbstractContainerMenu menu) {
        String[] items = new String[CrafterTemplate.SLOTS];
        for (int i = 0; i < CrafterTemplate.SLOTS; i++) {
            ItemStack s = menu.getSlot(i).getItem();
            items[i] = s.isEmpty() ? null : inv.idOf(s.getItem());
        }
        return items;
    }

    /**
     * One fix round once the previous round's clicks are done: diff the open crafter with the template, then take
     * wrong items out (QUICK_MOVE) or, when nothing is left to clear, toggle slot locks and seed items. Vanilla only
     * toggles empty slots, hence clears go first in their own round. Done when the diff is empty.
     */
    private void crafterApplyTick(LocalPlayer player, AbstractContainerMenu menu) {
        if (!(menu instanceof net.minecraft.world.inventory.CrafterMenu cm)) {
            fail("Crafter closed while applying the template");
            return;
        }
        if (timer < 3) {
            return; // let the server echo the last round's slot changes
        }
        List<CrafterTemplate.Fix> fixes = crafterTarget.diff(crafterMask(cm), crafterItems(menu));
        if (fixes.isEmpty()) {
            finish(player, "Crafter set to template " + crafterTarget.name());
            return;
        }
        if (crafterPasses++ >= MAX_CRAFTER_PASSES) {
            fail("Crafter still differs from " + crafterTarget.name() + " (" + fixes.size() + " fixes left)");
            return;
        }
        boolean cleared = false;
        for (CrafterTemplate.Fix f : fixes) {
            if (f.kind() == CrafterTemplate.FixKind.CLEAR) {
                Services.clicker().queueClick(lease, f.slot(), 0, IContainerClicker.Click.QUICK_MOVE);
                cleared = true;
            }
        }
        if (!cleared) {
            Inventory pinv = player.getInventory();
            for (CrafterTemplate.Fix f : fixes) {
                if (f.kind() == CrafterTemplate.FixKind.TOGGLE
                        && !Services.clicker().toggleSlotState(lease, f.slot(), f.enable())) {
                    fail("Could not toggle crafter slot " + f.slot());
                    return;
                }
                if (f.kind() == CrafterTemplate.FixKind.SEED && moveFromInventory(menu, pinv, f.itemId(), 1,
                        f.slot()) == 0) {
                    fail("No " + f.itemId() + " carried to seed crafter slot " + f.slot());
                    return;
                }
            }
        }
        timer = 0;
    }

    /** Queues exact-count moves of {@code itemId} from the player's slots into {@code dst}; returns the count. */
    private int moveFromInventory(AbstractContainerMenu menu, Inventory pinv, String itemId, int count, int dst) {
        int left = count;
        for (Slot s : menu.slots) {
            if (left <= 0) {
                break;
            }
            if (s.container != pinv || !s.hasItem() || !itemId.equals(inv.idOf(s.getItem().getItem()))) {
                continue;
            }
            int n = Math.min(left, s.getItem().getCount());
            for (SlotTransfer.Step c : SlotTransfer.exact(s.index, s.getItem().getCount(), dst, n)) {
                Services.clicker().queueClick(lease, c.slot(), c.button(), c.click());
            }
            left -= n;
        }
        return count - left;
    }

    /** Player slot holding a bottle of the potion key, skipping the first {@code skip} matches; -1 when none. */
    private int findPotion(AbstractContainerMenu menu, Inventory pinv, String key, int skip) {
        int seen = 0;
        for (Slot s : menu.slots) {
            if (s.container != pinv || !s.hasItem()) {
                continue;
            }
            String id = inv.idOf(s.getItem().getItem());
            if (key.equals(MinecraftInventoryView.potionKey(s.getItem(), id)) && seen++ >= skip) {
                return s.index;
            }
        }
        return -1;
    }

    /**
     * One hand-placement round: snapshots the open crafting grid, inventory and cursor, lets
     * {@link ManualCraftPlanner} plan the clicks and queues them through the clicker under the action's lease.
     */
    private void placeByHand(LocalPlayer player, AbstractContainerMenu menu) {
        int gridSize = gridSizeOf(menu);
        String[] grid = gridIds(menu, gridSize);
        if (grid == null) {
            fail("No crafting grid open for " + step.itemId());
            return;
        }
        Inventory pinv = player.getInventory();
        int n = 0;
        for (Slot s : menu.slots) {
            if (s.container == pinv && s.getContainerSlot() < Inventory.INVENTORY_SIZE) {
                n++;
            }
        }
        int[] slots = new int[n];
        String[] items = new String[n];
        int[] counts = new int[n];
        int k = 0;
        for (Slot s : menu.slots) {
            if (s.container == pinv && s.getContainerSlot() < Inventory.INVENTORY_SIZE) {
                ItemStack st = s.getItem();
                slots[k] = s.index;
                items[k] = st.isEmpty() ? null : inv.idOf(st.getItem());
                counts[k] = st.getCount();
                k++;
            }
        }
        ItemStack carried = menu.getCarried();
        ManualCraftPlanner.Plan plan = ManualCraftPlanner.plan(manualShape, new ManualCraftPlanner.Menu(gridSize,
                CRAFT_GRID_FIRST_SLOT, grid, slots, items, counts,
                carried.isEmpty() ? null : inv.idOf(carried.getItem()), carried.getCount()));
        switch (plan.status()) {
            case PLACE -> {
                if (queueAll(plan.clicks())) {
                    manual = true;
                    placedAt = 0; // checked once the clicks are done (afterClicks restarts the timer)
                    phase = Phase.WAIT_CLICKS;
                    timer = 0;
                }
            }
            case CLEAR_GRID -> {
                if (++gridClears > MAX_GRID_CLEARS) {
                    fail("Could not empty the crafting grid (inventory full?)");
                } else if (queueAll(plan.clicks())) {
                    phase = Phase.WAIT_CLICKS; // back to WORK with placedAt < 0: plan again on the emptied grid
                    timer = 0;
                }
            }
            case CURSOR_STUCK -> fail("Inventory full: nowhere to put the held " + plan.detail());
            case MISSING -> fail("Missing ingredients for " + step.itemId() + ": " + plan.detail());
            case TOO_BIG -> fail("Recipe " + recipe.id() + " does not fit the open crafting grid");
            case EMPTY_RECIPE -> fail("Recipe " + recipe.id() + " has no ingredients to place");
        }
    }

    /** Queues every click or none: on a full queue the action's clicks are dropped and the action fails. */
    private boolean queueAll(List<ManualCraftPlanner.Click> clicks) {
        IContainerClicker clicker = Services.clicker();
        for (ManualCraftPlanner.Click c : clicks) {
            if (!clicker.queueClick(lease, c.slot(), c.button(), c.type())) {
                clicker.cancel(lease);
                fail("Could not queue crafting clicks");
                return false;
            }
        }
        return true;
    }

    /** Menu slot of the crafting grid's top-left cell in both the inventory and the crafting-table menus. */
    private static final int CRAFT_GRID_FIRST_SLOT = 1;

    private static int gridSizeOf(AbstractContainerMenu menu) {
        return menu instanceof CraftingMenu ? 3 : menu instanceof InventoryMenu ? 2 : 0;
    }

    /** Item id per crafting-grid cell (row-major, null when empty), or null when the menu has no crafting grid. */
    private String[] gridIds(AbstractContainerMenu menu, int gridSize) {
        if (gridSize == 0) {
            return null;
        }
        String[] grid = new String[gridSize * gridSize];
        for (int c = 0; c < grid.length; c++) {
            ItemStack st = menu.getSlot(CRAFT_GRID_FIRST_SLOT + c).getItem();
            grid[c] = st.isEmpty() ? null : inv.idOf(st.getItem());
        }
        return grid;
    }

    /** Shaped/shapeless crafting recipe as ids for {@link ManualCraftPlanner}; null for any other recipe. */
    private ManualCraftPlanner.Shape shapeOf(McRecipeCatalog.Entry e) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return null;
        }
        ContextMap ctx = SlotDisplayContext.fromLevel(mc.level);
        if (e.display() instanceof ShapedCraftingRecipeDisplay sr) {
            return ManualCraftPlanner.Shape.shaped(sr.width(), sr.height(), idsOf(sr.ingredients(), ctx));
        }
        if (e.display() instanceof ShapelessCraftingRecipeDisplay sl) {
            return ManualCraftPlanner.Shape.shapeless(idsOf(sl.ingredients(), ctx));
        }
        return null;
    }

    private List<Set<String>> idsOf(List<SlotDisplay> ingredients, ContextMap ctx) {
        List<Set<String>> out = new ArrayList<>(ingredients.size());
        for (SlotDisplay ing : ingredients) {
            if (ing instanceof SlotDisplay.Empty) {
                out.add(Set.of());
                continue;
            }
            Set<String> ids = new HashSet<>();
            for (ItemStack st : ing.resolveForStacks(ctx)) {
                if (!st.isEmpty()) {
                    ids.add(inv.idOf(st.getItem()));
                }
            }
            out.add(Set.copyOf(ids));
        }
        return out;
    }

    private void loadFurnace(LocalPlayer player, AbstractContainerMenu menu) {
        Inventory pinv = player.getInventory();
        McRecipeCatalog.Entry ing = recipe;
        net.minecraft.client.multiplayer.ClientLevel level = Minecraft.getInstance().level;
        int needInput = craftsLeft;
        double needFuel = craftsLeft;
        Slot fuelSlot = menu.getSlot(AbstractFurnaceMenu.FUEL_SLOT);
        if (fuelSlot.hasItem()) {
            needFuel -= fuels.smeltsPer(inv.idOf(fuelSlot.getItem().getItem())) * fuelSlot.getItem().getCount();
        }
        for (Slot s : menu.slots) {
            if (s.container != pinv || !s.hasItem()) {
                continue;
            }
            ItemStack st = s.getItem();
            if (needInput > 0 && ing.acceptsInput(st, level)) {
                Services.clicker().queueClick(lease, s.index, 0, IContainerClicker.Click.QUICK_MOVE);
                needInput -= st.getCount();
                continue;
            }
            double per = fuels.smeltsPer(inv.idOf(st.getItem()));
            if (needFuel > 0 && per > 0 && !ing.acceptsInput(st, level)) {
                Services.clicker().queueClick(lease, s.index, 0, IContainerClicker.Click.QUICK_MOVE);
                needFuel -= per * st.getCount();
            }
        }
        if (needInput > 0) {
            message = "Not enough input items for " + step.itemId();
        }
    }

    private void afterClicks(LocalPlayer player) {
        switch (op) {
            case CRAFT -> {
                phase = Phase.WORK;
                timer = 0;
            }
            case SMELT -> {
                phase = Phase.COLLECT;
                timer = 0;
            }
            case FETCH, DEPOSIT -> {
                Map<String, Integer> moved = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : before.entrySet()) {
                    int delta = inv.count(e.getKey()) - e.getValue();
                    int m = op == Op.FETCH ? delta : -delta;
                    if (m > 0) {
                        moved.put(e.getKey(), m);
                    }
                }
                transferred = Map.copyOf(moved);
                finish(player, (op == Op.FETCH ? "Fetched " : "Deposited ") + transferred);
            }
            case SHULKER -> {
                int got = inv.count(restockItem) - before.getOrDefault(restockItem, 0);
                transferred = got > 0 ? Map.of(restockItem, got) : Map.of();
                if (!(player.containerMenu instanceof InventoryMenu)) {
                    player.closeContainer();
                }
                selectBreakTool(player);
                Services.input().setRotationTarget(lease, aimAt(player, BlockPos.getX(stationPos) + 0.5,
                        BlockPos.getY(stationPos) + 0.5, BlockPos.getZ(stationPos) + 0.5));
                phase = Phase.BREAK;
                timer = 0;
            }
            case ENDER -> {
                Map<String, Integer> moved = new LinkedHashMap<>();
                for (Map.Entry<String, Integer> e : before.entrySet()) {
                    int m = inv.count(e.getKey()) - e.getValue();
                    if (m > 0) {
                        moved.put(e.getKey(), m);
                    }
                }
                transferred = Map.copyOf(moved);
                if (!(player.containerMenu instanceof InventoryMenu)) {
                    player.closeContainer();
                }
                int silk = inv.silkTouchPickaxeSlot();
                if (silk < 0 || silk >= 9) {
                    fail("Silk Touch pickaxe not on the hotbar: ender chest left in place at "
                            + PackedPos.format(stationPos) + " (items taken: " + transferred + ")");
                    return;
                }
                Services.input().selectHotbarSlot(lease, silk);
                Services.input().setRotationTarget(lease, aimAt(player, BlockPos.getX(stationPos) + 0.5,
                        BlockPos.getY(stationPos) + 0.5, BlockPos.getZ(stationPos) + 0.5));
                phase = Phase.BREAK;
                timer = 0;
            }
            case HALL -> hallAfterClicks(player);
            default -> finish(player, "");
        }
    }

    /** A pickaxe on the hotbar breaks a shulker box fastest; the bare hand works too (it always drops). */
    private void selectBreakTool(LocalPlayer player) {
        Inventory pinv = player.getInventory();
        for (int i = 0; i < 9; i++) {
            ItemStack s = pinv.getNonEquipmentItems().get(i);
            if (!s.isEmpty() && inv.idOf(s.getItem()).endsWith("_pickaxe")) {
                Services.input().selectHotbarSlot(lease, i);
                return;
            }
        }
    }

    /** Holds attack on the placed box until it is gone, then walks onto its spot to pick the dropped box up. */
    private void breakBox(Minecraft mc, LocalPlayer player) {
        IInputSmoother input = Services.input();
        if (!station.equals(blockAt(mc.level, stationPos))) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            input.clearRotation(lease);
            driver.get().gotoCoord(stationPos, 0);
            phase = Phase.PICKUP;
            timer = 0;
            return;
        }
        if (timer > 200) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            input.clearRotation(lease);
            fail("Could not break the " + station + " at " + BlockPos.getX(stationPos) + " "
                    + BlockPos.getY(stationPos) + " " + BlockPos.getZ(stationPos) + " (left in place; items taken: "
                    + transferred + ")");
            return;
        }
        if (!input.isRotationSettled(2f) && timer < 30) {
            return;
        }
        boolean onIt = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().asLong() == stationPos;
        if (op == Op.ENDER && !inv.isSilkTouchPickaxe(player.getMainHandItem())) {
            onIt = false; // never mine an ender chest without Silk Touch (it would drop obsidian)
            if (timer > 40) {
                input.setKeyHeld(lease, SimKey.ATTACK, false);
                input.clearRotation(lease);
                fail("Silk Touch pickaxe not in hand: ender chest left in place at " + PackedPos.format(stationPos)
                        + " (items taken: " + transferred + ")");
                return;
            }
        }
        input.setKeyHeld(lease, SimKey.ATTACK, onIt);
    }

    private void pickUpBox(LocalPlayer player) {
        if (inv.count(station) >= boxBefore) {
            driver.get().cancel();
            finish(player, (op == Op.ENDER ? "Fetched " : "Unpacked ") + transferred + " from " + station);
            return;
        }
        MiningDriver.Status st = driver.get().status();
        if (timer > 160 || st == MiningDriver.Status.FAILED || st == MiningDriver.Status.UNAVAILABLE) {
            driver.get().cancel();
            fail("Shulker box dropped but was not picked up (items taken: " + transferred + ")");
        }
    }

    private void collectSmelted(LocalPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (!(menu instanceof AbstractFurnaceMenu)) {
            fail("Furnace closed while smelting");
            return;
        }
        if (inv.count(step.itemId()) - startCount >= step.count()) {
            finish(player, "Smelted " + step.itemId());
            return;
        }
        if (timer % 20 == 0 && menu.getSlot(AbstractFurnaceMenu.RESULT_SLOT).hasItem()) {
            Services.clicker().queueClick(lease, AbstractFurnaceMenu.RESULT_SLOT, 0, IContainerClicker.Click.QUICK_MOVE);
        }
        int limit = craftsLeft * 200 * 3 / 2 + 200;
        if (timer > limit) {
            fail("Smelting timed out" + (message.isEmpty() ? "" : " (" + message + ")"));
        }
    }

    private void finish(LocalPlayer player, String msg) {
        if (!(player.containerMenu instanceof InventoryMenu)) {
            player.closeContainer();
        }
        message = msg;
        result = Result.DONE;
        phase = Phase.DONE;
        op = Op.NONE;
    }

    private void fail(String why) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !(mc.player.containerMenu instanceof InventoryMenu) && op != Op.NONE) {
            mc.player.closeContainer();
        }
        message = why;
        result = Result.FAILED;
        phase = Phase.DONE;
        op = Op.NONE;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public Map<String, Integer> transferred() {
        return transferred;
    }

    @Override
    public void abort() {
        if (op != Op.NONE) {
            if (phase == Phase.BREAK && lease != null) {
                Services.input().setKeyHeld(lease, SimKey.ATTACK, false);
            }
            if ((phase == Phase.WATER_WALK || phase == Phase.WATER_AIM || phase == Phase.WATER_WAIT)
                    && lease != null) {
                Services.input().clearRotation(lease);
                driver.get().cancel();
            }
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && !(mc.player.containerMenu instanceof InventoryMenu)) {
                mc.player.closeContainer();
            }
            if (lease != null) {
                Services.clicker().cancel(lease);
            }
        }
        op = Op.NONE;
        phase = Phase.DONE;
        if (result == Result.RUNNING) {
            result = Result.FAILED;
            message = "Aborted";
        }
    }

    // ---- world queries ----------------------------------------------------------------------------------------------

    @Override
    public String blockAtIfLoaded(String dimension, long packedPos) {
        Level level = Minecraft.getInstance().level;
        if (level == null || !dimension.equals(dimension())) {
            return null;
        }
        probe.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));
        if (!level.isLoaded(probe)) {
            return null;
        }
        return blockId(level.getBlockState(probe).getBlock());
    }

    private String blockAt(Level level, long packedPos) {
        probe.set(BlockPos.getX(packedPos), BlockPos.getY(packedPos), BlockPos.getZ(packedPos));
        return blockId(level.getBlockState(probe).getBlock());
    }

    /** Blocks around a position searched for a fuse (creeper blast reach plus a margin) and for lava. */
    private static final int FUSE_WATCH = 8;
    private static final int LAVA_WATCH = 2;
    private static final EntityTypeTest<Entity, Entity> ANY_ENTITY = EntityTypeTest.forClass(Entity.class);

    private boolean fuseSeen;
    /** Flags a fuse and rejects every entity, so the sink list below stays empty. Created once. */
    private final Predicate<Entity> fuseProbe = e -> {
        if (e instanceof PrimedTnt || (e instanceof Creeper c && (c.getSwellDir() > 0 || c.isIgnited()))) {
            this.fuseSeen = true;
        }
        return false;
    };
    private final ArrayList<Entity> fuseSink = new ArrayList<>(0);
    /** Search box of the last fuse query; rebuilt only when the position changes (once per held resume). */
    private long fuseBoxPos = Long.MIN_VALUE;
    private AABB fuseBox;

    @Override
    public int hazardsNear(long packedPos, int kinds) {
        Level level = Minecraft.getInstance().level;
        if (level == null || kinds == 0) {
            return 0;
        }
        int x = BlockPos.getX(packedPos), y = BlockPos.getY(packedPos), z = BlockPos.getZ(packedPos);
        probe.set(x, y, z);
        if (!level.isLoaded(probe)) {
            return 0;
        }
        int found = 0;
        if ((kinds & ResumeHoldGate.EXPLOSION) != 0) {
            if (fuseBox == null || fuseBoxPos != packedPos) {
                fuseBoxPos = packedPos;
                fuseBox = new AABB(x - FUSE_WATCH, y - FUSE_WATCH, z - FUSE_WATCH,
                        x + 1 + FUSE_WATCH, y + 1 + FUSE_WATCH, z + 1 + FUSE_WATCH);
            }
            fuseSeen = false;
            level.getEntities(ANY_ENTITY, fuseBox, fuseProbe, fuseSink);
            if (fuseSeen) {
                found |= ResumeHoldGate.EXPLOSION;
            }
        }
        if ((kinds & ResumeHoldGate.LAVA) != 0 && lavaWithin(level, x, y, z)) {
            found |= ResumeHoldGate.LAVA;
        }
        return found;
    }

    private boolean lavaWithin(Level level, int x, int y, int z) {
        for (int dy = -LAVA_WATCH; dy <= LAVA_WATCH; dy++) {
            for (int dx = -LAVA_WATCH; dx <= LAVA_WATCH; dx++) {
                for (int dz = -LAVA_WATCH; dz <= LAVA_WATCH; dz++) {
                    probe.set(x + dx, y + dy, z + dz);
                    if (level.getFluidState(probe).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    @Override
    public long playerPos() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? 0L : p.blockPosition().asLong();
    }

    @Override
    public String dimension() {
        Level level = Minecraft.getInstance().level;
        if (level != null && level.dimension() != dimKey) {
            dimKey = level.dimension();
            dimId = dimKey.identifier().toString();
        }
        return dimId;
    }

    private final ToolDurabilityGuard toolGuard = new ToolDurabilityGuard();
    private ControlLease swapLease;

    @Override
    public ToolDurabilityGuard.Decision guardTool(int minRemaining, boolean protectEnchanted, int requiredTier) {
        if (swapLease != null) {
            if (swapLease.isActive() && Services.clicker().pending() > 0) {
                return ToolDurabilityGuard.Decision.OK; // the previous swap is still being clicked
            }
            swapLease.close();
            swapLease = null;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return ToolDurabilityGuard.Decision.OK;
        }
        inv.fillToolGuard(toolGuard);
        ToolDurabilityGuard.Decision d = toolGuard.decide(minRemaining, protectEnchanted, requiredTier);
        if (d != ToolDurabilityGuard.Decision.SWAP) {
            return d;
        }
        ControlLease l = Services.arbiter().request(new com.utilitysuite.core.api.LeaseRequest("goal-tool-guard",
                com.utilitysuite.core.api.ControlPriority.GOAL, java.util.EnumSet.of(ControlChannel.HOTBAR), 40, null))
                .orElse(null);
        if (l == null) {
            return ToolDurabilityGuard.Decision.OK; // retried on the next check
        }
        swapLease = l;
        int slot = toolGuard.swapSlot;
        if (slot < 9) {
            Services.input().selectHotbarSlot(l, slot);
        } else {
            if (!(player.containerMenu instanceof InventoryMenu)) {
                player.closeContainer();
            }
            Services.clicker().queueClick(l, slot, player.getInventory().getSelectedSlot(), IContainerClicker.Click.SWAP);
        }
        return d;
    }

    @Override
    public boolean heldToolHelpsWith(java.util.List<String> blockIds) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || blockIds.isEmpty()) {
            return true; // unknown targets: keep guarding
        }
        net.minecraft.world.item.ItemStack held = player.getMainHandItem();
        for (int i = 0; i < blockIds.size(); i++) {
            net.minecraft.resources.Identifier id = net.minecraft.resources.Identifier.tryParse(blockIds.get(i));
            if (id == null) {
                continue;
            }
            net.minecraft.world.level.block.Block b = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getValue(id);
            if (held.getDestroySpeed(b.defaultBlockState()) > 1.0f) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int fortuneLevel() {
        return inv.fortuneOfBestPickaxe();
    }

    @Override
    public Set<String> nearbyStations() {
        Minecraft mc = Minecraft.getInstance();
        Set<String> out = new HashSet<>();
        for (String s : new String[]{Items.CRAFTING_TABLE, Items.FURNACE, Items.BLAST_FURNACE, Items.SMOKER}) {
            if (findBlockNear(mc, s, 4) != Long.MIN_VALUE) {
                out.add(s);
            }
        }
        return out;
    }

    /** Nearest block of the id within {@code r} blocks of the player (one-off scan, at most (2r+1)^3 lookups). */
    private long findBlockNear(Minecraft mc, String id, int r) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) {
            return Long.MIN_VALUE;
        }
        BlockPos c = p.blockPosition();
        long best = Long.MIN_VALUE;
        int bestD = Integer.MAX_VALUE;
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -2; dy <= 3; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    probe.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (id.equals(blockId(level.getBlockState(probe).getBlock()))) {
                        int d = dx * dx + dy * dy + dz * dz;
                        if (d < bestD) {
                            bestD = d;
                            best = probe.asLong();
                        }
                    }
                }
            }
        }
        return best;
    }

    /** An air block next to the player (not its own column) with a solid block below. */
    /** Y offsets tried for a station spot, nearest first (inside a tree canopy foot level is often all leaves). */
    private static final int[] PLACE_DY = {0, -1, 1, -2, 2};

    /**
     * Nearest standable ground column (ignoring leaves) within 6 blocks: air for feet and head on a sturdy block.
     * Not per tick: only when no station spot was found.
     */
    private long findOpenGround(Minecraft mc, int minRing, int maxRing) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        BlockPos c = p.blockPosition();
        for (int ring = minRing; ring <= maxRing; ring++) {
            for (int dx = -ring; dx <= ring; dx++) {
                for (int dz = -ring; dz <= ring; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    int x = c.getX() + dx;
                    int z = c.getZ() + dz;
                    int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                    // one reused position: each state is judged at its own block before the position moves on
                    probe.set(x, y, z);
                    BlockState feet = level.getBlockState(probe);
                    if (!feet.getCollisionShape(level, probe).isEmpty() || !feet.getFluidState().isEmpty()) {
                        continue;
                    }
                    probe.set(x, y + 1, z);
                    if (!level.getBlockState(probe).getCollisionShape(level, probe).isEmpty()) {
                        continue;
                    }
                    probe.set(x, y - 1, z);
                    if (level.getBlockState(probe).isFaceSturdy(level, probe, Direction.UP)) {
                        return BlockPos.asLong(x, y, z);
                    }
                }
            }
        }
        return Long.MIN_VALUE;
    }

    private long findPlacementSpot(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        BlockPos c = p.blockPosition();
        // Uneven ground (tree roots, slopes, a Baritone pit) often has no free spot at foot level, so also try one
        // or two lower and higher; nearer rings and foot level first, and only spots whose support is within reach.
        double ex = p.getX();
        double ey = p.getEyeY();
        double ez = p.getZ();
        double reach = Math.min(4.5, p.blockInteractionRange()) - 0.3;
        for (int ring = 1; ring <= 4; ring++) {
            for (int dyi = 0; dyi < PLACE_DY.length; dyi++) {
                int y = c.getY() + PLACE_DY[dyi];
                for (int dx = -ring; dx <= ring; dx++) {
                    for (int dz = -ring; dz <= ring; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        probe.set(c.getX() + dx, y, c.getZ() + dz);
                        BlockState at = level.getBlockState(probe);
                        if (!at.canBeReplaced() || !at.getFluidState().isEmpty()) {
                            continue;
                        }
                        double sx = c.getX() + dx + 0.5 - ex;
                        double sy = y - ey;
                        double sz = c.getZ() + dz + 0.5 - ez;
                        if (sx * sx + sy * sy + sz * sz > reach * reach) {
                            continue;
                        }
                        long spot = probe.asLong();
                        int sx0 = c.getX() + dx;
                        int sz0 = c.getZ() + dz;
                        if (p.getBoundingBox().intersects(sx0, y, sz0, sx0 + 1, y + 1, sz0 + 1)) {
                            continue; // the block would go into the player
                        }
                        if (op == Op.SHULKER || op == Op.ENDER) {
                            // a box on the floor opens upward: the block above must be free, and not fluid
                            BlockState above = level.getBlockState(probe.set(sx0, y + 1, sz0));
                            probe.set(sx0, y, sz0);
                            if (!above.isAir() || !above.getFluidState().isEmpty()) {
                                continue;
                            }
                        }
                        if (chooseSupport(level, p, sx0, y, sz0, reach)
                                && ((op != Op.SHULKER && op != Op.ENDER) || supportFace == Direction.UP)) {
                            return spot;
                        }
                    }
                }
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * A soft block hemming the player in (leaves, or a replaceable plant with a shape) next to its feet or head, in
     * reach with a visible face: breaking it makes room for a station. Sets {@link #clearPos} and the aim point.
     */
    private boolean findSoftObstruction(Minecraft mc) {
        LocalPlayer p = mc.player;
        Level level = mc.level;
        BlockPos c = p.blockPosition();
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
        for (int dy = 0; dy <= 1; dy++) {
            for (Direction d : Direction.Plane.HORIZONTAL) {
                supportProbe.set(c.getX() + d.getStepX(), c.getY() + dy, c.getZ() + d.getStepZ());
                BlockState st = level.getBlockState(supportProbe);
                if (!st.is(net.minecraft.tags.BlockTags.LEAVES)) {
                    continue;
                }
                Direction face = d.getOpposite();
                double ax = supportProbe.getX() + 0.5 + face.getStepX() * 0.499;
                double ay = supportProbe.getY() + 0.5;
                double az = supportProbe.getZ() + 0.5 + face.getStepZ() * 0.499;
                BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
                        new net.minecraft.world.phys.Vec3(ax, ay, az),
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, p));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(supportProbe)) {
                    clearPos = supportProbe.asLong();
                    aimX = ax;
                    aimY = ay;
                    aimZ = az;
                    Services.input().setRotationTarget(lease, aimAt(p, ax, ay, az));
                    return true;
                }
            }
        }
        return false;
    }

    /** Breaks {@link #clearPos} by holding attack on it, then looks for a station spot again. */
    private void clearObstruction(Minecraft mc) {
        IInputSmoother input = Services.input();
        BlockPos.MutableBlockPos at = supportProbe.set(clearPos);
        boolean gone = mc.level.getBlockState(at).canBeReplaced() || mc.level.getBlockState(at).isAir();
        if (gone || timer > 80) {
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            input.clearRotation(lease);
            if (!gone) {
                obstructionClears = MAX_OBSTRUCTION_CLEARS; // could not break it: walk instead
            }
            phase = Phase.SELECT_ITEM;
            timer = 0;
            return;
        }
        if (!input.isRotationSettled(2f) && timer < 30) {
            return;
        }
        boolean onIt = mc.hitResult instanceof BlockHitResult bh && bh.getType() == HitResult.Type.BLOCK
                && bh.getBlockPos().asLong() == clearPos;
        input.setKeyHeld(lease, SimKey.ATTACK, onIt);
    }

    /** Support directions from the spot, floor first (a table on the ground looks normal), then sides, then above. */
    private static final Direction[] SUPPORT_ORDER = {Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.WEST,
            Direction.EAST, Direction.UP};
    private final BlockPos.MutableBlockPos supportProbe = new BlockPos.MutableBlockPos();

    /**
     * Picks a block next to the spot to click, like a player would: any block with an outline (stone, dirt, a log's
     * side, leaves in a canopy) except ones that open a screen or toggle on right-click. The face toward the spot must
     * be in reach and visible (line of sight respected: never through a wall). Sets {@link #supportPos},
     * {@link #supportFace} and the aim point. Not per tick: only while choosing a station spot.
     */
    private boolean chooseSupport(Level level, LocalPlayer p, int x, int y, int z, double reach) {
        net.minecraft.world.phys.Vec3 eye = p.getEyePosition();
        for (Direction d : SUPPORT_ORDER) {
            supportProbe.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
            BlockState st = level.getBlockState(supportProbe);
            if (st.canBeReplaced() || st.getShape(level, supportProbe).isEmpty() || opensOrToggles(level, st)) {
                continue;
            }
            Direction face = d.getOpposite();
            double ax = supportProbe.getX() + 0.5 + face.getStepX() * 0.499;
            double ay = supportProbe.getY() + 0.5 + face.getStepY() * 0.499;
            double az = supportProbe.getZ() + 0.5 + face.getStepZ() * 0.499;
            double rx = ax - eye.x;
            double ry = ay - eye.y;
            double rz = az - eye.z;
            if (rx * rx + ry * ry + rz * rz > reach * reach) {
                continue;
            }
            // the eye must be on the outside of that face, and the ray must land on it
            if ((eye.x - ax) * face.getStepX() + (eye.y - ay) * face.getStepY() + (eye.z - az) * face.getStepZ() <= 0) {
                continue;
            }
            BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye,
                    new net.minecraft.world.phys.Vec3(ax, ay, az), net.minecraft.world.level.ClipContext.Block.OUTLINE,
                    net.minecraft.world.level.ClipContext.Fluid.NONE, p));
            if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(supportProbe)
                    && hit.getDirection() == face) {
                supportPos = supportProbe.asLong();
                supportFace = face;
                aimX = ax;
                aimY = ay;
                aimZ = az;
                return true;
            }
        }
        return false;
    }

    /** Right-clicking these opens a screen or toggles them instead of placing against them. */
    private static boolean opensOrToggles(Level level, BlockState st) {
        return st.hasBlockEntity() || st.getMenuProvider(level, BlockPos.ZERO) != null
                || st.is(net.minecraft.tags.BlockTags.DOORS) || st.is(net.minecraft.tags.BlockTags.TRAPDOORS)
                || st.is(net.minecraft.tags.BlockTags.FENCE_GATES) || st.is(net.minecraft.tags.BlockTags.BUTTONS)
                || st.is(net.minecraft.tags.BlockTags.BEDS) || st.is(net.minecraft.world.level.block.Blocks.LEVER)
                || st.is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE);
    }

    private static RotationTarget aimAt(LocalPlayer p, double x, double y, double z) {
        var eye = p.getEyePosition();
        double dx = x - eye.x;
        double dy = y - eye.y;
        double dz = z - eye.z;
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        return RotationTarget.visible(yaw, pitch);
    }

    private String blockId(Block b) {
        String id = blockIds.get(b);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(b).toString();
            blockIds.put(b, id);
        }
        return id;
    }

    /** 26.3: recipes are server-side; the catalogue reads the integrated server, synced recipes or the recipe book. */
    private static Optional<McRecipeCatalog.Entry> lookupRecipe(String id) {
        return Optional.ofNullable(McRecipeCatalog.byId(Minecraft.getInstance(), id));
    }
}
