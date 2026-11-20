package com.utilitysuite.jobs.baritone;

import baritone.api.BaritoneAPI;
import baritone.api.IBaritone;
import baritone.api.Settings;
import baritone.api.pathing.calc.IPath;
import baritone.api.pathing.goals.Goal;
import baritone.api.pathing.goals.GoalBlock;
import baritone.api.pathing.goals.GoalGetToBlock;
import baritone.api.pathing.goals.GoalNear;
import baritone.api.utils.BetterBlockPos;
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
}
