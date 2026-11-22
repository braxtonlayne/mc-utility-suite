package com.utilitysuite.jobs.baritone;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeasedNavigation;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IRoutePlanner;
import com.utilitysuite.core.api.IRoutePlanner.PlanState;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.jobs.goal.MiningDriver;
import com.utilitysuite.jobs.goal.MiningStyle;
import com.utilitysuite.jobs.planner.PackedPos;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import com.utilitysuite.jobs.planner.PositionPredicate;
import java.util.function.LongPredicate;

/**
 * Typed Baritone operations for the suite: {@code mineByYield}, {@code gotoCoord}, {@code followPath},
 * {@code interactBlock}, {@code pause}, {@code cancel}. Implements both the goal engine's {@link MiningDriver} and the
 * shared {@link INavigator}. Every entry point is guarded by {@code ModList.get().isLoaded("baritoe")}; Baritone classes
 * are only loaded through {@link BaritoneBridge#create()} after that check, and no Baritone type leaves this package.
 * <p>
 * Baritone drives the player's input itself, so callers must hold a GOAL (or TRAVEL) lease on MOVEMENT, CAMERA and
 * WORLD_INTERACT while a command runs and call {@link #pause()} when preempted. {@link #pause()} cancels Baritone and
 * remembers the command; {@link #resume()} re-issues it.
 * <p>
 * Navigator runs started through {@link INavigator#goToLeased} / {@link INavigator#followPathLeased} (waypoints,
 * schematic walk, travel autopilot) hold a {@link LeasedNavigation} lease on MOVEMENT, CAMERA and WORLD_INTERACT;
 * the adapter tracks the current run and releases its lease when the command ends or is superseded, and the run
 * cancels Baritone when its lease is preempted, overridden by the user or revoked. Independently of leases, Stop-All
 * ({@link #cancelAll()}, wired by the jobs bootstrap) cancels every Baritone process unconditionally.
 * <p>
 * Protected areas: Baritone 1.11.3 only offers block-type avoidance lists ({@code blocksToAvoidBreaking}), not
 * positional ones, so every 10 ticks the adapter inspects the planned path and cancels (status FAILED) when it would
 * break a non-air block inside a protected box; the executor then replans.
 */
public final class BaritoneAdapter implements MiningDriver {

    public static final String BARITONE_MOD_ID = "baritoe";
    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/baritone");
    private static final int GRACE_TICKS = 6;

    private enum Mode { NONE, MINE, GOTO, FOLLOW, INTERACT, CLEAR }

    private enum InteractPhase { PATH, AIM, USED }

    private PathBackend backend;
    private boolean checked;
    private final PositionPredicate isProtected;

    private Mode mode = Mode.NONE;
    private Status status = Status.IDLE;
    private String failure = "";
    private boolean paused;
    private long ticks;
    private long issuedAt;

    private List<String> mineBlocks = List.of();
    private int gx;
    private int gy;
    private int gz;
    private int range;
    private long[] path = new long[0];
    /** CLEAR: the far corner (the near one is the goal fields). */
    private long clearB;
    private long savedClearB;
    private int pathIndex;
    private ControlLease interactLease;
    private InteractPhase interactPhase = InteractPhase.PATH;
    private long interactStart;
    /** Leased navigator run whose command is currently executing, or null. */
    private LeasedNavigation currentRun;
    /** True while a leased navigator run issues its command (see {@link #start}). */
    private boolean startingRun;
    // Goal-engine command that was paused when a leased navigator run replaced it; re-issued by resume().
    private Mode savedMode = Mode.NONE;
    private List<String> savedMineBlocks = List.of();
    private long savedGoal;
    private int savedRange;
    private long[] savedPath = new long[0];
    private int savedPathIndex;

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private ResourceKey<Level> dimKey;
    private String dimId = "";
    private final LongPredicate protectedSolid = this::isProtectedSolid;

    /** @param isProtected (dimension, packed pos) -> inside a PROTECTED_NO_MINE box */
    public BaritoneAdapter(PositionPredicate isProtected) {
        this.isProtected = isProtected;
    }

    /** Test seam: an adapter over the given backend (no Baritone or ModList lookup). */
    BaritoneAdapter(PositionPredicate isProtected, PathBackend backend) {
        this.isProtected = isProtected;
        this.backend = backend;
        this.checked = true;
    }

    /** Wires Stop-All: every {@link SuiteEvent.StopAll} cancels every Baritone process ({@link #cancelAll()}). */
    public void subscribeStopAll(ISuiteEventBus events) {
        if (events != null) {
            events.subscribe(SuiteEvent.StopAll.class, e -> cancelAll());
        }
    }

    private PathBackend backend() {
        if (!checked) {
            checked = true;
            if (ModList.get() != null && ModList.get().isLoaded(BARITONE_MOD_ID)) {
                try {
                    backend = BaritoneBridge.create();
                    backend.configure();
                } catch (LinkageError | RuntimeException e) {
                    LOG.warn("Baritone is installed but its API could not be loaded: {}", e.toString());
                    backend = null;
                }
            }
        }
        return backend;
    }

    @Override
    public boolean isAvailable() {
        return backend() != null;
    }

    // ---- commands ---------------------------------------------------------------------------------------------------

    @Override
    public void mineByYield(String itemId, int desiredItemCount, List<String> candidateBlocks) {
        if (!start(Mode.MINE)) {
            return;
        }
        mineBlocks = List.copyOf(candidateBlocks);
        backend.mine(mineBlocks);
    }

    @Override
    public void setMiningStyle(MiningStyle style, List<String> candidateBlocks) {
        PathBackend b = backend();
        if (b == null) {
            return;
        }
        boolean legit = style == MiningStyle.LIKE_A_PLAYER;
        int level = MiningStyle.STAY;
        if (legit) {
            var p = Minecraft.getInstance().player;
            int feet = p == null ? 64 : p.blockPosition().getY();
            level = MiningStyle.stripLevel(candidateBlocks, feet);
            if (level == MiningStyle.STAY) {
                level = feet;
            }
        }
        b.setLegitMining(legit, level);
    }

    @Override
    public void clearArea(long cornerA, long cornerB) {
        if (!start(Mode.CLEAR)) {
            return;
        }
        setGoal(cornerA, 0);
        clearB = cornerB;
        backend.clearArea(gx, gy, gz, PackedPos.x(cornerB), PackedPos.y(cornerB), PackedPos.z(cornerB));
    }

    @Override
    public void gotoCoord(long packedPos, int range) {
        if (!start(Mode.GOTO)) {
            return;
        }
        setGoal(packedPos, range);
        backend.goTo(gx, gy, gz, range);
    }

    @Override
    public void followPath(long[] packedPositions) {
        if (packedPositions.length == 0 || !start(Mode.FOLLOW)) {
            return;
        }
        path = packedPositions.clone();
        pathIndex = 0;
        setGoal(path[0], 1);
        backend.goTo(gx, gy, gz, 1);
    }

    @Override
    public void interactBlock(long packedPos, ControlLease lease) {
        if (!start(Mode.INTERACT)) {
            return;
        }
        setGoal(packedPos, 0);
        interactLease = lease;
        interactPhase = InteractPhase.PATH;
        backend.goToBlock(gx, gy, gz);
    }

    private boolean start(Mode m) {
        if (backend() == null) {
            status = Status.UNAVAILABLE;
            failure = "Baritone is not installed";
            return false;
        }
        endCurrentRun();
        backend.cancel();
        backend.cancelPlan(); // a job replaces any plan-only request
        if (startingRun) {
            // A leased navigator run preempted the goal engine: keep its paused command so resume() can re-issue it.
            if (paused && mode != Mode.NONE && mode != Mode.INTERACT) {
                saveSuspended();
            }
        } else {
            savedMode = Mode.NONE;
        }
        mode = m;
        status = Status.RUNNING;
        failure = "";
        paused = false;
        issuedAt = ticks;
        return true;
    }

    private void saveSuspended() {
        savedMode = mode;
        savedMineBlocks = mineBlocks;
        savedGoal = PackedPos.pack(gx, gy, gz);
        savedRange = range;
        savedPath = path;
        savedPathIndex = pathIndex;
        savedClearB = clearB;
    }

    private void restoreSuspended() {
        mode = savedMode;
        mineBlocks = savedMineBlocks;
        setGoal(savedGoal, savedRange);
        path = savedPath;
        pathIndex = savedPathIndex;
        clearB = savedClearB;
        savedMode = Mode.NONE;
        paused = true;
    }

    private void setGoal(long packed, int r) {
        gx = PackedPos.x(packed);
        gy = PackedPos.y(packed);
        gz = PackedPos.z(packed);
        range = r;
    }

    @Override
    public void pause() {
        if (backend() == null || mode == Mode.NONE || paused) {
            return;
        }
        paused = true;
        backend.cancel();
    }

    @Override
    public void resume() {
        if (backend() == null) {
            return;
        }
        if (!paused) {
            if (savedMode == Mode.NONE) {
                return;
            }
            // The goal engine resumes after a leased navigator run replaced its paused command.
            endCurrentRun();
            backend.cancel();
            restoreSuspended();
        }
        paused = false;
        issuedAt = ticks;
        status = Status.RUNNING;
        switch (mode) {
            case MINE -> backend.mine(mineBlocks);
            case GOTO -> backend.goTo(gx, gy, gz, range);
            case FOLLOW -> backend.goTo(gx, gy, gz, 1);
            case CLEAR -> backend.clearArea(gx, gy, gz, PackedPos.x(clearB), PackedPos.y(clearB), PackedPos.z(clearB));
            case INTERACT -> {
                interactPhase = InteractPhase.PATH;
                backend.goToBlock(gx, gy, gz);
            }
            default -> status = Status.IDLE;
        }
    }

    @Override
    public void cancel() {
        savedMode = Mode.NONE;
        cancelCommand();
    }

    /** Cancels the current command and run; keeps a goal command suspended by a navigator run (see resume()). */
    private void cancelCommand() {
        endCurrentRun();
        if (backend() != null) {
            backend.cancel();
        }
        mode = Mode.NONE;
        paused = false;
        status = backend == null ? Status.UNAVAILABLE : Status.IDLE;
        interactLease = null;
    }

    /**
     * Stop-All: cancels every Baritone process (Baritone's cancel-everything, including processes the suite did not
     * start) and ends the current navigator run, whatever state the adapter believes it is in.
     */
    public void cancelAll() {
        cancel();
        if (backend != null) {
            backend.cancelPlan();
        }
    }

    private void endCurrentRun() {
        LeasedNavigation run = currentRun;
        if (run != null) {
            currentRun = null;
            run.detach();
        }
    }

    private Optional<NavigationRun> beginRun(String moduleId, ControlPriority priority, Runnable command) {
        Optional<LeasedNavigation> run;
        startingRun = true;
        try {
            run = LeasedNavigation.begin(Services.arbiter(), navigatorView, moduleId, priority, command);
        } finally {
            startingRun = false;
        }
        if (run.isPresent() && !run.get().isEnded()) {
            currentRun = run.get();
        }
        return run.map(r -> r);
    }

    @Override
    public Status status() {
        return backend() == null ? Status.UNAVAILABLE : status;
    }

    @Override
    public String failureReason() {
        return failure;
    }

    // ---- INavigator view -------------------------------------------------------------------------------------------------

    private final INavigator navigatorView = new INavigator() {
        @Override
        public boolean isAvailable() {
            return BaritoneAdapter.this.isAvailable();
        }

        @Override
        public void goTo(int x, int y, int z, int range) {
            gotoCoord(PackedPos.pack(x, y, z), range);
        }

        @Override
        public void followPath(long[] packedPositions) {
            BaritoneAdapter.this.followPath(packedPositions);
        }

        @Override
        public NavigationStatus status() {
            return switch (BaritoneAdapter.this.status()) {
                case IDLE -> NavigationStatus.IDLE;
                case RUNNING -> NavigationStatus.PATHING;
                case ARRIVED -> NavigationStatus.ARRIVED;
                case FAILED -> NavigationStatus.FAILED;
                case UNAVAILABLE -> NavigationStatus.UNAVAILABLE;
            };
        }

        @Override
        public void pause() {
            BaritoneAdapter.this.pause();
        }

        @Override
        public void resume() {
            BaritoneAdapter.this.resume();
        }

        @Override
        public void cancel() {
            cancelCommand();
        }

        @Override
        public Optional<NavigationRun> goToLeased(String moduleId, ControlPriority priority, int x, int y, int z,
                                                  int range) {
            return beginRun(moduleId, priority, () -> gotoCoord(PackedPos.pack(x, y, z), range));
        }

        @Override
        public Optional<NavigationRun> followPathLeased(String moduleId, ControlPriority priority,
                                                        long[] packedPositions) {
            if (packedPositions == null || packedPositions.length == 0) {
                return Optional.empty();
            }
            return beginRun(moduleId, priority, () -> BaritoneAdapter.this.followPath(packedPositions));
        }

        @Override
        public void cancelAll() {
            BaritoneAdapter.this.cancelAll();
        }
    };

    /** The same adapter as the shared {@link INavigator} (registered with {@code Services.registerNavigator}). */
    public INavigator navigator() {
        return navigatorView;
    }

    // ---- IRoutePlanner view (plan-only: Baritone computes, the suite drives) ----------------------------------------

    /**
     * True when the backend exists and nothing executes a path: no adapter command (running or paused), no mining,
     * building or custom-goal process, and no executing path except the momentary one a plan's own calculation may
     * leave for a tick before the plan drops it.
     */
    private boolean planAvailable() {
        PathBackend b = backend();
        if (b == null || mode != Mode.NONE) {
            return false;
        }
        if (b.isMining() || b.isGoalActive() || b.isBuilding()) {
            return false;
        }
        return !b.isPathing() || b.planState() == PlanState.CALCULATING;
    }

    private final IRoutePlanner routePlannerView = new IRoutePlanner() {
        @Override
        public boolean isAvailable() {
            return planAvailable();
        }

        @Override
        public boolean planTo(int x, int y, int z, int range) {
            if (!planAvailable()) {
                return false;
            }
            backend.planTo(x, y, z, Math.max(0, range));
            return true;
        }

        @Override
        public boolean planAway(int x, int y, int z, int distance) {
            if (!planAvailable()) {
                return false;
            }
            backend.planAway(x, y, z, Math.max(1, distance));
            return true;
        }

        @Override
        public PlanState state() {
            PathBackend b = backend();
            return b == null ? PlanState.NONE : b.planState();
        }

        @Override
        public int planSerial() {
            PathBackend b = backend();
            return b == null ? 0 : b.planSerial();
        }

        @Override
        public int copyPath(long[] out) {
            PathBackend b = backend();
            if (b == null || out == null || out.length == 0 || b.planState() != PlanState.READY) {
                return 0;
            }
            return b.copyPlannedPath(out);
        }

        @Override
        public void cancelPlan() {
            BaritoneAdapter.this.cancelPlan();
        }
    };

    /** The plan-only route planner (registered with {@code Services.registerRoutePlanner} when Baritone is loaded). */
    public IRoutePlanner routePlanner() {
        return routePlannerView;
    }

    /**
     * Drops the plan request and route only; a path executing for a job is never touched. Also called by Stop-All and
     * by the jobs bootstrap on dimension change and world unload.
     */
    public void cancelPlan() {
        PathBackend b = backend;
        if (checked && b != null) {
            b.cancelPlan();
        }
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    /** Ticks the look guard stays on after Baritone goes idle (it may still turn while finishing a move). */
    static final int GUARD_LINGER_TICKS = 10;
    private long lastBusyTick = Long.MIN_VALUE / 2;
    private boolean guardOn;

    /**
     * Baritone turns the player itself (straight to the block it mines or the next node): while it works, the core
     * turns those into capped, smoothed turns (IInputSmoother#setForeignRotationGuard). No head snapping.
     */
    private void guardLookTurns() {
        boolean busy = checked && backend != null
                && (backend.isPathing() || backend.isMining() || backend.isGoalActive() || backend.isBuilding()
                || (mode != Mode.NONE && status == Status.RUNNING));
        if (busy) {
            lastBusyTick = ticks;
        }
        boolean want = ticks - lastBusyTick <= GUARD_LINGER_TICKS;
        if (want != guardOn) {
            guardOn = want;
            Services.input().setForeignRotationGuard(want);
        }
    }

    /** Called once per client tick (post) by the jobs bootstrap. Cheap when idle; no allocation. */
    public void tick() {
        ticks++;
        guardLookTurns();
        LeasedNavigation run = currentRun;
        if (run != null) {
            run.update();
            if (run.isEnded() && currentRun == run) {
                currentRun = null;
            }
        }
        if (!checked || backend == null || paused || mode == Mode.NONE || status != Status.RUNNING) {
            return;
        }
        boolean graceOver = ticks - issuedAt > GRACE_TICKS;
        if ((ticks % 10) == 0 && updateDimension() && backend.isPathing()) {
            long hit = backend.firstPathPos(protectedSolid);
            if (hit != Long.MIN_VALUE) {
                backend.cancel();
                fail("Route crosses protected area at " + PackedPos.format(hit));
                return;
            }
        }
        switch (mode) {
            case MINE -> {
                if (graceOver && !backend.isMining()) {
                    status = Status.IDLE;
                }
            }
            case GOTO -> tickGoal(graceOver);
            case CLEAR -> {
                if (graceOver && !backend.isBuilding()) {
                    status = Status.ARRIVED;
                }
            }
            case FOLLOW -> {
                if (graceOver && backend.isInGoal()) {
                    if (++pathIndex >= path.length) {
                        status = Status.ARRIVED;
                    } else {
                        setGoal(path[pathIndex], 1);
                        issuedAt = ticks;
                        backend.goTo(gx, gy, gz, 1);
                    }
                } else if (graceOver && !backend.isGoalActive() && !backend.isPathing()) {
                    fail("Baritone stopped before reaching breadcrumb " + (pathIndex + 1) + "/" + path.length);
                }
            }
            case INTERACT -> tickInteract(graceOver);
            default -> {
            }
        }
    }

    private void tickGoal(boolean graceOver) {
        if (!graceOver) {
            return;
        }
        if (backend.isInGoal()) {
            status = Status.ARRIVED;
        } else if (!backend.isGoalActive() && !backend.isPathing()) {
            fail("Baritone stopped before reaching " + gx + " " + gy + " " + gz);
        }
    }

    private void tickInteract(boolean graceOver) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        IInputSmoother input = Services.input();
        switch (interactPhase) {
            case PATH -> {
                if (!graceOver) {
                    return;
                }
                if (backend.isInGoal()) {
                    backend.cancel();
                    interactPhase = InteractPhase.AIM;
                    interactStart = ticks;
                    Vec3 eye = mc.player.getEyePosition();
                    double dx = gx + 0.5 - eye.x;
                    double dy = gy + 0.5 - eye.y;
                    double dz = gz + 0.5 - eye.z;
                    float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
                    float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
                    if (interactLease == null || !input.setRotationTarget(interactLease,
                            RotationTarget.visible(yaw, pitch))) {
                        fail("No camera control to interact with " + gx + " " + gy + " " + gz);
                    }
                } else if (!backend.isGoalActive() && !backend.isPathing()) {
                    fail("Could not reach block " + gx + " " + gy + " " + gz);
                }
            }
            case AIM -> {
                if (input.isRotationSettled(2f) || ticks - interactStart > 30) {
                    if (!input.scheduleKeyTap(interactLease, SimKey.USE, 1, 2)) {
                        fail("No interact control for " + gx + " " + gy + " " + gz);
                        return;
                    }
                    interactPhase = InteractPhase.USED;
                    interactStart = ticks;
                }
            }
            case USED -> {
                if (ticks - interactStart > 3) {
                    input.clearRotation(interactLease);
                    status = Status.ARRIVED;
                }
            }
        }
    }

    private void fail(String why) {
        status = Status.FAILED;
        failure = why;
    }

    private boolean updateDimension() {
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        if (level.dimension() != dimKey) {
            dimKey = level.dimension();
            dimId = dimKey.location().toString();
        }
        return true;
    }

    private boolean isProtectedSolid(long packed) {
        if (!isProtected.test(dimId, packed)) {
            return false;
        }
        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return false;
        }
        probe.set(PackedPos.x(packed), PackedPos.y(packed), PackedPos.z(packed));
        BlockState s = level.getBlockState(probe);
        return !s.isAir() && !s.canBeReplaced();
    }
}
