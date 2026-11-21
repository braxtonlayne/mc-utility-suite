package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render filters: skip drawing falling rain/snow ({@code renderSnowAndRain}) and the rain splash particles and sound
 * ({@code tickRain}). Why a mixin: no NeoForge event covers weather drawing (the dimension-effects hook belongs to the
 * dimension, not to a client mod). Pass-through unless the module set the flag. Targets checked against the 1.21.1
 * Mojang-mapped sources: {@code private void renderSnowAndRain(LightTexture, float, double, double, double)} and
 * {@code public void tickRain(Camera)}.
 */
@Mixin(LevelRenderer.class)
public abstract class LevelRendererWeatherMixin {

    @Inject(method = "renderSnowAndRain", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideWeather(LightTexture lightTexture, float partialTick, double camX, double camY,
                                          double camZ, CallbackInfo ci) {
        if (RenderFilterHooks.hideWeather) {
            ci.cancel();
        }
    }

    @Inject(method = "tickRain", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideRainSplashes(Camera camera, CallbackInfo ci) {
        if (RenderFilterHooks.hideWeather) {
            ci.cancel();
        }
    }
}
