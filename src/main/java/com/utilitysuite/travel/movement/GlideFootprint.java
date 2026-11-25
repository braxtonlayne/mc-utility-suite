package com.utilitysuite.travel.movement;

/**
 * Zero-rocket glide footprint (engine free, allocation free after construction): for each of {@code headings} evenly
 * spaced compass directions, fly {@link ElytraGlideSim} at {@link ElytraGlideSim#BEST_GLIDE_PITCH} from the current
 * state over the terrain heightmap until the feet meet the ground (or a wall: the ground height rises above the feet),
 * and record that point. The ring of points is the farthest reachable ground without rockets.
 * <p>
 * Turning is treated as free (vanilla elytra steering keeps the horizontal speed magnitude), so every heading starts
 * with the current horizontal speed. Headings are computed one at a time ({@link #computeHeading}) so the caller can
 * budget heightmap lookups across ticks.
 * <p>
 * Also hosts the wave-glide comparison: dive to {@link #WAVE_DIVE_PITCH}, climb at {@link #WAVE_CLIMB_PITCH}, repeat.
 */
public final class GlideFootprint {

    /** Ground surface height (the y the feet stand on) at a column. Must not allocate. */
    @FunctionalInterface
    public interface Heightmap {
        int surfaceY(int x, int z);
    }

    public static final double WAVE_DIVE_PITCH = 40.0;
    public static final double WAVE_CLIMB_PITCH = -40.0;
    /** Wave: climb when faster than this, dive when slower than {@link #WAVE_LOW_SPEED} (blocks/tick). */
    public static final double WAVE_HIGH_SPEED = 2.0, WAVE_LOW_SPEED = 0.9;

    public final int headings;
    public final double[] ringX, ringY, ringZ, reach;
    public final boolean[] landed;
    private final ElytraGlideSim sim = new ElytraGlideSim();
    private final int maxTicks;

    public GlideFootprint(int headings, int maxTicks) {
        this.headings = headings;
        this.maxTicks = maxTicks;
        ringX = new double[headings];
        ringY = new double[headings];
        ringZ = new double[headings];
        reach = new double[headings];
        landed = new boolean[headings];
    }

    /** Compass yaw (Minecraft convention, degrees) of heading {@code i}. */
    public double yawOf(int i) {
        return 360.0 * i / headings;
    }

    /** Computes every heading now. */
    public void computeAll(double x, double y, double z, double hSpeed, double vy, Heightmap map) {
        for (int i = 0; i < headings; i++) {
            computeHeading(i, x, y, z, hSpeed, vy, map);
        }
    }

    /**
     * One heading: glide from (x, y, z) with horizontal speed {@code hSpeed} and vertical speed {@code vy} until the
     * ground is reached. Writes ringX/Y/Z[i], reach[i] and landed[i] (false when {@code maxTicks} ran out in the air).
     */
    public void computeHeading(int i, double x, double y, double z, double hSpeed, double vy, Heightmap map) {
        double yaw = Math.toRadians(yawOf(i));
        double dx = -Math.sin(yaw), dz = Math.cos(yaw);
        sim.set(hSpeed, vy, y);
        double px = x, pz = z;
        boolean hit = false;
        for (int t = 0; t < maxTicks; t++) {
            sim.step(ElytraGlideSim.BEST_GLIDE_PITCH);
            px = x + dx * sim.h;
            pz = z + dz * sim.h;
            int ground = map.surfaceY((int) Math.floor(px), (int) Math.floor(pz));
            if (sim.y <= ground) {
                hit = true;
                break;
            }
        }
        ringX[i] = px;
        ringZ[i] = pz;
        ringY[i] = hit ? map.surfaceY((int) Math.floor(px), (int) Math.floor(pz)) : sim.y;
        reach[i] = sim.h;
        landed[i] = hit;
    }

    // ---------------------------------------------------------------- wave glide

    public enum WaveCue { NONE, DIVE, CLIMB }

    /**
     * Horizontal distance flown on flat ground {@code height} blocks below, from speed {@code hSpeed}/{@code vy}: either
     * straight at the best glide pitch or by the wave (dive/climb) pattern. Mutates {@code scratch}.
     */
    public static double flatDistance(ElytraGlideSim scratch, double hSpeed, double vy, double height, boolean wave,
                                      int maxTicks) {
        scratch.set(hSpeed, vy, height);
        boolean climbing = false;
        for (int t = 0; t < maxTicks && scratch.y > 0; t++) {
            double pitch;
            if (wave) {
                double s = scratch.speed();
                if (climbing && (s < WAVE_LOW_SPEED || scratch.vy < 0)) {
                    climbing = false;
                } else if (!climbing && s > WAVE_HIGH_SPEED) {
                    climbing = true;
                }
                pitch = climbing ? WAVE_CLIMB_PITCH : WAVE_DIVE_PITCH;
                // keep a safety margin: do not dive into the last few blocks
                if (!climbing && scratch.y < 6) {
                    pitch = ElytraGlideSim.BEST_GLIDE_PITCH;
                }
            } else {
                pitch = ElytraGlideSim.BEST_GLIDE_PITCH;
            }
            scratch.step(pitch);
        }
        return scratch.h;
    }

    /** True when the wave pattern beats straight best-glide by at least 3% from this state. */
    public static boolean waveBeneficial(ElytraGlideSim scratch, double hSpeed, double vy, double height) {
        if (height < 20) {
            return false;
        }
        double straight = flatDistance(scratch, hSpeed, vy, height, false, 4000);
        double wave = flatDistance(scratch, hSpeed, vy, height, true, 4000);
        return wave > straight * 1.03;
    }

    /**
     * Wave cue with hysteresis: {@code previous} is the last cue. Dive until fast, then climb until slow or sinking.
     */
    public static WaveCue waveCue(WaveCue previous, double speed, double vy) {
        if (previous == WaveCue.CLIMB) {
            return speed < WAVE_LOW_SPEED || vy < 0 ? WaveCue.DIVE : WaveCue.CLIMB;
        }
        return speed > WAVE_HIGH_SPEED ? WaveCue.CLIMB : WaveCue.DIVE;
    }
}
