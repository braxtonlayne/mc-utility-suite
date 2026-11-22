package com.utilitysuite.combat.tactics;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobPlaybookTest {

    @Test
    void starterRows() {
        MobPlaybook.Row creeper = MobPlaybook.row("minecraft:creeper");
        assertEquals(HitPlanner.Policy.KNOCKBACK, creeper.policy(), "sprint hit");
        assertEquals(MobPlaybook.Rule.FUSE_BACKOFF, creeper.rule());
        for (String id : new String[]{"minecraft:skeleton", "minecraft:stray", "minecraft:bogged"}) {
            assertEquals(MobPlaybook.Rule.STRAFE_IN, MobPlaybook.row(id).rule(), id);
        }
        assertEquals(MobPlaybook.Rule.NO_HOPS, MobPlaybook.row("minecraft:spider").rule());
        assertEquals(MobPlaybook.Rule.NO_HOPS, MobPlaybook.row("minecraft:cave_spider").rule());
        MobPlaybook.Row witch = MobPlaybook.row("minecraft:witch");
        assertEquals(MobPlaybook.Rule.RUSH, witch.rule());
        assertEquals(0.0, witch.spacingExtra(), "no extra spacing");
        assertSame(MobPlaybook.DEFAULT, MobPlaybook.row("minecraft:enderman"), "no special move yet");
        assertTrue(MobPlaybook.neverTarget("minecraft:warden"));
        assertTrue(MobPlaybook.neverTarget("minecraft:wither"));
        assertFalse(MobPlaybook.neverTarget("minecraft:zombie"));
    }

    @Test
    void babyZombiesNoHopsAdultsDefault() {
        assertSame(MobPlaybook.BABY_ZOMBIE, MobPlaybook.row("minecraft:zombie", true));
        assertSame(MobPlaybook.BABY_ZOMBIE, MobPlaybook.row("minecraft:husk", true));
        assertEquals(MobPlaybook.Rule.NO_HOPS, MobPlaybook.BABY_ZOMBIE.rule());
        assertSame(MobPlaybook.DEFAULT, MobPlaybook.row("minecraft:zombie", false));
        assertEquals(MobPlaybook.Rule.NO_HOPS, MobPlaybook.row("minecraft:spider", true).rule(),
                "baby flag only changes the zombie family");
    }

    @Test
    void unknownAndModdedIdsGetTheDefaultRow() {
        assertSame(MobPlaybook.DEFAULT, MobPlaybook.row("somemod:frost_wraith"));
        assertSame(MobPlaybook.DEFAULT, MobPlaybook.row(null, true));
        assertNull(MobPlaybook.DEFAULT.policy(), "the user's hit choice");
        assertEquals(MobPlaybook.Rule.NONE, MobPlaybook.DEFAULT.rule());
        assertFalse(MobPlaybook.DEFAULT.neverTarget());
    }
}
