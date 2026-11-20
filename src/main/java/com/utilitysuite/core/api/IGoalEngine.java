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

    List<JobSnapshot> queuedJobs();

    /** Plan steps of a job for "Show Plan". */
    List<String> planSteps(long jobId);

    void pause(long jobId);

    void resume(long jobId);

    void cancel(long jobId);
}
