package com.utilitysuite.core.input;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;

/**
 * The suite's key bindings (Controls screen category "Utility Suite"). Feature modules attach actions with
 * {@link #onPressed(KeyId, Runnable)} during construction; the core dispatches presses once per client tick.
 * <p>
 * Defaults: Open Menu = Right Shift, Stop-All = Backspace (unused by vanilla in game, large and easy to hit in a hurry),
 * Clean-Screen = F10 (unused by vanilla). Quick toggles start unbound. All bindings only act in game (no screen open),
 * so typing Backspace in chat never triggers Stop-All.
 */
public final class KeybindManager {

    public static final String CATEGORY = "key.categories.utilitysuite";

    /** Suite actions with a key binding. */
    public enum KeyId {
        OPEN_MENU("open_menu", GLFW.GLFW_KEY_RIGHT_SHIFT),
        STOP_ALL("stop_all", GLFW.GLFW_KEY_BACKSPACE),
        CLEAN_SCREEN("clean_screen", GLFW.GLFW_KEY_F10),
        TOGGLE_TERRAIN_SURFACE("toggle_terrain_surface", InputConstants.UNKNOWN.getValue()),
        TOGGLE_WORLD_OVERLAYS("toggle_world_overlays", InputConstants.UNKNOWN.getValue()),
        TOGGLE_SCOUT_CAMERA("toggle_scout_camera", InputConstants.UNKNOWN.getValue()),
        TOGGLE_AUTOPILOT("toggle_autopilot", InputConstants.UNKNOWN.getValue());

        private static final KeyId[] VALUES = values();

        private final String name;
        private final int defaultKey;

        KeyId(String name, int defaultKey) {
            this.name = name;
            this.defaultKey = defaultKey;
        }

        /** Translation key, e.g. {@code key.utilitysuite.stop_all}. */
        public String translationKey() {
            return "key.utilitysuite." + name;
        }

        public int defaultKey() {
            return defaultKey;
        }
    }

    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/keys");
    private static final KeyId[] IDS = KeyId.VALUES;
    private static final KeyMapping[] MAPPINGS = new KeyMapping[IDS.length];
    private static final Runnable[][] HANDLERS = new Runnable[IDS.length][];

    static {
        Arrays.fill(HANDLERS, new Runnable[0]);
    }

    private KeybindManager() {
    }

    /**
     * Adds an action for a key. Several handlers per key are allowed and run in registration order. Call during mod
     * construction or later on the client thread.
     */
    public static synchronized void onPressed(KeyId id, Runnable handler) {
        Runnable[] old = HANDLERS[id.ordinal()];
        Runnable[] next = Arrays.copyOf(old, old.length + 1);
        next[old.length] = handler;
        HANDLERS[id.ordinal()] = next;
    }

    /** Pluggable handler for the Open Menu key; the UI milestone sets it. Same as {@code onPressed(OPEN_MENU, ...)}. */
    public static void setOpenMenuHandler(Runnable handler) {
        onPressed(KeyId.OPEN_MENU, handler);
    }

    /** The vanilla mapping (null before {@code RegisterKeyMappingsEvent}). For display, e.g. the bound key name. */
    public static KeyMapping mapping(KeyId id) {
        return MAPPINGS[id.ordinal()];
    }

    /** Mod-bus listener. */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyId id : IDS) {
            KeyMapping m = new KeyMapping(id.translationKey(), KeyConflictContext.IN_GAME, InputConstants.Type.KEYSYM,
                    id.defaultKey(), CATEGORY);
            MAPPINGS[id.ordinal()] = m;
            event.register(m);
        }
    }

    /** Client tick: dispatches every queued press. Allocation free. */
    public static void tick() {
        for (int i = 0; i < IDS.length; i++) {
            KeyMapping m = MAPPINGS[i];
            if (m == null) {
                continue;
            }
            while (m.consumeClick()) {
                Runnable[] hs = HANDLERS[i];
                for (Runnable h : hs) {
                    try {
                        h.run();
                    } catch (RuntimeException e) {
                        LOG.error("Key handler for {} failed", IDS[i], e);
                    }
                }
            }
        }
    }
}
