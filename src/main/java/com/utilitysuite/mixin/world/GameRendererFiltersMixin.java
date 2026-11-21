package com.utilitysuite.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render filters: skip the hurt-camera tilt ({@code bobHurt}) and the full-screen item activation pop
 * ({@code displayItemActivation}, the totem animation). Why a mixin: NeoForge has no event for either (the hurt tilt is
 * applied to the pose stack, not through {@code ViewportEvent.ComputeCameraAngles}). Pass-through unless the module set
 * the flag. Targets checked against the 1.21.1 Mojang-mapped sources: {@code private void bobHurt(PoseStack, float)}
 * and {@code public void displayItemActivation(ItemStack)}.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererFiltersMixin {

    @Inject(method = "bobHurt", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideHurtTilt(PoseStack poseStack, float partialTicks, CallbackInfo ci) {
        if (RenderFilterHooks.hideHurtTilt) {
            ci.cancel();
        }
    }

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideItemActivation(ItemStack stack, CallbackInfo ci) {
        if (RenderFilterHooks.hideItemActivation) {
            ci.cancel();
        }
    }
}
