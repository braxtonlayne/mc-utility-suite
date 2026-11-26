package com.utilitysuite.mixin.world;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.renderer.WeatherEffectRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Render filters, hide weather: no rain or snow columns are drawn. 26.3 (checked against the sources): rain and snow
 * left {@code LevelRenderer.renderSnowAndRain} (the 1.21.1 target); {@code WeatherEffectRenderer.extractRenderState}
 * reads {@code level.getRainLevel(partialTicks)} once and builds no columns when it is 0, which is what this reports
 * while the filter is on. Only the drawn weather changes (sky darkening uses the level's own rain level). Why a mixin:
 * no event covers weather rendering. Allocation free.
 */
@Mixin(WeatherEffectRenderer.class)
public abstract class WeatherEffectRendererHideMixin {

    @ModifyExpressionValue(method = "extractRenderState", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientLevel;getRainLevel(F)F"))
    private float utilitysuite$hideWeather(float rainLevel) {
        return RenderFilterHooks.hideWeather ? 0.0f : rainLevel;
    }
}
