package com.utilitysuite.core.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.*;

class ApiContractTest {

    @Test
    void priorityOrdering() {
        assertTrue(ControlPriority.EMERGENCY.outranks(ControlPriority.COMBAT));
        assertTrue(ControlPriority.SURVIVAL.outranks(ControlPriority.GOAL));
        assertFalse(ControlPriority.GOAL.outranks(ControlPriority.GOAL));
    }

    @Test
    void leaseRequestValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new LeaseRequest("x", ControlPriority.MANUAL, EnumSet.of(ControlChannel.CAMERA), 0, null));
        assertThrows(IllegalArgumentException.class,
                () -> new LeaseRequest("x", ControlPriority.GOAL, EnumSet.noneOf(ControlChannel.class), 0, null));
        LeaseRequest r = LeaseRequest.of("x", ControlPriority.GOAL, null, ControlChannel.CAMERA, ControlChannel.MOVEMENT);
        assertEquals(2, r.channels().size());
        assertSame(LeaseListener.NONE, r.listener());
    }

    @Test
    void rotationTargetClampsPitch() {
        assertEquals(90f, RotationTarget.visible(0, 120).pitch());
    }
}
