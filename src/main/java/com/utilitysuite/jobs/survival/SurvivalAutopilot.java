package com.utilitysuite.jobs.survival;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.jobs.goal.GoalEngine;
import com.utilitysuite.jobs.goal.MinecraftInventoryView;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.ButtonBlock;
import net.minecraft.world.level.block.CraftingTableBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Survival Autopilot: one master toggle (the module) with collapsible sub-policies, all requesting SURVIVAL leases.
 * <ul>
 *   <li>Auto-Tool: on block attack start, select the fastest valid hotbar tool that keeps the desired drop.</li>
 *   <li>Durability protection: valuable tools under the threshold are never auto-selected; warns when no spare.</li>
 *   <li>Auto-Eat: below the hunger threshold, briefly preempt GOAL work and eat the least wasteful safe food.</li>
 *   <li>Hazard guard: lava next to the feet while automation walks: preempt movement and sneak briefly.</li>
 *   <li>Inventory pressure: drop junk-list stacks, optionally queue 9-to-1 compaction crafts.</li>
 *   <li>Protected areas: PROTECTED_NO_MINE boxes consumed by the Baritone adapter and the job executor.</li>
 *   <li>Death recovery: record and announce the death; optionally gather a recovery kit, travel back, then resume
 *       the interrupted job.</li>
 * </ul>
 */
public final class SurvivalAutopilot implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "survival_autopilot";

    private final ProtectedAreas protectedAreas;
    private final MinecraftInventoryView invView;
    private boolean enabled;
    private long tick;

    // settings
    private boolean autoTool = true;
    private boolean durabilityProtect = true;
    private float protectBelow = 0.10f;
    private enum EatPhase { NONE, PULL, USE, BACK }

    private boolean autoEat = true;
    private int hungerThreshold = 14;
    private float healthThreshold = 0.5f;
    private boolean hazardGuard = true;
    private boolean pressure = true;
    private int minFreeSlots = 2;
    private boolean compact = false;
    private Set<String> junk = InventoryPressurePolicy.DEFAULT_JUNK;
    private Set<String> pinned = Set.of();
    private Set<String> silkBlocks = Set.of("minecraft:glass", "minecraft:glass_pane", "minecraft:ender_chest",
            "minecraft:ice", "minecraft:packed_ice", "minecraft:blue_ice", "minecraft:bookshelf");
    private boolean deathRecovery = false;
    private boolean resumeAfterDeath = true;
    private Map<String, Integer> recoveryKit = Map.of("minecraft:stone_pickaxe", 1);

    // state
    private ControlLease eatLease;
    private ControlLease hazardLease;
    private ControlLease pressureLease;
    private long eatStart;
    private int eatStartFood;
    private int eatPrevSlot = -1;
    private int eatSlot = -1;
    private int eatSource = -1;
    private boolean eatPulled;
    private EatPhase eatPhase = EatPhase.NONE;
    /** Item key of what the pull displaced from the hotbar slot (recorded when the pull is queued). */
    private int eatDisplacedKey = FoodSelector.SwapBack.EMPTY;
    /** The owed swap-back of pulled food, kept across an early lease end (preemption, Stop-All, manual input). */
    private final FoodSelector.SwapBack swapBack = new FoodSelector.SwapBack();
    private boolean useInventoryFood = true;
    private boolean swapBackFood = true;
    private long hazardUntil;
    private long hazardCooldown;
    private boolean warnedDurability;
    private boolean dead;
    private long deathPos;
    private String deathDim;
    private long interruptedJob = -1;

    private final ToolSelector.Candidate[] candidates = new ToolSelector.Candidate[9];
    private final String[] foodIds = new String[36];
    private final boolean[] foodOccupied = new boolean[FoodSelector.HOTBAR];
    private final int[] foodNutrition = new int[36];
    private final float[] foodSaturation = new float[36];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private ResourceKey<Level> dimKey;
    private String dimId = "minecraft:overworld";
    private Holder<Enchantment> silkHolder;
    private Holder<Enchantment> fortuneHolder;
    private Object holderSource;

    public SurvivalAutopilot(ProtectedAreas protectedAreas, MinecraftInventoryView invView) {
        this.protectedAreas = protectedAreas;
        this.invView = invView;
        for (int i = 0; i < 9; i++) {
            candidates[i] = new ToolSelector.Candidate();
        }
    }

    public ProtectedAreas protectedAreas() {
        return protectedAreas;
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        checkDeath(mc, player);
        if (dead) {
            return;
        }
        if (eatLease != null) {
            tickEating(player);
        } else if (swapBack.deferred()) {
            if (tick % 5 == 0) {
                tryDeferredSwapBack(mc, player);
            }
        } else if (autoEat && tick % 10 == 0) {
            maybeStartEating(mc, player);
        }
        if (hazardLease != null && tick >= hazardUntil) {
            Services.input().setKeyHeld(hazardLease, SimKey.SNEAK, false);
            closeLease(hazardLease);
            hazardLease = null;
        } else if (hazardGuard && hazardLease == null && tick % 10 == 5 && tick >= hazardCooldown) {
            checkLava(mc, player);
        }
        if (pressureLease != null) {
            if (Services.clicker().pending() == 0) {
                closeLease(pressureLease);
                pressureLease = null;
            }
        } else if (pressure && tick % 100 == 50 && mc.screen == null) {
            checkPressure(player);
        }
    }

    // ---- auto-tool / durability ------------------------------------------------------------------------------------

    /** NeoForge client event: the player started attacking a block. */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!enabled || !autoTool || !event.getLevel().isClientSide()
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || player.isCreative() || Services.modState().isAutomationStopped()) {
            return;
        }
        BlockState state = event.getLevel().getBlockState(event.getPos());
        if (state.isAir()) {
            return;
        }
        refreshHolders(mc);
        Inventory inv = player.getInventory();
        int current = selectedSlot(inv);
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.items.get(i);
            float durability = s.isDamageableItem() && s.getMaxDamage() > 0
                    ? 1f - (float) s.getDamageValue() / s.getMaxDamage() : 1f;
            candidates[i].set(i, s.getDestroySpeed(state), s.isCorrectToolForDrops(state),
                    EnchantmentHelper.getItemEnchantmentLevel(silkHolder, s),
                    EnchantmentHelper.getItemEnchantmentLevel(fortuneHolder, s), durability, isValuable(s));
        }
        String blockId = invView.idOf(state.getBlock().asItem());
        ToolSelector.DropPreference pref = silkBlocks.contains(blockId) ? ToolSelector.DropPreference.SILK
                : blockId.endsWith("_ore") ? ToolSelector.DropPreference.FORTUNE : ToolSelector.DropPreference.ANY;
        float protect = durabilityProtect ? protectBelow : -1f;
        int slot = ToolSelector.choose(candidates, 9, state.requiresCorrectToolForDrops(), pref, protect, current);
        if (current >= 0 && durabilityProtect && slot == current
                && ToolSelector.needsProtection(candidates[current], protectBelow)) {
            if (!warnedDurability && !Services.modState().isCleanScreen()) {
                warnedDurability = true;
                IWaypointService wp = Services.waypointsOrNull();
                String where = wp != null && wp.byName("Repair").isPresent() ? " (waypoint 'Repair' is set)" : "";
                player.displayClientMessage(Component.translatable("message.utilitysuite.tool_low_durability",
                        Math.round(protectBelow * 100), where), true);
            }
            return;
        }
        warnedDurability = false;
        if (slot == current || slot < 0) {
            return;
        }
        ControlLease l = Services.arbiter().request(new LeaseRequest(ID, ControlPriority.SURVIVAL,
                EnumSet.of(ControlChannel.HOTBAR), 10, this)).orElse(null);
        if (l != null) {
            Services.input().selectHotbarSlot(l, slot);
            closeLease(l);
        }
    }

    private void refreshHolders(Minecraft mc) {
        Object src = mc.level.registryAccess();
        if (src != holderSource) {
            holderSource = src;
            var reg = mc.level.registryAccess().registryOrThrow(Registries.ENCHANTMENT);
            silkHolder = reg.getHolderOrThrow(Enchantments.SILK_TOUCH);
            fortuneHolder = reg.getHolderOrThrow(Enchantments.FORTUNE);
        }
    }

    private static boolean isValuable(ItemStack s) {
        if (s.isEnchanted()) {
            return true;
        }
        if (s.getItem() instanceof TieredItem t) {
            Tier tier = t.getTier();
            return tier == Tiers.DIAMOND || tier == Tiers.NETHERITE;
        }
        return false;
    }

    /** Index of the selected hotbar slot, found by comparing the held stack with the hotbar (no direct field access). */
    private static int selectedSlot(Inventory inv) {
        ItemStack held = inv.getSelected();
        for (int i = 0; i < 9; i++) {
            if (inv.items.get(i) == held) {
                return i;
            }
        }
        return -1;
    }

    // ---- auto-eat ---------------------------------------------------------------------------------------------------

    private void maybeStartEating(Minecraft mc, LocalPlayer player) {
        int food = player.getFoodData().getFoodLevel();
        boolean longLeg = Services.arbiter().isHeldBy(ControlChannel.MOVEMENT, GoalEngine.ID);
        if (!FoodSelector.shouldEat(food, player.getHealth(), player.getMaxHealth(), hungerThreshold, healthThreshold,
                longLeg) || player.isUsingItem() || mc.screen != null || player.isCreative()
                || player.containerMenu != player.inventoryMenu) {
            return;
        }
        Inventory inv = player.getInventory();
        int scan = useInventoryFood ? 36 : 9;
        for (int i = 0; i < 36; i++) {
            ItemStack s = i < scan ? inv.items.get(i) : ItemStack.EMPTY;
            FoodProperties fp = s.isEmpty() ? null : s.get(DataComponents.FOOD);
            foodIds[i] = fp == null ? null : invView.idOf(s.getItem());
            foodNutrition[i] = fp == null ? 0 : fp.nutrition();
            foodSaturation[i] = fp == null ? 0 : fp.saturation();
        }
        int slot = FoodSelector.choose(foodIds, foodNutrition, foodSaturation, food, longLeg);
        if (slot < 0 || targetsInteractiveBlock(mc)) {
            return;
        }
        boolean pull = slot >= FoodSelector.HOTBAR;
        if (pull && !FoodSelector.canPull(mc.screen != null, mc.gameMode != null && mc.gameMode.isDestroying())) {
            return;
        }
        ControlLease l = Services.arbiter().request(new LeaseRequest(ID, ControlPriority.SURVIVAL,
                EnumSet.of(ControlChannel.HOTBAR, ControlChannel.WORLD_INTERACT), 100, this)).orElse(null);
        if (l == null) {
            return;
        }
        eatLease = l;
        eatPrevSlot = selectedSlot(inv);
        eatStart = tick;
        eatStartFood = food;
        eatPulled = false;
        if (pull) {
            for (int i = 0; i < FoodSelector.HOTBAR; i++) {
                foodOccupied[i] = !inv.items.get(i).isEmpty();
            }
            eatSource = slot;
            eatSlot = FoodSelector.chooseSwapTarget(foodOccupied, eatPrevSlot);
            eatDisplacedKey = itemKey(inv.items.get(eatSlot));
            // SWAP: the menu slot of a main-inventory index equals the index; button = hotbar index.
            if (!Services.clicker().queueClick(l, slot, eatSlot, IContainerClicker.Click.SWAP)) {
                closeLease(l);
                eatLease = null;
                return;
            }
            eatPulled = true;
            eatPhase = EatPhase.PULL;
            return;
        }
        eatSlot = slot;
        beginUsing(l);
    }

    private void beginUsing(ControlLease l) {
        eatPhase = EatPhase.USE;
        eatStart = tick;
        Services.input().selectHotbarSlot(l, eatSlot);
        Services.input().setKeyHeld(l, SimKey.USE, true);
    }

    private void tickEating(LocalPlayer player) {
        ControlLease l = eatLease;
        if (!l.isActive()) {
            if (eatPulled && eatPhase == EatPhase.PULL) {
                notePullOutcome(player); // the click may have landed before the lease ran out
            }
            swapBack.leaseEnded(); // expired before the swap-back: restore at the next safe moment
            endEating(l);
            return;
        }
        if (eatPhase == EatPhase.PULL) {
            if (Services.clicker().pending() == 0 && tick - eatStart > 1) {
                if (notePullOutcome(player)) {
                    beginUsing(l);
                } else {
                    endEating(l); // the swap did not land (screen opened, items moved): give up quietly
                }
            } else if (tick - eatStart > 60) {
                Services.clicker().cancel(l);
                endEating(l);
            }
            return;
        }
        if (eatPhase == EatPhase.BACK) {
            if ((Services.clicker().pending() == 0 && tick - eatStart > 1) || tick - eatStart > 60) {
                if (Services.clicker().pending() == 0) {
                    swapBack.forget(); // the swap-back went out
                } else {
                    swapBack.leaseEnded();
                }
                endEating(l);
            }
            return;
        }
        boolean done = player.getFoodData().getFoodLevel() > eatStartFood
                || (tick - eatStart > 10 && !player.isUsingItem()) || tick - eatStart > 80;
        if (!done) {
            return;
        }
        Services.input().setKeyHeld(l, SimKey.USE, false);
        if (eatPrevSlot >= 0) {
            Services.input().selectHotbarSlot(l, eatPrevSlot);
        }
        if (FoodSelector.needsSwapBack(eatPulled, swapBackFood) && swapBack.owed()) {
            if (Services.clicker().queueClick(l, swapBack.source(), swapBack.hotbar(),
                    IContainerClicker.Click.SWAP)) {
                eatPhase = EatPhase.BACK;
                eatStart = tick;
                return;
            }
            swapBack.leaseEnded();
        } else {
            swapBack.forget();
        }
        endEating(l);
    }

    /**
     * After the pull click: records the owed swap-back when the food landed in the hotbar slot.
     *
     * @return true when the pulled food is in the hotbar slot
     */
    private boolean notePullOutcome(LocalPlayer player) {
        Inventory inv = player.getInventory();
        ItemStack landed = inv.items.get(eatSlot);
        if (!landed.has(DataComponents.FOOD)) {
            return false;
        }
        if (swapBackFood) {
            swapBack.pulled(eatSource, eatSlot, itemKey(landed), eatDisplacedKey);
        }
        return true;
    }

    /**
     * The owed swap-back after an early lease end: at most every 5 ticks while one is owed. Clicks only when no screen
     * is open, nothing else holds the container channel, the user is not on the hotbar, automation runs, and both
     * slots still hold what the cycle left there; otherwise waits or forgets the restore without clicking.
     */
    private void tryDeferredSwapBack(Minecraft mc, LocalPlayer player) {
        if (!swapBackFood) {
            swapBack.forget();
            return;
        }
        var arbiter = Services.arbiter();
        boolean screenOpen = mc.screen != null || player.containerMenu != player.inventoryMenu;
        boolean busy = Services.clicker().pending() > 0 || arbiter.isAutomationStopped()
                || arbiter.isManualOverrideActive(ControlChannel.HOTBAR)
                || arbiter.holder(ControlChannel.HOTBAR).isPresent();
        Inventory inv = player.getInventory();
        int hb = swapBack.hotbar();
        int src = swapBack.source();
        FoodSelector.SwapBack.Step step = swapBack.decide(screenOpen, busy, itemKey(inv.items.get(hb)),
                itemKey(inv.items.get(src)));
        if (step == FoodSelector.SwapBack.Step.DROP) {
            swapBack.forget();
            return;
        }
        if (step != FoodSelector.SwapBack.Step.RESTORE) {
            return;
        }
        ControlLease l = arbiter.request(new LeaseRequest(ID, ControlPriority.SURVIVAL,
                EnumSet.of(ControlChannel.HOTBAR), 40, this)).orElse(null);
        if (l == null) {
            return;
        }
        if (!Services.clicker().queueClick(l, src, hb, IContainerClicker.Click.SWAP)) {
            closeLease(l);
            return;
        }
        // the BACK phase closes the lease once the click went out and forgets the restore
        eatLease = l;
        eatPulled = true;
        eatPhase = EatPhase.BACK;
        eatStart = tick;
    }

    /** Opaque item key for {@link FoodSelector.SwapBack}: the registry id, {@link FoodSelector.SwapBack#EMPTY} if empty. */
    private static int itemKey(ItemStack s) {
        return s.isEmpty() ? FoodSelector.SwapBack.EMPTY : Item.getId(s.getItem());
    }

    private void endEating(ControlLease l) {
        closeLease(l);
        eatLease = null;
        eatPhase = EatPhase.NONE;
        eatPulled = false;
    }

    /** Holding USE on a door, chest or table would interact with it instead of eating. */
    private static boolean targetsInteractiveBlock(Minecraft mc) {
        HitResult hit = mc.hitResult;
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return false;
        }
        if (hit.getType() == HitResult.Type.ENTITY) {
            return true;
        }
        Block b = mc.level.getBlockState(((BlockHitResult) hit).getBlockPos()).getBlock();
        return b instanceof BaseEntityBlock || b instanceof DoorBlock || b instanceof TrapDoorBlock
                || b instanceof FenceGateBlock || b instanceof ButtonBlock || b instanceof LeverBlock
                || b instanceof BedBlock || b instanceof CraftingTableBlock;
    }

    // ---- hazards ----------------------------------------------------------------------------------------------------

    private void checkLava(Minecraft mc, LocalPlayer player) {
        if (!Services.arbiter().isHeldBy(ControlChannel.MOVEMENT, GoalEngine.ID)) {
            return; // only guard automation; the user walks on their own
        }
        BlockPos feet = player.blockPosition();
        Level level = mc.level;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = -1; dy <= 0; dy++) {
                    probe.set(feet.getX() + dx, feet.getY() + dy, feet.getZ() + dz);
                    if (level.getFluidState(probe).is(FluidTags.LAVA)) {
                        ControlLease l = Services.arbiter().request(new LeaseRequest(ID, ControlPriority.SURVIVAL,
                                EnumSet.of(ControlChannel.MOVEMENT), 40, this)).orElse(null);
                        if (l != null) {
                            hazardLease = l;
                            hazardUntil = tick + 20;
                            hazardCooldown = tick + 100;
                            Services.input().setKeyHeld(l, SimKey.SNEAK, true);
                            post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID,
                                    "Lava next to the player at " + probe.toShortString()
                                            + "; pausing automation briefly"));
                        }
                        return;
                    }
                }
            }
        }
    }

    // ---- inventory pressure -----------------------------------------------------------------------------------------

    private void checkPressure(LocalPlayer player) {
        Inventory inv = player.getInventory();
        int free = 0;
        for (int i = 0; i < 36; i++) {
            if (inv.items.get(i).isEmpty()) {
                free++;
            }
        }
        if (free >= minFreeSlots) {
            return;
        }
        List<InventoryPressurePolicy.Stack> stacks = new ArrayList<>();
        for (int i = 0; i < 36; i++) {
            ItemStack s = inv.items.get(i);
            if (s.isEmpty()) {
                continue;
            }
            boolean tool = s.getItem() instanceof TieredItem || s.getItem() instanceof ArmorItem
                    || s.isDamageableItem();
            int menuSlot = i < 9 ? InventoryMenu.USE_ROW_SLOT_START + i : i;
            stacks.add(new InventoryPressurePolicy.Stack(menuSlot, invView.idOf(s.getItem()), s.getCount(), tool,
                    s.isEnchanted()));
        }
        Set<InventoryPressurePolicy.Rule> rules = EnumSet.of(InventoryPressurePolicy.Rule.KEEP_VALUABLES,
                InventoryPressurePolicy.Rule.DROP_JUNK_LIST);
        if (compact) {
            rules.add(InventoryPressurePolicy.Rule.COMPACT_BLOCKS);
        }
        List<InventoryPressurePolicy.Action> actions = InventoryPressurePolicy.decide(stacks, free, minFreeSlots,
                rules, junk, pinned);
        if (actions.isEmpty()) {
            return;
        }
        ControlLease l = null;
        for (InventoryPressurePolicy.Action a : actions) {
            if (a instanceof InventoryPressurePolicy.Action.Drop d) {
                if (l == null) {
                    l = Services.arbiter().request(new LeaseRequest(ID, ControlPriority.SURVIVAL,
                            EnumSet.of(ControlChannel.HOTBAR), 200, this)).orElse(null);
                    if (l == null) {
                        return;
                    }
                }
                Services.clicker().queueClick(l, d.slot(), 1, IContainerClicker.Click.THROW);
            } else if (a instanceof InventoryPressurePolicy.Action.Compact c) {
                IGoalEngine goals = Services.goalsOrNull();
                if (goals != null && goals.activeJob().isEmpty() && goals.queuedJobs().isEmpty()) {
                    goals.submit(new GoalRequest.Craft(c.blockId(), c.blocks(), CostProfile.CHEAPEST,
                            DataSourceMode.OBSERVED_ONLY));
                }
            }
        }
        pressureLease = l;
    }

    // ---- death recovery ---------------------------------------------------------------------------------------------

    private void checkDeath(Minecraft mc, LocalPlayer player) {
        boolean nowDead = player.isDeadOrDying();
        if (nowDead && !dead) {
            dead = true;
            deathPos = player.blockPosition().asLong();
            deathDim = dimension(mc.level);
            post(new SuiteEvent.PlayerDied(System.currentTimeMillis(), deathPos, deathDim));
            IWaypointService wp = Services.waypointsOrNull();
            if (wp != null) {
                wp.add("Death " + java.time.LocalTime.now().withNano(0), "Death Sites", BlockPos.getX(deathPos),
                        BlockPos.getY(deathPos), BlockPos.getZ(deathPos), deathDim);
            }
            IGoalEngine goals = Services.goalsOrNull();
            interruptedJob = -1;
            if (goals != null) {
                goals.activeJob().ifPresent(j -> {
                    interruptedJob = j.jobId();
                    goals.pause(j.jobId());
                });
            }
            releaseAll();
        } else if (!nowDead && dead) {
            dead = false;
            if (deathRecovery) {
                startRecovery();
            }
        }
    }

    private void startRecovery() {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return;
        }
        if (!recoveryKit.isEmpty()) {
            goals.submit(new GoalRequest.GatherMany(recoveryKit, "death recovery kit", CostProfile.SAFEST,
                    DataSourceMode.OBSERVED_ONLY));
        }
        goals.submit(new GoalRequest.Travel(BlockPos.getX(deathPos), BlockPos.getY(deathPos), BlockPos.getZ(deathPos),
                deathDim, CostProfile.SAFEST, DataSourceMode.OBSERVED_ONLY));
        if (interruptedJob >= 0 && resumeAfterDeath) {
            Services.modules().find(GoalEngine.class).ifPresent(e -> e.executor().requeueAtEnd(interruptedJob));
            goals.resume(interruptedJob);
        }
        interruptedJob = -1;
    }

    // ---- leases -----------------------------------------------------------------------------------------------------

    @Override
    public void onPreempted(ControlLease lease, LeaseEndReason reason, String byModuleId) {
        if (lease == eatLease) {
            if (eatPulled && eatPhase == EatPhase.PULL) {
                LocalPlayer player = Minecraft.getInstance().player;
                if (player != null) {
                    notePullOutcome(player); // the pull may have landed just before the preemption
                }
            }
            swapBack.leaseEnded(); // keep an owed swap-back for the next safe moment
            eatLease = null;
            eatPhase = EatPhase.NONE;
            eatPulled = false;
            Services.clicker().cancel(lease);
        } else if (lease == hazardLease) {
            hazardLease = null;
        } else if (lease == pressureLease) {
            pressureLease = null;
        }
    }

    private static void closeLease(ControlLease l) {
        if (l != null && l.isActive()) {
            l.close();
        }
    }

    private void releaseAll() {
        closeLease(eatLease);
        closeLease(hazardLease);
        closeLease(pressureLease);
        eatLease = null;
        hazardLease = null;
        pressureLease = null;
    }

    private String dimension(Level level) {
        if (level.dimension() != dimKey) {
            dimKey = level.dimension();
            dimId = dimKey.location().toString();
        }
        return dimId;
    }

    private static void post(SuiteEvent e) {
        ISuiteEventBus bus = Services.eventsOrNull();
        if (bus != null) {
            bus.post(e);
        }
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        if (!enabled) {
            releaseAll();
            swapBack.forget(); // a disabled module never clicks
            eatPhase = EatPhase.NONE;
            eatPulled = false;
        }
        this.enabled = enabled;
    }

    @Override
    public void onStopAll() {
        if (eatLease != null) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (eatPulled && eatPhase == EatPhase.PULL && player != null) {
                notePullOutcome(player);
            }
            swapBack.leaseEnded(); // restored once automation runs again and the slots are unchanged
        }
        eatPhase = EatPhase.NONE;
        eatPulled = false;
        eatLease = null;
        hazardLease = null;
        pressureLease = null;
    }

    @Override
    public void onWorldUnload() {
        releaseAll();
        swapBack.forget(); // slot indices mean nothing in the next world
        eatPhase = EatPhase.NONE;
        eatPulled = false;
        dead = false;
        interruptedJob = -1;
        holderSource = null;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        autoTool = p.getBool(ID, "auto_tool", true);
        durabilityProtect = p.getBool(ID, "durability_protect", true);
        protectBelow = p.getInt(ID, "durability_percent", 10) / 100f;
        autoEat = p.getBool(ID, "auto_eat", true);
        hungerThreshold = p.getInt(ID, "hunger_threshold", 14);
        useInventoryFood = p.getBool(ID, "use_inventory_food", true);
        swapBackFood = p.getBool(ID, "eat_swap_back", true);
        healthThreshold = p.getInt(ID, "health_percent", 50) / 100f;
        hazardGuard = p.getBool(ID, "hazard_guard", true);
        pressure = p.getBool(ID, "inventory_pressure", true);
        minFreeSlots = p.getInt(ID, "min_free_slots", 2);
        compact = p.getBool(ID, "compact_blocks", false);
        junk = parseIds(p.getString(ID, "junk_items", String.join(",", InventoryPressurePolicy.DEFAULT_JUNK)));
        pinned = parseIds(p.getString(ID, "pinned_items", ""));
        deathRecovery = p.getBool(ID, "death_recovery", false);
        resumeAfterDeath = p.getBool(ID, "resume_after_death", true);
        recoveryKit = parseKit(p.getString(ID, "recovery_kit", "minecraft:stone_pickaxe=1"));
        List<String> errors = new ArrayList<>();
        protectedAreas.set(ProtectedAreas.parse(p.getString(ID, "protected_areas", ""), errors));
        for (String e : errors) {
            post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID, "Protected area ignored: " + e));
        }
    }

    static Set<String> parseIds(String text) {
        Set<String> out = new HashSet<>();
        for (String t : text.split("[,;\\s]+")) {
            if (!t.isBlank()) {
                out.add(t.contains(":") ? t.trim() : "minecraft:" + t.trim());
            }
        }
        return Set.copyOf(out);
    }

    static Map<String, Integer> parseKit(String text) {
        Map<String, Integer> out = new LinkedHashMap<>();
        for (String t : text.split("[,;\\s]+")) {
            int eq = t.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            try {
                String id = t.substring(0, eq).trim();
                out.put(id.contains(":") ? id : "minecraft:" + id, Integer.parseInt(t.substring(eq + 1).trim()));
            } catch (NumberFormatException ignored) {
                // skip malformed entry
            }
        }
        return Map.copyOf(out);
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Survival Autopilot";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("auto_tool", "Auto-Tool", true, "Auto-Tool",
                        "Select the fastest valid hotbar tool when you start breaking a block"),
                SettingSpec.bool("durability_protect", "Protect valuable tools", true, "Auto-Tool",
                        "Never auto-select enchanted/diamond tools below the threshold"),
                SettingSpec.integer("durability_percent", "Protect below (%)", 10, 1, 50, "Auto-Tool", ""),
                SettingSpec.bool("auto_eat", "Auto-Eat", true, "Auto-Eat",
                        "Eat safe food below the threshold (briefly pauses jobs)"),
                SettingSpec.integer("hunger_threshold", "Eat at hunger", 14, 1, 19, "Auto-Eat", ""),
                SettingSpec.bool("use_inventory_food", "Use food from the whole inventory", true, "Auto-Eat",
                        "Pull the best food from the pack into the hotbar when the hotbar has none"),
                SettingSpec.bool("eat_swap_back", "Swap back", true, "Auto-Eat",
                        "Put the hotbar item back after eating food pulled from the pack"),
                SettingSpec.integer("health_percent", "Top up below health (%)", 50, 10, 100, "Auto-Eat", "")
                        .asAdvanced(),
                SettingSpec.bool("hazard_guard", "Lava guard", true, "Safety",
                        "Pause automated walking briefly when lava is next to the player"),
                SettingSpec.bool("inventory_pressure", "Inventory pressure rules", true, "Inventory",
                        "Drop junk when the inventory is nearly full; valuables, tools and pinned items are kept"),
                SettingSpec.integer("min_free_slots", "Keep free slots", 2, 1, 9, "Inventory", ""),
                SettingSpec.bool("compact_blocks", "Compact into blocks", false, "Inventory",
                        "Queue 9-to-1 block crafts (needs the goal engine to be idle)"),
                SettingSpec.text("junk_items", "Junk list", String.join(",", InventoryPressurePolicy.DEFAULT_JUNK),
                        "Inventory", "Comma-separated item ids").asAdvanced(),
                SettingSpec.text("pinned_items", "Pinned items", "", "Inventory",
                        "Never dropped or compacted").asAdvanced(),
                SettingSpec.text("protected_areas", "Protected areas (no mining)", "", "Protected Areas",
                        "x1 y1 z1 x2 y2 z2 [dimension]; ..."),
                SettingSpec.bool("death_recovery", "Death recovery", false, "Death Recovery",
                        "After respawning: gather the recovery kit, travel back to the death point"),
                SettingSpec.bool("resume_after_death", "Resume interrupted job", true, "Death Recovery", ""),
                SettingSpec.text("recovery_kit", "Recovery kit", "minecraft:stone_pickaxe=1", "Death Recovery",
                        "item=count, ...").asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    /** Last recorded death (packed pos) or {@link Long#MIN_VALUE}. */
    public long lastDeathPos() {
        return deathDim == null ? Long.MIN_VALUE : deathPos;
    }

    /** True while the module holds a lease (tests, HUD). */
    public boolean busy() {
        return eatLease != null || hazardLease != null || pressureLease != null;
    }
}
