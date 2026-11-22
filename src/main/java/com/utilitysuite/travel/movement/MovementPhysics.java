package com.utilitysuite.travel.movement;

/**
 * Engine-free replica of vanilla 1.21.1 player movement for prediction: walking, sprinting, sprint-jump boost, air
 * control, gravity, drag and block collision against a {@link Voxels} grid (full-cube approximation, plus a
 * one-block-high hitbox clamp for slabs/stairs is left to the caller via {@link Voxels#solid}). Allocation free; one
 * instance is a mutable simulated player that can be {@link #copyFrom copied} and stepped ahead.
 * <p>
 * Per tick, matching {@code LivingEntity.aiStep/travel}:
 * <ol>
 *   <li>input scaled by 0.98; a jump on the ground sets vy = 0.42 and, when sprinting, adds 0.2 along the yaw;</li>
 *   <li>acceleration: on ground {@code speed * 0.216 / f^3} (f = block slipperiness, 0.6 for most blocks; speed 0.1,
 *       x1.3 sprinting), in the air 0.02 (0.026 sprinting), input normalised when longer than 1;</li>
 *   <li>move with collision (Y first, then X and Z, like vanilla);</li>
 *   <li>vy = (vy - 0.08) * 0.98; horizontal *= f * 0.91 on ground, 0.91 in the air.</li>
 * </ol>
 * Not modelled: step-up onto slabs, ladders, water, soul sand, effects (speed/jump boost are parameters).
 */
public final class MovementPhysics {

    /** Collision lookup at integer block coordinates. */
    @FunctionalInterface
    public interface Voxels {
        boolean solid(int x, int y, int z);

        /** A full cube (every face can be clicked where it is drawn); slabs, stairs, fences are solid but not full. */
        default boolean fullBlock(int x, int y, int z) {
            return solid(x, y, z);
        }

        // ---- exact shapes (contract of the 2026-11-22 control model; cubes stay the fast path) ----------------------

        /**
         * False when every solid cell of this grid is a unit cube: collision then never asks for boxes. A grid that
         * returns true must answer {@link #boxCount} and {@link #box} for every cell.
         */
        default boolean shaped() {
            return false;
        }

        /** Number of collision boxes of the block in this cell (0 = nothing to collide with). */
        default int boxCount(int x, int y, int z) {
            return solid(x, y, z) ? 1 : 0;
        }

        /**
         * Writes box {@code i} of the cell into {@code out[off .. off + 5]} as block-local minX, minY, minZ, maxX,
         * maxY, maxZ. A box may rise above its cell (fences and walls: maxY 1.5), so collision also scans one cell
         * below the body. Allocation free.
         */
        default void box(int x, int y, int z, int i, float[] out, int off) {
            out[off] = 0f;
            out[off + 1] = 0f;
            out[off + 2] = 0f;
            out[off + 3] = 1f;
            out[off + 4] = 1f;
            out[off + 5] = 1f;
        }

        /** What the block in this cell does to a body: one of the {@code SURFACE_*} constants. */
        default byte surface(int x, int y, int z) {
            return solid(x, y, z) ? SURFACE_NORMAL : SURFACE_NONE;
        }

        /** Slipperiness of the block in this cell when stood on (0.6 for most blocks, 0.98 ice). */
        default float slipperiness(int x, int y, int z) {
            return (float) DEFAULT_SLIPPERINESS;
        }
    }

    /** Air or a block with no effect on a body. */
    public static final byte SURFACE_NONE = 0;
    public static final byte SURFACE_NORMAL = 1;
    public static final byte SURFACE_ICE = 2;
    /** Bounces unless the body sneaks. */
    public static final byte SURFACE_SLIME = 3;
    public static final byte SURFACE_HONEY = 4;
    public static final byte SURFACE_SOUL_SAND = 5;
    /** Ladder, vine, scaffolding-free climbables. */
    public static final byte SURFACE_CLIMBABLE = 6;
    /** Still water at least one block deep: a damage-free landing. */
    public static final byte SURFACE_WATER = 7;
    /** Cactus, berry bush, magma, fire, lava: never a landing. */
    public static final byte SURFACE_HARM = 8;
    /** Cobweb, powder snow: the body gets stuck. */
    public static final byte SURFACE_STUCK = 9;
    /**
     * A shape that depends on position or on the entity (offset blocks, scaffolding), or anything else the replica
     * does not model. Fail-safe rule: a plan whose roll touches such a cell is never committed to.
     */
    public static final byte SURFACE_UNMODELLED = 10;

    public static final double HALF_WIDTH = 0.3;
    public static final double HEIGHT = 1.8;
    public static final double GRAVITY = 0.08;
    public static final double DRAG = 0.98;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final double AIR_FRICTION = 0.91;
    public static final double DEFAULT_SLIPPERINESS = 0.6;
    public static final double BASE_SPEED = 0.1;
    public static final double INPUT_SCALE = 0.98;
    private static final double EPS = 1.0e-7;

    public double x, y, z;
    public double vx, vy, vz;
    public boolean onGround;
    /** Fall distance accumulated since leaving the ground (for fall-damage estimates). */
    public double fallDistance;
    /** Movement speed attribute (0.1 normally; Speed I adds 20 %). */
    public double speed = BASE_SPEED;
    /** Extra jump velocity from Jump Boost (0.1 per level). */
    public double jumpBoost;
    public double slipperiness = DEFAULT_SLIPPERINESS;
    /** True when the last tick bumped into a wall horizontally. */
    public boolean horizontalCollision;

    public MovementPhysics copyFrom(MovementPhysics o) {
        x = o.x;
        y = o.y;
        z = o.z;
        vx = o.vx;
        vy = o.vy;
        vz = o.vz;
        onGround = o.onGround;
        fallDistance = o.fallDistance;
        speed = o.speed;
        jumpBoost = o.jumpBoost;
        slipperiness = o.slipperiness;
        horizontalCollision = o.horizontalCollision;
        return this;
    }

    public MovementPhysics set(double x, double y, double z, double vx, double vy, double vz, boolean onGround) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.vx = vx;
        this.vy = vy;
        this.vz = vz;
        this.onGround = onGround;
        this.fallDistance = 0;
        return this;
    }

    /**
     * Advances one tick with the sneak key state. Contract of the 2026-11-22 control model: while sneaking on the
     * ground the input is scaled by 0.3 and the body backs off from edges like vanilla (not on the touchdown tick).
     * Until the shape-physics work lands this ignores {@code sneak} and behaves like the six-argument tick.
     */
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, boolean sneak, Voxels w) {
        tick(forward, strafe, yawDeg, sprint, jump, w);
    }

    /**
     * Advances one tick.
     *
     * @param forward +1 forward key, -1 back, 0 none
     * @param strafe  +1 left key, -1 right, 0 none
     * @param yawDeg  Minecraft yaw (0 = +Z, 90 = -X)
     * @param sprint  sprinting (only effective while moving forward)
     * @param jump    jump key held
     */
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, Voxels w) {
        boolean sprinting = sprint && forward > 0;
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        if (jump && onGround) {
            vy = JUMP_VELOCITY + jumpBoost;
            if (sprinting) {
                vx -= sin * SPRINT_JUMP_BOOST;
                vz += cos * SPRINT_JUMP_BOOST;
            }
        }
        double friction = onGround ? slipperiness * AIR_FRICTION : AIR_FRICTION;
        double accel;
        if (onGround) {
            double s = sprinting ? speed * 1.3 : speed;
            accel = s * (0.21600002 / (slipperiness * slipperiness * slipperiness));
        } else {
            accel = sprinting ? 0.025999999 : 0.02;
        }
        double ix = strafe * INPUT_SCALE;
        double iz = forward * INPUT_SCALE;
        double len2 = ix * ix + iz * iz;
        if (len2 > EPS) {
            if (len2 > 1.0) {
                double inv = 1.0 / Math.sqrt(len2);
                ix *= inv;
                iz *= inv;
            }
            ix *= accel;
            iz *= accel;
            vx += ix * cos - iz * sin;
            vz += iz * cos + ix * sin;
        }
        move(w);
        vy = (vy - GRAVITY) * DRAG;
        vx *= friction;
        vz *= friction;
    }

    /** Vanilla order: Y, then X, then Z, each clipped against solid voxels of the player box. */
    private void move(Voxels w) {
        double dy = clipY(vy, w);
        boolean landed = vy < 0 && dy != vy;
        boolean hitHead = vy > 0 && dy != vy;
        y += dy;
        double dx = clipX(vx, w);
        x += dx;
        double dz = clipZ(vz, w);
        z += dz;
        horizontalCollision = dx != vx || dz != vz;
        if (dx != vx) {
            vx = 0;
        }
        if (dz != vz) {
            vz = 0;
        }
        if (landed || hitHead) {
            vy = 0;
        }
        if (landed) {
            onGround = true;
        } else if (dy < 0 || !standing(w)) {
            onGround = false;
        }
        if (onGround) {
            fallDistance = 0;
        } else if (dy < 0) {
            fallDistance -= dy;
        }
    }

    /**
     * True when a block is under the player's feet right now. Differs from {@link #onGround} on the tick the player
     * steps off an edge: vanilla (and this replica) resolves Y before X/Z, so that tick still reports onGround.
     */
    public boolean supported(Voxels w) {
        return standing(w);
    }

    private boolean standing(Voxels w) {
        int by = floor(y - 0.01);
        return anySolid(x - HALF_WIDTH, x + HALF_WIDTH, by, by, z - HALF_WIDTH, z + HALF_WIDTH, w);
    }

    private double clipY(double d, Voxels w) {
        if (d == 0) {
            return 0;
        }
        int x0 = floor(x - HALF_WIDTH + EPS);
        int x1 = floor(x + HALF_WIDTH - EPS);
        int z0 = floor(z - HALF_WIDTH + EPS);
        int z1 = floor(z + HALF_WIDTH - EPS);
        if (d < 0) {
            int from = floor(y - EPS);
            int to = floor(y + d);
            for (int by = from; by >= to; by--) {
                if (layerSolid(x0, x1, by, z0, z1, w)) {
                    return Math.max(d, by + 1 - y);
                }
            }
        } else {
            int from = floor(y + HEIGHT + EPS);
            int to = floor(y + HEIGHT + d);
            for (int by = from; by <= to; by++) {
                if (layerSolid(x0, x1, by, z0, z1, w)) {
                    return Math.min(d, by - (y + HEIGHT));
                }
            }
        }
        return d;
    }

    private double clipX(double d, Voxels w) {
        if (d == 0) {
            return 0;
        }
        int y0 = floor(y + EPS);
        int y1 = floor(y + HEIGHT - EPS);
        int z0 = floor(z - HALF_WIDTH + EPS);
        int z1 = floor(z + HALF_WIDTH - EPS);
        if (d > 0) {
            int from = floor(x + HALF_WIDTH + EPS);
            int to = floor(x + HALF_WIDTH + d);
            for (int bx = from; bx <= to; bx++) {
                if (columnSolid(bx, y0, y1, z0, z1, true, w)) {
                    return Math.min(d, bx - (x + HALF_WIDTH));
                }
            }
        } else {
            int from = floor(x - HALF_WIDTH - EPS);
            int to = floor(x - HALF_WIDTH + d);
            for (int bx = from; bx >= to; bx--) {
                if (columnSolid(bx, y0, y1, z0, z1, true, w)) {
                    return Math.max(d, bx + 1 - (x - HALF_WIDTH));
                }
            }
        }
        return d;
    }

    private double clipZ(double d, Voxels w) {
        if (d == 0) {
            return 0;
        }
        int y0 = floor(y + EPS);
        int y1 = floor(y + HEIGHT - EPS);
        int x0 = floor(x - HALF_WIDTH + EPS);
        int x1 = floor(x + HALF_WIDTH - EPS);
        if (d > 0) {
            int from = floor(z + HALF_WIDTH + EPS);
            int to = floor(z + HALF_WIDTH + d);
            for (int bz = from; bz <= to; bz++) {
                if (columnSolid(bz, y0, y1, x0, x1, false, w)) {
                    return Math.min(d, bz - (z + HALF_WIDTH));
                }
            }
        } else {
            int from = floor(z - HALF_WIDTH - EPS);
            int to = floor(z - HALF_WIDTH + d);
            for (int bz = from; bz >= to; bz--) {
                if (columnSolid(bz, y0, y1, x0, x1, false, w)) {
                    return Math.max(d, bz + 1 - (z - HALF_WIDTH));
                }
            }
        }
        return d;
    }

    private static boolean layerSolid(int x0, int x1, int by, int z0, int z1, Voxels w) {
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (w.solid(bx, by, bz)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** A wall slice: fixed {@code fixed} coordinate on the moving axis, spanning y0..y1 and a0..a1 on the other. */
    private static boolean columnSolid(int fixed, int y0, int y1, int a0, int a1, boolean xAxis, Voxels w) {
        for (int by = y0; by <= y1; by++) {
            for (int a = a0; a <= a1; a++) {
                if (xAxis ? w.solid(fixed, by, a) : w.solid(a, by, fixed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean anySolid(double minX, double maxX, int y0, int y1, double minZ, double maxZ, Voxels w) {
        int x0 = floor(minX + EPS);
        int x1 = floor(maxX - EPS);
        int z0 = floor(minZ + EPS);
        int z1 = floor(maxZ - EPS);
        for (int by = y0; by <= y1; by++) {
            if (layerSolid(x0, x1, by, z0, z1, w)) {
                return true;
            }
        }
        return false;
    }

    static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** Vanilla fall damage for a fall distance: ceil(distance - 3) half-hearts (no armor or effects). */
    public static float fallDamage(double fallDistance) {
        return (float) Math.max(0, Math.ceil(fallDistance - 3.0));
    }
}
