package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.api.JobStatus;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JobEscapeGateTest {

    @Test
    void armedByARunningJobWithAssistantAndAutopilotOff() {
        assertTrue(JobEscapeGate.armed(true, true, true, false, false, true));
    }

    @Test
    void notArmedWhenSettingEscapeModuleOrJobIsMissing() {
        assertFalse(JobEscapeGate.armed(true, true, false, false, false, true), "setting off");
        assertFalse(JobEscapeGate.armed(true, false, true, false, false, true), "escape off");
        assertFalse(JobEscapeGate.armed(false, true, true, false, false, true), "module disabled");
        assertFalse(JobEscapeGate.armed(true, true, true, false, false, false), "no job");
    }

    @Test
    void assistantAndAutopilotArmingUnchanged() {
        // the old rule: enabled && escapeOn && (assistantOn || apAllows), for every job/setting combination
        boolean[] b = {false, true};
        for (boolean en : b) {
            for (boolean esc : b) {
                for (boolean as : b) {
                    for (boolean ap : b) {
                        boolean old = en && esc && (as || ap);
                        for (boolean during : b) {
                            for (boolean job : b) {
                                boolean now = JobEscapeGate.armed(en, esc, during, as, ap, job);
                                if (old) {
                                    assertTrue(now);
                                } else {
                                    assertEquals(en && esc && during && job, now);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void viaAutopilotOnlyWhenTheAutopilotAllowsAndTheAssistantIsOff() {
        assertFalse(JobEscapeGate.viaAutopilot(false, false), "armed only by the job gate: own lease");
        assertTrue(JobEscapeGate.viaAutopilot(false, true));
        assertFalse(JobEscapeGate.viaAutopilot(true, true), "assistant on: its lease, not the autopilot");
        assertFalse(JobEscapeGate.viaAutopilot(true, false));
    }

    @Test
    void pollFiresExactlyOncePerTenTicks() {
        int counter = 0;
        int polls = 0;
        int lastPoll = -JobEscapeGate.POLL_INTERVAL_TICKS;
        for (int tick = 0; tick < 1000; tick++) {
            if (JobEscapeGate.pollDue(counter)) {
                polls++;
                assertEquals(JobEscapeGate.POLL_INTERVAL_TICKS, tick - lastPoll);
                lastPoll = tick;
            }
            counter = JobEscapeGate.nextPollCounter(counter);
        }
        assertEquals(1000 / JobEscapeGate.POLL_INTERVAL_TICKS, polls);
        assertEquals(10, JobEscapeGate.POLL_INTERVAL_TICKS);
    }

    @Test
    void pollSkippedWhenTheJobGateCannotArm() {
        assertTrue(JobEscapeGate.shouldPoll(true, true, true));
        assertFalse(JobEscapeGate.shouldPoll(false, true, true));
        assertFalse(JobEscapeGate.shouldPoll(true, false, true));
        assertFalse(JobEscapeGate.shouldPoll(true, true, false));
    }

    @Test
    void onlyRunningOrReplanningJobsCount() {
        for (JobStatus s : JobStatus.values()) {
            assertEquals(s == JobStatus.RUNNING || s == JobStatus.REPLANNING, JobEscapeGate.jobActive(s), s.name());
        }
        assertFalse(JobEscapeGate.jobActive(null));
    }
}
