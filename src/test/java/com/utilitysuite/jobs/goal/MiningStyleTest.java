package com.utilitysuite.jobs.goal;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningStyleTest {

    static final List<String> COAL = List.of("minecraft:coal_ore", "minecraft:deepslate_coal_ore");
    static final List<String> DIAMOND = List.of("minecraft:diamond_ore", "minecraft:deepslate_diamond_ore");

    @Test
    void alreadyInAGoodBandStaysAtTheCurrentLevel() {
        assertEquals(30, MiningStyle.stripLevel(COAL, 30));
        assertEquals(-55, MiningStyle.stripLevel(DIAMOND, -55));
    }

    @Test
    void outsideTheBandGoesToThePreferredLevel() {
        assertEquals(48, MiningStyle.stripLevel(COAL, 120), "coal from a mountain top");
        assertEquals(-58, MiningStyle.stripLevel(DIAMOND, 64), "diamonds from the surface");
    }

    @Test
    void unknownOreStaysWhereThePlayerIs() {
        assertEquals(MiningStyle.STAY, MiningStyle.stripLevel(List.of("minecraft:obsidian"), 40));
    }
}
