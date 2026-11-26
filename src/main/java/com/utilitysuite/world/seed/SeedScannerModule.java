package com.utilitysuite.world.seed;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongConsumer;
import java.util.function.Supplier;

/**
 * Passive client-side world seed scanner. When the active world seed is unknown (remote server without {@code /seed}),
 * collects vanilla worldgen clues from loaded chunks (exact-origin structures, End obsidian pillars, buried treasures,
 * and underground slimes) plus the client's hashed {@code BiomeManager} seed, and solves the 64-bit world seed on a
 * dedicated low-priority background thread.
 */
public final class SeedScannerModule extends WorldModule {

    public static final String ID = "seed_scanner";
    private static final int SLIME_SCAN_INTERVAL = 20;
    private static final int PILLAR_SCAN_INTERVAL = 20;
    private static final int MAX_BEDROCK_SAMPLES = 64;

    private static final ExecutorService SOLVE_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-SeedScan");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private final Supplier<GeneratorProfile> profileLookup;
    private final LongConsumer onSeedFound;

    private int chunksPerTick = 2;
    private boolean scanStructures = true;
    private boolean scanEndPillars = true;
    private boolean scanSlimes = true;
    private boolean autoSolve = true;
    private boolean announceProgress = true;

    private final LongArrayFIFOQueue pendingChunks = new LongArrayFIFOQueue();
    private final LongOpenHashSet queuedChunks = new LongOpenHashSet();
    private final LongOpenHashSet scannedChunks = new LongOpenHashSet();

    private final List<SeedSolver.StructureClue> structures = new ArrayList<>();
    private final Set<SeedSolver.StructureClue> seenStructures = new HashSet<>();
    private final List<SeedSolver.ChunkClue> buriedTreasures = new ArrayList<>();
    private final LongOpenHashSet seenTreasures = new LongOpenHashSet();
    private final List<SeedSolver.ChunkClue> slimeChunks = new ArrayList<>();
    private final LongOpenHashSet seenSlimes = new LongOpenHashSet();
    private final List<BedrockPatternVerifier.Sample> bedrockSamples = new ArrayList<>();
    private final int[] pillarHeights = new int[EndPillars.COUNT];
    private int pillarSeed = -1;
    private long hashedSeed;
    private boolean hasHashedSeed;

    private boolean cluesDirty;
    private int solvedClueCount = -1;
    private boolean solveNowRequested;
    private boolean rescanRequested;
    private volatile boolean solving;
    private volatile long solveGeneration;
    private AtomicBoolean activeCancel;
    private volatile String status = "Idle";
    private volatile Long detectedSeed;

    private ClientLevel lastLevel;
    private int ticks;
    private ClientChunkClueScanner chunkScanner;
    private final ClientChunkClueScanner.ClueSink sink = new ClientChunkClueScanner.ClueSink() {
        @Override
        public void onStructure(String type, int chunkX, int chunkZ) {
            recordStructure(type, chunkX, chunkZ);
        }

        @Override
        public void onBuriedTreasure(int chunkX, int chunkZ) {
            recordBuriedTreasure(chunkX, chunkZ);
        }

        @Override
        public void onSlimeChunk(int chunkX, int chunkZ) {
            recordSlimeChunk(chunkX, chunkZ);
        }
    };
    private final ClientChunkClueScanner.BedrockSink bedrockSink = (layer, x, y, z, present) -> {
        if (bedrockSamples.size() < MAX_BEDROCK_SAMPLES) {
            bedrockSamples.add(new BedrockPatternVerifier.Sample(layer, x, y, z, present));
            if (bedrockSamples.size() == 16) {
                cluesDirty = true;
            }
        }
    };

    public SeedScannerModule(Supplier<GeneratorProfile> profileLookup, LongConsumer onSeedFound) {
        super(ID, "Seed Scanner", true);
        this.profileLookup = profileLookup == null ? GeneratorProfile::none : profileLookup;
        this.onSeedFound = onSeedFound == null ? s -> { } : onSeedFound;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("chunks_per_tick", "Chunks per tick", 2, 1, 8, "Scanning",
                        "Maximum loaded chunks inspected per tick when collecting worldgen clues.").asAdvanced(),
                SettingSpec.bool("scan_structures", "Scan structures", true, "Clues",
                        "Detects temples, swamp huts, igloos, ocean monuments, and buried treasure chests in loaded chunks."),
                SettingSpec.bool("scan_end_pillars", "Scan End pillars", true, "Clues",
                        "Reads the 10 End obsidian pillar heights when entering the End (cuts the search by 16 bits)."),
                SettingSpec.bool("scan_slimes", "Scan underground slimes", true, "Clues",
                        "Uses underground slimes below Y=36 (outside swamps) as extra 1-bit chunk constraints."),
                SettingSpec.bool("auto_solve", "Auto-solve seed", true, "Solver",
                        "Automatically launches the 48/64-bit seed solver on a background thread once enough clues are gathered."),
                SettingSpec.bool("announce_progress", "Notices", true, "Display",
                        "Posts status notices when the world seed is detected."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        chunksPerTick = integer(p, "chunks_per_tick");
        scanStructures = bool(p, "scan_structures");
        scanEndPillars = bool(p, "scan_end_pillars");
        scanSlimes = bool(p, "scan_slimes");
        autoSolve = bool(p, "auto_solve");
        announceProgress = bool(p, "announce_progress");
    }

    @Override
    protected void onDisable() {
        cancelActiveSolve();
        pendingChunks.clear();
        queuedChunks.clear();
        status = "Off";
    }

    @Override
    protected void onEnable() {
        rescanRequested = true;
        status = "Ready";
    }

    @Override
    public void onWorldUnload() {
        resetClues();
        lastLevel = null;
        detectedSeed = null;
        hasHashedSeed = false;
        hashedSeed = 0L;
    }

    public void onChunkLoaded(ClientLevel level, ChunkAccess chunk) {
        if (!isEnabled() || !scanStructures || !(chunk instanceof LevelChunk)) {
            return;
        }
        GeneratorProfile prof = profileLookup.get();
        if (prof.seedKnown()) {
            return;
        }
        long key = BlockPosPacking.chunk(chunk.getPos().x(), chunk.getPos().z());
        if (!scannedChunks.contains(key) && queuedChunks.add(key)) {
            pendingChunks.enqueue(key);
        }
    }

    @Override
    public void onTick() {
        if (!isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        GeneratorProfile prof = profileLookup.get();
        if (mc.getSingleplayerServer() != null || prof.seedKnown()) {
            if (!pendingChunks.isEmpty()) {
                pendingChunks.clear();
                queuedChunks.clear();
            }
            if (detectedSeed != null) {
                status = "Seed detected: " + detectedSeed;
            } else if (prof.seedKnown()) {
                status = "Seed known (" + prof.source().name().toLowerCase(java.util.Locale.ROOT) + ")";
            } else {
                status = "Singleplayer";
            }
            return;
        }

        if (level != lastLevel) {
            lastLevel = level;
            pendingChunks.clear();
            queuedChunks.clear();
            scannedChunks.clear();
            rescanRequested = true;
        }

        ClientChunkClueScanner scanner = scanner();
        if (!hasHashedSeed) {
            hashedSeed = scanner.readHashedSeed(level);
            hasHashedSeed = true;
        }
        if (rescanRequested) {
            rescanRequested = false;
            enqueueLoadedAround(mc, level, player.getBlockX() >> 4, player.getBlockZ() >> 4);
        }

        ticks++;
        if (scanStructures) {
            int budget = chunksPerTick;
            while (budget > 0 && !pendingChunks.isEmpty()) {
                long key = pendingChunks.dequeueLong();
                queuedChunks.remove(key);
                if (!scannedChunks.add(key)) {
                    continue;
                }
                int cx = BlockPosPacking.chunkX(key);
                int cz = BlockPosPacking.chunkZ(key);
                LevelChunk lc = level.getChunkSource().getChunk(cx, cz, false);
                if (lc != null && !lc.isEmpty()) {
                    scanner.scanChunk(level, lc, sink);
                    if (bedrockSamples.size() < MAX_BEDROCK_SAMPLES) {
                        scanner.scanBedrock(level, lc, bedrockSink);
                    }
                    budget--;
                }
            }
        }
        if (scanEndPillars && pillarSeed < 0 && ticks % PILLAR_SCAN_INTERVAL == 0) {
            if (scanner.scanEndPillars(level, pillarHeights)) {
                int recovered = EndPillars.recoverPillarSeed(pillarHeights);
                if (recovered >= 0) {
                    pillarSeed = recovered;
                    cluesDirty = true;
                }
            }
        }
        if (scanSlimes && ticks % SLIME_SCAN_INTERVAL == 0) {
            scanner.scanSlimes(level, player, sink);
        }

        if ((autoSolve && cluesDirty && totalClues() != solvedClueCount) || solveNowRequested) {
            solveNowRequested = false;
            maybeLaunchSolve();
        } else if (!solving && cluesDirty) {
            updateIdleStatus();
            cluesDirty = false;
        }
    }

    /** Queues currently loaded chunks near the player for clue scanning. Safe from UI buttons. */
    public void rescanLoaded() {
        scannedChunks.clear();
        rescanRequested = true;
    }

    /** Clears collected clues and cancels any running solve. */
    public void resetClues() {
        cancelActiveSolve();
        pendingChunks.clear();
        queuedChunks.clear();
        scannedChunks.clear();
        structures.clear();
        seenStructures.clear();
        buriedTreasures.clear();
        seenTreasures.clear();
        slimeChunks.clear();
        seenSlimes.clear();
        bedrockSamples.clear();
        Arrays.fill(pillarHeights, 0);
        pillarSeed = -1;
        cluesDirty = false;
        solvedClueCount = -1;
        detectedSeed = null;
        status = isEnabled() ? "Cleared clues" : "Off";
    }

    /** Records an exact structure start chunk (e.g. from worldgen/manual input) for tests or manual assistance. */
    public void addManualStructure(String type, int chunkX, int chunkZ) {
        if (type == null || !StructureSpread.PLACEMENTS.containsKey(type)) {
            return;
        }
        recordStructure(type, chunkX, chunkZ);
    }

    public void solveNow() {
        solveNowRequested = true;
    }

    public boolean isSolving() {
        return solving;
    }

    public int clueCount() {
        return totalClues();
    }

    public int pillarSeed() {
        return pillarSeed;
    }

    public String statusLine() {
        return status;
    }

    public String clueSummary() {
        String pillars = pillarSeed >= 0 ? "pillars=ok" : "pillars=" + observedPillarCount() + "/10";
        String bedrock = bedrockSamples.isEmpty() ? "" : ", " + bedrockSamples.size() + " bedrock";
        return structures.size() + " struct, " + buriedTreasures.size() + " treasure, "
                + slimeChunks.size() + " slime, " + pillars + bedrock;
    }

    /** Handles {@code .seed} dot-commands ({@code status | add <type> <cx> <cz> | rescan | reset}). */
    public String handleDotCommand(String[] args) {
        if (args == null || args.length == 0 || "status".equalsIgnoreCase(args[0])) {
            Long seed = detectedSeed;
            if (seed != null) {
                return "Seed: " + seed + " | " + clueSummary();
            }
            return "Status: " + status + " | Clues: " + clueSummary();
        }
        String sub = args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "add" -> {
                if (args.length < 4) {
                    return "Usage: .seed add <type> <cx> <cz>";
                }
                String type = args[1].toLowerCase(java.util.Locale.ROOT);
                if (!StructureSpread.PLACEMENTS.containsKey(type)) {
                    return "Unknown structure type '" + type + "'.";
                }
                int cx;
                int cz;
                try {
                    cx = Integer.parseInt(args[2]);
                    cz = Integer.parseInt(args[3]);
                } catch (NumberFormatException e) {
                    return "Usage: .seed add <type> <cx> <cz>";
                }
                addManualStructure(type, cx, cz);
                return "Added " + type + " at (" + cx + ", " + cz + "). Clues: " + clueSummary();
            }
            case "rescan" -> {
                rescanLoaded();
                return "Rescanning loaded chunks...";
            }
            case "reset" -> {
                resetClues();
                return "Seed scanner clues cleared.";
            }
            case "solve" -> {
                solveNow();
                return "Solve requested (" + clueSummary() + ").";
            }
            default -> {
                return "Usage: .seed status | add <type> <cx> <cz> | rescan | reset";
            }
        }
    }

    private ClientChunkClueScanner scanner() {
        if (chunkScanner == null) {
            chunkScanner = new ClientChunkClueScanner();
        }
        return chunkScanner;
    }

    private void enqueueLoadedAround(Minecraft mc, ClientLevel level, int pcx, int pcz) {
        int rd = Math.min(12, Math.max(2, mc.options.renderDistance().get()));
        for (int dx = -rd; dx <= rd; dx++) {
            for (int dz = -rd; dz <= rd; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                long key = BlockPosPacking.chunk(cx, cz);
                if (scannedChunks.contains(key) || queuedChunks.contains(key)) {
                    continue;
                }
                LevelChunk lc = level.getChunkSource().getChunk(cx, cz, false);
                if (lc != null && !lc.isEmpty() && queuedChunks.add(key)) {
                    pendingChunks.enqueue(key);
                }
            }
        }
    }

    private void recordStructure(String type, int chunkX, int chunkZ) {
        SeedSolver.StructureClue clue = new SeedSolver.StructureClue(type, chunkX, chunkZ);
        if (seenStructures.add(clue)) {
            structures.add(clue);
            cluesDirty = true;
        }
    }

    private void recordBuriedTreasure(int chunkX, int chunkZ) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        if (seenTreasures.add(k)) {
            buriedTreasures.add(new SeedSolver.ChunkClue(chunkX, chunkZ));
            cluesDirty = true;
        }
    }

    private void recordSlimeChunk(int chunkX, int chunkZ) {
        long k = BlockPosPacking.chunk(chunkX, chunkZ);
        if (seenSlimes.add(k)) {
            slimeChunks.add(new SeedSolver.ChunkClue(chunkX, chunkZ));
            cluesDirty = true;
        }
    }

    private int totalClues() {
        return structures.size() + buriedTreasures.size() + slimeChunks.size() + (pillarSeed >= 0 ? 1 : 0);
    }

    private int observedPillarCount() {
        int count = 0;
        for (int h : pillarHeights) {
            if (h > 0) {
                count++;
            }
        }
        return count;
    }

    private void updateIdleStatus() {
        SeedSolver.Clues clues = snapshotClues();
        SeedSolver.Readiness r = SeedSolver.assess(clues);
        status = totalClues() == 0 ? "Scanning loaded chunks"
                : clueSummary() + " (" + r.reason() + ")";
    }

    private SeedSolver.Clues snapshotClues() {
        return new SeedSolver.Clues(hashedSeed, hasHashedSeed, pillarSeed,
                List.copyOf(structures), List.copyOf(buriedTreasures), List.copyOf(slimeChunks),
                List.copyOf(bedrockSamples));
    }

    private void maybeLaunchSolve() {
        SeedSolver.Clues clues = snapshotClues();
        SeedSolver.Readiness r = SeedSolver.assess(clues);
        cluesDirty = false;
        if (!r.ready()) {
            status = totalClues() == 0 ? "Scanning loaded chunks" : clueSummary() + " (" + r.reason() + ")";
            return;
        }
        cancelActiveSolve();
        final long gen = ++solveGeneration;
        final AtomicBoolean cancel = new AtomicBoolean(false);
        activeCancel = cancel;
        solvedClueCount = totalClues();
        solving = true;
        status = "Solving (" + r.reason() + ")...";
        SOLVE_EXECUTOR.execute(() -> {
            SeedSolver.Result result = SeedSolver.solve(clues, cancel::get);
            Minecraft.getInstance().execute(() -> onSolveDone(gen, cancel, result));
        });
    }

    private void onSolveDone(long gen, AtomicBoolean cancel, SeedSolver.Result result) {
        if (gen != solveGeneration || cancel.get()) {
            return;
        }
        solving = false;
        if (result.status() == SeedSolver.Status.FOUND_WORLD_SEED) {
            detectedSeed = result.worldSeed();
            status = "Seed detected: " + result.worldSeed();
            onSeedFound.accept(result.worldSeed());
            String msg = "Seed detected: " + result.worldSeed() + " (" + result.detail() + ")";
            var events = Services.eventsOrNull();
            if (events != null) {
                events.post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID, msg));
            }
            if (announceProgress && Minecraft.getInstance().player != null && !cleanScreen()) {
                Services.visuals().notices().post(NoticeKind.INFO, msg, null);
            }
        } else if (result.status() == SeedSolver.Status.FOUND_STRUCTURE_SEED) {
            status = "48-bit seed " + result.structureSeed() + " (need BiomeManager hash)";
        } else {
            status = clueSummary() + " (" + result.detail() + ")";
        }
    }

    private void cancelActiveSolve() {
        solveGeneration++;
        AtomicBoolean c = activeCancel;
        if (c != null) {
            c.set(true);
            activeCancel = null;
        }
        solving = false;
    }
}
