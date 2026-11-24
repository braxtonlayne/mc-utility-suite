package com.utilitysuite.travel.movement;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.SharedInputOverlay;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The per-tick reads of Movement Assist's input handler: vanilla's values, and no garbage. */
class VanillaBodyRulesTest {

    private static final long BUDGET = 1_024;

    @Test
    void movementBlockIsHalfABlockBelowTheFeet() {
        // Entity.getOnPos(0.500001F): floor(feetY - 0.500001F)
        assertEquals(63, VanillaBodyRules.movementBlockY(64.0), "standing on a full block");
        assertEquals(63, VanillaBodyRules.movementBlockY(64.5), "standing on a bottom slab: the slab's own cell is 64");
        assertEquals(64, VanillaBodyRules.movementBlockY(64.51));
        assertEquals(64, VanillaBodyRules.movementBlockY(65.25), "mid jump: the air under the feet");
        assertEquals(-1, VanillaBodyRules.movementBlockY(0.0));
        assertEquals(-65, VanillaBodyRules.movementBlockY(-64.0));
        assertEquals((int) Math.floor(70.0625 - (double) 0.500001F), VanillaBodyRules.movementBlockY(70.0625));
    }

    @Test
    void sprintStartsInTheAir() {
        // no ground term at all: the sprint-key start of LocalPlayer.aiStep has none
        assertTrue(start(false, 1.0f, false, false, false, 20, false, false, false, false, true, false, false));
        assertTrue(start(false, 0.8f, false, false, false, 7, false, false, false, false, true, false, false));
    }

    @Test
    void sprintStartKeepsVanillasRefusals() {
        assertFalse(start(true, 1.0f, false, false, false, 20, false, false, false, false, true, false, false),
                "already sprinting");
        assertFalse(start(false, 0.79f, false, false, false, 20, false, false, false, false, true, false, false),
                "forward not held hard enough (sneaking and item use scale it below)");
        assertFalse(start(false, 1.0f, false, false, false, 6, false, false, false, false, true, false, false),
                "hungry");
        assertTrue(start(false, 1.0f, false, false, false, 6, false, true, false, false, true, false, false),
                "hungry but may fly");
        assertFalse(start(false, 1.0f, false, false, false, 20, false, false, true, false, true, false, false),
                "using an item");
        assertFalse(start(false, 1.0f, false, false, false, 20, false, false, false, true, true, false, false),
                "blind");
        assertFalse(start(false, 1.0f, false, false, false, 20, false, false, false, false, true, true, false),
                "gliding");
        assertFalse(start(false, 1.0f, false, false, false, 20, false, false, false, false, true, false, true),
                "against a wall");
        assertFalse(start(false, 1.0f, false, true, false, 20, false, false, false, false, true, false, false),
                "wading");
        assertTrue(start(false, 0.3f, true, true, false, 20, false, false, false, false, true, false, false),
                "under water any forward impulse will do");
        assertFalse(start(false, 1.0f, false, false, false, 20, true, false, false, false, false, false, false),
                "a vehicle that cannot sprint");
    }

    /** The gate of the sprint fill and the rule the core applies to the write must never drift apart. */
    @Test
    void sprintStartAgreesWithTheSharedInputOverlay() {
        float[] impulses = {-1.0f, 0.0f, 1.0e-6f, 0.3f, 0.79f, 0.8f, 1.0f};
        int[] foods = {0, 6, 7, 20};
        int cases = 0;
        for (int bits = 0; bits < 1 << 10; bits++) {
            for (float impulse : impulses) {
                for (int food : foods) {
                    boolean a = (bits & 1) != 0;
                    boolean b = (bits & 2) != 0;
                    boolean c = (bits & 4) != 0;
                    boolean d = (bits & 8) != 0;
                    boolean e = (bits & 16) != 0;
                    boolean f = (bits & 32) != 0;
                    boolean g = (bits & 64) != 0;
                    boolean h = (bits & 128) != 0;
                    boolean i = (bits & 256) != 0;
                    boolean j = (bits & 512) != 0;
                    assertEquals(SharedInputOverlay.sprintAllowed(a, impulse, b, c, d, food, e, f, g, h, i, j, false),
                            start(a, impulse, b, c, d, food, e, f, g, h, i, j, false),
                            "bits=" + bits + " impulse=" + impulse + " food=" + food);
                    assertFalse(start(a, impulse, b, c, d, food, e, f, g, h, i, j, true));
                    assertFalse(SharedInputOverlay.sprintAllowed(a, impulse, b, c, d, food, e, f, g, h, i, j, true));
                    cases++;
                }
            }
        }
        assertEquals(1024 * 7 * 4, cases);
        assertEquals(SharedInputOverlay.SPRINT_IMPULSE, VanillaBodyRules.SPRINT_IMPULSE);
        assertEquals(SharedInputOverlay.SPRINT_FOOD, VanillaBodyRules.SPRINT_FOOD);
    }

    @Test
    void holderOrNullIsTheHolder() {
        ActionArbiter arbiter = new ActionArbiter();
        IActionArbiter api = arbiter;
        assertNull(api.holderOrNull(ControlChannel.MOVEMENT));
        ControlLease lease = arbiter.request(LeaseRequest.of("job", ControlPriority.TRAVEL, null,
                ControlChannel.MOVEMENT)).orElseThrow();
        assertSame(lease, api.holderOrNull(ControlChannel.MOVEMENT));
        assertSame(lease, api.holder(ControlChannel.MOVEMENT).orElseThrow());
        assertNull(api.holderOrNull(ControlChannel.CAMERA));
        lease.close();
        assertNull(api.holderOrNull(ControlChannel.MOVEMENT));
    }

    /** What the handler asks every tick while a job's lease is active, through the contract: nothing allocated. */
    @Test
    void perTickReadsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ActionArbiter arbiter = new ActionArbiter();
        IActionArbiter api = arbiter;
        arbiter.request(LeaseRequest.of("job", ControlPriority.EMERGENCY, null, ControlChannel.MOVEMENT))
                .orElseThrow();
        int[] n = {0};
        long[] sink = {0};
        long bytes = AllocationProbe.measure(200_000, () -> {
            int k = n[0]++;
            ControlLease h = api.holderOrNull(ControlChannel.MOVEMENT);
            if (h != null && h.priority().level() >= ControlPriority.EMERGENCY.level()
                    && !api.isHeldBy(ControlChannel.MOVEMENT, "move_toggles")) {
                sink[0]++;
            }
            sink[0] += VanillaBodyRules.movementBlockY(64.0 + (k & 1023) * 0.01);
            if (start((k & 1) != 0, (k & 7) * 0.2f, (k & 8) != 0, (k & 16) != 0, (k & 32) != 0, k & 15,
                    (k & 64) != 0, (k & 128) != 0, (k & 256) != 0, (k & 512) != 0, (k & 1024) == 0,
                    (k & 2048) != 0, (k & 4096) != 0)) {
                sink[0]++;
            }
        });
        assertTrue(sink[0] > 0);
        assertTrue(bytes < BUDGET, "per-tick reads allocated " + bytes + " bytes over 200k calls");
    }

    private static boolean start(boolean sprinting, float forwardImpulse, boolean underWater, boolean inWater,
                                 boolean canStartSwimming, int foodLevel, boolean passenger, boolean mayFly,
                                 boolean usingItem, boolean blind, boolean vehicleOk, boolean fallFlying,
                                 boolean againstWall) {
        return VanillaBodyRules.sprintStart(sprinting, forwardImpulse, underWater, inWater, canStartSwimming,
                foodLevel, passenger, mayFly, usingItem, blind, vehicleOk, fallFlying, againstWall);
    }
}
