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
 * Server heartbeat, NeoForge path: a NeoForge server sends NeoForge clients {@code ClientboundCustomSetTimePayload}
 * instead of the vanilla {@code ClientboundSetTimePacket} ({@code MinecraftServer#synchronizeTime}), so the vanilla hook
 * in {@link ClientPacketListenerTimeMixin} never fires there (single player included: playtest 2026-11-25, Lag Guard
 * never saw a packet and a {@code /tick freeze} went unnoticed). Feeds the same two readers with the payload's game
 * time. Why a mixin: no event exposes the payload; the handler is registered by NeoForge for the main thread. Read-only.
 */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public abstract class NeoSetTimePayloadMixin {

    @Inject(method = "handle(Lnet/neoforged/neoforge/network/payload/ClientboundCustomSetTimePayload;"
            + "Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V", at = @At("TAIL"), remap = false)
    private static void utilitysuite$serverHeartbeat(ClientboundCustomSetTimePayload payload, IPayloadContext context,
                                                     CallbackInfo ci) {
        ServerTickFeed.onTimePacket(System.currentTimeMillis());
        LagGuard.onServerTimePacket(payload.gameTime());
    }
}
