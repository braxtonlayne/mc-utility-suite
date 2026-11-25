package com.utilitysuite.world.stronghold;

/**
 * Engine-free sampler of one eye of ender's flight: records the spawn point and the horizontal positions at flight ages
 * {@link #FIRST_TICK}..{@link #LAST_TICK}; the heading is the mean per-tick horizontal step over that window (the
 * first tick is skipped because the client position snaps in from the spawn packet). Reused, no allocation per tick.
 */
public final class EyeFlight {

    public static final int FIRST_TICK = 2;
    public static final int LAST_TICK = 12;

    private double spawnX;
    private double spawnZ;
    private double startX;
    private double startZ;
    private double endX;
    private double endZ;
    private int age;
    private boolean started;
    private boolean done;

    public void begin(double x, double z) {
        spawnX = x;
        spawnZ = z;
        age = 0;
        started = false;
        done = false;
    }

    /** One client tick of the eye at its current position; true once the heading is ready (exactly once). */
    public boolean tick(double x, double z) {
        if (done) {
            return false;
        }
        age++;
        if (age == FIRST_TICK) {
            startX = x;
            startZ = z;
            started = true;
        } else if (age == LAST_TICK && started) {
            endX = x;
            endZ = z;
            done = true;
            return true;
        }
        return false;
    }

    public double spawnX() {
        return spawnX;
    }

    public double spawnZ() {
        return spawnZ;
    }

    /** Mean horizontal step per tick over the window (x). */
    public double dirX() {
        return (endX - startX) / (LAST_TICK - FIRST_TICK);
    }

    public double dirZ() {
        return (endZ - startZ) / (LAST_TICK - FIRST_TICK);
    }

    /** True when the eye actually moved horizontally (not a stalled or dropped eye). */
    public boolean moved() {
        return Math.hypot(endX - startX, endZ - startZ) > 0.5;
    }
}
