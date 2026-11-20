package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.bom.BillOfMaterials;
import com.utilitysuite.jobs.schematic.bom.ItemEquivalents;
import com.utilitysuite.jobs.schematic.bom.ItemRequirement;
import com.utilitysuite.jobs.schematic.bom.MaterialDiff;
import com.utilitysuite.jobs.schematic.bom.SubstitutionRules;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MaterialsTest {

    // ---- item equivalents -------------------------------------------------------------------------------------------

    @ParameterizedTest(name = "{0} -> {1} x{2}")
    @CsvSource(delimiter = ';', value = {
            "minecraft:oak_slab[type=double,waterlogged=false];minecraft:oak_slab;2",
            "minecraft:oak_slab[type=top,waterlogged=true];minecraft:oak_slab;1",
            "minecraft:oak_door[facing=north,half=lower,hinge=left,open=false,powered=false];minecraft:oak_door;1",
            "minecraft:red_bed[facing=east,occupied=false,part=head];minecraft:red_bed;1",
            "minecraft:candle[candles=3,lit=false,waterlogged=false];minecraft:candle;3",
            "minecraft:sea_pickle[pickles=4,waterlogged=true];minecraft:sea_pickle;4",
            "minecraft:snow[layers=5];minecraft:snow;5",
            "minecraft:turtle_egg[eggs=2,hatch=0];minecraft:turtle_egg;2",
            "minecraft:wall_torch[facing=north];minecraft:torch;1",
            "minecraft:soul_wall_torch[facing=east];minecraft:soul_torch;1",
            "minecraft:redstone_wall_torch[facing=east,lit=true];minecraft:redstone_torch;1",
            "minecraft:spruce_wall_sign[facing=south,waterlogged=false];minecraft:spruce_sign;1",
            "minecraft:oak_wall_hanging_sign[facing=south,waterlogged=false];minecraft:oak_hanging_sign;1",
            "minecraft:skeleton_wall_skull[facing=south,powered=false];minecraft:skeleton_skull;1",
            "minecraft:creeper_wall_head[facing=south,powered=false];minecraft:creeper_head;1",
            "minecraft:white_wall_banner[facing=north];minecraft:white_banner;1",
            "minecraft:brain_coral_wall_fan[facing=north,waterlogged=true];minecraft:brain_coral_fan;1",
            "minecraft:redstone_wire[east=none,north=none,power=0,south=none,west=none];minecraft:redstone;1",
            "minecraft:tripwire[attached=false,disarmed=false,east=false,north=false,powered=false,south=false,west=false];minecraft:string;1",
            "minecraft:wheat[age=7];minecraft:wheat_seeds;1",
            "minecraft:carrots[age=3];minecraft:carrot;1",
            "minecraft:cave_vines_plant[berries=true];minecraft:glow_berries;1",
            "minecraft:kelp_plant;minecraft:kelp;1",
            "minecraft:farmland[moisture=7];minecraft:dirt;1",
            "minecraft:water_cauldron[level=3];minecraft:cauldron;1",
            "minecraft:oak_stairs[facing=east,half=top,shape=straight,waterlogged=true];minecraft:oak_stairs;1",
            "minecraft:stone;minecraft:stone;1",
    })
    void blockToItem(String state, String item, int count) {
        ItemRequirement r = ItemEquivalents.requirementFor(state);
        assertEquals(ItemRequirement.Kind.ITEM, r.kind(), state);
        assertEquals(item, r.item());
        assertEquals(count, r.count());
        assertNull(r.item2());
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "minecraft:oak_door[facing=north,half=upper,hinge=left,open=false,powered=false]",
            "minecraft:red_bed[facing=east,occupied=false,part=foot]",
            "minecraft:tall_grass[half=upper]",
            "minecraft:sunflower[half=upper]",
            "minecraft:piston_head[facing=up,short=false,type=normal]",
            "minecraft:moving_piston[facing=up,type=normal]",
            "minecraft:air",
            "minecraft:cave_air",
    })
    void costsNothing(String state) {
        ItemRequirement r = ItemEquivalents.requirementFor(state);
        assertEquals(ItemRequirement.Kind.NONE, r.kind(), state);
    }

    @ParameterizedTest
    @CsvSource(delimiter = ';', value = {
            "minecraft:water[level=0]", "minecraft:lava[level=0]", "minecraft:fire[age=0,east=false,north=false,south=false,up=false,west=false]",
            "minecraft:nether_portal[axis=x]"})
    void flaggedNotCounted(String state) {
        ItemRequirement r = ItemEquivalents.requirementFor(state);
        assertEquals(ItemRequirement.Kind.NONE, r.kind());
        assertTrue(r.note() != null && !r.note().isEmpty());
    }

    @ParameterizedTest
    @CsvSource({"minecraft:bedrock", "minecraft:command_block", "minecraft:spawner", "minecraft:end_portal_frame",
            "minecraft:barrier", "minecraft:infested_stone", "minecraft:reinforced_deepslate", "minecraft:budding_amethyst",
            "minecraft:structure_block", "minecraft:light"})
    void unobtainable(String state) {
        assertEquals(ItemRequirement.Kind.UNOBTAINABLE, ItemEquivalents.requirementFor(state).kind());
    }

    @Test
    void twoItemBlocks() {
        ItemRequirement pot = ItemEquivalents.requirementFor("minecraft:potted_red_tulip");
        assertEquals("minecraft:flower_pot", pot.item());
        assertEquals("minecraft:red_tulip", pot.item2());
        ItemRequirement cake = ItemEquivalents.requirementFor("minecraft:blue_candle_cake[lit=false]");
        assertEquals("minecraft:cake", cake.item());
        assertEquals("minecraft:blue_candle", cake.item2());
        ItemRequirement azalea = ItemEquivalents.requirementFor("minecraft:potted_azalea_bush");
        assertEquals("minecraft:azalea", azalea.item2());
    }

    @Test
    void billSeparatesUnobtainableAndFlagged() {
        SchematicVolume.Builder b = SchematicVolume.builder("t", "test", 4, 1, 2);
        b.set(0, 0, 0, "minecraft:bedrock");
        b.set(1, 0, 0, "minecraft:bedrock");
        b.set(2, 0, 0, "minecraft:water[level=0]");
        b.set(3, 0, 0, "minecraft:potted_poppy");
        b.set(0, 0, 1, "minecraft:red_bed[facing=east,occupied=false,part=head]");
        b.set(1, 0, 1, "minecraft:red_bed[facing=east,occupied=false,part=foot]");
        b.set(2, 0, 1, "minecraft:wheat[age=1]");
        b.set(3, 0, 1, "minecraft:air");
        BillOfMaterials bom = BillOfMaterials.compute(b.build(), SubstitutionRules.NONE);
        assertEquals(Map.of("minecraft:flower_pot", 1, "minecraft:poppy", 1, "minecraft:red_bed", 1,
                "minecraft:wheat_seeds", 1), bom.asMap());
        assertEquals(Map.of("minecraft:bedrock", 2), bom.unobtainable());
        assertEquals(2, bom.flagged().size()); // water + crop note
        assertEquals(7, bom.totalBlocks());
    }

    // ---- substitution -----------------------------------------------------------------------------------------------

    /** 3x3x3 cube of planks with a birch core: only the core is hidden. */
    private static SchematicVolume plankCube() {
        SchematicVolume.Builder b = SchematicVolume.builder("cube", "test", 3, 3, 3);
        for (int y = 0; y < 3; y++) {
            for (int z = 0; z < 3; z++) {
                for (int x = 0; x < 3; x++) {
                    b.set(x, y, z, x == 1 && y == 1 && z == 1 ? "minecraft:birch_planks" : "minecraft:oak_planks");
                }
            }
        }
        return b.build();
    }

    @Test
    void hiddenOnlyPlankRuleAppliesToTheCoreOnly() {
        SubstitutionRules rules = SubstitutionRules.builder().anyPlanks(SubstitutionRules.Scope.HIDDEN_ONLY).build();
        BillOfMaterials bom = BillOfMaterials.compute(plankCube(), rules);
        assertEquals(2, bom.lines().size());
        assertEquals(26, bom.required("minecraft:oak_planks"), "visible planks stay exact");
        // The hidden birch core is satisfied by spruce from the inventory.
        MaterialDiff diff = MaterialDiff.compute(bom, Map.of("minecraft:spruce_planks", 5,
                "minecraft:oak_planks", 26), Map.of());
        assertEquals(0, diff.totalMissing());
        MaterialDiff.Line core = diff.lines().stream().filter(MaterialDiff.Line::group).findFirst().orElseThrow();
        assertEquals(1, core.fromInventory());
        assertEquals("minecraft:oak_planks", core.preferredItem());
    }

    @Test
    void exactLinesAreServedBeforeGroups() {
        SubstitutionRules rules = SubstitutionRules.builder().anyPlanks(SubstitutionRules.Scope.HIDDEN_ONLY).build();
        BillOfMaterials bom = BillOfMaterials.compute(plankCube(), rules);
        // 26 oak planks: the exact line takes all of them; the hidden core finds nothing left and is missing.
        MaterialDiff diff = MaterialDiff.compute(bom, Map.of("minecraft:oak_planks", 26), Map.of());
        assertEquals(Map.of("minecraft:oak_planks", 1), diff.missing());
    }

    @Test
    void strictItemsNeverSubstitute() {
        SubstitutionRules rules = SubstitutionRules.builder().anyPlanks(SubstitutionRules.Scope.ANYWHERE)
                .strict("minecraft:birch_planks").build();
        assertNull(rules.groupFor("minecraft:birch_planks", true));
        assertTrue(rules.groupFor("minecraft:oak_planks", false) != null);
        BillOfMaterials bom = BillOfMaterials.compute(plankCube(), rules);
        assertEquals(1, bom.required("minecraft:birch_planks"));
        MaterialDiff diff = MaterialDiff.compute(bom, Map.of("minecraft:spruce_planks", 64), Map.of());
        assertEquals(Map.of("minecraft:birch_planks", 1), diff.missing());
    }

    @Test
    void deepslateMapsToStoneVariants() {
        SubstitutionRules rules = SubstitutionRules.builder().deepslateToStone(SubstitutionRules.Scope.ANYWHERE).build();
        SchematicVolume.Builder b = SchematicVolume.builder("d", "test", 3, 1, 1);
        b.set(0, 0, 0, "minecraft:cobbled_deepslate");
        b.set(1, 0, 0, "minecraft:cobbled_deepslate");
        b.set(2, 0, 0, "minecraft:deepslate_brick_stairs[facing=north,half=bottom,shape=straight,waterlogged=false]");
        BillOfMaterials bom = BillOfMaterials.compute(b.build(), rules);
        MaterialDiff diff = MaterialDiff.compute(bom, Map.of("minecraft:cobblestone", 1,
                "minecraft:cobbled_deepslate", 0), Map.of("minecraft:stone_brick_stairs", 3));
        assertEquals(Map.of("minecraft:cobblestone", 1), diff.missing(), "shortfall gathered as the stone variant");
        assertFalse(rules.isEmpty());
    }
}
