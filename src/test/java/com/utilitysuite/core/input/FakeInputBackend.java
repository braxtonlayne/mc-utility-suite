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
    public int rotationWrites;
    public int selectedSlot;

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
}
