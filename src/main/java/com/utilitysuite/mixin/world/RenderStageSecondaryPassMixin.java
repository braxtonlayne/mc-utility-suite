package com.utilitysuite.mixin.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.utilitysuite.core.api.SecondaryView;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Picture-in-picture full render: no {@code RenderLevelStageEvent} is posted while the second level pass runs, so no
 * overlay (ours or another mod's) draws into the panel, recurses, or runs its per-frame work twice. Why a mixin:
 * the event is not cancellable and listeners cannot be skipped from outside. The {@code RenderType} overload
 * delegates to this one, so one injection covers both. Pass-through unless the flag is set (one static read).
 * Target checked against NeoForge 21.1.265 {@code ClientHooks.dispatchRenderStage(Stage, LevelRenderer, PoseStack,
 * Matrix4f, Matrix4f, int, Camera, Frustum)}.
 */
@Mixin(ClientHooks.class)
public abstract class RenderStageSecondaryPassMixin {

    @Inject(method = "dispatchRenderStage(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage;"
            + "Lnet/minecraft/client/renderer/LevelRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;"
            + "Lorg/joml/Matrix4f;ILnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;)V",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void utilitysuite$skipInSecondaryPass(RenderLevelStageEvent.Stage stage, LevelRenderer renderer,
                                                         PoseStack poseStack, Matrix4f modelView, Matrix4f projection,
                                                         int renderTick, Camera camera, Frustum frustum,
                                                         CallbackInfo ci) {
        if (SecondaryView.active()) {
            ci.cancel();
        }
    }
}
