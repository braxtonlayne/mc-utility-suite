package com.utilitysuite.world.budget;

/**
 * Engine-free distance-cull decision of the FPS governor for decorative entities (item frames, item displays, armor
 * stands). An entity kind is culled only when its kind is switched on, the shared budget tier is degraded to at least
 * the configured tier, and it is farther than the cull distance. Pure static, allocation free.
 */
public final class EntityCullPolicy {

    /** Item frames and glow item frames. */
    public static final int ITEM_FRAME = 1;
    /** {@code Display.ItemDisplay}. */
    public static final int ITEM_DISPLAY = 2;
    /** Armor stands (handled through {@code RenderLivingEvent}). */
    public static final int ARMOR_STAND = 4;

    /** Armor stands are always hidden past this at tier 3 (the governor's original rule). */
    public static final double TIER3_ARMOR_STAND_DIST = 24.0;

    private EntityCullPolicy() {
    }

    /**
     * @param kind        one of the kind bits, or 0 for "not a cullable kind"
     * @param enabledMask kind bits switched on in the settings
     * @param tier        current render-budget tier (0 = full detail)
     * @param fromTier    first degraded tier that culls (1..3)
     * @param distSq      squared distance from the camera
     * @param cullDistSq  squared cull distance
     */
    public static boolean shouldCull(int kind, int enabledMask, int tier, int fromTier, double distSq,
                                     double cullDistSq) {
        return kind != 0 && (enabledMask & kind) != 0 && tier > 0 && tier >= fromTier && cullDistSq > 0
                && distSq > cullDistSq;
    }

    /** Armor stands: the opt-in rule above, or the fixed tier-3 rule (24 blocks) that predates it. */
    public static boolean shouldCullArmorStand(int enabledMask, int tier, int fromTier, double distSq,
                                               double cullDistSq) {
        if (tier >= 3 && distSq > TIER3_ARMOR_STAND_DIST * TIER3_ARMOR_STAND_DIST) {
            return true;
        }
        return shouldCull(ARMOR_STAND, enabledMask, tier, fromTier, distSq, cullDistSq);
    }
}
