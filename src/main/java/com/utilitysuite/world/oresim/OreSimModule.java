package com.utilitysuite.world.oresim;

import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.knowledge.KnowledgeLedger;
import com.utilitysuite.world.knowledge.PredictionSource;
import com.utilitysuite.world.knowledge.WorldKnowledgeService;
import com.utilitysuite.world.overlays.BlockCategories;
import com.utilitysuite.world.overlays.HighlightIndex;
import com.utilitysuite.world.overlays.WorldScanner;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import com.utilitysuite.world.seed.GeneratorProfile;
import com.utilitysuite.world.seed.SeedText;
import com.utilitysuite.world.seed.SeedWorkbench;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.neoforged.neoforge.client.event.ClientChatReceivedEvent;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Seed Workbench and OreSim: the generator profile of the current world (seed from the integrated server, manual entry
 * or a {@code /seed} reply in chat), background ore simulation around the player, and the ghost overlay of the
 * predictions.
 * <p>
 * Predictions are never presented as confirmed: they are drawn as dashed outlines, faded by confidence, labelled
 * "predicted", and hidden where the knowledge ledger recorded a verified miss (or its vein was suppressed). The Seed
 * Workbench compares the seed against ores actually seen and reports MATCH / AMBIGUOUS / NO_MATCH with evidence counts;
 * a NO_MATCH flags every prediction as uncertain.
 */
public final class OreSimModule extends WorldModule {

    public static final String ID = "seed_oresim";

    public enum SeedMode { AUTO, MANUAL }

    private static final int REQUESTS_PER_TICK = 2;
    private static final int WORKBENCH_INTERVAL = 20 * 60;
    private static final int LABELS = 6;

    private final OreSimService service = new OreSimService(WorldExecutor.get());
    private final OreSimPredictions predictions = new OreSimPredictions(service);
    private final WorldKnowledgeService knowledge;
    private final WorldScanner scanner;

    private SeedMode seedMode;
    private String manualSeed;
    private String chatSeed;
    private boolean listenChat;
    private Set<String> resources = Set.of();
    private int radiusChunks;
    private boolean showGhosts;
    private boolean ghostLabels;
    private int maxGhosts;

    private GeneratorProfile profile = GeneratorProfile.none();
    private boolean workbenchUncertain;
    private volatile SeedWorkbench.Report lastReport;
    private SeedWorkbench.Verdict announcedVerdict;
    private String configKey = "";
    private int[] spiralX = new int[0];
    private int[] spiralZ = new int[0];
    private int spiralCursor;
    private long lastCenter = Long.MIN_VALUE;
    private int ticks;
    private boolean ghostsDirty = true;
    private int lastMissCount = -1;
    private String profileId;

    // Ghost render list (client thread).
    private float[] gx = new float[0];
    private float[] gy = new float[0];
    private float[] gz = new float[0];
    private int[] gColor = new int[0];
    private byte[] gPercent = new byte[0];
    private int ghostCount;
    private final PredictionSource.Visitor ghostCollector = this::collectGhost;
    private final Object2IntOpenHashMap<String> colorCache = new Object2IntOpenHashMap<>();
    private String ghostDimension;
    private final int[] labelIndex = new int[LABELS];
    private final double[] labelDist = new double[LABELS];

    private ClientLevel configuredLevel;

    public OreSimModule(WorldKnowledgeService knowledge, WorldScanner scanner) {
        super(ID, "Seed Workbench & OreSim", false);
        this.knowledge = knowledge;
        this.scanner = scanner;
        colorCache.defaultReturnValue(-1);
    }

    public PredictionSource predictions() {
        return predictions;
    }

    public GeneratorProfile profile() {
        return profile;
    }

    /** Latest workbench result (null until one ran). */
    public SeedWorkbench.Report lastReport() {
        return lastReport;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("seed_mode", "Seed source", SeedMode.AUTO, "Seed",
                        "AUTO: the integrated server's seed in single-player, otherwise the last /seed reply seen in "
                                + "chat. MANUAL: the seed typed below."),
                SettingSpec.text("manual_seed", "Seed", "", "Seed",
                        "Number or text (text seeds are hashed the way the world creation screen does)."),
                SettingSpec.bool("listen_chat", "Read /seed replies", true, "Seed",
                        "Picks up the seed from a \"Seed: [...]\" message in chat (your own /seed command)."),
                SettingSpec.text("resources", "Simulated ores", "diamond,ancient_debris,emerald,gold", "OreSim",
                        "Comma list of: " + String.join(", ", VanillaOreTables.resources())),
                SettingSpec.integer("radius_chunks", "Radius (chunks)", 6, 1, 12, "OreSim",
                        "Chunks around you that are simulated in the background."),
                SettingSpec.bool("show_ghosts", "Show predictions", true, "Display",
                        "Dashed ghost outlines; follows the World Knowledge overlay data setting."),
                SettingSpec.bool("ghost_labels", "Label nearest predictions", true, "Display",
                        "Labels the nearest few ghosts with \"predicted\" and their confidence."),
                SettingSpec.integer("max_ghosts", "Max ghosts drawn", 3000, 100, 20000, "Display",
                        "Upper bound on outlines per frame.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        seedMode = choice(p, "seed_mode", SeedMode.class);
        manualSeed = text(p, "manual_seed");
        listenChat = bool(p, "listen_chat");
        Set<String> res = new LinkedHashSet<>();
        for (String s : text(p, "resources").split(",")) {
            String t = s.trim().toLowerCase(java.util.Locale.ROOT);
            if (!t.isEmpty()) {
                res.add(t);
            }
        }
        resources = Set.copyOf(res);
        int r = integer(p, "radius_chunks");
        if (r != radiusChunks) {
            radiusChunks = r;
            buildSpiral(r);
        }
        showGhosts = bool(p, "show_ghosts");
        ghostLabels = bool(p, "ghost_labels");
        maxGhosts = integer(p, "max_ghosts");
        if (!p.profileId().equals(profileId)) {
            profileId = p.profileId();
            chatSeed = p.getString(ID, "chat_seed", "");
            workbenchUncertain = false;
            lastReport = null;
            announcedVerdict = null;
        }
        configKey = ""; // force reconfiguration on the next tick
        ghostsDirty = true;
    }

    private void buildSpiral(int r) {
        int side = 2 * r + 1;
        Integer[] order = new Integer[side * side];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(order, (a, b) -> {
            int ax = a % side - r;
            int az = a / side - r;
            int bx = b % side - r;
            int bz = b / side - r;
            return Integer.compare(ax * ax + az * az, bx * bx + bz * bz);
        });
        spiralX = new int[order.length];
        spiralZ = new int[order.length];
        for (int i = 0; i < order.length; i++) {
            spiralX[i] = order[i] % side - r;
            spiralZ[i] = order[i] / side - r;
        }
        spiralCursor = 0;
    }

    @Override
    protected void onEnable() {
        configKey = "";
    }

    @Override
    protected void onDisable() {
        service.configure(null);
        ServerWorldgenOracles.clearCaches();
        knowledge.setPredictionSource(PredictionSource.NONE);
        configKey = "";
        ghostCount = 0;
    }

    @Override
    public void onStopAll() {
        // Nothing automated here; predictions keep running.
    }

    @Override
    public void onWorldUnload() {
        service.configure(null);
        ServerWorldgenOracles.clearCaches();
        configKey = "";
        ghostCount = 0;
        profile = GeneratorProfile.none();
        lastCenter = Long.MIN_VALUE;
    }

    // ---- seed ---------------------------------------------------------------------------------------------------

    /** Chat listener (game bus): picks up the reply of the user's own {@code /seed} command. */
    public void onChat(ClientChatReceivedEvent.System event) {
        if (!listenChat || event.isOverlay()) {
            return;
        }
        OptionalLong seed = SeedText.parseSeedMessage(event.getMessage().getString());
        if (seed.isEmpty()) {
            return;
        }
        chatSeed = Long.toString(seed.getAsLong());
        ProfileConfig p = Services.modState().profile();
        p.set(ID, "chat_seed", chatSeed);
        Services.modState().markDirty();
        configKey = "";
    }

    private GeneratorProfile resolveProfile(Minecraft mc, ClientLevel level) {
        if (seedMode == SeedMode.MANUAL) {
            OptionalLong s = SeedText.parseManualSeed(manualSeed);
            return s.isPresent() ? new GeneratorProfile(s.getAsLong(), true, GeneratorProfile.CURRENT_VERSION,
                    GeneratorProfile.Source.MANUAL, workbenchUncertain) : GeneratorProfile.none();
        }
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            long seed = sp.getWorldData().worldGenOptions().seed();
            boolean custom = workbenchUncertain || !vanillaGenerator(sp, level.dimension());
            return new GeneratorProfile(seed, true, GeneratorProfile.CURRENT_VERSION,
                    GeneratorProfile.Source.INTEGRATED_SERVER, custom);
        }
        OptionalLong s = SeedText.parseManualSeed(chatSeed == null ? "" : chatSeed);
        return s.isPresent() ? new GeneratorProfile(s.getAsLong(), true, GeneratorProfile.CURRENT_VERSION,
                GeneratorProfile.Source.CHAT, workbenchUncertain) : GeneratorProfile.none();
    }

    /** Vanilla noise generator with the vanilla settings for this dimension (else predictions are uncertain). */
    private static boolean vanillaGenerator(IntegratedServer sp, ResourceKey<Level> dim) {
        ServerLevel sl = sp.getLevel(dim);
        if (sl == null) {
            return false;
        }
        ChunkGenerator gen = sl.getChunkSource().getGenerator();
        if (!(gen instanceof NoiseBasedChunkGenerator noise)) {
            return false;
        }
        ResourceKey<NoiseGeneratorSettings> want = dim == Level.NETHER ? NoiseGeneratorSettings.NETHER
                : NoiseGeneratorSettings.OVERWORLD;
        return noise.generatorSettings().is(want);
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
        ticks++;
        String dim = WorldIds.dimension(level);
        if (configKey.isEmpty() || level != configuredLevel) {
            configuredLevel = level; // dimension change / respawn: re-resolve (not polled)
            reconfigure(mc, level, dim);
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        long center = BlockPosPacking.chunk(pcx, pcz);
        if (center != lastCenter) {
            lastCenter = center;
            spiralCursor = 0;
            service.evictOutside(pcx, pcz, radiusChunks + 2);
            ghostsDirty = true;
        }
        if (service.isConfigured()) {
            requestChunks(mc, level, pcx, pcz);
            if (service.drainCompleted(32) > 0) {
                ghostsDirty = true;
            }
        }
        int misses = knowledge.ledger().missCount(dim);
        if (misses != lastMissCount) {
            lastMissCount = misses;
            ghostsDirty = true;
        }
        if (ghostsDirty && ticks % 5 == 0) {
            ghostsDirty = false;
            rebuildGhosts(dim, player);
        }
        if (ticks % WORKBENCH_INTERVAL == 0) {
            runWorkbench(dim);
        }
    }

    private void reconfigure(Minecraft mc, ClientLevel level, String dim) {
        profile = resolveProfile(mc, level);
        // Large (noise-stage) veins need the generator's noises, so only the integrated server's oracle provides them.
        OreSpec[] specs = mc.getSingleplayerServer() != null
                ? VanillaOreTables.selectWithLargeVeins(dim, resources)
                : VanillaOreTables.select(dim, resources);
        String key = profile.seedKnown() + "|" + profile.seed() + "|" + dim + "|" + resources + "|"
                + profile.uncertain();
        if (key.equals(configKey)) {
            return;
        }
        configKey = key;
        if (!profile.seedKnown() || specs.length == 0) {
            service.configure(null);
            knowledge.setPredictionSource(PredictionSource.NONE);
        } else {
            service.configure(new OreSimService.Config(profile.seed(), dim, specs, VanillaOreTables.minY(dim),
                    VanillaOreTables.height(dim), profile.uncertain()));
            knowledge.setPredictionSource(predictions);
        }
        spiralCursor = 0;
        ghostsDirty = true;
    }

    private void requestChunks(Minecraft mc, ClientLevel level, int pcx, int pcz) {
        IntegratedServer sp = mc.getSingleplayerServer();
        ServerLevel serverLevel = sp == null ? null : sp.getLevel(level.dimension());
        String dim = WorldIds.dimension(level);
        KnowledgeLedger ledger = knowledge.ledger();
        int sent = 0;
        while (sent < REQUESTS_PER_TICK && spiralCursor < spiralX.length) {
            int cx = pcx + spiralX[spiralCursor];
            int cz = pcz + spiralZ[spiralCursor];
            spiralCursor++;
            if (service.isKnownOrPending(cx, cz)) {
                continue;
            }
            TerrainOracle terrain;
            BiomeOracle biomes;
            if (serverLevel != null) {
                terrain = ServerWorldgenOracles.terrain(serverLevel);
                biomes = ServerWorldgenOracles.biomes(serverLevel, BiomeManager.obfuscateSeed(profile.seed()));
            } else {
                terrain = TerrainOracle.UNKNOWN_TERRAIN;
                biomes = BiomeOracle.UNKNOWN_BIOMES;
            }
            boolean suspect = ledger.isChunkSuspect(dim, cx, cz);
            if (service.request(cx, cz, terrain, biomes, suspect)) {
                sent++;
            }
        }
    }

    // ---- workbench ----------------------------------------------------------------------------------------------

    private void runWorkbench(String dim) {
        if (!profile.seedKnown() || VanillaOreTables.forDimension(dim).isEmpty()) {
            return;
        }
        HighlightIndex observed = scanner.observed;
        List<SeedWorkbench.Evidence> evidence = new ArrayList<>();
        int n = observed.renderCount();
        int step = Math.max(1, n / 48);
        for (int i = 0; i < n && evidence.size() < 48; i += step) {
            int v = observed.renderValue(i);
            if ((HighlightIndex.categories(v) & BlockCategories.ORES) == 0) {
                continue;
            }
            String id = observed.idAt(HighlightIndex.idIndexOf(v));
            if (id != null) {
                evidence.add(SeedWorkbench.ore(dim, observed.renderPos(i), id));
            }
        }
        if (evidence.size() < 4) {
            return;
        }
        long seed = profile.seed();
        WorldExecutor.get().execute(() -> {
            SeedWorkbench.Report report = new SeedWorkbench().compare(seed, evidence);
            lastReport = report;
            Minecraft.getInstance().execute(() -> announce(report));
        });
    }

    private void announce(SeedWorkbench.Report report) {
        boolean uncertain = report.verdict() == SeedWorkbench.Verdict.NO_MATCH;
        if (uncertain != workbenchUncertain) {
            workbenchUncertain = uncertain;
            configKey = "";
        }
        if (report.verdict() == announcedVerdict) {
            return;
        }
        announcedVerdict = report.verdict();
        String line = "Seed check: " + report.verdict() + " (" + report.matches() + " match, " + report.mismatches()
                + " mismatch, " + report.unknown() + " undecided)";
        var events = Services.eventsOrNull();
        if (events != null) {
            events.post(new SuiteEvent.Diagnostic(System.currentTimeMillis(), ID, line));
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && !cleanScreen()) {
            mc.player.displayClientMessage(Component.literal(line), true);
        }
    }

    // ---- ghosts -------------------------------------------------------------------------------------------------

    private void rebuildGhosts(String dim, LocalPlayer player) {
        ghostCount = 0;
        ghostDimension = dim;
        if (!showGhosts || !service.isConfigured()) {
            return;
        }
        predictions.forEachPrediction(dim, BlockPosPacking.pack(player.getBlockX(), player.getBlockY(),
                player.getBlockZ()), radiusChunks * 16, ghostCollector);
    }

    private void collectGhost(long pos, String id, String alt, float conf, long cluster) {
        if (ghostCount >= maxGhosts) {
            return;
        }
        String dim = ghostDimension;
        float factor = knowledge.overlayFactor(dim, pos, cluster);
        if (factor <= 0f || knowledge.ledger().observedAt(dim, pos) != null) {
            return; // refuted, or already confirmed (the observed overlay draws it)
        }
        float c = Math.max(0f, Math.min(1f, conf * factor));
        if (ghostCount == gx.length) {
            int cap = Math.max(256, gx.length * 2);
            gx = Arrays.copyOf(gx, cap);
            gy = Arrays.copyOf(gy, cap);
            gz = Arrays.copyOf(gz, cap);
            gColor = Arrays.copyOf(gColor, cap);
            gPercent = Arrays.copyOf(gPercent, cap);
        }
        int rgb = colorCache.getInt(id);
        if (rgb < 0) {
            rgb = BlockCategories.color(BlockCategories.classify(id, Set.of()), id.substring(id.indexOf(':') + 1))
                    & 0xFFFFFF;
            colorCache.put(id, rgb);
        }
        int alpha = (int) (60 + 150 * c);
        int i = ghostCount++;
        gx[i] = BlockPosPacking.x(pos);
        gy[i] = BlockPosPacking.y(pos);
        gz[i] = BlockPosPacking.z(pos);
        gColor[i] = (alpha << 24) | rgb;
        gPercent[i] = (byte) Math.round(c * 100f);
    }

    private boolean ghostsVisible(RenderContext ctx) {
        return showGhosts && ghostCount > 0 && ctx.dimension.equals(ghostDimension)
                && knowledge.overlayMode() != DataSourceMode.OBSERVED_ONLY;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (!ghostsVisible(ctx)) {
            return;
        }
        for (int i = 0; i < ghostCount; i++) {
            float x = ctx.rx(gx[i]);
            float y = ctx.ry(gy[i]);
            float z = ctx.rz(gz[i]);
            WorldRenderKit.dashedBox(ctx.lines, ctx.pose, x + 0.15f, y + 0.15f, z + 0.15f, x + 0.85f, y + 0.85f,
                    z + 0.85f, gColor[i]);
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (!ghostLabels || !ghostsVisible(ctx)) {
            return;
        }
        Arrays.fill(labelIndex, -1);
        Arrays.fill(labelDist, Double.MAX_VALUE);
        for (int i = 0; i < ghostCount; i++) {
            double d = ctx.distSq((int) gx[i], (int) gy[i], (int) gz[i]);
            if (d >= labelDist[LABELS - 1]) {
                continue;
            }
            int slot = LABELS - 1;
            while (slot > 0 && labelDist[slot - 1] > d) {
                labelDist[slot] = labelDist[slot - 1];
                labelIndex[slot] = labelIndex[slot - 1];
                slot--;
            }
            labelDist[slot] = d;
            labelIndex[slot] = i;
        }
        for (int k = 0; k < LABELS; k++) {
            int i = labelIndex[k];
            if (i < 0) {
                break;
            }
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(gx[i] + 0.5), ctx.ry(gy[i] + 1.3), ctx.rz(gz[i] + 0.5));
            float x = WorldRenderKit.text(ctx.font, ctx.buffers, m, "predicted ", -30, 0xC0FFFFFF);
            x = WorldRenderKit.text(ctx.font, ctx.buffers, m, WorldRenderKit.number(gPercent[i] & 0xFF), x,
                    gColor[i] | 0xFF000000);
            WorldRenderKit.text(ctx.font, ctx.buffers, m, "%", x, 0xC0FFFFFF);
        }
    }
}
