package com.utilitysuite.travel.movement;

import com.utilitysuite.travel.Packed;

/**
 * Fall clutch: while falling toward a landing that would kill or badly hurt, what to place, where, and on which tick,
 * so the fall is broken before (or as) the player lands. Engine free; nothing allocated per call.
 * <p>
 * The trajectory comes from the physics replica with the player's keys. Each {@link Kind} breaks a fall its own way:
 * <ul>
 *   <li>{@link Mode#PASS} (water, cobweb): the hitbox overlaps the cell at the end of a tick, which resets the fall
 *       distance (water also works poured on the landing block itself);</li>
 *   <li>{@link Mode#LAND} (powder snow, slime, hay bale): the player lands on top of it, which deals the fall damage
 *       times the block's multiplier (0 for powder snow and slime, 0.2 for hay);</li>
 *   <li>{@link Mode#CLIMB} (ladder, vine, on a wall): the feet start a tick in the cell, which resets the fall
 *       distance and slows the fall to a climb.</li>
 * </ul>
 * Kinds are tried in priority order and the first one that leaves no damage wins; otherwise the least damage wins. A
 * candidate is valid when, from the eye at the start of the use tick, the clicked face is visible and within
 * {@link #REACH}, the head turn from the current look fits in time at the given speed, and (for blocks with a
 * collision box) the body is still wholly above the cell. The executor re-plans every tick.
 */
public final class FallClutchPlanner {

    /** How a kind breaks the fall. */
    public enum Mode { PASS, LAND, CLIMB }

    /** Clutch items, in priority order (retrievable and damage-free first). */
    public enum Kind {
        WATER(Mode.PASS, 0.85, 0f),
        POWDER_SNOW(Mode.LAND, 0.9, 0f),
        COBWEB(Mode.PASS, 0.95, 0f),
        SLIME(Mode.LAND, 1.0, 0f),
        LADDER(Mode.CLIMB, 1.0, 0f),
        VINE(Mode.CLIMB, 1.0, 0f),
        HAY(Mode.LAND, 1.0, 0.2f);

        final Mode mode;
        /** PASS: the height in the cell the hitbox must reach below; LAND: the height landed on. */
        final double top;
        /** LAND: fall damage multiplier. */
        final float damageScale;

        Kind(Mode mode, double top, float damageScale) {
            this.mode = mode;
            this.top = top;
            this.damageScale = damageScale;
        }

        public int bit() {
            return 1 << ordinal();
        }
    }

    public static final Kind[] KINDS = Kind.values();
    public static final int WATER_ONLY = Kind.WATER.bit();

    public static final int HORIZON = 120;
    /** Block reach from the eye (survival), less a small margin for the tick's own motion. */
    public static final double REACH = 4.4;
    public static final double EYE = 1.62;
    /** A look this close to the aim already puts the crosshair on the face. */
    static final double ON_TARGET_DEG = 2.0;
    /** Distance kept from a face edge when aiming at it. */
    static final double FACE_MARGIN = 0.2;
    static final double BODY_HEIGHT = 1.8;
    /** Direction data values of the faces clicked. */
    public static final int UP_FACE = 1, WEST_FACE = 4;

    private static final int[] FX = {0, 0, 0, 0, -1, 1};
    private static final int[] FY = {-1, 1, 0, 0, 0, 0};
    private static final int[] FZ = {0, 0, -1, 1, 0, 0};

    private final MovementPhysics sim = new MovementPhysics();
    /** Positions at the START of each tick (index k = before tick k+1 moves). */
    private final double[] px = new double[HORIZON + 1];
    private final double[] py = new double[HORIZON + 1];
    private final double[] pz = new double[HORIZON + 1];
    private int ticks;
    private boolean lands;
    private double landY;
    private float landDamage;
    private double fallNow;

    // result
    private Kind kind;
    private long support;
    private int face;
    private long cell;
    private int useTick;
    private float damageAfter;
    private float aimYaw;
    private float aimPitch;

    /** Water bucket only (the original clutch). */
    public boolean plan(MovementPhysics now, float heading, boolean sprint, int f, int s, float health,
                        float minDamage, float lookYaw, float lookPitch, double degPerTick,
                        MovementPhysics.Voxels w) {
        return plan(now, heading, sprint, f, s, health, minDamage, lookYaw, lookPitch, degPerTick, WATER_ONLY, w);
    }

    /**
     * @param minDamage clutch only when the landing would deal at least this much (or kill)
     * @param kinds     bit set of {@link Kind#bit()} the player has at hand
     * @return true when a clutch is needed and possible; {@link #useTick()} 0 means "use now"
     */
    public boolean plan(MovementPhysics now, float heading, boolean sprint, int f, int s, float health,
                        float minDamage, float lookYaw, float lookPitch, double degPerTick, int kinds,
                        MovementPhysics.Voxels w) {
        useTick = -1;
        kind = null;
        if (kinds == 0 || now.onGround || !predict(now, heading, sprint, f, s, w)) {
            return false;
        }
        if (landDamage < minDamage && landDamage < health) {
            return false;
        }
        // a clutch must leave less than the landing would, and less than the health left
        damageAfter = Math.min(landDamage, health);
        for (Kind k : KINDS) {
            if ((kinds & k.bit()) == 0) {
                continue;
            }
            switch (k.mode) {
                case PASS -> planPass(k, lookYaw, lookPitch, degPerTick, w);
                case LAND -> planLand(k, lookYaw, lookPitch, degPerTick, w);
                case CLIMB -> planClimb(k, lookYaw, lookPitch, degPerTick, w);
            }
            if (useTick >= 0 && damageAfter <= 0f) {
                break;
            }
        }
        return useTick >= 0;
    }

    /** Water / cobweb: the landing cell (water only), then cells the hitbox overlaps on the way down, latest first. */
    private void planPass(Kind k, float lookYaw, float lookPitch, double degPerTick, MovementPhysics.Voxels w) {
        if (k == Kind.WATER) {
            // the landing cell: click the top of the block under it; vanilla resets the fall as the move ends in water
            tryCell(k, floor(px[ticks]), floor(landY + 1.0e-6), floor(pz[ticks]), ticks, 0f, false, false,
                    lookYaw, lookPitch, degPerTick, w);
        }
        // The head cell counts: at terminal speed the feet cell is always out of reach from the eye a tick earlier,
        // the head cell is not. In it at the end of tick j the fall distance resets, so only the drop from there
        // counts.
        for (int j = ticks - 1; j >= 1; j--) {
            float after = MovementPhysics.fallDamage(Math.max(0.0, py[j] - landY));
            if (after >= damageAfter) {
                continue;
            }
            int cx = floor(px[j]);
            int cz = floor(pz[j]);
            for (int cy = floor(py[j] + BODY_HEIGHT - 0.01); cy >= floor(py[j]); cy--) {
                if (py[j] >= cy + k.top || py[j] + BODY_HEIGHT <= cy) {
                    continue; // the hitbox misses the cell's active part
                }
                if (tryCell(k, cx, cy, cz, j, after, false, false, lookYaw, lookPitch, degPerTick, w)) {
                    break;
                }
            }
        }
    }

    /**
     * Powder snow / slime / hay: a block whose top the feet cross during tick j, under the feet. Highest cells first
     * (the shortest fall onto it), down to the landing cell.
     */
    private void planLand(Kind k, float lookYaw, float lookPitch, double degPerTick, MovementPhysics.Voxels w) {
        for (int j = 1; j <= ticks; j++) {
            int cx = floor(px[j]);
            int cz = floor(pz[j]);
            for (int cy = floor(py[j - 1] - k.top); cy + k.top > py[j] - 1.0e-6; cy--) {
                double fall = fallNow + Math.max(0.0, py[0] - (cy + k.top));
                float after = (float) Math.max(0.0, Math.ceil((fall - 3.0) * k.damageScale));
                if (after >= damageAfter) {
                    continue;
                }
                tryCell(k, cx, cy, cz, j, after, true, false, lookYaw, lookPitch, degPerTick, w);
            }
        }
    }

    /** Ladder / vine on a wall: the feet cell at the end of tick j (j before landing), latest first. */
    private void planClimb(Kind k, float lookYaw, float lookPitch, double degPerTick, MovementPhysics.Voxels w) {
        for (int j = ticks - 1; j >= 1; j--) {
            float after = MovementPhysics.fallDamage(Math.max(0.0, py[j] - landY));
            if (after >= damageAfter) {
                continue;
            }
            tryCell(k, floor(px[j]), floor(py[j]), floor(pz[j]), j, after, true, true, lookYaw, lookPitch,
                    degPerTick, w);
        }
    }

    /**
     * Only the landing prediction (no clutch search): fills {@link #ticksToLand()}, {@link #landDamage()} and
     * {@link #lands()}. Used by the danger feed and the option ladder. Allocation free.
     */
    public boolean predictLanding(MovementPhysics now, float heading, boolean sprint, int f, int s,
                                  MovementPhysics.Voxels w) {
        if (now.onGround) {
            lands = false;
            return false;
        }
        return predict(now, heading, sprint, f, s, w);
    }

    /** Trajectory until landing; false when it never lands within the horizon. */
    private boolean predict(MovementPhysics now, float heading, boolean sprint, int f, int s,
                            MovementPhysics.Voxels w) {
        sim.copyFrom(now);
        px[0] = sim.x;
        py[0] = sim.y;
        pz[0] = sim.z;
        fallNow = now.fallDistance;
        double fall = now.fallDistance;
        for (int t = 1; t <= HORIZON; t++) {
            double yBefore = sim.y;
            sim.tick(f, s, heading, sprint, false, w);
            px[t] = sim.x;
            py[t] = sim.y;
            pz[t] = sim.z;
            fall += Math.max(0.0, yBefore - sim.y);
            if (sim.onGround) {
                ticks = t;
                landY = sim.y;
                landDamage = MovementPhysics.fallDamage(fall);
                lands = true;
                return true;
            }
        }
        lands = false;
        return false;
    }

    /**
     * Place {@code k} into the cell, which must hold it by trajectory tick {@code inTick}: the latest start-of-tick eye
     * (before inTick) that can reach a face next to it, with the head turn done in time. The latest leaves the most
     * turn time. Takes the result when found (callers only offer cells that beat the current best).
     *
     * @param clearAbove the block has a collision box: the body must be wholly above the cell when it is placed
     * @param wallOnly   only side faces (ladders and vines hang on walls)
     */
    private boolean tryCell(Kind k, int cx, int cy, int cz, int inTick, float after, boolean clearAbove,
                            boolean wallOnly, float lookYaw, float lookPitch, double degPerTick,
                            MovementPhysics.Voxels w) {
        if (w.solid(cx, cy, cz)) {
            return false;
        }
        for (int t = inTick - 1; t >= 0; t--) {
            double ex = px[t];
            double ey = py[t] + EYE;
            double ez = pz[t];
            if (ey - (cy + 0.5) > REACH + 0.9) {
                return false; // earlier ticks are higher still: out of reach from here on
            }
            if (clearAbove && py[t] < cy + 1.0) {
                continue;
            }
            for (int d = wallOnly ? 2 : 0; d < 6; d++) {
                int nx = cx - FX[d];
                int ny = cy - FY[d];
                int nz = cz - FZ[d];
                if (!w.fullBlock(nx, ny, nz)) {
                    continue; // a slab or fence is not where its face would be drawn: the click would miss
                }
                double hx = FX[d] != 0 ? cx + (FX[d] > 0 ? 0.0 : 1.0) : aimOnFace(cx, ex);
                double hy = FY[d] != 0 ? cy + (FY[d] > 0 ? 0.0 : 1.0) : aimOnFace(cy, ey);
                double hz = FZ[d] != 0 ? cz + (FZ[d] > 0 ? 0.0 : 1.0) : aimOnFace(cz, ez);
                if (FX[d] * (ex - hx) + FY[d] * (ey - hy) + FZ[d] * (ez - hz) <= 0.02) {
                    continue; // face not visible from this eye
                }
                double dx = hx - ex;
                double dy = hy - ey;
                double dz = hz - ez;
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > REACH) {
                    continue;
                }
                double horiz = Math.sqrt(dx * dx + dz * dz);
                // straight down: keep the yaw (atan2 of a near-zero offset would swing the head for nothing)
                float yaw = horiz < 0.35 ? lookYaw : (float) Math.toDegrees(Math.atan2(-dx, dz));
                float pitch = (float) Math.toDegrees(-Math.atan2(dy, horiz));
                double angle = BlockAssistPlanner.angle(lookYaw, lookPitch, yaw, pitch);
                if (turnTicks(angle, degPerTick) > t) {
                    continue; // the head cannot get there in time
                }
                kind = k;
                support = Packed.pack(nx, ny, nz);
                face = d;
                cell = Packed.pack(cx, cy, cz);
                useTick = t;
                damageAfter = after;
                aimYaw = yaw;
                aimPitch = pitch;
                return true;
            }
        }
        return false;
    }

    /**
     * Ticks the head needs to turn {@code angle}: none when already on it (within the pick tolerance), one for a
     * small re-aim, else the turn at the planned rate plus one for the curve to ease in and out.
     */
    static int turnTicks(double angle, double degPerTick) {
        double rate = Math.max(1.0e-3, degPerTick);
        if (angle <= ON_TARGET_DEG) {
            return 0;
        }
        return angle <= rate ? 1 : (int) Math.ceil(angle / rate) + 1;
    }

    /**
     * Where on a face to aim, along one of its in-plane axes: the point nearest the eye, kept off the edges. Straight
     * down onto a block below keeps the yaw; on a wall beside a fall the ray is less grazing than to the middle.
     */
    public static double aimOnFace(int min, double eye) {
        return Math.max(min + FACE_MARGIN, Math.min(min + 1.0 - FACE_MARGIN, eye));
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    // ---- result -------------------------------------------------------------------------------------------------------

    /** What to place (null when nothing was found). */
    public Kind kind() {
        return kind;
    }

    /** Block to click. */
    public long support() {
        return support;
    }

    /** Face of {@link #support()} (Direction 3D data value). */
    public int face() {
        return face;
    }

    /** Cell the clutch item ends up in. */
    public long cell() {
        return cell;
    }

    /** Ticks from now until the item must be used (0 = this tick). */
    public int useTick() {
        return useTick;
    }

    /** Fall damage left with the clutch. */
    public float damageAfter() {
        return damageAfter;
    }

    /** Fall damage without it. */
    public float landDamage() {
        return landDamage;
    }

    /** Ticks until landing without it. */
    public int ticksToLand() {
        return ticks;
    }

    public boolean lands() {
        return lands;
    }

    /** Predicted landing position (valid when {@link #lands()}). */
    public double landX() {
        return px[ticks];
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return pz[ticks];
    }

    /**
     * Fills {@code out[0..ticksToLand()]} with the feet height above the predicted landing at the start of each tick
     * (valid when {@link #lands()}; the {@link ClutchChain} input). Allocation free.
     */
    public void heights(double[] out) {
        for (int t = 0; t <= ticks; t++) {
            out[t] = Math.max(0.0, py[t] - landY);
        }
    }

    public float aimYaw() {
        return aimYaw;
    }

    public float aimPitch() {
        return aimPitch;
    }
}
