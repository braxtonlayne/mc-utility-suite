package com.utilitysuite.core.autopilot;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ManualInput;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReflexChainTest {

    private final DangerModelTest.ManualClock clock = new DangerModelTest.ManualClock();
    private final FakeArbiter arbiter = new FakeArbiter();
    private final ReflexChain chain = new ReflexChain(clock);

    @Test
    void reflexesInAChainShareOneEmergencyLease() {
        ControlLease first = chain.join(Reflex.FALL_CLUTCH, arbiter);
        assertNotNull(first);
        assertEquals(ControlPriority.EMERGENCY, first.priority());
        assertEquals("autopilot", first.moduleId());
        chain.done(Reflex.FALL_CLUTCH);
        clock.t += 2; // within the grace
        chain.tick(arbiter);
        assertSame(first, chain.join(Reflex.SHIELD, arbiter), "the next reflex retargets on the same lease");
        assertEquals(1, arbiter.requests);
        chain.done(Reflex.SHIELD);
        clock.t += ReflexChain.GRACE_TICKS;
        chain.tick(arbiter);
        assertFalse(first.isActive(), "released after the last reflex and the grace");
        assertNull(chain.lease());
    }

    @Test
    void nonDangerReflexesDoNotChain() {
        assertNull(chain.join(Reflex.PARKOUR, arbiter));
        assertNull(chain.join(Reflex.TOTEM, arbiter));
        assertEquals(0, arbiter.requests);
    }

    @Test
    void manualInputReleasesTheChain() {
        ControlLease l = chain.join(Reflex.DODGE, arbiter);
        arbiter.paused = true;
        chain.tick(arbiter);
        assertFalse(l.isActive(), "the user's input wins over an emergency chain");
    }

    @Test
    void preemptionDropsTheChain() {
        ControlLease l = chain.join(Reflex.DODGE, arbiter);
        ((FakeLease) l).active = false;
        chain.onPreempted(l, LeaseEndReason.STOP_ALL, "stop_all");
        assertNull(chain.lease());
        assertNotNull(chain.join(Reflex.DODGE, arbiter), "a new chain can start");
        assertEquals(2, arbiter.requests);
    }

    @Test
    void stoppedAutomationGrantsNothing() {
        arbiter.stopped = true;
        assertNull(chain.join(Reflex.SHIELD, arbiter));
    }

    static final class FakeLease implements ControlLease {
        final LeaseRequest req;
        boolean active = true;

        FakeLease(LeaseRequest req) {
            this.req = req;
        }

        @Override
        public long id() {
            return 1;
        }

        @Override
        public String moduleId() {
            return req.moduleId();
        }

        @Override
        public ControlPriority priority() {
            return req.priority();
        }

        @Override
        public Set<ControlChannel> channels() {
            return req.channels();
        }

        @Override
        public boolean covers(ControlChannel channel) {
            return req.channels().contains(channel);
        }

        @Override
        public boolean isActive() {
            return active;
        }

        @Override
        public void close() {
            active = false;
        }
    }

    static final class FakeArbiter implements IActionArbiter {
        int requests;
        boolean paused;
        boolean stopped;

        @Override
        public Optional<ControlLease> request(LeaseRequest request) {
            requests++;
            assertTrue(request.channels().containsAll(Set.of(ControlChannel.values())));
            return Optional.of(new FakeLease(request));
        }

        @Override
        public void release(ControlLease lease) {
            lease.close();
        }

        @Override
        public Optional<ControlLease> holder(ControlChannel channel) {
            return Optional.empty();
        }

        @Override
        public boolean isHeldBy(ControlChannel channel, String moduleId) {
            return false;
        }

        @Override
        public void onManualInput(ManualInput input) {
        }

        @Override
        public boolean isManualOverrideActive(ControlChannel channel) {
            return paused;
        }

        @Override
        public void stopAll(String reason) {
        }

        @Override
        public boolean isAutomationStopped() {
            return stopped;
        }

        @Override
        public void resumeAutomation() {
        }

        @Override
        public void tick() {
        }
    }
}
