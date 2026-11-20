package com.utilitysuite.combat.assistant.mc;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.combat.assistant.CombatAssistant;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.input.KeybindManager;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundDamageEventPacket;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Forwards client events to the {@link CombatAssistant}: manual attacks ({@code AttackEntityEvent}), server damage
 * events (from {@code ClientPacketListenerDamageMixin}) and the assistant's own keys (Retreat, Toggle; unbound by
 * default). Manual movement presses reach the assistant through the core arbiter's manual-override state, so this
 * class never reads vanilla key bindings. Client thread only.
 */
public final class CombatEventBridge {

    private static CombatAssistant assistant;
    private static KeyMapping retreatKey;
    private static KeyMapping toggleKey;

    private CombatEventBridge() {
    }

    public static void install(CombatAssistant a) {
        assistant = a;
    }

    /** Mod-bus listener. */
    public static void onRegisterKeys(RegisterKeyMappingsEvent event) {
        retreatKey = new KeyMapping("key.utilitysuite.combat_retreat", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeybindManager.CATEGORY);
        toggleKey = new KeyMapping("key.utilitysuite.combat_toggle", KeyConflictContext.IN_GAME,
                InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), KeybindManager.CATEGORY);
        event.register(retreatKey);
        event.register(toggleKey);
    }

    /** Called by the mixin after the vanilla handler ran on the client thread. */
    public static void onDamagePacket(ClientboundDamageEventPacket packet) {
        CombatAssistant a = assistant;
        if (a != null) {
            a.onDamageEvent(packet.entityId(), packet.sourceType().getRegisteredName(), packet.sourceCauseId(),
                    packet.sourceDirectId());
        }
    }

    public static void onAttack(AttackEntityEvent event) {
        CombatAssistant a = assistant;
        Minecraft mc = Minecraft.getInstance();
        // Fires on both sides in single player; only the client-side local player counts.
        if (a != null && event.getEntity() == mc.player && event.getEntity().level().isClientSide()) {
            a.onLocalAttack(event.getTarget().getId());
        }
    }

    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS || assistant == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        int key = event.getKey();
        int scan = event.getScanCode();
        if (retreatKey != null && retreatKey.matches(key, scan)) {
            assistant.onCancelKey();
        } else if (toggleKey != null && toggleKey.matches(key, scan)) {
            toggleArmed(mc);
        }
    }

    public static void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS || assistant == null) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.screen != null) {
            return;
        }
        int b = event.getButton();
        if (retreatKey != null && retreatKey.matchesMouse(b)) {
            assistant.onCancelKey();
        } else if (toggleKey != null && toggleKey.matchesMouse(b)) {
            toggleArmed(mc);
        }
    }

    private static void toggleArmed(Minecraft mc) {
        boolean armed = !assistant.isEnabled();
        CoreBootstrap.modules().setModuleEnabled(CombatAssistant.ID, armed, Services.modState().profile());
        Services.modState().markDirty();
        if (mc.player != null && !Services.modState().isCleanScreen()) {
            mc.player.displayClientMessage(Component.translatable(armed ? "message.utilitysuite.combat_armed"
                    : "message.utilitysuite.combat_disarmed"), true);
        }
    }
}
