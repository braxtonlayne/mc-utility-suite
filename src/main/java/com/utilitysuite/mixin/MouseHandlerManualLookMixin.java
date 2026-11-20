package com.utilitysuite.mixin;

import com.utilitysuite.core.input.ManualInputHooks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Manual-override detection for the camera: reports the raw cursor delta that is about to turn the player.
 * <p>
 * Why a mixin: NeoForge's {@code CalculatePlayerTurnEvent} fires here but exposes only sensitivity, not the delta, and
 * comparing yaw/pitch per tick would also catch server corrections and the smoother's own writes. {@code turnPlayer}
 * only runs while the mouse is grabbed in game, and only for hardware movement, so automation can never trigger it.
 * Read-only: nothing is changed.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerManualLookMixin {

    @Shadow
    private double accumulatedDX;
    @Shadow
    private double accumulatedDY;

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void utilitysuite$reportMouseLook(double movementTime, CallbackInfo ci) {
        if (accumulatedDX != 0.0 || accumulatedDY != 0.0) {
            ManualInputHooks.onMouseTurn(accumulatedDX, accumulatedDY);
        }
    }
}
