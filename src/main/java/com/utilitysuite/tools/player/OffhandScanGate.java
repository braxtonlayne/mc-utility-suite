package com.utilitysuite.tools.player;

/**
 * Engine-free scan timing for the {@link OffhandManager}: an inventory scan is due every
 * {@link OffhandManager#SCAN_INTERVAL} ticks, at once when health or gliding changed, and on every tick of a short
 * window after the local player's totem popped. The window (one scan interval long) covers the server's packets
 * arriving out of step: the pop event can come a tick or two before the emptied offhand slot and the new health do.
 * It only removes the wait for the next scan; the reaction delay before the swap is the caller's and stays. No
 * allocation.
 */
final class OffhandScanGate {

    /** Ticks scanned one by one after a pop. After that the interval scan has taken over again. */
    static final int POP_WINDOW = OffhandManager.SCAN_INTERVAL;

    private int popTicks;

    /** The local player's totem popped: scan on each of the next {@link #POP_WINDOW} ticks. */
    void popped() {
        popTicks = POP_WINDOW;
    }

    /** One tick without a pending swap. True when the inventory should be scanned now. */
    boolean due(boolean changed, int clock) {
        boolean afterPop = popTicks > 0;
        if (afterPop) {
            popTicks--;
        }
        return afterPop || changed || clock % OffhandManager.SCAN_INTERVAL == 0;
    }

    /** A swap was planned, or the state was dropped: the window is over. */
    void clear() {
        popTicks = 0;
    }

    boolean inPopWindow() {
        return popTicks > 0;
    }
}
