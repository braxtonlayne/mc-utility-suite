package com.utilitysuite.world.budget;

import com.utilitysuite.core.api.RenderBudget;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;

/**
 * Static state the entity-cull mixin reads ({@code EntityRenderDispatcher.shouldRender}, once per entity per frame).
 * Written by {@link RenderGovernorModule} when its settings change; mask 0 (the default, and whenever the governor is
 * off) makes the hook a single field read. Allocation free: instanceof checks and arithmetic only.
 */
public final class EntityCullHooks {

    private static volatile int mask;
    private static volatile int fromTier = 2;
    private static volatile double cullDistSq;

    private EntityCullHooks() {
    }

    /** Only the mixin-handled kinds ({@link EntityCullPolicy#ITEM_FRAME}, {@link EntityCullPolicy#ITEM_DISPLAY}). */
    static void configure(int kindMask, int fromTierSetting, double cullDistance) {
        fromTier = fromTierSetting;
        cullDistSq = cullDistance * cullDistance;
        mask = kindMask & (EntityCullPolicy.ITEM_FRAME | EntityCullPolicy.ITEM_DISPLAY);
    }

    static void clear() {
        mask = 0;
    }

    static int mask() {
        return mask;
    }

    /** True when the entity should not be drawn this frame. */
    public static boolean cull(Entity e, double camX, double camY, double camZ) {
        int m = mask;
        if (m == 0) {
            return false;
        }
        int tier = RenderBudget.tier();
        if (tier == 0) {
            return false;
        }
        int kind = e instanceof ItemFrame ? EntityCullPolicy.ITEM_FRAME
                : e instanceof Display.ItemDisplay ? EntityCullPolicy.ITEM_DISPLAY : 0;
        if (kind == 0) {
            return false;
        }
        double dx = e.getX() - camX;
        double dy = e.getY() - camY;
        double dz = e.getZ() - camZ;
        return EntityCullPolicy.shouldCull(kind, m, tier, fromTier, dx * dx + dy * dy + dz * dz, cullDistSq);
    }
}
