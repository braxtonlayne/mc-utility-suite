package com.utilitysuite.jobs.fieldwork;

/**
 * Engine-free hold latch for field work during a server stall: fed once per tick with the Lag Guard verdict
 * ({@code Services.serverHealth().pauseHighRisk()}, always false while Lag Guard is off) and whether the work at hand
 * is hazardous (Nether mining, a lava plug). While it holds, the caller releases its keys and leaves its world
 * actions (breaks, placements, charges) for later; it resumes on the first tick the guard clears, which Lag Guard
 * itself only does after its stable-tick count. Allocation free.
 */
final class StallHold {

    private boolean held;
    private boolean resumed;
    private int holds;

    /** @return true while the work must hold this tick */
    boolean update(boolean stalled, boolean hazardous) {
        boolean h = stalled && hazardous;
        resumed = held && !h;
        if (h && !held) {
            holds++;
        }
        held = h;
        return h;
    }

    boolean held() {
        return held;
    }

    /** True on the first tick after a hold ended (callers re-check stale targets). */
    boolean justResumed() {
        return resumed;
    }

    /** Holds started since creation (diagnostics, tests). */
    int holds() {
        return holds;
    }

    void reset() {
        held = false;
        resumed = false;
    }
}
