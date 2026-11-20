package com.utilitysuite.mixin.travel;

import com.utilitysuite.travel.terrain.TerrainHooks;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Posts {@code SuiteEvent.PositionCorrection} whenever the server sets the local player's position, so features that
 * bend movement (surface traversal) can notice the server rejecting it and back off.
 * <p>
 * Why a mixin: NeoForge has no client event for {@code ClientboundPlayerPositionPacket}. Read-only: the position before
 * and after the vanilla handler is compared; nothing is changed. The handler runs once on the network thread (where
 * {@code ensureRunningOnSameThread} throws before RETURN) and once on the client thread; the hook ignores the former.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerCorrectionMixin {

    @Inject(method = "handleMovePlayer", at = @At("HEAD"))
    private void utilitysuite$beforeCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        TerrainHooks.beforeServerPosition();
    }

    @Inject(method = "handleMovePlayer", at = @At("RETURN"))
    private void utilitysuite$afterCorrection(ClientboundPlayerPositionPacket packet, CallbackInfo ci) {
        TerrainHooks.afterServerPosition();
    }
}
