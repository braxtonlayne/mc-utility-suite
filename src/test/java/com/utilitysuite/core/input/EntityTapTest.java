package com.utilitysuite.core.input;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.RotationTarget;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.core.arbiter.ActionArbiter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EntityTapTest {

    private FakeInputBackend backend;
    private InputSmoother smoother;
    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        backend = new FakeInputBackend();
        smoother = new InputSmoother(backend, new Random(7));
        arbiter = new ActionArbiter();
        arbiter.bind(smoother, null, null);
    }

    @AfterEach
    void tearDown() {
        smoother.releaseAll();
        SilentRotation.clear();
    }

    @Test
    void tapsOnlyWithInteractLeaseSettledAimAndTheEntityUnderTheCrosshair() {
        ControlLease move = arbiter.request(LeaseRequest.of("t", ControlPriority.GOAL, null, ControlChannel.MOVEMENT))
                .orElseThrow();
        backend.crosshairEntity = 12;
        assertFalse(smoother.tapOnEntity(move, SimKey.USE, 12), "needs WORLD_INTERACT");
        move.close();

        ControlLease l = arbiter.request(LeaseRequest.of("t", ControlPriority.GOAL, null,
                ControlChannel.WORLD_INTERACT, ControlChannel.CAMERA)).orElseThrow();
        assertFalse(smoother.tapOnEntity(l, SimKey.FORWARD, 12), "only ATTACK/USE");
        backend.crosshairEntity = 5;
        assertFalse(smoother.tapOnEntity(l, SimKey.USE, 12), "crosshair on another entity");

        smoother.setRotationTarget(l, RotationTarget.visible(90f, 10f));
        backend.crosshairEntity = 12;
        assertFalse(smoother.tapOnEntity(l, SimKey.ATTACK, 12), "rotation still turning (smoothed, no snap)");
        for (int i = 0; i < 400 && !smoother.isRotationSettled(3f); i++) {
            smoother.preTick();
            smoother.onFrame(0.05);
            smoother.postTick();
        }
        assertTrue(smoother.isRotationSettled(3f));
        int before = backend.clicks[SimKey.ATTACK.ordinal()];
        assertTrue(smoother.tapOnEntity(l, SimKey.ATTACK, 12));
        assertEquals(before + 1, backend.clicks[SimKey.ATTACK.ordinal()]);
    }
}
