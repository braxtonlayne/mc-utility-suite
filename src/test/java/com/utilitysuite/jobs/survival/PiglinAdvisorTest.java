package com.utilitysuite.jobs.survival;

import com.utilitysuite.jobs.survival.PiglinAdvisor.Act;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Drop;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Guard;
import com.utilitysuite.jobs.survival.PiglinAdvisor.Piglin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PiglinAdvisorTest {

    private static final Set<String> NONE = Set.of();

    @Test
    void givesToNearestIdleAdult() {
        List<Piglin> ps = List.of(new Piglin(1, 2.5, true, false, true), new Piglin(2, 2.8, true, false, false),
                new Piglin(3, 1.0, false, false, false));
        var d = PiglinAdvisor.barter(10, ps, List.of(), 40, 20, 8, NONE);
        assertEquals(Act.GIVE, d.act());
        assertEquals(2, d.targetId());
    }

    @Test
    void tossesWhenOutOfReachAndRespectsCadence() {
        List<Piglin> ps = List.of(new Piglin(5, 5.0, true, false, false));
        assertEquals(Act.TOSS, PiglinAdvisor.barter(3, ps, List.of(), 40, 20, 8, NONE).act());
        assertEquals(Act.WAIT, PiglinAdvisor.barter(3, ps, List.of(), 5, 20, 8, NONE).act());
    }

    @Test
    void hostilePiglinStopsTheLoop() {
        List<Piglin> ps = List.of(new Piglin(1, 2, true, false, false), new Piglin(2, 10, true, true, false));
        assertEquals(Act.NONE, PiglinAdvisor.barter(10, ps, List.of(), 40, 20, 8, NONE).act());
    }

    @Test
    void collectsUsefulDropsAndSkipsJunk() {
        List<Drop> drops = List.of(new Drop(10, "minecraft:gravel", 1.0), new Drop(11, "minecraft:ender_pearl", 4.0),
                new Drop(12, "minecraft:obsidian", 20.0));
        var d = PiglinAdvisor.barter(0, List.of(new Piglin(1, 3, true, false, true)), drops, 0, 20, 8, NONE);
        assertEquals(Act.COLLECT, d.act());
        assertEquals(11, d.targetId());
        assertTrue(PiglinAdvisor.isJunk("minecraft:soul_sand", NONE));
        assertTrue(PiglinAdvisor.isJunk("minecraft:string", Set.of("minecraft:string")));
        assertFalse(PiglinAdvisor.isJunk("minecraft:ender_pearl", Set.of("minecraft:ender_pearl")));
        assertEquals(Act.NONE, PiglinAdvisor.barter(0, List.of(), List.of(drops.get(0)), 0, 20, 8, NONE).act());
    }

    @Test
    void guardWarnsWithoutGold() {
        List<String> iron = List.of("minecraft:iron_boots", "", "minecraft:iron_chestplate", "");
        List<String> gold = List.of("minecraft:golden_boots", "", "", "");
        assertEquals(Guard.SAFE, PiglinAdvisor.guard(iron, 30, false, false, false, 0));
        assertEquals(Guard.WARN_NO_GOLD, PiglinAdvisor.guard(iron, 10, false, true, false, 0));
        assertEquals(Guard.EQUIP_GOLD, PiglinAdvisor.guard(iron, 10, true, true, false, 0));
        assertEquals(Guard.WARN_NO_GOLD, PiglinAdvisor.guard(iron, 10, true, false, false, 0));
        assertEquals(Guard.SAFE, PiglinAdvisor.guard(gold, 5, false, false, false, 0));
        assertEquals(Guard.WARN_CONTAINER, PiglinAdvisor.guard(gold, Double.POSITIVE_INFINITY, false, false, true, 3));
        assertEquals(Guard.WARN_CONTAINER, PiglinAdvisor.guard(gold, 8, false, false, true, 0));
        assertEquals(Guard.SAFE, PiglinAdvisor.guard(gold, 40, false, false, true, 0));
    }

    @Test
    void pickGoldSlotPrefersEmptySlots() {
        boolean[] empty = {false, true, false, false};
        boolean[] avail = {true, true, false, true};
        assertEquals(1, PiglinAdvisor.pickGoldSlot(empty, avail, false));
        boolean[] full = {false, false, false, false};
        assertEquals(-1, PiglinAdvisor.pickGoldSlot(full, avail, false));
        assertEquals(0, PiglinAdvisor.pickGoldSlot(full, avail, true));
        assertEquals(3, PiglinAdvisor.pickGoldSlot(full, new boolean[] {false, false, false, true}, true));
    }
}
