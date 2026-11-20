package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.goal.BuildHandler;
import net.minecraft.core.BlockPos;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Connects the goal engine's {@link BuildHandler} hook to the {@link SchematicToolkit}.
 * <p>
 * The schematic has to be loaded and diffed against the world (asynchronously) before its missing materials are
 * known, so the engine plans no materials of its own for a build job ({@link #missingMaterials} is empty) and the
 * toolkit drives the material round:
 * <ul>
 *     <li>Gather-only: the toolkit submits a {@code GatherMany} job after its first diff; this job then completes.</li>
 *     <li>Build: once the diff is ready, if materials are missing and this schematic+anchor has not had a gather round
 *     yet, the bridge queues a {@code GatherMany} job followed by the same build (anchored where it was resolved) and
 *     completes this job. The second pass builds with whatever is available, so there is at most one gather round.</li>
 * </ul>
 */
final class SchematicBuildBridge implements BuildHandler {

    private final SchematicToolkit toolkit;
    private final Set<String> gatherRounds = new HashSet<>();
    private long activeJob = -1;
    private GoalRequest.Build active;
    private boolean failed;

    SchematicBuildBridge(SchematicToolkit toolkit) {
        this.toolkit = toolkit;
    }

    @Override
    public Map<String, Integer> missingMaterials(GoalRequest.Build request) {
        return Map.of();
    }

    @Override
    public void startBuild(long jobId, GoalRequest.Build request) {
        activeJob = jobId;
        active = request;
        failed = !toolkit.handleBuild(request);
    }

    @Override
    public JobStatus buildStatus(long jobId) {
        if (jobId != activeJob || active == null) {
            return JobStatus.CANCELLED;
        }
        if (failed) {
            return JobStatus.FAILED;
        }
        Optional<SchematicJobCard> opt = toolkit.jobCard();
        if (opt.isEmpty()) {
            return JobStatus.CANCELLED; // cancelled from the toolkit side
        }
        SchematicJobCard card = opt.get();
        switch (card.phase()) {
            case "FAILED":
                return JobStatus.FAILED;
            case "COMPLETE":
                return JobStatus.COMPLETED;
            case "PAUSED":
                return JobStatus.PAUSED;
            case "READY":
            case "BUILDING":
                break;
            default:
                return JobStatus.RUNNING; // LOADING, SCANNING
        }
        if (active.gatherOnly()) {
            return toolkit.gatherSubmitted() ? JobStatus.COMPLETED : JobStatus.RUNNING;
        }
        if (!card.missing().isEmpty() && gatherRounds.add(key(card))) {
            IGoalEngine goals = Services.goalsOrNull();
            if (goals != null) {
                long anchor = BlockPos.asLong(card.anchorX(), card.anchorY(), card.anchorZ());
                goals.submit(new GoalRequest.GatherMany(card.missing(), "schematic " + card.name(), active.cost(),
                        active.dataSource()));
                goals.submit(new GoalRequest.Build(active.schematicPath(), anchor, false, active.cost(),
                        active.dataSource()));
                toolkit.cancel();
                active = null;
                return JobStatus.COMPLETED;
            }
        }
        return JobStatus.RUNNING;
    }

    @Override
    public void cancelBuild(long jobId) {
        if (jobId == activeJob) {
            toolkit.cancel();
            active = null;
            activeJob = -1;
        }
    }

    private String key(SchematicJobCard card) {
        return active.schematicPath() + "@" + card.anchorX() + "," + card.anchorY() + "," + card.anchorZ();
    }
}
