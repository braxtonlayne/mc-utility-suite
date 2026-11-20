package com.utilitysuite.travel.autopilot;

/**
 * Rocket reserve &amp; elytra durability check, engine-free. Cruise is aborted (and a landing site sought) as soon as the
 * rockets left reach the reserve or the elytra has no more than {@code minDurability} uses left (one use per second of
 * flight), so there is always enough left to land under control.
 */
public final class FlightReserve {

    public enum Decision { CONTINUE, ABORT_LOW_ROCKETS, ABORT_LOW_DURABILITY }

    private FlightReserve() {
    }

    /**
     * @param rockets        usable rockets (hotbar)
     * @param reserveRockets keep at least this many for the landing
     * @param durabilityLeft elytra uses left before it stops working
     * @param minDurability  abort at or below this many uses
     */
    public static Decision evaluate(int rockets, int reserveRockets, int durabilityLeft, int minDurability) {
        if (durabilityLeft <= minDurability) {
            return Decision.ABORT_LOW_DURABILITY;
        }
        if (rockets <= reserveRockets) {
            return Decision.ABORT_LOW_ROCKETS;
        }
        return Decision.CONTINUE;
    }
}
