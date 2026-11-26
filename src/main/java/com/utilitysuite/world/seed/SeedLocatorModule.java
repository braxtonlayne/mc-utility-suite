package com.utilitysuite.world.seed;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.spawn.SlimeAfkFinder;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Seed Locator: from the known world seed (single-player integrated server, else the Seed Workbench / seed scanner
 * profile) lists the nearest structure start <em>candidates</em> ({@link StructureLocator}) and the best slime-farm AFK
 * chunks ({@link SlimeAfkFinder}) in the World tab, with Add waypoint / Go buttons there. Draws nothing (the slime chunk
 * outlines live in Spawn Geometry). The tick only compares a few fields; a search runs on the world executor when the
 * player moved {@value #REFRESH_CHUNKS} chunks, the seed or dimension changed, or a setting changed, and publishes an
 * immutable {@link Snapshot}.
 */
public final class SeedLocatorModule extends WorldModule {

    public static final String ID = "seed_locator";
    static final int REFRESH_CHUNKS = 8;

    /** Published results; {@code version} increases with every publish so the menu can rebuild. */
    public record Snapshot(int version, boolean seedKnown, StructureLocator.Dim dim, int originX, int originZ,
                           List<StructureLocator.Candidate> structures, List<SlimeAfkFinder.Spot> slimeSpots) {
        static final Snapshot EMPTY = new Snapshot(0, false, StructureLocator.Dim.OVERWORLD, 0, 0, List.of(), List.of());
    }

    private final Supplier<GeneratorProfile> seedSource;

    private boolean structures;
    private boolean slimeAfk;
    private int radiusBlocks;
    private int perType;
    private int listSize;
    private int slimeSearch;
    private volatile Snapshot snapshot = Snapshot.EMPTY;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicInteger versionCounter = new AtomicInteger();

    // Last searched request (client thread).
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ;
    private long lastSeed;
    private boolean lastKnown;
    private StructureLocator.Dim lastDim;
    private int lastStamp = -1;

    // Request handed to the reusable job (written before submit, read on the executor).
    private volatile long reqSeed;
    private volatile int reqBlockX;
    private volatile int reqBlockZ;
    private volatile StructureLocator.Dim reqDim = StructureLocator.Dim.OVERWORLD;
    private volatile boolean reqStructures;
    private volatile boolean reqSlime;
    private volatile int reqRadius;
    private volatile int reqPerType;
    private volatile int reqList;
    private volatile int reqSlimeSearch;
    private final Runnable job = this::runSearch;

    public SeedLocatorModule(Supplier<GeneratorProfile> seedSource) {
        super(ID, "Seed Locator", true);
        this.seedSource = seedSource;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("structures", "Structure candidates", true, "Structures",
                        "Lists where random-spread structures can start near you (biomes unchecked: candidates)."),
                SettingSpec.integer("radius_blocks", "Search radius (blocks)", 2048, 256, 8192, "Structures",
                        "How far from you structure candidates are listed."),
                SettingSpec.integer("per_type", "Per structure type", 2, 1, 5, "Structures",
                        "Nearest candidates kept for each structure type."),
                SettingSpec.integer("list_size", "List length", 8, 3, 20, "Structures",
                        "Rows shown in the World tab."),
                SettingSpec.bool("slime_afk", "Slime AFK spot", true, "Slime farm",
                        "Finds the chunk with the most slime chunks within the 128-block spawn sphere."),
                SettingSpec.integer("slime_search", "AFK search (chunks)", 24, 4, 64, "Slime farm",
                        "How far from you AFK spots are searched."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        structures = bool(p, "structures");
        radiusBlocks = integer(p, "radius_blocks");
        perType = integer(p, "per_type");
        listSize = integer(p, "list_size");
        slimeAfk = bool(p, "slime_afk");
        slimeSearch = integer(p, "slime_search");
    }

    @Override
    protected void onEnable() {
        lastChunkX = Integer.MIN_VALUE;
    }

    @Override
    public void onWorldUnload() {
        lastChunkX = Integer.MIN_VALUE;
        snapshot = Snapshot.EMPTY;
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public boolean isSearching() {
        return running.get();
    }

    @Override
    public void onTick() {
        if (!structures && !slimeAfk) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || running.get()) {
            return;
        }
        StructureLocator.Dim dim = level.dimension() == Level.NETHER ? StructureLocator.Dim.NETHER
                : level.dimension() == Level.END ? StructureLocator.Dim.END : StructureLocator.Dim.OVERWORLD;
        boolean known;
        long seed;
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            known = true;
            seed = sp.getWorldGenSettings().options().seed();
        } else {
            GeneratorProfile prof = seedSource == null ? null : seedSource.get();
            known = prof != null && prof.seedKnown();
            seed = known ? prof.seed() : 0L;
        }
        int stamp = settingsStamp();
        int bx = player.getBlockX();
        int bz = player.getBlockZ();
        int cx = bx >> 4;
        int cz = bz >> 4;
        boolean moved = lastChunkX == Integer.MIN_VALUE || Math.abs(cx - lastChunkX) >= REFRESH_CHUNKS
                || Math.abs(cz - lastChunkZ) >= REFRESH_CHUNKS;
        if (!moved && known == lastKnown && seed == lastSeed && dim == lastDim && stamp == lastStamp) {
            return;
        }
        lastChunkX = cx;
        lastChunkZ = cz;
        lastKnown = known;
        lastSeed = seed;
        lastDim = dim;
        lastStamp = stamp;
        if (!known) {
            if (snapshot.seedKnown() || snapshot.version() == 0) {
                snapshot = new Snapshot(versionCounter.incrementAndGet(), false, dim, bx, bz, List.of(), List.of());
            }
            return;
        }
        reqSeed = seed;
        reqBlockX = bx;
        reqBlockZ = bz;
        reqDim = dim;
        reqStructures = structures;
        reqSlime = slimeAfk;
        reqRadius = radiusBlocks;
        reqPerType = perType;
        reqList = listSize;
        reqSlimeSearch = slimeSearch;
        running.set(true);
        WorldExecutor.get().execute(job);
    }

    /** Cheap fingerprint of the search settings (a change re-runs the search). */
    private int settingsStamp() {
        int h = structures ? 1 : 0;
        h = h * 31 + (slimeAfk ? 1 : 0);
        h = h * 31 + radiusBlocks;
        h = h * 31 + perType;
        h = h * 31 + listSize;
        return h * 31 + slimeSearch;
    }

    /** Executor side: pure maths on the request fields, then one volatile publish. */
    private void runSearch() {
        try {
            snapshot = search(versionCounter.incrementAndGet(), reqSeed, reqDim, reqBlockX, reqBlockZ, reqStructures, reqRadius,
                    reqPerType, reqList, reqSlime, reqSlimeSearch);
        } finally {
            running.set(false);
        }
    }

    /** Engine-free search (also used by tests). */
    static Snapshot search(int version, long seed, StructureLocator.Dim dim, int blockX, int blockZ,
                           boolean structures, int radius, int perType, int listSize, boolean slime, int slimeSearch) {
        List<StructureLocator.Candidate> found = structures
                ? StructureLocator.nearest(seed, dim, blockX, blockZ, radius, perType, listSize) : List.of();
        List<SlimeAfkFinder.Spot> spots = slime && dim == StructureLocator.Dim.OVERWORLD
                ? SlimeAfkFinder.best(seed, blockX >> 4, blockZ >> 4, slimeSearch, 3, 8) : List.of();
        return new Snapshot(version, true, dim, blockX, blockZ, found, spots);
    }
}
