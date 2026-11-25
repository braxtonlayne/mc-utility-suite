package com.utilitysuite.mixin.world;

import com.utilitysuite.world.budget.EntityCullHooks;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * FPS governor: far item frames, glow item frames and item displays are not drawn while the render budget is in a
 * degraded tier (opt-in settings). Why a mixin: these are not living entities, so {@code RenderLivingEvent} never
 * fires for them, and NeoForge 21.1 has no per-entity render event; {@code RenderItemInFrameEvent} only skips the
 * item, not the frame. Returning false here is vanilla's own "outside the frustum" answer, so the entity is skipped
 * before any render work. Pass-through unless a cull setting is on (one static read). Target checked against the
 * 1.21.1 Mojang-mapped sources: {@code public <E extends Entity> boolean shouldRender(E, Frustum, double, double,
 * double)}.
 */
@Mixin(EntityRenderDispatcher.class)
public abstract class EntityRenderDispatcherCullMixin {

    @Inject(method = "shouldRender", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$budgetCull(Entity entity, Frustum frustum, double camX, double camY, double camZ,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (EntityCullHooks.cull(entity, camX, camY, camZ)) {
            cir.setReturnValue(false);
        }
    }
}
