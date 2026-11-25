package com.utilitysuite.mixin.tools;

import com.utilitysuite.tools.packetlab.LagGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server tick heartbeat for {@link LagGuard}: vanilla sends the time packet every 20 server ticks, so its arrival
 * time and game time give the server's TPS and reveal a stalled server.
 * <p>
 * Why a mixin: no NeoForge client event fires for the time packet. TAIL runs only on the client thread (on the
 * network thread {@code ensureRunningOnSameThread} throws before reaching it). Read-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerServerTimeMixin {

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void utilitysuite$serverHeartbeat(ClientboundSetTimePacket packet, CallbackInfo ci) {
        LagGuard.onServerTimePacket(packet.getGameTime());
    }
}
