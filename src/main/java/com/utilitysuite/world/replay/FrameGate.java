package com.utilitysuite.world.replay;

/** Lets work through once every {@code every} frames (the picture-in-picture refresh rate). Engine free. */
public final class FrameGate {

    private int every = 3;
    private int counter;

    public void setEvery(int n) {
        every = Math.max(1, n);
    }

    public int every() {
        return every;
    }

    /** Forces the next {@link #tick} to pass. */
    public void invalidate() {
        counter = 0;
    }

    /** True on the frames that should refresh. */
    public boolean tick() {
        if (counter <= 0) {
            counter = every - 1;
            return true;
        }
        counter--;
        return false;
    }
}
