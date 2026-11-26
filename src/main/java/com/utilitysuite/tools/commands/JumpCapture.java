package com.utilitysuite.tools.commands;

import com.utilitysuite.core.api.Services;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * {@code .jumpcap [note]}: saves the blocks around the player (exact collision boxes), the player's position, look and
 * velocity, and the movement layer's one-line diagnostics to {@code <game dir>/utilitysuite/jumpcap/}, so a jump Movement
 * Assist got wrong can be rebuilt block for block as a test. Run by hand, once: reading the cells allocates, which is
 * fine here and never happens per tick. The file is written on a background thread.
 */
final class JumpCapture {

    /** Cells captured: this many blocks out horizontally, {@link #DOWN} below and {@link #UP} above the feet. */
    static final int RADIUS = 6;
    static final int DOWN = 4;
    static final int UP = 6;

    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "utilitysuite-jumpcap");
        t.setDaemon(true);
        return t;
    });

    private JumpCapture() {
    }

    /** Captures on the client thread; returns the file it will be written to, or null without a world. */
    static Path capture(String note) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        Level level = mc.level;
        if (p == null || level == null) {
            return null;
        }
        String json = toJson(level, p, note == null ? "" : note.trim());
        Path dir = mc.gameDirectory.toPath().resolve("utilitysuite").resolve("jumpcap");
        Path file = dir.resolve("jumpcap-" + System.currentTimeMillis() + ".json");
        WRITER.execute(() -> {
            try {
                Files.createDirectories(dir);
                Files.writeString(file, json, StandardCharsets.UTF_8);
            } catch (IOException e) {
                com.mojang.logging.LogUtils.getLogger().warn("jumpcap write failed: {}", file, e);
            }
        });
        return file;
    }

    private static String toJson(Level level, LocalPlayer p, String note) {
        int ox = BlockPos.containing(p.getX(), 0, 0).getX();
        int oy = BlockPos.containing(0, p.getY(), 0).getY();
        int oz = BlockPos.containing(0, 0, p.getZ()).getZ();
        Map<BlockState, Integer> palette = new LinkedHashMap<>();
        List<String> shapes = new ArrayList<>();
        StringBuilder cells = new StringBuilder();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int dy = -DOWN; dy <= UP; dy++) {
            for (int dz = -RADIUS; dz <= RADIUS; dz++) {
                for (int dx = -RADIUS; dx <= RADIUS; dx++) {
                    pos.set(ox + dx, oy + dy, oz + dz);
                    BlockState st = level.getBlockState(pos);
                    if (st.isAir()) {
                        continue;
                    }
                    Integer idx = palette.get(st);
                    if (idx == null) {
                        idx = palette.size();
                        palette.put(st, idx);
                        shapes.add(boxes(st.getCollisionShape(level, pos)));
                    }
                    if (!cells.isEmpty()) {
                        cells.append(',');
                    }
                    cells.append('[').append(dx).append(',').append(dy).append(',').append(dz).append(',').append(idx)
                            .append(']');
                }
            }
        }
        Vec3 v = p.getDeltaMovement();
        StringBuilder b = new StringBuilder(4096 + cells.length());
        b.append("{\n  \"format\": 1,\n  \"minecraft\": ").append(str(SharedConstants.getCurrentVersion().name()))
                .append(",\n  \"note\": ").append(str(note))
                .append(",\n  \"origin\": [").append(ox).append(", ").append(oy).append(", ").append(oz).append(']')
                .append(",\n  \"player\": {")
                .append("\"x\": ").append(num(p.getX())).append(", \"y\": ").append(num(p.getY()))
                .append(", \"z\": ").append(num(p.getZ()))
                .append(", \"yaw\": ").append(num(p.getYRot())).append(", \"pitch\": ").append(num(p.getXRot()))
                .append(", \"vx\": ").append(num(v.x)).append(", \"vy\": ").append(num(v.y))
                .append(", \"vz\": ").append(num(v.z))
                .append(", \"onGround\": ").append(p.onGround()).append(", \"sprinting\": ").append(p.isSprinting())
                .append(", \"crouching\": ").append(p.isCrouching()).append('}')
                .append(",\n  \"assist\": ").append(str(Services.plannedJump().diagnostics()))
                .append(",\n  \"palette\": [");
        int i = 0;
        for (BlockState st : palette.keySet()) {
            b.append(i == 0 ? "\n    " : ",\n    ").append("{\"state\": ").append(str(net.minecraft.commands.arguments.blocks.BlockStateParser.serialize(st)))
                    .append(", \"boxes\": ").append(shapes.get(i)).append('}');
            i++;
        }
        b.append("\n  ],\n  \"cells\": [").append(cells).append("]\n}\n");
        return b.toString();
    }

    /** Block-local collision boxes as [[minX, minY, minZ, maxX, maxY, maxZ], ...]. */
    private static String boxes(VoxelShape shape) {
        StringBuilder b = new StringBuilder("[");
        List<AABB> list = shape.toAabbs();
        for (int i = 0; i < list.size(); i++) {
            AABB a = list.get(i);
            b.append(i == 0 ? "" : ", ").append('[').append(num(a.minX)).append(", ").append(num(a.minY)).append(", ")
                    .append(num(a.minZ)).append(", ").append(num(a.maxX)).append(", ").append(num(a.maxY)).append(", ")
                    .append(num(a.maxZ)).append(']');
        }
        return b.append(']').toString();
    }

    private static String num(double d) {
        return String.format(Locale.ROOT, "%.5f", d);
    }

    private static String str(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                default -> {
                    if (c < 0x20) {
                        b.append(String.format(Locale.ROOT, "\\u%04x", (int) c));
                    } else {
                        b.append(c);
                    }
                }
            }
        }
        return b.append('"').toString();
    }
}
