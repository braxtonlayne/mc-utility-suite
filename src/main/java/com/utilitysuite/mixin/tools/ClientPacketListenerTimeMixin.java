package com.utilitysuite.mixin.tools;

import com.utilitysuite.core.api.ServerTickFeed;
import com.utilitysuite.tools.packetlab.LagGuard;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server heartbeat: vanilla sends the time-sync packet once per 20 server ticks. One hook feeds both readers:
 * <ul>
 *   <li>{@link ServerTickFeed} (core.api): when each packet was handled, so the AFK grinder can stop swinging while
 *       the server is stalled;</li>
 *   <li>{@link LagGuard}: arrival time plus the packet's game time give the server's TPS and reveal a stall
 *       (served as {@code Services.serverHealth()}).</li>
 * </ul>
 * Why a mixin: NeoForge has no client event for {@code ClientboundSetTimePacket}. TAIL runs only on the client thread
 * (on the network thread {@code ensureRunningOnSameThread} throws before reaching it). Read-only.
 * <p>
 * 26.3: NeoForge's custom time payload ({@code ClientboundCustomSetTimePayload}) is gone (world clocks are vanilla
 * now), so this vanilla packet is the only heartbeat on every server; the former {@code NeoTimePayloadMixin} was
 * removed. The packet is a record ({@code gameTime()}).
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTimeMixin {

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void utilitysuite$serverHeartbeat(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerTickFeed.onTimePacket(System.currentTimeMillis(), packet.gameTime());
        LagGuard.onServerTimePacket(packet.gameTime());
    }
}
