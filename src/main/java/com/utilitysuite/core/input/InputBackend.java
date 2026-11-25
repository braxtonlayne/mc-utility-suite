package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.SimKey;

/**
 * The game-facing side of {@link InputSmoother}: key states, camera, hotbar and container clicks. The Minecraft
 * implementation is {@link MinecraftInputBackend}; tests use a fake so the smoother runs without the game.
 * Client thread only.
 */
public interface InputBackend {

    /** True when a local player exists (keys and camera can be driven). */
    boolean isReady();

    /** The user's "Invert Mouse" option: vertical mouse motion maps to the opposite pitch direction. */
    default boolean invertMouseY() {
        return false;
    }

    /** Automation starts driving the key: remember the user's state and set it down. */
    void pressKey(SimKey key);

    /** Re-asserts the down state each tick so vanilla's own key handling does not clear it. */
    void holdKey(SimKey key);

    /** Automation stops driving the key: restore what the user physically holds (or the prior toggle state). */
    void releaseKey(SimKey key);

    /** Registers one click (a press edge) for keys whose vanilla handling consumes clicks (attack/use). */
    void clickKey(SimKey key);

    /**
     * Copies the forward and strafe impulse of the local player's movement input of this tick into {@code f}. False
     * when there is none (no player). The default has no movement input.
     */
    default boolean readMoveAxes(SharedInputOverlay.MoveFields f) {
        return false;
    }

    /** Writes the forward/strafe impulse and the four direction flags of {@code f} onto that movement input. */
    default void writeMoveAxes(SharedInputOverlay.MoveFields f) {
    }

    float yaw();

    float pitch();

    /** Normalized mouse sensitivity in [0, 1] (vanilla {@code Options.sensitivity()}), or {@code NaN} when unknown. */
    default double mouseSensitivity() {
        return Double.NaN;
    }

    /** Sets the player's real (rendered) rotation. */
    void setRotation(float yaw, float pitch);

    void selectHotbarSlot(int slot);

    /** Performs a click on the open menu (or the inventory menu). Returns false if it could not be performed. */
    boolean clickContainerSlot(int slot, int button, IContainerClicker.Click click);

    /** Recipe-book placement into the open crafting menu. Returns false if no menu is open or the recipe is unknown. */
    boolean placeRecipe(String recipeId, boolean craftAll);

    /** Crafter slot-lock toggle on the open crafter menu. Returns false when no crafter is open. */
    default boolean setCrafterSlotEnabled(int slot, boolean enabled) {
        return false;
    }
}
