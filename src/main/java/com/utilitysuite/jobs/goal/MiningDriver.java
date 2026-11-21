package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlLease;

import java.util.List;

/**
 * How the job executor moves and mines. In game this is {@code jobs.baritone.BaritoneAdapter}; tests use a fake.
 * No Baritone type crosses this interface. Client thread only.
 */
public interface MiningDriver {

    enum Status {
        IDLE,
        RUNNING,
        /** The last goto/interact reached its goal. */
        ARRIVED,
        /** Pathing failed or the process gave up; see {@link #failureReason()}. */
        FAILED,
        /** No pathing backend installed. */
        UNAVAILABLE
    }

    boolean isAvailable();

    /**
     * Mines the candidate blocks with the backend's own loaded-chunk scanning until stopped. The executor counts the
     * inventory delta of the item (yield), not blocks broken, and cancels when the yield is reached.
     *
     * @param desiredItemCount remaining items wanted (a hint for the backend)
     */
    void mineByYield(String itemId, int desiredItemCount, List<String> candidateBlocks);

    /**
     * Digs out every block in the box between the two packed corners (inclusive): a tunnel segment, or one ore block
     * the tunnel has uncovered. Status goes ARRIVED when done.
     */
    default void clearArea(long cornerA, long cornerB) {
    }

    /** How the next {@link #mineByYield} finds ore (see {@link MiningStyle}); applies until changed. */
    default void setMiningStyle(MiningStyle style, List<String> candidateBlocks) {
    }

    /** Paths to within {@code range} blocks of the packed position. */
    void gotoCoord(long packedPos, int range);

    /** Follows packed positions in order. */
    void followPath(long[] packedPositions);

    /** Paths next to the block and right-clicks it (open a container or station) using the given lease. */
    void interactBlock(long packedPos, ControlLease lease);

    /** Stops the backend and remembers the command so {@link #resume()} can re-issue it. */
    void pause();

    void resume();

    void cancel();

    Status status();

    /** Why the last command failed, or empty. */
    String failureReason();

    /** Driver with no backend: every command fails immediately. */
    MiningDriver NONE = new MiningDriver() {
        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public void mineByYield(String itemId, int desiredItemCount, List<String> candidateBlocks) {
        }

        @Override
        public void gotoCoord(long packedPos, int range) {
        }

        @Override
        public void followPath(long[] packedPositions) {
        }

        @Override
        public void interactBlock(long packedPos, ControlLease lease) {
        }

        @Override
        public void pause() {
        }

        @Override
        public void resume() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public Status status() {
            return Status.UNAVAILABLE;
        }

        @Override
        public String failureReason() {
            return "Baritone is not installed";
        }
    };
}
