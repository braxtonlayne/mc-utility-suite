package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.ClientClockManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Render filters, client-side time of day: the client's world clocks report a fixed time of day (keeping the real day
 * count) and no sub-tick progress while the override is set. Only the client copy
 * ({@code ClientClockManager.ClientClockInstance}) is touched, never server data, and the client never sends clock
 * time anywhere, so this changes only what is drawn (sky, sun, moon, sky light darkening). The real value keeps
 * arriving and is stored as usual.
 * <p>
 * 26.3: the day time left {@code ClientLevel.ClientLevelData} (the 1.21.1 target {@code getDayTime()J}); time of day is
 * now a {@code WorldClock} whose client instance exposes {@code totalTicks()J} and {@code partialTick()F} (checked
 * against the 26.3 sources). Every client clock is overridden (the overworld and the end each have one; only the
 * current dimension's is drawn). Why a mixin: no event exposes the drawn time.
 */
@Mixin(ClientClockManager.ClientClockInstance.class)
public abstract class ClientClockTimeMixin {

    @Inject(method = "totalTicks", at = @At("RETURN"), cancellable = true)
    private void utilitysuite$fixedDayTime(CallbackInfoReturnable<Long> cir) {
        long override = RenderFilterHooks.dayTime;
        if (override != RenderFilterHooks.NO_TIME) {
            cir.setReturnValue(RenderFilterHooks.displayedDayTime(cir.getReturnValueJ(), override));
        }
    }

    @Inject(method = "partialTick", at = @At("RETURN"), cancellable = true)
    private void utilitysuite$fixedPartialTick(CallbackInfoReturnable<Float> cir) {
        if (RenderFilterHooks.dayTime != RenderFilterHooks.NO_TIME) {
            cir.setReturnValue(0.0f);
        }
    }
}
