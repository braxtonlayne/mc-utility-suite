package com.utilitysuite.world.stealth;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.LineSink;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.Shapes;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.piglin.PiglinBrute;
import net.minecraft.world.entity.monster.warden.Warden;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

/**
 * Stealth vision: who can notice you. For the nearest few sources it draws, at ground level, the Warden's 16-block
 * vibration radius and facing (with its synced anger), each Enderman's stare cone as a ring around its head (keep the
 * crosshair outside it: vanilla's {@code dot > 1 - 0.025 / distance} test within 64 blocks), Piglin Brute sight radii,
 * other players' view fans (red when you stand inside one with line of sight), and the 8-block spheres of nearby sculk
 * sensors and shriekers with a line to you that turns grey when wool blocks the vibration (vanilla's six-ray rule).
 * <p>
 * Entities are gathered once per tick into fixed arrays (nearest N), sculk blocks by a budgeted section sweep with a
 * palette pre-check; frames only read those arrays. Off by default.
 */
public final class StealthVisionModule extends WorldModule {

    public static final String ID = "stealth_vision";
    static final int KIND_WARDEN = 1;
    static final int KIND_ENDERMAN = 2;
    static final int KIND_BRUTE = 3;
    static final int KIND_PLAYER = 4;
    private static final int MAX_SOURCES = 32;
    private static final int MAX_SCULK = 64;
    private static final int SCAN_CAP = 96;
    private static final int SECTIONS_PER_TICK = 3;
    private static final int CALM = 0xA0FFB040;
    private static final int ALERT = 0xF0FF3030;
    private static final int SCULK_COLOR = 0x9030D0C0;
    private static final int MUFFLED = 0x80909090;
    private static final EntityTypeTest<Entity, LivingEntity> LIVING = EntityTypeTest.forClass(LivingEntity.class);
    private static final Predicate<LivingEntity> SOURCE = StealthVisionModule::isSource;

    private final PoseLineSink sink = new PoseLineSink();
    private final List<LivingEntity> buffer = new ArrayList<>(SCAN_CAP);
    private final BlockPos.MutableBlockPos cell = new BlockPos.MutableBlockPos();
    private final StealthMath.CellTest solid = this::solidAt;
    private final StealthMath.CellTest wool = this::woolAt;
    private final Predicate<BlockState> sculkBlock = StealthVisionModule::isSculk;
    private final SectionSweep sweep = new SectionSweep();
    private ClientLevel testLevel;

    private boolean wardens;
    private boolean endermen;
    private boolean brutes;
    private boolean players;
    private boolean sculk;
    private int maxSources;
    private int range;
    private int fovDeg;
    private int playerRange;
    private float fovRad;
    private double cosFov;

    // Nearest sources, refilled each tick.
    private final LivingEntity[] src = new LivingEntity[MAX_SOURCES];
    private final int[] kind = new int[MAX_SOURCES];
    private final double[] dist2 = new double[MAX_SOURCES];
    private final boolean[] alert = new boolean[MAX_SOURCES];
    private final float[] yawRad = new float[MAX_SOURCES];
    private final float[] gaze = new float[MAX_SOURCES];
    private final int[] anger = new int[MAX_SOURCES];
    private int count;

    // Sculk blocks: front (rendered) and back (sweep) buffers, plus per-block "can hear me" state.
    private long[] sculkPos = new long[MAX_SCULK];
    private int sculkCount;
    private long[] backSculk = new long[MAX_SCULK];
    private int backCount;
    private final byte[] sculkHear = new byte[MAX_SCULK]; // 0 out of range, 1 hears, 2 wool-muffled
    private boolean sweeping;

    public StealthVisionModule() {
        super(ID, "Stealth Vision", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("wardens", "Wardens", true, "Sources",
                        "16-block vibration radius, facing and anger of nearby Wardens."),
                SettingSpec.bool("endermen", "Endermen", true, "Sources",
                        "Stare ring around each Enderman's head: crosshair inside it (with line of sight) angers it."),
                SettingSpec.bool("brutes", "Piglin Brutes", true, "Sources", "16-block sight radius of Piglin Brutes."),
                SettingSpec.bool("players", "Other players", true, "Sources",
                        "Ground fan of other players' view; red while you are inside it with line of sight."),
                SettingSpec.bool("sculk", "Sculk sensors", true, "Sources",
                        "8-block listen spheres of sculk sensors and shriekers; the line to you turns grey when "
                                + "wool blocks the vibration."),
                SettingSpec.integer("max_sources", "Max sources", 8, 1, MAX_SOURCES, "Limits",
                        "Only the nearest sources are drawn."),
                SettingSpec.integer("range", "Range", 48, 16, 96, "Limits", "Mobs and players this close count."),
                SettingSpec.integer("player_fov", "Player view half-angle", 70, 30, 90, "Limits",
                        "Half-angle of other players' view fan (default FOV 70 gives about 70 sideways on 16:9)."),
                SettingSpec.integer("player_range", "Player view length", 32, 8, 96, "Limits",
                        "Length of other players' view fan."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        wardens = bool(p, "wardens");
        endermen = bool(p, "endermen");
        brutes = bool(p, "brutes");
        players = bool(p, "players");
        sculk = bool(p, "sculk");
        maxSources = integer(p, "max_sources");
        range = integer(p, "range");
        fovDeg = integer(p, "player_fov");
        playerRange = integer(p, "player_range");
        fovRad = (float) Math.toRadians(fovDeg);
        cosFov = Math.cos(fovRad);
    }

    @Override
    protected void onDisable() {
        clearAll();
    }

    @Override
    public void onWorldUnload() {
        clearAll();
    }

    private void clearAll() {
        java.util.Arrays.fill(src, null);
        count = 0;
        sculkCount = 0;
        backCount = 0;
        sweeping = false;
        testLevel = null;
    }

    private static boolean isSource(LivingEntity e) {
        return e instanceof Warden || e instanceof EnderMan || e instanceof PiglinBrute || e instanceof Player;
    }

    private static boolean isSculk(BlockState s) {
        return s.is(Blocks.SCULK_SENSOR) || s.is(Blocks.CALIBRATED_SCULK_SENSOR) || s.is(Blocks.SCULK_SHRIEKER);
    }

    private boolean solidAt(int x, int y, int z) {
        BlockState s = testLevel.getBlockState(cell.set(x, y, z));
        return !s.getCollisionShape(testLevel, cell).isEmpty();
    }

    private boolean woolAt(int x, int y, int z) {
        return testLevel.getBlockState(cell.set(x, y, z)).is(BlockTags.OCCLUDES_VIBRATION_SIGNALS);
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer me = mc.player;
        ClientLevel level = mc.level;
        if (me == null || level == null) {
            return;
        }
        testLevel = level;
        gatherSources(level, me);
        if (sculk) {
            sweepSculk(level, me);
            classifySculk(me);
        } else {
            sculkCount = 0;
        }
    }

    // ---- entities ------------------------------------------------------------------------------------------------

    private boolean wanted(LivingEntity e, LocalPlayer me) {
        if (e == me || !e.isAlive()) {
            return false;
        }
        if (e instanceof Warden) {
            return wardens;
        }
        if (e instanceof EnderMan) {
            return endermen;
        }
        if (e instanceof PiglinBrute) {
            return brutes;
        }
        return players && e instanceof Player p && !p.isSpectator();
    }

    private void gatherSources(ClientLevel level, LocalPlayer me) {
        buffer.clear();
        // One AABB per tick (vanilla's query needs it).
        level.getEntities(LIVING, me.getBoundingBox().inflate(range), SOURCE, buffer, SCAN_CAP);
        count = 0;
        double r2 = (double) range * range;
        for (int i = 0, n = buffer.size(); i < n; i++) {
            LivingEntity e = buffer.get(i);
            if (!wanted(e, me)) {
                continue;
            }
            double d2 = e.distanceToSqr(me);
            if (d2 > r2) {
                continue;
            }
            insertNearest(e, d2);
        }
        buffer.clear();
        for (int i = count; i < MAX_SOURCES && src[i] != null; i++) {
            src[i] = null; // drop stale references
        }
        double eyeX = me.getX();
        double eyeY = me.getEyeY();
        double eyeZ = me.getZ();
        for (int i = 0; i < count; i++) {
            LivingEntity e = src[i];
            double dx = e.getX() - eyeX;
            double dy = e.getEyeY() - eyeY;
            double dz = e.getZ() - eyeZ;
            alert[i] = false;
            anger[i] = 0;
            if (e instanceof Warden w) {
                kind[i] = KIND_WARDEN;
                yawRad[i] = (float) Math.toRadians(w.getYHeadRot());
                anger[i] = w.getClientAngerLevel();
                alert[i] = dist2[i] <= StealthMath.WARDEN_LISTEN_RADIUS * StealthMath.WARDEN_LISTEN_RADIUS
                        && !StealthMath.vibrationOccluded(me.getBlockX(), me.getBlockY(), me.getBlockZ(),
                        e.getBlockX(), Mth.floor(e.getEyeY()), e.getBlockZ(), wool);
            } else if (e instanceof EnderMan) {
                kind[i] = KIND_ENDERMAN;
                double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
                gaze[i] = (float) StealthMath.endermanGazeRadius(d);
                float yaw = me.getYRot();
                float pitch = me.getXRot();
                boolean masked = me.getItemBySlot(EquipmentSlot.HEAD).is(Items.CARVED_PUMPKIN);
                alert[i] = !masked && StealthMath.endermanStare(StealthMath.lookX(yaw, pitch),
                        StealthMath.lookY(pitch), StealthMath.lookZ(yaw, pitch), dx, dy, dz)
                        && !StealthMath.blocked(eyeX, eyeY, eyeZ, e.getX(), e.getEyeY(), e.getZ(), solid);
            } else if (e instanceof PiglinBrute) {
                kind[i] = KIND_BRUTE;
                yawRad[i] = (float) Math.toRadians(e.getYHeadRot());
                alert[i] = dist2[i] <= StealthMath.BRUTE_RADIUS * StealthMath.BRUTE_RADIUS
                        && !StealthMath.blocked(e.getX(), e.getEyeY(), e.getZ(), eyeX, eyeY, eyeZ, solid);
            } else {
                kind[i] = KIND_PLAYER;
                float yaw = e.getYHeadRot();
                float pitch = e.getXRot();
                yawRad[i] = (float) Math.toRadians(yaw);
                alert[i] = StealthMath.inCone(StealthMath.lookX(yaw, pitch), StealthMath.lookY(pitch),
                        StealthMath.lookZ(yaw, pitch), -dx, -dy, -dz, cosFov, playerRange)
                        && !StealthMath.blocked(e.getX(), e.getEyeY(), e.getZ(), eyeX, eyeY, eyeZ, solid);
            }
        }
    }

    /** Keeps the {@code maxSources} nearest, sorted (insertion into fixed arrays). */
    private void insertNearest(LivingEntity e, double d2) {
        int limit = Math.min(maxSources, MAX_SOURCES);
        int at = count;
        while (at > 0 && dist2[at - 1] > d2) {
            at--;
        }
        if (at >= limit) {
            return;
        }
        int last = Math.min(count, limit - 1);
        for (int j = last; j > at; j--) {
            src[j] = src[j - 1];
            dist2[j] = dist2[j - 1];
        }
        src[at] = e;
        dist2[at] = d2;
        if (count < limit) {
            count++;
        }
    }

    // ---- sculk ---------------------------------------------------------------------------------------------------

    private void sweepSculk(ClientLevel level, LocalPlayer me) {
        int scx = me.getBlockX() >> 4;
        int scy = me.getBlockY() >> 4;
        int scz = me.getBlockZ() >> 4;
        if (!sweeping || sweep.done()) {
            if (sweeping) { // publish the finished pass
                long[] t = sculkPos;
                sculkPos = backSculk;
                sculkCount = backCount;
                backSculk = t;
            }
            backCount = 0;
            sweeping = true;
            sweep.restart(scx, scy, scz, 1, 1); // 48x48x48 around you: everything within 8 of you and more
        }
        for (int k = 0; k < SECTIONS_PER_TICK && sweep.next(); k++) {
            scanSection(level, sweep.x(), sweep.y(), sweep.z());
        }
    }

    private void scanSection(ClientLevel level, int sx, int sy, int sz) {
        int idx = level.getSectionIndexFromSectionY(sy);
        if (idx < 0 || idx >= level.getSectionsCount()) {
            return;
        }
        LevelChunk chunk = level.getChunkSource().getChunk(sx, sz, false);
        if (chunk == null) {
            return;
        }
        LevelChunkSection section = chunk.getSection(idx);
        if (section.hasOnlyAir() || !section.maybeHas(sculkBlock)) {
            return;
        }
        for (int ly = 0; ly < 16; ly++) {
            for (int lz = 0; lz < 16; lz++) {
                for (int lx = 0; lx < 16; lx++) {
                    if (backCount < MAX_SCULK && isSculk(section.getBlockState(lx, ly, lz))) {
                        backSculk[backCount++] = BlockPosPacking.pack((sx << 4) + lx, (sy << 4) + ly, (sz << 4) + lz);
                    }
                }
            }
        }
    }

    private void classifySculk(LocalPlayer me) {
        int px = me.getBlockX();
        int py = me.getBlockY();
        int pz = me.getBlockZ();
        for (int i = 0; i < sculkCount; i++) {
            long p = sculkPos[i];
            int x = BlockPosPacking.x(p);
            int y = BlockPosPacking.y(p);
            int z = BlockPosPacking.z(p);
            double dx = me.getX() - (x + 0.5);
            double dy = me.getY() - (y + 0.5);
            double dz = me.getZ() - (z + 0.5);
            if (dx * dx + dy * dy + dz * dz > StealthMath.SCULK_RADIUS * StealthMath.SCULK_RADIUS) {
                sculkHear[i] = 0;
            } else {
                sculkHear[i] = StealthMath.vibrationOccluded(px, py, pz, x, y, z, wool) ? (byte) 2 : (byte) 1;
            }
        }
    }

    // ---- render --------------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        LocalPlayer me = Minecraft.getInstance().player;
        if (me == null) {
            return;
        }
        LineSink s = sink.bind(ctx);
        float pt = ctx.partialTick;
        for (int i = 0; i < count; i++) {
            LivingEntity e = src[i];
            if (e == null) {
                continue;
            }
            float x = ctx.rx(Mth.lerp(pt, e.xo, e.getX()));
            float y = ctx.ry(Mth.lerp(pt, e.yo, e.getY())) + 0.05f;
            float z = ctx.rz(Mth.lerp(pt, e.zo, e.getZ()));
            int c = alert[i] ? ALERT : CALM;
            switch (kind[i]) {
                case KIND_WARDEN -> {
                    Shapes.ring(s, x, y, z, StealthMath.WARDEN_LISTEN_RADIUS, c);
                    emitFacing(s, x, y, z, yawRad[i], 4f, c);
                }
                case KIND_BRUTE -> {
                    Shapes.ring(s, x, y, z, StealthMath.BRUTE_RADIUS, c);
                    emitFacing(s, x, y, z, yawRad[i], 3f, c);
                }
                case KIND_PLAYER -> Shapes.fan(s, x, y, z, yawRad[i], fovRad, playerRange, c);
                case KIND_ENDERMAN -> emitGazeRing(s, x, y - 0.05f + e.getEyeHeight(), z, ctx, gaze[i], c);
                default -> {
                }
            }
        }
        if (sculk) {
            float myX = ctx.rx(me.getX());
            float myY = ctx.ry(me.getY() + 0.1);
            float myZ = ctx.rz(me.getZ());
            for (int i = 0; i < sculkCount; i++) {
                long p = sculkPos[i];
                float x = ctx.rx(BlockPosPacking.x(p) + 0.5);
                float y = ctx.ry(BlockPosPacking.y(p) + 0.5);
                float z = ctx.rz(BlockPosPacking.z(p) + 0.5);
                byte h = sculkHear[i];
                Shapes.sphereOutline(s, x, y, z, StealthMath.SCULK_RADIUS, h == 1 ? ALERT : SCULK_COLOR);
                if (h != 0) {
                    s.line(x, y, z, myX, myY, myZ, h == 1 ? ALERT : MUFFLED);
                }
            }
        }
    }

    /** Facing tick on the ground along Minecraft yaw. */
    static void emitFacing(LineSink s, float x, float y, float z, float yaw, float len, int argb) {
        s.line(x, y, z, x - (float) Math.sin(yaw) * len, y, z + (float) Math.cos(yaw) * len, argb);
    }

    /** Ring of the stare cone around the enderman's eye, in the plane facing the camera (16 segments). */
    static void emitGazeRing(LineSink s, float x, float y, float z, RenderContext ctx, float r, int argb) {
        // Camera is at the origin in camera-relative space: the ring's normal is the eye direction.
        float len = (float) Math.sqrt(x * x + y * y + z * z);
        if (len < 1.0e-3f) {
            return;
        }
        float nx = x / len;
        float ny = y / len;
        float nz = z / len;
        // u = normalize(n x up), v = u x n.
        float ux = -nz;
        float uz = nx;
        float ul = (float) Math.sqrt(ux * ux + uz * uz);
        if (ul < 1.0e-3f) {
            ux = 1f;
            uz = 0f;
            ul = 1f;
        }
        ux /= ul;
        uz /= ul;
        float vx = -uz * ny;
        float vy = uz * nx - ux * nz;
        float vz = ux * ny;
        float px = x + ux * r;
        float py = y;
        float pz = z + uz * r;
        for (int k = 1; k <= 16; k++) {
            double a = Math.PI * 2.0 * k / 16;
            float c = (float) Math.cos(a) * r;
            float sn = (float) Math.sin(a) * r;
            float qx = x + ux * c + vx * sn;
            float qy = y + vy * sn;
            float qz = z + uz * c + vz * sn;
            s.line(px, py, pz, qx, qy, qz, argb);
            px = qx;
            py = qy;
            pz = qz;
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        for (int i = 0; i < count; i++) {
            LivingEntity e = src[i];
            if (e == null || kind[i] != KIND_WARDEN) {
                continue;
            }
            String t = WorldRenderKit.number(anger[i]);
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(e.getX()), ctx.ry(e.getY() + e.getBbHeight() + 0.6),
                    ctx.rz(e.getZ()));
            WorldRenderKit.text(ctx.font, ctx.buffers, m, t, -ctx.font.width(t) / 2f, alert[i] ? ALERT : CALM);
        }
    }
}
