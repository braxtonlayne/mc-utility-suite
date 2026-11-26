package com.utilitysuite.world.overlays;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.List;

/**
 * Time-slice projectile ribbon: for the held pearl, wind charge, bow, crossbow, trident, snowball or egg, the predicted
 * flight as a soft camera-facing ribbon (the Shell's world kit) with a chevron every N ticks pointing along the flight,
 * an impact ring where it hits, and for an ender pearl a ghost silhouette (legs / body / head boxes) standing where
 * you would land. Physics are {@link TrajectoryIntegrator}'s (shared with the World Radar aim arc); all points,
 * chevrons and boxes go into preallocated arrays. Drawn in {@link SubmitCustomGeometryEvent} (the Shell world kit pass, batched by
 * the kit); nothing when nothing throwable is held or in Clean-Screen. Off by default.
 */
public final class ProjectileRibbonModule extends WorldModule {

    public static final String ID = "projectile_ribbon";
    private static final int MAX_STEPS = 160;

    private final TrajectoryIntegrator sim = new TrajectoryIntegrator(MAX_STEPS);
    private final HeldProjectile held = new HeldProjectile();
    private final float[] path = new float[(MAX_STEPS + 1) * 3];
    private final float[] chevrons = new float[(MAX_STEPS / 1 + 1) * RibbonGeometry.CHEVRON_SEGS * RibbonGeometry.SEG];
    private final float[] ghost = new float[RibbonGeometry.GHOST_BOXES * RibbonGeometry.BOX];
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final TrajectoryIntegrator.Collider collider = this::collide;
    private ClientLevel colliderLevel;

    private int chevronEvery = 2;
    private boolean showGhost = true;
    private boolean showRing = true;
    private float opacity = 0.85f;

    public ProjectileRibbonModule() {
        super(ID, "Projectile Ribbon", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("chevron_ticks", "Chevron every (ticks)", 2, 1, 10, "Display",
                        "A chevron marks where the projectile is every this many game ticks."),
                SettingSpec.bool("ghost", "Pearl landing ghost", true, "Display",
                        "A ghost figure stands where a thrown ender pearl would put you."),
                SettingSpec.bool("impact_ring", "Impact ring", true, "Display", "A ring where the projectile hits."),
                SettingSpec.decimal("opacity", "Opacity", 0.85, 0.2, 1.0, "Display", "How solid the ribbon is."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        chevronEvery = integer(p, "chevron_ticks");
        showGhost = bool(p, "ghost");
        showRing = bool(p, "impact_ring");
        opacity = (float) decimal(p, "opacity");
    }

    @Override
    public void onTick() {
        // all work happens in the frame (the path follows the interpolated look); nothing to do per tick
    }

    public void onRenderLevel(SubmitCustomGeometryEvent event) {
        if (!isEnabled()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null || !held.read(self)) {
            return;
        }
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        if (world == null) {
            return; // no world pass: Clean-Screen or no Shell
        }
        float pt = event.getLevelRenderState().worldPartialTicks;
        double x = Mth.lerp(pt, self.xOld, self.getX());
        double y = Mth.lerp(pt, self.yOld, self.getY()) + self.getEyeHeight() - 0.1;
        double z = Mth.lerp(pt, self.zOld, self.getZ());
        Vec3 motion = self.getDeltaMovement();
        colliderLevel = level;
        sim.simulate(held.kind, x, y, z, self.getViewYRot(pt), self.getViewXRot(pt), held.power, motion.x,
                self.onGround() ? 0.0 : motion.y, motion.z, collider);
        colliderLevel = null;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        int n = RibbonGeometry.path(sim, cam.x, cam.y, cam.z, path);
        if (n < 2) {
            return;
        }
        HoloToken tone = held.pearl ? HoloToken.ACCENT : HoloToken.CAT_COMBAT;
        world.polyline(path, n, 0f, tone, opacity);
        int segs = RibbonGeometry.chevrons(path, n, chevronEvery, 0.3f, chevrons);
        for (int s = 0; s < segs; s++) {
            int o = s * RibbonGeometry.SEG;
            float a = opacity * RibbonGeometry.fade(s / RibbonGeometry.CHEVRON_SEGS * chevronEvery, n);
            world.ribbon(chevrons[o], chevrons[o + 1], chevrons[o + 2], chevrons[o + 3], chevrons[o + 4],
                    chevrons[o + 5], HoloToken.ACCENT_GLOW, a);
        }
        if (!sim.hit()) {
            return;
        }
        float hx = (float) (sim.hitX() - cam.x);
        float hy = (float) (sim.hitY() - cam.y);
        float hz = (float) (sim.hitZ() - cam.z);
        if (showRing) {
            world.ring(hx, hy + 0.02f, hz, 0.45f, HoloToken.WARN, opacity);
            world.ring(hx, hy + 0.02f, hz, 0.2f, HoloToken.WARN, opacity * 0.6f);
        }
        if (showGhost && held.pearl) {
            RibbonGeometry.ghost(hx, hy, hz, ghost);
            for (int b = 0; b < RibbonGeometry.GHOST_BOXES; b++) {
                int o = b * RibbonGeometry.BOX;
                world.boxEdges(ghost[o], ghost[o + 1], ghost[o + 2], ghost[o + 3], ghost[o + 4], ghost[o + 5],
                        HoloToken.ALLY, opacity * 0.55f);
            }
        }
    }

    private int collide(int x, int y, int z) {
        ClientLevel level = colliderLevel;
        BlockState s = level.getBlockState(probe.set(x, y, z));
        if (s.isAir()) {
            return TrajectoryIntegrator.Collider.FREE;
        }
        boolean empty = s.getCollisionShape(level, probe).isEmpty();
        if (empty && s.getFluidState().is(FluidTags.WATER)) {
            return TrajectoryIntegrator.Collider.WATER;
        }
        return empty ? TrajectoryIntegrator.Collider.FREE : TrajectoryIntegrator.Collider.SOLID;
    }
}
