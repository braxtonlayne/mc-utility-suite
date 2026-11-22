package com.utilitysuite.combat.assistant.stages;

import com.utilitysuite.combat.assistant.CombatState;
import com.utilitysuite.combat.assistant.FightStageSeamTest;
import com.utilitysuite.combat.tactics.HitPlanner;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SimKey;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.utilitysuite.combat.tactics.ThreatRank.*;
import static org.junit.jupiter.api.Assertions.*;

/** {@link ThreatFightStage} against the real Combat Assistant, with a scripted threat table. */
class ThreatFightStageTest {

    static final int LOCAL = 1;
    static final int ZOMBIE = 10;
    static final int CREEPER = 20;
    static final int SKELETON = 30;
    static final int BYSTANDER = 40;
    static final int WARDEN = 50;

    /** Scripted table: rows set by the test persist; refresh only counts. */
    public static final class FakeThreats extends NearbyThreats {
        public int refreshes;

        @Override
        protected void refresh() {
            refreshes++;
        }

        public int row(int id, String type, double x, double z, float hp, int flags) {
            add(id, type, x, 64, z, Math.sqrt(x * x + z * z), hp, flags, (flags & SWELLING) != 0 ? 20 : 0);
            return size - 1;
        }

        public void move(int row, double x, double z) {
            this.x[row] = x;
            this.z[row] = z;
            distance[row] = Math.sqrt(x * x + z * z);
        }
    }

    FightStageSeamTest.Rig rig;
    FakeThreats threats;
    ThreatFightStage stage;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        rig = new FightStageSeamTest.Rig();
        threats = new FakeThreats();
        stage = new ThreatFightStage(threats);
        rig.assistant.addStage(stage);
    }

    @AfterEach
    void tearDown() {
        Services.resetForTests();
    }

    @Test
    void aSwellingCreeperWidensTheBandAndTheAssistantBacksOffWithoutDisengaging() {
        rig.world.add(CREEPER, "Creeper", 3.0, 64, 0);
        int c = threats.row(CREEPER, "minecraft:creeper", 3.0, 0, 20f, HOSTILE | LOS);
        rig.engage(CREEPER);
        rig.tick(5);
        assertFalse(rig.down(SimKey.BACK), "inside the band: no back-off");
        assertEquals(HitPlanner.Policy.KNOCKBACK, rig.assistant.lastHitPolicy(), "playbook: sprint hit a creeper");

        threats.flags[c] |= SWELLING;
        rig.tick(3);
        assertTrue(stage.backingOff());
        assertTrue(rig.down(SimKey.BACK), "backs out past the blast radius");
        assertFalse(rig.down(SimKey.FORWARD));
        rig.tick(20);
        assertEquals(CombatState.ENGAGED, rig.assistant.state(), "still engaged");
        assertEquals(CREEPER, rig.assistant.targetId());

        threats.flags[c] &= ~SWELLING;
        rig.tick(3);
        assertFalse(stage.backingOff());
        assertFalse(rig.down(SimKey.BACK), "fuse stopped: back to the normal band");
    }

    @Test
    void targetSwitchesToTheSwellingCreeperAndBack() {
        rig.world.add(ZOMBIE, "Zombie", 2.5, 64, 0);
        rig.world.add(CREEPER, "Creeper", 0, 64, 4.0);
        threats.row(ZOMBIE, "minecraft:zombie", 2.5, 0, 10f, HOSTILE | LOS | MELEE_IN_RANGE);
        int c = threats.row(CREEPER, "minecraft:creeper", 0, 4.0, 20f, HOSTILE | LOS);
        rig.engage(ZOMBIE);
        rig.tick(30);
        assertEquals(ZOMBIE, rig.assistant.targetId(), "an approaching creeper does not outrank the zombie");

        threats.flags[c] |= SWELLING;
        rig.tick(6);
        assertEquals(CREEPER, rig.assistant.targetId(), "the swelling creeper is the threat");
        assertTrue(threats.refreshes > 5, "the table refreshes every few ticks");

        threats.flags[c] &= ~SWELLING; // it gave up as we backed off
        threats.move(c, 0, 7.0);
        rig.world.entities.get(CREEPER).z = 7.0;
        rig.tick(30);
        assertEquals(ZOMBIE, rig.assistant.targetId(), "back to the zombie once the fuse stopped");
        assertEquals(2, stage.requests(), "no flapping in between");
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
    }

    @Test
    void neverATargetChangeTowardANonHostileOrNeverTargetRow() {
        rig.world.add(ZOMBIE, "Zombie", 2.5, 64, 0);
        rig.world.add(BYSTANDER, "Zombie", -1.5, 64, 0);
        rig.world.add(WARDEN, "Warden", 0, 64, -2);
        threats.row(ZOMBIE, "minecraft:zombie", 2.5, 0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        // unaware of us (no HOSTILE), low health, right behind: still never picked
        threats.row(BYSTANDER, "minecraft:zombie", -1.5, 0, 2f, LOS | MELEE_IN_RANGE | BEHIND);
        threats.row(WARDEN, "minecraft:warden", 0, -2, 500f, HOSTILE | LOS | MELEE_IN_RANGE | BEHIND);
        rig.engage(ZOMBIE);
        rig.tick(100);
        assertEquals(ZOMBIE, rig.assistant.targetId());
        assertEquals(0, stage.requests());
        assertFalse(stage.packMode(), "a bystander does not make a pack");
    }

    @Test
    void aBowDrawnFromTheLeftForcesAStrafeToTheRight() {
        rig.world.add(ZOMBIE, "Zombie", 3.0, 64, 0); // facing +x: left is -z
        rig.world.add(SKELETON, "Skeleton", 3.0, 64, -12.0);
        threats.row(ZOMBIE, "minecraft:zombie", 3.0, 0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        int sk = threats.row(SKELETON, "minecraft:skeleton", 3.0, -12.0, 20f, HOSTILE | LOS | DRAWING | RANGED);
        rig.engage(ZOMBIE);
        rig.tick(2);
        assertTrue(rig.down(SimKey.RIGHT), "away from the shooter");
        assertFalse(rig.down(SimKey.LEFT));
        assertEquals(1, stage.dodges());

        threats.flags[sk] &= ~DRAWING;
        rig.tick(ThreatFightStage.DODGE_TICKS + 2);
        rig.world.self.hazardRight = true;
        threats.flags[sk] |= DRAWING;
        rig.tick(2);
        assertTrue(rig.down(SimKey.LEFT), "a hazard on the away side: the other side");
        assertFalse(rig.down(SimKey.RIGHT));
    }

    @Test
    void aPackWidensTheBandAndLetsTheHitPlannerChoose() {
        rig.assistant.config().hitChoice = HitPlanner.Policy.CRITS;
        rig.world.add(ZOMBIE, "Zombie", 2.5, 64, 0);
        rig.world.add(11, "Zombie", 2.0, 64, 2.0);
        rig.world.add(12, "Zombie", 2.0, 64, -2.0);
        threats.row(ZOMBIE, "minecraft:zombie", 2.5, 0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        threats.row(11, "minecraft:zombie", 2.0, 2.0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        threats.row(12, "minecraft:zombie", 2.0, -2.0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        rig.engage(ZOMBIE);
        rig.tick(3);
        assertTrue(stage.packMode());
        assertEquals(HitPlanner.Policy.AUTO, rig.assistant.lastHitPolicy(), "the hit planner sweeps on its own");
        assertTrue(rig.down(SimKey.BACK), "string them out");
        assertEquals(CombatState.ENGAGED, rig.assistant.state());
    }

    @Test
    void spiderPlaybookVetoesCritHops() {
        rig.assistant.config().hitChoice = HitPlanner.Policy.CRITS;
        rig.assistant.config().critSync = true;
        rig.world.self.attackStrength = 1f;
        rig.world.add(ZOMBIE, "Spider", 3.0, 64, 0);
        threats.row(ZOMBIE, "minecraft:spider", 3.0, 0, 16f, HOSTILE | LOS | MELEE_IN_RANGE);
        rig.engage(ZOMBIE);
        int jumps = 0;
        for (int i = 0; i < 30; i++) {
            rig.tick(1);
            jumps += rig.down(SimKey.JUMP) ? 1 : 0;
        }
        assertEquals(0, jumps, "no crit hop into a spider's leap");
        assertTrue(rig.assistant.swings() > 0, "still swings");

        stage.setPlaybook(false);
        for (int i = 0; i < 30; i++) {
            rig.tick(1);
            jumps += rig.down(SimKey.JUMP) ? 1 : 0;
        }
        assertTrue(jumps > 0, "playbook off: the crit hops come back");
    }

    @Test
    void aPlayerOpponentIsNeverDroppedForAMob() {
        rig.world.addPlayer(ZOMBIE, "Duelist", 3.0, 64, 0);
        rig.world.add(CREEPER, "Creeper", 0, 64, 3.0);
        threats.row(CREEPER, "minecraft:creeper", 0, 3.0, 20f, HOSTILE | LOS | SWELLING);
        rig.assistant.onDamageEvent(LOCAL, "minecraft:player_attack", ZOMBIE, ZOMBIE);
        rig.tick(20);
        assertEquals(ZOMBIE, rig.assistant.targetId());
        assertEquals(0, stage.requests());
        assertTrue(stage.backingOff(), "the fuse answer still runs");
    }

    @Test
    void settingOffDoesNothing() {
        stage.setEnabled(false);
        rig.world.add(ZOMBIE, "Zombie", 2.5, 64, 0);
        rig.world.add(CREEPER, "Creeper", 0, 64, 3.0);
        threats.row(ZOMBIE, "minecraft:zombie", 2.5, 0, 20f, HOSTILE | LOS | MELEE_IN_RANGE);
        threats.row(CREEPER, "minecraft:creeper", 0, 3.0, 20f, HOSTILE | LOS | SWELLING);
        rig.engage(ZOMBIE);
        rig.tick(20);
        assertEquals(ZOMBIE, rig.assistant.targetId());
        assertEquals(0, threats.refreshes, "no table work while off");
    }
}
