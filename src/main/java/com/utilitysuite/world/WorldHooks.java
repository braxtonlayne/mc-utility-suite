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
