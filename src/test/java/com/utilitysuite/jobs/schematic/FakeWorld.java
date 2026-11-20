package com.utilitysuite.jobs.schematic;

import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.jobs.schematic.world.PlacementStatus;
import com.utilitysuite.jobs.schematic.world.WorldProbe;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;

/** Map-backed world for tests: unset cells are air; everything is loaded within the build height. */
final class FakeWorld implements WorldProbe {

    private final SchematicVolume volume;
    private final Long2ObjectOpenHashMap<String> blocks = new Long2ObjectOpenHashMap<>();
    int minY = -64;
    int maxY = 320;

    FakeWorld(SchematicVolume volume) {
        this.volume = volume;
    }

    FakeWorld set(int x, int y, int z, String state) {
        blocks.put(BlockPos.asLong(x, y, z), BlockStateString.canonical(state));
        return this;
    }

    @Override
    public int probe(int x, int y, int z, int paletteIndex) {
        if (y < minY || y >= maxY) {
            return PlacementStatus.W_UNLOADED;
        }
        String s = blocks.get(BlockPos.asLong(x, y, z));
        if (paletteIndex >= 0 && s != null) {
            String want = volume.paletteEntry(paletteIndex);
            if (s.equals(want)) {
                return PlacementStatus.W_MATCH;
            }
            if (BlockStateString.blockId(s).equals(BlockStateString.blockId(want))) {
                return PlacementStatus.W_SAME_BLOCK;
            }
        }
        if (s == null || s.equals("minecraft:air")) {
            return paletteIndex >= 0 && volume.isAir(paletteIndex) ? PlacementStatus.W_MATCH : PlacementStatus.W_EMPTY;
        }
        String id = BlockStateString.blockId(s);
        if (id.equals("minecraft:water") || id.equals("minecraft:lava")) {
            return PlacementStatus.W_FLUID;
        }
        if (id.equals("minecraft:short_grass")) {
            return PlacementStatus.W_EMPTY;
        }
        return PlacementStatus.W_OTHER;
    }
}
