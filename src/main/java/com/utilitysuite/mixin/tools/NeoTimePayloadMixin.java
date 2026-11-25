package com.utilitysuite.mixin.tools;

import com.utilitysuite.core.api.ServerTickFeed;
import com.utilitysuite.tools.packetlab.LagGuard;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.ClientboundCustomSetTimePayload;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server heartbeat on NeoForge servers (single player included): when the client has NeoForge's time channel, the
 * server sends {@code ClientboundCustomSetTimePayload} instead of vanilla's {@code ClientboundSetTimePacket}
 * ({@code MinecraftServer.synchronizeTime}), so {@link ClientPacketListenerTimeMixin} never fires there. This feeds the
 * same two readers from NeoForge's handler. Why a mixin: no event exposes the payload. Payload handlers run on the
 * client thread (NeoForge's default). Read-only. Target checked against the NeoForge 21.1.265 sources:
 * {@code ClientPayloadHandler#handle(ClientboundCustomSetTimePayload, IPayloadContext)}.
 */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public abstract class NeoTimePayloadMixin {

    @Inject(method = "handle(Lnet/neoforged/neoforge/network/payload/ClientboundCustomSetTimePayload;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V", at = @At("TAIL"))
    private static void utilitysuite$serverHeartbeat(ClientboundCustomSetTimePayload payload, IPayloadContext context,
                                                     CallbackInfo ci) {
        ServerTickFeed.onTimePacket(System.currentTimeMillis(), payload.gameTime());
        LagGuard.onServerTimePacket(payload.gameTime());
    }
}
