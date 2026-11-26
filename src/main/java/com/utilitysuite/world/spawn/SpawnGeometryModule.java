package com.utilitysuite.world.spawn;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.Shapes;
import com.utilitysuite.world.render.WorldRenderKit;
import com.utilitysuite.world.seed.GeneratorProfile;
import com.utilitysuite.world.seed.SeedText;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import com.utilitysuite.world.lighting.ColumnScanScheduler;
import com.utilitysuite.world.render.LineSink;
import org.joml.Matrix4f;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Spawn geometry: slime chunks (overworld, from the world seed) drawn as chunk outlines, and the mob spawn sphere
 * around a pinned point (where the module was switched on): nothing spawns within 24 blocks of a player, and mobs
 * beyond 128 blocks despawn at once. The slime chunk list is recomputed on the tick only when the player's chunk or
 * the seed changes; frames walk a flat array.
 * <p>
 * Mob-cap view (off by default): wireframe domes at 24 (no spawn), 32 (random despawn starts) and 128 blocks (instant
 * despawn) around you or a pinned AFK spot, and a budgeted cave scan that counts dark spawnable floor spots (light 0,
 * valid floor, two air blocks above) 24 to 128 blocks from that spot per sub-chunk ({@link CaveHeatGrid}) and points an
 * arrow at the biggest touching cluster: where your mob cap goes while you idle there.
 */
public final class SpawnGeometryModule extends WorldModule {

    public static final String ID = "spawn_geometry";
    public static final float NO_SPAWN_RADIUS = 24f;
    public static final float DESPAWN_RADIUS = 128f;
    private static final int SLIME_COLOR = 0xC060FF60;
    private static final int INNER_COLOR = 0x90FF5050;
    private static final int OUTER_COLOR = 0x605090FF;
    private static final int DOME_NO_SPAWN = 0x90FF5050;
    private static final int DOME_RANDOM = 0x80FFD040;
    private static final int DOME_INSTANT = 0x605090FF;
    private static final int HEAT_COLOR = 0xE0FF60FF;
    /** Cave scan: one column every HEAT_STEP blocks each way (a 4x sample of the 257x257 square). */
    private static final int HEAT_STEP = 2;
    private static final int HEAT_MOVE_RESTART = 16;
    private static final String SEED_NEEDED = "Slime chunks: seed needed (Seed Workbench or setting)";

    private final Supplier<GeneratorProfile> seedSource;
    private final PoseLineSink sink = new PoseLineSink();

    private boolean slime;
    private boolean spheres;
    private int slimeRadius;
    private boolean followFeet;
    private int fixedY;
    private String manualSeed = "";
    private boolean manualKnown;
    private long manualValue;
    /** Output of {@link #resolveSeed} (fields, so the tick allocates nothing). */
    private boolean resolvedKnown;
    private long resolvedSeed;

    private long[] slimeChunks = new long[0];
    private int slimeCount;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private long lastSeed;
    private boolean seedKnown;
    private boolean overworld;
    private boolean pinned;
    private double pinX;
    private double pinY;
    private double pinZ;

    // Mob-cap domes and cave heat (R4).
    private boolean domes;
    private boolean afkPin;
    private boolean afkCapturePending;
    private boolean afkKnown;
    private double afkX;
    private double afkY;
    private double afkZ;
    private boolean caveHeat;
    private int heatBudget;
    private final ColumnScanScheduler heatScheduler = new ColumnScanScheduler();
    private final CaveHeatGrid heatGrid = new CaveHeatGrid();
    private final BlockPos.MutableBlockPos heatPos = new BlockPos.MutableBlockPos();
    private boolean heatRunning;
    private int heatCenterX;
    private int heatCenterY;
    private int heatCenterZ;
    // Published result of the last finished pass (rendered).
    private int sinkSpots;
    private int sinkTotal;
    private double sinkX;
    private double sinkY;
    private double sinkZ;
    private int sinkX0;
    private int sinkY0;
    private int sinkZ0;
    private int sinkX1;
    private int sinkY1;
    private int sinkZ1;
    private String sinkLabel = "";

    public SpawnGeometryModule(Supplier<GeneratorProfile> seedSource) {
        super(ID, "Spawn Geometry", false);
        this.seedSource = seedSource;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("slime", "Slime chunks", true, "Slime chunks",
                        "Outline slime chunks around you (needs the world seed)."),
                SettingSpec.integer("slime_radius", "Radius (chunks)", 6, 1, 16, "Slime chunks", "Reach of the outlines."),
                SettingSpec.bool("follow_feet", "At my feet", true, "Slime chunks",
                        "Draw the outlines at your feet; off: at the fixed height below."),
                SettingSpec.integer("outline_y", "Fixed height", 40, -64, 320, "Slime chunks",
                        "Outline height when 'At my feet' is off (slimes spawn below Y 40)."),
                SettingSpec.text("manual_seed", "Seed", "", "Slime chunks",
                        "Used when neither single-player nor the Seed Workbench knows the seed."),
                SettingSpec.bool("spheres", "Spawn spheres", true, "Spawn spheres",
                        "24-block no-spawn sphere and 128-block despawn sphere around the point where you switched "
                                + "this module on (switch off and on to move it)."),
                SettingSpec.bool("domes", "Mob-cap domes", false, "Mob cap",
                        "Wireframe domes at 24 (no spawn), 32 (random despawn starts) and 128 blocks (instant "
                                + "despawn) around you or the pinned AFK spot."),
                SettingSpec.bool("afk_pin", "Pin AFK spot here", false, "Mob cap",
                        "Switch on to pin the domes and the cave scan where you stand now; off: they follow you."),
                SettingSpec.bool("cave_heat", "Find cave spawn spots", false, "Mob cap",
                        "Scan (a little per tick) for dark spawnable cave floor 24-128 blocks from the AFK spot and "
                                + "point at the biggest cluster: where your mob cap goes."),
                SettingSpec.integer("heat_budget", "Scan columns per tick", 16, 2, 128, "Mob cap",
                        "Columns examined per tick by the cave scan (one every 2 blocks each way)."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        slime = bool(p, "slime");
        slimeRadius = integer(p, "slime_radius");
        followFeet = bool(p, "follow_feet");
        fixedY = integer(p, "outline_y");
        manualSeed = text(p, "manual_seed");
        OptionalLong parsed = manualSeed.isEmpty() ? OptionalLong.empty() : SeedText.parseManualSeed(manualSeed);
        manualKnown = parsed.isPresent();
        manualValue = manualKnown ? parsed.getAsLong() : 0L;
        spheres = bool(p, "spheres");
        lastChunkX = Integer.MIN_VALUE; // force a recompute
        domes = bool(p, "domes");
        boolean pin = bool(p, "afk_pin");
        afkCapturePending = pin && (!afkPin || !afkKnown); // capture on the next tick when switched on
        afkPin = pin;
        caveHeat = bool(p, "cave_heat");
        heatBudget = integer(p, "heat_budget");
        if (!caveHeat) {
            heatRunning = false;
        }
    }

    @Override
    protected void onEnable() {
        pinned = false;
        lastChunkX = Integer.MIN_VALUE;
    }

    @Override
    public void onWorldUnload() {
        pinned = false;
        afkKnown = false;
        afkCapturePending = afkPin;
        heatRunning = false;
        sinkSpots = 0;
        slimeCount = 0;
        lastChunkX = Integer.MIN_VALUE;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        if (!pinned) {
            pinned = true;
            pinX = player.getX();
            pinY = player.getY();
            pinZ = player.getZ();
        }
        if (afkCapturePending) {
            afkCapturePending = false;
            afkKnown = true;
            afkX = player.getX();
            afkY = player.getY();
            afkZ = player.getZ();
        }
        if (caveHeat) {
            tickCaveHeat(level, player);
        }
        if (!slime) {
            slimeCount = 0;
            return;
        }
        overworld = level.dimension() == Level.OVERWORLD;
        resolveSeed(mc);
        boolean known = resolvedKnown;
        long s = resolvedSeed;
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        if (cx == lastChunkX && cz == lastChunkZ && known == seedKnown && s == lastSeed) {
            return;
        }
        lastChunkX = cx;
        lastChunkZ = cz;
        seedKnown = known;
        lastSeed = s;
        slimeCount = 0;
        if (!known || !overworld) {
            return;
        }
        int r = slimeRadius;
        int side = 2 * r + 1;
        if (slimeChunks.length < side * side) {
            slimeChunks = new long[side * side];
        }
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                if (SlimeChunks.isSlimeChunk(s, cx + dx, cz + dz)) {
                    slimeChunks[slimeCount++] = ((long) (cx + dx) << 32) | ((cz + dz) & 0xFFFFFFFFL);
                }
            }
        }
    }

    private double capCenterX(LocalPlayer player) {
        return afkPin && afkKnown ? afkX : player.getX();
    }

    private double capCenterY(LocalPlayer player) {
        return afkPin && afkKnown ? afkY : player.getY();
    }

    private double capCenterZ(LocalPlayer player) {
        return afkPin && afkKnown ? afkZ : player.getZ();
    }

    /** Budgeted cave scan around the AFK spot (or the player); publishes the biggest cluster when a pass ends. */
    private void tickCaveHeat(ClientLevel level, LocalPlayer player) {
        int cx = (int) Math.floor(capCenterX(player));
        int cy = (int) Math.floor(capCenterY(player));
        int cz = (int) Math.floor(capCenterZ(player));
        if (!heatRunning || Math.abs(cx - heatCenterX) > HEAT_MOVE_RESTART
                || Math.abs(cz - heatCenterZ) > HEAT_MOVE_RESTART || Math.abs(cy - heatCenterY) > HEAT_MOVE_RESTART) {
            startHeatPass(level, cx, cy, cz);
        }
        heatScheduler.beginTick(heatBudget);
        while (heatScheduler.next()) {
            scanHeatColumn(level, heatCenterX + (heatScheduler.x() - heatCenterX) * HEAT_STEP,
                    heatCenterZ + (heatScheduler.z() - heatCenterZ) * HEAT_STEP);
        }
        if (!heatScheduler.passActive()) {
            heatGrid.computeClusters();
            publishSink();
            startHeatPass(level, cx, cy, cz);
        }
    }

    private void startHeatPass(ClientLevel level, int cx, int cy, int cz) {
        heatRunning = true;
        heatCenterX = cx;
        heatCenterY = cy;
        heatCenterZ = cz;
        heatGrid.reset(cx, cz, level.getMinSectionY(), level.getSectionsCount());
        // Scheduler units are HEAT_STEP blocks, centred on the anchor (x() - centre is the step index).
        heatScheduler.clear();
        heatScheduler.restart(cx, cz, (int) CaveHeatGrid.INSTANT_DESPAWN / HEAT_STEP);
    }

    private void scanHeatColumn(ClientLevel level, int x, int z) {
        LevelChunk chunk = level.getChunkSource().getChunk(x >> 4, z >> 4, false);
        if (chunk == null) {
            return;
        }
        double dx = x + 0.5 - heatCenterX;
        double dz = z + 0.5 - heatCenterZ;
        double h2 = dx * dx + dz * dz;
        if (h2 > CaveHeatGrid.INSTANT_DESPAWN * CaveHeatGrid.INSTANT_DESPAWN) {
            return;
        }
        int minY = Math.max(level.getMinY(), heatCenterY - (int) CaveHeatGrid.INSTANT_DESPAWN);
        int maxY = Math.min(level.getMaxY() - 2, heatCenterY + (int) CaveHeatGrid.INSTANT_DESPAWN);
        // Walk down; 'air' counts consecutive empty blocks above the current one.
        int air = 0;
        for (int y = maxY + 2; y >= minY; y--) {
            BlockState s = chunk.getBlockState(heatPos.set(x, y, z));
            if (s.isAir()) {
                air++;
                continue;
            }
            if (air >= 2 && y <= maxY) {
                double dy = y + 1 - heatCenterY;
                if (CaveHeatGrid.countsForCap(h2 + dy * dy) && s.isValidSpawn(level, heatPos, EntityTypes.ZOMBIE)) {
                    heatPos.set(x, y + 1, z);
                    if (level.getBrightness(LightLayer.BLOCK, heatPos) == 0
                            && level.getBrightness(LightLayer.SKY, heatPos) == 0) {
                        heatGrid.add(x, y + 1, z);
                    }
                }
            }
            air = 0;
        }
    }

    /** Copies the grid's best cluster into the rendered fields (once per finished pass). */
    private void publishSink() {
        sinkTotal = heatGrid.total();
        sinkSpots = heatGrid.bestSpots();
        if (sinkSpots == 0) {
            return;
        }
        sinkX = heatGrid.bestX();
        sinkY = heatGrid.bestY();
        sinkZ = heatGrid.bestZ();
        sinkX0 = heatGrid.bestMinSX() << 4;
        sinkY0 = heatGrid.bestMinSY() << 4;
        sinkZ0 = heatGrid.bestMinSZ() << 4;
        sinkX1 = (heatGrid.bestMaxSX() + 1) << 4;
        sinkY1 = (heatGrid.bestMaxSY() + 1) << 4;
        sinkZ1 = (heatGrid.bestMaxSZ() + 1) << 4;
        sinkLabel = "Cave spawns: " + sinkSpots + " of " + sinkTotal + " dark spots (sampled)"; // once per pass
    }

    /** Integrated server seed, else the Seed Workbench's profile, else the manual setting. Not a hot path per frame. */
    private void resolveSeed(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            resolvedKnown = true;
            resolvedSeed = sp.getWorldGenSettings().options().seed();
            return;
        }
        GeneratorProfile p = seedSource == null ? null : seedSource.get();
        if (p != null && p.seedKnown()) {
            resolvedKnown = true;
            resolvedSeed = p.seed();
            return;
        }
        resolvedKnown = manualKnown;
        resolvedSeed = manualValue;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        PoseLineSink s = sink.bind(ctx);
        if (slime && slimeCount > 0) {
            double wy = followFeet ? player.getBlockY() + 0.05 : fixedY + 0.05;
            emitSlime(s, slimeChunks, slimeCount, (float) (wy - ctx.camY), ctx.camX, ctx.camZ);
        }
        if (spheres && pinned) {
            float x = ctx.rx(pinX);
            float y = ctx.ry(pinY);
            float z = ctx.rz(pinZ);
            Shapes.globe(s, x, y, z, NO_SPAWN_RADIUS, INNER_COLOR);
            Shapes.globe(s, x, y, z, DESPAWN_RADIUS, OUTER_COLOR);
        }
        if (domes || caveHeat) {
            float x = ctx.rx(capCenterX(player));
            float y = ctx.ry(capCenterY(player));
            float z = ctx.rz(capCenterZ(player));
            if (domes) {
                emitDomes(s, x, y, z);
            }
            if (afkPin && afkKnown) {
                Shapes.verticalRing(s, x, y + 1f, z, 0.6f, true, HEAT_COLOR);
                Shapes.verticalRing(s, x, y + 1f, z, 0.6f, false, HEAT_COLOR);
            }
            if (caveHeat && sinkSpots > 0) {
                emitSinkArrow(s, x, y + 0.2f, z, ctx.rx(sinkX), ctx.ry(sinkY), ctx.rz(sinkZ));
                WorldRenderKit.box(ctx.lines, ctx.pose, ctx.rx(sinkX0), ctx.ry(sinkY0), ctx.rz(sinkZ0), ctx.rx(sinkX1),
                        ctx.ry(sinkY1), ctx.rz(sinkZ1), HEAT_COLOR & 0x80FFFFFF);
            }
        }
    }

    /** The three mob-cap domes (allocation-free). */
    static void emitDomes(LineSink s, float x, float y, float z) {
        Shapes.dome(s, x, y, z, CaveHeatGrid.NO_SPAWN, DOME_NO_SPAWN);
        Shapes.dome(s, x, y, z, CaveHeatGrid.RANDOM_DESPAWN, DOME_RANDOM);
        Shapes.dome(s, x, y, z, CaveHeatGrid.INSTANT_DESPAWN, DOME_INSTANT);
    }

    /** Ground arrow up to 8 blocks long from the AFK spot toward the cluster, plus a thin line to its centroid. */
    static void emitSinkArrow(LineSink s, float x, float y, float z, float tx, float ty, float tz) {
        float dx = tx - x;
        float dz = tz - z;
        float len = (float) Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0e-3f) {
            float k = Math.min(8f, len) / len;
            Shapes.arrow(s, x, y, z, x + dx * k, y, z + dz * k, 1.5f, HEAT_COLOR);
        }
        s.line(x, y, z, tx, ty, tz, HEAT_COLOR & 0x60FFFFFF);
    }

    /** Per-frame emitter (allocation-free). */
    static void emitSlime(com.utilitysuite.world.render.LineSink s, long[] chunks, int n, float y, double camX,
                          double camZ) {
        for (int i = 0; i < n; i++) {
            long k = chunks[i];
            int cx = (int) (k >> 32);
            int cz = (int) k;
            float x0 = (float) ((cx << 4) - camX);
            float z0 = (float) ((cz << 4) - camZ);
            Shapes.square(s, x0 + 0.1f, y, z0 + 0.1f, x0 + 15.9f, z0 + 15.9f, SLIME_COLOR);
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (caveHeat && sinkSpots > 0) {
            Matrix4f hm = ctx.kit.beginLabel(ctx.pose, ctx.rx(sinkX), ctx.ry(sinkY + 2), ctx.rz(sinkZ));
            WorldRenderKit.text(ctx.font, ctx.buffers, hm, sinkLabel, -ctx.font.width(sinkLabel) / 2f, HEAT_COLOR);
        }
        if (!slime || seedKnown || !overworld) {
            return;
        }
        // One line in the world ahead of the player while no seed is known.
        float x = ctx.rx(((player.getBlockX() >> 4) << 4) + 8);
        float y = ctx.ry(player.getBlockY() + 0.5);
        float z = ctx.rz(((player.getBlockZ() >> 4) << 4) + 8);
        Matrix4f m = ctx.kit.beginLabel(ctx.pose, x, y, z);
        WorldRenderKit.text(ctx.font, ctx.buffers, m, SEED_NEEDED, -ctx.font.width(SEED_NEEDED) / 2f, 0xFFFFC040);
    }
}
