package com.utilitysuite.world.knowledge;

/** Seed-based predictions (ghost data). Implemented over the OreSim snapshots; a fake in tests. Any thread. */
public interface PredictionSource {

    @FunctionalInterface
    interface Visitor {
        /**
         * @param blockId    predicted block id at that position
         * @param altBlockId the other variant of the same ore (deepslate/stone; the boundary is approximate), or null
         * @param clusterKey vein identity for downranking
         */
        void visit(long pos, String blockId, String altBlockId, float confidence, long clusterKey);
    }

    /** Visits predictions within {@code radius} blocks of {@code origin} in the dimension. */
    void forEachPrediction(String dimension, long origin, int radius, Visitor visitor);

    /** Cluster key of the prediction at {@code pos}, or {@link #NO_CLUSTER} when nothing is predicted there. */
    long clusterAt(String dimension, long pos);

    /** Predicted block id at {@code pos}, or null. */
    String predictedIdAt(String dimension, long pos);

    long NO_CLUSTER = Long.MIN_VALUE;

    PredictionSource NONE = new PredictionSource() {
        @Override
        public void forEachPrediction(String dimension, long origin, int radius, Visitor visitor) {
        }

        @Override
        public long clusterAt(String dimension, long pos) {
            return NO_CLUSTER;
        }

        @Override
        public String predictedIdAt(String dimension, long pos) {
            return null;
        }
    };
}
