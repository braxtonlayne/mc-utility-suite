package com.utilitysuite.mixin.world;

import com.utilitysuite.tools.packetlab.AcousticRadar;
import com.utilitysuite.tools.packetlab.PacketLab;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundLevelEventPacket;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Passive acoustic radar feed: forwards sound and level event packets to {@link AcousticRadar} when PacketLab is
 * enabled.
 * <p>
 * Why a mixin: NeoForge has no client-side packet hook for raw {@code ClientboundSoundPacket} /
 * {@code ClientboundLevelEventPacket} coordinates before audio playback filtering. Read-only; never modifies packet
 * behavior. {@code TAIL} runs only on the client thread after {@code ensureRunningOnSameThread}.
 */
@Mixin(ClientPacketListener.class)
public abstract class ClientSoundPacketMixin {

    @Inject(method = "handleSoundEvent", at = @At("TAIL"))
    private void utilitysuite$onSoundEvent(ClientboundSoundPacket packet, CallbackInfo ci) {
        AcousticRadar radar = PacketLab.acousticRadar();
        if (radar == null) {
            return;
        }
        Identifier loc = packet.getSound().value().location(); // 26.3: SoundEvent is a record
        int category = AcousticRadar.classifySound(loc.getPath());
        radar.recordSound(packet.getX(), packet.getY(), packet.getZ(), System.currentTimeMillis(), category);
    }

    @Inject(method = "handleLevelEvent", at = @At("TAIL"))
    private void utilitysuite$onLevelEvent(ClientboundLevelEventPacket packet, CallbackInfo ci) {
        AcousticRadar radar = PacketLab.acousticRadar();
        if (radar == null) {
            return;
        }
        int type = packet.getType();
        int category = AcousticRadar.classifyLevelEvent(type);
        BlockPos pos = packet.getPos();
        if (packet.isGlobalEvent()) {
            LocalPlayer player = Minecraft.getInstance().player;
            if (player != null) {
                radar.recordGlobalBearing(pos.getX() - player.getX(), pos.getZ() - player.getZ(),
                        System.currentTimeMillis(), category);
            }
        } else if (AcousticRadar.isRelevantLevelEvent(type)) {
            radar.recordSound(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5,
                    System.currentTimeMillis(), category);
        }
    }
}
