package com.utilitysuite.world.sonar;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldExecutor;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;

import java.util.List;

/**
 * Cavity tomography: finds enclosed air voids below the surface in the loaded chunks around the player (air not
 * connected to the sky inside the scan box) and draws each as a wireframe contour coloured by depth (cyan near the
 * surface, red deep down), fading with distance. The box is copied into a reused voxel grid a few sections per tick,
 * then flood-filled off-thread ({@link CavityTomography}); the result is an immutable segment list. Rescans when the
 * player enters another chunk (optional) or on the rescan key. Default off.
 */
public final class CavityTomographyModule extends WorldModule {

    public static final String ID = "cavity_tomography";

    private final VoxelGrid grid = new VoxelGrid();
    private final GridCapture capture = new GridCapture(grid);
    /** Worker-owned (one scan at a time). */
    private final CavityTomography tomography = new CavityTomography();
    private final PoseLineSink sink = new PoseLineSink();
    private KeyMapping key;

    private volatile CavityTomography.Model model = CavityTomography.Model.EMPTY;
    private volatile boolean busy;
    private boolean scanRequested;
    private int lastChunkX = Integer.MIN_VALUE;
    private int lastChunkZ = Integer.MIN_VALUE;
    private ClientLevel lastLevel;

    private int chunkRadius;
    private int depthBelow;
    private int heightAbove;
    private int minVoid;
    private int maxEdges;
    private boolean rescanOnMove;
    private int sectionsPerTick;

    public CavityTomographyModule() {
        super(ID, "Cavity Tomography", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("chunk_radius", "Chunk radius", 2, 1, 4, "Scan",
                        "Loaded chunks around you that are searched for voids."),
                SettingSpec.integer("depth_below", "Depth below", 48, 16, 128, "Scan",
                        "Blocks searched below your feet."),
                SettingSpec.integer("height_above", "Height above", 16, 0, 64, "Scan",
                        "Blocks searched above your feet."),
                SettingSpec.integer("min_void", "Smallest void", 24, 2, 1000, "Scan",
                        "Voids with fewer air blocks are ignored."),
                SettingSpec.bool("rescan_on_move", "Rescan on chunk change", true, "Scan",
                        "Rescan when you walk into another chunk (otherwise only on enable or the rescan key)."),
                SettingSpec.integer("max_edges", "Max edges", 12000, 1000, 60000, "Performance",
                        "Cap on contour edges per scan.").asAdvanced(),
                SettingSpec.integer("sections_per_tick", "Sections per tick", 32, 4, 256, "Performance",
                        "Chunk sections (16x16x16) copied per tick while scanning.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        chunkRadius = integer(p, "chunk_radius");
        depthBelow = integer(p, "depth_below");
        heightAbove = integer(p, "height_above");
        minVoid = integer(p, "min_void");
        rescanOnMove = bool(p, "rescan_on_move");
        maxEdges = integer(p, "max_edges");
        sectionsPerTick = integer(p, "sections_per_tick");
        scanRequested = true;
    }

    public void setKey(KeyMapping rescanKey) {
        this.key = rescanKey;
    }

    @Override
    protected void onEnable() {
        scanRequested = true;
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
        model = CavityTomography.Model.EMPTY;
        lastChunkX = Integer.MIN_VALUE;
        lastLevel = null;
    }

    public void rescan() {
        scanRequested = true;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            return;
        }
        while (key != null && key.consumeClick()) {
            scanRequested = true;
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        if (level != lastLevel) {
            lastLevel = level;
            model = CavityTomography.Model.EMPTY;
            scanRequested = true;
        } else if ((cx != lastChunkX || cz != lastChunkZ) && rescanOnMove) {
            scanRequested = true;
        }
        if (scanRequested && !busy && !capture.running()) {
            scanRequested = false;
            lastChunkX = cx;
            lastChunkZ = cz;
            int y = player.getBlockY();
            capture.begin(level, cx, cz, chunkRadius, (y - depthBelow) >> 4, (y + heightAbove) >> 4);
        }
        if (capture.running() && capture.step(sectionsPerTick)) {
            busy = true;
            final int min = minVoid;
            final int cap = maxEdges;
            WorldExecutor.get().execute(() -> {
                try {
                    model = tomography.scan(grid, min, cap);
                } finally {
                    busy = false;
                }
            });
        }
    }

    @Override
    public void renderLines(RenderContext ctx) {
        CavityTomography.Model m = model;
        if (m.segments() == 0) {
            return;
        }
        m.emit(sink.bind(ctx), ctx.camX, ctx.camY, ctx.camZ, chunkRadius * 16 + 24);
    }

    /** For tests and diagnostics. */
    CavityTomography.Model model() {
        return model;
    }
}
