package com.utilitysuite.world.overlays;

import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldFiles;
import com.utilitysuite.world.WorldIds;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Reference2IntOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.BaseSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.WeatheringCopper;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Budgeted, incremental scanning of the client's loaded chunks, shared by every world overlay.
 * <ul>
 *   <li><b>Match scan</b> (ores, storage, spawners, custom ids): every loaded section is queued once on chunk load and
 *       scanned within a per-tick budget. Sections that are all air, or whose palette cannot contain a matching state
 *       ({@code LevelChunkSection.maybeHas}), are skipped without touching their 4096 blocks. Results live in
 *       {@link #observed}. Single block changes are applied incrementally.</li>
 *   <li><b>Near sweep</b> (cave cells and fluid-pocket surfaces): only sections within a small radius of the player,
 *       revisited round-robin, so the cost does not grow with render distance.</li>
 *   <li><b>Chunk age</b>: fluid-update bookkeeping for the exploration-trail heuristic.</li>
 * </ul>
 * Client thread only. Steady-state ticks allocate nothing (match lists are reused; the index copies them).
 */
public final class WorldScanner {

    /** Matches in loaded chunks (ores, storage, spawners, custom). */
    public final HighlightIndex observed = new HighlightIndex();
    /** Underground fluid source blocks that touch open space, near the player. */
    public final HighlightIndex fluids = new HighlightIndex();
    public final CaveCells caves = new CaveCells();
    public final ChunkAgeTracker chunkAge = new ChunkAgeTracker();

    private static final int MAX_FLUIDS_PER_SECTION = 768;
    private static final int CAVE_CELL_MIN_OPEN = 24;
    private static final int SKIP_BUDGET = 256;

    private static volatile ChunkAgeTracker activeChunkAge;

    private final Reference2IntOpenHashMap<Block> categoryCache = new Reference2IntOpenHashMap<>();
    private final LongOpenHashSet loadedChunks = new LongOpenHashSet();
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private final LongArrayList matchPos = new LongArrayList();
    private final IntArrayList matchVal = new IntArrayList();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final int[] cellOpen = new int[64];
    private final Predicate<BlockState> matcher = this::matches;
    private final Predicate<BlockState> fluidMatcher = s -> !s.getFluidState().isEmpty();
    private final Predicate<BlockState> oxidizedCopperMatcher =
            s -> s.getBlock() instanceof WeatheringCopper wc && wc.getAge() != WeatheringCopper.WeatherState.UNAFFECTED;

    private Level level;
    private String dimension = "";
    private int mask;
    private Set<String> customIds = Set.of();
    private Set<String> lastCustomIdsRef = customIds;
    private boolean nearCaves;
    private boolean nearFluids;
    private int nearRadiusSections = 3;
    private int nearCursor;
    private long nearCenter = Long.MIN_VALUE;
    private boolean trackChunkAge;
    private long gameTime;
    /** Game time of the last render-list rebuild (see {@link #RENDER_REBUILD_TICKS}). */
    private long lastRenderRebuild = -RENDER_REBUILD_TICKS;
    /**
     * Render lists are rebuilt at most this often. While sections stream in, the indexes change every tick, and
     * copying tens of thousands of entries each tick showed up in the playtest profile; a quarter second of lag on
     * the outlines is not visible in practice.
     */
    static final int RENDER_REBUILD_TICKS = 5;

    public WorldScanner() {
        categoryCache.defaultReturnValue(-1);
        activeChunkAge = this.chunkAge;
    }

    /** Most recently constructed scanner's chunk-age tracker (for minimap/autopilot integration). */
    public static ChunkAgeTracker activeChunkAge() {
        return activeChunkAge;
    }

    // ---- configuration ------------------------------------------------------------------------------------------

    /** What the match scan looks for. A change requeues every loaded section. */
    public void setMask(int newMask, Set<String> newCustomIds) {
        int m = newMask & ~BlockCategories.FLUIDS;
        if (m == mask && newCustomIds == lastCustomIdsRef) {
            return; // per-tick fast path: same mask, same set instance
        }
        lastCustomIdsRef = newCustomIds;
        Set<String> ids = Set.copyOf(newCustomIds);
        if (m == mask && ids.equals(customIds)) {
            return;
        }
        boolean idsChanged = !ids.equals(customIds);
        mask = m;
        customIds = ids;
        if (idsChanged) {
            categoryCache.clear();
        }
        observed.clear();
        requeueAll();
    }

    public int mask() {
        return mask;
    }

    public void setNearOptions(boolean caves, boolean fluidPockets, int radiusBlocks) {
        int r = Math.max(1, Math.min(6, (radiusBlocks + 15) >> 4));
        if (caves == nearCaves && fluidPockets == nearFluids && r == nearRadiusSections) {
            return;
        }
        nearCaves = caves;
        nearFluids = fluidPockets;
        nearRadiusSections = r;
        nearCenter = Long.MIN_VALUE;
        if (!caves) {
            this.caves.clear();
        }
        if (!fluidPockets) {
            fluids.clear();
        }
    }

    public void setTrackChunkAge(boolean on) {
        trackChunkAge = on;
        if (!on) {
            chunkAge.clear();
        }
    }

    public String dimension() {
        return dimension;
    }

    /** Category bits of a block (cached per block). */
    public int categoryOf(BlockState state) {
        Block b = state.getBlock();
        int c = categoryCache.getInt(b);
        if (c < 0) {
            c = BlockCategories.classify(WorldIds.block(b), customIds);
            categoryCache.put(b, c);
        }
        return c;
    }

    private boolean matches(BlockState s) {
        return (categoryOf(s) & mask) != 0;
    }

    // ---- lifecycle ----------------------------------------------------------------------------------------------

    public void reset() {
        lastRenderRebuild = -RENDER_REBUILD_TICKS;
        level = null;
        dimension = "";
        loadedChunks.clear();
        queue.clear();
        queued.clear();
        observed.clear();
        fluids.clear();
        caves.clear();
        chunkAge.clear();
        nearCenter = Long.MIN_VALUE;
    }

    private void ensureLevel(Level l) {
        if (l != level) {
            reset();
            level = l;
            dimension = WorldIds.dimension(l);
            observed.setDimension(dimension);
            fluids.setDimension(dimension);
        }
    }

    public void onChunkLoad(Level l, ChunkAccess chunk) {
        ensureLevel(l);
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        loadedChunks.add(BlockPosPacking.chunk(cx, cz));
        queueChunk(chunk, cx, cz);
        if (trackChunkAge) {
            boolean fluid = false;
            boolean oxidizedCopper = false;
            int nonAirSections = 0;
            LevelChunkSection[] secs = chunk.getSections();
            for (int i = 0; i < secs.length; i++) {
                LevelChunkSection sec = secs[i];
                if (sec == null || sec.hasOnlyAir()) {
                    continue;
                }
                nonAirSections++;
                if (!fluid && sec.maybeHas(fluidMatcher)) {
                    fluid = true;
                }
                if (!oxidizedCopper && sec.maybeHas(oxidizedCopperMatcher)) {
                    oxidizedCopper = true;
                }
            }
            boolean spawnerActive = false;
            if (chunk instanceof LevelChunk lc && !lc.getBlockEntities().isEmpty()) {
                for (BlockEntity be : lc.getBlockEntities().values()) {
                    if (be instanceof SpawnerBlockEntity sbe) {
                        BaseSpawner spawner = sbe.getSpawner();
                        if (spawner.getSpin() != 0.0 || spawner.getoSpin() != 0.0) {
                            spawnerActive = true;
                            break;
                        }
                    }
                }
            }
            chunkAge.onChunkLoad(cx, cz, gameTime, fluid);
            byte extraFlags = ChunkAgeTracker.FLAG_NONE;
            if (oxidizedCopper) {
                extraFlags |= ChunkAgeTracker.OXIDIZED_COPPER;
            }
            if (spawnerActive) {
                extraFlags |= ChunkAgeTracker.SPAWNER_ACTIVE;
            }
            boolean modifiedSignals = oxidizedCopper || spawnerActive;
            boolean paletteLooksOld = modifiedSignals && nonAirSections > 0;
            chunkAge.onPaletteCheck(cx, cz, paletteLooksOld, modifiedSignals, extraFlags);
        }
    }

    /** Persists recorded chunk-age trails to {@code file} via {@link WorldFiles}. */
    public void saveTrails(Path file, boolean async) {
        if (file == null) {
            return;
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream(Math.max(64, chunkAge.size() * 10 + 16));
        try (DataOutputStream out = new DataOutputStream(baos)) {
            chunkAge.write(out);
        } catch (IOException e) {
            return;
        }
        byte[] data = baos.toByteArray();
        if (async) {
            WorldFiles.writeAsync(file, data);
        } else {
            WorldFiles.writeNow(file, data);
        }
    }

    /** Loads recorded chunk-age trails from {@code file} via {@link WorldFiles}. */
    public boolean loadTrails(Path file) {
        if (file == null) {
            return false;
        }
        byte[] data = WorldFiles.read(file);
        if (data == null || data.length == 0) {
            return false;
        }
        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(data))) {
            chunkAge.read(in);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public void onChunkUnload(Level l, ChunkAccess chunk) {
        if (l != level) {
            return;
        }
        int cx = chunk.getPos().x;
        int cz = chunk.getPos().z;
        loadedChunks.remove(BlockPosPacking.chunk(cx, cz));
        int minS = chunk.getMinSection();
        int maxS = chunk.getMaxSection();
        for (int sy = minS; sy < maxS; sy++) {
            long key = HighlightIndex.sectionKey(cx, sy, cz);
            observed.removeSection(key);
            fluids.removeSection(key);
            caves.setSection(key, 0L);
            queued.remove(key); // the stale queue entry is skipped when popped (chunk no longer loaded)
        }
        chunkAge.onChunkUnload(cx, cz);
    }

    private void queueChunk(ChunkAccess chunk, int cx, int cz) {
        for (int sy = chunk.getMinSection(); sy < chunk.getMaxSection(); sy++) {
            long key = HighlightIndex.sectionKey(cx, sy, cz);
            if (queued.add(key)) {
                queue.enqueue(key);
            }
        }
    }

    private void requeueAll() {
        if (level == null) {
            return;
        }
        int minS = level.getMinSection();
        int maxS = level.getMaxSection();
        LongIterator it = loadedChunks.iterator();
        while (it.hasNext()) {
            long ck = it.nextLong();
            int cx = BlockPosPacking.chunkX(ck);
            int cz = BlockPosPacking.chunkZ(ck);
            for (int sy = minS; sy < maxS; sy++) {
                long key = HighlightIndex.sectionKey(cx, sy, cz);
                if (queued.add(key)) {
                    queue.enqueue(key);
                }
            }
        }
    }

    /** Incremental update for one block (client block update packets, own edits). */
    public void onBlockChanged(Level l, BlockPos pos, BlockState oldState, BlockState newState) {
        if (l != level) {
            return;
        }
        if (trackChunkAge) {
            FluidState fs = newState.getFluidState();
            if (!fs.isEmpty() && !fs.isSource()) {
                chunkAge.onFluidUpdate(pos.getX() >> 4, pos.getZ() >> 4, gameTime);
            }
        }
        if (mask == 0) {
            return;
        }
        long packed = BlockPosPacking.pack(pos.getX(), pos.getY(), pos.getZ());
        int cat = categoryOf(newState) & mask;
        observed.setBlock(packed, cat == 0 ? 0 : HighlightIndex.value(cat, isExposed(l, pos.getX(), pos.getY(),
                pos.getZ()), observed.idIndex(WorldIds.block(newState.getBlock()))));
        // Opening or closing a block changes the exposure of the matches next to it.
        if (oldState.isAir() != newState.isAir()) {
            for (int d = 0; d < 6; d++) {
                int nx = pos.getX() + DX[d];
                int ny = pos.getY() + DY[d];
                int nz = pos.getZ() + DZ[d];
                long np = BlockPosPacking.pack(nx, ny, nz);
                int v = observed.get(np);
                if (v != 0) {
                    boolean exposed = isExposed(l, nx, ny, nz);
                    observed.setBlock(np, HighlightIndex.value(HighlightIndex.categories(v), exposed,
                            HighlightIndex.idIndexOf(v)));
                }
            }
        }
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};

    // ---- per tick -----------------------------------------------------------------------------------------------

    /**
     * Runs the scans within budget. {@code fullScans} bounds the number of sections whose blocks are iterated; cheap
     * skips (all air / palette mismatch) have their own larger budget.
     */
    public void tick(Level l, int px, int py, int pz, long time, int fullScans, int nearScans) {
        ensureLevel(l);
        gameTime = time;
        if (trackChunkAge && time % 20 == 0) {
            chunkAge.settle(time); // once a second, as the tracker expects
        }
        if (mask != 0) {
            int full = 0;
            int skips = 0;
            while (!queue.isEmpty() && full < fullScans && skips < SKIP_BUDGET) {
                long key = queue.dequeueLong();
                if (!queued.remove(key)) {
                    continue; // unloaded meanwhile
                }
                int r = scanSection(l, key);
                if (r == SCANNED) {
                    full++;
                } else {
                    skips++;
                }
            }
        } else if (!queue.isEmpty()) {
            queue.clear();
            queued.clear();
        }
        if (nearCaves || nearFluids) {
            nearSweep(l, px, py, pz, nearScans);
        }
        if (time - lastRenderRebuild >= RENDER_REBUILD_TICKS || time < lastRenderRebuild) {
            lastRenderRebuild = time;
            observed.rebuildRenderListIfDirty();
            fluids.rebuildRenderListIfDirty();
            caves.rebuildRenderListIfDirty();
        }
    }

    /** True while sections are still waiting for their first scan. */
    public boolean busy() {
        return !queue.isEmpty();
    }

    private static final int SKIPPED = 0;
    private static final int SCANNED = 1;

    private LevelChunkSection section(Level l, int cx, int sy, int cz) {
        if (!loadedChunks.contains(BlockPosPacking.chunk(cx, cz))) {
            return null;
        }
        LevelChunk chunk = l.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return null;
        }
        int idx = chunk.getSectionIndexFromSectionY(sy);
        if (idx < 0 || idx >= chunk.getSectionsCount()) {
            return null;
        }
        return chunk.getSection(idx);
    }

    private int scanSection(Level l, long key) {
        int cx = BlockPosPacking.x(key);
        int sy = BlockPosPacking.y(key);
        int cz = BlockPosPacking.z(key);
        LevelChunkSection sec = section(l, cx, sy, cz);
        if (sec == null || sec.hasOnlyAir() || !sec.maybeHas(matcher)) {
            observed.removeSection(key);
            return SKIPPED;
        }
        matchPos.clear();
        matchVal.clear();
        int bx = cx << 4;
        int by = sy << 4;
        int bz = cz << 4;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    int cat = categoryOf(s) & mask;
                    if (cat == 0) {
                        continue;
                    }
                    boolean exposed = isExposed(l, sec, x, y, z, bx, by, bz);
                    matchPos.add(BlockPosPacking.pack(bx + x, by + y, bz + z));
                    matchVal.add(HighlightIndex.value(cat, exposed, observed.idIndex(WorldIds.block(s.getBlock()))));
                }
            }
        }
        observed.replaceSection(key, matchPos, matchVal);
        return SCANNED;
    }

    private static boolean open(BlockState s) {
        return s.isAir() || !s.getFluidState().isEmpty();
    }

    /** Exposure check reading the section directly for interior neighbours. */
    private boolean isExposed(Level l, LevelChunkSection sec, int x, int y, int z, int bx, int by, int bz) {
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d];
            int ny = y + DY[d];
            int nz = z + DZ[d];
            BlockState n;
            if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                n = sec.getBlockState(nx, ny, nz);
            } else {
                n = l.getBlockState(probe.set(bx + nx, by + ny, bz + nz));
            }
            if (open(n)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExposed(Level l, int x, int y, int z) {
        for (int d = 0; d < 6; d++) {
            if (open(l.getBlockState(probe.set(x + DX[d], y + DY[d], z + DZ[d])))) {
                return true;
            }
        }
        return false;
    }

    // ---- near sweep (caves, fluid pockets) ----------------------------------------------------------------------

    private void nearSweep(Level l, int px, int py, int pz, int budget) {
        int r = nearRadiusSections;
        int csx = px >> 4;
        int csy = py >> 4;
        int csz = pz >> 4;
        long center = HighlightIndex.sectionKey(csx, csy, csz);
        if (center != nearCenter) {
            nearCenter = center;
            caves.evictOutside(csx, csy, csz, r);
            evictFluids(csx, csy, csz, r);
        }
        int side = 2 * r + 1;
        int total = side * side * side;
        for (int n = 0; n < budget; n++) {
            int i = nearCursor++ % total;
            if (nearCursor >= total) {
                nearCursor = 0;
            }
            int dx = i % side - r;
            int dz = (i / side) % side - r;
            int dy = i / (side * side) - r;
            int sy = csy + dy;
            if (sy < l.getMinSection() || sy >= l.getMaxSection()) {
                continue;
            }
            scanNear(l, csx + dx, sy, csz + dz);
        }
    }

    private final LongArrayList evictScratch = new LongArrayList();

    private void evictFluids(int sx, int sy, int sz, int r) {
        // HighlightIndex has no section iteration; walk the render list (rebuilt each tick) to find stale sections.
        evictScratch.clear();
        for (int i = 0; i < fluids.renderCount(); i++) {
            long p = fluids.renderPos(i);
            long sk = HighlightIndex.sectionOf(p);
            if ((Math.abs(BlockPosPacking.x(sk) - sx) > r || Math.abs(BlockPosPacking.y(sk) - sy) > r
                    || Math.abs(BlockPosPacking.z(sk) - sz) > r) && !evictScratch.contains(sk)) {
                evictScratch.add(sk);
            }
        }
        for (int i = 0; i < evictScratch.size(); i++) {
            fluids.removeSection(evictScratch.getLong(i));
        }
    }

    private void scanNear(Level l, int cx, int sy, int cz) {
        long key = HighlightIndex.sectionKey(cx, sy, cz);
        LevelChunkSection sec = section(l, cx, sy, cz);
        if (sec == null || sec.hasOnlyAir()) {
            caves.setSection(key, 0L);
            fluids.removeSection(key);
            return;
        }
        LevelChunk chunk = l.getChunkSource().getChunk(cx, cz, false);
        int bx = cx << 4;
        int by = sy << 4;
        int bz = cz << 4;
        boolean fluidHere = nearFluids && sec.maybeHas(fluidMatcher);
        if (nearCaves) {
            java.util.Arrays.fill(cellOpen, 0);
        }
        matchPos.clear();
        matchVal.clear();
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    BlockState s = sec.getBlockState(x, y, z);
                    if (nearCaves && s.isAir()) {
                        cellOpen[CaveCells.bit(x >> 2, y >> 2, z >> 2)]++;
                    }
                    if (fluidHere && matchPos.size() < MAX_FLUIDS_PER_SECTION) {
                        FluidState fs = s.getFluidState();
                        if (!fs.isEmpty() && fs.isSource()
                                && by + y < chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 2
                                && touchesAir(l, sec, x, y, z, bx, by, bz)) {
                            int cat = fs.is(FluidTags.LAVA) ? BlockCategories.LAVA : BlockCategories.WATER;
                            matchPos.add(BlockPosPacking.pack(bx + x, by + y, bz + z));
                            matchVal.add(HighlightIndex.value(cat, true, 0));
                        }
                    }
                }
            }
        }
        if (nearFluids) {
            fluids.replaceSection(key, matchPos, matchVal);
        }
        if (nearCaves) {
            long m = 0L;
            for (int b = 0; b < 64; b++) {
                if (cellOpen[b] < CAVE_CELL_MIN_OPEN) {
                    continue;
                }
                int cellX = (b & 3) << 2;
                int cellZ = ((b >> 2) & 3) << 2;
                int cellTop = by + ((b >> 4) << 2) + 3;
                int surface = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, cellX + 2, cellZ + 2);
                if (cellTop < surface - 2) {
                    m |= 1L << b;
                }
            }
            caves.setSection(key, m);
        }
    }

    private boolean touchesAir(Level l, LevelChunkSection sec, int x, int y, int z, int bx, int by, int bz) {
        for (int d = 0; d < 6; d++) {
            int nx = x + DX[d];
            int ny = y + DY[d];
            int nz = z + DZ[d];
            BlockState n;
            if ((nx | ny | nz) >= 0 && nx < 16 && ny < 16 && nz < 16) {
                n = sec.getBlockState(nx, ny, nz);
            } else {
                n = l.getBlockState(probe.set(bx + nx, by + ny, bz + nz));
            }
            if (n.isAir()) {
                return true;
            }
        }
        return false;
    }

    /** Loaded chunk count (diagnostics). */
    public int loadedChunkCount() {
        return loadedChunks.size();
    }
}
