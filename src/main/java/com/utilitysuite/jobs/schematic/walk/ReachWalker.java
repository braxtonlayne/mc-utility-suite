package com.utilitysuite.jobs.schematic.walk;

import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.NavigationStatus;
import net.minecraft.core.BlockPos;

import java.util.BitSet;
import java.util.Optional;

/**
 * "Walk to reach blocks": the state machine that moves the player when Build Assist has placeable cells left but none
 * it can see and reach from where the player stands. Engine-agnostic; Build Assist supplies the world side through
 * {@link Host}. Client thread only.
 * <ul>
 *   <li>IDLE: counts consecutive stalled ticks at the same block. After {@code stallTicks} it picks the nearest stalled
 *       cell, asks for standing spots, releases Build Assist's own lease, then starts a leased walk (never both).</li>
 *   <li>WALKING: Build Assist does nothing. ARRIVED returns to IDLE (Build Assist resumes placing). FAILED (or a run
 *       cut short without a reason) marks the cell unreachable for {@code unreachableTicks} and goes back to IDLE, so
 *       the next stall tries the next cell.</li>
 *   <li>PAUSED: the run was preempted, overridden by the user, timed out or stopped by Stop-All. No new walk until the
 *       player has moved to another block (the user took over) or {@link #reset()} (resume, new plan).</li>
 *   <li>UNAVAILABLE: no navigator (Baritone missing); Build Assist keeps today's "move around the build" behaviour.</li>
 * </ul>
 */
public final class ReachWalker {

    /** World side of the walker, implemented by Build Assist. */
    public interface Host {
        boolean navigatorAvailable();

        /** Nearest remaining placeable cell (materials on hand) not in {@code skip}, or -1 when there is none. */
        int nearestStalledCell(BitSet skip);

        /** Writes standing spots for the cell into {@code out} (packed feet positions, best first); returns the count. */
        int standingSpots(int cell, long[] out);

        /** Releases Build Assist's own lease; called right before {@link #startWalk}. */
        void releaseOwnLease();

        /** Starts the leased walk to the feet position; empty when refused (movement busy, automation stopped). */
        Optional<NavigationRun> startWalk(int x, int y, int z);
    }

    public enum Phase { IDLE, WALKING, PAUSED, UNAVAILABLE }

    private static final int MAX_CELL_TRIES = 4;
    private static final int REFUSED_RETRY_TICKS = 100;

    private final int stallTicks;
    private final int unreachableTicks;
    private final long[] spots = new long[4];
    private final BitSet unreachable = new BitSet();

    private Phase phase = Phase.IDLE;
    private NavigationRun run;
    private int tick;
    private int stall;
    private long stallFrom = Long.MIN_VALUE;
    private long pausedAt = Long.MIN_VALUE;
    private int walkCell = -1;
    private int arrivedCell = -1;
    private int unreachableClearAt;

    public ReachWalker(int stallTicks, int unreachableTicks) {
        this.stallTicks = stallTicks;
        this.unreachableTicks = unreachableTicks;
    }

    public Phase phase() {
        return phase;
    }

    public boolean isWalking() {
        return phase == Phase.WALKING;
    }

    public boolean isUnreachable(int cell) {
        return unreachable.get(cell);
    }

    /**
     * One client tick.
     *
     * @param stalled     Build Assist found nothing to place from here last tick (and is not paused by preemption)
     * @param playerBlock the player's packed block position
     * @return true while walking: Build Assist must not take its lease or act this tick
     */
    public boolean tick(boolean stalled, long playerBlock, Host host) {
        tick++;
        if (!unreachable.isEmpty() && tick - unreachableClearAt >= 0) {
            unreachable.clear();
        }
        switch (phase) {
            case WALKING -> {
                return tickWalking(playerBlock);
            }
            case PAUSED -> {
                if (playerBlock == pausedAt) {
                    return false;
                }
                phase = Phase.IDLE; // the user moved: count a fresh stall from the new spot
                stall = 0;
                stallFrom = playerBlock;
                return false;
            }
            default -> {
                return tickIdle(stalled, playerBlock, host);
            }
        }
    }

    private boolean tickWalking(long playerBlock) {
        NavigationRun r = run;
        if (r != null && r.isActive()) {
            return true;
        }
        run = null;
        stall = 0;
        NavigationStatus status = r == null ? NavigationStatus.IDLE : r.status();
        LeaseEndReason why = r == null ? null : r.endReason();
        int cell = walkCell;
        walkCell = -1;
        if (status == NavigationStatus.ARRIVED) {
            arrivedCell = cell;
            phase = Phase.IDLE;
        } else if (why != null && why != LeaseEndReason.RELEASED) {
            phase = Phase.PAUSED;
            pausedAt = playerBlock;
        } else {
            markUnreachable(cell);
            phase = Phase.IDLE;
        }
        return false;
    }

    private boolean tickIdle(boolean stalled, long playerBlock, Host host) {
        if (!stalled || playerBlock != stallFrom) {
            stallFrom = playerBlock;
            stall = stalled ? 1 : 0;
            if (phase == Phase.UNAVAILABLE && !stalled) {
                phase = Phase.IDLE;
            }
            return false;
        }
        if (++stall < stallTicks) {
            return false;
        }
        stall = 0;
        if (!host.navigatorAvailable()) {
            phase = Phase.UNAVAILABLE;
            return false;
        }
        phase = Phase.IDLE;
        int cell = host.nearestStalledCell(unreachable);
        if (cell >= 0 && cell == arrivedCell) {
            markUnreachable(cell); // walked there already and still could not place it
            cell = host.nearestStalledCell(unreachable);
        }
        arrivedCell = -1;
        for (int tries = 0; tries < MAX_CELL_TRIES && cell >= 0; tries++) {
            int n = host.standingSpots(cell, spots);
            if (n <= 0) {
                markUnreachable(cell);
                cell = host.nearestStalledCell(unreachable);
                continue;
            }
            host.releaseOwnLease();
            long spot = spots[0];
            Optional<NavigationRun> started = host.startWalk(BlockPos.getX(spot), BlockPos.getY(spot),
                    BlockPos.getZ(spot));
            if (started.isEmpty()) {
                stall = -REFUSED_RETRY_TICKS; // movement busy or stopped: try again later
                return false;
            }
            run = started.get();
            walkCell = cell;
            phase = Phase.WALKING;
            return true;
        }
        return false;
    }

    private void markUnreachable(int cell) {
        if (cell >= 0) {
            if (unreachable.isEmpty()) {
                unreachableClearAt = tick + unreachableTicks;
            }
            unreachable.set(cell);
        }
    }

    /**
     * Walks off a block the player stands on so it can be taken down: the walk's own scaffold that ended up in a
     * schematic cell (Baritone pillared onto it to reach a high layer) would otherwise block that cell for good, since
     * scaffold under the player is never broken. {@code spots} are standing spots that reach {@code target} (best
     * first); one in the target's own column (on top of it) is skipped. Same lease handover as a reach walk.
     *
     * @return true when a walk started
     */
    public boolean stepOff(long target, long[] spots, int count, Host host) {
        if (phase == Phase.WALKING || count <= 0) {
            return false;
        }
        int tx = BlockPos.getX(target), tz = BlockPos.getZ(target);
        for (int i = 0; i < count; i++) {
            long spot = spots[i];
            if (BlockPos.getX(spot) == tx && BlockPos.getZ(spot) == tz) {
                continue; // standing on (or above) it again
            }
            host.releaseOwnLease();
            Optional<NavigationRun> started = host.startWalk(BlockPos.getX(spot), BlockPos.getY(spot),
                    BlockPos.getZ(spot));
            if (started.isEmpty()) {
                return false;
            }
            run = started.get();
            walkCell = -1;
            phase = Phase.WALKING;
            return true;
        }
        return false;
    }

    /** Cancels a running walk and forgets all state (stop, resume, new plan, setting turned off). */
    public void reset() {
        NavigationRun r = run;
        run = null;
        if (r != null && r.isActive()) {
            r.cancel();
        }
        phase = Phase.IDLE;
        stall = 0;
        stallFrom = Long.MIN_VALUE;
        pausedAt = Long.MIN_VALUE;
        walkCell = -1;
        arrivedCell = -1;
        unreachable.clear();
    }
}
