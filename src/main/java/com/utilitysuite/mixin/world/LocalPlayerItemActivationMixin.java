package com.utilitysuite.mixin.world;

import com.utilitysuite.world.filters.RenderFilterHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Render filters: skip the full-screen item activation pop (the totem animation). Why a mixin: NeoForge has no event
 * for it. Pass-through unless the module set the flag. 26.3 moved it from {@code GameRenderer} to the local player:
 * target checked against the 26.3 sources, {@code public void displayItemActivation(ItemStack)} on
 * {@code LocalPlayer} (it starts {@code itemActivation}; nothing else reads the call).
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerItemActivationMixin {

    @Inject(method = "displayItemActivation", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$hideItemActivation(ItemStack stack, CallbackInfo ci) {
        if (RenderFilterHooks.hideItemActivation) {
            ci.cancel();
        }
    }
}
