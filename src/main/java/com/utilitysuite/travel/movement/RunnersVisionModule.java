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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Runner's Vision (roadmap M3): while sprinting, a glowing flow-line along the fastest no-stop chain of runs and
 * sprint jumps ahead ({@link RunnersVision}, about 24 blocks), refreshed as you move. Visual only: it never takes a
 * lease or touches input. The search is budgeted per client tick and its last completed line is reused until the next
 * one completes. Off by default; nothing is drawn while not sprinting, in Clean-Screen (no world kit then), or when the
 * line is empty. One batch per frame through the Shell's world kit: a faint glow under a dashed flow, plus a warning
 * mark where the run has to stop.
 */
public final class RunnersVisionModule implements IModule, IConfigurable {

    public static final String ID = "runners_vision";
    public static final String K_OPACITY = "opacity";
    public static final String K_BUDGET = "search_budget";
    /** Client ticks between search starts (the last line is drawn meanwhile). */
    static final int REFRESH_TICKS = 4;
    /** Horizontal speed (blocks per tick) above which the movement direction, not the look, sets the heading. */
    static final double MOVE_HEADING_SPEED = 0.1;
    static final float LIFT = 0.08f;
    static final float FLOW_DASH = 0.5f;
    static final float STOP_MARK = 0.9f;

    private final TickWorldShapes worldShapes;
    private final RunnersVision vision = new RunnersVision();
    private final MovementPhysics start = new MovementPhysics();
    /** The camera-relative line drawn this frame. */
    private final float[] path = new float[RunnersVision.MAX_POINTS * 3];

    private boolean enabled;
    private float opacity = 0.6f;
    private int budget = RunnersVision.DEFAULT_BUDGET;
    private int clock;
    private int drawFrom;

    public RunnersVisionModule(TickWorldShapes worldShapes) {
        this.worldShapes = worldShapes;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null || !p.isSprinting() || p.getAbilities().flying || p.isFallFlying()
                || p.isPassenger() || p.isInWater()) {
            clear();
            return;
        }
        if (!vision.searching() && ++clock >= REFRESH_TICKS) {
            clock = 0;
            Vec3 v = p.getDeltaMovement();
            double speed = Math.sqrt(v.x * v.x + v.z * v.z);
            float heading = speed > MOVE_HEADING_SPEED ? (float) Math.toDegrees(Math.atan2(-v.x, v.z)) : p.getYRot();
            start.set(p.getX(), p.getY(), p.getZ(), v.x, v.y, v.z, p.onGround());
            vision.begin(start, heading, p.getHealth() + p.getAbsorptionAmount());
        }
        if (vision.searching() && vision.step(budget, worldShapes.forTick(mc.level))) {
            drawFrom = 0;
        }
        if (vision.count() > 0) {
            drawFrom = vision.nearest(p.getX(), p.getZ(), drawFrom);
        }
    }

    private void clear() {
        vision.clear();
        clock = REFRESH_TICKS; // search at once when the sprint starts
        drawFrom = 0;
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || vision.count() - drawFrom < 2 || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        Minecraft mc = Minecraft.getInstance();
        if (world == null || mc.player == null) {
            return; // Clean-Screen or no Shell: nothing to draw
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        // the first point follows the rendered player so the line does not step at tick rate
        path[0] = (float) (Mth.lerp(pt, mc.player.xo, mc.player.getX()) - cam.x);
        path[1] = (float) (Mth.lerp(pt, mc.player.yo, mc.player.getY()) - cam.y) + LIFT;
        path[2] = (float) (Mth.lerp(pt, mc.player.zo, mc.player.getZ()) - cam.z);
        int n = 1;
        for (int i = drawFrom + 1; i < vision.count(); i++, n++) {
            int o = n * 3;
            path[o] = (float) (vision.x(i) - cam.x);
            path[o + 1] = (float) (vision.y(i) - cam.y) + LIFT;
            path[o + 2] = (float) (vision.z(i) - cam.z);
        }
        world.polyline(path, n, 0f, HoloToken.ACCENT_GLOW, opacity * 0.45f);
        world.polyline(path, n, FLOW_DASH, HoloToken.ACCENT, opacity);
        if (vision.end() == RunnersVision.END_STOP) {
            int o = (n - 1) * 3;
            world.groundMark(path[o], path[o + 1], path[o + 2], STOP_MARK, HoloToken.WARN, Math.max(0.6f, opacity));
        }
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
            clear();
        }
    }

    @Override
    public void onWorldUnload() {
        clear();
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        opacity = (float) Mth.clamp(p.getDouble(ID, K_OPACITY, 0.6), 0.1, 1.0);
        budget = Mth.clamp(p.getInt(ID, K_BUDGET, RunnersVision.DEFAULT_BUDGET), 60, 400);
    }

    @Override
    public String displayName() {
        return "Runner's Vision";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal(K_OPACITY, "Opacity", 0.6, 0.1, 1.0, "", "How bright the flow-line is drawn."),
                SettingSpec.integer(K_BUDGET, "Search budget", RunnersVision.DEFAULT_BUDGET, 60, 400, "",
                        "Simulated ticks per game tick for the line ahead. Lower is lighter, higher refreshes faster."));
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
