package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render filters, hide weather: no rain splash particles or rain sounds. 26.3 (checked against the sources): the
 * splash tick left {@code LevelRenderer.tickRain(Camera)} (the 1.21.1 target) for {@code ClientLevel.tickWeatherEffects()},
 * which only spawns those particles and plays those sounds. Why a mixin: no event covers it.
 */
@Mixin(ClientLevel.class)
public abstract class ClientLevelWeatherTickMixin {

    @Inject(method = "tickWeatherEffects", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideRainSplashes(CallbackInfo ci) {
        if (RenderFilterHooks.hideWeather) {
            ci.cancel();
        }
    }
}
