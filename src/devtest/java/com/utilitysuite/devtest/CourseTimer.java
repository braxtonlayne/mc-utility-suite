package com.utilitysuite.devtest;

import java.util.Arrays;

/**
 * Times one run of the benchmark course from feet positions relative to the course origin, one call per game tick
 * (paused ticks are not fed). Plain Java, shared by the live course ({@link CourseLive}) and the automated scenario.
 * <ul>
 *   <li>IDLE on the start pad; the run starts on the first tick the feet are more than {@link #START_LEAVE} blocks
 *   from the start position.</li>
 *   <li>A checkpoint counts the first time it is reached, and only in order past the last one reached (a later pad
 *   reached early, by a shortcut, still counts; an earlier one is ignored).</li>
 *   <li>A fall is feet more than {@link #FALL_DEPTH} below the lowest built block: the caller puts the body back on
 *   the last checkpoint ({@link #respawnIndex()}); the clock keeps running.</li>
 *   <li>The finish volume ends the run.</li>
 * </ul>
 */
final class CourseTimer {

    enum State { IDLE, RUNNING, FINISHED }

    static final double START_LEAVE = 0.75;
    static final int FALL_DEPTH = 4;

    private final CourseFile course;
    State state = State.IDLE;
    int ticks;
    int falls;
    /** Tick of reaching each checkpoint (index in the course's list), -1 when not reached. */
    final int[] splits;
    int lastCheckpoint = -1;

    CourseTimer(CourseFile course) {
        this.course = course;
        this.splits = new int[course.checkpoints.size()];
        reset();
    }

    void reset() {
        state = State.IDLE;
        ticks = 0;
        falls = 0;
        lastCheckpoint = -1;
        Arrays.fill(splits, -1);
    }

    /** What the caller must do after a tick. */
    enum Event { NONE, STARTED, CHECKPOINT, FELL, FINISHED }

    Event tick(double x, double y, double z) {
        switch (state) {
            case IDLE -> {
                double dx = x - course.startX, dz = z - course.startZ;
                if (dx * dx + dz * dz > START_LEAVE * START_LEAVE || y > course.startY + 1.5) {
                    state = State.RUNNING;
                    ticks = 0;
                    return Event.STARTED;
                }
                return Event.NONE;
            }
            case RUNNING -> {
                ticks++;
                if (course.inFinish(x, y, z)) {
                    state = State.FINISHED;
                    return Event.FINISHED;
                }
                if (y < course.minY - FALL_DEPTH) {
                    falls++;
                    return Event.FELL;
                }
                int cp = course.checkpointAt(x, y, z);
                if (cp > lastCheckpoint) {
                    splits[cp] = ticks;
                    lastCheckpoint = cp;
                    return Event.CHECKPOINT;
                }
                return Event.NONE;
            }
            default -> {
                return Event.NONE;
            }
        }
    }

    /** The checkpoint to put the body back on after a fall, or -1 for the start. */
    int respawnIndex() {
        return lastCheckpoint;
    }

    static String clock(int ticks) {
        int ms = ticks * 50;
        return String.format("%d:%02d.%02d", ms / 60000, (ms / 1000) % 60, (ms % 1000) / 10);
    }
}
