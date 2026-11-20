package com.utilitysuite.mixin.world;

import com.utilitysuite.world.WorldHooks;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Scout camera: while the detached camera is active, mouse look turns the camera entity instead of the player, so the
 * player's own rotation (and what the server sees) stays untouched.
 * <p>
 * Why a mixin: {@code MouseHandler.turnPlayer} calls {@code player.turn(dx, dy)} directly; NeoForge's
 * {@code CalculatePlayerTurnEvent} can only change sensitivity, not redirect the turn. Only the local player's turn is
 * intercepted, and only while the camera is active; otherwise this is an instanceof check plus a volatile read.
 */
@Mixin(Entity.class)
public abstract class EntityTurnFreecamMixin {

    @Inject(method = "turn", at = @At("HEAD"), cancellable = true)
    private void utilitysuite$redirectTurn(double yRot, double xRot, CallbackInfo ci) {
        if ((Object) this instanceof LocalPlayer && WorldHooks.redirectTurn(yRot, xRot)) {
            ci.cancel();
        }
    }
}
