package com.utilitysuite.world.overlays;

/**
 * Projectile path preview mirroring vanilla 1.21.1 per-tick motion: {@code pos += vel; vel *= drag; vel.y -= gravity}
 * ({@code AbstractArrow.tick}, {@code ThrowableProjectile.tick}); wind charges fly straight (inertia 1, no gravity,
 * no acceleration). Writes into a preallocated buffer: zero allocation per simulation.
 */
public final class TrajectoryIntegrator {

    /** Per-projectile physics (gravity per tick, air drag, water drag) and launch speed. */
    public enum Kind {
        /** Bow at full draw (speed = power 1.0 * 3.0). */
        ARROW(0.05, 0.99, 0.6, 3.0),
        CROSSBOW_ARROW(0.05, 0.99, 0.6, 3.15),
        TRIDENT(0.05, 0.99, 0.99, 2.5),
        /** Ender pearl, snowball, egg. */
        THROWN(0.03, 0.99, 0.8, 1.5),
        WIND_CHARGE(0.0, 1.0, 1.0, 1.5);

        public final double gravity;
        public final double drag;
        public final double waterDrag;
        public final double speed;

        Kind(double gravity, double drag, double waterDrag, double speed) {
            this.gravity = gravity;
            this.drag = drag;
            this.waterDrag = waterDrag;
            this.speed = speed;
        }
    }

    /** World query used for collision: 0 free, 1 solid, 2 water. */
    @FunctionalInterface
    public interface Collider {
        int FREE = 0;
        int SOLID = 1;
        int WATER = 2;

        int stateAt(int x, int y, int z);
    }

    private final float[] points;
    private final int maxPoints;
    private int count;
    private boolean hit;
    private double hitX;
    private double hitY;
    private double hitZ;

    public TrajectoryIntegrator(int maxSteps) {
        this.maxPoints = maxSteps + 1;
        this.points = new float[maxPoints * 3];
    }

    public int count() {
        return count;
    }

    public float x(int i) {
        return points[i * 3];
    }

    public float y(int i) {
        return points[i * 3 + 1];
    }

    public float z(int i) {
        return points[i * 3 + 2];
    }

    public boolean hit() {
        return hit;
    }

    public double hitX() {
        return hitX;
    }

    public double hitY() {
        return hitY;
    }

    public double hitZ() {
        return hitZ;
    }

    /** Launch velocity component for a yaw/pitch (degrees), as in {@code Projectile.shootFromRotation}. */
    public static double dirX(float yaw, float pitch) {
        return -Math.sin(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
    }

    public static double dirY(float pitch) {
        return -Math.sin(Math.toRadians(pitch));
    }

    public static double dirZ(float yaw, float pitch) {
        return Math.cos(Math.toRadians(yaw)) * Math.cos(Math.toRadians(pitch));
    }

    /**
     * Integrates from the launch point. Collision is sampled along each tick's segment at 0.25-block steps.
     *
     * @param speedScale multiplies the kind's launch speed (bow draw power)
     */
    public void simulate(Kind kind, double x, double y, double z, float yaw, float pitch, double speedScale,
                         double shooterVx, double shooterVy, double shooterVz, Collider collider) {
        double s = kind.speed * speedScale;
        double vx = dirX(yaw, pitch) * s + shooterVx;
        double vy = dirY(pitch) * s + shooterVy;
        double vz = dirZ(yaw, pitch) * s + shooterVz;
        simulate(kind, x, y, z, vx, vy, vz, collider);
    }

    public void simulate(Kind kind, double x, double y, double z, double vx, double vy, double vz,
                         Collider collider) {
        count = 0;
        hit = false;
        put(x, y, z);
        boolean inWater = collider != null && collider.stateAt(floor(x), floor(y), floor(z)) == Collider.WATER;
        while (count < maxPoints) {
            double nx = x + vx;
            double ny = y + vy;
            double nz = z + vz;
            if (collider != null && segmentBlocked(collider, x, y, z, nx, ny, nz)) {
                put(hitX, hitY, hitZ);
                hit = true;
                return;
            }
            double drag = inWater ? kind.waterDrag : kind.drag;
            vx *= drag;
            vy *= drag;
            vz *= drag;
            vy -= kind.gravity;
            x = nx;
            y = ny;
            z = nz;
            put(x, y, z);
            if (collider != null) {
                inWater = collider.stateAt(floor(x), floor(y), floor(z)) == Collider.WATER;
            }
            if (y < -128) {
                return;
            }
        }
    }

    private boolean segmentBlocked(Collider c, double x0, double y0, double z0, double x1, double y1, double z1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        int steps = Math.max(1, (int) Math.ceil(len / 0.25));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps;
            double px = x0 + dx * t;
            double py = y0 + dy * t;
            double pz = z0 + dz * t;
            if (c.stateAt(floor(px), floor(py), floor(pz)) == Collider.SOLID) {
                hitX = px;
                hitY = py;
                hitZ = pz;
                return true;
            }
        }
        return false;
    }

    private void put(double x, double y, double z) {
        if (count >= maxPoints) {
            return;
        }
        points[count * 3] = (float) x;
        points[count * 3 + 1] = (float) y;
        points[count * 3 + 2] = (float) z;
        count++;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** {@code BowItem.getPowerForTime}: draw power 0..1 for the ticks the bow was held. */
    public static double bowPower(int useTicks) {
        double f = useTicks / 20.0;
        f = (f * f + f * 2.0) / 3.0;
        return Math.min(1.0, f);
    }
}
