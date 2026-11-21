package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.api.JobStatus;

/**
 * Engine-free gating for the fuse back-off ({@link CombatReflexes#ESCAPE}): when it is armed, whether it acts through
 * the autopilot, and how often the goal engine is polled for a running job. Pure booleans and ints, so it is testable
 * without Minecraft and allocation free.
 */
public final class JobEscapeGate {

    /** Ticks between goal-engine polls ({@code IGoalEngine.activeJob()} allocates, so it is not read every tick). */
    public static final int POLL_INTERVAL_TICKS = 10;

    private JobEscapeGate() {
    }

    /**
     * Whether the back-off is armed: the module and its setting are on and either the Combat Assistant is on, the
     * autopilot allows the reflex, or (with "back off during jobs" on) a goal job is running.
     *
     * @param apAllows the autopilot is on and allows {@code THREAT_ESCAPE}
     * @param jobActive a goal job is running or replanning (or this reflex is already backing off for one)
     */
    public static boolean armed(boolean enabled, boolean escapeOn, boolean duringJobs, boolean assistantOn,
                                boolean apAllows, boolean jobActive) {
        return enabled && escapeOn && (assistantOn || apAllows || duringJobs && jobActive);
    }

    /**
     * Whether the escape goes through the autopilot (its {@code mayAct}/{@code noteActed}/{@code noteDone} and chain
     * lease). False when the assistant is on or when only the job gate armed it: then the module's own EMERGENCY move
     * lease is used and the autopilot is never asked.
     */
    public static boolean viaAutopilot(boolean assistantOn, boolean apAllows) {
        return !assistantOn && apAllows;
    }

    /** Whether the goal engine should be polled at all (skipped entirely while the job gate cannot arm anything). */
    public static boolean shouldPoll(boolean enabled, boolean escapeOn, boolean duringJobs) {
        return enabled && escapeOn && duringJobs;
    }

    /** Advances the poll counter: counts 0..{@link #POLL_INTERVAL_TICKS}-1 and wraps. */
    public static int nextPollCounter(int counter) {
        int n = counter + 1;
        return n >= POLL_INTERVAL_TICKS ? 0 : n;
    }

    /** A poll is due on the tick the counter is 0 (exactly once per {@link #POLL_INTERVAL_TICKS} ticks). */
    public static boolean pollDue(int counter) {
        return counter == 0;
    }

    /** A job the back-off protects: one that is moving the player (running or replanning on the spot). */
    public static boolean jobActive(JobStatus status) {
        return status == JobStatus.RUNNING || status == JobStatus.REPLANNING;
    }
}
