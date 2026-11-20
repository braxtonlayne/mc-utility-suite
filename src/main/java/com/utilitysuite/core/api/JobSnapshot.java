package com.utilitysuite.core.api;

/**
 * Immutable view of a job for the UI and HUD.
 *
 * @param currentStep what the job is doing now ("Mining at Y=-54")
 * @param reason      why it is doing it / why it paused or replanned (shown as "Why")
 * @param nextStep    the following plan step, or empty
 */
public record JobSnapshot(long jobId, String goalLabel, JobStatus status, String currentStep, int progressCurrent,
                          int progressTarget, String reason, String nextStep, int replanCount) {
}
