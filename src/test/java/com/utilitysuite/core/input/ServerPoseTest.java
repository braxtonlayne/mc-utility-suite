package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerPoseTest {

    private static final class Lease implements ControlLease {
        boolean active = true;

        @Override public long id() { return 1; }
        @Override public String moduleId() { return "bridge_assist.walk"; }
        @Override public ControlPriority priority() { return ControlPriority.TRAVEL; }
        @Override public Set<ControlChannel> channels() { return Set.of(ControlChannel.CAMERA); }
        @Override public boolean covers(ControlChannel c) { return c == ControlChannel.CAMERA; }
        @Override public boolean isActive() { return active; }
        @Override public void close() { active = false; }
    }

    @AfterEach
    void reset() {
        ServerPose.clear();
    }

    @Test
    void followsTheRealStateWithoutAnOverride() {
        assertTrue(ServerPose.sneak(true));
        assertFalse(ServerPose.sneak(false));
        assertTrue(ServerPose.sprint(true));
    }

    @Test
    void reportsTheOverrideWhileTheLeaseIsActive() {
        Lease l = new Lease();
        ServerPose.set(l, Boolean.TRUE, Boolean.FALSE);
        assertTrue(ServerPose.sneak(false), "crouch shown while walking at full speed");
        assertFalse(ServerPose.sprint(true), "walking shown while sprinting");
    }

    @Test
    void nullKeepsThatStateReal() {
        ServerPose.set(new Lease(), null, Boolean.FALSE);
        assertTrue(ServerPose.sneak(true));
        assertFalse(ServerPose.sneak(false));
        assertFalse(ServerPose.sprint(true));
    }

    @Test
    void lapsesTheMomentTheLeaseEnds() {
        Lease l = new Lease();
        ServerPose.set(l, Boolean.TRUE, Boolean.FALSE);
        l.close(); // preempted / Stop-All without the owner clearing it
        assertFalse(ServerPose.sneak(false));
        assertTrue(ServerPose.sprint(true));
    }
}
