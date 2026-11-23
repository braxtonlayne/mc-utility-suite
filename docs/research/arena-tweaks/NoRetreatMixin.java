package com.arenatweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Bot arena only (local benchmark server, not part of mc-utility-suite): the opponent bot fights to the death.
 * Its RETREAT state triggers at health <= gappleThreshold (16 on HARD) and then it only runs; the owner asked that the
 * opponent not flee. With no golden apples in the arena kit the threshold has no other use, so it is zeroed after every
 * difficulty change.
 */
@Mixin(targets = "com.pvpbot.config.BotConfig", remap = false)
public abstract class NoRetreatMixin {
    @Inject(method = "applyDifficulty", at = @At("TAIL"), remap = false)
    private void arenatweaks$noRetreat(CallbackInfo ci) {
        try {
            Object self = this;
            self.getClass().getField("gappleThreshold").setFloat(self, 0f);
            self.getClass().getField("notchAppleThreshold").setFloat(self, 0f);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("arena-tweaks: BotConfig fields changed", e);
        }
    }
}
