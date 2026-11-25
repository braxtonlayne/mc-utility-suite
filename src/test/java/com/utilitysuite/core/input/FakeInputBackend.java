package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.SimKey;

import java.util.ArrayList;
import java.util.List;

/** Test double for {@link InputBackend}: records key states, rotation, hotbar and clicks without Minecraft. */
public final class FakeInputBackend implements InputBackend {

    public final boolean[] down = new boolean[SimKey.all().length];
    /** Keys the "user" physically holds; release restores these. */
    public final boolean[] physical = new boolean[SimKey.all().length];
    public final int[] clicks = new int[SimKey.all().length];
    public final int[] presses = new int[SimKey.all().length];
    public final List<int[]> containerClicks = new ArrayList<>();
    public boolean ready = true;
    public float yaw;
    public float pitch;
    public double sensitivity = Double.NaN;
    public int rotationWrites;
    public int selectedSlot;
    /** The "player's" movement input of this tick (what {@code MovementInputUpdateEvent} carries). */
    public final SharedInputOverlay.MoveFields move = new SharedInputOverlay.MoveFields();
    public int moveWrites;

    @Override
    public boolean readMoveAxes(SharedInputOverlay.MoveFields f) {
        if (!ready) {
            return false;
        }
        f.forwardImpulse = move.forwardImpulse;
        f.leftImpulse = move.leftImpulse;
        return true;
    }

    @Override
    public void writeMoveAxes(SharedInputOverlay.MoveFields f) {
        move.forwardImpulse = f.forwardImpulse;
        move.leftImpulse = f.leftImpulse;
        move.up = f.up;
        move.down = f.down;
        move.left = f.left;
        move.right = f.right;
        moveWrites++;
    }

    public boolean isDown(SimKey k) {
        return down[k.ordinal()];
    }

    public boolean anyDown() {
        for (boolean b : down) {
            if (b) {
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    @Override
    public void pressKey(SimKey key) {
        down[key.ordinal()] = true;
        presses[key.ordinal()]++;
    }

    @Override
    public void holdKey(SimKey key) {
        down[key.ordinal()] = true;
    }

    @Override
    public void releaseKey(SimKey key) {
        down[key.ordinal()] = physical[key.ordinal()];
    }

    @Override
    public void clickKey(SimKey key) {
        clicks[key.ordinal()]++;
    }

    @Override
    public float yaw() {
        return yaw;
    }

    @Override
    public float pitch() {
        return pitch;
    }

    @Override
    public double mouseSensitivity() {
        return sensitivity;
    }

    @Override
    public void setRotation(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        rotationWrites++;
    }

    @Override
    public void selectHotbarSlot(int slot) {
        selectedSlot = slot;
    }

    @Override
    public boolean clickContainerSlot(int slot, int button, IContainerClicker.Click click) {
        containerClicks.add(new int[]{slot, button, click.ordinal()});
        return true;
    }

    public final java.util.List<String> placedRecipes = new java.util.ArrayList<>();

    @Override
    public boolean placeRecipe(String recipeId, boolean craftAll) {
        placedRecipes.add(recipeId);
        return true;
    }

    /** Entity id under the crosshair (-1: none). */
    public int crosshairEntity = -1;

    @Override
    public int crosshairEntityId() {
        return crosshairEntity;
    }
}
