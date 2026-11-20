package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.jobs.planner.FuelTable;
import com.utilitysuite.jobs.planner.Items;
import com.utilitysuite.jobs.planner.PlanStep;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * In-game {@link WorldActions}: opens stations and containers (pathing next to them through the driver, then a real
 * right-click via the input smoother), places a crafting table or furnace from the inventory when none is nearby, and
 * moves items with {@link IContainerClicker} QUICK_MOVE clicks under the executor's lease.
 * <p>
 * Recipe placement uses the vanilla recipe-book request through {@code IContainerClicker.placeRecipe} (needs an
 * active lease with HOTBAR); the crafted result is then taken with
 * a clicker QUICK_MOVE. The server only places recipes the player has unlocked in the recipe book.
 */
public final class MinecraftWorldActions implements WorldActions {

    private enum Op { NONE, CRAFT, SMELT, FETCH, DEPOSIT }

    private enum Phase { FIND, SELECT_ITEM, AIM_PLACE, WAIT_PLACED, OPEN, WAIT_OPEN, WORK, WAIT_CLICKS, COLLECT, DONE }

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
    /** Ticks to wait for a recipe unlock packet before giving up on a locked recipe. */
    private static final int RECIPE_UNLOCK_WAIT = 20;
    private PlanStep step;
    private RecipeHolder<?> recipe;
    private String station;
    private long stationPos = Long.MIN_VALUE;
    private long supportPos;
    private int craftsLeft;
    private int startCount;
    private Map<String, Integer> wanted = Map.of();
    private final Map<String, Integer> before = new LinkedHashMap<>();
    private Map<String, Integer> transferred = Map.of();

    public MinecraftWorldActions(Supplier<MiningDriver> driver, MinecraftInventoryView inv) {
        this.driver = driver;
        this.inv = inv;
    }

    // ---- begin ------------------------------------------------------------------------------------------------------

    @Override
    public void beginCraft(PlanStep s, ControlLease l) {
        start(Op.CRAFT, s, l);
        Optional<RecipeHolder<?>> r = lookupRecipe(s.recipeId());
        if (r.isEmpty()) {
            fail("Unknown recipe " + s.recipeId());
            return;
        }
        recipe = r.get();
        craftsLeft = Math.max(1, s.crafts());
        startCount = inv.count(s.itemId());
        boolean table = !recipe.value().canCraftInDimensions(2, 2);
        station = table ? Items.CRAFTING_TABLE : null;
        phase = table ? Phase.FIND : Phase.WORK;
    }

    @Override
    public void beginSmelt(PlanStep s, ControlLease l) {
        start(Op.SMELT, s, l);
        Optional<RecipeHolder<?>> r = lookupRecipe(s.recipeId());
        if (r.isEmpty()) {
            fail("Unknown recipe " + s.recipeId());
            return;
        }
        recipe = r.get();
        RecipeType<?> t = recipe.value().getType();
        station = t == RecipeType.BLASTING ? Items.BLAST_FURNACE : t == RecipeType.SMOKING ? Items.SMOKER
                : Items.FURNACE;
        startCount = inv.count(s.itemId());
        craftsLeft = Math.max(1, s.crafts());
        phase = Phase.FIND;
    }

    @Override
    public void beginFetch(long packedPos, Map<String, Integer> items, ControlLease l) {
        start(Op.FETCH, null, l);
        beginTransfer(packedPos, items);
    }

    @Override
    public void beginDeposit(long packedPos, Map<String, Integer> items, ControlLease l) {
        start(Op.DEPOSIT, null, l);
        beginTransfer(packedPos, items);
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
            case COLLECT -> collectSmelted(player);
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
            if (pinv.items.get(i).getItem() == item) {
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
        if (spot == Long.MIN_VALUE) {
            fail("No free spot to place " + station);
            return;
        }
        stationPos = spot;
        supportPos = BlockPos.offset(spot, Direction.DOWN);
        Services.input().setRotationTarget(lease, aimAt(player, BlockPos.getX(supportPos) + 0.5,
                BlockPos.getY(supportPos) + 1.0, BlockPos.getZ(supportPos) + 0.5));
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
                && bh.getBlockPos().asLong() == supportPos && bh.getDirection() == Direction.UP) {
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
                if (craftsLeft <= 0 || inv.count(step.itemId()) - startCount >= step.count()) {
                    finish(player, "Crafted " + step.itemId());
                    return;
                }
                if (placedAt < 0) {
                    if (mc.gameMode == null) {
                        fail("No game mode");
                        return;
                    }
                    if (!player.getRecipeBook().contains(recipe.id())) {
                        // Vanilla unlocks a recipe through an advancement when an ingredient reaches the inventory;
                        // the unlock packet can arrive a few ticks after the ingredient was crafted, so wait briefly.
                        // The server refuses recipe-book placement for locked recipes; replanning cannot fix that.
                        if (timer > RECIPE_UNLOCK_WAIT) {
                            fail("Recipe " + recipe.id() + " is not unlocked in your recipe book");
                        }
                        return;
                    }
                    if (!Services.clicker().placeRecipe(lease, recipe.id().toString(), false)) {
                        fail("Could not place recipe " + recipe.id());
                        return;
                    }
                    placedAt = timer;
                } else if (timer >= placedAt + 5) {
                    Slot result = menu.getSlot(0);
                    if (result.getItem().isEmpty()) {
                        fail("Missing ingredients for " + step.itemId());
                        return;
                    }
                    Services.clicker().queueClick(lease, 0, 0, IContainerClicker.Click.QUICK_MOVE);
                    craftsLeft--;
                    placedAt = -1;
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
            case FETCH, DEPOSIT -> {
                Inventory pinv = player.getInventory();
                Map<String, Integer> left = new LinkedHashMap<>(wanted);
                for (Slot s : menu.slots) {
                    boolean playerSlot = s.container == pinv;
                    if (playerSlot == (op == Op.FETCH) || !s.hasItem()) {
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
            default -> fail("No operation");
        }
    }

    private void loadFurnace(LocalPlayer player, AbstractContainerMenu menu) {
        Inventory pinv = player.getInventory();
        Recipe<?> r = recipe.value();
        Ingredient ing = r.getIngredients().isEmpty() ? Ingredient.EMPTY : r.getIngredients().get(0);
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
            if (needInput > 0 && ing.test(st)) {
                Services.clicker().queueClick(lease, s.index, 0, IContainerClicker.Click.QUICK_MOVE);
                needInput -= st.getCount();
                continue;
            }
            double per = fuels.smeltsPer(inv.idOf(st.getItem()));
            if (needFuel > 0 && per > 0 && !ing.test(st)) {
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
            default -> finish(player, "");
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
            dimId = dimKey.location().toString();
        }
        return dimId;
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
                        probe.set(c.getX() + dx, y - 1, c.getZ() + dz);
                        BlockState below = level.getBlockState(probe);
                        if (below.isFaceSturdy(level, probe, Direction.UP)) {
                            return spot;
                        }
                    }
                }
            }
        }
        return Long.MIN_VALUE;
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

    private static Optional<RecipeHolder<?>> lookupRecipe(String id) {
        Minecraft mc = Minecraft.getInstance();
        ResourceLocation rl = id == null ? null : ResourceLocation.tryParse(id);
        if (mc.level == null || rl == null) {
            return Optional.empty();
        }
        return mc.level.getRecipeManager().byKey(rl);
    }
}
