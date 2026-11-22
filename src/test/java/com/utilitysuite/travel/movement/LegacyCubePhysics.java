package com.utilitysuite.travel.movement;

import static com.utilitysuite.travel.movement.MovementPhysics.AIR_FRICTION;
import static com.utilitysuite.travel.movement.MovementPhysics.BASE_SPEED;
import static com.utilitysuite.travel.movement.MovementPhysics.DEFAULT_SLIPPERINESS;
import static com.utilitysuite.travel.movement.MovementPhysics.DRAG;
import static com.utilitysuite.travel.movement.MovementPhysics.GRAVITY;
import static com.utilitysuite.travel.movement.MovementPhysics.HALF_WIDTH;
import static com.utilitysuite.travel.movement.MovementPhysics.HEIGHT;
import static com.utilitysuite.travel.movement.MovementPhysics.INPUT_SCALE;
import static com.utilitysuite.travel.movement.MovementPhysics.JUMP_VELOCITY;
import static com.utilitysuite.travel.movement.MovementPhysics.SPRINT_JUMP_BOOST;

/**
 * Frozen copy of the cube-only {@link MovementPhysics} tick as it was before the shape-physics work (main at 0e29eea),
 * kept so tests can prove that cube grids still give bit-identical results.
 */
final class LegacyCubePhysics {

    private static final double EPS = 1.0e-7;

    double x, y, z;
    double vx, vy, vz;
    boolean onGround;
    double fallDistance;
    double speed = BASE_SPEED;
    double jumpBoost;
    double slipperiness = DEFAULT_SLIPPERINESS;
    boolean horizontalCollision;

    LegacyCubePhysics set(double x, double y, double z, double vx, double vy, double vz, boolean onGround) {
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

    void tick(int forward, int strafe, float yawDeg, boolean sprint, boolean jump, MovementPhysics.Voxels w) {
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

    private void move(MovementPhysics.Voxels w) {
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

    boolean standing(MovementPhysics.Voxels w) {
        int by = floor(y - 0.01);
        int x0 = floor(x - HALF_WIDTH + EPS);
        int x1 = floor(x + HALF_WIDTH - EPS);
        int z0 = floor(z - HALF_WIDTH + EPS);
        int z1 = floor(z + HALF_WIDTH - EPS);
        return layerSolid(x0, x1, by, z0, z1, w);
    }

    private double clipY(double d, MovementPhysics.Voxels w) {
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

    private double clipX(double d, MovementPhysics.Voxels w) {
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

    private double clipZ(double d, MovementPhysics.Voxels w) {
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

    private static boolean layerSolid(int x0, int x1, int by, int z0, int z1, MovementPhysics.Voxels w) {
        for (int bx = x0; bx <= x1; bx++) {
            for (int bz = z0; bz <= z1; bz++) {
                if (w.solid(bx, by, bz)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean columnSolid(int fixed, int y0, int y1, int a0, int a1, boolean xAxis,
                                       MovementPhysics.Voxels w) {
        for (int by = y0; by <= y1; by++) {
            for (int a = a0; a <= a1; a++) {
                if (xAxis ? w.solid(fixed, by, a) : w.solid(a, by, fixed)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }
}
