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
import net.minecraft.world.level.Level;
import org.joml.Matrix4f;

import java.util.List;
import java.util.OptionalLong;
import java.util.function.Supplier;

/**
 * Spawn geometry: slime chunks (overworld, from the world seed) drawn as chunk outlines, and the mob spawn sphere
 * around a pinned point (where the module was switched on): nothing spawns within 24 blocks of a player, and mobs
 * beyond 128 blocks despawn at once. The slime chunk list is recomputed on the tick only when the player's chunk or
 * the seed changes; frames walk a flat array.
 */
public final class SpawnGeometryModule extends WorldModule {

    public static final String ID = "spawn_geometry";
    public static final float NO_SPAWN_RADIUS = 24f;
    public static final float DESPAWN_RADIUS = 128f;
    private static final int SLIME_COLOR = 0xC060FF60;
    private static final int INNER_COLOR = 0x90FF5050;
    private static final int OUTER_COLOR = 0x605090FF;
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
                                + "this module on (switch off and on to move it)."));
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
    }

    @Override
    protected void onEnable() {
        pinned = false;
        lastChunkX = Integer.MIN_VALUE;
    }

    @Override
    public void onWorldUnload() {
        pinned = false;
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

    /** Integrated server seed, else the Seed Workbench's profile, else the manual setting. Not a hot path per frame. */
    private void resolveSeed(Minecraft mc) {
        IntegratedServer sp = mc.getSingleplayerServer();
        if (sp != null) {
            resolvedKnown = true;
            resolvedSeed = sp.getWorldData().worldGenOptions().seed();
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
        if (!slime || seedKnown || !overworld) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
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
