package com.utilitysuite.core.input;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import org.lwjgl.glfw.GLFW;

/**
 * Menu movement support (added for SmartInventory's "move in menus" option). While an inventory/container screen is
 * open, vanilla stops forwarding key events to {@link KeyMapping}s, so the player stands still. {@link #apply} mirrors
 * the user's <em>physically held</em> movement keys (forward/back/left/right/jump/sprint, optionally sneak) into the
 * vanilla mappings once per tick. This is the user's own input, not automation:
 * <ul>
 *   <li>keys currently held by automation ({@link IInputSmoother#isKeyHeldByAutomation}) are left alone;</li>
 *   <li>a newly pressed movement key is reported to the arbiter as {@link ManualInput#MOVEMENT_KEY}, exactly like the
 *       in-game hook does, so manual input still wins over automation while a menu is open.</li>
 * </ul>
 * The caller decides which screens qualify (never chat, signs, books, anvils or a focused text box). When the screen
 * closes vanilla re-syncs every mapping ({@code KeyMapping.setAll()} on mouse grab); {@link #stop()} additionally
 * restores the physical state if the caller turns the feature off while a screen is still open.
 * <p>
 * Client thread only; allocation free. Lives in {@code core.input} because only this package may call
 * {@link KeyMapping#setDown}.
 */
public final class ScreenMovementPassthrough {

    private static final SimKey[] KEYS = {SimKey.FORWARD, SimKey.BACK, SimKey.LEFT, SimKey.RIGHT, SimKey.JUMP,
            SimKey.SPRINT, SimKey.SNEAK};
    private static final boolean[] LAST_DOWN = new boolean[KEYS.length];
    private static boolean active;

    private ScreenMovementPassthrough() {
    }

    /**
     * Mirrors the physically held movement keys into the vanilla mappings. Call once per client tick while a qualifying
     * screen is open.
     *
     * @param includeSneak also mirror the sneak key (off by default: shift-clicking would make the player crouch)
     */
    public static void apply(IInputSmoother input, IActionArbiter arbiter, boolean includeSneak) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        Options o = mc.options;
        long window = mc.getWindow().getWindow();
        boolean reported = false;
        for (int i = 0; i < KEYS.length; i++) {
            SimKey key = KEYS[i];
            KeyMapping m = mapping(o, key);
            boolean physical = (key != SimKey.SNEAK || includeSneak) && isPhysicallyDown(window, m);
            if (physical && !LAST_DOWN[i] && !reported) {
                arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
                reported = true;
            }
            LAST_DOWN[i] = physical;
            if (input.isKeyHeldByAutomation(key)) {
                continue;
            }
            if (isToggle(o, key)) {
                continue; // toggle sneak/sprint keep their latched state; never flip them from a menu
            }
            if (m.isDown() != physical) {
                m.setDown(physical);
            }
        }
        active = true;
    }

    /** Stops mirroring: every mapping the passthrough drove is set back to the physical state. */
    public static void stop() {
        if (!active) {
            return;
        }
        active = false;
        Minecraft mc = Minecraft.getInstance();
        Options o = mc.options;
        long window = mc.getWindow().getWindow();
        IInputSmoother input = com.utilitysuite.core.api.Services.input();
        for (int i = 0; i < KEYS.length; i++) {
            LAST_DOWN[i] = false;
            SimKey key = KEYS[i];
            if (input.isKeyHeldByAutomation(key) || isToggle(o, key)) {
                continue;
            }
            KeyMapping m = mapping(o, key);
            boolean physical = mc.screen == null && isPhysicallyDown(window, m);
            if (m.isDown() != physical) {
                m.setDown(physical);
            }
        }
    }

    /** True while {@link #apply} ran since the last {@link #stop()}. */
    public static boolean isActive() {
        return active;
    }

    private static boolean isToggle(Options o, SimKey key) {
        return (key == SimKey.SNEAK && o.toggleCrouch().get()) || (key == SimKey.SPRINT && o.toggleSprint().get());
    }

    private static KeyMapping mapping(Options o, SimKey key) {
        return switch (key) {
            case FORWARD -> o.keyUp;
            case BACK -> o.keyDown;
            case LEFT -> o.keyLeft;
            case RIGHT -> o.keyRight;
            case JUMP -> o.keyJump;
            case SNEAK -> o.keyShift;
            case SPRINT -> o.keySprint;
            default -> throw new IllegalArgumentException(key.name());
        };
    }

    private static boolean isPhysicallyDown(long window, KeyMapping m) {
        if (m.isUnbound()) {
            return false;
        }
        InputConstants.Key k = m.getKey();
        if (k.getType() == InputConstants.Type.KEYSYM) {
            return InputConstants.isKeyDown(window, k.getValue());
        }
        if (k.getType() == InputConstants.Type.MOUSE) {
            return GLFW.glfwGetMouseButton(window, k.getValue()) == GLFW.GLFW_PRESS;
        }
        return false;
    }
}
