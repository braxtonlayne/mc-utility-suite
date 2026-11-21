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

import java.util.BitSet;
import java.util.EnumSet;
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
        }
        stalledLastTick = false;
        clearTarget();
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
        clearTarget();
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
        if (targetStep < 0 && !selectTarget(player, reach)) {
            stalledLastTick = true;
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
        if (searchPos < firstOpen || searchPos >= Math.min(size, firstOpen + LOOKAHEAD)) {
            searchPos = firstOpen;
        }
        int end = Math.min(size, firstOpen + LOOKAHEAD);
        for (int n = 0; n < SEARCH_PER_TICK && searchPos < end; n++, searchPos++) {
            int step = searchPos;
            byte kind = plan.kind(step);
            if (kind == BuildPlan.REMOVE_SCAFFOLD && step != firstOpen) {
                continue; // scaffolds come down only after everything before them is done
            }
            if (blockedCells.get(plan.cell(step)) || isStepDone(step)) {
                continue;
            }
            int cell = plan.cell(step);
            if (!setTargetCoords(cell) || !withinReach(player, reach)) {
                continue;
            }
            if (kind != BuildPlan.REMOVE_SCAFFOLD) {
                if (scan.status(cell) == PlacementStatus.WRONG || intersectsPlayer(player)) {
                    continue;
                }
                int face = findSupportFace();
                if (face < 0) {
                    continue;
                }
                Item need = kind == BuildPlan.SCAFFOLD ? scaffoldItem
                        : items[scan.volume().paletteIndex(cell)];
                if (need == null || findItem(player.getInventory(), need, 0, 36) < 0) {
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
            searchPos = firstOpen;
        }
        return false;
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

    /** First neighbor with a solid block to click against, as a direction index from the target, or -1. */
    private int findSupportFace() {
        for (int d = 0; d < DIRS.length; d++) {
            Direction dir = DIRS[d];
            int w = probe.probe(targetX + dir.getStepX(), targetY + dir.getStepY(), targetZ + dir.getStepZ(), -1);
            if (w == PlacementStatus.W_OTHER) {
                return d;
            }
        }
        return -1;
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
        for (int pass = 0; pass < DIRS.length; pass++) {
            // try the planned face first, then every other solid neighbour
            int d = pass == 0 ? targetFace : (pass <= targetFace ? pass - 1 : pass);
            if (d < 0) {
                continue;
            }
            Direction toSupport = DIRS[d];
            int sx = targetX + toSupport.getStepX(), sy = targetY + toSupport.getStepY();
            int sz = targetZ + toSupport.getStepZ();
            if (probe.probe(sx, sy, sz, -1) != PlacementStatus.W_OTHER) {
                continue;
            }
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
                if (scan.status(cell) == PlacementStatus.WRONG || findSupportFace() < 0) {
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
        targetStep = -1;
        targetFace = -1;
        aimTicks = 0;
        breakTicks = 0;
        aimSet = false;
    }
}
