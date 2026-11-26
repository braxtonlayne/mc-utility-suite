package com.utilitysuite.world.fortress;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import com.utilitysuite.world.seed.GeneratorProfile;
import com.utilitysuite.world.seed.StructureLocator;
import com.utilitysuite.world.stealth.SectionSweep;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.joml.Matrix4f;

import java.util.List;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Nether Fortress spawn boxes. Vanilla spawns blazes, wither skeletons, skeletons and magma cubes anywhere inside the
 * bounding boxes of a fortress's pieces. The client cannot see the pieces, so a budgeted sweep of the loaded Nether
 * sections records where fortress blocks (nether bricks, fences, stairs) are, from y {@value
 * FortressSections#FORTRESS_MIN_Y} up (the support pillars below are outside every piece); touching sections with enough bricks
 * form a fortress and their brick bounding boxes are drawn as the approximate spawn volumes ("approx"). Inside the
 * boxes near you, valid spawn floors are marked: purple where wither skeletons can spawn (3 blocks of room, block light
 * at or below 7, the Nether's monster light level), orange where only blazes fit or the light is too high. With a known
 * seed, nearby fortress/bastion start candidates are marked too (biome decides which one, so both are named).
 * Off by default.
 */
public final class FortressSpawnModule extends WorldModule {

    public static final String ID = "fortress_spawns";
    private static final int SECTIONS_PER_TICK = 6;
    private static final int FLOOR_CHECKS_PER_TICK = 1024;
    private static final int MAX_MARKS = 4096;
    private static final int BOX_COLOR = 0xA0FF4030;
    private static final int WITHER_COLOR = 0xE0B040FF;
    private static final int BLAZE_COLOR = 0xC0FFA020;
    private static final int SEED_COLOR = 0xC0FFE060;
    private static final String APPROX = "Fortress spawn area (approx)";
    private static final String CANDIDATE = "Fortress or bastion (seed candidate)";

    private final Supplier<GeneratorProfile> seedSource;
    private final FortressSections sections = new FortressSections();
    private final SectionSweep sweep = new SectionSweep();
    private final Predicate<BlockState> brick = FortressSpawnModule::isFortressBlock;
    private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos above = new BlockPos.MutableBlockPos();

    private int rangeChunks;
    private int minBricks;
    private boolean floor;
    private int floorRange;
    private int maxLight;
    private boolean seedHint;

    private boolean sweeping;
    // Rendered section boxes (block coordinates, max exclusive) and fortress cluster label points.
    private int[] boxes = new int[6 * 64];
    private int boxCount;
    private int[] labels = new int[3 * 8];
    private int labelCount;

    // Floor marks: front (rendered) and back (being filled).
    private long[] marks = new long[256];
    private byte[] markKind = new byte[256];
    private int markCount;
    private long[] backMarks = new long[256];
    private byte[] backKind = new byte[256];
    private int backMarkCount;
    private int floorBox;
    private int floorCursor;

    // Seed candidates (chunk centres, packed x << 32 | z), published by the executor.
    private volatile long[] candidates = new long[0];
    private int lastCandChunkX = Integer.MIN_VALUE;
    private int lastCandChunkZ = Integer.MIN_VALUE;
    private long lastCandSeed;

    public FortressSpawnModule(Supplier<GeneratorProfile> seedSource) {
        super(ID, "Fortress Spawns", false);
        this.seedSource = seedSource;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("range_chunks", "Range (chunks)", 6, 2, 12, "",
                        "Loaded Nether chunks this close are searched for fortress blocks."),
                SettingSpec.integer("min_bricks", "Min fortress blocks", 300, 50, 4000, "",
                        "A group of touching sections needs this many nether brick blocks to count as a fortress "
                                + "(smaller groups are treated as builds)."),
                SettingSpec.bool("floor", "Spawn floors", true, "",
                        "Mark valid spawn floors inside the boxes near you (purple: wither skeletons, orange: "
                                + "blazes only)."),
                SettingSpec.integer("floor_range", "Floor range", 16, 4, 32, "", "Floor marks this close."),
                SettingSpec.integer("max_light", "Wither skeleton max light", FortressSections.NETHER_MONSTER_LIGHT, 0,
                        15, "", "Highest block light for wither skeletons (vanilla Nether: 7)."),
                SettingSpec.bool("seed_hint", "Seed candidates", true, "",
                        "With a known seed, mark nearby fortress/bastion start chunks."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        rangeChunks = integer(p, "range_chunks");
        minBricks = integer(p, "min_bricks");
        floor = bool(p, "floor");
        floorRange = integer(p, "floor_range");
        maxLight = integer(p, "max_light");
        seedHint = bool(p, "seed_hint");
        lastCandChunkX = Integer.MIN_VALUE;
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @Override
    public void onWorldUnload() {
        reset();
    }

    private void reset() {
        sections.clear();
        sweeping = false;
        boxCount = 0;
        labelCount = 0;
        markCount = 0;
        backMarkCount = 0;
        candidates = new long[0];
        lastCandChunkX = Integer.MIN_VALUE;
    }

    private static boolean isFortressBlock(BlockState s) {
        return s.is(Blocks.NETHER_BRICKS) || s.is(Blocks.NETHER_BRICK_FENCE) || s.is(Blocks.NETHER_BRICK_STAIRS);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (level.dimension() != Level.NETHER) {
            if (sweeping || boxCount > 0) {
                reset();
            }
            return;
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        if (!sweeping || sweep.done()) {
            if (sweeping) {
                publishBoxes();
            }
            sweeping = true;
            sections.pruneOutside(pcx, pcz, rangeChunks + 2);
            // Nether: y 0..127 = sections 0..7.
            sweep.restart(pcx, 4, pcz, rangeChunks, 4);
        }
        for (int k = 0; k < SECTIONS_PER_TICK && sweep.next(); k++) {
            scanSection(level, sweep.x(), sweep.y(), sweep.z());
        }
        if (floor && boxCount > 0) {
            scanFloors(level, player);
        } else {
            markCount = 0;
        }
        if (seedHint) {
            requestCandidates(mc, pcx, pcz);
        }
    }

    private void scanSection(ClientLevel level, int sx, int sy, int sz) {
        int idx = level.getSectionIndexFromSectionY(sy);
        if (idx < 0 || idx >= level.getSectionsCount()) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) {
            return; // keep what we knew while the chunk is unloaded
        }
        LevelChunkSection section = chunk.getSection(idx);
        int firstY = FortressSections.firstPieceLocalY(sy); // support pillars below the piece band never spawn
        if (firstY >= 16 || section.hasOnlyAir() || !section.maybeHas(brick)) {
            sections.put(sx, sy, sz, 0L);
            return;
        }
        int n = 0;
        int x0 = 16;
        int y0 = 16;
        int z0 = 16;
        int x1 = -1;
        int y1 = -1;
        int z1 = -1;
        for (int ly = firstY; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (isFortressBlock(section.getBlockState(lx, ly, lz))) {
                        n++;
                        x0 = Math.min(x0, lx);
                        y0 = Math.min(y0, ly);
                        z0 = Math.min(z0, lz);
                        x1 = Math.max(x1, lx);
                        y1 = Math.max(y1, ly);
                        z1 = Math.max(z1, lz);
                    }
                }
            }
        }
        sections.put(sx, sy, sz, FortressSections.summary(n, x0, y0, z0, x1, y1, z1));
    }

    /** Clusters the finished sweep and copies the fortress sections' boxes into the render arrays. */
    private void publishBoxes() {
        sections.cluster();
        boxCount = 0;
        labelCount = 0;
        for (int i = 0, n = sections.size(); i < n; i++) {
            if (sections.clusterBricks(sections.clusterOf(i)) < minBricks) {
                continue;
            }
            if ((boxCount + 1) * 6 > boxes.length) {
                boxes = java.util.Arrays.copyOf(boxes, boxes.length * 2); // rare growth
            }
            int b = boxCount * 6;
            boxes[b] = sections.boxMinX(i);
            boxes[b + 1] = sections.boxMinY(i);
            boxes[b + 2] = sections.boxMinZ(i);
            boxes[b + 3] = sections.boxMaxX(i);
            boxes[b + 4] = sections.boxMaxY(i);
            boxes[b + 5] = sections.boxMaxZ(i);
            boxCount++;
        }
        for (int c = 0, n = sections.clusterCount(); c < n && labelCount < labels.length / 3; c++) {
            if (sections.clusterBricks(c) < minBricks) {
                continue;
            }
            int l = labelCount * 3;
            labels[l] = (sections.clusterBox(c, 0) + sections.clusterBox(c, 3)) / 2;
            labels[l + 1] = sections.clusterBox(c, 4) + 2;
            labels[l + 2] = (sections.clusterBox(c, 2) + sections.clusterBox(c, 5)) / 2;
            labelCount++;
        }
        if (floorBox >= boxCount) {
            floorBox = 0;
            floorCursor = 0;
        }
    }

    /** Budgeted walk over the feet positions of the boxes near the player; swaps buffers after the last box. */
    private void scanFloors(ClientLevel level, LocalPlayer player) {
        int budget = FLOOR_CHECKS_PER_TICK;
        double r2 = (double) floorRange * floorRange;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        while (budget > 0) {
            if (floorBox >= boxCount) {
                swapMarks();
                floorBox = 0;
                floorCursor = 0;
                return;
            }
            int b = floorBox * 6;
            int x0 = boxes[b];
            int y0 = boxes[b + 1];
            int z0 = boxes[b + 2];
            int sx = boxes[b + 3] - x0;
            int sy = boxes[b + 4] - y0;
            int sz = boxes[b + 5] - z0;
            int volume = sx * sy * sz;
            // Skip boxes entirely out of range.
            double cx = Math.max(x0, Math.min(px, x0 + sx));
            double cy = Math.max(y0, Math.min(py, y0 + sy));
            double cz = Math.max(z0, Math.min(pz, z0 + sz));
            double ddx = cx - px;
            double ddy = cy - py;
            double ddz = cz - pz;
            if (volume <= 0 || ddx * ddx + ddy * ddy + ddz * ddz > r2) {
                floorBox++;
                floorCursor = 0;
                continue;
            }
            while (budget > 0 && floorCursor < volume) {
                int i = floorCursor++;
                budget--;
                int x = x0 + i % sx;
                int z = z0 + (i / sx) % sz;
                int y = y0 + i / (sx * sz);
                double dx = x + 0.5 - px;
                double dy = y - py;
                double dz = z + 0.5 - pz;
                if (dx * dx + dy * dy + dz * dz > r2) {
                    continue;
                }
                int k = classifyFeet(level, x, y, z);
                if (k != FortressSections.SPAWN_NONE && backMarkCount < MAX_MARKS) {
                    pushMark(BlockPosPacking.pack(x, y - 1, z), (byte) k);
                }
            }
            if (floorCursor >= volume) {
                floorBox++;
                floorCursor = 0;
            }
        }
    }

    private int classifyFeet(ClientLevel level, int x, int y, int z) {
        pos.set(x, y - 1, z);
        BlockState fs = level.getBlockState(pos);
        if (fs.isAir() || !fs.isValidSpawn(level, pos, EntityTypes.WITHER_SKELETON)) {
            return FortressSections.SPAWN_NONE;
        }
        int empty = 0;
        for (int k = 0; k < 3; k++) {
            above.set(x, y + k, z);
            BlockState s = level.getBlockState(above);
            if (!NaturalSpawner.isValidEmptySpawnBlock(level, above, s, s.getFluidState(),
                    EntityTypes.WITHER_SKELETON)) {
                break;
            }
            empty++;
        }
        above.set(x, y, z);
        return FortressSections.spawnClass(true, empty, level.getBrightness(LightLayer.BLOCK, above), maxLight);
    }

    private void pushMark(long p, byte k) {
        if (backMarkCount == backMarks.length) {
            backMarks = java.util.Arrays.copyOf(backMarks, backMarkCount * 2); // rare growth
            backKind = java.util.Arrays.copyOf(backKind, backMarkCount * 2);
        }
        backMarks[backMarkCount] = p;
        backKind[backMarkCount] = k;
        backMarkCount++;
    }

    private void swapMarks() {
        long[] tm = marks;
        byte[] tk = markKind;
        marks = backMarks;
        markKind = backKind;
        markCount = backMarkCount;
        backMarks = tm;
        backKind = tk;
        backMarkCount = 0;
    }

    // ---- seed candidates -------------------------------------------------------------------------------------------

    private void requestCandidates(Minecraft mc, int pcx, int pcz) {
        long seed;
        IntegratedServer sp = mc.getSingleplayerServer();
        GeneratorProfile p = seedSource == null ? null : seedSource.get();
        if (sp != null) {
            seed = sp.getWorldGenSettings().options().seed();
        } else if (p != null && p.seedKnown()) {
            seed = p.seed();
        } else {
            return;
        }
        if (Math.abs(pcx - lastCandChunkX) < 4 && Math.abs(pcz - lastCandChunkZ) < 4 && seed == lastCandSeed) {
            return;
        }
        lastCandChunkX = pcx;
        lastCandChunkZ = pcz;
        lastCandSeed = seed;
        int bx = (pcx << 4) + 8;
        int bz = (pcz << 4) + 8;
        WorldExecutor.get().submit(() -> { // off-thread; publishes an immutable array
            List<StructureLocator.Candidate> list = StructureLocator.nearest(seed, StructureLocator.Dim.NETHER, bx,
                    bz, 384, 4, 4);
            long[] out = list.stream().filter(c -> "nether_complex".equals(c.type()))
                    .mapToLong(c -> ((long) c.chunkX() << 32) | (c.chunkZ() & 0xFFFFFFFFL)).toArray();
            candidates = out;
        });
    }

    // ---- render ----------------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        for (int i = 0; i < boxCount; i++) {
            int b = i * 6;
            WorldRenderKit.dashedBox(ctx.lines, ctx.pose, ctx.rx(boxes[b]), ctx.ry(boxes[b + 1]), ctx.rz(boxes[b + 2]),
                    ctx.rx(boxes[b + 3]), ctx.ry(boxes[b + 4]), ctx.rz(boxes[b + 5]), BOX_COLOR);
        }
        for (int i = 0; i < markCount; i++) {
            long p = marks[i];
            float x = ctx.rx(BlockPosPacking.x(p));
            float y = ctx.ry(BlockPosPacking.y(p) + 1.02);
            float z = ctx.rz(BlockPosPacking.z(p));
            int c = markKind[i] == FortressSections.SPAWN_WITHER ? WITHER_COLOR : BLAZE_COLOR;
            WorldRenderKit.square(ctx.lines, ctx.pose, x + 0.1f, y, z + 0.1f, x + 0.9f, z + 0.9f, c);
        }
        if (seedHint) {
            long[] cand = candidates;
            for (long k : cand) {
                float x = ctx.rx((((int) (k >> 32)) << 4) + 8);
                float z = ctx.rz((((int) k) << 4) + 8);
                WorldRenderKit.line(ctx.lines, ctx.pose, x, ctx.ry(0), z, x, ctx.ry(128), z, SEED_COLOR);
            }
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        for (int i = 0; i < labelCount; i++) {
            int l = i * 3;
            if (ctx.distSq(labels[l], labels[l + 1], labels[l + 2]) > 160 * 160) {
                continue;
            }
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(labels[l]), ctx.ry(labels[l + 1]), ctx.rz(labels[l + 2]));
            WorldRenderKit.text(ctx.font, ctx.buffers, m, APPROX, -ctx.font.width(APPROX) / 2f, BOX_COLOR | 0xFF000000);
        }
        if (seedHint) {
            long[] cand = candidates;
            for (long k : cand) {
                int bx = (((int) (k >> 32)) << 4) + 8;
                int bz = (((int) k) << 4) + 8;
                if (ctx.distSq(bx, (int) ctx.camY, bz) > 256 * 256) {
                    continue;
                }
                Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(bx + 0.5), ctx.ry(ctx.camY + 4), ctx.rz(bz + 0.5));
                WorldRenderKit.text(ctx.font, ctx.buffers, m, CANDIDATE, -ctx.font.width(CANDIDATE) / 2f, SEED_COLOR);
            }
        }
    }
}
