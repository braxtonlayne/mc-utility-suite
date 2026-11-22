package com.utilitysuite.combat.tactics;

/**
 * Where a thrown ender pearl comes down: vanilla 1.21.1 thrown-projectile motion per tick
 * ({@code pos += vel; vel *= 0.99 (0.8 in water); vel.y -= 0.03}) stepped until the segment enters a block that is not
 * {@link CombatVoxels#AIR} or {@link CombatVoxels#WATER}. Engine free, allocation free.
 * <p>
 * The landing point is where the thrower appears: the impact point backed off {@link #BACK_OFF} along the incoming
 * direction, and snapped to the top of the block when it was hit from above. Water is judged at the pearl's cell at the
 * start of the tick (the entity's in-water flag is refreshed before it moves). Unknown cells, lava and the void make
 * {@link #simulate} answer false: never route into unloaded terrain or a lethal landing.
 */
public final class PearlLandingSim {

    /** A pearl still flying after this many ticks is treated as lost (out of loaded chunks). */
    public static final int MAX_TICKS = 200;

    /** Distance the landing point is pulled back from the impact point along the flight direction. */
    public static final double BACK_OFF = 0.3;

    private static final double STEP = 0.25;
    private static final double AIR_DRAG = 0.99;
    private static final double WATER_DRAG = 0.8;
    private static final double GRAVITY = 0.03;
    private static final double VOID_Y = -80.0;

    private double landX, landY, landZ;
    private int flightTicks;

    /**
     * @return true when the pearl hits a block within {@code maxTicks} (capped at {@link #MAX_TICKS}); the landing
     *         point (where the thrower will stand: on top of or in front of the block hit) is then in
     *         {@link #landX()}..
     */
    public boolean simulate(double x, double y, double z, double vx, double vy, double vz, CombatVoxels w,
                            int maxTicks) {
        int limit = Math.min(maxTicks, MAX_TICKS);
        for (int tick = 1; tick <= limit; tick++) {
            boolean inWater = w.kind(floor(x), floor(y), floor(z)) == CombatVoxels.WATER;
            double dx = vx, dy = vy, dz = vz;
            double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
            int steps = Math.max(1, (int) Math.ceil(len / STEP));
            int pcy = floor(y);
            for (int i = 1; i <= steps; i++) {
                double t = (double) i / steps;
                double px = x + dx * t;
                double py = y + dy * t;
                double pz = z + dz * t;
                int cx = floor(px), cy = floor(py), cz = floor(pz);
                int k = w.kind(cx, cy, cz);
                if (k == CombatVoxels.AIR || k == CombatVoxels.WATER) {
                    pcy = cy;
                    continue;
                }
                if (k == CombatVoxels.UNKNOWN || k == CombatVoxels.LAVA) {
                    return false;
                }
                // Impact: pull back along the incoming direction; snap to the block top when it came from above.
                double inv = len > 1.0e-9 ? BACK_OFF / len : 0.0;
                landX = px - dx * inv;
                landY = py - dy * inv;
                landZ = pz - dz * inv;
                if (dy < 0.0 && pcy > cy) {
                    landY = cy + 1.0;
                }
                flightTicks = tick;
                return true;
            }
            x += dx;
            y += dy;
            z += dz;
            double drag = inWater ? WATER_DRAG : AIR_DRAG;
            vx *= drag;
            vy = vy * drag - GRAVITY;
            vz *= drag;
            if (y < VOID_Y) {
                return false;
            }
        }
        return false;
    }

    private static int floor(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    public double landX() {
        return landX;
    }

    public double landY() {
        return landY;
    }

    public double landZ() {
        return landZ;
    }

    /** Ticks from now until the impact. */
    public int flightTicks() {
        return flightTicks;
    }
}
