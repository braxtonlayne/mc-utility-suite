package com.utilitysuite.jobs.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.behavior.IPathingBehavior;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.pathing.goals.GoalRunAway;
import baritone.api.pathing.path.IPathExecutor;
import baritone.api.process.IBaritoneProcess;
import baritone.api.process.PathingCommand;
import baritone.api.process.PathingCommandType;
import baritone.api.utils.BetterBlockPos;
import com.utilitysuite.core.api.IRoutePlanner.PlanState;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.function.LongPredicate;

/**
 * The only class that touches Baritone. Loaded exclusively through {@link #create()}, which {@link BaritoneAdapter}
 * calls only after {@code ModList.get().isLoaded("baritoe")} returned true.
 */
final class BaritoneBridge implements PathBackend {

    private BaritoneBridge() {
    }

    static PathBackend create() {
        return new BaritoneBridge();
    }

    private static IBaritone baritone() {
        return BaritoneAPI.getProvider().getPrimaryBaritone();
    }

    @Override
    public void configure() {
        Settings s = BaritoneAPI.getSettings();
        s.autoTool.value = true;
        s.chatControl.value = false;
        s.desktopNotifications.value = false;
        s.mineScanDroppedItems.value = true;
        // Look: Baritone turns the real camera, which the core guard turns into capped, smoothed turns. Its own "free
        // look" would instead send snapped angles in packets behind the camera (silent snapping: not allowed).
        s.freeLook.value = false;
        s.blockFreeLook.value = false;
        s.elytraFreeLook.value = false;
        s.smoothLook.value = false;
        s.randomLooking.value = 0.0;
        s.randomLooking113.value = 0.0;
    }

    @Override
    public void setLegitMining(boolean legit, int yLevel) {
        Settings s = BaritoneAPI.getSettings();
        s.legitMine.value = legit;
        s.legitMineYLevel.value = yLevel;
        s.legitMineIncludeDiagonals.value = true;
    }

    @Override
    public void clearArea(int x1, int y1, int z1, int x2, int y2, int z2) {
        baritone().getBuilderProcess().clearArea(new BlockPos(x1, y1, z1), new BlockPos(x2, y2, z2));
    }

    @Override
    public boolean isBuilding() {
        return baritone().getBuilderProcess().isActive();
    }

    @Override
    public void mine(List<String> blockIds) {
        baritone().getMineProcess().mineByName(0, blockIds.toArray(new String[0]));
    }

    @Override
    public void goTo(int x, int y, int z, int range) {
        Goal g = range <= 0 ? new GoalBlock(x, y, z) : new GoalNear(new BlockPos(x, y, z), range);
        baritone().getCustomGoalProcess().setGoalAndPath(g);
    }

    @Override
    public void goToBlock(int x, int y, int z) {
        baritone().getCustomGoalProcess().setGoalAndPath(new GoalGetToBlock(new BlockPos(x, y, z)));
    }

    @Override
    public void cancel() {
        baritone().getPathingBehavior().cancelEverything();
    }

    @Override
    public boolean isMining() {
        return baritone().getMineProcess().isActive();
    }

    @Override
    public boolean isGoalActive() {
        return baritone().getCustomGoalProcess().isActive();
    }

    @Override
    public boolean isInGoal() {
        IBaritone b = baritone();
        Goal g = b.getCustomGoalProcess().mostRecentGoal();
        if (g == null) {
            return false;
        }
        BetterBlockPos feet = b.getPlayerContext().playerFeet();
        return g.isInGoal(feet.x, feet.y, feet.z);
    }

    @Override
    public boolean isPathing() {
        return baritone().getPathingBehavior().isPathing();
    }

    @Override
    public long firstPathPos(LongPredicate test) {
        Optional<IPath> path = baritone().getPathingBehavior().getPath();
        if (path.isEmpty()) {
            return Long.MIN_VALUE;
        }
        List<BetterBlockPos> positions = path.get().positions();
        for (int i = 0, n = positions.size(); i < n; i++) {
            BetterBlockPos p = positions.get(i);
            long feet = BlockPos.asLong(p.x, p.y, p.z);
            if (test.test(feet)) {
                return feet;
            }
            long head = BlockPos.asLong(p.x, p.y + 1, p.z);
            if (test.test(head)) {
                return head;
            }
        }
        return Long.MIN_VALUE;
    }

    // ---- plan-only routes -------------------------------------------------------------------------------------------

    /** Registered with Baritone's control manager on the first plan request. */
    private RoutePlanProcess planProcess;

    private RoutePlanProcess planProcess() {
        RoutePlanProcess p = planProcess;
        if (p == null) {
            p = new RoutePlanProcess();
            baritone().getPathingControlManager().registerProcess(p);
            planProcess = p;
        }
        return p;
    }

    @Override
    public void planTo(int x, int y, int z, int range) {
        planProcess().request(range <= 0 ? new GoalBlock(x, y, z) : new GoalNear(new BlockPos(x, y, z), range));
    }

    @Override
    public void planAway(int x, int y, int z, int distance) {
        planProcess().request(new GoalRunAway(Math.max(1, distance), new BlockPos(x, y, z)));
    }

    @Override
    public PlanState planState() {
        RoutePlanProcess p = planProcess;
        return p == null ? PlanState.NONE : p.state;
    }

    @Override
    public int planSerial() {
        RoutePlanProcess p = planProcess;
        return p == null ? 0 : p.serial;
    }

    @Override
    public int copyPlannedPath(long[] out) {
        RoutePlanProcess p = planProcess;
        if (p == null || p.state != PlanState.READY) {
            return 0;
        }
        int n = Math.min(p.count, out.length);
        System.arraycopy(p.route, 0, out, 0, n);
        return n;
    }

    @Override
    public void cancelPlan() {
        RoutePlanProcess p = planProcess;
        if (p != null) {
            p.cancel();
        }
    }

    /**
     * Computes a route through Baritone's own pathing behaviour without letting its executor drive. Verified against
     * the 1.11.3 bytecode (see integration/baritone-plan.notes.md):
     * <ul>
     *   <li>{@code SET_GOAL_AND_PAUSE}: the control manager starts a calculation (only when neither an executor nor a
     *       calculation exists) and raises the pause request, which skips the executor tick whenever the previous
     *       executor tick reported safe-to-cancel.</li>
     *   <li>Once the calculation has produced an executor, the next process tick (which runs before that game tick's
     *       executor tick) copies the path and returns {@code CANCEL_AND_SET_GOAL}: the executor is dropped before it
     *       runs.</li>
     * </ul>
     * Lowest priority and not temporary: any job process outranks it, and when one takes control Baritone cancels the
     * plan's calculation and calls {@link #onLostControl()}. Ticked by Baritone on the client thread.
     */
    private final class RoutePlanProcess implements IBaritoneProcess {

        private static final int IDLE = 0;
        /** Waiting for a replaced calculation (or a stale executor) to clear before starting. */
        private static final int REQUESTED = 1;
        private static final int CALCULATING = 2;
        /** Cancelled while a calculation ran: one more tick to stop it. */
        private static final int CANCELLING = 3;
        /** Ticks before a plan that never produced a route counts as FAILED (Baritone's own timeouts are ~2 s). */
        private static final int MAX_TICKS = 200;
        /** Calculations restarted because Baritone discarded a result (player moved off its start) or never began. */
        private static final int MAX_RESTARTS = 8;

        private int phase = IDLE;
        private Goal goal;
        private int ticks;
        private int restarts;

        PlanState state = PlanState.NONE;
        long[] route = new long[64];
        int count;
        int serial;

        void request(Goal g) {
            goal = g;
            phase = REQUESTED;
            state = PlanState.CALCULATING;
            count = 0;
            ticks = 0;
            restarts = 0;
        }

        void cancel() {
            if (phase == CALCULATING || phase == REQUESTED) {
                phase = CANCELLING;
            }
            goal = null;
            state = PlanState.NONE;
            count = 0;
        }

        @Override
        public boolean isActive() {
            return phase != IDLE;
        }

        @Override
        public boolean isTemporary() {
            return false;
        }

        @Override
        public double priority() {
            return -1000.0;
        }

        @Override
        public String displayName0() {
            return "Route plan";
        }

        @Override
        public void onLostControl() {
            if (phase != IDLE) {
                phase = IDLE;
                goal = null;
                if (state == PlanState.CALCULATING) {
                    state = PlanState.NONE;
                }
            }
        }

        @Override
        public PathingCommand onTick(boolean calcFailed, boolean isSafeToCancel) {
            IBaritone b = baritone();
            IPathingBehavior pb = b.getPathingBehavior();
            ticks++;
            switch (phase) {
                case REQUESTED -> {
                    if (pb.getInProgress().isPresent() || pb.getCurrent() != null) {
                        if (ticks > MAX_TICKS) {
                            return finish(PlanState.FAILED);
                        }
                        // A replaced plan's calculation (or a stale executor) is still there: stop it first.
                        return new PathingCommand(goal, PathingCommandType.CANCEL_AND_SET_GOAL);
                    }
                    phase = CALCULATING;
                    return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PAUSE);
                }
                case CALCULATING -> {
                    IPathExecutor exec = pb.getCurrent();
                    if (exec != null) {
                        snapshot(exec.getPath());
                        return finish(count > 0 ? PlanState.READY : PlanState.FAILED);
                    }
                    if (calcFailed || ticks > MAX_TICKS) {
                        return finish(PlanState.FAILED);
                    }
                    if (pb.getInProgress().isEmpty()) {
                        BetterBlockPos feet = b.getPlayerContext().playerFeet();
                        if (goal.isInGoal(feet.x, feet.y, feet.z)) {
                            // Baritone does not path when already in the goal: the route is where we stand.
                            route[0] = BlockPos.asLong(feet.x, feet.y, feet.z);
                            count = 1;
                            return finish(PlanState.READY);
                        }
                        if (++restarts > MAX_RESTARTS) {
                            return finish(PlanState.FAILED);
                        }
                    }
                    // Starts (or restarts) the calculation when none runs, and keeps the pause request up.
                    return new PathingCommand(goal, PathingCommandType.SET_GOAL_AND_PAUSE);
                }
                default -> {
                    // CANCELLING (or a stray tick): stop the calculation and any executor it produced.
                    phase = IDLE;
                    return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
                }
            }
        }

        /** Ends the plan: drops the executor before it ever ticks and stops any calculation. */
        private PathingCommand finish(PlanState result) {
            phase = IDLE;
            goal = null;
            if (result == PlanState.READY) {
                serial++;
            } else {
                count = 0;
            }
            state = result;
            return new PathingCommand(null, PathingCommandType.CANCEL_AND_SET_GOAL);
        }

        private void snapshot(IPath path) {
            List<BetterBlockPos> positions = path == null ? List.of() : path.positions();
            int n = positions.size();
            if (route.length < n) {
                route = new long[Math.max(n, route.length * 2)];
            }
            for (int i = 0; i < n; i++) {
                BetterBlockPos p = positions.get(i);
                route[i] = BlockPos.asLong(p.x, p.y, p.z);
            }
            count = n;
        }
    }
}
