package com.utilitysuite.tools.rules;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.CostProfile;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.IWorldKnowledgeService;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.tools.inventory.SmartInventory;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The "deposit at a named waypoint" rule action: pause the running job, travel there through the goal engine (a
 * {@code GoalRequest.Travel}), turn to the container at the waypoint and open it (GOAL lease on CAMERA +
 * WORLD_INTERACT), let SmartInventory dump everything except the hotbar, close the container and optionally resume
 * the paused job. Any manual input or Stop-All aborts the run (the user always wins); the paused job stays paused.
 */
final class DepositRun implements LeaseListener {

    enum State { IDLE, TRAVEL, OPENING, DEPOSITING }

    private static final int SEARCH_RADIUS = 3;
    private static final double REACH = 4.5;
    private static final int OPEN_TIMEOUT = 60;

    private final Supplier<SmartInventory> inventory;
    private final Consumer<String> status;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private State state = State.IDLE;
    private String waypointName = "";
    private boolean resumeAfter;
    private long pausedJobId = -1;
    private long travelJobId = -1;
    private JobStatus travelOutcome;
    private long target;
    private BlockPos container;
    private ControlLease lease;
    private int ticks;
    private int attempts;
    private boolean tapped;

    DepositRun(Supplier<SmartInventory> inventory, Consumer<String> status) {
        this.inventory = inventory;
        this.status = status;
    }

    boolean isRunning() {
        return state != State.IDLE;
    }

    State state() {
        return state;
    }

    void start(String name, boolean resume) {
        if (state != State.IDLE) {
            return;
        }
        IGoalEngine goals = Services.goalsOrNull();
        IWaypointService wps = Services.waypointsOrNull();
        Minecraft mc = Minecraft.getInstance();
        if (goals == null || wps == null || mc.level == null || inventory.get() == null) {
            status.accept("Deposit: needs the goal engine, waypoints and Smart Inventory");
            return;
        }
        Optional<Waypoint> wp = wps.byName(name);
        if (wp.isEmpty()) {
            status.accept("Deposit: no waypoint named '" + name + "'");
            return;
        }
        String dim = mc.level.dimension().location().toString();
        target = wps.positionIn(wp.get(), dim);
        waypointName = name;
        resumeAfter = resume;
        pausedJobId = -1;
        Optional<JobSnapshot> active = goals.activeJob();
        if (active.isPresent() && !active.get().status().isTerminal()) {
            pausedJobId = active.get().jobId();
            goals.pause(pausedJobId);
        }
        IWorldKnowledgeService wk = Services.worldKnowledgeOrNull();
        DataSourceMode mode = wk == null ? DataSourceMode.OBSERVED_ONLY : wk.routingMode();
        travelOutcome = null;
        travelJobId = goals.submit(new GoalRequest.Travel(BlockPos.getX(target), BlockPos.getY(target),
                BlockPos.getZ(target), dim, CostProfile.SAFEST, mode));
        state = State.TRAVEL;
        ticks = 0;
        status.accept("Deposit: travelling to " + name);
    }

    void onJobChanged(SuiteEvent.JobChanged e) {
        JobSnapshot s = e.snapshot();
        if (state == State.TRAVEL && s != null && s.jobId() == travelJobId && s.status().isTerminal()) {
            travelOutcome = s.status();
        }
    }

    void tick() {
        if (state == State.IDLE) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            abort(null);
            return;
        }
        ticks++;
        switch (state) {
            case TRAVEL -> {
                if (travelOutcome == null) {
                    return;
                }
                if (travelOutcome != JobStatus.COMPLETED) {
                    abort("Deposit: travel to " + waypointName + " " + travelOutcome.name().toLowerCase());
                    return;
                }
                beginOpening(mc, player);
            }
            case OPENING -> tickOpening(mc, player);
            case DEPOSITING -> {
                SmartInventory inv = inventory.get();
                if (ticks < 3 || (inv != null && inv.isBusy())) {
                    return;
                }
                player.closeContainer();
                finish("Deposit at " + waypointName + " done");
            }
            default -> {
            }
        }
    }

    private void beginOpening(Minecraft mc, LocalPlayer player) {
        container = findContainer(mc, player);
        if (container == null) {
            abort("Deposit: no reachable chest, barrel or shulker box at " + waypointName);
            return;
        }
        lease = Services.arbiter().request(new LeaseRequest(LocalRules.ID, ControlPriority.GOAL,
                EnumSet.of(ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT), 200, this)).orElse(null);
        if (lease == null) {
            abort("Deposit: controls are busy");
            return;
        }
        Vec3 eye = player.getEyePosition();
        double dx = container.getX() + 0.5 - eye.x;
        double dy = container.getY() + 0.5 - eye.y;
        double dz = container.getZ() + 0.5 - eye.z;
        float yaw = Mth.wrapDegrees((float) Math.toDegrees(Math.atan2(dz, dx)) - 90f);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        Services.input().setRotationTarget(lease, RotationTarget.visible(yaw, pitch));
        state = State.OPENING;
        ticks = 0;
        tapped = false;
    }

    private void tickOpening(Minecraft mc, LocalPlayer player) {
        if (mc.screen instanceof AbstractContainerScreen<?> s && SmartInventory.isStorageMenu(s.getMenu())) {
            releaseLease();
            SmartInventory inv = inventory.get();
            String r = inv == null ? "Smart Inventory missing" : inv.depositToOpenContainer(true, true);
            status.accept("Deposit: " + r);
            state = State.DEPOSITING;
            ticks = 0;
            return;
        }
        ControlLease l = lease;
        if (l == null || !l.isActive()) {
            abort("Deposit: interrupted");
            return;
        }
        if (!tapped && Services.input().isRotationSettled(2.5f)) {
            Services.input().scheduleKeyTap(l, SimKey.USE, 1, 2);
            tapped = true;
        }
        if (ticks > OPEN_TIMEOUT) {
            if (++attempts > 1) {
                abort("Deposit: the container did not open");
            } else {
                ticks = 0;
                tapped = false;
            }
        }
    }

    private BlockPos findContainer(Minecraft mc, LocalPlayer player) {
        Vec3 eye = player.getEyePosition();
        int tx = BlockPos.getX(target);
        int ty = BlockPos.getY(target);
        int tz = BlockPos.getZ(target);
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dy = -SEARCH_RADIUS; dy <= SEARCH_RADIUS; dy++) {
                for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                    cursor.set(tx + dx, ty + dy, tz + dz);
                    BlockEntity be = mc.level.getBlockEntity(cursor);
                    if (!(be instanceof RandomizableContainerBlockEntity)) {
                        continue;
                    }
                    double reach = eye.distanceToSqr(cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5);
                    if (reach > REACH * REACH) {
                        continue;
                    }
                    double d = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (d < bestD) {
                        bestD = d;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null; // tickOpening notices and aborts (unless the container already opened)
        }
    }

    void abort(String message) {
        releaseLease();
        state = State.IDLE;
        attempts = 0;
        if (message != null) {
            status.accept(message);
        }
    }

    private void finish(String message) {
        releaseLease();
        state = State.IDLE;
        attempts = 0;
        IGoalEngine goals = Services.goalsOrNull();
        if (resumeAfter && pausedJobId >= 0 && goals != null) {
            goals.resume(pausedJobId);
            message += "; resuming the job";
        }
        status.accept(message);
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null) {
            l.close();
        }
    }
}
