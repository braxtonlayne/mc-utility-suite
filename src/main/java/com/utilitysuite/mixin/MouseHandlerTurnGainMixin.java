package com.utilitysuite.mixin;

import com.utilitysuite.core.input.ManualInputHooks;
import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Turn gain on the user's own mouse: scales only the YAW argument that {@code MouseHandler.turnPlayer} passes to
 * {@code LocalPlayer.turn(yaw, pitch)} (turn units, 1 unit = 0.15 deg), through
 * {@link ManualInputHooks#onTurnYaw}. It never writes a rotation: with a still mouse the argument is 0 and stays 0.
 * <p>
 * Why a mixin: NeoForge's {@code CalculatePlayerTurnEvent} (fired from {@code ClientHooks.getTurnPlayerValues} at the
 * top of {@code turnPlayer}) exposes one mouse sensitivity for both axes and the cinematic flag, nothing per axis and
 * no delta, so it cannot scale yaw without also scaling pitch, nor depend on the turn direction. No other event sits
 * between the mouse delta and {@code Entity.turn}. Writing yaw afterwards from a frame event would be rotation
 * injection, which this feature must not do.
 * <p>
 * The opt-in pitch gain is a second {@code @ModifyArg} (index 1) on the same call. Kept as small as possible: a HEAD hook that records the frame time (the method's own argument) and one
 * {@code @ModifyArg} on index 0 of the single {@code LocalPlayer.turn(DD)V} call. Both delegate to static methods that
 * return at once when no gain request is live. Manual-override detection ({@link MouseHandlerManualLookMixin}) reads
 * the raw accumulated deltas at HEAD and is unaffected.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerTurnGainMixin {

    @Inject(method = "turnPlayer", at = @At("HEAD"))
    private void utilitysuite$recordTurnFrame(double movementTime, CallbackInfo ci) {
        ManualInputHooks.onTurnFrame(movementTime);
    }

    @ModifyArg(method = "turnPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"),
            index = 0)
    private double utilitysuite$scaleTurnYaw(double yaw) {
        return ManualInputHooks.onTurnYaw(yaw);
    }

    /** Opt-in pitch counterpart: index 1 of the same call; returns the argument at once when no pitch gain is live. */
    @ModifyArg(method = "turnPlayer",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/client/player/LocalPlayer;turn(DD)V"),
            index = 1)
    private double utilitysuite$scaleTurnPitch(double pitch) {
        return ManualInputHooks.onTurnPitch(pitch);
    }
}
