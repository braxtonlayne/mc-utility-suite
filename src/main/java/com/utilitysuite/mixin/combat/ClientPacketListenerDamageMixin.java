package com.utilitysuite.mixin.combat;

import com.utilitysuite.combat.assistant.mc.CombatEventBridge;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Damage attribution for the combat assistant: forwards each server damage event (victim id, damage type, cause and
 * direct entity ids) to {@link CombatEventBridge}.
 * <p>
 * Why a mixin: {@code LivingDamageEvent}/{@code LivingIncomingDamageEvent} are server-side only, and no client event
 * exposes the attacker of damage dealt to the local player or other players. TAIL runs only on the client thread
 * (on the network thread {@code ensureRunningOnSameThread} throws before reaching it). Read-only.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerDamageMixin {

    @Inject(method = "handleDamageEvent", at = @At("TAIL"))
    private void utilitysuite$forwardDamageEvent(ClientboundDamageEventPacket packet, CallbackInfo ci) {
        CombatEventBridge.onDamagePacket(packet);
    }
}
