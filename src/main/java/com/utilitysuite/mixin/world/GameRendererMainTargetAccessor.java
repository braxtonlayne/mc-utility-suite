package com.utilitysuite.mixin.world;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture second pass: swaps the main render target for the panel's own target for the duration of one
 * level render, then puts the real one back. 26.3 moved the field from {@code Minecraft} (the 1.21.1
 * {@code MinecraftMainTargetAccessor}) to {@code GameRenderer}: {@code private final RenderTarget mainRenderTarget}
 * (checked against the 26.3 sources; {@code Minecraft.getMainRenderTarget()} reads it from there). Why a mixin: the
 * field is final and has no setter.
 */
@Mixin(GameRenderer.class)
public interface GameRendererMainTargetAccessor {

    @Mutable
    @Accessor("mainRenderTarget")
    void utilitysuite$setMainRenderTarget(RenderTarget target);
}
