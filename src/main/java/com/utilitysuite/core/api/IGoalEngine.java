package com.utilitysuite.core.api;

import java.util.List;
import java.util.Optional;

/** The goal engine as seen by the UI, schematic toolkit and rules. Client thread only. */
public interface IGoalEngine {

    ParseResult parse(String command);

    /** Plans without executing. May be computed asynchronously; the UI polls {@link #lastPreview()}. */
    void requestPreview(GoalRequest request);

    Optional<PlanPreview> lastPreview();

    /** Queues a goal. Returns the job id. */
    long submit(GoalRequest request);

    Optional<JobSnapshot> activeJob();

    /**
     * Live ETA of the running job in seconds (moving average of its step completion rate), or -1 while unknown.
     * Cheap and allocation free: the HUD reads it on its poll and rebuilds text only when the shown value changes.
     */
    default int activeJobEtaSeconds() {
        return -1;
    }

    List<JobSnapshot> queuedJobs();

    /** Plan steps of a job for "Show Plan". */
    List<String> planSteps(long jobId);

    void pause(long jobId);

    void resume(long jobId);

    void cancel(long jobId);
}
