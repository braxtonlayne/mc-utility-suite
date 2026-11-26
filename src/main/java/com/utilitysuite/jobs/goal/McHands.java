package com.utilitysuite.jobs.goal;

import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;

/**
 * Arm swings for direct game calls, matching what 26.3 vanilla does (the client {@code swing} is animation only now and
 * the old swing packet is gone): a mining tick swings and sends {@link ServerboundPunchPacket}, a successful block use
 * only animates (the server handles its own swing). Client thread; no allocation.
 */
public final class McHands {

    private McHands() {
    }

    /** One mining tick's swing (vanilla {@code continueAttack}). */
    public static void swingBreak(LocalPlayer player) {
        player.swing(InteractionHand.MAIN_HAND, player.getMainHandItem().getAttackAnimation(), false);
        player.connection.send(ServerboundPunchPacket.INSTANCE);
    }

    /** The swing after a successful item use on a block (vanilla {@code startUseItem}). */
    public static void swingUse(LocalPlayer player, InteractionHand hand) {
        player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
    }
}
