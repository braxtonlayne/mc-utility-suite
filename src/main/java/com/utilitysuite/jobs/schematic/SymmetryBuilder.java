package com.utilitysuite.jobs.schematic;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.IBlockChangeFeed;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.jobs.schematic.gen.SymmetryTransform;
import com.utilitysuite.jobs.schematic.model.BlockStateString;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Symmetry builder (off by default): around a centre block (the crosshair block when switched on, or "Set centre"),
 * watches the player's own placements through the block-change feed and adds their mirrored / rotated counterparts
 * ({@link SymmetryTransform.Mode}) to a live layer. The layer is handed to the Schematic Toolkit as the generated design
 * {@code generated:symmetry}, so the existing ghost preview outlines it and Build Assist fills it as you walk past.
 * Mirror planes / radial guides are drawn in one line batch. Removals are not mirrored.
 * <p>
 * The feed listener only queues positions (amortised array append); the work happens on the next tick, and the layer
 * volume is built on the schematic thread after a short debounce.
 */
public final class SymmetryBuilder implements IModule, IConfigurable {

    public static final String ID = "symmetry_builder";
    public static final String KEY = "symmetry";
    /** Ticks without new placements before the layer is republished to Build Assist. */
    static final int DEBOUNCE_TICKS = 20;
    /** Placements farther than this from the player are not the player's own (server-side changes). */
    static final double OWN_REACH = 6.5;

    public record Settings(SymmetryTransform.Mode mode, int radius, boolean guides) {
        static final Settings DEFAULTS = new Settings(SymmetryTransform.Mode.MIRROR_X, 24, true);

        static Settings read(ProfileConfig p) {
            return new Settings(SymmetryTransform.Mode.parse(p.getString(ID, "mode", DEFAULTS.mode.name())),
                    Math.max(4, Math.min(64, p.getInt(ID, "radius", DEFAULTS.radius))),
                    p.getBool(ID, "guides", DEFAULTS.guides));
        }
    }

    private boolean enabled;
    private Settings settings = Settings.DEFAULTS;
    private long centre = Long.MIN_VALUE;
    private final LongArrayList pending = new LongArrayList();
    private final Long2ObjectOpenHashMap<String> layer = new Long2ObjectOpenHashMap<>();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final IBlockChangeFeed.Listener listener = this::onBlockChanged;
    private boolean subscribed;
    private int debounce = -1;
    private String status = "";

    public SymmetryBuilder() {
        applySettings();
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        IBlockChangeFeed feed = Services.blockChangesOrNull();
        if (on && feed != null && !subscribed) {
            feed.subscribe(listener);
            subscribed = true;
        } else if (!on) {
            if (feed != null && subscribed) {
                feed.unsubscribe(listener);
            }
            subscribed = false;
            reset();
        }
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        if (centre == Long.MIN_VALUE) {
            setCentreFromCrosshair();
        }
        int n = pending.size();
        for (int i = 0; i < n; i++) {
            mirror(mc, pending.getLong(i));
        }
        pending.clear();
        if (debounce > 0 && --debounce == 0) {
            publish();
        }
    }

    @Override
    public void onStopAll() {
        pending.clear();
    }

    @Override
    public void onWorldUnload() {
        reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        applySettings();
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Symmetry Builder";
    }

    @Override
    public List<SettingSpec> settings() {
        Settings d = Settings.DEFAULTS;
        return List.of(
                SettingSpec.choice("mode", "Mode", d.mode, "Symmetry",
                        "Mirror X / Z / both, or radial 4x / 8x (8x adds the mirrors of each rotation)"),
                SettingSpec.integer("radius", "Region radius", d.radius, 4, 64, "Symmetry",
                        "Placements within this many blocks of the centre are mirrored"),
                SettingSpec.bool("guides", "Show guides", d.guides, "Symmetry",
                        "Draw the mirror planes / radial guide lines at the centre"));
    }

    @Override
    public void onSettingChanged(String key) {
        Settings before = settings;
        applySettings();
        if (before.mode() != settings.mode()) {
            layer.clear(); // a new mode starts a new layer
            debounce = -1;
        }
    }

    private void applySettings() {
        IModState s = modState();
        settings = s == null ? Settings.DEFAULTS : Settings.read(s.profile());
    }

    private static IModState modState() {
        try {
            return Services.modState();
        } catch (NullPointerException notRegistered) {
            return null;
        }
    }

    // ---- public API (UI) --------------------------------------------------------------------------------------------

    public Settings currentSettings() {
        return settings;
    }

    /** Centre as {@code BlockPos.asLong}, or {@link Long#MIN_VALUE} when not set. */
    public long centre() {
        return centre;
    }

    public int layerSize() {
        return layer.size();
    }

    public String status() {
        return status;
    }

    /** Sets the centre to the crosshair block (or the block under the player) and starts a new layer. */
    public void setCentreFromCrosshair() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        HitResult hit = mc.hitResult;
        BlockPos pos = hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK ? b.getBlockPos()
                : mc.player.blockPosition().below();
        centre = pos.asLong();
        layer.clear();
        debounce = -1;
        status = "Centre " + pos.getX() + " " + pos.getY() + " " + pos.getZ();
    }

    /** Drops the layer (and its build) but keeps the centre. */
    public void clearLayer() {
        layer.clear();
        pending.clear();
        debounce = -1;
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t != null && t.isBuildingGenerated(KEY)) {
            t.cancel();
        }
    }

    private void reset() {
        clearLayer();
        centre = Long.MIN_VALUE;
        status = "";
    }

    // ---- placement watch --------------------------------------------------------------------------------------------

    /** Feed listener: cheap filter, then queue. */
    private void onBlockChanged(long packed) {
        long c = centre;
        if (!enabled || c == Long.MIN_VALUE) {
            return;
        }
        int r = settings.radius();
        if (Math.abs(BlockPos.getX(packed) - BlockPos.getX(c)) > r || Math.abs(BlockPos.getZ(packed) - BlockPos.getZ(c)) > r
                || Math.abs(BlockPos.getY(packed) - BlockPos.getY(c)) > r) {
            return;
        }
        pending.add(packed);
    }

    private void mirror(Minecraft mc, long packed) {
        if (layer.containsKey(packed)) {
            return; // a counterpart being filled (by Build Assist or by hand): never mirrored again
        }
        cursor.set(BlockPos.getX(packed), BlockPos.getY(packed), BlockPos.getZ(packed));
        BlockState st = mc.level.getBlockState(cursor);
        if (st.isAir() || mc.player.distanceToSqr(cursor.getX() + 0.5, cursor.getY() + 0.5, cursor.getZ() + 0.5)
                > OWN_REACH * OWN_REACH) {
            return;
        }
        String state = BlockStateString.canonical(BlockStateParser.serialize(st));
        int cx = BlockPos.getX(centre), cz = BlockPos.getZ(centre);
        int dx = cursor.getX() - cx, dz = cursor.getZ() - cz;
        for (int t : settings.mode().images()) {
            long target = BlockPos.asLong(cx + SymmetryTransform.tx(t, dx, dz), cursor.getY(),
                    cz + SymmetryTransform.tz(t, dx, dz));
            if (target != packed) {
                layer.put(target, SymmetryTransform.state(t, state));
            }
        }
        debounce = DEBOUNCE_TICKS;
    }

    /** Builds the layer volume off-thread and hands it to the toolkit (unless another schematic job is running). */
    private void publish() {
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t == null || layer.isEmpty()) {
            return;
        }
        if (t.jobCard().isPresent() && !t.isBuildingGenerated(KEY)) {
            status = "Another schematic job is running: " + layer.size() + " counterparts wait";
            return;
        }
        long[] keys = layer.keySet().toLongArray();
        String[] states = new String[keys.length];
        for (int i = 0; i < keys.length; i++) {
            states[i] = layer.get(keys[i]);
        }
        long anchor = centre;
        Minecraft mc = Minecraft.getInstance();
        t.library().submit(() -> layerVolume(anchor, keys, states)).whenComplete((v, err) -> mc.execute(() -> {
            if (err == null && v != null && enabled && anchor == centre) {
                t.replaceGenerated(KEY, v, anchor);
                status = layer.size() + " counterpart" + (layer.size() == 1 ? "" : "s") + " queued for Build Assist";
            }
        }));
    }

    /** Sparse layer to a volume anchored at the centre (offset = min corner relative to it). */
    static SchematicVolume layerVolume(long anchor, long[] keys, String[] states) {
        int ax = BlockPos.getX(anchor), ay = BlockPos.getY(anchor), az = BlockPos.getZ(anchor);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (long k : keys) {
            int x = BlockPos.getX(k) - ax, y = BlockPos.getY(k) - ay, z = BlockPos.getZ(k) - az;
            minX = Math.min(minX, x); minY = Math.min(minY, y); minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x); maxY = Math.max(maxY, y); maxZ = Math.max(maxZ, z);
        }
        SchematicVolume.Builder b = SchematicVolume.builder(KEY, "symmetry", maxX - minX + 1, maxY - minY + 1,
                maxZ - minZ + 1).offset(minX, minY, minZ);
        for (int i = 0; i < keys.length; i++) {
            long k = keys[i];
            b.set(BlockPos.getX(k) - ax - minX, BlockPos.getY(k) - ay - minY, BlockPos.getZ(k) - az - minZ, states[i]);
        }
        return b.build();
    }

    // ---- guides -----------------------------------------------------------------------------------------------------

    void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !settings.guides() || centre == Long.MIN_VALUE
                || event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        IModState state = modState();
        if (state != null && state.isCleanScreen()) {
            return;
        }
        Vec3 cam = event.getCamera().getPosition();
        MultiBufferSource.BufferSource buffers = Minecraft.getInstance().renderBuffers().bufferSource();
        VertexConsumer lines = buffers.getBuffer(RenderType.lines());
        PoseStack pose = event.getPoseStack();
        pose.pushPose();
        pose.translate(-cam.x, -cam.y, -cam.z);
        double cx = BlockPos.getX(centre) + 0.5, cy = BlockPos.getY(centre) + 1.02, cz = BlockPos.getZ(centre) + 0.5;
        int r = settings.radius();
        SymmetryTransform.Mode m = settings.mode();
        boolean planeX = m != SymmetryTransform.Mode.MIRROR_Z; // the x = centre plane (mirror X)
        boolean planeZ = m != SymmetryTransform.Mode.MIRROR_X;
        if (planeX) {
            LevelRenderer.renderLineBox(pose, lines, cx, cy - 1, cz - r, cx, cy + 1, cz + r, 1f, 0.6f, 0.2f, 0.9f);
        }
        if (planeZ) {
            LevelRenderer.renderLineBox(pose, lines, cx - r, cy - 1, cz, cx + r, cy + 1, cz, 0.2f, 0.7f, 1f, 0.9f);
        }
        if (m == SymmetryTransform.Mode.RADIAL_8) {
            float d = (float) (r * 0.7071);
            line(pose, lines, cx - d, cy, cz - d, cx + d, cy, cz + d);
            line(pose, lines, cx - d, cy, cz + d, cx + d, cy, cz - d);
        }
        LevelRenderer.renderLineBox(pose, lines, cx - 0.5, cy - 1.02, cz - 0.5, cx + 0.5, cy - 0.02, cz + 0.5, 1f, 1f,
                1f, 1f);
        pose.popPose();
        buffers.endBatch(RenderType.lines());
    }

    private static void line(PoseStack pose, VertexConsumer c, double x0, double y0, double z0, double x1, double y1,
                             double z1) {
        PoseStack.Pose last = pose.last();
        float nx = (float) (x1 - x0), nz = (float) (z1 - z0);
        float len = (float) Math.sqrt(nx * nx + nz * nz);
        nx /= len;
        nz /= len;
        c.addVertex(last, (float) x0, (float) y0, (float) z0).setColor(0.7f, 0.5f, 1f, 0.9f).setNormal(last, nx, 0, nz);
        c.addVertex(last, (float) x1, (float) y1, (float) z1).setColor(0.7f, 0.5f, 1f, 0.9f).setNormal(last, nx, 0, nz);
    }
}
