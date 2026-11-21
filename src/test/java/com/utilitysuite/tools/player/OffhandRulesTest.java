package com.utilitysuite.tools.player;

import com.utilitysuite.tools.player.OffhandRules.Want;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OffhandRulesTest {

    private OffhandRules.State s;
    private OffhandRules.Config c;

    @BeforeEach
    void setUp() {
        s = new OffhandRules.State();
        c = new OffhandRules.Config();
        s.health = 20;
        s.haveTotem = true;
        s.haveShield = true;
        s.havePreferred = true;
    }

    @Test
    void priorityOrder() {
        assertEquals(Want.PREFERRED, OffhandRules.decide(s, c));
        s.rangedThreatNear = true;
        assertEquals(Want.SHIELD, OffhandRules.decide(s, c));
        s.gliding = true;
        assertEquals(Want.TOTEM, OffhandRules.decide(s, c));
        s.gliding = false;
        s.health = 8;
        assertEquals(Want.TOTEM, OffhandRules.decide(s, c));
    }

    @Test
    void projectileDangerAsksForShield() {
        s.projectileDanger = true;
        assertEquals(Want.SHIELD, OffhandRules.decide(s, c));
    }

    @Test
    void missingItemFallsThrough() {
        s.health = 4;
        s.haveTotem = false;
        s.rangedThreatNear = true;
        assertEquals(Want.SHIELD, OffhandRules.decide(s, c));
        s.haveShield = false;
        assertEquals(Want.PREFERRED, OffhandRules.decide(s, c));
        s.havePreferred = false;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
    }

    @Test
    void alreadyHeldMeansNoSwap() {
        s.health = 4;
        s.offhandHolds = Want.TOTEM;
        s.haveTotem = false; // the only totem is the one in the offhand
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
    }

    @Test
    void totemIsNotTradedAwayAtLowHealth() {
        s.health = 4;
        s.offhandHolds = Want.TOTEM;
        c.totemOnLowHealth = true;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
    }

    @Test
    void blockersAndCooldown() {
        s.health = 4;
        s.cooldownTicks = 3;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
        s.cooldownTicks = 0;
        s.usingItem = true;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c), "never while the user uses an item");
        s.usingItem = false;
        s.screenOpen = true;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
        s.screenOpen = false;
        s.clickPending = true;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
        s.clickPending = false;
        assertEquals(Want.TOTEM, OffhandRules.decide(s, c));
    }

    @Test
    void autopilotFallbackUsesTotemRulesOnly() {
        c.totemRulesOnly = true;
        s.rangedThreatNear = true;
        assertEquals(Want.KEEP, OffhandRules.decide(s, c));
        s.health = 6;
        assertEquals(Want.TOTEM, OffhandRules.decide(s, c));
    }

    @Test
    void decideDoesNotAllocate() {
        if (!com.utilitysuite.core.AllocationProbe.supported()) {
            return;
        }
        long bytes = com.utilitysuite.core.AllocationProbe.measure(100_000, () -> {
            s.health = (s.health + 1) % 20;
            OffhandRules.decide(s, c);
        });
        assertEquals(0, bytes, "OffhandRules.decide allocated " + bytes + " bytes");
    }
}
