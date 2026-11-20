package com.utilitysuite.mixin;

import com.utilitysuite.core.input.SilentRotation;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * SILENT rotation: while {@link SilentRotation#isActive()}, outgoing {@code ServerboundMovePlayerPacket}s carry the
 * automation's angles while the rendered camera stays where the user put it.
 * <p>
 * Why a mixin: no NeoForge event wraps {@code LocalPlayer.sendPosition()}, and the packet reads {@code getYRot()} /
 * {@code getXRot()} directly. We swap the silent angles in at HEAD and restore the real ones at every RETURN, so
 * nothing outside this one method ever sees them (render, input and movement all ran earlier in the tick).
 * {@code yRotLast}/{@code xRotLast} track the sent angles, so the real camera is re-sent once silent mode ends.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerSilentRotationMixin {

    @Unique
    private boolean utilitysuite$swapped;
    @Unique
    private float utilitysuite$realYaw;
    @Unique
    private float utilitysuite$realPitch;

    @Inject(method = "sendPosition", at = @At("HEAD"))
    private void utilitysuite$swapInSilentRotation(CallbackInfo ci) {
        if (!SilentRotation.isActive()) {
            return;
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        utilitysuite$realYaw = self.getYRot();
        utilitysuite$realPitch = self.getXRot();
        self.setYRot(SilentRotation.yaw());
        self.setXRot(SilentRotation.pitch());
        utilitysuite$swapped = true;
    }

    @Inject(method = "sendPosition", at = @At("RETURN"))
    private void utilitysuite$restoreRealRotation(CallbackInfo ci) {
        if (!utilitysuite$swapped) {
            return;
        }
        LocalPlayer self = (LocalPlayer) (Object) this;
        self.setYRot(utilitysuite$realYaw);
        self.setXRot(utilitysuite$realPitch);
        utilitysuite$swapped = false;
    }
}
