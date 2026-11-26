package com.utilitysuite.world;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.SecondaryView;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.world.overlays.WorldScanner;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
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
    /** Modules of the frame whose line batch is pending (set in {@link #onSubmit}). */
    private WorldModule[] linesModules = new WorldModule[0];
    /** The one reused custom-geometry callback (no lambda per frame). */
    private final SubmitNodeCollector.CustomGeometryRenderer lineRenderer = this::buildLines;
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

    /**
     * 26.3 world pass: one custom-geometry submit per frame carries every module's lines (written by
     * {@link #lineRenderer} when the feature dispatcher builds the batch, later in the same frame on the render
     * thread), and the label phase submits see-through text nodes straight away. Skipped in the picture-in-picture
     * second level pass.
     */
    public void onSubmit(SubmitCustomGeometryEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || SecondaryView.active()) {
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
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        PoseStack poseStack = event.getPoseStack();
        ctx.pose = poseStack.last();
        ctx.camX = cam.x;
        ctx.camY = cam.y;
        ctx.camZ = cam.z;
        ctx.partialTick = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        ctx.font = mc.font;
        ctx.dimension = WorldIds.dimension(mc.level);
        SubmitNodeCollector collector = event.getSubmitNodeCollector();
        linesModules = ms;
        collector.submitCustomGeometry(poseStack, WorldRenderKit.linesNoDepth(), lineRenderer);
        ctx.lines = null;
        ctx.buffers.bind(collector);
        for (WorldModule m : ms) {
            if (m.isEnabled()) {
                m.renderLabels(ctx);
            }
        }
        ctx.buffers.unbind();
    }

    /** Line phase: runs when the feature dispatcher builds our batch (render thread, same frame). */
    private void buildLines(PoseStack.Pose pose, VertexConsumer buffer) {
        PoseStack.Pose labelPose = ctx.pose;
        ctx.pose = pose;
        ctx.lines = buffer;
        for (WorldModule m : linesModules) {
            if (m.isEnabled()) {
                m.renderLines(ctx);
            }
        }
        ctx.lines = null;
        ctx.pose = labelPose;
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
