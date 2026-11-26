package com.utilitysuite.travel.movement;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.travel.pursuit.MinecraftRouteRunner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;

import java.util.List;

/**
 * "Ghost Line": the path about a second ahead of the player along the route the route runner follows (with a
 * player-sized ghost outline, 0.6 x 1.8, at its end), or, when no route runs, the arc to the landing Movement Assist
 * predicts for the current jump with a bracketed ground mark there, coloured by jump quality ({@code ACCENT} good,
 * {@code WARN} marginal, {@code DANGER} miss). The samples are computed once per client tick in {@link GhostLine}; the
 * render callback only interpolates and hands segments to the Shell's world kit ({@code Services.visuals().world()}):
 * the path is a dashed ribbon whose dashes advance toward the end and whose alpha fades with distance along the path
 * (drawn band by band, each band a whole number of dash periods so the dash pattern stays continuous), over a faint
 * continuous base. The kit owns the look (ribbons, or plain lines with effects off or a shader pack), the segment cap
 * and the single batch. Off by default; nothing is drawn when neither source is active, in Clean-Screen (the kit is
 * absent then), or when the ghost is within 1.5 blocks of the camera.
 */
public final class GhostLineModule implements IModule, IConfigurable {

    public static final String ID = "ghost_line";
    public static final String K_LEAD = "lead_seconds";
    public static final String K_OPACITY = "opacity";
    public static final String K_TRAIL = "show_trail";

    private final MovementAssist movement;
    private final MinecraftRouteRunner routeRunner;
    private final GhostLine ghost = new GhostLine();
    private final long[] routeBuf = new long[RouteFollower.MAX_ROUTE];
    private final float[] pos = new float[3];
    /** The camera-relative path drawn this frame (rendered player position first). */
    private final float[] path = new float[GhostLine.MAX_TRAIL * 3];
    /** One fade band of the path. */
    private final float[] bandBuf = new float[(GhostLine.MAX_TRAIL + 2) * 3];

    private boolean enabled;
    private double leadSeconds = 1.0;
    private float opacity = 0.5f;
    private boolean showTrail = true;

    public GhostLineModule(MovementAssist movement, MinecraftRouteRunner routeRunner) {
        this.movement = movement;
        this.routeRunner = routeRunner;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            ghost.clear();
            return;
        }
        Vec3 v = p.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        if (routeRunner.isFollowingRoute()) {
            int n = routeRunner.copyRoute(routeBuf);
            ghost.updateRoute(routeBuf, n, routeRunner.routeIndex(), p.getX(), p.getY(), p.getZ(), speed,
                    (int) Math.round(leadSeconds * 20.0));
            return;
        }
        MovementAssistBrain brain = movement.brain();
        boolean moving = !p.onGround() || speed * speed > 0.0025;
        // Airborne the arc shows even when nothing survivable is reached (lethal: the DANGER mark), like Movement
        // Assist's own overlay. Gating on hasLanding alone made the lethal case unreachable (playtest find).
        if (GhostLine.showsJump(movement.isEnabled(), moving, brain.hasLanding(), p.onGround())) {
            ghost.updateJump(p.getX(), p.getY(), p.getZ(), brain.landX(), brain.landY(), brain.landZ());
            ghost.setQuality(GhostLine.quality(brain.lethal(), brain.landDamage(), brain.missDistance()));
        } else {
            ghost.clear();
        }
    }

    public void onRenderLevel(SubmitCustomGeometryEvent event) {
        if (!enabled || !ghost.active()) {
            return;
        }
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        Minecraft mc = Minecraft.getInstance();
        if (world == null || mc.player == null) {
            return; // no world pass (Clean-Screen, no Shell) or no player: nothing to draw
        }
        float pt = event.getLevelRenderState().worldPartialTicks;
        Vec3 cam = event.getLevelRenderState().cameraRenderState.pos;
        ghost.interpolate(pt, pos);
        float gx = (float) (pos[0] - cam.x);
        float gy = (float) (pos[1] - cam.y);
        float gz = (float) (pos[2] - cam.z);
        if (gx * gx + gy * gy + gz * gz < GhostLine.MIN_SHOW * GhostLine.MIN_SHOW) {
            return;
        }
        boolean jump = ghost.mode() == GhostLine.JUMP;
        int n = 0;
        if (jump || showTrail) {
            n = ghost.trailCount();
            // the first point follows the player's rendered position so the line does not step at tick rate
            path[0] = (float) (Mth.lerp(pt, mc.player.xo, mc.player.getX()) - cam.x);
            path[1] = (float) (Mth.lerp(pt, mc.player.yo, mc.player.getY()) - cam.y) + GhostLine.PATH_LIFT;
            path[2] = (float) (Mth.lerp(pt, mc.player.zo, mc.player.getZ()) - cam.z);
            for (int i = 1; i < n; i++) {
                int o = i * 3;
                path[o] = (float) (ghost.trailX(i) - cam.x);
                path[o + 1] = (float) (ghost.trailY(i) - cam.y) + GhostLine.PATH_LIFT;
                path[o + 2] = (float) (ghost.trailZ(i) - cam.z);
            }
        }
        GhostLine.draw(world, path, n, bandBuf, opacity, jump, ghost.quality(), gx, gy, gz);
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TRAVEL;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            ghost.clear();
        }
    }

    @Override
    public void onWorldUnload() {
        ghost.clear();
    }

    /** The ghost computed this tick (read-only use: playtests, diagnostics). Client thread. */
    public GhostLine ghost() {
        return ghost;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        leadSeconds = Mth.clamp(p.getDouble(ID, K_LEAD, 1.0), 0.5, 2.0);
        opacity = (float) p.getDouble(ID, K_OPACITY, 0.5);
        showTrail = p.getBool(ID, K_TRAIL, true);
    }

    @Override
    public String displayName() {
        return "Ghost Line";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal(K_LEAD, "Lead time (s)", 1.0, 0.5, 2.0, "",
                        "How far ahead of you the ghost stands, in seconds at your current speed."),
                SettingSpec.decimal(K_OPACITY, "Opacity", 0.5, 0.1, 1.0, "", "How solid the ghost is drawn."),
                SettingSpec.bool(K_TRAIL, "Show trail", true, "",
                        "Draw the line from you to the ghost along the route (a jump arc is always drawn)."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
