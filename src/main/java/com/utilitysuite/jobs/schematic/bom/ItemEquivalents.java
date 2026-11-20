package com.utilitysuite.jobs.schematic.bom;

import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;

import java.util.Map;
import java.util.Set;

/**
 * Table-driven mapping from a block state to the items it costs, without double counting multi-block and multi-part
 * blocks. Rules, in order:
 * <ol>
 *   <li>void and air cost nothing;</li>
 *   <li>survival-unobtainable blocks ({@link #UNOBTAINABLE}, {@code infested_*}) are flagged separately;</li>
 *   <li>the upper half of two-block-tall blocks ({@code half=upper}: doors, tall flowers, tall grass, small dripleaf,
 *       pitcher plants) and the foot of beds ({@code part=foot}) cost nothing, so each block counts once;</li>
 *   <li>{@link #SPECIAL} entries (fluids, fire, portals, piston heads, crops, plant stems, farmland...);</li>
 *   <li>potted plants cost a flower pot plus the plant; candle cakes a cake plus the candle;</li>
 *   <li>wall-mounted variants map to their item ({@code oak_wall_sign} to {@code oak_sign});</li>
 *   <li>count properties: double slabs (2), {@code candles}, {@code pickles}, {@code layers}, {@code eggs},
 *       {@code flower_amount};</li>
 *   <li>otherwise the item has the block's id.</li>
 * </ol>
 * {@code waterlogged=true} never adds a water bucket (placing into water, or the planner's fluid note, covers it).
 */
public final class ItemEquivalents {

    /** Blocks that cannot be obtained as items in survival. */
    public static final Set<String> UNOBTAINABLE = Set.of(
            "minecraft:bedrock", "minecraft:command_block", "minecraft:chain_command_block",
            "minecraft:repeating_command_block", "minecraft:spawner", "minecraft:trial_spawner", "minecraft:vault",
            "minecraft:end_portal_frame", "minecraft:end_portal", "minecraft:end_gateway", "minecraft:barrier",
            "minecraft:structure_block", "minecraft:jigsaw", "minecraft:light", "minecraft:reinforced_deepslate",
            "minecraft:budding_amethyst", "minecraft:petrified_oak_slab", "minecraft:chorus_plant",
            "minecraft:suspicious_sand", "minecraft:suspicious_gravel", "minecraft:frogspawn",
            "minecraft:test_block", "minecraft:test_instance_block");

    private static final ItemRequirement FLUID = ItemRequirement.flagged("fluid: place with a bucket");
    private static final ItemRequirement IGNITE = ItemRequirement.flagged("fire: light with flint and steel");

    /** Block id to requirement for blocks whose item differs from the block (or that have none). */
    private static final Map<String, ItemRequirement> SPECIAL = Map.ofEntries(
            Map.entry("minecraft:water", FLUID),
            Map.entry("minecraft:lava", FLUID),
            Map.entry("minecraft:bubble_column", FLUID),
            Map.entry("minecraft:fire", IGNITE),
            Map.entry("minecraft:soul_fire", IGNITE),
            Map.entry("minecraft:nether_portal", ItemRequirement.flagged("portal: light the obsidian frame")),
            Map.entry("minecraft:piston_head", ItemRequirement.none()),
            Map.entry("minecraft:moving_piston", ItemRequirement.none()),
            Map.entry("minecraft:frosted_ice", ItemRequirement.flagged("frosted ice: made by Frost Walker")),
            Map.entry("minecraft:wall_torch", ItemRequirement.item("minecraft:torch", 1)),
            Map.entry("minecraft:redstone_wire", ItemRequirement.item("minecraft:redstone", 1)),
            Map.entry("minecraft:tripwire", ItemRequirement.item("minecraft:string", 1)),
            Map.entry("minecraft:wheat", ItemRequirement.item("minecraft:wheat_seeds", 1, "crop: plant on farmland")),
            Map.entry("minecraft:carrots", ItemRequirement.item("minecraft:carrot", 1, "crop: plant on farmland")),
            Map.entry("minecraft:potatoes", ItemRequirement.item("minecraft:potato", 1, "crop: plant on farmland")),
            Map.entry("minecraft:beetroots",
                    ItemRequirement.item("minecraft:beetroot_seeds", 1, "crop: plant on farmland")),
            Map.entry("minecraft:melon_stem", ItemRequirement.item("minecraft:melon_seeds", 1)),
            Map.entry("minecraft:attached_melon_stem", ItemRequirement.item("minecraft:melon_seeds", 1)),
            Map.entry("minecraft:pumpkin_stem", ItemRequirement.item("minecraft:pumpkin_seeds", 1)),
            Map.entry("minecraft:attached_pumpkin_stem", ItemRequirement.item("minecraft:pumpkin_seeds", 1)),
            Map.entry("minecraft:torchflower_crop", ItemRequirement.item("minecraft:torchflower_seeds", 1)),
            Map.entry("minecraft:pitcher_crop", ItemRequirement.item("minecraft:pitcher_pod", 1)),
            Map.entry("minecraft:cocoa", ItemRequirement.item("minecraft:cocoa_beans", 1)),
            Map.entry("minecraft:sweet_berry_bush", ItemRequirement.item("minecraft:sweet_berries", 1)),
            Map.entry("minecraft:kelp_plant", ItemRequirement.item("minecraft:kelp", 1)),
            Map.entry("minecraft:cave_vines", ItemRequirement.item("minecraft:glow_berries", 1)),
            Map.entry("minecraft:cave_vines_plant", ItemRequirement.item("minecraft:glow_berries", 1)),
            Map.entry("minecraft:weeping_vines_plant", ItemRequirement.item("minecraft:weeping_vines", 1)),
            Map.entry("minecraft:twisting_vines_plant", ItemRequirement.item("minecraft:twisting_vines", 1)),
            Map.entry("minecraft:bamboo_sapling", ItemRequirement.item("minecraft:bamboo", 1)),
            Map.entry("minecraft:big_dripleaf_stem", ItemRequirement.item("minecraft:big_dripleaf", 1)),
            Map.entry("minecraft:tall_seagrass", ItemRequirement.item("minecraft:seagrass", 1, "needs shears")),
            Map.entry("minecraft:farmland", ItemRequirement.item("minecraft:dirt", 1, "farmland: till dirt with a hoe")),
            Map.entry("minecraft:dirt_path", ItemRequirement.item("minecraft:dirt", 1, "path: flatten dirt with a shovel")),
            Map.entry("minecraft:water_cauldron", ItemRequirement.item("minecraft:cauldron", 1, "cauldron: fill it")),
            Map.entry("minecraft:lava_cauldron", ItemRequirement.item("minecraft:cauldron", 1, "cauldron: fill it")),
            Map.entry("minecraft:powder_snow_cauldron",
                    ItemRequirement.item("minecraft:cauldron", 1, "cauldron: fill it")),
            Map.entry("minecraft:powder_snow", ItemRequirement.item("minecraft:powder_snow_bucket", 1)),
            Map.entry("minecraft:candle_cake", ItemRequirement.two("minecraft:cake", 1, "minecraft:candle", 1)),
            Map.entry("minecraft:potted_azalea_bush",
                    ItemRequirement.two("minecraft:flower_pot", 1, "minecraft:azalea", 1)),
            Map.entry("minecraft:potted_flowering_azalea_bush",
                    ItemRequirement.two("minecraft:flower_pot", 1, "minecraft:flowering_azalea", 1)));

    /** Wall-mounted block suffix to the item suffix, checked in order. */
    private static final String[][] WALL_SUFFIXES = {
            {"_wall_hanging_sign", "_hanging_sign"},
            {"_wall_sign", "_sign"},
            {"_wall_torch", "_torch"},
            {"_wall_banner", "_banner"},
            {"_wall_head", "_head"},
            {"_wall_skull", "_skull"},
            {"_wall_fan", "_fan"},
    };

    /** Properties that hold how many items one block contains. */
    private static final String[] COUNT_PROPERTIES = {"candles", "pickles", "layers", "eggs", "flower_amount",
            "segment_amount"};

    private ItemEquivalents() {
    }

    public static ItemRequirement requirementFor(String state) {
        String id = BlockStateString.blockId(state);
        if (id.equals(SchematicVolume.VOID) || id.equals(SchematicVolume.AIR) || id.equals("minecraft:cave_air")
                || id.equals("minecraft:void_air")) {
            return ItemRequirement.none();
        }
        String path = BlockStateString.path(id);
        if (UNOBTAINABLE.contains(id) || path.startsWith("infested_")) {
            return ItemRequirement.unobtainable("not obtainable in survival");
        }
        if ("upper".equals(BlockStateString.property(state, "half"))) {
            return ItemRequirement.none();
        }
        if (path.endsWith("_bed") && "foot".equals(BlockStateString.property(state, "part"))) {
            return ItemRequirement.none();
        }
        ItemRequirement special = SPECIAL.get(id);
        if (special != null) {
            return special;
        }
        String ns = BlockStateString.namespace(id);
        if (path.startsWith("potted_")) {
            return ItemRequirement.two("minecraft:flower_pot", 1, ns + ":" + path.substring("potted_".length()), 1);
        }
        if (path.endsWith("_candle_cake")) {
            return ItemRequirement.two("minecraft:cake", 1,
                    ns + ":" + path.substring(0, path.length() - "_cake".length()), 1);
        }
        String item = id;
        for (String[] suffix : WALL_SUFFIXES) {
            if (path.endsWith(suffix[0])) {
                item = ns + ":" + path.substring(0, path.length() - suffix[0].length()) + suffix[1];
                break;
            }
        }
        if (path.endsWith("_slab")) {
            return ItemRequirement.item(item, "double".equals(BlockStateString.property(state, "type")) ? 2 : 1);
        }
        for (String prop : COUNT_PROPERTIES) {
            int n = BlockStateString.intProperty(state, prop, -1);
            if (n > 0) {
                return ItemRequirement.item(item, n);
            }
        }
        return ItemRequirement.item(item, 1);
    }

    /** Requirements for every palette entry of a volume (computed once per volume, indexed by palette id). */
    public static ItemRequirement[] forPalette(SchematicVolume volume) {
        ItemRequirement[] out = new ItemRequirement[volume.paletteSize()];
        for (int i = 0; i < out.length; i++) {
            out[i] = SchematicVolume.isVoid(i) ? ItemRequirement.none() : requirementFor(volume.paletteEntry(i));
        }
        return out;
    }
}
