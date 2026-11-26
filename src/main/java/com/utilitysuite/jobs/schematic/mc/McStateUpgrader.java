package com.utilitysuite.jobs.schematic.mc;

import com.mojang.serialization.Dynamic;
import com.utilitysuite.jobs.schematic.format.StateUpgrader;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.util.datafix.DataFixers;
import net.minecraft.util.datafix.fixes.References;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Runs schematic palette states saved by older game versions through the vanilla DataFixer ({@code block_state}), so
 * a 1.21.1 schematic (e.g. {@code minecraft:chain}) resolves in 26.3 ({@code minecraft:iron_chain}). Called on the
 * schematic thread while a file is read (palette entries only, cached); thread-safe.
 */
public final class McStateUpgrader implements StateUpgrader {

    private final Map<String, String> cache = new ConcurrentHashMap<>();
    /** Running data version, read on first use (the game version is set during early startup). */
    private volatile int current = -1;

    @Override
    public String upgrade(String state, int dataVersion) {
        int cur = current;
        if (cur < 0) {
            cur = SharedConstants.getCurrentVersion().dataVersion().version();
            current = cur;
        }
        if (dataVersion <= 0 || dataVersion >= cur) {
            return state;
        }
        String canonical = BlockStateString.canonical(state);
        int to = cur;
        return cache.computeIfAbsent(dataVersion + "|" + canonical, k -> fix(canonical, dataVersion, to));
    }

    private String fix(String canonical, int dataVersion, int current) {
        CompoundTag tag = new CompoundTag();
        tag.putString("Name", BlockStateString.blockId(canonical));
        int bracket = canonical.indexOf('[');
        if (bracket >= 0) {
            CompoundTag props = new CompoundTag();
            String body = canonical.substring(bracket + 1, canonical.length() - 1);
            for (String part : body.split(",")) {
                int eq = part.indexOf('=');
                if (eq > 0) {
                    props.putString(part.substring(0, eq), part.substring(eq + 1));
                }
            }
            tag.put("Properties", props);
        }
        Tag out = DataFixers.getDataFixer()
                .update(References.BLOCK_STATE, new Dynamic<>(NbtOps.INSTANCE, (Tag) tag), dataVersion, current)
                .getValue();
        if (!(out instanceof CompoundTag fixed)) {
            return canonical;
        }
        String id = fixed.getStringOr("Name", "");
        if (id.isEmpty()) {
            return canonical;
        }
        TreeMap<String, String> map = new TreeMap<>();
        if (fixed.get("Properties") instanceof CompoundTag p) {
            for (String key : p.keySet()) {
                map.put(key, p.getStringOr(key, ""));
            }
        }
        return BlockStateString.of(id, map);
    }
}
