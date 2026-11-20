package com.utilitysuite.travel;

import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/**
 * Status output for the travel modules. Sent on state changes only, never per tick. Status lines go to the action bar
 * and are suppressed in Clean-Screen; warnings (a mode was switched off for safety) go to chat and are always shown.
 */
public final class TravelMessages {

    private TravelMessages() {
    }

    /** One action-bar status line, e.g. "Autopilot: arrived". Suppressed in Clean-Screen. */
    public static void status(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        IModState state = Services.modState();
        if (mc.player == null || state.isCleanScreen()) {
            return;
        }
        mc.player.displayClientMessage(Component.translatable(key, args), true);
    }

    /** A chat warning that is shown even in Clean-Screen (safety-relevant). */
    public static void warn(String key, Object... args) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        mc.player.displayClientMessage(Component.translatable(key, args).withStyle(ChatFormatting.GOLD), false);
    }
}
