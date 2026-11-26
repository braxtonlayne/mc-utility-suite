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
import net.minecraft.client.multiplayer.ClientLevel;
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
     * LiquidBlockSolidSurfaceMixin: a fluid source the local player stands on becomes a full block while their feet are
     * above it (SOLID_COLLISION stands on the surface, not half in it). 26.3's {@code LiquidBlock.getCollisionShape}
     * takes the stand-on shape from {@code LivingEntity.getLiquidCollisionShape()}, which is empty for players (only
     * striders override it), so vanilla answers {@link Shapes#empty()} here even when {@link #standOnFluid} said yes:
     * this re-asks the same {@code canStandOnFluid} question (which reaches {@link #standOnFluid} through the fluid
     * surface mixin) for the local player over a source block. Constant shapes and one reused position, no allocation.
     */
    public static VoxelShape fluidSurfaceShape(VoxelShape original, BlockPos pos, CollisionContext context) {
        if (original != Shapes.empty() || assist == null || !(context instanceof EntityCollisionContext ec)) {
            return original;
        }
        Minecraft mc = Minecraft.getInstance();
        Entity e = ec.getEntity();
        ClientLevel level = mc.level;
        if (e == null || e != mc.player || level == null || !mc.isSameThread()) {
            return original;
        }
        FluidState fluid = level.getFluidState(pos);
        if (fluid.isEmpty() || !fluid.isSource()) {
            return original; // vanilla gives flowing fluid no surface either
        }
        ABOVE.set(pos.getX(), pos.getY() + 1, pos.getZ());
        if (!context.canStandOnFluid(level.getFluidState(ABOVE), fluid)) {
            return original;
        }
        return context.isAbove(Shapes.block(), pos, true) ? Shapes.block() : original;
    }

    /** The block above the queried fluid (client thread only, see the thread check). */
    private static final BlockPos.MutableBlockPos ABOVE = new BlockPos.MutableBlockPos();

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
