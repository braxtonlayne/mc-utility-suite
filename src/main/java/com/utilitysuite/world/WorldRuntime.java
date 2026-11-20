package com.utilitysuite.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.world.overlays.WorldScanner;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.event.level.ChunkEvent;

/**
 * Shared runtime of the world modules: the one chunk scanner, the one world renderer and the chunk/block event plumbing.
 * The scanner only runs while some enabled module asks for categories (its mask is the union of their
 * {@link WorldModule#scanMask()}), and the renderer draws nothing while Clean-Screen is on.
 */
public final class WorldRuntime {

    /** Section scans (4096-block iterations) per tick, and near-sweep sections per tick. */
    private static final int FULL_SCANS_PER_TICK = 6;
    private static final int NEAR_SCANS_PER_TICK = 2;

    private final WorldScanner scanner = new WorldScanner();
    private final RenderContext ctx = new RenderContext();
    private WorldModule[] modules = new WorldModule[0];
    private boolean nearCaves;
    private boolean nearFluids;
    private int nearRadius = 48;
    private java.util.Set<String> customIds = java.util.Set.of();
    private boolean trackChunkAge;

    public WorldScanner scanner() {
        return scanner;
    }

    public void add(WorldModule m) {
        WorldModule[] next = java.util.Arrays.copyOf(modules, modules.length + 1);
        next[modules.length] = m;
        modules = next;
    }

    /** Scanner options owned by the X-ray filter module. */
    public void setScanOptions(java.util.Set<String> custom, boolean caves, boolean fluids, int radius) {
        customIds = custom;
        nearCaves = caves;
        nearFluids = fluids;
        nearRadius = radius;
    }

    public void setTrackChunkAge(boolean on) {
        if (trackChunkAge != on) {
            trackChunkAge = on;
            scanner.setTrackChunkAge(on);
        }
    }

    // ---- events -------------------------------------------------------------------------------------------------

    public void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ClientLevel level) {
            scanner.onChunkLoad(level, event.getChunk());
        }
    }

    public void onChunkUnload(ChunkEvent.Unload event) {
        if (event.getLevel() instanceof ClientLevel level) {
            scanner.onChunkUnload(level, event.getChunk());
        }
    }

    /** From the block-update mixin (client thread). */
    public void onBlockChanged(net.minecraft.core.BlockPos pos, net.minecraft.world.level.block.state.BlockState o,
                               net.minecraft.world.level.block.state.BlockState n) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level != null) {
            scanner.onBlockChanged(level, pos, o, n);
        }
    }

    public void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        int mask = 0;
        boolean caves = false;
        boolean fluids = false;
        for (WorldModule m : modules) {
            if (m.isEnabled()) {
                mask |= m.scanMask();
            }
        }
        if (mask != 0) {
            caves = nearCaves;
            fluids = nearFluids;
        }
        scanner.setMask(mask, customIds);
        scanner.setNearOptions(caves, fluids, nearRadius);
        scanner.tick(level, player.getBlockX(), player.getBlockY(), player.getBlockZ(), level.getGameTime(),
                FULL_SCANS_PER_TICK, NEAR_SCANS_PER_TICK);
    }

    public void onWorldUnload() {
        scanner.reset();
    }

    public void onRender(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_WEATHER) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        IModState state = Services.modState();
        if (state.isCleanScreen()) {
            return;
        }
        WorldModule[] ms = modules;
        boolean any = false;
        for (WorldModule m : ms) {
            any |= m.isEnabled();
        }
        if (!any) {
            return;
        }
        Camera camera = event.getCamera();
        Vec3 cam = camera.getPosition();
        PoseStack poseStack = event.getPoseStack();
        ctx.pose = poseStack.last();
        ctx.camX = cam.x;
        ctx.camY = cam.y;
        ctx.camZ = cam.z;
        ctx.partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        ctx.font = mc.font;
        ctx.dimension = WorldIds.dimension(mc.level);
        ctx.buffers = ctx.kit.buffers();
        ctx.lines = ctx.kit.lines(ctx.buffers);
        for (WorldModule m : ms) {
            if (m.isEnabled()) {
                m.renderLines(ctx);
            }
        }
        ctx.kit.endLines(ctx.buffers);
        ctx.lines = null;
        for (WorldModule m : ms) {
            if (m.isEnabled()) {
                m.renderLabels(ctx);
            }
        }
        ctx.buffers.endBatch();
    }

    /** Distance cap helper shared by the renderers: squared radius as double. */
    public static double sq(int r) {
        return (double) r * r;
    }

    /** Kit accessor for modules that draw outside the shared pass (none currently). */
    public WorldRenderKit kit() {
        return ctx.kit;
    }
}
