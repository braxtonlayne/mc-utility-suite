package com.utilitysuite.travel.navigator;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.LeasedNavigation;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.travel.Packed;
import com.utilitysuite.travel.movement.LevelVoxels;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * Baritone-free {@link INavigator}, registered only when Baritone is absent: plans short legs with the bounded
 * {@link LocalPathPlanner} (budgeted per client tick) and walks them with the shared {@link IRouteRunner} under the
 * run's {@link ControlLease}. It walks, steps up one block, drops up to three and jumps one-block gaps; it never breaks
 * or places blocks.
 * <p>
 * {@link #goTo}: a target beyond the planner's box is reached leg by leg (each leg ends at the explored cell closest to
 * the target, then the next leg is planned from there). {@link #followPath}: breadcrumbs are chained point to point,
 * skipping crumbs within a few blocks and crumbs no walk reaches.
 * <p>
 * Only the leased entry points move the player ({@link #goToLeased} / {@link #followPathLeased}); the plain
 * {@link #goTo} / {@link #followPath} have no lease to drive input with and end FAILED at once. Client thread only;
 * the tick handler returns immediately while idle, and a running tick allocates nothing.
 */
public final class LocalNavigator implements INavigator {

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/navigator");

    /** Expansions per client tick (a 64-block search usually finishes in one to five ticks). */
    static final int EXPANSIONS_PER_TICK = 2_000;
    /** Breadcrumbs closer than this to the player are skipped (the leg would be a stutter-step). */
    static final int CRUMB_SKIP = 4;
    /** A partial leg must end at least this many blocks (squared) closer to the target to be worth walking. */
    static final int MIN_PROGRESS_SQ = 9;
    /** Consecutive failed legs (replans or skipped crumbs) before the run gives up. */
    static final int MAX_FAILURES = 3;
    /** A leg running longer than this is replanned from where the player stands. */
    static final int LEG_TIMEOUT_TICKS = 20 * 45;
    static final int MAX_CRUMBS = 4096;

    private enum Phase { IDLE, PLAN, WALK, ARRIVED, FAILED }

    private final LocalPathPlanner planner = new LocalPathPlanner();
    private final LevelVoxels voxels = new LevelVoxels();
    private final LevelHazards hazards = new LevelHazards();
    private final long[] route = new long[LocalPathPlanner.MAX_PATH];
    private final long[] crumbs = new long[MAX_CRUMBS];
    private int crumbCount;
    private int crumbIndex;
    private boolean followMode;

    private Phase phase = Phase.IDLE;
    private boolean planning;
    private int tx, ty, tz, range;
    private int legTicks;
    private int failures;
    private LeasedNavigation run;
    private ControlLease lease;

    // ---- INavigator ------------------------------------------------------------------------------------------------

    @Override
    public boolean isAvailable() {
        IRouteRunner r = Services.routeRunnerOrNull();
        return r != null && Minecraft.getInstance().player != null;
    }

    @Override
    public void goTo(int x, int y, int z, int range) {
        stopWalking();
        followMode = false;
        crumbCount = 0;
        tx = x;
        ty = y;
        tz = z;
        this.range = Math.max(0, range);
        startLeg();
    }

    @Override
    public void followPath(long[] packedPositions) {
        stopWalking();
        followMode = true;
        crumbCount = Math.min(packedPositions == null ? 0 : packedPositions.length, MAX_CRUMBS);
        if (crumbCount > 0) {
            System.arraycopy(packedPositions, 0, crumbs, 0, crumbCount);
        }
        crumbIndex = 0;
        if (crumbCount == 0) {
            phase = Phase.FAILED;
            return;
        }
        targetCrumb();
        startLeg();
    }

    @Override
    public NavigationStatus status() {
        return switch (phase) {
            case IDLE -> NavigationStatus.IDLE;
            case PLAN, WALK -> NavigationStatus.PATHING;
            case ARRIVED -> NavigationStatus.ARRIVED;
            case FAILED -> NavigationStatus.FAILED;
        };
    }

    @Override
    public void pause() {
        // a paused leg would hold no keys anyway: pausing ends the walk, resume() replans from where the player is
        if (phase == Phase.WALK || phase == Phase.PLAN) {
            stopRunner();
            planner.reset();
            planning = false;
            paused = true;
        }
    }

    private boolean paused;

    @Override
    public void resume() {
        if (paused) {
            paused = false;
            startLeg();
        }
    }

    @Override
    public void cancel() {
        stopWalking();
        phase = Phase.IDLE;
        LeasedNavigation r = run;
        run = null;
        lease = null;
        if (r != null) {
            r.detach(); // releases the lease; a no-op when the run itself is cancelling
        }
    }

    @Override
    public Optional<NavigationRun> goToLeased(String moduleId, ControlPriority priority, int x, int y, int z,
                                              int range) {
        return beginRun(moduleId, priority, () -> goTo(x, y, z, range));
    }

    @Override
    public Optional<NavigationRun> followPathLeased(String moduleId, ControlPriority priority,
                                                    long[] packedPositions) {
        if (packedPositions == null || packedPositions.length == 0) {
            return Optional.empty();
        }
        return beginRun(moduleId, priority, () -> followPath(packedPositions));
    }

    private Optional<NavigationRun> beginRun(String moduleId, ControlPriority priority, Runnable command) {
        cancel(); // a new run supersedes the old one (and its lease)
        Optional<LeasedNavigation> r = LeasedNavigation.begin(Services.arbiter(), this, moduleId, priority, command);
        if (r.isPresent() && !r.get().isEnded()) {
            run = r.get();
            lease = run.lease();
        } else if (phase == Phase.PLAN || phase == Phase.WALK) {
            stopWalking();
            phase = Phase.IDLE;
        }
        return r.map(x -> x);
    }

    // ---- tick ------------------------------------------------------------------------------------------------------

    /** Client tick listener (register once). Returns at once while idle. */
    public void onTick(ClientTickEvent.Post event) {
        if (phase != Phase.PLAN && phase != Phase.WALK) {
            return;
        }
        if (run == null || lease == null || !lease.isActive()) {
            // unleased command, or the lease ended without a callback: nothing may drive input
            fail("lost control");
            return;
        }
        if (paused) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        IRouteRunner runner = Services.routeRunnerOrNull();
        if (p == null || level == null || runner == null) {
            fail("no world");
            return;
        }
        if (phase == Phase.PLAN) {
            tickPlan(level, p, runner);
        } else {
            tickWalk(p, runner);
        }
    }

    /** Lets the run see the final status: it releases the lease once the navigator stopped pathing. */
    private void syncRun() {
        LeasedNavigation r = run;
        if (r != null) {
            r.update();
            if (r.isEnded()) {
                run = null;
                lease = null;
            }
        }
    }

    private void tickPlan(Level level, LocalPlayer p, IRouteRunner runner) {
        voxels.begin(level);
        hazards.begin(level);
        if (!planning) {
            long start = feetCell(p);
            planner.begin(voxels, hazards, start, Packed.pack(tx, ty, tz), followMode ? 1 : range,
                    LocalPathPlanner.DEFAULT_NODE_BUDGET);
            planning = true;
        } else {
            planner.rebind(voxels);
        }
        LocalPathPlanner.Result res = planner.step(EXPANSIONS_PER_TICK);
        if (res == LocalPathPlanner.Result.SEARCHING) {
            return;
        }
        planning = false;
        int n = planner.copyPath(route);
        boolean usable = res == LocalPathPlanner.Result.FOUND
                || res == LocalPathPlanner.Result.PARTIAL && n >= 2 && progressSq(route[0], route[n - 1]) >= MIN_PROGRESS_SQ;
        if (res == LocalPathPlanner.Result.FOUND && n < 2) {
            legArrived(); // already inside the range
            return;
        }
        if (!usable) {
            legFailed();
            return;
        }
        if (!runner.follow(lease, route, n, true)) {
            fail("route runner refused the route");
            return;
        }
        legTicks = 0;
        phase = Phase.WALK;
    }

    private void tickWalk(LocalPlayer p, IRouteRunner runner) {
        IRouteRunner.State st = runner.state();
        if (st == IRouteRunner.State.ARRIVED) {
            legArrived();
        } else if (st == IRouteRunner.State.FAILED || st == IRouteRunner.State.IDLE) {
            legFailed();
        } else if (++legTicks > LEG_TIMEOUT_TICKS) {
            stopRunner();
            phase = Phase.PLAN; // stuck: replan from here
            failures++;
            if (failures > MAX_FAILURES) {
                fail("stuck");
            }
        }
    }

    /** The leg ended where it was planned to: done, or plan the next leg. */
    private void legArrived() {
        failures = 0;
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            fail("no world");
            return;
        }
        long feet = feetCell(p);
        if (followMode) {
            if (crumbIndex >= crumbCount - 1 && withinRange(feet, tx, ty, tz, 1)) {
                arrive();
                return;
            }
            if (withinRange(feet, tx, ty, tz, 1)) {
                crumbIndex++;
            }
            targetCrumb();
            startLeg();
        } else if (withinRange(feet, tx, ty, tz, Math.max(range, 1))) {
            arrive();
        } else {
            startLeg(); // a partial leg toward a far target: plan the next one from here
        }
    }

    /** No route for this leg, or the walk left it. */
    private void legFailed() {
        stopRunner();
        failures++;
        if (followMode && crumbIndex < crumbCount - 1) {
            crumbIndex++; // that crumb is out of reach (pillared, broken bridge): aim past it
            targetCrumb();
        }
        if (failures > MAX_FAILURES) {
            fail("no walkable route");
            return;
        }
        startLeg();
    }

    /** Aims at the next breadcrumb that is at least {@link #CRUMB_SKIP} blocks from the player (or the last one). */
    private void targetCrumb() {
        LocalPlayer p = Minecraft.getInstance().player;
        long feet = p == null ? crumbs[crumbIndex] : feetCell(p);
        while (crumbIndex < crumbCount - 1 && withinRange(feet, Packed.x(crumbs[crumbIndex]),
                Packed.y(crumbs[crumbIndex]), Packed.z(crumbs[crumbIndex]), CRUMB_SKIP)) {
            crumbIndex++;
        }
        long c = crumbs[crumbIndex];
        tx = Packed.x(c);
        ty = Packed.y(c);
        tz = Packed.z(c);
    }

    private void startLeg() {
        planning = false;
        paused = false;
        phase = Phase.PLAN;
    }

    private void arrive() {
        stopRunner();
        planner.reset();
        planning = false;
        phase = Phase.ARRIVED;
        syncRun();
    }

    private void fail(String why) {
        stopRunner();
        planner.reset();
        planning = false;
        phase = Phase.FAILED;
        syncRun();
        LOG.debug("Local navigation failed: {}", why);
        notice(why);
    }

    private static void notice(String why) {
        var visuals = Services.visualsOrNull();
        if (visuals != null && !Services.modState().isCleanScreen()) {
            visuals.notices().post(com.utilitysuite.core.api.visual.NoticeKind.INFO, "Navigation stopped: " + why,
                    null);
        }
    }

    private void stopWalking() {
        stopRunner();
        planner.reset();
        planning = false;
        paused = false;
        failures = 0;
    }

    private void stopRunner() {
        IRouteRunner r = Services.routeRunnerOrNull();
        if (r != null && lease != null) {
            r.stop(lease);
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The player's feet cell; one up when the feet are inside a slab or path block (the planner uses full cubes). */
    private long feetCell(LocalPlayer p) {
        int x = net.minecraft.util.Mth.floor(p.getX());
        int y = net.minecraft.util.Mth.floor(p.getY() + 1.0E-3);
        int z = net.minecraft.util.Mth.floor(p.getZ());
        Level level = p.level();
        voxels.begin(level);
        if (voxels.solid(x, y, z) && !voxels.solid(x, y + 1, z)) {
            y++;
        }
        return Packed.pack(x, y, z);
    }

    private static boolean withinRange(long cell, int x, int y, int z, int r) {
        int dx = Packed.x(cell) - x;
        int dz = Packed.z(cell) - z;
        return dx * dx + dz * dz <= r * r && Math.abs(Packed.y(cell) - y) <= Math.max(1, r);
    }

    private long progressSq(long from, long to) {
        long d0 = distSq(from);
        long d1 = distSq(to);
        return d0 - d1;
    }

    private long distSq(long c) {
        long dx = Packed.x(c) - tx;
        long dz = Packed.z(c) - tz;
        return dx * dx + dz * dz;
    }

    /** Lava, fire and cactus are forbidden feet/floor cells; water and cobwebs cost extra. Client thread only. */
    static final class LevelHazards implements LocalPathPlanner.Hazards {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private Level level;

        void begin(Level l) {
            level = l;
        }

        @Override
        public int cost(int x, int y, int z) {
            if (level == null) {
                return 0;
            }
            int extra = 0;
            for (int dy = -1; dy <= 1; dy++) {
                pos.set(x, y + dy, z);
                BlockState st = level.getBlockState(pos);
                if (st.isAir()) {
                    continue;
                }
                if (st.getFluidState().is(FluidTags.LAVA) || st.is(Blocks.FIRE) || st.is(Blocks.SOUL_FIRE)
                        || st.is(Blocks.CACTUS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.POWDER_SNOW)
                        || st.is(Blocks.CAMPFIRE) || st.is(Blocks.SOUL_CAMPFIRE)
                        || dy == -1 && st.is(Blocks.MAGMA_BLOCK)) {
                    return FORBIDDEN;
                }
                if (dy >= 0 && st.getFluidState().is(FluidTags.WATER)) {
                    extra += 20;
                } else if (dy >= 0 && st.is(Blocks.COBWEB)) {
                    extra += 40;
                }
            }
            return extra;
        }
    }
}
