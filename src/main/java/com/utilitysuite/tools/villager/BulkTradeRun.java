package com.utilitysuite.tools.villager;

/**
 * Engine-free state of one bulk-trade run: repeats the selected offer, paced by a minimum number of ticks between
 * trades, until the inputs run out, the offer locks (uses >= maxUses), the inventory has no room for the result, the
 * screen closes, or the user stops it. Allocation free.
 */
public final class BulkTradeRun {

    public enum Stop { NONE, OUT_OF_INPUTS, LOCKED, INVENTORY_FULL, SCREEN_CLOSED, MANUAL, MAX_TRADES }

    public enum Action { NONE, TRADE }

    private boolean running;
    private Stop stop = Stop.NONE;
    private int offerIndex = -1;
    private int cooldown;
    private int trades;

    public void start(int offerIndex) {
        this.offerIndex = offerIndex;
        running = offerIndex >= 0;
        stop = running ? Stop.NONE : Stop.OUT_OF_INPUTS;
        cooldown = 0;
        trades = 0;
    }

    public void stop(Stop reason) {
        if (running) {
            running = false;
            stop = reason;
        }
    }

    /** Stop condition for the current facts, NONE when trading may go on. */
    public static Stop check(boolean screenOpen, int uses, int maxUses, boolean hasInputs, boolean hasRoom) {
        if (!screenOpen) {
            return Stop.SCREEN_CLOSED;
        }
        if (uses >= maxUses) {
            return Stop.LOCKED;
        }
        if (!hasInputs) {
            return Stop.OUT_OF_INPUTS;
        }
        if (!hasRoom) {
            return Stop.INVENTORY_FULL;
        }
        return Stop.NONE;
    }

    /**
     * One client tick.
     *
     * @param minTicks minimum ticks between two trades
     * @param maxTrades cap on trades per run (0 = none)
     * @return TRADE when the module should execute the offer once now
     */
    public Action tick(boolean screenOpen, int uses, int maxUses, boolean hasInputs, boolean hasRoom, int minTicks,
                       int maxTrades) {
        if (!running) {
            return Action.NONE;
        }
        if (cooldown > 0) {
            cooldown--;
            if (!screenOpen) {
                stop(Stop.SCREEN_CLOSED);
            }
            return Action.NONE;
        }
        Stop s = check(screenOpen, uses, maxUses, hasInputs, hasRoom);
        if (s != Stop.NONE) {
            stop(s);
            return Action.NONE;
        }
        if (maxTrades > 0 && trades >= maxTrades) {
            stop(Stop.MAX_TRADES);
            return Action.NONE;
        }
        trades++;
        cooldown = Math.max(1, minTicks);
        return Action.TRADE;
    }

    public boolean running() {
        return running;
    }

    public Stop stopReason() {
        return stop;
    }

    public int offerIndex() {
        return offerIndex;
    }

    public int trades() {
        return trades;
    }
}
