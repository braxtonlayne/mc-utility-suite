package com.utilitysuite.mixin.input;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.utilitysuite.core.input.SharedInputOverlay;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Sprint withhold of shared control ({@code SharedGrant.withholdSprint}): while the current tick's movement input
 * carries a withhold, {@code LocalPlayer.aiStep} starts no sprint, as if the sprint key were up for that tick.
 * <p>
 * Why a mixin: in 1.21.1 {@code aiStep} reads {@code options.keySprint.isDown()} and calls {@code setSprinting(true)}
 * (the sprint-key start and the double-tap-forward start) AFTER {@code MovementInputUpdateEvent}, with no event in
 * between, so a listener can end a running sprint but cannot stop the held key from starting it again in the same
 * tick. This skips only {@code setSprinting(true)} calls inside {@code aiStep}, only while
 * {@link SharedInputOverlay#isSprintStartBlocked()} is set (a static field read otherwise); every
 * {@code setSprinting(false)} (vanilla's stop rules) runs untouched. Target verified against the 21.1.265 sources and
 * bytecode: four {@code invokevirtual LocalPlayer.setSprinting(Z)V} in {@code aiStep}, two of them with {@code true}.
 * {@code @WrapWithCondition} (MixinExtras, bundled with NeoForge) composes with other mods' wrappers and allocates
 * nothing.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSprintWithholdMixin {

    @WrapWithCondition(method = "aiStep",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;setSprinting(Z)V"))
    private boolean utilitysuite$withholdSprintStart(LocalPlayer self, boolean sprinting) {
        return !sprinting || !SharedInputOverlay.isSprintStartBlocked();
    }
}
