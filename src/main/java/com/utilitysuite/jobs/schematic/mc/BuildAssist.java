package com.utilitysuite.jobs.schematic.mc;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.plan.BuildPlan;
import com.utilitysuite.jobs.schematic.walk.ReachWalker;
import com.utilitysuite.jobs.schematic.walk.StandingSpotFinder;
import com.utilitysuite.jobs.schematic.world.PlacementScan;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import com.utilitysuite.core.api.IBlockChangeFeed;
import com.utilitysuite.jobs.schematic.bom.SupplyTrip;
import com.utilitysuite.jobs.schematic.plan.RestockCollector;
import com.utilitysuite.jobs.schematic.plan.ScaffoldLedger;
import com.utilitysuite.jobs.schematic.plan.SupportFaceRanker;
import net.minecraft.core.registries.BuiltInRegistries;

import java.util.BitSet;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Build Assist: while the player is near the site, places the plan's missing blocks that are within reach.
 * <ul>
 *   <li>Acquires a {@code WORLD_INTERACT + CAMERA + HOTBAR} lease at {@link ControlPriority#GOAL} only when it has a
 *       placeable target, and releases it after a short idle spell, so it never sits on the user's controls.</li>
 *   <li>Aims through {@link IInputSmoother} (curved rotation; SILENT mode keeps the camera with the user, placement
 *       then uses a hit result computed from the silent angles' target face). Selects hotbar slots and swaps blocks
 *       from the main inventory only through the core.</li>
 *   <li>Spacing: gaussian delay between placements, at most N placements per tick window.</li>
 *   <li>Manual input / preemption ends the lease; the assist pauses, and on {@code onResumable} continues from the
 *       first block of the plan that is still missing.</li>
 * </ul>
 * Hot path: the per-tick code reuses fields and a mutable position; the only allocations happen per placement
 * (the rotation target record and the vanilla hit result), never per idle tick.
 */
public final class BuildAssist implements LeaseListener {

    private static final Direction[] DIRS = Direction.values();
    private static final int SEARCH_PER_TICK = 256;
    private static final int LOOKAHEAD = 2048;
    private static final int IDLE_RELEASE_TICKS = 40;
    private static final int AIM_TIMEOUT_TICKS = 30;
    private static final int BREAK_TIMEOUT_TICKS = 200;

    private final String ownerId;
    private final LeaseRequest leaseRequest;
    private final BlockPos.MutableBlockPos cursorPos = new BlockPos.MutableBlockPos();

    // Plan context (client thread).
    private BuildPlan plan;
    private PlacementScan scan;
    private McWorldProbe probe;
    private Item[] items;
    private Item scaffoldItem;

    // Settings.
    private boolean silent;
    private int maxPerWindow = 4;
    private int windowTicks = 20;
    private double meanDelayTicks = 4;
    private int swapSlot = 8;

    // Runtime state.
    private ControlLease lease;
    private boolean pausedByPreemption;
    private int leaseRetry;
    private int cooldown;
    private int tick;
    private int windowStart;
    private int windowCount;
    private int idleTicks;
    private int firstOpen;
    private int searchPos;
    private int targetStep = -1;
    private int targetCell;
    private int targetX;
    private int targetY;
    private int targetZ;
    private int targetFace = -1;
    private int aimTicks;
    private int breakTicks;
    private boolean aimSet;
    private int waitTicks;
    private int placements;

    // Cells whose aim timed out from where the player stands (every support face hidden behind other blocks). They are
    // skipped until the player moves to another block, so the rest of the build continues and the card can say why.
    private final java.util.BitSet blockedCells = new java.util.BitSet();
    private long blockedFrom = Long.MIN_VALUE;

    // "Walk to reach blocks": after WALK_STALL_TICKS with nothing placeable from here, walk to a standing spot.
    private static final int WALK_STALL_TICKS = 40;
    private static final int WALK_UNREACHABLE_TICKS = 1200;
    private final String walkOwnerId;
    private final ReachWalker walker = new ReachWalker(WALK_STALL_TICKS, WALK_UNREACHABLE_TICKS);
    private final StandingSpotFinder spotFinder = new StandingSpotFinder(4);
    private final ReachWalker.Host walkHost = new WalkHost();
    private boolean walkToReach = true;
    private boolean requireLineOfSight = true;
    private boolean stalledLastTick;
    private LocalPlayer walkPlayer;

    // Support face choice: valid faces ranked by how close they sit to the current look direction.
    private final SupportFaceRanker faceRanker = new SupportFaceRanker();

    // Material restock. Per-tick "item on hand" cache by palette index (last slot: scaffold), stamped with the tick.
    /** Placements per restock round are capped at this many stacks of each item (inventory room). */
    static final int RESTOCK_STACKS_PER_ITEM = 4;
    /** Plan steps looked at when collecting the next missing items (once per restock request, not per tick). */
    private static final int RESTOCK_SCAN_STEPS = 16384;
    /** After "nothing stored", ask again only this much later (the storage index may learn new chests). */
    private static final int RESTOCK_RETRY_TICKS = 200;
    private int[] haveStamp = new int[0];
    private boolean[] haveItem = new boolean[0];
    private final RestockCollector restock = new RestockCollector();
    private RestockHook restockHook;
    private boolean sweepMissing;
    private boolean sweepUsable;
    private boolean restockQueued;
    private int restockRetryAt;
    private String restockNotice;

    // Auto-supply between layers: checked once when the first open step moves to a new Y level.
    private boolean autoSupply = true;
    private int layerY = Integer.MIN_VALUE;
    private boolean layerCheckDue;

    // Temporary scaffolding cleanup: blocks that appeared next to the player during a "walk to reach" run (the
    // navigator pillaring / bridging) and are not part of the schematic. Recorded from the block-change feed into a
    // preallocated buffer, filtered on the tick, removed top-down once the plan is finished.
    private static final int PENDING_CHANGES_MAX = 256;
    private static final double SCAFFOLD_RECORD_RADIUS = 5.0;
    private final ScaffoldLedger ledger = new ScaffoldLedger();
    private final long[] pendingChanges = new long[PENDING_CHANGES_MAX];
    private int pendingCount;
    private final IBlockChangeFeed.Listener changeListener = this::onBlockChanged;
    private IBlockChangeFeed subscribedFeed;
    private boolean cleanupEnabled = true;
    private boolean cleanupRequested;
    private boolean cleanupPending;
    private boolean cleanupTarget;
    private long cleanupPos;
    private long[] cleanupOrder = new long[16];
    private int cleanupLeftUnder = -1;
    private int cleanupOutOfReach = -1;
    private String cleanupNotice;
    /** A recorded scaffold block sits in a schematic cell: clean up before the build finishes, not after. */
    private boolean scaffoldInCells;
    private final long[] stepOffSpots = new long[4];

    /**
     * Called once when the build has run out of materials: nothing in the look-ahead window is placeable with what the
     * inventory holds, and some of it needs items the inventory lacks. Client thread, from inside {@link #tick}: the
     * hook must not stop Build Assist synchronously (defer the hand-over to the next job-status poll).
     */
    public interface RestockHook {
        /**
         * @param wanted     item id to count, the next missing palette items in plan order (at most
         *                   {@link RestockCollector#DEFAULT_CAPACITY})
         * @param fromPacked the player's block position, packed
         * @param dimension  the player's dimension id
         * @return true when a fetch round was queued; false when nothing wanted is stored nearby
         */
        boolean requestRestock(Map<String, Integer> wanted, long fromPacked, String dimension);

        /**
         * Auto-supply between layers: the build has moved on to a new layer and the inventory does not cover it.
         * Same threading rules as {@link #requestRestock}.
         *
         * @param shortfall item id to the count the layer needs beyond the inventory (plan order)
         * @return true when a supply trip was queued
         */
        default boolean requestSupply(Map<String, Integer> shortfall, long fromPacked, String dimension) {
            return false;
        }
    }

    public BuildAssist(String ownerId) {
        this(ownerId, ownerId + ".walk");
    }

    /**
     * @param walkOwnerId lease owner of the "walk to reach" runs; must differ from {@code ownerId} so the walk lease
     *                    and Build Assist's own lease never replace each other
     */
    public BuildAssist(String ownerId, String walkOwnerId) {
        this.ownerId = ownerId;
        this.walkOwnerId = walkOwnerId;
        this.leaseRequest = new LeaseRequest(ownerId, ControlPriority.GOAL,
                EnumSet.of(ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA, ControlChannel.HOTBAR), 0, this);
    }

    /** "Walk to reach blocks" setting. Turning it off cancels a walk in progress. */
    /** "Require line of sight" setting (on by default): off places hidden faces directly instead of skipping them. */
    public void setRequireLineOfSight(boolean on) {
        requireLineOfSight = on;
        spotFinder.setRequireLineOfSight(on);
        blockedCells.clear();
    }

    public void setWalkToReach(boolean on) {
        if (walkToReach && !on) {
            walker.reset();
        }
        walkToReach = on;
    }

    public ReachWalker.Phase walkPhase() {
        return walkToReach ? walker.phase() : ReachWalker.Phase.IDLE;
    }

    public void configure(boolean silent, int maxPerWindow, int windowTicks, double meanDelayTicks, int swapSlot) {
        this.silent = silent;
        this.maxPerWindow = maxPerWindow;
        this.windowTicks = windowTicks;
        this.meanDelayTicks = meanDelayTicks;
        this.swapSlot = swapSlot;
    }

    /** Hook asked to fetch materials when the build runs out (null: just stall, as before). */
    public void setRestockHook(RestockHook hook) {
        restockHook = hook;
    }

    /** "Auto-supply between layers" setting. */
    public void setAutoSupply(boolean on) {
        autoSupply = on;
        if (!on) {
            layerCheckDue = false;
        }
    }

    /** "Clean up scaffolding" setting: remove recorded temporary blocks when the plan is finished. */
    public void setCleanupScaffold(boolean on) {
        cleanupEnabled = on;
        cleanupPending = (on || cleanupRequested) && !ledger.isEmpty();
    }

    /** Removes the recorded temporary blocks now (menu button), whether or not the setting is on. */
    public void requestScaffoldCleanup() {
        cleanupRequested = true;
        cleanupPending = !ledger.isEmpty();
    }

    /** Recorded temporary blocks not yet removed. */
    public int scaffoldCount() {
        return ledger.size();
    }

    /** True while recorded scaffolding is still to be removed (the job is not complete yet). */
    public boolean cleanupPending() {
        return cleanupPending;
    }

    /** Card notice about scaffolding left behind (under the player or out of reach), or null. */
    public String cleanupNotice() {
        return cleanupNotice;
    }

    /** Card notice about restocking (out of materials with nothing stored, or a fetch queued), or null. */
    public String restockNotice() {
        return restockNotice;
    }

    /** Installs (or refreshes) the plan. Keeps the progress cursor when the plan belongs to the same scan. */
    public void install(BuildPlan plan, PlacementScan scan, McWorldProbe probe, Item[] items, Item scaffoldItem) {
        boolean sameProject = this.scan == scan;
        this.plan = plan;
        this.scan = scan;
        this.probe = probe;
        this.items = items;
        this.scaffoldItem = scaffoldItem;
        firstOpen = 0;
        searchPos = 0;
        if (!sameProject) {
            placements = 0;
            blockedCells.clear(); // cell indices belong to the scan; a refreshed plan for the same scan keeps them
            walker.reset();
            resetRestock();
            // The ledger survives a new pass (a supply trip hands the build over to a new job): world unload clears it.
            pendingCount = 0;
            cleanupNotice = null;
            cleanupLeftUnder = -1;
            cleanupOutOfReach = -1;
        }
        cleanupPending = (cleanupEnabled || cleanupRequested) && !ledger.isEmpty();
        layerY = Integer.MIN_VALUE; // the first layer is checked too (after a supply trip it is usually covered)
        layerCheckDue = false;
        IBlockChangeFeed feed = Services.blockChangesOrNull();
        if (feed != null && feed != subscribedFeed) {
            feed.subscribe(changeListener);
            subscribedFeed = feed;
        }
        if (haveStamp.length != items.length + 1) {
            haveStamp = new int[items.length + 1];
            haveItem = new boolean[items.length + 1];
        }
        java.util.Arrays.fill(haveStamp, Integer.MIN_VALUE);
        startSweep();
        stalledLastTick = false;
        clearTarget();
    }

    private void resetRestock() {
        restockQueued = false;
        restockRetryAt = 0;
        restockNotice = null;
    }

    public boolean hasPlan() {
        return plan != null;
    }

    /** Cells currently skipped because they could not be aimed at from the player's position. */
    public int blockedCount() {
        return blockedCells.cardinality();
    }

    public int placements() {
        return placements;
    }

    public boolean isActive() {
        return (lease != null && lease.isActive()) || walker.isWalking();
    }

    /** Drops the plan and the lease (Stop-All, cancel, world unload); cancels a walk in progress. */
    public void stop() {
        walker.reset();
        stalledLastTick = false;
        walkPlayer = null;
        releaseLease();
        plan = null;
        scan = null;
        probe = null;
        items = null;
        pausedByPreemption = false;
        resetRestock();
        if (subscribedFeed != null) {
            subscribedFeed.unsubscribe(changeListener);
            subscribedFeed = null;
        }
        pendingCount = 0;
        cleanupRequested = false;
        cleanupPending = false;
        cleanupNotice = null;
        layerCheckDue = false;
        clearTarget();
    }

    /** Forgets the recorded temporary blocks (world unload: positions belong to that world). */
    public void clearScaffolding() {
        ledger.clear();
        cleanupPending = false;
        scaffoldInCells = false;
    }

    /** {@link IBlockChangeFeed} listener: cheap and allocation free, only buffers positions while a walk runs. */
    private void onBlockChanged(long packedPos) {
        if (walker.isWalking() && pendingCount < PENDING_CHANGES_MAX) {
            pendingChanges[pendingCount++] = packedPos;
        }
    }

    /** Records buffered changes that are solid blocks near the player and not part of the schematic. */
    private void drainBlockChanges(LocalPlayer player) {
        int n = pendingCount;
        pendingCount = 0;
        if (scan == null) {
            return;
        }
        double r2 = SCAFFOLD_RECORD_RADIUS * SCAFFOLD_RECORD_RADIUS;
        SchematicVolume v = scan.volume();
        for (int i = 0; i < n; i++) {
            long p = pendingChanges[i];
            int x = BlockPos.getX(p), y = BlockPos.getY(p), z = BlockPos.getZ(p);
            double dx = x + 0.5 - player.getX(), dy = y + 0.5 - player.getY(), dz = z + 0.5 - player.getZ();
            if (dx * dx + dy * dy + dz * dz > r2 || probe.probe(x, y, z, -1) != PlacementStatus.W_OTHER) {
                continue;
            }
            int lx = x - scan.originX(), ly = y - scan.originY(), lz = z - scan.originZ();
            if (v.contains(lx, ly, lz) && v.isBlock(v.paletteIndex(lx, ly, lz))) {
                int w = probe.probe(x, y, z, v.paletteIndex(lx, ly, lz));
                if (w == PlacementStatus.W_MATCH || w == PlacementStatus.W_SAME_BLOCK) {
                    continue; // the schematic's own block
                }
                // The walk put a scaffold block where the build needs another one (playtest 14-fieldjobs dome stuck at
                // 24/33): it is ours to take down, and it blocks that cell until it is.
                scaffoldInCells = true;
            }
            ledger.record(p);
        }
        if (n > 0 && (cleanupEnabled || cleanupRequested) && !ledger.isEmpty()) {
            cleanupPending = true;
        }
    }

    // ---- lease callbacks ----------------------------------------------------------------------------------------

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        // Checkpoint: the plan cursor stays; the current target is dropped and re-chosen on resume.
        lease = null;
        pausedByPreemption = reason != LeaseEndReason.STOP_ALL && reason != LeaseEndReason.TIMED_OUT;
        clearTarget();
        leaseRetry = 20;
    }

    @Override
    public void onResumable(ControlLease formerLease) {
        pausedByPreemption = false;
        leaseRetry = 0; // re-request on the next tick
    }

    // ---- tick -----------------------------------------------------------------------------------------------------

    public void tick(Minecraft mc) {
        tick++;
        LocalPlayer player = mc.player;
        if (plan == null || player == null || mc.level == null || mc.gameMode == null || mc.screen != null) {
            return;
        }
        if (Services.arbiter().isAutomationStopped()) {
            releaseLease();
            return;
        }
        if (pendingCount > 0) {
            drainBlockChanges(player);
        }
        long here = player.blockPosition().asLong();
        if (!blockedCells.isEmpty() && here != blockedFrom) {
            blockedCells.clear(); // the player moved: hidden faces may be visible now
        }
        if (walkToReach) {
            // The walker releases this module's lease before it starts a walk, and Build Assist stays hands-off
            // while the walk runs, so the two leases are never held together.
            walkPlayer = player;
            boolean walking = walker.tick(stalledLastTick && !pausedByPreemption, here, walkHost);
            walkPlayer = null;
            if (walking) {
                stalledLastTick = false;
                idleTicks = 0;
                return;
            }
        }
        double reach = player.blockInteractionRange();
        if (!nearSite(player, reach + 6)) {
            stalledLastTick = false;
            idleTicks = 0;
            releaseLease();
            clearTarget();
            return;
        }
        if (cooldown > 0) {
            cooldown--;
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        if (tick - windowStart >= windowTicks) {
            windowStart = tick;
            windowCount = 0;
        }
        if (windowCount >= maxPerWindow) {
            return;
        }
        if (layerCheckDue && targetStep < 0 && !cleanupTarget) {
            layerCheckDue = false;
            checkLayerSupply(player);
        }
        if (targetStep < 0 && !cleanupTarget && !selectTarget(player, reach)
                && !((firstOpen >= plan.size() || scaffoldInCells) && cleanupPending
                        && pickCleanupTarget(player, reach))) {
            stalledLastTick = firstOpen < plan.size(); // nothing to walk to once only scaffold cleanup is left
            if (++idleTicks > IDLE_RELEASE_TICKS) {
                releaseLease();
            }
            return;
        }
        stalledLastTick = false;
        idleTicks = 0;
        if (!ensureLease()) {
            return;
        }
        IInputSmoother input = Services.input();
        if (cleanupTarget) {
            if (ScaffoldLedger.underPlayer(cleanupPos, player.blockPosition().asLong())) {
                // The player stepped onto (or above) it while aiming: never break the block under them.
                input.setKeyHeld(lease, SimKey.ATTACK, false);
                input.clearRotation(lease);
                clearTarget();
                return;
            }
            tickBreak(mc, player, input);
            return;
        }
        byte kind = plan.kind(targetStep);
        if (kind == BuildPlan.REMOVE_SCAFFOLD) {
            tickBreak(mc, player, input);
            return;
        }
        // Still needed? (the user may have placed it by hand)
        if (!stillNeedsBlock(kind)) {
            clearTarget();
            return;
        }
        Item need = kind == BuildPlan.SCAFFOLD ? scaffoldItem : items[scan.volume().paletteIndex(targetCell)];
        if (need == null) {
            clearTarget();
            return;
        }
        if (!player.getMainHandItem().is(need)) {
            ensureInHand(player, input, need);
            return;
        }
        if (!aim(player, input)) {
            return;
        }
        place(mc, player, input);
    }

    // ---- target selection ---------------------------------------------------------------------------------------

    private boolean selectTarget(LocalPlayer player, double reach) {
        int size = plan.size();
        // Advance the "first open step" checkpoint past finished steps.
        int budget = SEARCH_PER_TICK;
        while (firstOpen < size && budget > 0 && isStepDone(firstOpen)) {
            firstOpen++;
            budget--;
        }
        if (firstOpen >= size || budget == 0) {
            return false; // finished, or keep advancing the checkpoint next tick
        }
        int y = scan.volume().yOf(plan.cell(firstOpen));
        if (y != layerY) { // the plan is bottom-up, one layer at a time: a new Y is a new layer
            layerY = y;
            layerCheckDue = autoSupply && restockHook != null && !restockQueued;
            if (layerCheckDue) {
                return false; // check the layer's materials first (next tick, outside the search)
            }
        }
        if (searchPos < firstOpen || searchPos >= Math.min(size, firstOpen + LOOKAHEAD)) {
            searchPos = firstOpen;
            startSweep();
        }
        int end = Math.min(size, firstOpen + LOOKAHEAD);
        Inventory inv = player.getInventory();
        for (int n = 0; n < SEARCH_PER_TICK && searchPos < end; n++, searchPos++) {
            int step = searchPos;
            byte kind = plan.kind(step);
            if (kind == BuildPlan.REMOVE_SCAFFOLD && step != firstOpen) {
                continue; // scaffolds come down only after everything before them is done
            }
            int cell = plan.cell(step);
            if (isStepDone(step)) { // also sets the target coordinates
                continue;
            }
            if (kind != BuildPlan.REMOVE_SCAFFOLD) {
                int key = itemKey(kind, cell);
                if (key < 0) {
                    continue; // no item for this block
                }
                if (!hasItem(inv, key)) {
                    sweepMissing = true;
                    continue;
                }
                if (!sweepUsable && scan.status(cell) != PlacementStatus.WRONG && supportMask() != 0) {
                    sweepUsable = true; // something is placeable with what is on hand (maybe after a walk)
                }
            } else {
                sweepUsable = true;
            }
            if (blockedCells.get(cell) || !withinReach(player, reach)) {
                continue;
            }
            if (kind != BuildPlan.REMOVE_SCAFFOLD) {
                if (scan.status(cell) == PlacementStatus.WRONG || intersectsPlayer(player)) {
                    continue;
                }
                int face = findSupportFace(player);
                if (face < 0) {
                    continue;
                }
                targetFace = face;
            }
            targetStep = step;
            targetCell = cell;
            aimTicks = 0;
            aimSet = false;
            searchPos++;
            return true;
        }
        if (searchPos >= end) {
            endSweep(player);
            searchPos = firstOpen;
            startSweep();
        }
        return false;
    }

    // ---- material restock -----------------------------------------------------------------------------------------

    private void startSweep() {
        sweepMissing = false;
        sweepUsable = false;
    }

    /** End of a pass over the look-ahead window: out of materials when only steps without items were left. */
    private void endSweep(LocalPlayer player) {
        if (sweepUsable || !sweepMissing) {
            if (sweepUsable && !restockQueued) {
                restockNotice = null; // materials on hand again
            }
            return;
        }
        if (restockQueued || tick < restockRetryAt) {
            return;
        }
        restockRetryAt = tick + RESTOCK_RETRY_TICKS;
        Map<String, Integer> wanted = collectRestock(player.getInventory());
        if (wanted.isEmpty()) {
            return;
        }
        RestockHook hook = restockHook;
        boolean queued = hook != null && hook.requestRestock(wanted, player.blockPosition().asLong(),
                player.level().dimension().location().toString());
        if (queued) {
            restockQueued = true;
            restockNotice = "Out of materials: fetching " + wanted.size() + " item type(s) from storage";
        } else {
            restockNotice = "Out of " + String.join(", ", wanted.keySet()) + ": none in remembered storage nearby";
        }
    }

    /**
     * The next missing palette items (plan order, at most {@link RestockCollector#DEFAULT_CAPACITY}) with the count
     * the remaining steps need, capped to {@link #RESTOCK_STACKS_PER_ITEM} stacks. Runs once per restock request.
     */
    private Map<String, Integer> collectRestock(Inventory inv) {
        restock.reset();
        int end = (int) Math.min(plan.size(), (long) firstOpen + RESTOCK_SCAN_STEPS);
        for (int step = firstOpen; step < end; step++) {
            byte kind = plan.kind(step);
            if (kind == BuildPlan.REMOVE_SCAFFOLD) {
                continue;
            }
            int cell = plan.cell(step);
            int key = itemKey(kind, cell);
            if (key < 0 || hasItem(inv, key)) {
                continue;
            }
            if (kind == BuildPlan.PLACE) {
                byte s = scan.status(cell);
                if (!PlacementStatus.needsPlacing(s) || s == PlacementStatus.WRONG) {
                    continue;
                }
            } else {
                setTargetCoords(cell);
                int w = probe.probe(targetX, targetY, targetZ, -1);
                if (w != PlacementStatus.W_EMPTY && w != PlacementStatus.W_FLUID) {
                    continue;
                }
            }
            restock.add(key);
        }
        clearTarget();
        Map<String, Integer> out = new LinkedHashMap<>();
        for (int i = 0; i < restock.size(); i++) {
            Item item = itemOf(restock.key(i));
            int cap = Math.max(1, item.getDefaultMaxStackSize()) * RESTOCK_STACKS_PER_ITEM;
            out.merge(BuiltInRegistries.ITEM.getKey(item).toString(), Math.min(restock.count(i), cap), Integer::sum);
        }
        return out;
    }

    /**
     * Auto-supply between layers: counts what the steps of the layer at {@link #firstOpen} still need, compares it with
     * the inventory and, when something is short, asks the hook for a supply trip. Runs once per layer change.
     */
    private void checkLayerSupply(LocalPlayer player) {
        RestockHook hook = restockHook;
        if (hook == null || plan == null || firstOpen >= plan.size()) {
            return;
        }
        restock.reset();
        SchematicVolume v = scan.volume();
        int end = (int) Math.min(plan.size(), (long) firstOpen + RESTOCK_SCAN_STEPS);
        for (int step = firstOpen; step < end; step++) {
            int cell = plan.cell(step);
            if (v.yOf(cell) != layerY) {
                break; // bottom-up plan: the layer ends here
            }
            byte kind = plan.kind(step);
            if (kind != BuildPlan.PLACE) {
                continue; // scaffold is on hand or comes back when removed; removal needs no item
            }
            byte s = scan.status(cell);
            if (!PlacementStatus.needsPlacing(s) || s == PlacementStatus.WRONG) {
                continue;
            }
            int key = itemKey(kind, cell);
            if (key >= 0 && !restock.add(key)) {
                break; // more item types than one round carries; the rest is the next round's
            }
        }
        if (restock.size() == 0) {
            return;
        }
        Map<String, Integer> need = new LinkedHashMap<>();
        for (int i = 0; i < restock.size(); i++) {
            need.merge(BuiltInRegistries.ITEM.getKey(itemOf(restock.key(i))).toString(), restock.count(i),
                    Integer::sum);
        }
        Map<String, Integer> shortfall = SupplyTrip.shortfall(need, McInventoryView.snapshot(player));
        if (shortfall.isEmpty()) {
            return;
        }
        if (hook.requestSupply(shortfall, player.blockPosition().asLong(),
                player.level().dimension().location().toString())) {
            restockQueued = true;
            restockNotice = "Next layer short of " + shortfall.size() + " item type(s): supply trip queued";
        }
    }

    /**
     * Next recorded scaffold block to break: removal order (top-down, the player's own column last), skipping blocks
     * already gone (forgotten), blocks under the player and blocks out of reach. Sets {@link #cleanupTarget}.
     */
    private boolean pickCleanupTarget(LocalPlayer player, double reach) {
        if (ledger.isEmpty()) {
            cleanupPending = false;
            cleanupRequested = false;
            setCleanupNotice(0, 0);
            return false;
        }
        long feet = player.blockPosition().asLong();
        int n = ledger.size();
        cleanupOrder = ledger.removalOrder(feet, cleanupOrder);
        int under = 0, far = 0;
        boolean inCells = false;
        long inCellUnder = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long p = cleanupOrder[i];
            targetX = BlockPos.getX(p);
            targetY = BlockPos.getY(p);
            targetZ = BlockPos.getZ(p);
            int w = probe.probe(targetX, targetY, targetZ, -1);
            if (w == PlacementStatus.W_EMPTY || w == PlacementStatus.W_FLUID) {
                ledger.forget(p);
                continue;
            }
            boolean inCell = inSchematicCell(targetX, targetY, targetZ);
            inCells |= inCell;
            if (w == PlacementStatus.W_UNLOADED || !withinReach(player, reach)) {
                far++;
                continue;
            }
            if (ScaffoldLedger.underPlayer(p, feet)) {
                under++;
                if (inCell && inCellUnder == Long.MIN_VALUE) {
                    inCellUnder = p;
                }
                continue;
            }
            cleanupPos = p;
            cleanupTarget = true;
            aimSet = false;
            aimTicks = 0;
            breakTicks = 0;
            scaffoldInCells = inCells || scaffoldInCells;
            return true;
        }
        scaffoldInCells = inCells;
        if (inCellUnder != Long.MIN_VALUE && walkToReach && plan != null && firstOpen < plan.size()
                && stepOffScaffold(player, inCellUnder)) {
            return false; // walking off it; cleanup stays pending and takes it down from the new spot
        }
        // Nothing breakable from here: leave the rest (the card says where) and let the job complete.
        cleanupPending = false;
        cleanupRequested = false;
        setCleanupNotice(under, far);
        return false;
    }

    /** The position is a block cell of the schematic (not air/void in it). */
    private boolean inSchematicCell(int x, int y, int z) {
        SchematicVolume v = scan.volume();
        int lx = x - scan.originX(), ly = y - scan.originY(), lz = z - scan.originZ();
        return v.contains(lx, ly, lz) && v.isBlock(v.paletteIndex(lx, ly, lz));
    }

    /**
     * The player stands on the walk's own scaffold that sits in a build cell: walk to a spot off it that still reaches
     * it (playtest 14-fieldjobs: the dome waited at 24/33 on a dirt pillar top inside a dome cell).
     */
    private boolean stepOffScaffold(LocalPlayer player, long scaffold) {
        int n = spotFinder.find(probe, scan.volume(), scan.originX(), scan.originY(), scan.originZ(),
                BlockPos.getX(scaffold), BlockPos.getY(scaffold), BlockPos.getZ(scaffold),
                player.getX(), player.getY(), player.getZ(), player.blockInteractionRange(), stepOffSpots);
        clearTarget();
        return walker.stepOff(scaffold, stepOffSpots, n, walkHost);
    }

    private void setCleanupNotice(int under, int far) {
        if (under == cleanupLeftUnder && far == cleanupOutOfReach) {
            return; // builds a new string only when the counts change
        }
        cleanupLeftUnder = under;
        cleanupOutOfReach = far;
        if (under == 0 && far == 0) {
            cleanupNotice = null;
        } else {
            cleanupNotice = "Scaffolding left: " + under + " under you, " + far
                    + " out of reach (step off / move closer, then Remove scaffolding)";
        }
    }

    /** Cache key of the item a step needs: its palette index, {@code items.length} for scaffold, -1 for none. */
    private int itemKey(byte kind, int cell) {
        if (kind == BuildPlan.SCAFFOLD) {
            return scaffoldItem == null ? -1 : items.length;
        }
        int p = scan.volume().paletteIndex(cell);
        return p < 0 || p >= items.length || items[p] == null ? -1 : p;
    }

    private Item itemOf(int key) {
        return key == items.length ? scaffoldItem : items[key];
    }

    /** Whether the inventory holds the item; looked up at most once per item per tick (allocation-free). */
    private boolean hasItem(Inventory inv, int key) {
        if (haveStamp[key] != tick) {
            haveStamp[key] = tick;
            haveItem[key] = findItem(inv, itemOf(key), 0, 36) >= 0;
        }
        return haveItem[key];
    }

    private boolean isStepDone(int step) {
        int cell = plan.cell(step);
        setTargetCoords(cell);
        byte kind = plan.kind(step);
        if (kind == BuildPlan.PLACE) {
            scan.refresh(probe, cell);
            byte s = scan.status(cell);
            return PlacementStatus.isPlaced(s) || s == PlacementStatus.CLEAR || s == PlacementStatus.UNLOADED;
        }
        int w = probe.probe(targetX, targetY, targetZ, -1);
        if (kind == BuildPlan.SCAFFOLD) {
            // Done once something solid fills the cell (the scaffold, or anything else).
            return w == PlacementStatus.W_OTHER || w == PlacementStatus.W_UNLOADED;
        }
        return w == PlacementStatus.W_EMPTY || w == PlacementStatus.W_FLUID || w == PlacementStatus.W_UNLOADED;
    }

    private boolean stillNeedsBlock(byte kind) {
        if (kind == BuildPlan.SCAFFOLD) {
            int w = probe.probe(targetX, targetY, targetZ, -1);
            return w == PlacementStatus.W_EMPTY || w == PlacementStatus.W_FLUID;
        }
        scan.refresh(probe, targetCell);
        return PlacementStatus.needsPlacing(scan.status(targetCell)) && scan.status(targetCell) != PlacementStatus.WRONG;
    }

    private boolean setTargetCoords(int cell) {
        SchematicVolume v = scan.volume();
        targetX = scan.originX() + v.xOf(cell);
        targetY = scan.originY() + v.yOf(cell);
        targetZ = scan.originZ() + v.zOf(cell);
        return true;
    }

    /** Bit {@code d} set when the neighbour in direction {@code d} of the target is solid to click against. */
    private int supportMask() {
        int mask = 0;
        for (int d = 0; d < DIRS.length; d++) {
            Direction dir = DIRS[d];
            int w = probe.probe(targetX + dir.getStepX(), targetY + dir.getStepY(), targetZ + dir.getStepZ(), -1);
            if (w == PlacementStatus.W_OTHER) {
                mask |= 1 << d;
            }
        }
        return mask;
    }

    /**
     * Support face to click, as a direction index from the target, or -1: of the solid neighbours, the one whose face
     * lies closest to the current look direction, so the camera turns least. Line of sight is checked per face when
     * aiming ({@link #chooseVisibleAim}), in the same order.
     */
    private int findSupportFace(LocalPlayer p) {
        int mask = supportMask();
        if (mask == 0) {
            return -1;
        }
        rankFaces(p, mask);
        return faceRanker.best();
    }

    private int rankFaces(LocalPlayer p, int mask) {
        float yaw = p.getYRot(), pitch = p.getXRot();
        return faceRanker.rank(targetX, targetY, targetZ, p.getX(), p.getEyeY(), p.getZ(),
                SupportFaceRanker.lookX(yaw, pitch), SupportFaceRanker.lookY(pitch),
                SupportFaceRanker.lookZ(yaw, pitch), mask);
    }

    private boolean withinReach(LocalPlayer p, double reach) {
        double dx = targetX + 0.5 - p.getX(), dy = targetY + 0.5 - p.getEyeY(), dz = targetZ + 0.5 - p.getZ();
        double r = reach - 0.25;
        return dx * dx + dy * dy + dz * dz <= r * r;
    }

    private boolean intersectsPlayer(LocalPlayer p) {
        AABB box = p.getBoundingBox();
        return box.intersects(targetX, targetY, targetZ, targetX + 1, targetY + 1, targetZ + 1);
    }

    private boolean nearSite(LocalPlayer p, double margin) {
        SchematicVolume v = scan.volume();
        double cx = clamp(p.getX(), scan.originX(), scan.originX() + v.sizeX());
        double cy = clamp(p.getEyeY(), scan.originY(), scan.originY() + v.sizeY());
        double cz = clamp(p.getZ(), scan.originZ(), scan.originZ() + v.sizeZ());
        double dx = cx - p.getX(), dy = cy - p.getEyeY(), dz = cz - p.getZ();
        return dx * dx + dy * dy + dz * dz <= margin * margin;
    }

    private static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }

    // ---- lease, items, aiming, placing --------------------------------------------------------------------------

    private boolean ensureLease() {
        if (lease != null && lease.isActive()) {
            return true;
        }
        lease = null;
        if (pausedByPreemption && leaseRetry > 0) {
            leaseRetry--;
            return false;
        }
        if (leaseRetry > 0) {
            leaseRetry--;
            return false;
        }
        Optional<ControlLease> granted = Services.arbiter().request(leaseRequest);
        if (granted.isEmpty()) {
            leaseRetry = 20;
            return false;
        }
        lease = granted.get();
        pausedByPreemption = false;
        return true;
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            l.close();
        }
    }

    private void ensureInHand(LocalPlayer player, IInputSmoother input, Item need) {
        Inventory inv = player.getInventory();
        int hotbar = findItem(inv, need, 0, 9);
        if (hotbar >= 0) {
            input.selectHotbarSlot(lease, hotbar);
            waitTicks = 1;
            return;
        }
        int main = findItem(inv, need, 9, 36);
        IContainerClicker clicker = Services.clicker();
        if (main < 0) {
            clearTarget(); // ran out; the next search skips steps without items
            return;
        }
        if (clicker.pending() == 0) {
            // Player inventory menu: slots 9..35 are the main inventory; SWAP with hotbar index.
            clicker.queueClick(lease, main, swapSlot, IContainerClicker.Click.SWAP);
        }
        waitTicks = 2;
    }

    private static int findItem(Inventory inv, Item item, int from, int to) {
        for (int i = from; i < to; i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    /** Offsets across a face, centre first: a block placed earlier often covers the centre but not a corner. */
    private static final double[] FACE_OFFSETS_U = {0, -0.3, 0.3, 0, 0, -0.3, 0.3, -0.3, 0.3};
    private static final double[] FACE_OFFSETS_V = {0, 0, 0, -0.3, 0.3, -0.3, -0.3, 0.3, 0.3};
    private double aimX;
    private double aimY;
    private double aimZ;

    /**
     * Picks the support face and the point on it to aim at: the first sampled point (centre first) whose ray from
     * the eye hits that support on that face. Runs once per target (up to 6 faces x 9 rays), never per tick.
     *
     * @return false when no support face is visible from here
     */
    private boolean chooseVisibleAim(LocalPlayer player, double reach) {
        Level level = player.level();
        Vec3 eye = player.getEyePosition();
        double r2 = reach * reach;
        // Solid neighbours, closest to the look direction first: the first one with a clear line of sight wins.
        int faces = rankFaces(player, supportMask());
        for (int pass = 0; pass < faces; pass++) {
            int d = faceRanker.face(pass);
            Direction toSupport = DIRS[d];
            int sx = targetX + toSupport.getStepX(), sy = targetY + toSupport.getStepY();
            int sz = targetZ + toSupport.getStepZ();
            Direction clicked = toSupport.getOpposite();
            double cx = targetX + 0.5 + toSupport.getStepX() * 0.5;
            double cy = targetY + 0.5 + toSupport.getStepY() * 0.5;
            double cz = targetZ + 0.5 + toSupport.getStepZ() * 0.5;
            // the two axes spanning the face
            Direction.Axis axis = toSupport.getAxis();
            for (int i = 0; i < FACE_OFFSETS_U.length; i++) {
                double u = FACE_OFFSETS_U[i], v = FACE_OFFSETS_V[i];
                double px = cx + (axis == Direction.Axis.X ? 0 : u);
                double py = cy + (axis == Direction.Axis.Y ? 0 : axis == Direction.Axis.X ? u : v);
                double pz = cz + (axis == Direction.Axis.Z ? 0 : v);
                double ex = px - eye.x, ey = py - eye.y, ez = pz - eye.z;
                if (ex * ex + ey * ey + ez * ez > r2) {
                    continue;
                }
                // end just inside the support so the ray reaches the face
                Vec3 end = new Vec3(px + toSupport.getStepX() * 0.05, py + toSupport.getStepY() * 0.05,
                        pz + toSupport.getStepZ() * 0.05);
                BlockHitResult hit = level.clip(new net.minecraft.world.level.ClipContext(eye, end,
                        net.minecraft.world.level.ClipContext.Block.OUTLINE,
                        net.minecraft.world.level.ClipContext.Fluid.NONE, player));
                if (hit.getType() == HitResult.Type.BLOCK && hit.getDirection() == clicked
                        && hit.getBlockPos().getX() == sx && hit.getBlockPos().getY() == sy
                        && hit.getBlockPos().getZ() == sz) {
                    targetFace = d;
                    aimX = px;
                    aimY = py;
                    aimZ = pz;
                    return true;
                }
            }
        }
        return false;
    }

    /** Aim point: a visible point on the support face (or the target's centre when removing scaffold). */
    private boolean aim(LocalPlayer player, IInputSmoother input) {
        boolean removing = plan.kind(targetStep) == BuildPlan.REMOVE_SCAFFOLD;
        if (!aimSet) {
            if (removing || targetFace < 0) {
                aimX = targetX + 0.5;
                aimY = targetY + 0.5;
                aimZ = targetZ + 0.5;
            } else if (!chooseVisibleAim(player, player.blockInteractionRange())) {
                if (requireLineOfSight) {
                    // No face of this cell is visible from here: skip it until the player moves (the reach walker
                    // then finds a spot that sees it).
                    blockedCells.set(targetCell);
                    blockedFrom = player.blockPosition().asLong();
                    clearTarget();
                    return false;
                }
                Direction d = DIRS[targetFace]; // line of sight off: aim at the centre, place() goes direct
                aimX = targetX + 0.5 + d.getStepX() * 0.5;
                aimY = targetY + 0.5 + d.getStepY() * 0.5;
                aimZ = targetZ + 0.5 + d.getStepZ() * 0.5;
            }
        }
        double hx = aimX, hy = aimY, hz = aimZ;
        double dx = hx - player.getX(), dy = hy - player.getEyeY(), dz = hz - player.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        if (!aimSet) {
            input.setRotationTarget(lease, silent ? RotationTarget.silent(yaw, pitch)
                    : RotationTarget.visible(yaw, pitch));
            aimSet = true;
        }
        if (++aimTicks > AIM_TIMEOUT_TICKS) {
            blockedCells.set(targetCell);
            blockedFrom = player.blockPosition().asLong();
            input.clearRotation(lease);
            clearTarget();
            return false;
        }
        return input.isRotationSettled(2.0f);
    }

    private void place(Minecraft mc, LocalPlayer player, IInputSmoother input) {
        Direction toSupport = DIRS[targetFace];
        int nx = targetX + toSupport.getStepX(), ny = targetY + toSupport.getStepY(), nz = targetZ + toSupport.getStepZ();
        Direction clickedFace = toSupport.getOpposite();
        boolean placed = false;
        boolean direct = silent;
        if (!silent) {
            HitResult hr = mc.hitResult;
            if (hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
                    && bhr.getBlockPos().equals(cursorPos.set(nx, ny, nz)) && bhr.getDirection() == clickedFace) {
                placed = input.scheduleKeyTap(lease, SimKey.USE, 1, 2);
            } else if (!requireLineOfSight) {
                // Aimed, but something is in the way: place straight onto the face (servers only check reach).
                direct = true;
            } else if (aimTicks < AIM_TIMEOUT_TICKS) {
                return; // something is in the line of sight; wait for the crosshair (times out in aim())
            } else {
                // Timed out with the face still hidden from here: skip this cell until the player moves.
                blockedCells.set(targetCell);
                blockedFrom = player.blockPosition().asLong();
            }
        }
        if (direct) {
            // Hit result built from the target face (silent rotation, or a face hidden from the crosshair).
            // Direct game call, so re-check the lease here (it may have been revoked earlier this tick).
            if (lease == null || !lease.isActive()
                    || !lease.covers(com.utilitysuite.core.api.ControlChannel.WORLD_INTERACT)) {
                return;
            }
            Vec3 hit = new Vec3(targetX + 0.5 + toSupport.getStepX() * 0.5, targetY + 0.5 + toSupport.getStepY() * 0.5,
                    targetZ + 0.5 + toSupport.getStepZ() * 0.5);
            mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND,
                    new BlockHitResult(hit, clickedFace, new BlockPos(nx, ny, nz), false));
            player.swing(InteractionHand.MAIN_HAND);
            placed = true;
        }
        if (placed) {
            placements++;
            windowCount++;
            cooldown = input.nextGaussianTicks(meanDelayTicks, meanDelayTicks * 0.35, 1,
                    (int) Math.ceil(meanDelayTicks * 3));
        }
        // Hand the camera back between placements; the next target sets a fresh rotation.
        input.clearRotation(lease);
        clearTarget();
    }

    /**
     * Removes a scaffold block. Breaking needs the real crosshair (vanilla stops any dig the attack key does not
     * hold), so removal always uses a VISIBLE rotation and holds ATTACK while the crosshair is on the block.
     */
    private void tickBreak(Minecraft mc, LocalPlayer player, IInputSmoother input) {
        int w = probe.probe(targetX, targetY, targetZ, -1);
        if (w == PlacementStatus.W_EMPTY || w == PlacementStatus.W_FLUID || ++breakTicks > BREAK_TIMEOUT_TICKS) {
            if (cleanupTarget) {
                ledger.forget(cleanupPos); // removed, or gave up on it (timed out): never retried in a loop
            }
            input.setKeyHeld(lease, SimKey.ATTACK, false);
            input.clearRotation(lease);
            clearTarget();
            return;
        }
        if (!aimSet) {
            double dx = targetX + 0.5 - player.getX(), dy = targetY + 0.5 - player.getEyeY();
            double dz = targetZ + 0.5 - player.getZ();
            float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
            float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
            input.setRotationTarget(lease, RotationTarget.visible(yaw, pitch));
            aimSet = true;
        }
        if (!input.isRotationSettled(3.0f)) {
            return;
        }
        HitResult hr = mc.hitResult;
        boolean onTarget = hr instanceof BlockHitResult bhr && hr.getType() == HitResult.Type.BLOCK
                && bhr.getBlockPos().equals(cursorPos.set(targetX, targetY, targetZ));
        input.setKeyHeld(lease, SimKey.ATTACK, onTarget);
    }

    // ---- walk to reach blocks -----------------------------------------------------------------------------------

    /** World side of {@link ReachWalker}; only called from {@link #tick} (once per stall, not per idle tick). */
    private final class WalkHost implements ReachWalker.Host {

        @Override
        public boolean navigatorAvailable() {
            INavigator nav = Services.navigatorOrNull();
            return nav != null && nav.isAvailable();
        }

        @Override
        public int nearestStalledCell(BitSet skip) {
            LocalPlayer p = walkPlayer;
            if (p == null || plan == null) {
                return -1;
            }
            Inventory inv = p.getInventory();
            int end = Math.min(plan.size(), firstOpen + LOOKAHEAD);
            int best = -1;
            double bestD = Double.MAX_VALUE;
            for (int step = firstOpen; step < end; step++) {
                byte kind = plan.kind(step);
                int cell = plan.cell(step);
                if (kind == BuildPlan.REMOVE_SCAFFOLD || skip.get(cell) || isStepDone(step)) {
                    continue; // isStepDone also sets the target coordinates
                }
                if (scan.status(cell) == PlacementStatus.WRONG || supportMask() == 0) {
                    continue;
                }
                Item need = kind == BuildPlan.SCAFFOLD ? scaffoldItem : items[scan.volume().paletteIndex(cell)];
                if (need == null || findItem(inv, need, 0, 36) < 0) {
                    continue;
                }
                double dx = targetX + 0.5 - p.getX(), dy = targetY + 0.5 - p.getEyeY(), dz = targetZ + 0.5 - p.getZ();
                double d = dx * dx + dy * dy + dz * dz;
                if (d < bestD) {
                    bestD = d;
                    best = cell;
                }
            }
            clearTarget();
            return best;
        }

        @Override
        public int standingSpots(int cell, long[] out) {
            LocalPlayer p = walkPlayer;
            if (p == null || scan == null) {
                return 0;
            }
            setTargetCoords(cell);
            int n = spotFinder.find(probe, scan.volume(), scan.originX(), scan.originY(), scan.originZ(),
                    targetX, targetY, targetZ, p.getX(), p.getY(), p.getZ(), p.blockInteractionRange(), out);
            clearTarget();
            return n;
        }

        @Override
        public void releaseOwnLease() {
            releaseLease();
            clearTarget();
        }

        @Override
        public Optional<NavigationRun> startWalk(int x, int y, int z) {
            INavigator nav = Services.navigatorOrNull();
            if (nav == null || !nav.isAvailable()) {
                return Optional.empty();
            }
            return nav.goToLeased(walkOwnerId, ControlPriority.GOAL, x, y, z, 0);
        }
    }

    private void clearTarget() {
        cleanupTarget = false;
        targetStep = -1;
        targetFace = -1;
        aimTicks = 0;
        breakTicks = 0;
        aimSet = false;
    }
}
