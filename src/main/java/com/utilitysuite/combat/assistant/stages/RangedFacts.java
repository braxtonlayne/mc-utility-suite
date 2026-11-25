package com.utilitysuite.combat.assistant.stages;

/**
 * What the ranged shot stage needs about our own main hand. The Minecraft implementation
 * ({@code combat.assistant.mc.McRangedFacts}) reads the local player once per {@link #refresh}; tests use a fake.
 * Client thread only, allocation free.
 */
public interface RangedFacts {

    int NONE = 0;
    int BOW = 1;
    int CROSSBOW = 2;
    int TRIDENT = 3;

    void refresh();

    /** {@link #NONE}, {@link #BOW}, {@link #CROSSBOW} or {@link #TRIDENT} (a trident with Riptide counts as NONE). */
    int weapon();

    /** Ticks the main-hand item has been in use; 0 when not using it. */
    int useTicks();

    /** The held crossbow is loaded. */
    boolean crossbowCharged();

    /** Ticks of USE a crossbow needs before releasing loads it (25 without Quick Charge). */
    int crossbowChargeTicks();
}
