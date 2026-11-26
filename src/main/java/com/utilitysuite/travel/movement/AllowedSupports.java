package com.utilitysuite.travel.movement;

import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.ArrayList;
import java.util.List;

/**
 * The block types Parkour blocks may place against on a map that only allows building on some blocks (the "Place only
 * on allowed blocks" setting, filled with the ] key). Kept as a comma-separated list of block ids in the world's or
 * server's profile. {@link #allowed} runs inside the planner: one block-state read and a set lookup, no allocation.
 * Client thread only.
 */
final class AllowedSupports implements BlockAssistPlanner.SupportFilter {

    private final ReferenceOpenHashSet<Block> blocks = new ReferenceOpenHashSet<>();
    /** Ids in the order they were added (the saved text). */
    private final List<String> ids = new ArrayList<>();
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();

    /** Replaces the list from the saved text; unknown ids are kept in the text but match nothing. */
    void parse(String text) {
        blocks.clear();
        ids.clear();
        if (text == null) {
            return;
        }
        for (String raw : text.split(",")) {
            String id = raw.trim();
            if (id.isEmpty() || ids.contains(id)) {
                continue;
            }
            ids.add(id);
            Block b = block(id);
            if (b != null) {
                blocks.add(b);
            }
        }
    }

    /** Adds the block's type, or removes it when already listed. @return true when it is now allowed */
    boolean toggle(Block b) {
        String id = BuiltInRegistries.BLOCK.getKey(b).toString();
        if (ids.remove(id)) {
            blocks.remove(b);
            return false;
        }
        ids.add(id);
        blocks.add(b);
        return true;
    }

    /** The saved form: ids joined by commas. */
    String text() {
        return String.join(",", ids);
    }

    int size() {
        return ids.size();
    }

    @Override
    public boolean allowed(int x, int y, int z) {
        Level level = Minecraft.getInstance().level;
        return level != null && blocks.contains(level.getBlockState(pos.set(x, y, z)).getBlock());
    }

    private static Block block(String id) {
        Identifier key = Identifier.tryParse(id.contains(":") ? id : "minecraft:" + id);
        return key == null ? null : BuiltInRegistries.BLOCK.getOptional(key).orElse(null);
    }
}
