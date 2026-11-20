package com.utilitysuite.jobs.goal;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.JobStatus;

import java.util.Map;

/**
 * Hook for the schematic toolkit (registered with {@link GoalEngine#registerBuildHandler}). Until one is registered,
 * {@link GoalRequest.Build} jobs stay queued as "waiting for schematic toolkit". With a handler, the goal engine first
 * gathers {@link #missingMaterials} through the dependency planner, then calls
 * {@link #startBuild} and polls {@link #buildStatus} each tick. Only core.api types cross this interface.
 */
public interface BuildHandler {

    /** Item id -> count still missing in inventory for the build. Called on the client thread. */
    Map<String, Integer> missingMaterials(GoalRequest.Build request);

    /**
     * Starts placing blocks (for {@code gatherOnly}: starts the material round only). The handler requests its own
     * leases.
     */
    void startBuild(long jobId, GoalRequest.Build request);

    /** RUNNING while placing, COMPLETED / FAILED when done, PAUSED when it waits on the user. */
    JobStatus buildStatus(long jobId);

    void cancelBuild(long jobId);
}
