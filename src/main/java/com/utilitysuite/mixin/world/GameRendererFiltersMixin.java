package com.utilitysuite.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render filters: skip the hurt-camera tilt ({@code bobHurt}). Why a mixin: NeoForge has no event for it (the hurt
 * tilt is applied to the pose stack, not through {@code ViewportEvent.ComputeCameraAngles}). Pass-through unless the
 * module set the flag. Target checked against the 26.3 sources: {@code private void bobHurt(CameraRenderState,
 * PoseStack)}. The item activation pop moved to {@code LocalPlayer} in 26.3 ({@link LocalPlayerItemActivationMixin}).
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFiltersMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideHurtTilt(CameraRenderState cameraState, PoseStack poseStack, CallbackInfo ci) {
        if (RenderFilterHooks.hideHurtTilt) {
            ci.cancel();
        }
    }
}
