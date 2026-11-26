package com.utilitysuite.mixin.travel;

import com.utilitysuite.travel.mobility.VelocityScale;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundExplodePacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Velocity scale (Relaxed, single-player / allow-listed only): after vanilla applies an entity-motion packet, the
 * module may replace the local player's velocity with a scaled copy.
 * <p>
 * Why a mixin: NeoForge has no client event for {@code ClientboundSetEntityMotionPacket}. The handler runs once on the
 * network thread (where {@code ensureRunningOnSameThread} throws before RETURN) and once on the client thread, so the
 * hook only ever runs on the client thread. It does nothing unless the module is on.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerVelocityMixin {

    @Inject(method = "handleSetEntityMotion", at = @At("RETURN"))
    private void utilitysuite$scaleVelocity(ClientboundSetEntityMotionPacket packet, CallbackInfo ci) {
        net.minecraft.world.phys.Vec3 m = packet.movement(); // 26.3: record (id, movement)
        VelocityScale.afterEntityMotion(packet.id(), m.x, m.y, m.z);
    }

    /** Explosions add their knockback to the local player's velocity in the same handler: take back the unscaled share. */
    @Inject(method = "handleExplosion", at = @At("RETURN"))
    private void utilitysuite$scaleExplosion(ClientboundExplodePacket packet, CallbackInfo ci) {
        // 26.3: the knockback is optional (absent when the explosion did not push the local player)
        java.util.Optional<net.minecraft.world.phys.Vec3> kb = packet.playerKnockback();
        if (kb.isPresent()) {
            net.minecraft.world.phys.Vec3 k = kb.get();
            VelocityScale.afterExplosion(k.x, k.y, k.z);
        }
    }
}
