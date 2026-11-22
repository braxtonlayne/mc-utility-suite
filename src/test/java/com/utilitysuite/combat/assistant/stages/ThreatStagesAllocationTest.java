package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.FightStageSeamTest;
import com.utilitysuite.combat.tactics.ComboEscape;
import com.utilitysuite.combat.tactics.MobPlaybook;
import com.utilitysuite.combat.tactics.ThreatRank;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.Services;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.tactics.ThreatRank.*;
import static org.junit.jupiter.api.Assertions.*;

/** The threat stages allocate nothing per step: a busy engaged tick (every answer live) and the engine-free units. */
class ThreatStagesAllocationTest {

    static final long LIMIT = 1_024;

    FightStageSeamTest.Rig rig;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = new FightStageSeamTest.Rig();
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    @Test
    void aBusyEngagedTickWithBothStagesAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ThreatFightStageTest.FakeThreats threats = new ThreatFightStageTest.FakeThreats();
        ThreatFightStage threat = new ThreatFightStage(threats);
        ComboEscapeStage combo = new ComboEscapeStage();
        rig.assistant.addStage(threat);
        rig.assistant.addStage(combo);
        rig.world.add(10, "Creeper", 3.0, 64, 0);
        rig.world.add(11, "Zombie", 2.0, 64, 2.0);
        rig.world.add(12, "Zombie", 2.0, 64, -2.0);
        rig.world.add(13, "Skeleton", 3.0, 64, -12.0);
        rig.world.add(14, "Zombie", -2.0, 64, 1.0);
        threats.row(14, "minecraft:zombie", -2.0, 1.0, 20f, HOSTILE | LOS | MELEE_IN_RANGE | BEHIND);
        threats.row(10, "minecraft:creeper", 3.0, 0, 20f, HOSTILE | LOS | SWELLING);
        threats.row(11, "minecraft:zombie", 2.0, 2.0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        threats.row(12, "minecraft:zombie", 2.0, -2.0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        threats.row(13, "minecraft:skeleton", 3.0, -12.0, 20f, HOSTILE | LOS | DRAWING | RANGED);
        rig.world.self.attackStrength = 0.3f;
        rig.engage(10);
        Runnable body = () -> {
            rig.smoother.preTick();
            rig.assistant.onTick();
            rig.smoother.postTick();
        };
        AllocationProbe.measure(50_000, body);
        long bytes = AllocationProbe.measure(50_000, body);
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
        assertEquals(10, rig.assistant.targetId());
        assertTrue(threat.backingOff() && threat.packMode() && threat.dodges() > 0, "every answer live");
        assertTrue(threats.refreshes > 10_000, "re-picked every refresh");
        assertTrue(bytes < LIMIT, "engaged tick with the threat stages allocated " + bytes + " bytes over 50k ticks");
    }

    @Test
    void theEngineFreeUnitsAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ThreatRank rank = new ThreatRank();
        ComboEscape escape = new ComboEscape();
        String[] types = {"minecraft:creeper", "minecraft:zombie", "somemod:thing", "minecraft:spider"};
        long[] clock = {0};
        double[] sink = {0};
        Runnable body = () -> {
            long t = clock[0]++;
            rank.begin(1, t);
            for (int i = 0; i < 16; i++) {
                int f = HOSTILE | LOS | ((i & 1) != 0 ? MELEE_IN_RANGE : 0) | (i == 3 ? SWELLING : 0);
                rank.offer(i + 1, ThreatRank.score(f, i * 0.7, 20f - i, MobPlaybook.row(types[i & 3],
                        (i & 4) != 0).neverTarget(), true), ThreatRank.emergency(f, i * 0.7));
            }
            sink[0] += rank.decide(t);
            int hits = (int) (t % 30) < 15 ? 2 : 0;
            if (escape.update(t, hits, t - (t % 15), -1, false, (t & 1) != 0, (t & 2) != 0, false, 1)) {
                sink[0] += escape.side();
            }
        };
        long bytes = AllocationProbe.measure(100_000, body);
        assertTrue(bytes < LIMIT, "rank, playbook and escape allocated " + bytes + " bytes");
        assertTrue(escape.escapes() > 0 && rank.switches() >= 0);
    }
}
