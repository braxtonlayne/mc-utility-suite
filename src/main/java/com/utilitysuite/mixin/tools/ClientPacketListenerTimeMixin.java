package com.utilitysuite.mixin.tools;

import com.utilitysuite.core.api.ServerTickFeed;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundSetTimePacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Server heartbeat for {@link ServerTickFeed}: records when each time-sync packet (one per 20 server ticks) is handled,
 * so the AFK grinder can stop swinging while the server is stalled.
 * <p>
 * Why a mixin: NeoForge has no client event for {@code ClientboundSetTimePacket}. TAIL runs only on the client thread
 * (on the network thread {@code ensureRunningOnSameThread} throws before reaching it). Read-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTimeMixin {

    @Inject(method = "handleSetTime", at = @At("TAIL"))
    private void utilitysuite$recordServerTime(ClientboundSetTimePacket packet, CallbackInfo ci) {
        ServerTickFeed.onTimePacket(System.currentTimeMillis());
    }
}
