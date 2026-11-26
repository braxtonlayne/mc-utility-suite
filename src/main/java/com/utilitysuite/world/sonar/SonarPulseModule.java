package com.utilitysuite.world.sonar;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.knowledge.PredictionSource;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import net.minecraft.util.Util;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * LiDAR / sonar pulse: the pulse key sends an expanding spherical wavefront from the player. The loaded world around
 * the player is copied into a reused voxel grid over a few ticks ({@link GridCapture}, budgeted), then a worker job
 * samples it into a sparse, distance-ordered point cloud ({@link SurfaceExtractor}): terrain and cave-wall surfaces,
 * spawners, and seed-predicted ores from OreSim. Points light up as the front passes them and fade; a thin ring marks
 * the front. Nothing is drawn between pulses. Default off.
 */
public final class SonarPulseModule extends WorldModule {

    public static final String ID = "sonar_pulse";
    private static final int RING_COLOR = 0x60E0FF;

    private final PredictionSource predictions;
    private final VoxelGrid grid = new VoxelGrid();
    private final GridCapture capture = new GridCapture(grid);
    private final Wavefront wave = new Wavefront();
    private final PoseLineSink sink = new PoseLineSink();
    private KeyMapping key;

    private volatile PointCloud cloud = PointCloud.EMPTY;
    private volatile boolean busy;
    private boolean pulseRequested;
    private int originX;
    private int originY;
    private int originZ;
    private String dimension;

    private int speed;
    private int range;
    private int fadeMs;
    private int maxPoints;
    private boolean showOres;
    private boolean showSpawners;
    private int sectionsPerTick;

    public SonarPulseModule(PredictionSource predictions) {
        super(ID, "Sonar Pulse", false);
        this.predictions = predictions == null ? PredictionSource.NONE : predictions;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("speed", "Wave speed", 32, 8, 128, "Pulse",
                        "How fast the wavefront expands, in blocks per second."),
                SettingSpec.integer("range", "Range", 48, 16, 96, "Pulse", "Radius the wavefront travels."),
                SettingSpec.integer("fade_ms", "Fade time (ms)", 1500, 300, 5000, "Pulse",
                        "How long a point stays lit after the front passes it."),
                SettingSpec.bool("show_ores", "Predicted ores", true, "Marks",
                        "Also light up OreSim's seed-predicted ores (needs OreSim configured)."),
                SettingSpec.bool("show_spawners", "Spawners", true, "Marks", "Mark mob and trial spawners."),
                SettingSpec.integer("max_points", "Max points", 6000, 500, 30000, "Performance",
                        "Cap on sampled points; the nearest are kept.").asAdvanced(),
                SettingSpec.integer("sections_per_tick", "Sections per tick", 32, 4, 256, "Performance",
                        "Chunk sections (16x16x16) copied per tick while a pulse samples the world.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        speed = integer(p, "speed");
        range = integer(p, "range");
        fadeMs = integer(p, "fade_ms");
        showOres = bool(p, "show_ores");
        showSpawners = bool(p, "show_spawners");
        maxPoints = integer(p, "max_points");
        sectionsPerTick = integer(p, "sections_per_tick");
    }

    public void setKey(KeyMapping pulseKey) {
        this.key = pulseKey;
    }

    /** Turning the module on sends one pulse. */
    @Override
    protected void onEnable() {
        pulseRequested = true;
    }

    @Override
    protected void onDisable() {
        clear();
    }

    @Override
    public void onWorldUnload() {
        clear();
    }

    private void clear() {
        capture.cancel();
        wave.stop();
        cloud = PointCloud.EMPTY;
        pulseRequested = false;
    }

    /** Requests a pulse (key, command). */
    public void pulse() {
        pulseRequested = true;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        while (key != null && key.consumeClick()) {
            pulseRequested = true;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (pulseRequested && !busy && !capture.running()) {
            pulseRequested = false;
            originX = player.getBlockX();
            originY = (int) Math.floor(player.getEyeY());
            originZ = player.getBlockZ();
            dimension = WorldIds.dimension(level);
            int chunkRadius = (range + 15) >> 4;
            capture.begin(level, originX >> 4, originZ >> 4, chunkRadius, (originY - range) >> 4,
                    (originY + range) >> 4);
            cloud = PointCloud.EMPTY;
            wave.start(Util.getMillis(), speed, range, fadeMs);
        }
        if (capture.running() && capture.step(sectionsPerTick)) {
            submit();
        }
        if (!wave.active(Util.getMillis()) && !capture.running() && !busy && cloud != PointCloud.EMPTY) {
            cloud = PointCloud.EMPTY; // pulse over: free the points
        }
    }

    /** Worker job over the captured grid; publishes an immutable cloud. */
    private void submit() {
        busy = true;
        final int ox = originX;
        final int oy = originY;
        final int oz = originZ;
        final int r = range;
        final int cap = maxPoints;
        final boolean ores = showOres;
        final boolean spawners = showSpawners;
        final String dim = dimension;
        WorldExecutor.get().execute(() -> {
            try {
                long[] extraPos = new long[64];
                byte[] extraKind = new byte[64];
                int[] n = {0};
                if (ores && dim != null) {
                    long[][] holder = {extraPos};
                    byte[][] kinds = {extraKind};
                    predictions.forEachPrediction(dim, BlockPosPacking.pack(ox, oy, oz), r,
                            (pos, id, alt, confidence, cluster) -> {
                                if (n[0] == holder[0].length) {
                                    holder[0] = java.util.Arrays.copyOf(holder[0], n[0] * 2);
                                    kinds[0] = java.util.Arrays.copyOf(kinds[0], n[0] * 2);
                                }
                                holder[0][n[0]] = pos;
                                kinds[0][n[0]] = PointCloud.ORE;
                                n[0]++;
                            });
                    extraPos = holder[0];
                    extraKind = kinds[0];
                }
                cloud = SurfaceExtractor.extract(grid, ox, oy, oz, r, cap, spawners, extraPos, extraKind, n[0]);
            } finally {
                busy = false;
            }
        });
    }

    @Override
    public void renderLines(RenderContext ctx) {
        long now = Util.getMillis();
        if (!wave.active(now)) {
            return; // idle between pulses: nothing drawn
        }
        // While the sample is still being prepared the cloud is EMPTY and only the front is drawn.
        cloud.emit(sink.bind(ctx), wave, now, ctx.camX, ctx.camY, ctx.camZ, RING_COLOR, originX, originY, originZ);
    }

    /** For tests and diagnostics. */
    PointCloud cloud() {
        return cloud;
    }
}
