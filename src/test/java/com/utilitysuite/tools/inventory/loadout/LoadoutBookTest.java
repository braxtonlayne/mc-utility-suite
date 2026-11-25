package com.utilitysuite.tools.inventory.loadout;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoadoutBookTest {

    private static Loadout named(String name) {
        GearItem[] s = new GearItem[Loadout.TARGETS];
        s[Loadout.HOTBAR_TARGET] = new GearItem("minecraft:diamond_pickaxe", Map.of(), "");
        return new Loadout(name, s);
    }

    @Test
    void renameKeepsOrderAndContentsAndRefusesClashes() {
        LoadoutBook b = new LoadoutBook();
        b.put(named("Mining"));
        b.put(named("Raid kit"));
        b.put(named("Farm"));
        assertTrue(b.rename("raid kit", "Ocean Monument"));
        assertNull(b.get("Raid kit"));
        assertEquals("Ocean Monument", b.all().get(1).name());
        assertEquals("minecraft:diamond_pickaxe", b.get("ocean monument").slot(Loadout.HOTBAR_TARGET).itemId());
        assertFalse(b.rename("Farm", "mining"), "name already taken");
        assertFalse(b.rename("Farm", "  "));
        assertFalse(b.rename("Nope", "X"));
        assertTrue(b.rename("Farm", "FARM"), "case-only rename is allowed");
        assertEquals("FARM", b.get("farm").name());
        // survives encode/decode
        assertEquals("Ocean Monument", LoadoutBook.decode(b.encode()).all().get(1).name());
    }

    @Test
    void presetAndActionKeys() {
        assertTrue(LoadoutBook.isPreset(" pvp "));
        assertFalse(LoadoutBook.isPreset("Raid kit"));
        assertEquals("ocean_monument_2", LoadoutBook.actionKey("  Ocean Monument #2! "));
    }
}
