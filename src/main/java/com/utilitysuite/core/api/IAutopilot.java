package com.utilitysuite.core.api;

/**
 * The opt-in autopilot: a layer over the modules that lets chosen reflexes act even when their own module is off
 * (a lethal fall is clutched, a run gets parkour timing). Off by default, toggled from the menu's top bar or its key.
 * Every reflex still goes through the core (leases, smoothed turns), and the user's own input always wins.
 */
public interface IAutopilot {

    /** Reflexes the autopilot may call. Each one is also a setting of its own module. */
    enum Reflex {
        /** A fall that would hurt: look down and clutch with water, powder snow, hay, slime or cobweb. */
        FALL_CLUTCH,
        /** While you run: edge jumps on the last tick and air steering toward a safe landing. */
        PARKOUR,
        /** Mid-jump: place one block when it keeps the run going. Uses blocks from the hotbar. */
        PARKOUR_BLOCKS
    }

    /** How far the autopilot may go. */
    enum Reach {
        /** Only in danger (a fall that hurts). */
        DANGER_ONLY,
        /** In danger, and it also helps while you move (parkour timing, blocks). */
        ASSIST
    }

    boolean isOn();

    /** True when the autopilot is on, the reflex is allowed and the reach covers it. Allocation free. */
    boolean allows(Reflex reflex);

    Reach reach();
}
