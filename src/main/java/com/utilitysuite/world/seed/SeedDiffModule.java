package com.utilitysuite.world.seed;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.overlays.OverlayStyle;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;

import java.util.Arrays;
import java.util.List;
import java.util.function.Supplier;

/**
 * Real-time Seed-Diff Base &amp; Tunnel Detector: incrementally snapshots loaded chunk sections around the player,
 * evaluates them off-thread against {@link TerrainBaseline} on {@link com.utilitysuite.world.WorldExecutor}, and draws
 * subtle wireframe anomaly boxes for probable artificial tunnels and cleared chambers.
 */
public final class SeedDiffModule extends WorldModule {

    public static final String ID = "seed_diff";

    private static final int COLOR_TUNNEL = 0x99FFAA33;
    private static final int COLOR_CHAMBER = 0x99FF4466;
    private static final int COLOR_OCCUPANCY = 0x8066CCFF;
    private static final int EVICT_INTERVAL_TICKS = 20;

    private final Supplier<GeneratorProfile> profileLookup;
    private final SeedDiffService service = new SeedDiffService();

    // Reusable client-thread scratch buffers (never mutated off-thread; cloned only when a section is submitted).
    private final long[] scratchMask = new long[64];
    private final int[] scratchHeights = new int[256];

    // Settings (mutated via readSettings).
    private int radiusChunks;
    private double minConfidence;
    private boolean flagTunnels;
    private boolean flagChambers;
    private int chunksPerTick;

    // Incremental scan state (client thread only).
    private long lastSeed;
    private boolean lastSeedKnown;
    private String lastDimension = "";
    private int scanDx;
    private int scanDz;
    private int scanSy;
    private int ticks;

    public SeedDiffModule(Supplier<GeneratorProfile> profileLookup) {
        super(ID, "Seed Diff Detector", false);
        this.profileLookup = profileLookup;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("radius_chunks", "Scan radius (chunks)", 6, 2, 10, "Detection",
                        "Chunk radius around the player to compare against the world seed baseline."),
                SettingSpec.decimal("min_confidence", "Min confidence", 0.65, 0.2, 0.95, "Detection",
                        "Minimum anomaly score required before a section is highlighted."),
                SettingSpec.bool("flag_tunnels", "Flag 1x2 tunnels", true, "Detection",
                        "Highlight straight artificial 1x2 strip-mine tunnels."),
                SettingSpec.bool("flag_chambers", "Flag chambers", true, "Detection",
                        "Highlight flat artificial rooms and cleared underground bunkers."),
                SettingSpec.integer("chunks_per_tick", "Sections per tick", 2, 1, 6, "Performance",
                        "Maximum chunk sections snapshotted per client tick."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        radiusChunks = integer(p, "radius_chunks");
        minConfidence = decimal(p, "min_confidence");
        flagTunnels = bool(p, "flag_tunnels");
        flagChambers = bool(p, "flag_chambers");
        chunksPerTick = integer(p, "chunks_per_tick");
    }

    @Override
    protected void onEnable() {
        resetScanCursor();
    }

    @Override
    protected void onDisable() {
        service.clear();
        resetScanCursor();
    }

    @Override
    public void onWorldUnload() {
        service.clear();
        lastSeedKnown = false;
        lastSeed = 0L;
        lastDimension = "";
        resetScanCursor();
    }

    private void resetScanCursor() {
        scanDx = -radiusChunks;
        scanDz = -radiusChunks;
        scanSy = -4;
    }

    public SeedDiffService service() {
        return service;
    }

    @Override
    public void onTick() {
        if (!isEnabled()) {
            return;
        }
        service.drainCompleted(16);

        GeneratorProfile profile = profileLookup == null ? null : profileLookup.get();
        if (profile == null || !profile.seedKnown()) {
            if (lastSeedKnown) {
                service.clear();
                lastSeedKnown = false;
            }
            return;
        }

        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }

        long seed = profile.seed();
        String dim = level.dimension().location().toString();
        if (!lastSeedKnown || seed != lastSeed || !dim.equals(lastDimension)) {
            service.clear();
            lastSeedKnown = true;
            lastSeed = seed;
            lastDimension = dim;
            resetScanCursor();
        }

        int playerCx = player.getBlockX() >> 4;
        int playerCz = player.getBlockZ() >> 4;

        if (++ticks % EVICT_INTERVAL_TICKS == 0) {
            service.evictOutside(playerCx, playerCz, radiusChunks + 2);
        }

        int minSection = level.getMinSection();
        int maxSection = Math.min(level.getMaxSection() - 1, 8);
        if (scanSy < minSection || scanSy > maxSection) {
            scanSy = minSection;
        }

        int budget = chunksPerTick;
        int diameter = radiusChunks * 2 + 1;
        int maxProbes = diameter * diameter * 2;
        int submitted = 0;

        for (int probe = 0; probe < maxProbes && submitted < budget; probe++) {
            int cx = playerCx + scanDx;
            int cz = playerCz + scanDz;
            int sy = scanSy;
            advanceCursor(minSection, maxSection);

            if (!level.hasChunk(cx, cz) || service.isKnownOrPending(cx, sy, cz)) {
                continue;
            }

            LevelChunk chunk = level.getChunk(cx, cz);
            int sectionIdx = sy - minSection;
            if (sectionIdx < 0 || sectionIdx >= chunk.getSectionsCount()) {
                continue;
            }
            LevelChunkSection section = chunk.getSection(sectionIdx);
            if (section == null || section.hasOnlyAir()) {
                continue;
            }

            Arrays.fill(scratchMask, 0L);
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        if (!section.getBlockState(x, y, z).isAir()) {
                            TerrainBaseline.setSolid(scratchMask, x, y, z, true);
                        }
                    }
                }
            }
            for (int z = 0; z < 16; z++) {
                int row = z << 4;
                for (int x = 0; x < 16; x++) {
                    scratchHeights[row | x] = chunk.getHeight(Heightmap.Types.WORLD_SURFACE, x, z);
                }
            }

            if (service.submit(seed, cx, sy, cz, scratchMask.clone(), scratchHeights.clone())) {
                submitted++;
            }
        }
    }

    private void advanceCursor(int minSection, int maxSection) {
        scanSy++;
        if (scanSy <= maxSection) {
            return;
        }
        scanSy = minSection;
        scanDx++;
        if (scanDx <= radiusChunks) {
            return;
        }
        scanDx = -radiusChunks;
        scanDz++;
        if (scanDz > radiusChunks) {
            scanDz = -radiusChunks;
        }
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (cleanScreen() || !isEnabled()) {
            return;
        }
        int count = service.renderCount();
        if (count == 0) {
            return;
        }
        float styleScale = OverlayStyle.predictionScale();
        if (styleScale <= 0.01f) {
            return;
        }
        float minConf = (float) minConfidence;
        for (int i = 0; i < count; i++) {
            float conf = service.renderConfidence(i);
            if (conf < minConf) {
                continue;
            }
            int flags = service.renderFlags(i);
            boolean hasTunnel = (flags & TerrainBaseline.FLAG_TUNNEL) != 0;
            boolean hasChamber = (flags & TerrainBaseline.FLAG_CHAMBER) != 0;
            if ((!flagTunnels || !hasTunnel) && (!flagChambers || !hasChamber)) {
                if ((flags & TerrainBaseline.FLAG_OCCUPANCY) == 0 || (!flagTunnels && !flagChambers)) {
                    continue;
                }
            }

            long key = service.renderKey(i);
            int x0 = BlockPosPacking.x(key) << 4;
            int y0 = BlockPosPacking.y(key) << 4;
            int z0 = BlockPosPacking.z(key) << 4;
            float rx0 = ctx.rx(x0);
            float ry0 = ctx.ry(y0);
            float rz0 = ctx.rz(z0);

            int baseColor = hasChamber ? COLOR_CHAMBER : (hasTunnel ? COLOR_TUNNEL : COLOR_OCCUPANCY);
            int color = OverlayStyle.scaleAlpha(baseColor, styleScale * Math.min(1.0f, 0.45f + conf * 0.55f));
            WorldRenderKit.dashedBox(ctx.lines, ctx.pose, rx0, ry0, rz0, rx0 + 16f, ry0 + 16f, rz0 + 16f, color);
        }
    }
}
