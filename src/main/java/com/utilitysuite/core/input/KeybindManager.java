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
 * Clean-Screen = F10 (unused by vanilla), World Atlas = M (unused by vanilla). Quick toggles and the hand-over hold key start unbound. All bindings only act in game (no screen open),
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
        TOGGLE_SCOUT_CAMERA("toggle_scout_camera", GLFW.GLFW_KEY_RIGHT_ALT), // freecam (Freelook holds Left Alt)
        TOGGLE_AUTOPILOT("toggle_autopilot", InputConstants.UNKNOWN.getValue()),
        /** Hold to hand the whole body to the autopilot (read with {@link #isHeld}; unbound by default). */
        HAND_OVER("hand_over", InputConstants.UNKNOWN.getValue()),
        /** Opens the full-screen World Atlas. M is not bound by vanilla 1.21.1. */
        OPEN_WORLD_ATLAS("open_world_atlas", GLFW.GLFW_KEY_M),
        /** Hold to open the action wheel (read with {@link #isHeld}; unbound by default: every free key is taken). */
        RADIAL_WHEEL("radial_wheel", InputConstants.UNKNOWN.getValue()),
        /** Ctrl+K: the command palette (vanilla binds nothing to K). */
        COMMAND_PALETTE("command_palette", GLFW.GLFW_KEY_K, true),
        UNDO_SETTING("undo_setting", InputConstants.UNKNOWN.getValue());

        private static final KeyId[] VALUES = values();

        private final String name;
        private final int defaultKey;
        private final boolean ctrl;

        KeyId(String name, int defaultKey) {
            this(name, defaultKey, false);
        }

        KeyId(String name, int defaultKey, boolean ctrl) {
            this.name = name;
            this.defaultKey = defaultKey;
            this.ctrl = ctrl;
        }

        /** Short id, e.g. {@code stop_all}. */
        public String id() {
            return name;
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

    /**
     * Runs a key's handlers as if it had been pressed (action registry: wheel slices, command palette). Client thread.
     * Returns false when the key has no handler.
     */
    public static boolean fire(KeyId id) {
        Runnable[] hs = HANDLERS[id.ordinal()];
        for (Runnable h : hs) {
            try {
                h.run();
            } catch (RuntimeException e) {
                LOG.error("Key handler for {} failed", id, e);
            }
        }
        return hs.length > 0;
    }

    /** Pluggable handler for the Open Menu key; the UI milestone sets it. Same as {@code onPressed(OPEN_MENU, ...)}. */
    public static void setOpenMenuHandler(Runnable handler) {
        onPressed(KeyId.OPEN_MENU, handler);
    }

    /** The vanilla mapping (null before {@code RegisterKeyMappingsEvent}). For display, e.g. the bound key name. */
    public static KeyMapping mapping(KeyId id) {
        return MAPPINGS[id.ordinal()];
    }

    /**
     * True while the key is held in game (false while unbound, before registration, or with a screen open, where
     * vanilla releases every mapping). For hold keys; a field read, allocation free.
     */
    public static boolean isHeld(KeyId id) {
        KeyMapping m = MAPPINGS[id.ordinal()];
        return m != null && !m.isUnbound() && m.isDown();
    }

    /**
     * Every registered mapping in the suite's category (this class's and the ones modules register), in Controls-screen
     * order. Menu build time only; allocates a list.
     */
    public static java.util.List<KeyMapping> suiteMappings() {
        java.util.List<KeyMapping> out = new java.util.ArrayList<>();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc == null || mc.options == null) {
            return out;
        }
        for (KeyMapping m : mc.options.keyMappings) {
            if (CATEGORY.equals(m.getCategory())) {
                out.add(m);
            }
        }
        return out;
    }

    /** True when another mapping that can be active at the same time is bound to the same key. */
    public static boolean hasConflict(KeyMapping m) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (m == null || m.isUnbound() || mc == null || mc.options == null) {
            return false;
        }
        for (KeyMapping o : mc.options.keyMappings) {
            if (o != m && m.same(o) && (m.getKeyConflictContext().conflicts(o.getKeyConflictContext())
                    || o.getKeyConflictContext().conflicts(m.getKeyConflictContext()))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Rebinds a mapping from the suite menu (the same calls the Controls screen makes) and saves options.txt.
     * {@link InputConstants#UNKNOWN} unbinds it.
     */
    public static void rebind(KeyMapping m, InputConstants.Key key) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (m == null || mc == null || mc.options == null) {
            return;
        }
        mc.options.setKey(m, key);
        KeyMapping.resetMapping();
        mc.options.save();
    }

    /**
     * Saved form of a mapping for profile bundles: the key name ({@code key.keyboard.k}) plus {@code ":CONTROL"} etc.
     * when it has a modifier.
     */
    public static String saveString(KeyMapping m) {
        net.neoforged.neoforge.client.settings.KeyModifier mod = m.getKeyModifier();
        return m.saveString() + (mod == net.neoforged.neoforge.client.settings.KeyModifier.NONE ? "" : ":" + mod);
    }

    /** Applies a {@link #saveString} value (bundle import) and saves options.txt. Returns false for a bad value. */
    public static boolean applySaveString(KeyMapping m, String value) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (m == null || value == null || mc == null || mc.options == null) {
            return false;
        }
        int colon = value.indexOf(':');
        String keyName = colon < 0 ? value : value.substring(0, colon);
        net.neoforged.neoforge.client.settings.KeyModifier mod = colon < 0
                ? net.neoforged.neoforge.client.settings.KeyModifier.NONE
                : net.neoforged.neoforge.client.settings.KeyModifier.valueFromString(value.substring(colon + 1));
        InputConstants.Key key;
        try {
            key = InputConstants.getKey(keyName);
        } catch (IllegalArgumentException e) {
            return false;
        }
        m.setKeyModifierAndCode(mod, key);
        KeyMapping.resetMapping();
        mc.options.save();
        return true;
    }

    /** Mod-bus listener. */
    public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
        for (KeyId id : IDS) {
            KeyMapping m = new KeyMapping(id.translationKey(), KeyConflictContext.IN_GAME,
                    id.ctrl ? net.neoforged.neoforge.client.settings.KeyModifier.CONTROL
                            : net.neoforged.neoforge.client.settings.KeyModifier.NONE,
                    InputConstants.Type.KEYSYM, id.defaultKey(), CATEGORY);
            MAPPINGS[id.ordinal()] = m;
            event.register(m);
        }
    }

    /** Client tick: dispatches every queued press. Allocation free. */
    public static void tick() {
        LookCapture.tick(); // a camera hold lapses unless its owner renews it this tick
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
