package com.utilitysuite.world.lighting;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.Shapes;
import it.unimi.dsi.fastutil.longs.Long2ByteOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ByteMap;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.NaturalSpawner;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * Light-level overlay: marks floor blocks where a hostile mob could spawn. Red crosses spawn at any time (no block
 * light, no sky light); yellow crosses spawn only at night (sky-lit). The scan is budgeted on the client tick
 * ({@link ColumnScanScheduler}: at most {@code budget} columns per tick), restarted when the player moves a few blocks
 * or the world changes nearby, and kept in a packed-long map. The frame only walks a flat array rebuilt on change.
 */
public final class LightOverlayModule extends WorldModule {

    public static final String ID = "light_overlay";
    private static final int COLOR_ALWAYS = 0xE0FF3030;
    private static final int COLOR_NIGHT = 0xC0FFD030;
    private static final int RESTART_DELAY_TICKS = 5;
    private static final int MOVE_RESTART = 4;

    private final ColumnScanScheduler scheduler = new ColumnScanScheduler();
    /** Packed floor position -> SpawnableRule kind. Client thread only. */
    private final Long2ByteOpenHashMap marks = new Long2ByteOpenHashMap();
    private final BlockPos.MutableBlockPos floor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos feet = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos head = new BlockPos.MutableBlockPos();
    private final PoseLineSink sink = new PoseLineSink();

    private long[] renderPos = new long[1024];
    private byte[] renderKind = new byte[1024];
    private int renderCount;
    private boolean renderDirty;

    private int radius;
    private int below;
    private int above;
    private int threshold;
    private boolean showNight;
    private int budget;
    private int maxMarks;

    private int ticks;
    private int restartAt = -1;
    private int scanY;
    private ClientLevel scanLevel;

    public LightOverlayModule() {
        super(ID, "Light Levels", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("radius", "Radius", 16, 4, 48, "Scan", "Columns around you that are checked."),
                SettingSpec.integer("below", "Depth below", 8, 1, 32, "Scan", "Blocks checked below your feet."),
                SettingSpec.integer("above", "Height above", 4, 1, 32, "Scan", "Blocks checked above your feet."),
                SettingSpec.integer("block_threshold", "Block light threshold", 0, 0, 15, "Rule",
                        "Highest block light that still counts as spawnable (vanilla: 0)."),
                SettingSpec.bool("show_night", "Night-only spots", true, "Rule",
                        "Also mark sky-lit spots (spawnable at night or in a storm) in yellow."),
                SettingSpec.integer("budget", "Columns per tick", 96, 16, 1024, "Performance",
                        "Scan budget; the overlay fills in over a few ticks.").asAdvanced(),
                SettingSpec.integer("max_marks", "Max marks drawn", 4096, 256, 16384, "Performance",
                        "Cap on drawn crosses.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        radius = integer(p, "radius");
        below = integer(p, "below");
        above = integer(p, "above");
        threshold = integer(p, "block_threshold");
        showNight = bool(p, "show_night");
        budget = integer(p, "budget");
        maxMarks = integer(p, "max_marks");
        restartAt = ticks; // settings changed: rescan from scratch
        marks.clear();
        renderDirty = true;
    }

    @Override
    protected void onEnable() {
        restartAt = ticks;
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @Override
    public void onWorldUnload() {
        reset();
    }

    private void reset() {
        scheduler.clear();
        marks.clear();
        renderCount = 0;
        scanLevel = null;
        restartAt = -1;
    }

    // ---- change hooks (client thread) ---------------------------------------------------------------------------

    /** Block update near the player: rescan the 3x3 columns now and everything shortly (light spreads, and lags). */
    public void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        if (!isEnabled()) {
            return;
        }
        int x = pos.getX();
        int z = pos.getZ();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                scheduler.markDirty(x + dx, z + dz);
            }
        }
        if (oldState.getLightEmission() != newState.getLightEmission() || oldState.canOcclude() != newState
                .canOcclude()) {
            requestRestart();
        }
    }

    /** Chunk arrived near the player. */
    public void onChunkLoaded(int chunkX, int chunkZ) {
        if (!isEnabled() || scanLevel == null) {
            return;
        }
        int cx = scheduler.centerX() >> 4;
        int cz = scheduler.centerZ() >> 4;
        int rc = (radius >> 4) + 1;
        if (Math.abs(chunkX - cx) <= rc && Math.abs(chunkZ - cz) <= rc) {
            requestRestart();
        }
    }

    private void requestRestart() {
        if (restartAt < 0 || restartAt > ticks + RESTART_DELAY_TICKS) {
            restartAt = ticks + RESTART_DELAY_TICKS;
        }
    }

    // ---- tick ---------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        ticks++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        int px = player.getBlockX();
        int py = player.getBlockY();
        int pz = player.getBlockZ();
        if (level != scanLevel) {
            reset();
            scanLevel = level;
            restartAt = ticks;
        }
        if (Math.abs(px - scheduler.centerX()) > MOVE_RESTART || Math.abs(pz - scheduler.centerZ()) > MOVE_RESTART
                || Math.abs(py - scanY) > MOVE_RESTART) {
            restartAt = ticks;
        }
        if (restartAt >= 0 && ticks >= restartAt) {
            restartAt = -1;
            scanY = py;
            scheduler.restart(px, pz, radius);
            pruneOutside();
        }
        scheduler.beginTick(budget);
        while (scheduler.next()) {
            scanColumn(level, scheduler.x(), scheduler.z());
        }
        if (renderDirty) {
            rebuildRenderArray();
        }
    }

    private void scanColumn(ClientLevel level, int x, int z) {
        int minY = Math.max(level.getMinY(), scanY - below);
        int maxY = Math.min(level.getMaxY() - 2, scanY + above);
        for (int y = minY; y <= maxY; y++) {
            long key = BlockPosPacking.pack(x, y, z);
            int kind = classifyFloor(level, x, y, z);
            if (kind == SpawnableRule.NIGHT && !showNight) {
                kind = SpawnableRule.NONE;
            }
            if (kind == SpawnableRule.NONE) {
                if (marks.remove(key) != 0) {
                    renderDirty = true;
                }
            } else if (marks.put(key, (byte) kind) != kind) {
                renderDirty = true;
            }
        }
    }

    private int classifyFloor(ClientLevel level, int x, int y, int z) {
        floor.set(x, y, z);
        if (!level.isLoaded(floor)) {
            return SpawnableRule.NONE;
        }
        BlockState fs = level.getBlockState(floor);
        if (fs.isAir()) {
            return SpawnableRule.NONE;
        }
        feet.set(x, y + 1, z);
        BlockState feetState = level.getBlockState(feet);
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, feet, feetState, feetState.getFluidState(),
                EntityTypes.ZOMBIE)) {
            return SpawnableRule.NONE;
        }
        head.set(x, y + 2, z);
        BlockState headState = level.getBlockState(head);
        if (!NaturalSpawner.isValidEmptySpawnBlock(level, head, headState, headState.getFluidState(),
                EntityTypes.ZOMBIE)) {
            return SpawnableRule.NONE;
        }
        boolean floorValid = fs.isValidSpawn(level, floor, EntityTypes.ZOMBIE);
        return SpawnableRule.classify(floorValid, true, true, level.getBrightness(LightLayer.BLOCK, feet),
                level.getBrightness(LightLayer.SKY, feet), threshold);
    }

    /** Drops marks outside the new scan square (not a hot path: once per restart). */
    private void pruneOutside() {
        int cx = scheduler.centerX();
        int cz = scheduler.centerZ();
        int r = scheduler.radius();
        int minY = scanY - below;
        int maxY = scanY + above;
        ObjectIterator<Long2ByteMap.Entry> it = marks.long2ByteEntrySet().fastIterator();
        while (it.hasNext()) {
            long k = it.next().getLongKey();
            int y = BlockPosPacking.y(k);
            if (Math.abs(BlockPosPacking.x(k) - cx) > r || Math.abs(BlockPosPacking.z(k) - cz) > r || y < minY
                    || y > maxY) {
                it.remove();
                renderDirty = true;
            }
        }
    }

    private void rebuildRenderArray() {
        renderDirty = false;
        int n = Math.min(marks.size(), maxMarks);
        if (renderPos.length < n) {
            int cap = Math.max(n, renderPos.length * 2);
            renderPos = new long[cap];
            renderKind = new byte[cap];
        }
        int i = 0;
        ObjectIterator<Long2ByteMap.Entry> it = marks.long2ByteEntrySet().fastIterator();
        while (it.hasNext() && i < n) {
            Long2ByteMap.Entry e = it.next();
            renderPos[i] = e.getLongKey();
            renderKind[i] = e.getByteValue();
            i++;
        }
        renderCount = i;
    }

    // ---- rendering ----------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        int n = renderCount;
        if (n == 0) {
            return;
        }
        emit(sink.bind(ctx), renderPos, renderKind, n, ctx.camX, ctx.camY, ctx.camZ);
    }

    /** Per-frame emitter (allocation-free; tested with a counting sink). */
    static void emit(com.utilitysuite.world.render.LineSink s, long[] pos, byte[] kind, int n, double camX,
                     double camY, double camZ) {
        for (int i = 0; i < n; i++) {
            long p = pos[i];
            float x = (float) (BlockPosPacking.x(p) - camX);
            float y = (float) (BlockPosPacking.y(p) + 1.02 - camY);
            float z = (float) (BlockPosPacking.z(p) - camZ);
            Shapes.cross(s, x, y, z, kind[i] == SpawnableRule.ALWAYS ? COLOR_ALWAYS : COLOR_NIGHT);
        }
    }

    /** For tests and diagnostics. */
    int markCount() {
        return marks.size();
    }
}
