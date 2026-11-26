package com.utilitysuite.travel;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.input.KeybindManager;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;

/**
 * The travel modules' own key bindings (category "Utility Suite", unbound by default except the parkour [ and ]):
 * start/stop the autopilot, start/stop bridging and mark bridge points, the parkour hold-to-take-over key, and the
 * parkour pin-target ([) and allowed-block (]) keys. Surface mode uses the core's
 * {@code TOGGLE_TERRAIN_SURFACE} key. Read only here (the travel bootstrap's tick handler and
 * {@link #takeoverHeld()}).
 */
public final class TravelKeys {

    public static final String AUTOPILOT = "key.utilitysuite.travel_autopilot";
    public static final String BRIDGE = "key.utilitysuite.bridge_toggle";
    public static final String BRIDGE_MARK = "key.utilitysuite.bridge_mark";

    public static final String TOGGLE_SPRINT = "key.utilitysuite.toggle_sprint";
    public static final String TOGGLE_SNEAK = "key.utilitysuite.toggle_sneak";
    /** Hold to take over (design 2026-11-22, D3: ships unbound): Movement Assist runs Takeover while it is held. */
    public static final String PARKOUR_TAKEOVER = "key.utilitysuite.parkour_takeover";
    /** [ (vanilla binds nothing to it): pin the block looked at as Movement Assist's jump target, again to clear. */
    public static final String PARKOUR_PIN = "key.utilitysuite.parkour_pin_target";
    /**
     * ] (vanilla binds nothing to it): with "Place only on allowed blocks" on, add (or remove) the type of the block
     * looked at to the blocks Parkour blocks may place against (maps that restrict placement).
     */
    public static final String PARKOUR_MARK = "key.utilitysuite.parkour_mark_block";

    private static KeyMapping autopilot;
    private static KeyMapping bridge;
    private static KeyMapping bridgeMark;
    private static KeyMapping toggleSprint;
    private static KeyMapping toggleSneak;
    private static KeyMapping parkourTakeover;
    private static KeyMapping parkourPin;
    private static KeyMapping parkourMark;

    private TravelKeys() {
    }

    /** Mod-bus listener. */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        autopilot = mapping(AUTOPILOT);
        bridge = mapping(BRIDGE);
        bridgeMark = mapping(BRIDGE_MARK);
        toggleSprint = mapping(TOGGLE_SPRINT);
        toggleSneak = mapping(TOGGLE_SNEAK);
        parkourTakeover = mapping(PARKOUR_TAKEOVER);
        parkourPin = mapping(PARKOUR_PIN, InputConstants.KEY_LBRACKET);
        parkourMark = mapping(PARKOUR_MARK, InputConstants.KEY_RBRACKET);
        event.register(autopilot);
        event.register(bridge);
        event.register(bridgeMark);
        event.register(toggleSprint);
        event.register(toggleSneak);
        event.register(parkourTakeover);
        event.register(parkourPin);
        event.register(parkourMark);
    }

    /** Dispatches the movement toggle keys once per client tick. Allocation free. */
    public static void tickToggles(Runnable onSprint, Runnable onSneak) {
        drain(toggleSprint, onSprint);
        drain(toggleSneak, onSneak);
    }

    /**
     * True while the hold-to-take-over key is held (false while unbound, before registration, or with a screen open,
     * where vanilla releases every mapping). A field read; allocation free.
     */
    public static boolean takeoverHeld() {
        KeyMapping m = parkourTakeover;
        return m != null && !m.isUnbound() && m.isDown();
    }

    private static KeyMapping mapping(String key) {
        return mapping(key, InputConstants.UNKNOWN.getValue());
    }

    private static KeyMapping mapping(String key, int defaultKey) {
        return new KeyMapping(key, KeyConflictContext.IN_GAME, InputConstants.Type.KEYBOARD, defaultKey,
                KeybindManager.CATEGORY);
    }

    /** Dispatches the parkour pin / mark keys once per client tick. Allocation free. */
    public static void tickParkour(Runnable onPin, Runnable onMark) {
        drain(parkourPin, onPin);
        drain(parkourMark, onMark);
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
