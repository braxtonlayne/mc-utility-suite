package com.utilitysuite.combat.tactics;

/**
 * Where our next melee hit sends the target, per vanilla 1.21.1:
 * <ol>
 *     <li>{@code LivingEntity.hurt}: {@code knockback(0.4, attacker - target)};</li>
 *     <li>{@code Player.attack}: {@code knockback(kb * 0.5, sin(yaw), -cos(yaw))} with {@code kb} = attack-knockback
 *     attribute (0 for players) + Knockback enchantment level + 1 for a sprint hit;</li>
 *     <li>{@code LivingEntity.knockback(s, x, z)}: {@code s *= 1 - resistance};
 *     {@code v = (v.x / 2 - nx * s, onGround ? min(0.4, v.y / 2 + s) : v.y, v.z / 2 - nz * s)};</li>
 *     <li>then per tick ({@code LivingEntity.travel}): move, {@code v.y = (v.y - 0.08) * 0.98}, horizontal
 *     {@code *= 0.91} in the air or {@code 0.6 * 0.91} on the ground.</li>
 * </ol>
 * The flight is traced as a point over {@link CombatVoxels} until it lands; {@link #drop()} is how far below the start
 * it ends up, and {@link #ledge()} is true past {@link #LEDGE_DROP} blocks (or when no floor is found). The target's own
 * movement input is ignored. Engine free, allocation free.
 */
public final class KnockbackLanding {

    public static final double LEDGE_DROP = 3.0;
    public static final int MAX_TICKS = 60;
    /** Floor search below the end of the simulated flight. */
    static final int FLOOR_SCAN = 24;

    private double landX;
    private double landY;
    private double landZ;
    private double vx;
    private double vy;
    private double vz;
    private double drop;
    private boolean ledge;

    /**
     * @param tx, ty, tz    target feet
     * @param tvx, tvy, tvz target velocity
     * @param onGround      target on the ground
     * @param ax, az        attacker position (horizontal)
     * @param yawDeg        attacker yaw in degrees
     * @param kbBonus       attack knockback: Knockback enchantment level + 1 when the hit is a sprint hit
     * @param resistance    target knockback resistance 0..1
     */
    public void predict(double tx, double ty, double tz, double tvx, double tvy, double tvz, boolean onGround,
                        double ax, double az, float yawDeg, double kbBonus, double resistance, CombatVoxels w) {
        double res = Math.max(0.0, Math.min(1.0, resistance));
        boolean ground = onGround;
        vx = tvx;
        vy = tvy;
        vz = tvz;
        // 1: base knockback from hurt(), direction attacker -> target
        applyKnockback(0.4 * (1.0 - res), ax - tx, az - tz, ground);
        // 2: the attack's extra knockback along the attacker's facing
        double extra = kbBonus * 0.5 * (1.0 - res);
        if (extra > 0.0) {
            double yaw = Math.toRadians(yawDeg);
            applyKnockback(extra, Math.sin(yaw), -Math.cos(yaw), ground);
        }
        double initVx = vx;
        double initVy = vy;
        double initVz = vz;
        // 3: trace the flight
        double x = tx;
        double y = ty;
        double z = tz;
        double mvx = vx;
        double mvy = vy;
        double mvz = vz;
        boolean landed = false;
        for (int tick = 0; tick < MAX_TICKS; tick++) {
            double nx = x + mvx;
            double nz = z + mvz;
            int feet = floor(y + 0.01);
            if (solid(w, floor(nx), feet, floor(nz)) || solid(w, floor(nx), feet + 1, floor(nz))) {
                mvx = 0.0; // hits a wall: horizontal motion stops (sliding ignored)
                mvz = 0.0;
                nx = x;
                nz = z;
            }
            double ny = y + mvy;
            if (mvy <= 0.0) {
                // every cell the feet pass through this tick (a fast fall can cross more than one)
                int found = Integer.MIN_VALUE;
                for (int c = floor(y - 1.0e-6), bottom = floor(ny); c >= bottom; c--) {
                    if (solid(w, floor(nx), c, floor(nz))) {
                        found = c;
                        break;
                    }
                }
                if (found != Integer.MIN_VALUE) {
                    x = nx;
                    y = found + 1;
                    z = nz;
                    landed = true;
                    break;
                }
            } else if (solid(w, floor(nx), floor(ny + 1.8), floor(nz))) {
                ny = y; // head bump
                mvy = 0.0;
            }
            x = nx;
            y = ny;
            z = nz;
            mvy = (mvy - 0.08) * 0.98;
            mvx *= 0.91;
            mvz *= 0.91;
        }
        if (!landed) {
            // no landing within the flight window: look straight down for a floor
            int fx = floor(x);
            int fz = floor(z);
            int fy = floor(y);
            landed = false;
            for (int d = 0; d < FLOOR_SCAN; d++) {
                int k = w.kind(fx, fy - d, fz);
                if (CombatVoxels.standable(k) || k == CombatVoxels.WATER) {
                    y = fy - d + 1;
                    landed = true;
                    break;
                }
                if (k == CombatVoxels.LAVA || k == CombatVoxels.UNKNOWN) {
                    break;
                }
            }
            if (!landed) {
                y = ty - (FLOOR_SCAN + 1);
            }
        }
        landX = x;
        landY = y;
        landZ = z;
        vx = initVx;
        vy = initVy;
        vz = initVz;
        drop = ty - y;
        ledge = drop > LEDGE_DROP;
    }

    private void applyKnockback(double strength, double dx, double dz, boolean ground) {
        if (strength <= 0.0) {
            return;
        }
        double len = Math.sqrt(dx * dx + dz * dz);
        double nx = len < 1.0e-4 ? 0.0 : dx / len * strength;
        double nz = len < 1.0e-4 ? 0.0 : dz / len * strength;
        vx = vx / 2.0 - nx;
        vz = vz / 2.0 - nz;
        if (ground) {
            vy = Math.min(0.4, vy / 2.0 + strength);
        }
    }

    /** Melee reach measure: distance from the attacker's eye to the nearest point of the target's box. */
    public static double reach(double ex, double ey, double ez, double minX, double minY, double minZ, double maxX,
                               double maxY, double maxZ) {
        double dx = ex < minX ? minX - ex : (ex > maxX ? ex - maxX : 0.0);
        double dy = ey < minY ? minY - ey : (ey > maxY ? ey - maxY : 0.0);
        double dz = ez < minZ ? minZ - ez : (ez > maxZ ? ez - maxZ : 0.0);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static boolean solid(CombatVoxels w, int x, int y, int z) {
        int k = w.kind(x, y, z);
        return CombatVoxels.standable(k);
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

    /** Launch velocity right after the hit. */
    public double launchX() {
        return vx;
    }

    public double launchY() {
        return vy;
    }

    public double launchZ() {
        return vz;
    }

    /** Blocks below the start where the target lands (negative when it lands higher). */
    public double drop() {
        return drop;
    }

    /** The hit would send the target over a drop of more than {@link #LEDGE_DROP} blocks (or into the void). */
    public boolean ledge() {
        return ledge;
    }
}
