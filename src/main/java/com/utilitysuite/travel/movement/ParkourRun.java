package com.utilitysuite.travel.movement;

/**
 * Bookkeeping for a run through a {@link ParkourCourse}, shared by the simulated benchmark and the in-game playtest:
 * which pad the runner reached, when, and the yaw a player would aim with (straight at the next pad). The runner only
 * holds forward and sprint and steers with that yaw; jumps and air corrections are the assist's job.
 */
public final class ParkourRun {

    private final ParkourCourse course;
    private final int[] reachedTick;
    private int pad;
    private int best;
    private int fellAt = -1;

    public ParkourRun(ParkourCourse course) {
        this.course = course;
        this.reachedTick = new int[course.pads()];
        java.util.Arrays.fill(reachedTick, -1);
        reachedTick[0] = 0;
    }

    /**
     * Feeds the runner's position (course coordinates) once per tick.
     *
     * @return false once the run is over (fell below the current pad, or finished)
     */
    public boolean update(int tick, double x, double y, double z, boolean onGround) {
        if (fellAt >= 0 || finished()) {
            return false;
        }
        if (onGround) {
            int p = course.padAt(x, y, z);
            if (p > pad) {
                pad = p;
                best = Math.max(best, p);
                if (reachedTick[p] < 0) {
                    reachedTick[p] = tick;
                }
            }
        }
        if (y < course.padY(pad) - 2.5) {
            fellAt = pad;
            return false;
        }
        return !finished();
    }

    /** Aim yaw (degrees, Minecraft convention) from the given position at the next pad. */
    public float aimYaw(double x, double z) {
        double dx = course.aimX(pad) - x;
        double dz = course.aimZ(pad) - z;
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    public boolean finished() {
        return best == course.pads() - 1;
    }

    /** Segments cleared (pads reached beyond the start runway). */
    public int cleared() {
        return best;
    }

    /** Segment the runner fell on (1-based segment index = the pad it was jumping to), or -1. */
    public int fellOnSegment() {
        return fellAt < 0 ? -1 : fellAt + 1;
    }

    public int reachedTick(int pad) {
        return reachedTick[pad];
    }

    /** One line per segment: name, cleared or not, tick reached. */
    public String report() {
        StringBuilder sb = new StringBuilder();
        var segs = course.segments();
        for (int i = 0; i < segs.size(); i++) {
            int t = reachedTick[i + 1];
            sb.append(i + 1).append(". ").append(segs.get(i).name()).append(": ")
                    .append(t >= 0 ? "cleared at tick " + t : (fellOnSegment() == i + 1 ? "FELL" : "not reached"))
                    .append('\n');
        }
        return sb.toString();
    }
}
