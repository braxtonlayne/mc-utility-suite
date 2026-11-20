package com.utilitysuite.travel.autopilot;

/** Stop conditions shared by every autopilot mode. Engine-free. */
public final class SafetyBudget {

    public enum Stop { NONE, LOW_HEALTH, LOW_FOOD }

    private SafetyBudget() {
    }

    /**
     * @param minHealth stop at or below this many health points (0 disables)
     * @param minFood   stop at or below this food level (0 disables)
     */
    public static Stop check(float health, int food, float minHealth, int minFood) {
        if (minHealth > 0 && health <= minHealth) {
            return Stop.LOW_HEALTH;
        }
        if (minFood > 0 && food <= minFood) {
            return Stop.LOW_FOOD;
        }
        return Stop.NONE;
    }
}
