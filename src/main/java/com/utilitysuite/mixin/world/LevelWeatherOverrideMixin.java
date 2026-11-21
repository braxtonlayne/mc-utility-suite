package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render filters, client-side weather: the client level reports the chosen rain/thunder strength. {@code Level} is
 * shared with the integrated server in single-player, so every injection checks {@code instanceof ClientLevel} first:
 * server levels are never affected. The client never sends weather anywhere, so this only changes what is drawn (rain,
 * sky colour, darkness). Why a mixin: {@code ClientLevel} does not override these methods and no event exposes them.
 * Targets checked against the 1.21.1 Mojang-mapped sources: {@code public float getRainLevel(float)} and
 * {@code public float getThunderLevel(float)} on {@code Level}.
 */
@Mixin(Level.class)
public abstract class LevelWeatherOverrideMixin {

    @Inject(method = "getRainLevel", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$rainOverride(float delta, CallbackInfoReturnable<Float> cir) {
        float v = RenderFilterHooks.rainLevel;
        if (v == v && (Object) this instanceof ClientLevel) { // v == v: not NaN (no override)
            cir.setReturnValue(v);
        }
    }

    @Inject(method = "getThunderLevel", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$thunderOverride(float delta, CallbackInfoReturnable<Float> cir) {
        float v = RenderFilterHooks.thunderLevel;
        if (v == v && (Object) this instanceof ClientLevel) {
            cir.setReturnValue(v);
        }
    }
}
