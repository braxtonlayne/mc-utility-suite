package com.utilitysuite.travel.movement;

/**
 * Vanilla 1.21.1 elytra glide physics in the vertical plane of the heading (engine free, allocation free). One tick of
 * {@code LivingEntity.travel} while fall flying, with the look direction on the heading (the 0.1 velocity-alignment
 * term is then zero, so turning is treated as free; it keeps the horizontal speed magnitude):
 * <pre>
 *   c   = cos(pitch)^2
 *   vy += 0.08 * (-1 + 0.75 c)
 *   if vy &lt; 0:          d = -0.1 vy c;            vh += d; vy += d
 *   if pitch &lt; 0 (up):  d = vh0 * -sin(pitch) * 0.04; vh -= d; vy += 3.2 d
 *   vh *= 0.99; vy *= 0.98; move
 * </pre>
 * {@code vh0} is the horizontal speed at the start of the tick. Minecraft pitch: positive is nose down.
 */
public final class ElytraGlideSim {

    public static final double GRAVITY = 0.08;
    /** Pitch (degrees, nose down positive) that flies the farthest per block of height lost; found numerically. */
    public static final double BEST_GLIDE_PITCH;
    /** Distance flown per block of height lost at {@link #BEST_GLIDE_PITCH} in steady glide. */
    public static final double BEST_GLIDE_RATIO;

    static {
        ElytraGlideSim s = new ElytraGlideSim();
        double best = 0, bestRatio = 0;
        for (double p = -15; p <= 30; p += 0.25) {
            // settle at the pitch first, then measure the steady-state ratio
            s.set(0.5, 0, 0);
            for (int i = 0; i < 400; i++) {
                s.step(p);
            }
            double h0 = s.h, y0 = s.y;
            for (int i = 0; i < 200; i++) {
                s.step(p);
            }
            double drop = y0 - s.y;
            double ratio = drop > 1e-6 ? (s.h - h0) / drop : 0;
            if (ratio > bestRatio) {
                bestRatio = ratio;
                best = p;
            }
        }
        BEST_GLIDE_PITCH = best;
        BEST_GLIDE_RATIO = bestRatio;
    }

    /** Horizontal speed along the heading and vertical speed (blocks per tick, up positive). */
    public double vh, vy;
    /** Distance flown along the heading and height (relative to wherever the caller started). */
    public double h, y;

    public ElytraGlideSim set(double vh, double vy, double y) {
        this.vh = vh;
        this.vy = vy;
        this.y = y;
        this.h = 0;
        return this;
    }

    public void step(double pitchDeg) {
        double rad = Math.toRadians(pitchDeg);
        double cos = Math.cos(rad);
        double c = cos * cos;
        double vh0 = vh;
        vy += GRAVITY * (-1.0 + c * 0.75);
        if (vy < 0) {
            double d = vy * -0.1 * c;
            vh += d;
            vy += d;
        }
        if (rad < 0) {
            double d = vh0 * -Math.sin(rad) * 0.04;
            vh -= d;
            vy += d * 3.2;
        }
        vh *= 0.99;
        vy *= 0.98;
        h += vh;
        y += vy;
    }

    /** Total speed in blocks per tick. */
    public double speed() {
        return Math.sqrt(vh * vh + vy * vy);
    }

    /**
     * Horizontal distance flown from the current state until the height drops by {@code drop} at a constant pitch, or
     * {@code maxTicks} pass. Mutates this sim.
     */
    public double distanceForDrop(double pitchDeg, double drop, int maxTicks) {
        double startH = h, floor = y - drop;
        for (int i = 0; i < maxTicks && y > floor; i++) {
            step(pitchDeg);
        }
        return h - startH;
    }

    /**
     * Glide-stall test for the HUD chevron: speed below {@code stallSpeed} while the nose is up or level (pitch &lt;= 5)
     * means the next ticks lose lift and the glider mushes down.
     */
    public static boolean stalled(double speed, double pitchDeg, double stallSpeed) {
        return speed < stallSpeed && pitchDeg <= 5.0;
    }

    /** Flight-path angle in degrees (positive = climbing) from a velocity. */
    public static double flightPathDeg(double vh, double vy) {
        return Math.toDegrees(Math.atan2(vy, Math.max(1e-9, vh)));
    }
}
