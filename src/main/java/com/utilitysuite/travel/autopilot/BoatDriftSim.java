package com.utilitysuite.travel.autopilot;

/**
 * Engine-free forward simulation of a boat with the vanilla per-tick rules ({@code Boat.floatBoat} then
 * {@code controlBoat} then the move): velocity and turn rate are multiplied by the surface friction (land: the block
 * friction under the boat, ice / packed / frosted 0.98, blue ice 0.989, slime 0.8, anything else 0.6; water 0.9), LEFT
 * / RIGHT change the turn rate by 1 degree per tick, FORWARD adds 0.04 thrust along the yaw, BACK subtracts 0.005,
 * turning alone adds 0.005. Approximations: water currents push {@value #CURRENT_PUSH} blocks/tick along the flow,
 * bubble columns end the prediction (the boat is pulled under or launched), walls stop the blocked axis, a missing floor
 * ends it. Fixed arrays, no allocation per run.
 */
public final class BoatDriftSim {

    /** Surface codes returned by a {@link Surface} for a cell at the boat's level. */
    public static final int WALL = 0;
    public static final int LAND = 1;
    public static final int ICE = 2;
    public static final int BLUE_ICE = 3;
    public static final int SLIME = 4;
    public static final int WATER = 5;
    public static final int BUBBLE_UP = 6;
    public static final int BUBBLE_DOWN = 7;
    public static final int DROP = 8;

    public static final double FRICTION_LAND = 0.6;
    public static final double FRICTION_ICE = 0.98;
    public static final double FRICTION_BLUE_ICE = 0.989;
    public static final double FRICTION_SLIME = 0.8;
    public static final double FRICTION_WATER = 0.9;
    public static final double THRUST_FORWARD = 0.04;
    public static final double THRUST_BACK = 0.005;
    public static final double THRUST_TURN = 0.005;
    public static final double CURRENT_PUSH = 0.014;
    /** Half the boat's footprint (vanilla boat width 1.375). */
    public static final double HALF_WIDTH = 0.6875;
    public static final int MAX_TICKS = 120;

    /** End reasons. */
    public static final int END_HORIZON = 0;
    public static final int END_BUBBLE = 1;
    public static final int END_DROP = 2;

    /** The world at the boat's level, by block cell. */
    public interface Surface {
        int surface(int x, int z);

        /** Water flow direction at a water cell (unit-ish x component), 0 elsewhere. */
        default double flowX(int x, int z) {
            return 0.0;
        }

        default double flowZ(int x, int z) {
            return 0.0;
        }
    }

    public static double friction(int surface) {
        return switch (surface) {
            case ICE -> FRICTION_ICE;
            case BLUE_ICE -> FRICTION_BLUE_ICE;
            case SLIME -> FRICTION_SLIME;
            case WATER, BUBBLE_UP, BUBBLE_DOWN, DROP -> FRICTION_WATER;
            default -> FRICTION_LAND;
        };
    }

    public static boolean isIce(int surface) {
        return surface == ICE || surface == BLUE_ICE;
    }

    /** Top speed in blocks/tick with FORWARD held straight: the fixed point of v = v * f + thrust. */
    public static double terminalSpeed(double friction) {
        return THRUST_FORWARD / (1.0 - friction);
    }

    private final double[] xs = new double[MAX_TICKS + 1];
    private final double[] zs = new double[MAX_TICKS + 1];
    private final double[] speeds = new double[MAX_TICKS + 1];
    private int count;
    private int end;
    private boolean hitWall;

    /**
     * Runs from the current state with the inputs held for {@code ticks} ticks.
     *
     * @param yaw      degrees (vanilla: 0 = +Z, 90 = -X)
     * @param turnRate the boat's current turn rate, degrees per tick (yaw change of the last tick)
     */
    public int run(Surface s, double x, double z, double vx, double vz, double yaw, double turnRate,
                   boolean forward, boolean back, boolean left, boolean right, int ticks) {
        ticks = Math.max(0, Math.min(MAX_TICKS, ticks));
        count = 0;
        end = END_HORIZON;
        hitWall = false;
        xs[0] = x;
        zs[0] = z;
        speeds[0] = Math.sqrt(vx * vx + vz * vz);
        count = 1;
        double rot = turnRate;
        for (int t = 0; t < ticks; t++) {
            int under = surfaceUnder(s, x, z);
            if (under == BUBBLE_UP || under == BUBBLE_DOWN) {
                end = END_BUBBLE;
                break;
            }
            if (under == DROP) {
                end = END_DROP;
                break;
            }
            double f = under == WATER ? FRICTION_WATER : groundFriction(s, x, z);
            vx *= f;
            vz *= f;
            rot *= f;
            double thrust = 0.0;
            if (left) {
                rot -= 1.0;
            }
            if (right) {
                rot += 1.0;
            }
            if (right != left && !forward && !back) {
                thrust += THRUST_TURN;
            }
            yaw += rot;
            if (forward) {
                thrust += THRUST_FORWARD;
            }
            if (back) {
                thrust -= THRUST_BACK;
            }
            double rad = Math.toRadians(yaw);
            vx += -Math.sin(rad) * thrust;
            vz += Math.cos(rad) * thrust;
            if (under == WATER) {
                int cx = floor(x);
                int cz = floor(z);
                vx += s.flowX(cx, cz) * CURRENT_PUSH;
                vz += s.flowZ(cx, cz) * CURRENT_PUSH;
            }
            // per-axis move with wall checks at the leading edge of the footprint
            double nx = x + vx;
            if (s.surface(floor(nx + Math.signum(vx) * HALF_WIDTH), floor(z)) == WALL) {
                vx = 0.0;
                hitWall = true;
            } else {
                x = nx;
            }
            double nz = z + vz;
            if (s.surface(floor(x), floor(nz + Math.signum(vz) * HALF_WIDTH)) == WALL) {
                vz = 0.0;
                hitWall = true;
            } else {
                z = nz;
            }
            xs[count] = x;
            zs[count] = z;
            speeds[count] = Math.sqrt(vx * vx + vz * vz);
            count++;
        }
        return count;
    }

    private static int surfaceUnder(Surface s, double x, double z) {
        return s.surface(floor(x), floor(z));
    }

    /** Average friction of the four footprint corners (vanilla averages the blocks under the boat). */
    static double groundFriction(Surface s, double x, double z) {
        double sum = 0.0;
        int n = 0;
        for (int i = 0; i < 4; i++) {
            double cx = x + ((i & 1) == 0 ? -HALF_WIDTH : HALF_WIDTH);
            double cz = z + ((i & 2) == 0 ? -HALF_WIDTH : HALF_WIDTH);
            int code = s.surface(floor(cx), floor(cz));
            if (code == WALL || code == DROP) {
                continue;
            }
            sum += friction(code);
            n++;
        }
        return n == 0 ? FRICTION_LAND : sum / n;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public int count() {
        return count;
    }

    public double x(int i) {
        return xs[i];
    }

    public double z(int i) {
        return zs[i];
    }

    public double speed(int i) {
        return speeds[i];
    }

    public int endReason() {
        return end;
    }

    public boolean hitWall() {
        return hitWall;
    }

    /**
     * Distance (blocks) to slow from {@code speed} to {@code target} with BACK held and no FORWARD, on a surface of
     * the given friction: per tick v = v * f - 0.005.
     */
    public static double brakingDistance(double speed, double target, double friction) {
        double d = 0.0;
        double v = speed;
        for (int i = 0; i < 2000 && v > target; i++) {
            v = v * friction - THRUST_BACK;
            d += Math.max(0.0, v);
        }
        return d;
    }
}
