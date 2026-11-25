package com.utilitysuite.core.api;

/**
 * Shared render-budget hint written by the FPS governor and read by anything that draws optional detail (overlays,
 * the particle filter, entity culling). Plain volatile fields: read per frame / per particle, so no logic beyond a
 * compare. Tier 0 = full detail (the default while the governor is off). Client/render thread.
 */
public final class RenderBudget {

    public static final int MAX_TIER = 3;

    private static volatile int tier;
    private static int particleCounter;

    private RenderBudget() {
    }

    public static int tier() {
        return tier;
    }

    public static void setTier(int t) {
        tier = Math.max(0, Math.min(MAX_TIER, t));
    }

    public static void reset() {
        tier = 0;
    }

    /** Multiplier for overlay density caps (labels, boxes, markers): 1.0 at tier 0, lower as the budget tightens. */
    public static float overlayScale() {
        return overlayScale(tier);
    }

    public static float overlayScale(int t) {
        return switch (t) {
            case 0 -> 1f;
            case 1 -> 0.7f;
            case 2 -> 0.5f;
            default -> 0.35f;
        };
    }

    /** Keep one particle in this many (1 = keep all). Tier 2 halves, tier 3 quarters. */
    public static int particleKeepEvery(int t) {
        return t >= 3 ? 4 : t == 2 ? 2 : 1;
    }

    /**
     * Particle filter hook: true when this particle should be dropped to save frame time. A counter keeps one in
     * {@link #particleKeepEvery(int)}. Called from the particle engine mixin (client thread).
     */
    public static boolean dropParticle() {
        int keep = particleKeepEvery(tier);
        if (keep <= 1) {
            return false;
        }
        particleCounter = (particleCounter + 1) & 0x3FFFFFFF;
        return particleCounter % keep != 0;
    }

    /** Armor stands farther than this (squared blocks) are not drawn; 0 = no culling. Tier 3 only. */
    public static double armorStandCullDistanceSq() {
        return tier >= 3 ? 24.0 * 24.0 : 0.0;
    }
}
