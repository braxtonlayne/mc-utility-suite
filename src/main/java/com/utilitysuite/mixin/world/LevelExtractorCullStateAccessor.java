package com.utilitysuite.mixin.world;

import net.minecraft.client.renderer.extract.LevelExtractor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Picture-in-picture second pass: the last camera rotation the extractor culled the section list for. Setting NaN
 * makes the next extract re-cull for its own frustum. Fields checked against the 26.3 sources:
 * {@code private double prevCamRotX, prevCamRotY} on {@code LevelExtractor} (1.21.1 kept them on {@code LevelRenderer}).
 * Reached through {@code Minecraft.levelExtractor}. Why a mixin: private, no setter.
 */
@Mixin(LevelExtractor.class)
public interface LevelExtractorCullStateAccessor {

    @Accessor("prevCamRotX")
    void utilitysuite$setPrevCamRotX(double v);

    @Accessor("prevCamRotY")
    void utilitysuite$setPrevCamRotY(double v);
}
