package com.utilitysuite.mixin;

import com.utilitysuite.core.input.MoveInputs;
import com.utilitysuite.core.input.ServerPose;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Server-visible pose: the sneak and sprint states {@code LocalPlayer} reports to the server come from
 * {@link ServerPose} while an automation lease overrides them (e.g. Bridge Assist's walk mode crouching at each edge for
 * the server while the player walks at full speed). The local player is untouched: movement, speed and rendering use
 * the real states.
 * <p>
 * Why a mixin: no NeoForge event covers {@code sendChanges()}. In 26.3 (checked against the 26.3.0.118 sources) the
 * sneak state is no longer a player command: {@code sendChanges()} compares {@code input.keyPresses} with
 * {@code lastSentInput} and sends a {@code ServerboundPlayerInputPacket} carrying the record on change. Redirecting
 * every read of {@code input.keyPresses} inside {@code sendChanges()} (the compare, the packet and the bookkeeping
 * assignment) sends the overridden shift flag and keeps {@code lastSentInput} consistent, so the real state is re-sent
 * once the override ends. Sprint still goes through {@code sendIsSprintingIfNeeded()}, which compares
 * {@code isSprinting()} against {@code wasSprinting}; redirecting that one read keeps the vanilla bookkeeping.
 * Allocation free: the substituted record comes from {@link MoveInputs#input}'s cache.
 */
@Mixin(LocalPlayer.class)
public abstract class LocalPlayerServerPoseMixin {

    @Redirect(method = "sendChanges",
            at = @At(value = "FIELD",
                    target = "Lnet/minecraft/client/player/ClientInput;keyPresses:Lnet/minecraft/world/entity/player/Input;",
                    opcode = Opcodes.GETFIELD))
    private Input utilitysuite$serverInput(ClientInput input) {
        Input k = input.keyPresses;
        boolean sneak = ServerPose.sneak(k.shift());
        if (sneak == k.shift()) {
            return k;
        }
        return MoveInputs.input(k.forward(), k.backward(), k.left(), k.right(), k.jump(), sneak, k.sprint());
    }

    @Redirect(method = "sendIsSprintingIfNeeded",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;isSprinting()Z"))
    private boolean utilitysuite$serverSprint(LocalPlayer self) {
        return ServerPose.sprint(self.isSprinting());
    }
}
