package com.utilitysuite.travel.movement;

/**
 * Engine-free replica of vanilla 1.21.1 player movement for prediction: walking, sprinting, sprint-jump boost, air
 * control, gravity, drag and block collision against a {@link Voxels} grid. Allocation free; one instance is a mutable
 * simulated player that can be {@link #copyFrom copied} and stepped ahead.
 * <p>
 * Per tick, matching {@code LivingEntity.aiStep/travel}:
 * <ol>
 *   <li>input scaled by 0.98; a jump on the ground sets vy = 0.42 and, when sprinting, adds 0.2 along the yaw;</li>
 *   <li>acceleration: on ground {@code speed * 0.216 / f^3} (f = block slipperiness, 0.6 for most blocks; speed 0.1,
 *       x1.3 sprinting), in the air 0.02 (0.026 sprinting), input normalised when longer than 1;</li>
 *   <li>move with collision (Y first, then X and Z);</li>
 *   <li>vy = (vy - 0.08) * 0.98; horizontal *= f * 0.91 on ground, 0.91 in the air.</li>
 * </ol>
 * <b>Two paths.</b> The <i>cube path</i> runs when the grid is not {@link Voxels#shaped() shaped} and the body neither
 * sneaks nor is crouched: every solid cell is a unit cube, X is resolved before Z, and the results are exactly those of
 * the replica before the shape work (the benchmarks and every planner bound rely on it). Everything else takes the
 * <i>full path</i>, written from the 1.21.1 sources ({@code Entity.move/collide}, {@code Player.maybeBackOffFromEdge},
 * {@code LocalPlayer.aiStep}, {@code LivingEntity.aiStep/travel}):
 * <ul>
 *   <li>exact collision boxes (a box may rise to 1.5 above its cell, so one cell below the body is scanned too), the
 *       vanilla axis order (Z before X when |dz| > |dx|) and {@code Mth.equal} for the wall-contact flags;</li>
 *   <li>step-up of 0.6 onto slabs and stairs ({@code Entity.collide} candidate heights);</li>
 *   <li>sneak: the edge back-off with the real shapes and the step height, the 0.3 input scale and the 1.5-high
 *       hitbox one tick after the key (vanilla reads the crouch state before it reads the key);</li>
 *   <li>sprint loss on a wall contact that is not minor, back on the next tick while the sprint input stays on;</li>
 *   <li>slipperiness of the block under the feet, read every tick from {@link Voxels#slipperiness};</li>
 *   <li>jump delay: a held jump re-jumps only 10 ticks after the last jump, a fresh press at once.</li>
 * </ul>
 * Not modelled: slime bounce, honey and soul sand factors, climbables, water, cobwebs, effects other than the speed and
 * jump-boost parameters.
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
    /** Hitbox height in the crouching pose. */
    public static final double CROUCH_HEIGHT = 1.5;
    public static final double GRAVITY = 0.08;
    public static final double DRAG = 0.98;
    public static final double JUMP_VELOCITY = 0.42;
    public static final double SPRINT_JUMP_BOOST = 0.2;
    public static final double AIR_FRICTION = 0.91;
    public static final double DEFAULT_SLIPPERINESS = 0.6;
    public static final double BASE_SPEED = 0.1;
    public static final double INPUT_SCALE = 0.98;
    /** Sneaking-speed attribute (default 0.3): movement input scale while crouched. */
    public static final double SNEAK_INPUT_SCALE = 0.3;
    /** Player step-height attribute (0.6), as the float vanilla reads. */
    public static final float STEP_HEIGHT = 0.6f;
    /** Ticks after a jump before a held jump key jumps again ({@code LivingEntity.noJumpDelay}). */
    public static final int JUMP_DELAY_TICKS = 10;
    private static final double EPS = 1.0e-7;
    /** {@code Mth.equal} tolerance (a float literal in vanilla). */
    private static final double MTH_EQUAL = 1.0E-5F;
    /** {@code Player.canFallAtLeast} extra depth (a float literal in vanilla). */
    private static final double FALL_PROBE = 1.0E-5F;
    private static final double BACK_OFF_STEP = 0.05;
    /** {@code LocalPlayer.isHorizontalCollisionMinor}: angle under which a wall contact keeps the sprint. */
    private static final double MINOR_ANGLE = 0.13962634F;
    private static final int MAX_STEP_CANDIDATES = 16;

    public double x, y, z;
    public double vx, vy, vz;
    public boolean onGround;
    /** Fall distance accumulated since leaving the ground (for fall-damage estimates). */
    public double fallDistance;
    /** Movement speed attribute (0.1 normally; Speed I adds 20 %). */
    public double speed = BASE_SPEED;
    /** Extra jump velocity from Jump Boost (0.1 per level). */
    public double jumpBoost;
    /**
     * Slipperiness used by the next tick. The cube path keeps what the caller set; the full path overwrites it every
     * ground tick with the block under the feet.
     */
    public double slipperiness = DEFAULT_SLIPPERINESS;
    /** True when the last tick bumped into a wall horizontally. */
    public boolean horizontalCollision;
    /**
     * Crouched pose (1.5-high hitbox, 0.3 input scale) for the next tick: the sneak input of the last tick, or kept
     * when the body cannot stand up.
     */
    public boolean crouching;
    /** Vanilla {@code noJumpDelay}: ticks until a held jump key may jump again. */
    public int jumpDelay;
    /** The last move hit a wall at more than the minor angle: the next tick does not sprint. */
    public boolean sprintBlocked;
    /** The last tick was a sprinting tick (full path; the cube path reports the sprint input). */
    public boolean sprinting;

    // scratch, reused every tick
    private final float[] box = new float[6];
    private final float[] stepHeights = new float[MAX_STEP_CANDIDATES];
    private double height = HEIGHT;
    private double rx, ry, rz;

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
        crouching = o.crouching;
        jumpDelay = o.jumpDelay;
        sprintBlocked = o.sprintBlocked;
        sprinting = o.sprinting;
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
     * Advances one tick.
     *
     * @param forward +1 forward key, -1 back, 0 none
     * @param strafe  +1 left key, -1 right, 0 none
     * @param yawDeg  Minecraft yaw (0 = +Z, 90 = -X)
     * @param sprint  sprinting (only effective while moving forward)
     * @param jump    jump key held
     */
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, Voxels w) {
        tick(forward, strafe, yawDeg, sprint, jump, false, w);
    }

    /**
     * Advances one tick with the sneak key state ({@code Input.shiftKeyDown} for this tick). The edge back-off follows
     * the key on the same tick; the 0.3 input scale and the 1.5-high hitbox follow it one tick later, like vanilla.
     */
    public void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, boolean sneak, Voxels w) {
        if (!sneak && !crouching && !w.shaped()) {
            tickCubes(forward, strafe, yawDeg, sprint, jump, w);
        } else {
            tickFull(forward, strafe, yawDeg, sprint, jump, sneak, w);
        }
    }

    // ---- cube path (unchanged replica) ------------------------------------------------------------------------------

    private void tickCubes(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, Voxels w) {
        boolean sprintingNow = sprint && forward > 0;
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        if (jumpDelay > 0) {
            jumpDelay--;
        }
        if (jump && onGround) {
            vy = JUMP_VELOCITY + jumpBoost;
            if (sprintingNow) {
                vx -= sin * SPRINT_JUMP_BOOST;
                vz += cos * SPRINT_JUMP_BOOST;
            }
            jumpDelay = JUMP_DELAY_TICKS;
        } else if (!jump) {
            jumpDelay = 0;
        }
        double friction = onGround ? slipperiness * AIR_FRICTION : AIR_FRICTION;
        double accel;
        if (onGround) {
            double s = sprintingNow ? speed * 1.3 : speed;
            accel = s * (0.21600002 / (slipperiness * slipperiness * slipperiness));
        } else {
            accel = sprintingNow ? 0.025999999 : 0.02;
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
        moveCubes(w);
        vy = (vy - GRAVITY) * DRAG;
        vx *= friction;
        vz *= friction;
        sprinting = sprintingNow;
        sprintBlocked = false;
    }

    /** Cube path: Y, then X, then Z, each clipped against solid voxels of the player box. */
    private void moveCubes(Voxels w) {
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
        } else if (dy < 0 || !standingCubes(w)) {
            onGround = false;
        }
        if (onGround) {
            fallDistance = 0;
        } else if (dy < 0) {
            fallDistance -= dy;
        }
    }

    // ---- full path ---------------------------------------------------------------------------------------------------

    private void tickFull(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, boolean sneak,
                          Voxels w) {
        height = crouching ? CROUCH_HEIGHT : HEIGHT;
        double yaw = Math.toRadians(yawDeg);
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);
        // LivingEntity.aiStep: the jump delay counts down first
        if (jumpDelay > 0) {
            jumpDelay--;
        }
        // LocalPlayer.aiStep: a wall hit of the last move stops the sprint; restarting it needs a forward impulse of
        // at least 0.8, which a crouched body (0.3) does not have
        boolean sprintingNow = sprint && forward > 0 && !sprintBlocked && (sprinting || !crouching);
        if (jump) {
            if (onGround && jumpDelay == 0) {
                vy = JUMP_VELOCITY + jumpBoost;
                if (sprintingNow) {
                    vx -= sin * SPRINT_JUMP_BOOST;
                    vz += cos * SPRINT_JUMP_BOOST;
                }
                jumpDelay = JUMP_DELAY_TICKS;
            }
        } else {
            jumpDelay = 0;
        }
        if (onGround) {
            slipperiness = readSlipperiness(w);
        }
        double friction = onGround ? slipperiness * AIR_FRICTION : AIR_FRICTION;
        double accel;
        if (onGround) {
            double s = sprintingNow ? speed * 1.3 : speed;
            accel = s * (0.21600002 / (slipperiness * slipperiness * slipperiness));
        } else {
            accel = sprintingNow ? 0.025999999 : 0.02;
        }
        double scale = crouching ? SNEAK_INPUT_SCALE * INPUT_SCALE : INPUT_SCALE;
        double xxa = strafe * scale;
        double zza = forward * scale;
        double ix = xxa;
        double iz = zza;
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
        moveFull(sneak, w);
        sprintBlocked = horizontalCollision && !minorCollision(xxa, zza, sin, cos);
        sprinting = sprintingNow;
        vy = (vy - GRAVITY) * DRAG;
        vx *= friction;
        vz *= friction;
        // Player.updatePlayerPose (after aiStep): crouch on the key, or stay crouched where standing does not fit
        if (sneak) {
            crouching = true;
        } else if (crouching) {
            crouching = boxBlocked(x - HALF_WIDTH + EPS, y + EPS, z - HALF_WIDTH + EPS,
                    x + HALF_WIDTH - EPS, y + HEIGHT - EPS, z + HALF_WIDTH - EPS, w);
        }
        height = HEIGHT;
    }

    /** Entity.move for MoverType.SELF: back-off, collide (with step-up), flags, fall distance. */
    private void moveFull(boolean sneak, Voxels w) {
        double mx = vx;
        double my = vy;
        double mz = vz;
        if (sneak && !(my > 0.0) && aboveGround(w)) {
            backOff(mx, mz, w);
            mx = rx;
            mz = rz;
        }
        collideWithStep(mx, my, mz, w);
        double dx = rx;
        double dy = ry;
        double dz = rz;
        if (dx * dx + dy * dy + dz * dz > EPS) {
            x += dx;
            y += dy;
            z += dz;
        }
        boolean hitX = Math.abs(dx - mx) >= MTH_EQUAL;
        boolean hitZ = Math.abs(dz - mz) >= MTH_EQUAL;
        horizontalCollision = hitX || hitZ;
        boolean vertical = my != dy;
        onGround = vertical && my < 0.0;
        if (hitX) {
            vx = 0;
        }
        if (hitZ) {
            vz = 0;
        }
        if (vertical) {
            vy = 0; // Block.updateEntityAfterFallOn (slime bounce is not modelled)
        }
        if (onGround) {
            fallDistance = 0;
        } else if (dy < 0) {
            fallDistance -= dy;
        }
    }

    /** {@code Player.isAboveGround}: on the ground, or a short fall that already has ground within the step height. */
    private boolean aboveGround(Voxels w) {
        if (onGround) {
            return true;
        }
        float fd = (float) fallDistance;
        return fd < STEP_HEIGHT && !canFallAtLeast(0.0, 0.0, STEP_HEIGHT - fd, w);
    }

    /** {@code Player.maybeBackOffFromEdge}: shortens the move in 0.05 steps until ground stays under the body. */
    private void backOff(double d0, double d1, Voxels w) {
        double d3 = Math.signum(d0) * BACK_OFF_STEP;
        double d4 = Math.signum(d1) * BACK_OFF_STEP;
        while (d0 != 0.0 && canFallAtLeast(d0, 0.0, STEP_HEIGHT, w)) {
            if (Math.abs(d0) <= BACK_OFF_STEP) {
                d0 = 0.0;
                break;
            }
            d0 -= d3;
        }
        while (d1 != 0.0 && canFallAtLeast(0.0, d1, STEP_HEIGHT, w)) {
            if (Math.abs(d1) <= BACK_OFF_STEP) {
                d1 = 0.0;
                break;
            }
            d1 -= d4;
        }
        while (d0 != 0.0 && d1 != 0.0 && canFallAtLeast(d0, d1, STEP_HEIGHT, w)) {
            if (Math.abs(d0) <= BACK_OFF_STEP) {
                d0 = 0.0;
            } else {
                d0 -= d3;
            }
            if (Math.abs(d1) <= BACK_OFF_STEP) {
                d1 = 0.0;
            } else {
                d1 -= d4;
            }
        }
        rx = d0;
        rz = d1;
    }

    /** {@code Player.canFallAtLeast}: nothing collides in the slab {@code distance} deep under the shifted feet. */
    private boolean canFallAtLeast(double ox, double oz, float distance, Voxels w) {
        return !boxBlocked(x - HALF_WIDTH + ox, y - (double) distance - FALL_PROBE, z - HALF_WIDTH + oz,
                x + HALF_WIDTH + ox, y, z + HALF_WIDTH + oz, w);
    }

    /** {@code LocalPlayer.isHorizontalCollisionMinor}: the move still runs within 8 degrees of the input direction. */
    private boolean minorCollision(double xxa, double zza, double sin, double cos) {
        double d2 = xxa * cos - zza * sin;
        double d3 = zza * cos + xxa * sin;
        double d4 = d2 * d2 + d3 * d3;
        double d5 = rx * rx + rz * rz;
        if (d4 < 1.0E-5F || d5 < 1.0E-5F) {
            return false;
        }
        double d6 = d2 * rx + d3 * rz;
        return Math.acos(d6 / Math.sqrt(d4 * d5)) < MINOR_ANGLE;
    }

    /** Slipperiness of the block that affects movement: {@code getOnPos(0.500001)} under the supporting block. */
    private double readSlipperiness(Voxels w) {
        int sy = floor(y - (double) 0.500001F);
        // Entity.checkSupportingBlock: the block whose shape touches the 1e-6 slab under the feet, nearest the body
        int bestX = floor(x);
        int bestZ = floor(z);
        double best = Double.MAX_VALUE;
        double minX = x - HALF_WIDTH;
        double maxX = x + HALF_WIDTH;
        double minZ = z - HALF_WIDTH;
        double maxZ = z + HALF_WIDTH;
        double q = y - 1.0e-6;
        int x0 = floor(minX);
        int x1 = floor(maxX);
        int z0 = floor(minZ);
        int z1 = floor(maxZ);
        int top = floor(q);
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = top - 1; by <= top; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int count = w.boxCount(bx, by, bz);
                    if (count == 0 || !cellTouches(bx, by, bz, count, minX, q, minZ, maxX, y, maxZ, w)) {
                        continue;
                    }
                    double ddx = bx + 0.5 - x;
                    double ddy = by + 0.5 - y;
                    double ddz = bz + 0.5 - z;
                    double d = ddx * ddx + ddy * ddy + ddz * ddz;
                    if (d < best) {
                        best = d;
                        bestX = bx;
                        bestZ = bz;
                    }
                }
            }
        }
        return w.slipperiness(bestX, sy, bestZ);
    }

    /**
     * {@code Entity.collide}: plain collision, then, when on the ground (or landing this move) and blocked
     * horizontally, the step-up candidates of up to {@link #STEP_HEIGHT}; the first that gets further wins.
     * Result in {@link #rx}, {@link #ry}, {@link #rz}.
     */
    private void collideWithStep(double mx, double my, double mz, Voxels w) {
        collide(mx, my, mz, x, y, z, w);
        boolean blockedX = mx != rx;
        boolean blockedZ = mz != rz;
        boolean landing = my != ry && my < 0.0;
        if (!(landing || onGround) || !(blockedX || blockedZ)) {
            return;
        }
        double ox = rx;
        double oy = ry;
        double oz = rz;
        double baseY = landing ? y + oy : y;
        int n = collectStepHeights(mx, mz, baseY, !landing, (float) oy, w);
        double flat = ox * ox + oz * oz;
        for (int i = 0; i < n; i++) {
            collide(mx, stepHeights[i], mz, x, baseY, z, w);
            if (rx * rx + rz * rz > flat) {
                ry += baseY - y;
                return;
            }
        }
        rx = ox;
        ry = oy;
        rz = oz;
    }

    /** {@code Entity.collectCandidateStepUpHeights}: sorted distinct box heights in [0, step] above {@code baseY}. */
    private int collectStepHeights(double mx, double mz, double baseY, boolean probeDown, float skip, Voxels w) {
        double minX = x - HALF_WIDTH + Math.min(mx, 0.0);
        double maxX = x + HALF_WIDTH + Math.max(mx, 0.0);
        double minZ = z - HALF_WIDTH + Math.min(mz, 0.0);
        double maxZ = z + HALF_WIDTH + Math.max(mz, 0.0);
        double minY = probeDown ? baseY - (double) 1.0E-5F : baseY;
        double maxY = baseY + height + STEP_HEIGHT;
        int n = 0;
        int cx0 = floor(minX);
        int cx1 = floor(maxX);
        int cz0 = floor(minZ);
        int cz1 = floor(maxZ);
        int cy0 = floor(minY) - 1;
        int cy1 = floor(maxY);
        for (int bx = cx0; bx <= cx1; bx++) {
            for (int by = cy0; by <= cy1; by++) {
                for (int bz = cz0; bz <= cz1; bz++) {
                    int count = w.boxCount(bx, by, bz);
                    if (count == 0 || !cellTouches(bx, by, bz, count, minX, minY, minZ, maxX, maxY, maxZ, w)) {
                        continue;
                    }
                    for (int i = 0; i < count; i++) {
                        w.box(bx, by, bz, i, box, 0);
                        n = addHeight((float) (by + (double) box[1] - baseY), skip, n);
                        n = addHeight((float) (by + (double) box[4] - baseY), skip, n);
                    }
                }
            }
        }
        return n;
    }

    private int addHeight(float h, float skip, int n) {
        if (h < 0.0F || h == skip || h > STEP_HEIGHT || n >= MAX_STEP_CANDIDATES) {
            return n;
        }
        int at = n;
        for (int i = 0; i < n; i++) {
            if (stepHeights[i] == h) {
                return n;
            }
            if (stepHeights[i] > h) {
                at = i;
                break;
            }
        }
        for (int i = n; i > at; i--) {
            stepHeights[i] = stepHeights[i - 1];
        }
        stepHeights[at] = h;
        return n + 1;
    }

    /** True when any box of the cell overlaps the open box (vanilla only collects shapes that touch the query). */
    private boolean cellTouches(int bx, int by, int bz, int count, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ, Voxels w) {
        for (int i = 0; i < count; i++) {
            w.box(bx, by, bz, i, box, 0);
            if (bx + (double) box[3] > minX && bx + (double) box[0] < maxX
                    && by + (double) box[4] > minY && by + (double) box[1] < maxY
                    && bz + (double) box[5] > minZ && bz + (double) box[2] < maxZ) {
                return true;
            }
        }
        return false;
    }

    /** {@code Entity.collideWithShapes}: Y first, then the larger horizontal axis last. Body at (px, py, pz). */
    private void collide(double mx, double my, double mz, double px, double py, double pz, Voxels w) {
        double dy = my;
        if (dy != 0.0) {
            dy = clipAxis(1, dy, px, py, pz, w);
        }
        double y1 = py + dy;
        double dx = mx;
        double dz = mz;
        boolean zFirst = Math.abs(dx) < Math.abs(dz);
        if (zFirst && dz != 0.0) {
            dz = clipAxis(2, dz, px, y1, pz, w);
        }
        if (dx != 0.0) {
            dx = clipAxis(0, dx, px, y1, zFirst ? pz + dz : pz, w);
        }
        if (!zFirst && dz != 0.0) {
            dz = clipAxis(2, dz, px + dx, y1, pz, w);
        }
        rx = dx;
        ry = dy;
        rz = dz;
    }

    /**
     * {@code Shapes.collide} on one axis (0 = X, 1 = Y, 2 = Z) for the body box at (px, py, pz): the move {@code d}
     * shortened by every box that overlaps the body on the other two axes and lies ahead of it. Scans the swept
     * range plus one cell below, for boxes that rise above their cell.
     */
    private double clipAxis(int axis, double d, double px, double py, double pz, Voxels w) {
        boolean touched = false;
        double minX = px - HALF_WIDTH;
        double maxX = px + HALF_WIDTH;
        double minY = py;
        double maxY = py + height;
        double minZ = pz - HALF_WIDTH;
        double maxZ = pz + HALF_WIDTH;
        double lo = Math.min(d, 0.0);
        double hi = Math.max(d, 0.0);
        int cx0 = floor((axis == 0 ? minX + lo : minX) + EPS);
        int cx1 = floor((axis == 0 ? maxX + hi : maxX) - EPS);
        int cy0 = floor((axis == 1 ? minY + lo : minY) + EPS) - 1;
        int cy1 = floor((axis == 1 ? maxY + hi : maxY) - EPS);
        int cz0 = floor((axis == 2 ? minZ + lo : minZ) + EPS);
        int cz1 = floor((axis == 2 ? maxZ + hi : maxZ) - EPS);
        for (int bx = cx0; bx <= cx1; bx++) {
            for (int by = cy0; by <= cy1; by++) {
                for (int bz = cz0; bz <= cz1; bz++) {
                    int count = w.boxCount(bx, by, bz);
                    for (int i = 0; i < count; i++) {
                        w.box(bx, by, bz, i, box, 0);
                        double bMinX = bx + (double) box[0];
                        double bMinY = by + (double) box[1];
                        double bMinZ = bz + (double) box[2];
                        double bMaxX = bx + (double) box[3];
                        double bMaxY = by + (double) box[4];
                        double bMaxZ = bz + (double) box[5];
                        if (axis == 0) {
                            if (bMaxY - EPS > minY && bMinY + EPS < maxY && bMaxZ - EPS > minZ && bMinZ + EPS < maxZ) {
                                touched = true;
                                d = d > 0 ? (bMinX >= maxX - EPS ? Math.min(d, bMinX - maxX) : d)
                                        : (bMaxX <= minX + EPS ? Math.max(d, bMaxX - minX) : d);
                            }
                        } else if (axis == 1) {
                            if (bMaxX - EPS > minX && bMinX + EPS < maxX && bMaxZ - EPS > minZ && bMinZ + EPS < maxZ) {
                                touched = true;
                                d = d > 0 ? (bMinY >= maxY - EPS ? Math.min(d, bMinY - maxY) : d)
                                        : (bMaxY <= minY + EPS ? Math.max(d, bMaxY - minY) : d);
                            }
                        } else {
                            if (bMaxX - EPS > minX && bMinX + EPS < maxX && bMaxY - EPS > minY && bMinY + EPS < maxY) {
                                touched = true;
                                d = d > 0 ? (bMinZ >= maxZ - EPS ? Math.min(d, bMinZ - maxZ) : d)
                                        : (bMaxZ <= minZ + EPS ? Math.max(d, bMaxZ - minZ) : d);
                            }
                        }
                    }
                }
            }
        }
        // Shapes.collide: a move shorter than 1e-7 against any shape is no move
        return touched && Math.abs(d) < EPS ? 0.0 : d;
    }

    /** True when any collision box overlaps the open box (strict on every axis, like {@code Level.noCollision}). */
    private boolean boxBlocked(double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
                               Voxels w) {
        int cx0 = floor(minX);
        int cx1 = floor(maxX);
        int cy0 = floor(minY) - 1;
        int cy1 = floor(maxY);
        int cz0 = floor(minZ);
        int cz1 = floor(maxZ);
        for (int bx = cx0; bx <= cx1; bx++) {
            for (int by = cy0; by <= cy1; by++) {
                for (int bz = cz0; bz <= cz1; bz++) {
                    int count = w.boxCount(bx, by, bz);
                    for (int i = 0; i < count; i++) {
                        w.box(bx, by, bz, i, box, 0);
                        if (bx + (double) box[3] > minX && bx + (double) box[0] < maxX
                                && by + (double) box[4] > minY && by + (double) box[1] < maxY
                                && bz + (double) box[5] > minZ && bz + (double) box[2] < maxZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * True when a block is under the player's feet right now. Differs from {@link #onGround} on the tick the player
     * steps off an edge: vanilla (and this replica) resolves Y before X/Z, so that tick still reports onGround. On a
     * shaped grid this uses the exact boxes (a fence top counts at y + 1.5, a bottom slab at y + 0.5).
     */
    public boolean supported(Voxels w) {
        return w.shaped() ? standingShaped(w) : standingCubes(w);
    }

    private boolean standingCubes(Voxels w) {
        int by = floor(y - 0.01);
        return anySolid(x - HALF_WIDTH, x + HALF_WIDTH, by, by, z - HALF_WIDTH, z + HALF_WIDTH, w);
    }

    /** A box spans the point 0.01 under the feet within the footprint (cube grids: same answer as the cube test). */
    private boolean standingShaped(Voxels w) {
        double q = y - 0.01;
        int top = floor(q);
        int x0 = floor(x - HALF_WIDTH + EPS);
        int x1 = floor(x + HALF_WIDTH - EPS);
        int z0 = floor(z - HALF_WIDTH + EPS);
        int z1 = floor(z + HALF_WIDTH - EPS);
        double minX = x - HALF_WIDTH;
        double maxX = x + HALF_WIDTH;
        double minZ = z - HALF_WIDTH;
        double maxZ = z + HALF_WIDTH;
        for (int bx = x0; bx <= x1; bx++) {
            for (int by = top - 1; by <= top; by++) {
                for (int bz = z0; bz <= z1; bz++) {
                    int count = w.boxCount(bx, by, bz);
                    for (int i = 0; i < count; i++) {
                        w.box(bx, by, bz, i, box, 0);
                        if (by + (double) box[1] <= q && by + (double) box[4] > q
                                && bx + (double) box[3] - EPS > minX && bx + (double) box[0] + EPS < maxX
                                && bz + (double) box[5] - EPS > minZ && bz + (double) box[2] + EPS < maxZ) {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
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
