package com.utilitysuite.world.changes;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.StorageEntry;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldFiles;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.List;

/**
 * "What changed since my last visit": takes compact chunk snapshots (per-section palette hashes everywhere in range,
 * per-block hashes for the area around the player and near waypoints), saved per world and dimension, and on request
 * re-captures the same chunks and diffs them off-thread. The overlay shows new blocks (cyan), removed blocks (amber)
 * and containers StorageMemory knew with items that are now empty (red). Capture is budgeted per tick; saving is
 * gzip on the world executor, debounced.
 */
public final class ChangeTrackerModule extends WorldModule {

    public static final String ID = "change_tracker";

    private static final int CAPTURE_CHUNKS_PER_TICK = 1;
    private static final int SAVE_CHECK_TICKS = 200;
    private static final int COLOR_ADDED = 0xC040FFFF;
    private static final int COLOR_REMOVED = 0xC0FFB040;
    private static final int COLOR_EMPTIED = 0xFFFF3030;

    private int markRadius;
    private int waypointRadius;
    private int coarseRadius;
    private boolean showDiff;
    private int maxMarks;

    private KeyMapping snapshotKey;
    private KeyMapping diffKey;

    private final SnapshotStore store = new SnapshotStore();
    private String pendingProfile;
    private String profileId;
    private String dimension;

    /** Chunks waiting to be captured: packed chunk key; detail flag kept in a parallel set. */
    private final LongArrayFIFOQueue queue = new LongArrayFIFOQueue();
    private final LongOpenHashSet queuedDetailed = new LongOpenHashSet();
    private final LongOpenHashSet queued = new LongOpenHashSet();
    private boolean capturingForDiff;
    private final Long2ObjectOpenHashMap<ChunkSnapshot> fresh = new Long2ObjectOpenHashMap<>();
    private final IntOpenHashSet palette = new IntOpenHashSet();
    private final int[] paletteIds = new int[4096];

    private volatile SnapshotDiff.Result diff;
    private volatile boolean diffing;
    private int ticks;
    private String status = "";

    public ChangeTrackerModule() {
        super(ID, "Change Tracker", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("mark_radius", "Detailed radius (chunks)", 2, 0, 8, "Snapshot",
                        "Chunks around you stored block by block when you take a snapshot."),
                SettingSpec.integer("waypoint_radius", "Waypoint radius (chunks)", 1, 0, 4, "Snapshot",
                        "Loaded chunks this close to a waypoint are also stored block by block."),
                SettingSpec.integer("coarse_radius", "Coarse radius (chunks)", 6, 0, 16, "Snapshot",
                        "Further chunks store only one fingerprint per 16-block section (changed or not)."),
                SettingSpec.bool("show_diff", "Show changes", false, "Display",
                        "Overlay: new blocks cyan, removed blocks amber, emptied containers red."),
                SettingSpec.integer("max_marks", "Max marks drawn", 4000, 256, 20000, "Advanced",
                        "Caps the number of changed blocks drawn per frame."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        markRadius = integer(p, "mark_radius");
        waypointRadius = integer(p, "waypoint_radius");
        coarseRadius = integer(p, "coarse_radius");
        boolean wasShowing = showDiff;
        showDiff = bool(p, "show_diff");
        if (showDiff && !wasShowing) {
            diffRequested = true;
        } else if (!showDiff) {
            diffRequested = false;
        }
        maxMarks = integer(p, "max_marks");
        if (!"global".equals(p.profileId())) {
            pendingProfile = p.profileId(); // loaded on the next tick in a world (keeps settings free of file I/O)
        }
    }

    private boolean diffRequested;

    public void setKeys(KeyMapping snapshot, KeyMapping toggleDiff) {
        this.snapshotKey = snapshot;
        this.diffKey = toggleDiff;
    }

    @Override
    protected void onDisable() {
        resetQueue();
        diff = null;
    }

    @Override
    public void onWorldUnload() {
        save(false);
        resetQueue();
        diff = null;
        store.clear();
        store.consumeDirty();
        profileId = null;
        dimension = null;
    }

    private void resetQueue() {
        queue.clear();
        queued.clear();
        queuedDetailed.clear();
        fresh.clear();
        capturingForDiff = false;
    }

    // ---- tick ---------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        String dim = WorldIds.dimension(level);
        if (pendingProfile != null && !pendingProfile.equals(profileId) || !dim.equals(dimension)) {
            save(false);
            if (pendingProfile != null) {
                profileId = pendingProfile;
            }
            dimension = dim;
            resetQueue();
            diff = null;
            load();
        }
        while (snapshotKey != null && snapshotKey.consumeClick()) {
            queueSnapshot(player, dim);
        }
        while (diffKey != null && diffKey.consumeClick()) {
            showDiff = !showDiff;
            Services.modState().profile().set(ID, "show_diff", showDiff);
            Services.modState().markDirty();
            if (showDiff) {
                diffRequested = true;
            }
        }
        if (diffRequested && !capturingForDiff && queue.isEmpty()) {
            diffRequested = false;
            queueDiffCapture(level);
        }
        for (int i = 0; i < CAPTURE_CHUNKS_PER_TICK && !queue.isEmpty(); i++) {
            long key = queue.dequeueLong();
            queued.remove(key);
            ChunkSnapshot s = capture(level, BlockPosPacking.chunkX(key), BlockPosPacking.chunkZ(key),
                    queuedDetailed.remove(key), dim);
            if (s == null) {
                continue;
            }
            if (capturingForDiff) {
                fresh.put(key, s);
            } else {
                store.put(s);
            }
        }
        if (capturingForDiff && queue.isEmpty()) {
            capturingForDiff = false;
            submitDiff(dim);
        }
        if (++ticks % SAVE_CHECK_TICKS == 0) {
            save(true);
        }
    }

    private void enqueue(int cx, int cz, boolean detailed) {
        long key = BlockPosPacking.chunk(cx, cz);
        if (detailed) {
            queuedDetailed.add(key);
        }
        if (queued.add(key)) {
            queue.enqueue(key);
        }
    }

    private void queueSnapshot(LocalPlayer player, String dim) {
        if (capturingForDiff) {
            resetQueue();
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        int far = Math.max(coarseRadius, markRadius);
        for (int dx = -far; dx <= far; dx++) {
            for (int dz = -far; dz <= far; dz++) {
                boolean detailed = Math.abs(dx) <= markRadius && Math.abs(dz) <= markRadius;
                enqueue(pcx + dx, pcz + dz, detailed);
            }
        }
        IWaypointService wps = Services.waypointsOrNull();
        if (wps != null && waypointRadius > 0) {
            for (Waypoint w : wps.all()) {
                long p = wps.positionIn(w, dim);
                int wcx = BlockPosPacking.x(p) >> 4;
                int wcz = BlockPosPacking.z(p) >> 4;
                if (Math.abs(wcx - pcx) > 32 || Math.abs(wcz - pcz) > 32) {
                    continue; // not loaded anyway
                }
                for (int dx = -waypointRadius; dx <= waypointRadius; dx++) {
                    for (int dz = -waypointRadius; dz <= waypointRadius; dz++) {
                        enqueue(wcx + dx, wcz + dz, true);
                    }
                }
            }
        }
        status = "snapshot queued: " + queue.size() + " chunks";
    }

    private void queueDiffCapture(ClientLevel level) {
        resetQueue();
        capturingForDiff = true;
        List<ChunkSnapshot> all = storeSnapshotList();
        for (ChunkSnapshot s : all) {
            if (level.getChunkSource().getChunk(s.chunkX, s.chunkZ, false) != null) {
                enqueue(s.chunkX, s.chunkZ, s.detailed());
            }
        }
    }

    private List<ChunkSnapshot> storeSnapshotList() {
        return store.all();
    }

    /** Captures one loaded chunk (client thread; one chunk per tick budget). */
    private ChunkSnapshot capture(ClientLevel level, int cx, int cz, boolean detailed, String dim) {
        LevelChunk chunk = level.getChunkSource().getChunk(cx, cz, false);
        if (chunk == null) {
            return null;
        }
        LevelChunkSection[] sections = chunk.getSections();
        int n = sections.length;
        int[] ph = new int[n];
        byte[][] blocks = detailed ? new byte[n][] : null;
        for (int si = 0; si < n; si++) {
            LevelChunkSection sec = sections[si];
            if (sec == null || sec.hasOnlyAir()) {
                continue;
            }
            palette.clear();
            byte[] b = detailed ? new byte[ChunkSnapshot.SECTION_VOLUME] : null;
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        BlockState st = sec.getBlockState(x, y, z);
                        boolean air = st.isAir();
                        int id = Block.getId(st);
                        if (!air) {
                            palette.add(id);
                        }
                        if (b != null) {
                            b[ChunkSnapshot.index(x, y, z)] = ChunkSnapshot.blockHash(id, air);
                        }
                    }
                }
            }
            int k = 0;
            var it = palette.iterator();
            while (it.hasNext()) {
                paletteIds[k++] = it.nextInt();
            }
            ph[si] = ChunkSnapshot.paletteHash(paletteIds, k);
            if (blocks != null) {
                blocks[si] = b;
            }
        }
        return new ChunkSnapshot(cx, cz, chunk.getMinSection(), System.currentTimeMillis(), ph, blocks,
                nonEmptyContainers(dim, cx, cz));
    }

    private static long[] nonEmptyContainers(String dim, int cx, int cz) {
        IStorageIndex idx = Services.storageOrNull();
        if (idx == null) {
            return new long[0];
        }
        LongArrayList out = new LongArrayList();
        for (StorageEntry e : idx.entries()) {
            if (!dim.equals(e.dimension()) || e.contents().isEmpty() && e.nested().isEmpty()) {
                continue;
            }
            if (BlockPosPacking.x(e.packedPos()) >> 4 == cx && BlockPosPacking.z(e.packedPos()) >> 4 == cz) {
                out.add(e.packedPos());
            }
        }
        return out.toLongArray();
    }

    private void submitDiff(String dim) {
        if (diffing) {
            return;
        }
        // Containers StorageMemory still knows with items: anything else that held items at snapshot time is emptied.
        LongOpenHashSet stillFull = new LongOpenHashSet();
        IStorageIndex idx = Services.storageOrNull();
        if (idx != null) {
            for (StorageEntry e : idx.entries()) {
                if (dim.equals(e.dimension()) && (!e.contents().isEmpty() || !e.nested().isEmpty())) {
                    stillFull.add(e.packedPos());
                }
            }
        }
        Long2ObjectOpenHashMap<ChunkSnapshot> now = new Long2ObjectOpenHashMap<>(fresh);
        fresh.clear();
        diffing = true;
        WorldExecutor.get().execute(() -> {
            try {
                SnapshotDiff d = new SnapshotDiff();
                for (ChunkSnapshot cur : now.values()) {
                    d.compare(store.get(cur.chunkX, cur.chunkZ), cur, p -> !stillFull.contains(p));
                }
                diff = d.result();
            } finally {
                diffing = false;
            }
        });
    }

    /** Short status for the HUD/menu. */
    public String status() {
        SnapshotDiff.Result r = diff;
        if (r != null) {
            return r.added().length + " new, " + r.removed().length + " removed, " + r.emptied().length
                    + " emptied in " + r.comparedChunks() + " chunks";
        }
        return status;
    }

    // ---- persistence --------------------------------------------------------------------------------------------

    private java.nio.file.Path file() {
        return WorldFiles.file(profileId, ".changes." + WorldFiles.safe(dimension) + ".bin.gz");
    }

    private void load() {
        store.clear();
        store.consumeDirty();
        if (profileId == null || dimension == null) {
            return;
        }
        byte[] data = WorldFiles.read(file());
        if (data == null) {
            return;
        }
        try {
            store.fromBytes(data);
        } catch (java.io.IOException e) {
            UtilitySuiteMod.LOGGER.warn("Ignoring unreadable change snapshots for {}", profileId, e);
            store.clear();
            store.consumeDirty();
        }
    }

    private void save(boolean async) {
        if (profileId == null || dimension == null || !store.consumeDirty()) {
            return;
        }
        java.nio.file.Path f = file();
        Runnable write = () -> {
            try {
                WorldFiles.writeNow(f, store.toBytes());
            } catch (java.io.IOException e) {
                UtilitySuiteMod.LOGGER.warn("Could not save change snapshots", e);
            }
        };
        if (async) {
            WorldExecutor.get().execute(write); // gzip off the client thread
        } else {
            write.run();
        }
    }

    // ---- rendering ----------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        SnapshotDiff.Result r = diff;
        if (!showDiff || r == null || r.isEmpty()) {
            return; // nothing drawn when idle
        }
        int budget = maxMarks;
        budget = drawMarks(ctx, r.added(), COLOR_ADDED, budget, 0.1f);
        budget = drawMarks(ctx, r.removed(), COLOR_REMOVED, budget, 0.1f);
        drawMarks(ctx, r.emptied(), COLOR_EMPTIED, Math.max(budget, 64), -0.05f);
    }

    private static int drawMarks(RenderContext ctx, long[] marks, int argb, int budget, float inset) {
        for (int i = 0; i < marks.length && budget > 0; i++) {
            long p = marks[i];
            int x = BlockPosPacking.x(p);
            int y = BlockPosPacking.y(p);
            int z = BlockPosPacking.z(p);
            if (ctx.distSq(x, y, z) > 128.0 * 128.0) {
                continue;
            }
            float rx = ctx.rx(x);
            float ry = ctx.ry(y);
            float rz = ctx.rz(z);
            WorldRenderKit.box(ctx.lines, ctx.pose, rx + inset, ry + inset, rz + inset, rx + 1 - inset,
                    ry + 1 - inset, rz + 1 - inset, argb);
            budget--;
        }
        return budget;
    }
}
