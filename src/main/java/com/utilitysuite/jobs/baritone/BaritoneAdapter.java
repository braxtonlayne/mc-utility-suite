package com.utilitysuite.jobs.baritone;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.jobs.goal.MiningDriver;
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
 * Protected areas: Baritone 1.11.3 only offers block-type avoidance lists ({@code blocksToAvoidBreaking}), not
 * positional ones, so every 10 ticks the adapter inspects the planned path and cancels (status FAILED) when it would
 * break a non-air block inside a protected box; the executor then replans.
 */
public final class BaritoneAdapter implements MiningDriver {

    public static final String BARITONE_MOD_ID = "baritoe";
    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/baritone");
    private static final int GRACE_TICKS = 6;

    private enum Mode { NONE, MINE, GOTO, FOLLOW, INTERACT }

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
    private int pathIndex;
    private ControlLease interactLease;
    private InteractPhase interactPhase = InteractPhase.PATH;
    private long interactStart;

    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private ResourceKey<Level> dimKey;
    private String dimId = "";
    private final LongPredicate protectedSolid = this::isProtectedSolid;

    /** @param isProtected (dimension, packed pos) -> inside a PROTECTED_NO_MINE box */
    public BaritoneAdapter(PositionPredicate isProtected) {
        this.isProtected = isProtected;
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
        backend.cancel();
        mode = m;
        status = Status.RUNNING;
        failure = "";
        paused = false;
        issuedAt = ticks;
        return true;
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
        if (backend() == null || !paused) {
            return;
        }
        paused = false;
        issuedAt = ticks;
        status = Status.RUNNING;
        switch (mode) {
            case MINE -> backend.mine(mineBlocks);
            case GOTO -> backend.goTo(gx, gy, gz, range);
            case FOLLOW -> backend.goTo(gx, gy, gz, 1);
            case INTERACT -> {
                interactPhase = InteractPhase.PATH;
                backend.goToBlock(gx, gy, gz);
            }
            default -> status = Status.IDLE;
        }
    }

    @Override
    public void cancel() {
        if (backend() != null) {
            backend.cancel();
        }
        mode = Mode.NONE;
        paused = false;
        status = backend == null ? Status.UNAVAILABLE : Status.IDLE;
        interactLease = null;
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
            BaritoneAdapter.this.cancel();
        }
    };

    /** The same adapter as the shared {@link INavigator} (registered with {@code Services.registerNavigator}). */
    public INavigator navigator() {
        return navigatorView;
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    /** Called once per client tick (post) by the jobs bootstrap. Cheap when idle; no allocation. */
    public void tick() {
        ticks++;
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
