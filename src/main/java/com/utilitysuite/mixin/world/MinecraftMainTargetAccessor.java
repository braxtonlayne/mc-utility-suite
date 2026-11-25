package com.utilitysuite.mixin.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture full render: swaps {@code Minecraft.mainRenderTarget} for the panel's offscreen target for the
 * duration of the second level pass, so every {@code getMainRenderTarget().bindWrite(..)} inside
 * {@code LevelRenderer.renderLevel} lands in the offscreen target. Why a mixin: the field is private final and no
 * event redirects the level render target. The pass always swaps back in a {@code finally}.
 */
@Mixin(Minecraft.class)
public interface MinecraftMainTargetAccessor {

    @Mutable
    @Accessor("mainRenderTarget")
    void utilitysuite$setMainRenderTarget(RenderTarget target);
}
