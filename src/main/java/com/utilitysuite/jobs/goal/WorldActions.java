package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.jobs.planner.PlanStep;

import java.util.Map;

/**
 * Non-movement world operations the executor needs: crafting, smelting, container transfers and block lookups. One
 * operation runs at a time; the executor starts it with {@code begin*} and polls it once per tick. The Minecraft
 * implementation drives container clicks through {@code Services.clicker()} with the executor's lease.
 */
public interface WorldActions {

    enum Result { RUNNING, DONE, FAILED }

    void beginCraft(PlanStep step, ControlLease lease);

    void beginSmelt(PlanStep step, ControlLease lease);

    /** Withdraws the items from the container at {@code packedPos}. */
    void beginFetch(long packedPos, Map<String, Integer> items, ControlLease lease);

    /** Puts the items into the container at {@code packedPos}. */
    void beginDeposit(long packedPos, Map<String, Integer> items, ControlLease lease);

    Result poll();

    /** Human-readable result or failure reason of the last operation. */
    String message();

    /** Items actually moved by the last fetch/deposit, keyed by item id. */
    Map<String, Integer> transferred();

    /** Stops the running operation (preemption, cancel). */
    void abort();

    /** Block id at the position when its chunk is loaded on the client, else null. */
    String blockAtIfLoaded(String dimension, long packedPos);

    /**
     * Hazards live around a position in the player's dimension, as a mask of {@link ResumeHoldGate#EXPLOSION} (a
     * swelling creeper or primed TNT within blast reach) and {@link ResumeHoldGate#LAVA} (lava within two blocks).
     * Only the kinds in {@code kinds} are looked for. Used while a resume is held, a few times a second, never while
     * a job runs normally. 0 when the area is not loaded.
     */
    default int hazardsNear(long packedPos, int kinds) {
        return 0;
    }

    long playerPos();

    String dimension();

    /** Fortune level on the best pickaxe carried (0 when none), for yield estimates. */
    int fortuneLevel();

    /**
     * Checks the selected pickaxe/shovel against {@code minRemaining} uses left and, when it is about to break, swaps in
     * a backup of the same kind (see {@link ToolDurabilityGuard}). Cheap; the executor calls it every few ticks.
     */
    default ToolDurabilityGuard.Decision guardTool(int minRemaining, boolean protectEnchanted, int requiredTier) {
        return ToolDurabilityGuard.Decision.OK;
    }

    /** Station items placed within reach of the player ("minecraft:crafting_table"). */
    java.util.Set<String> nearbyStations();
}
