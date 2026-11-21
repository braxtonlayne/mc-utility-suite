package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.JobStatus;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-tick decision methods of the job escape gate allocate nothing. */
class JobEscapeGateAllocationTest {

    private static final long BUDGET = 1_024;
    private static final JobStatus[] STATUSES = JobStatus.values();

    @Test
    void perTickDecisions() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        int[] i = {0};
        int[] sink = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            int k = i[0]++;
            boolean a = (k & 1) != 0, b = (k & 2) != 0, c = (k & 4) != 0, d = (k & 8) != 0, e = (k & 16) != 0,
                    f = (k & 32) != 0;
            int counter = k % JobEscapeGate.POLL_INTERVAL_TICKS;
            int r = 0;
            if (JobEscapeGate.armed(a, b, c, d, e, f)) r++;
            if (JobEscapeGate.viaAutopilot(d, e)) r++;
            if (JobEscapeGate.shouldPoll(a, b, c)) r++;
            if (JobEscapeGate.pollDue(counter)) r++;
            if (JobEscapeGate.jobActive(STATUSES[k % STATUSES.length])) r++;
            sink[0] += r + JobEscapeGate.nextPollCounter(counter);
        });
        assertTrue(bytes < BUDGET, "gate allocated " + bytes + " bytes");
    }
}
