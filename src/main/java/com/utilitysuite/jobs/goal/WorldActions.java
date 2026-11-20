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

    long playerPos();

    String dimension();

    /** Fortune level on the best pickaxe carried (0 when none), for yield estimates. */
    int fortuneLevel();

    /** Station items placed within reach of the player ("minecraft:crafting_table"). */
    java.util.Set<String> nearbyStations();
}
