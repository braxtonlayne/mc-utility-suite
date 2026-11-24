package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.GoalRequest;
import com.utilitysuite.core.api.IGoalEngine;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.goal.BuildHandler;
import com.utilitysuite.jobs.schematic.bom.MaterialRestock;
import com.utilitysuite.jobs.schematic.mc.BuildAssist;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
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
 *     <li>Restock: when Build Assist runs out of palette items mid-build it calls the {@link BuildAssist.RestockHook}
 *     set here. If nearby remembered storage holds any of them, the bridge queues a {@code GatherMany} capped to the
 *     stored counts (so the planner makes FETCH steps only, batched with other queued jobs' errands, plus the return
 *     to the site) followed by the same build, and completes this job on the next status poll; the new pass continues
 *     from what is already placed. With nothing stored the hook declines and Build Assist keeps its card notice.</li>
 * </ul>
 */
final class SchematicBuildBridge implements BuildHandler {

    /** Remembered containers farther than this from the player are not used for a restock. */
    static final int RESTOCK_RADIUS = 96;
    /** Safety cap on restock rounds per schematic + anchor (rounds that fetch nothing must not loop forever). */
    static final int MAX_RESTOCK_ROUNDS = 32;

    private final SchematicToolkit toolkit;
    private final Set<String> gatherRounds = new HashSet<>();
    private final Map<String, Integer> restockRounds = new HashMap<>();
    private long activeJob = -1;
    private GoalRequest.Build active;
    private boolean failed;
    private Map<String, Integer> pendingRestock;

    SchematicBuildBridge(SchematicToolkit toolkit) {
        this.toolkit = toolkit;
        toolkit.setRestockHook(this::requestRestock);
    }

    @Override
    public Map<String, Integer> missingMaterials(GoalRequest.Build request) {
        return Map.of();
    }

    @Override
    public void startBuild(long jobId, GoalRequest.Build request) {
        activeJob = jobId;
        active = request;
        pendingRestock = null;
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
        Map<String, Integer> restock = pendingRestock;
        if (restock != null) {
            pendingRestock = null;
            if (handOver(card, restock, "restock for schematic ")) {
                return JobStatus.COMPLETED;
            }
        }
        if (!card.missing().isEmpty() && gatherRounds.add(key(card))
                && handOver(card, card.missing(), "schematic ")) {
            return JobStatus.COMPLETED;
        }
        return JobStatus.RUNNING;
    }

    /** Queues a gather round for {@code items}, then the same build at the same anchor, and ends this build. */
    private boolean handOver(SchematicJobCard card, Map<String, Integer> items, String reason) {
        IGoalEngine goals = Services.goalsOrNull();
        if (goals == null) {
            return false;
        }
        long anchor = BlockPos.asLong(card.anchorX(), card.anchorY(), card.anchorZ());
        goals.submit(new GoalRequest.GatherMany(items, reason + card.name(), active.cost(), active.dataSource()));
        goals.submit(new GoalRequest.Build(active.schematicPath(), anchor, false, active.cost(),
                active.dataSource()));
        toolkit.cancel();
        active = null;
        return true;
    }

    /**
     * {@link BuildAssist.RestockHook}. Called from Build Assist's tick (client thread), so the hand-over itself waits
     * for the next {@link #buildStatus} poll instead of cancelling the toolkit from inside its own tick.
     */
    private boolean requestRestock(Map<String, Integer> wanted, long fromPacked, String dimension) {
        if (active == null || active.gatherOnly() || failed || Services.goalsOrNull() == null) {
            return false;
        }
        Optional<SchematicJobCard> card = toolkit.jobCard();
        if (card.isEmpty()) {
            return false;
        }
        String key = key(card.get());
        if (restockRounds.getOrDefault(key, 0) >= MAX_RESTOCK_ROUNDS) {
            return false;
        }
        IStorageIndex storage = Services.storageOrNull();
        Map<String, Integer> fetch = MaterialRestock.fromNearbyStorage(wanted, storage, dimension, fromPacked,
                RESTOCK_RADIUS);
        if (fetch.isEmpty()) {
            return false;
        }
        restockRounds.merge(key, 1, Integer::sum);
        pendingRestock = fetch;
        return true;
    }

    @Override
    public void cancelBuild(long jobId) {
        if (jobId == activeJob) {
            toolkit.cancel();
            active = null;
            activeJob = -1;
            pendingRestock = null;
        }
    }

    private String key(SchematicJobCard card) {
        return active.schematicPath() + "@" + card.anchorX() + "," + card.anchorY() + "," + card.anchorZ();
    }
}
