package com.utilitysuite.world.redstone;

/** Label text for the redstone tracer, engine-free. Called only when a combination is first shown (cached). */
public final class RedstoneLabels {

    private RedstoneLabels() {
    }

    /**
     * @param delayGt  cumulative game ticks from the start
     * @param strength 0-15, -1 = not shown, -2 = unknown ("?", comparators)
     * @param qc       reached through quasi-connectivity
     * @param piston   a piston (extension finishes {@link RedstoneTracer#PISTON_EXTEND_GT} game ticks later)
     */
    public static String format(int delayGt, int strength, boolean qc, boolean piston) {
        StringBuilder sb = new StringBuilder(24);
        sb.append('+').append(delayGt).append("gt");
        if (strength >= 0) {
            sb.append(' ').append(strength);
        } else if (strength == -2) {
            sb.append(" ?");
        }
        if (piston) {
            sb.append(" ext+").append(RedstoneTracer.PISTON_EXTEND_GT);
        }
        if (qc) {
            sb.append(" QC");
        }
        return sb.toString();
    }
}
