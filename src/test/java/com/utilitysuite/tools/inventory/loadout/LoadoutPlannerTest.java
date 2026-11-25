package com.utilitysuite.tools.inventory.loadout;

import com.utilitysuite.core.api.IContainerClicker.Click;
import com.utilitysuite.tools.inventory.ClickPlan;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LoadoutPlannerTest {

    private static GearItem[] empty(int container) {
        return new GearItem[LoadoutPlanner.CONTAINER + container];
    }

    private static Loadout loadout(GearItem... targets) {
        return new Loadout("Mining", targets);
    }

    @Test
    void alreadyEquippedPlansNothing() {
        GearItem pick = GearItem.of("minecraft:diamond_pickaxe").withEnchant("minecraft:efficiency", 5);
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[Loadout.HOTBAR_TARGET] = pick;
        GearItem[] pos = empty(0);
        pos[0] = pick;
        ClickPlan plan = new ClickPlan();
        LoadoutPlanner.Result r = new LoadoutPlanner().plan(loadout(slots), pos, 0, 0, plan);
        assertEquals(0, r.swaps());
        assertTrue(plan.isEmpty());
        assertTrue(r.complete());
    }

    @Test
    void hotbarFromMainInventoryIsOneSwapAndPrefersEnchantedMatch() {
        GearItem wanted = GearItem.of("minecraft:diamond_pickaxe").withEnchant("minecraft:efficiency", 5)
                .withEnchant("minecraft:fortune", 3);
        GearItem plain = GearItem.of("minecraft:diamond_pickaxe");
        GearItem silk = GearItem.of("minecraft:diamond_pickaxe").withEnchant("minecraft:efficiency", 5)
                .withEnchant("minecraft:silk_touch", 1);
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[Loadout.HOTBAR_TARGET + 2] = wanted;
        GearItem[] pos = empty(0);
        pos[12] = plain;
        pos[13] = silk;
        pos[20] = wanted;
        ClickPlan plan = new ClickPlan();
        new LoadoutPlanner().plan(loadout(slots), pos, 0, 0, plan);
        assertEquals(1, plan.size());
        assertEquals(20, plan.slot(0));
        assertEquals(2, plan.button(0));
        assertEquals(Click.SWAP, plan.click(0));
        assertSame(wanted, pos[2]);
    }

    @Test
    void armourSwapPutsOldPieceBack() {
        GearItem netherite = GearItem.of("minecraft:netherite_chestplate");
        GearItem iron = GearItem.of("minecraft:iron_chestplate");
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[2] = netherite;
        GearItem[] pos = empty(0);
        pos[LoadoutPlanner.ARMOR + 2] = iron;
        pos[15] = netherite;
        ClickPlan plan = new ClickPlan();
        LoadoutPlanner.Result r = new LoadoutPlanner().plan(loadout(slots), pos, 0, 0, plan);
        assertEquals(1, r.swaps());
        assertEquals(3, plan.size());
        assertEquals(15, plan.slot(0));
        assertEquals(6, plan.slot(1)); // chest armour menu slot
        assertEquals(15, plan.slot(2));
        assertSame(iron, pos[15]);
    }

    @Test
    void lockedArmourIsLeftAlone() {
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[3] = GearItem.of("minecraft:diamond_helmet");
        GearItem[] pos = empty(0);
        pos[LoadoutPlanner.ARMOR + 3] = GearItem.of("minecraft:carved_pumpkin");
        pos[10] = GearItem.of("minecraft:diamond_helmet");
        ClickPlan plan = new ClickPlan();
        new LoadoutPlanner().plan(loadout(slots), pos, 0, 1 << 3, plan);
        assertTrue(plan.isEmpty());
    }

    @Test
    void offhandAndHotbarFromOpenContainer() {
        GearItem totem = GearItem.of("minecraft:totem_of_undying");
        GearItem sword = GearItem.of("minecraft:netherite_sword").named("Edge");
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[Loadout.OFFHAND_TARGET] = totem;
        slots[Loadout.HOTBAR_TARGET] = sword;
        GearItem[] pos = empty(27);
        pos[LoadoutPlanner.CONTAINER + 4] = totem;
        pos[LoadoutPlanner.CONTAINER + 9] = GearItem.of("minecraft:netherite_sword");
        pos[LoadoutPlanner.CONTAINER + 10] = sword;
        ClickPlan plan = new ClickPlan();
        LoadoutPlanner.Result r = new LoadoutPlanner().plan(loadout(slots), pos, 27, 0, plan);
        assertEquals(2, r.swaps());
        assertEquals(4, plan.slot(0));
        assertEquals(LoadoutPlanner.OFFHAND_BUTTON, plan.button(0));
        assertEquals(10, plan.slot(1)); // the named sword wins
        assertEquals(0, plan.button(1));
    }

    @Test
    void armourInContainerIsPulledAndDeferred() {
        GearItem boots = GearItem.of("minecraft:diamond_boots");
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[0] = boots;
        slots[1] = GearItem.of("minecraft:elytra");
        GearItem[] pos = empty(27);
        pos[LoadoutPlanner.CONTAINER + 3] = boots;
        ClickPlan plan = new ClickPlan();
        LoadoutPlanner.Result r = new LoadoutPlanner().plan(loadout(slots), pos, 27, 0, plan);
        assertEquals(1, r.deferred());
        assertEquals(1, r.missing().size());
        assertEquals(3, plan.slot(0));
        assertEquals(27, plan.slot(1)); // first main inventory slot in a 27-slot container menu
        assertSame(boots, pos[9]);
    }

    @Test
    void chainedHotbarSwapsAreSimulated() {
        GearItem a = GearItem.of("minecraft:diamond_sword");
        GearItem b = GearItem.of("minecraft:bow");
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[Loadout.HOTBAR_TARGET] = a;
        slots[Loadout.HOTBAR_TARGET + 1] = b;
        GearItem[] pos = empty(0);
        pos[0] = b;
        pos[1] = a;
        ClickPlan plan = new ClickPlan();
        LoadoutPlanner.Result r = new LoadoutPlanner().plan(loadout(slots), pos, 0, 0, plan);
        assertEquals(1, r.swaps()); // one swap fixes both
        assertSame(a, pos[0]);
        assertSame(b, pos[1]);
    }

    @Test
    void bookReplacesByNameCaseInsensitiveAndSurvivesGarbage() {
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[5] = GearItem.of("minecraft:diamond_pickaxe");
        LoadoutBook b = new LoadoutBook();
        b.put(new Loadout("Mining", slots));
        slots[6] = GearItem.of("minecraft:torch");
        b.put(new Loadout("mining", slots));
        LoadoutBook back = LoadoutBook.decode(b.encode());
        assertEquals(1, back.all().size());
        assertEquals(2, back.get("MINING").filledSlots());
        assertTrue(LoadoutBook.decode("{not json").all().isEmpty());
    }

    @Test
    void jsonRoundTrip() {
        GearItem[] slots = new GearItem[Loadout.TARGETS];
        slots[2] = GearItem.of("minecraft:netherite_chestplate").withEnchant("minecraft:protection", 4).named("A|B");
        slots[7] = GearItem.of("minecraft:ender_pearl");
        Loadout l = Loadout.fromJson(new Loadout("PvP", slots).toJson());
        assertNotNull(l);
        assertEquals("PvP", l.name());
        assertEquals(2, l.filledSlots());
        assertEquals(4, l.slot(2).enchantments().get("minecraft:protection"));
        assertEquals("A/B", l.slot(2).customName());
        assertNull(l.slot(0));
    }
}
