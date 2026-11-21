package com.utilitysuite.mixin;

import com.utilitysuite.core.input.ServerPose;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Server-visible pose: the sneak and sprint states {@code LocalPlayer} reports to the server come from
 * {@link ServerPose} while an automation lease overrides them (e.g. Bridge Assist's walk mode crouching at each edge for
 * the server while the player walks at full speed). The local player is untouched: movement, speed and rendering use
 * the real states.
 * <p>
 * Why a mixin: no NeoForge event covers {@code sendPosition()}/{@code sendIsSprintingIfNeeded()}, which compare
 * {@code isShiftKeyDown()} / {@code isSprinting()} against what was last sent and send a
 * {@code ServerboundPlayerCommandPacket} on change. Redirecting just those two reads keeps the vanilla bookkeeping
 * ({@code wasShiftKeyDown}, {@code wasSprinting}), so the real state is re-sent once the override ends.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerServerPoseMixin {

    @Redirect(method = "sendPosition",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isShiftKeyDown()Z"))
    private boolean utilitysuite$serverSneak(LocalPlayer self) {
        return ServerPose.sneak(self.isShiftKeyDown());
    }

    @Redirect(method = "sendIsSprintingIfNeeded",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSprinting()Z"))
    private boolean utilitysuite$serverSprint(LocalPlayer self) {
        return ServerPose.sprint(self.isSprinting());
    }
}
