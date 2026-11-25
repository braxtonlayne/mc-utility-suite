package com.utilitysuite.world.stealth;

/**
 * Engine-free maths for the stealth vision overlay: the enderman stare test (vanilla {@code EnderMan.isLookingAtMe}),
 * view cones, a voxel ray walk (vanilla {@code BlockGetter.traverseBlocks}, Amanatides-Woo) and the vibration
 * occlusion rule (vanilla {@code VibrationSystem.Listener.isOccluded}: a vibration is blocked only when all six
 * slightly nudged rays from the source block centre to the listener block centre cross a wool block).
 * Allocation-free.
 */
public final class StealthMath {

    /** Enderman stare range (its LookForStaringPlayerGoal uses the 64-block follow range). */
    public static final double ENDERMAN_RANGE = 64.0;
    /** Warden vibration listener radius. */
    public static final int WARDEN_LISTEN_RADIUS = 16;
    /** Sculk sensor / shrieker vibration listener radius. */
    public static final int SCULK_RADIUS = 8;
    /** Piglin brute sight range (piglin NEAREST_VISIBLE_LIVING_ENTITIES sensor, 16 blocks). */
    public static final int BRUTE_RADIUS = 16;
    /** Ray-walk safety cap (cells). */
    private static final int MAX_CELLS = 1024;
    private static final double NUDGE = 1.0e-5;

    /** Opaque-cell test for {@link #blocked}. Implementations must not allocate. */
    @FunctionalInterface
    public interface CellTest {
        boolean test(int x, int y, int z);
    }

    private StealthMath() {
    }

    /** Vanilla stare threshold at distance {@code d}: the look . direction dot product must exceed this. */
    public static double endermanDotThreshold(double d) {
        return 1.0 - 0.025 / d;
    }

    /**
     * Vanilla stare test without line of sight: {@code look} is the player's unit view vector, {@code d} the vector
     * from the player's eye to the enderman's eye. True when the enderman would count the player as staring
     * (line of sight and carved pumpkin are checked by the caller).
     */
    public static boolean endermanStare(double lookX, double lookY, double lookZ, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0e-6 || len > ENDERMAN_RANGE) {
            return false;
        }
        double dot = (lookX * dx + lookY * dy + lookZ * dz) / len;
        return dot > endermanDotThreshold(len);
    }

    /**
     * Radius, at the enderman's eye, of the stare cone seen from distance {@code d}: keep the crosshair farther than
     * this from his head. {@code d * sin(acos(1 - 0.025 / d))}, about {@code sqrt(0.05 d)}.
     */
    public static double endermanGazeRadius(double d) {
        if (d <= 0.025) {
            return d;
        }
        double c = endermanDotThreshold(d);
        return d * Math.sqrt(Math.max(0.0, 1.0 - c * c));
    }

    /** True when the point at offset (dx, dy, dz) is within {@code range} and inside the cone around unit {@code f}. */
    public static boolean inCone(double fx, double fy, double fz, double dx, double dy, double dz, double cosHalf,
                                 double range) {
        double l2 = dx * dx + dy * dy + dz * dz;
        if (l2 > range * range) {
            return false;
        }
        if (l2 < 1.0e-12) {
            return true;
        }
        return (fx * dx + fy * dy + fz * dz) >= cosHalf * Math.sqrt(l2);
    }

    /** Minecraft look vector X for yaw/pitch in degrees. */
    public static double lookX(float yawDeg, float pitchDeg) {
        double p = Math.toRadians(pitchDeg);
        return -Math.sin(Math.toRadians(yawDeg)) * Math.cos(p);
    }

    public static double lookY(float pitchDeg) {
        return -Math.sin(Math.toRadians(pitchDeg));
    }

    public static double lookZ(float yawDeg, float pitchDeg) {
        double p = Math.toRadians(pitchDeg);
        return Math.cos(Math.toRadians(yawDeg)) * Math.cos(p);
    }

    /**
     * Walks the cells crossed by the segment a to b (start cell included) and returns true at the first cell the test
     * accepts. Mirrors vanilla's block traversal order.
     */
    public static boolean blocked(double x0, double y0, double z0, double x1, double y1, double z1, CellTest t) {
        int x = floor(x0);
        int y = floor(y0);
        int z = floor(z0);
        if (t.test(x, y, z)) {
            return true;
        }
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        int sx = dx > 0 ? 1 : dx < 0 ? -1 : 0;
        int sy = dy > 0 ? 1 : dy < 0 ? -1 : 0;
        int sz = dz > 0 ? 1 : dz < 0 ? -1 : 0;
        double tdx = sx == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dx);
        double tdy = sy == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dy);
        double tdz = sz == 0 ? Double.MAX_VALUE : 1.0 / Math.abs(dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : tdx * (sx > 0 ? 1.0 - frac(x0) : frac(x0));
        double tmy = sy == 0 ? Double.MAX_VALUE : tdy * (sy > 0 ? 1.0 - frac(y0) : frac(y0));
        double tmz = sz == 0 ? Double.MAX_VALUE : tdz * (sz > 0 ? 1.0 - frac(z0) : frac(z0));
        for (int i = 0; i < MAX_CELLS; i++) {
            if (tmx <= tmy && tmx <= tmz) {
                if (tmx > 1.0) {
                    return false;
                }
                x += sx;
                tmx += tdx;
            } else if (tmy <= tmz) {
                if (tmy > 1.0) {
                    return false;
                }
                y += sy;
                tmy += tdy;
            } else {
                if (tmz > 1.0) {
                    return false;
                }
                z += sz;
                tmz += tdz;
            }
            if (t.test(x, y, z)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Vanilla vibration occlusion between the source block and the listener block: occluded only if every one of the
     * six rays (source centre nudged 1e-5 toward each face) crosses an occluding (wool) cell.
     */
    public static boolean vibrationOccluded(int sx, int sy, int sz, int lx, int ly, int lz, CellTest wool) {
        double ax = sx + 0.5;
        double ay = sy + 0.5;
        double az = sz + 0.5;
        double bx = lx + 0.5;
        double by = ly + 0.5;
        double bz = lz + 0.5;
        for (int d = 0; d < 6; d++) {
            double ox = d == 4 ? -NUDGE : d == 5 ? NUDGE : 0;
            double oy = d == 0 ? -NUDGE : d == 1 ? NUDGE : 0;
            double oz = d == 2 ? -NUDGE : d == 3 ? NUDGE : 0;
            if (!blocked(ax + ox, ay + oy, az + oz, bx, by, bz, wool)) {
                return false;
            }
        }
        return true;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    private static double frac(double v) {
        return v - Math.floor(v);
    }
}
