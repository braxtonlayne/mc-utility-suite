package com.utilitysuite.travel;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.input.KeybindManager;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * The travel modules' own key bindings (category "Utility Suite", unbound by default): start/stop the autopilot,
 * start/stop bridging and mark bridge points. Surface mode uses the core's {@code TOGGLE_TERRAIN_SURFACE} key.
 * Read only by the travel bootstrap's tick handler.
 */
public final class TravelKeys {

    public static final String AUTOPILOT = "key.utilitysuite.travel_autopilot";
    public static final String BRIDGE = "key.utilitysuite.bridge_toggle";
    public static final String BRIDGE_MARK = "key.utilitysuite.bridge_mark";

    public static final String TOGGLE_SPRINT = "key.utilitysuite.toggle_sprint";
    public static final String TOGGLE_SNEAK = "key.utilitysuite.toggle_sneak";

    private static KeyMapping autopilot;
    private static KeyMapping bridge;
    private static KeyMapping bridgeMark;
    private static KeyMapping toggleSprint;
    private static KeyMapping toggleSneak;

    private TravelKeys() {
    }

    /** Mod-bus listener. */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        autopilot = mapping(AUTOPILOT);
        bridge = mapping(BRIDGE);
        bridgeMark = mapping(BRIDGE_MARK);
        toggleSprint = mapping(TOGGLE_SPRINT);
        toggleSneak = mapping(TOGGLE_SNEAK);
        event.register(autopilot);
        event.register(bridge);
        event.register(bridgeMark);
        event.register(toggleSprint);
        event.register(toggleSneak);
    }

    /** Dispatches the movement toggle keys once per client tick. Allocation free. */
    public static void tickToggles(Runnable onSprint, Runnable onSneak) {
        drain(toggleSprint, onSprint);
        drain(toggleSneak, onSneak);
    }

    private static KeyMapping mapping(String key) {
        return new KeyMapping(key, KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                InputConstants.UNKNOWN.getValue(), KeybindManager.CATEGORY);
    }

    /** Dispatches queued presses once per client tick. Allocation free. */
    public static void tick(Runnable onAutopilot, Runnable onBridge, Runnable onBridgeMark) {
        drain(autopilot, onAutopilot);
        drain(bridge, onBridge);
        drain(bridgeMark, onBridgeMark);
    }

    private static void drain(KeyMapping m, Runnable action) {
        if (m == null) {
            return;
        }
        while (m.consumeClick()) {
            action.run();
        }
    }
}
