package com.utilitysuite.world.blast;

/**
 * Vanilla 1.21.1 explosion damage to a player, engine free and allocation free:
 * <ul>
 *     <li>exposure: {@code Explosion.getSeenPercent}: a grid of sample points over the body box (step
 *     {@code 1 / (size * 2 + 1)} per axis, x/z offset to centre the grid), each traced to the blast centre; the share
 *     whose ray hits no block. Rays are traced through whole voxels ({@link Solid}), which treats every colliding block
 *     as a full cube (vanilla clips against the real shape; slabs and the like read slightly more covering);</li>
 *     <li>raw damage: {@code ExplosionDamageCalculator.getEntityDamageAmount}: {@code d = dist(feet, centre) /
 *     (2 * power)}, {@code i = (1 - d) * exposure}, {@code damage = (i * i + i) / 2 * 7 * 2 * power + 1}, zero past
 *     {@code d > 1};</li>
 *     <li>difficulty ({@code scalesWithDifficulty}): peaceful 0, easy {@code min(x / 2 + 1, x)}, normal x, hard
 *     {@code x * 1.5};</li>
 *     <li>armour ({@code CombatRules.getDamageAfterAbsorb}): {@code x * (1 - clamp(armour - x / (2 + toughness / 4),
 *     armour * 0.2, 20) / 25)};</li>
 *     <li>Resistance: {@code x * (25 - 5 * (amplifier + 1)) / 25};</li>
 *     <li>enchantments ({@code getDamageAfterMagicAbsorb}): {@code x * (1 - min(epf, 20) / 25)}, where every
 *     Protection level adds 1 and every Blast Protection level adds 2.</li>
 * </ul>
 */
public final class ExplosionDamage {

    /** End crystal blast power. */
    public static final float POWER_CRYSTAL = BlastMath.POWER_END_CRYSTAL;
    /** Charged respawn anchor used outside the Nether. */
    public static final float POWER_ANCHOR = 5.0f;

    public static final int PEACEFUL = 0;
    public static final int EASY = 1;
    public static final int NORMAL = 2;
    public static final int HARD = 3;

    /** Player collision box. */
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;

    /** Colliding block query: true when the block at the cell stops an exposure ray. Must not allocate. */
    @FunctionalInterface
    public interface Solid {
        boolean solid(int x, int y, int z);
    }

    private ExplosionDamage() {
    }

    /** Raw damage before difficulty, armour and enchantments. {@code dist}: feet to centre. */
    public static double raw(double power, double dist, double exposure) {
        double diameter = power * 2.0;
        double d = dist / diameter;
        if (d > 1.0) {
            return 0.0;
        }
        double i = (1.0 - d) * exposure;
        return (i * i + i) / 2.0 * 7.0 * diameter + 1.0;
    }

    public static double difficulty(double damage, int difficulty) {
        return switch (difficulty) {
            case PEACEFUL -> 0.0;
            case EASY -> Math.min(damage / 2.0 + 1.0, damage);
            case HARD -> damage * 1.5;
            default -> damage;
        };
    }

    public static double armour(double damage, double armour, double toughness) {
        double f = 2.0 + toughness / 4.0;
        double eff = Math.max(armour * 0.2, Math.min(20.0, armour - damage / f));
        return damage * (1.0 - eff / 25.0);
    }

    /** {@code amplifier} of the Resistance effect, or -1 for none. */
    public static double resistance(double damage, int amplifier) {
        if (amplifier < 0) {
            return damage;
        }
        return Math.max(0.0, damage * (25 - (amplifier + 1) * 5) / 25.0);
    }

    /** Enchantment protection factor against explosions: Protection levels + 2 x Blast Protection levels. */
    public static int epf(int protectionLevels, int blastProtectionLevels) {
        return Math.max(0, protectionLevels) + 2 * Math.max(0, blastProtectionLevels);
    }

    public static double enchantments(double damage, int epf) {
        double f = Math.max(0, Math.min(20, epf));
        return damage * (1.0 - f / 25.0);
    }

    /** Full chain: raw damage reduced by difficulty, armour, Resistance and enchantments. */
    public static double taken(double power, double dist, double exposure, int difficulty, double armour,
                               double toughness, int resistanceAmp, int epf) {
        double d = raw(power, dist, exposure);
        if (d <= 0.0) {
            return 0.0;
        }
        d = difficulty(d, difficulty);
        d = armour(d, armour, toughness);
        d = resistance(d, resistanceAmp);
        return enchantments(d, epf);
    }

    /**
     * Vanilla {@code getSeenPercent} for a player standing with feet at (fx, fy, fz) and the blast at (cx, cy, cz).
     */
    public static double exposure(double cx, double cy, double cz, double fx, double fy, double fz, Solid w) {
        double hw = PLAYER_WIDTH / 2.0;
        return exposure(cx, cy, cz, fx - hw, fy, fz - hw, fx + hw, fy + PLAYER_HEIGHT, fz + hw, w);
    }

    public static double exposure(double cx, double cy, double cz, double minX, double minY, double minZ,
                                  double maxX, double maxY, double maxZ, Solid w) {
        double d0 = 1.0 / ((maxX - minX) * 2.0 + 1.0);
        double d1 = 1.0 / ((maxY - minY) * 2.0 + 1.0);
        double d2 = 1.0 / ((maxZ - minZ) * 2.0 + 1.0);
        double d3 = (1.0 - Math.floor(1.0 / d0) * d0) / 2.0;
        double d4 = (1.0 - Math.floor(1.0 / d2) * d2) / 2.0;
        if (d0 < 0.0 || d1 < 0.0 || d2 < 0.0) {
            return 0.0;
        }
        int seen = 0;
        int total = 0;
        for (double a = 0.0; a <= 1.0; a += d0) {
            for (double b = 0.0; b <= 1.0; b += d1) {
                for (double c = 0.0; c <= 1.0; c += d2) {
                    double sx = minX + a * (maxX - minX) + d3;
                    double sy = minY + b * (maxY - minY);
                    double sz = minZ + c * (maxZ - minZ) + d4;
                    if (!blocked(sx, sy, sz, cx, cy, cz, w)) {
                        seen++;
                    }
                    total++;
                }
            }
        }
        return total == 0 ? 0.0 : seen / (double) total;
    }

    /** Voxel walk (Amanatides-Woo) from a to b: true when a solid cell lies on the way (both ends included). */
    public static boolean blocked(double ax, double ay, double az, double bx, double by, double bz, Solid w) {
        int x = fl(ax);
        int y = fl(ay);
        int z = fl(az);
        int ex = fl(bx);
        int ey = fl(by);
        int ez = fl(bz);
        double dx = bx - ax;
        double dy = by - ay;
        double dz = bz - az;
        int sx = dx > 0 ? 1 : (dx < 0 ? -1 : 0);
        int sy = dy > 0 ? 1 : (dy < 0 ? -1 : 0);
        int sz = dz > 0 ? 1 : (dz < 0 ? -1 : 0);
        double tdx = sx == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dx);
        double tdy = sy == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dy);
        double tdz = sz == 0 ? Double.MAX_VALUE : Math.abs(1.0 / dz);
        double tmx = sx == 0 ? Double.MAX_VALUE : (sx > 0 ? (x + 1 - ax) : (ax - x)) * tdx;
        double tmy = sy == 0 ? Double.MAX_VALUE : (sy > 0 ? (y + 1 - ay) : (ay - y)) * tdy;
        double tmz = sz == 0 ? Double.MAX_VALUE : (sz > 0 ? (z + 1 - az) : (az - z)) * tdz;
        for (int guard = 0; guard < 256; guard++) {
            if (w.solid(x, y, z)) {
                return true;
            }
            if (x == ex && y == ey && z == ez) {
                return false;
            }
            if (tmx < tmy && tmx < tmz) {
                if (tmx > 1.0) {
                    return false;
                }
                x += sx;
                tmx += tdx;
            } else if (tmy < tmz) {
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
        }
        return false;
    }

    private static int fl(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /** True when {@code damage} would kill at {@code health} + {@code absorption}. */
    public static boolean lethal(double damage, double health, double absorption) {
        return damage >= health + absorption;
    }
}
