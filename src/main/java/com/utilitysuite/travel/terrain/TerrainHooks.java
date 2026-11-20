package com.utilitysuite.travel.terrain;

import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SuiteEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.EntityCollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Static entry points for the travel mixins (mixin classes stay tiny and logic-free). Each method returns the
 * "no change" answer while Terrain Assist is not bound or not active.
 */
public final class TerrainHooks {

    private static TerrainAssist assist;

    private static boolean correctionPending;
    private static double beforeX;
    private static double beforeY;
    private static double beforeZ;

    private TerrainHooks() {
    }

    public static void bind(TerrainAssist terrainAssist) {
        assist = terrainAssist;
    }

    /** PlayerSafeWalkMixin: the clamped movement, or {@code original} unchanged. */
    public static Vec3 backOffFromEdge(Player self, Vec3 original, Vec3 movement, MoverType mover) {
        TerrainAssist a = assist;
        if (a == null) {
            return original;
        }
        Vec3 clamped = a.backOffFromEdge(self, movement, mover);
        return clamped == null ? original : clamped;
    }

    /** LivingEntityFluidSurfaceMixin: true when the local player stands on this fluid. */
    public static boolean standOnFluid(LivingEntity self, FluidState fluid, boolean original) {
        if (original) {
            return true;
        }
        TerrainAssist a = assist;
        return a != null && a.standsOnFluid(self, fluid);
    }

    /**
     * LiquidBlockSolidSurfaceMixin: vanilla's half-height fluid top for an entity that stands on fluid becomes a full
     * block for the local player while their feet are above it (SOLID_COLLISION stands on the surface, not half in it).
     * Only reached when {@link #standOnFluid} already said yes for this player; constant shapes, no allocation.
     */
    public static VoxelShape fluidSurfaceShape(VoxelShape original, BlockPos pos, CollisionContext context) {
        if (original != LiquidBlock.STABLE_SHAPE || !(context instanceof EntityCollisionContext ec)) {
            return original;
        }
        Entity e = ec.getEntity();
        if (e == null || e != Minecraft.getInstance().player) {
            return original;
        }
        return context.isAbove(Shapes.block(), pos, true) ? Shapes.block() : original;
    }

    /**
     * ClientPacketListenerCorrectionMixin, before a server position packet is applied. The handler first runs on the
     * network thread (and bounces to the client thread), so only the client-thread call is recorded.
     */
    public static void beforeServerPosition() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (!mc.isSameThread() || p == null) {
            correctionPending = false;
            return;
        }
        beforeX = p.getX();
        beforeY = p.getY();
        beforeZ = p.getZ();
        correctionPending = true;
    }

    /** After the packet was applied: posts {@link SuiteEvent.PositionCorrection} with the applied offset. */
    public static void afterServerPosition() {
        if (!correctionPending) {
            return;
        }
        correctionPending = false;
        LocalPlayer p = Minecraft.getInstance().player;
        ISuiteEventBus events = Services.eventsOrNull();
        if (p == null || events == null) {
            return;
        }
        events.post(new SuiteEvent.PositionCorrection(System.currentTimeMillis(), p.getX() - beforeX,
                p.getY() - beforeY, p.getZ() - beforeZ));
    }
}
