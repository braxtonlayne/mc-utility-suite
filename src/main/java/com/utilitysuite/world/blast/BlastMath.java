package com.utilitysuite.world.blast;

/**
 * Engine-free numbers for the blast overlay: explosion power per source, the radius drawn (vanilla damages entities
 * out to {@code 2 * power}), fuse ticks left and the ring colour (green with lots of fuse left, through yellow, to red
 * when it is about to go off), plus cached fuse labels in tenths of a second.
 */
public final class BlastMath {

    public static final float POWER_CREEPER = 3.0f;
    public static final float POWER_CHARGED_CREEPER = 6.0f;
    public static final float POWER_TNT = 4.0f;
    public static final float POWER_END_CRYSTAL = 6.0f;
    /** A bed (or respawn anchor) used outside the Overworld explodes with power 5 and sets fire. */
    public static final float POWER_BED = 5.0f;
    /** Vanilla creeper max swell (ticks from first hiss to explosion). */
    public static final int CREEPER_FUSE = 30;
    public static final int TNT_FUSE = 80;
    /** Fuse labels cached for 0.0 s .. 20.0 s. */
    private static final String[] LABELS = new String[201];

    private BlastMath() {
    }

    /** Radius within which entities take damage. */
    public static float damageRadius(float power) {
        return power * 2.0f;
    }

    /**
     * Creeper fuse ticks left from the swell fraction (0 = idle, 1 = exploding) and the creeper's max swell. Vanilla
     * explodes when swell reaches maxSwell; {@code getSwelling} returns swell / (maxSwell - 2).
     */
    public static int creeperTicksLeft(float swelling, int maxSwell) {
        float s = Math.max(0f, Math.min(1f, swelling));
        return Math.max(0, Math.round((1f - s) * (maxSwell - 2)));
    }

    /** ARGB colour by fraction of fuse left: 1 = green, 0.5 = yellow, 0 = red. Alpha fixed at 0xE0. */
    public static int fuseColor(int ticksLeft, int fullFuse) {
        float f = fullFuse <= 0 ? 0f : Math.max(0f, Math.min(1f, ticksLeft / (float) fullFuse));
        int r;
        int g;
        if (f >= 0.5f) {
            g = 255;
            r = Math.round((1f - f) * 2f * 255f);
        } else {
            r = 255;
            g = Math.round(f * 2f * 255f);
        }
        return 0xE0000000 | (r << 16) | (g << 8) | 0x30;
    }

    /** "1.5s" for 30 ticks; cached (allocates once per distinct tenth, then never). */
    public static String fuseLabel(int ticksLeft) {
        int tenths = Math.max(0, Math.min(LABELS.length - 1, Math.round(ticksLeft / 2f)));
        String s = LABELS[tenths];
        if (s == null) {
            s = (tenths / 10) + "." + (tenths % 10) + "s";
            LABELS[tenths] = s;
        }
        return s;
    }

    /**
     * Vanilla entity damage from an explosion of {@code power} at {@code distance} blocks (explosion centre to the
     * entity), given the fraction of rays that reach the entity ({@code exposure}, 0..1; vanilla's
     * {@code Explosion.getSeenPercent}) and the world difficulty (0 peaceful .. 3 hard; explosions scale with
     * difficulty for players). Armour and protection are ignored, so this is an upper bound.
     */
    public static float entityDamage(float power, double distance, double exposure, int difficulty) {
        double diameter = power * 2.0;
        if (power <= 0f || distance > diameter) {
            return 0f;
        }
        double impact = (1.0 - distance / diameter) * Math.max(0.0, Math.min(1.0, exposure));
        float dmg = (float) ((impact * impact + impact) / 2.0 * 7.0 * diameter + 1.0);
        return switch (difficulty) {
            case 0 -> 0f;
            case 1 -> Math.min(dmg / 2f + 1f, dmg);
            case 3 -> dmg * 1.5f;
            default -> dmg;
        };
    }

    /**
     * Smallest distance (0.1-block steps) at which {@link #entityDamage} stays below {@code maxDamage}; just past the
     * damage radius when even the vanilla floor of 1 damage inside the radius is too much.
     */
    public static double safeDistance(float power, double maxDamage, double exposure, int difficulty) {
        double radius = damageRadius(power);
        int steps = (int) Math.ceil(radius * 10);
        for (int i = 0; i <= steps; i++) {
            double d = i / 10.0;
            if (entityDamage(power, d, exposure, difficulty) < maxDamage) {
                return d;
            }
        }
        return radius + 0.1;
    }
}
