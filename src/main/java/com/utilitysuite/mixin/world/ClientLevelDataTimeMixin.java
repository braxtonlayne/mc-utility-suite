package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render filters, client-side time of day: the client's copy of the level data reports a fixed time (keeping the real
 * day count). Only {@code ClientLevel.ClientLevelData} is touched, never the shared {@code Level} or server data, and the
 * client never sends the day time anywhere, so this changes only what is drawn (sky, sun, moon, sky light darkening).
 * The real value keeps arriving and is stored as usual. Why a mixin: no event exposes the drawn time. Target checked
 * against the 1.21.1 Mojang-mapped sources: {@code ClientLevel.ClientLevelData#getDayTime()J}.
 */
@Mixin(ClientLevel.ClientLevelData.class)
public abstract class ClientLevelDataTimeMixin {

    @Inject(method = "getDayTime", at = @At("RETURN"), cancellable = true)
    private void utilitysuite$fixedDayTime(CallbackInfoReturnable<Long> cir) {
        long override = RenderFilterHooks.dayTime;
        if (override != RenderFilterHooks.NO_TIME) {
            cir.setReturnValue(RenderFilterHooks.displayedDayTime(cir.getReturnValueJ(), override));
        }
    }
}
