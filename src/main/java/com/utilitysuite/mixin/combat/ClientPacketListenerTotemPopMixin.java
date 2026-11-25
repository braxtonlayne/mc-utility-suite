package com.utilitysuite.mixin.combat;

import com.utilitysuite.combat.hud.TargetHud;
import com.utilitysuite.tools.ToolsBootstrap;
import com.utilitysuite.tools.player.OffhandManager;
import com.utilitysuite.tools.rules.LocalRules;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Totem pops: forwards entity event 35 (totem of undying used) to {@link TargetHud} for its pop count and, when it
 * was the local player's totem, to the {@link OffhandManager} so its refill scan does not wait for the interval, and
 * to {@link LocalRules} for its "seconds since totem pop" trigger.
 * <p>
 * Why a mixin: no NeoForge client event exposes entity status events, and event 35 is the only signal a client gets
 * that another entity used a totem. TAIL runs only on the client thread (on the network thread
 * {@code ensureRunningOnSameThread} throws before reaching it). Read-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerTotemPopMixin {

    @Inject(method = "handleEntityEvent", at = @At("TAIL"))
    private void utilitysuite$countTotemPop(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        if (packet.getEventId() != 35) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        Entity e = packet.getEntity(mc.level);
        if (e != null) {
            TargetHud.onTotemPop(e.getId());
            if (e == mc.player) {
                OffhandManager offhand = ToolsBootstrap.offhand();
                if (offhand != null) {
                    offhand.onLocalTotemPop();
                }
                LocalRules rules = ToolsBootstrap.rules();
                if (rules != null) {
                    rules.onLocalTotemPop();
                }
            }
        }
    }
}
