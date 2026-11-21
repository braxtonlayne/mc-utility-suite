package com.utilitysuite.combat.reflex;

import com.utilitysuite.core.SuiteEventBus;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Random;

import static com.utilitysuite.core.api.ControlChannel.CAMERA;
import static com.utilitysuite.core.api.ControlChannel.MOVEMENT;
import static com.utilitysuite.core.api.ControlChannel.WORLD_INTERACT;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The combat reflexes hold a shield lease and an escape/dodge movement lease at the same time. The arbiter keeps one
 * lease per owner id, so the two sides use distinct owner ids; these tests pin that against the real arbiter.
 */
class ReflexLeaseSplitTest {

    private ActionArbiter arbiter;

    @BeforeEach
    void setUp() {
        arbiter = new ActionArbiter();
        arbiter.bind(new InputSmoother(new FakeInputBackend(), new Random(1)), null, new SuiteEventBus());
    }

    private static LeaseRequest req(String id, ControlChannel... channels) {
        EnumSet<ControlChannel> set = EnumSet.noneOf(ControlChannel.class);
        for (ControlChannel c : channels) {
            set.add(c);
        }
        return new LeaseRequest(id, ControlPriority.EMERGENCY, set, 0, null);
    }

    @Test
    void subIdsAreDistinctAndDerivedFromModuleId() {
        assertNotEquals(CombatReflexes.INTERACT_LEASE_ID, CombatReflexes.MOVE_LEASE_ID);
        assertTrue(CombatReflexes.MOVE_LEASE_ID.startsWith(CombatReflexes.ID));
        assertEquals(CombatReflexes.ID, CombatReflexes.INTERACT_LEASE_ID);
    }

    @Test
    void shieldThenMoveLeavesBothActive() {
        ControlLease shield = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, WORLD_INTERACT)).orElseThrow();
        ControlLease move = arbiter.request(req(CombatReflexes.MOVE_LEASE_ID, MOVEMENT)).orElseThrow();
        assertTrue(shield.isActive());
        assertTrue(move.isActive());
        assertTrue(arbiter.isHeldBy(WORLD_INTERACT, CombatReflexes.INTERACT_LEASE_ID));
        assertTrue(arbiter.isHeldBy(MOVEMENT, CombatReflexes.MOVE_LEASE_ID));
    }

    @Test
    void moveThenShieldTurnLeavesBothActive() {
        ControlLease move = arbiter.request(req(CombatReflexes.MOVE_LEASE_ID, MOVEMENT)).orElseThrow();
        ControlLease turn = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, WORLD_INTERACT, CAMERA))
                .orElseThrow();
        assertTrue(move.isActive());
        assertTrue(turn.isActive());
    }

    @Test
    void releasingOneLeavesTheOther() {
        ControlLease shield = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, WORLD_INTERACT)).orElseThrow();
        ControlLease move = arbiter.request(req(CombatReflexes.MOVE_LEASE_ID, MOVEMENT)).orElseThrow();
        arbiter.release(move);
        assertFalse(move.isActive());
        assertTrue(shield.isActive());
        assertTrue(arbiter.isHeldBy(WORLD_INTERACT, CombatReflexes.INTERACT_LEASE_ID));
        assertFalse(arbiter.holder(MOVEMENT).isPresent());

        ControlLease move2 = arbiter.request(req(CombatReflexes.MOVE_LEASE_ID, MOVEMENT)).orElseThrow();
        arbiter.release(shield);
        assertFalse(shield.isActive());
        assertTrue(move2.isActive());
    }

    @Test
    void stopAllEndsBoth() {
        ControlLease shield = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, WORLD_INTERACT)).orElseThrow();
        ControlLease move = arbiter.request(req(CombatReflexes.MOVE_LEASE_ID, MOVEMENT)).orElseThrow();
        arbiter.stopAll("test");
        assertFalse(shield.isActive());
        assertFalse(move.isActive());
    }

    @Test
    void sameIdReRequestStillReplaces() {
        // documents the arbiter rule the split works around: one lease per owner id
        ControlLease shield = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, WORLD_INTERACT)).orElseThrow();
        ControlLease clash = arbiter.request(req(CombatReflexes.INTERACT_LEASE_ID, MOVEMENT)).orElseThrow();
        assertFalse(shield.isActive());
        assertTrue(clash.isActive());
        assertFalse(arbiter.holder(WORLD_INTERACT).isPresent());
    }
}
