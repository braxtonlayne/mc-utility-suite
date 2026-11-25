package com.utilitysuite.world;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Static entry points the world mixins call. Keeps the mixins one-liners; the listeners are set by
 * {@link WorldBootstrap}. Every hook is a no-op until its listener is installed.
 */
public final class WorldHooks {

    /** Client block change listener (from {@code ClientLevel.sendBlockUpdated}). */
    @FunctionalInterface
    public interface BlockChangeListener {
        void onBlockChanged(BlockPos pos, BlockState oldState, BlockState newState);
    }

    /** Detached-camera mouse look: returns true when the turn was consumed (the player must not rotate). */
    @FunctionalInterface
    public interface TurnRedirect {
        boolean turn(double yRot, double xRot);
    }

    private static volatile BlockChangeListener blockChanges;
    private static volatile TurnRedirect turnRedirect;
    /** Read by the light texture mixin every frame the lightmap updates. */
    private static volatile boolean fullbright;
    /** Atmosphere studio partial lift (0..1 of the night-vision brightening), read with the fullbright flag. */
    private static volatile float lightLift;

    private WorldHooks() {
    }

    public static void setBlockChangeListener(BlockChangeListener l) {
        blockChanges = l;
    }

    public static void setTurnRedirect(TurnRedirect r) {
        turnRedirect = r;
    }

    public static void setFullbright(boolean on) {
        fullbright = on;
    }

    public static boolean fullbright() {
        return fullbright;
    }

    public static void setLightLift(float lift) {
        lightLift = lift;
    }

    /** Partial dark-area lift requested by the Atmosphere studio (0 = none). */
    public static float lightLift() {
        return lightLift;
    }

    // ---- Atmosphere studio sky / cloud recolour (read by ClientLevelSkyColorMixin, set once per tick) -------------

    private static volatile float skyR;
    private static volatile float skyG;
    private static volatile float skyB;
    private static volatile float skyT;
    private static volatile float cloudR;
    private static volatile float cloudG;
    private static volatile float cloudB;
    private static volatile float cloudT;

    /** Sky colour target ({@code 0xRRGGBB}) and blend weight 0..1 (0 = vanilla sky). */
    public static void setSkyTint(int rgb, float weight) {
        skyR = ((rgb >> 16) & 0xFF) / 255f;
        skyG = ((rgb >> 8) & 0xFF) / 255f;
        skyB = (rgb & 0xFF) / 255f;
        skyT = Math.max(0f, Math.min(1f, weight));
    }

    /** Cloud colour target and blend weight (0 = vanilla clouds). */
    public static void setCloudTint(int rgb, float weight) {
        cloudR = ((rgb >> 16) & 0xFF) / 255f;
        cloudG = ((rgb >> 8) & 0xFF) / 255f;
        cloudB = (rgb & 0xFF) / 255f;
        cloudT = Math.max(0f, Math.min(1f, weight));
    }

    public static void clearSkyTint() {
        skyT = 0f;
        cloudT = 0f;
    }

    public static float skyTintWeight() {
        return skyT;
    }

    /** One channel (0 r, 1 g, 2 b) of vanilla's sky colour blended toward the tint. No allocation. */
    public static double skyChannel(int channel, double vanilla) {
        float t = skyT;
        if (t <= 0f) {
            return vanilla;
        }
        float target = channel == 0 ? skyR : channel == 1 ? skyG : skyB;
        return vanilla + (target - vanilla) * t;
    }

    /** One channel of vanilla's cloud colour blended toward the cloud tint. No allocation. */
    public static double cloudChannel(int channel, double vanilla) {
        float t = cloudT;
        if (t <= 0f) {
            return vanilla;
        }
        float target = channel == 0 ? cloudR : channel == 1 ? cloudG : cloudB;
        return vanilla + (target - vanilla) * t;
    }

    public static void blockChanged(BlockPos pos, BlockState oldState, BlockState newState) {
        BlockChangeListener l = blockChanges;
        if (l != null && oldState != newState) {
            l.onBlockChanged(pos, oldState, newState);
        }
    }

    public static boolean redirectTurn(double yRot, double xRot) {
        TurnRedirect r = turnRedirect;
        return r != null && r.turn(yRot, xRot);
    }
}
