package com.utilitysuite.travel.autopilot;

/** How the autopilot travels. AUTO picks BOAT when riding a boat, ELYTRA when wearing one, else OVERLAND. */
public enum AutopilotMode {
    AUTO,
    OVERLAND,
    BOAT,
    ELYTRA,
    /** Nether highway bounce: rhythmic jump-gliding along a flat tunnel (experimental). */
    NETHER_BOUNCE,
    /** Follow OLD-chunk exploration trails using TrailVector corridor estimation. */
    TRAIL
}
