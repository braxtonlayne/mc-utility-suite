package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.BubbleColumnBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;

import java.util.List;

/**
 * Boat Line: while you ride a boat on ice (or water), predicts where the boat goes over the next few seconds with the
 * keys you hold ({@link BoatDriftSim}, vanilla boat rules) and, on an ice track, reads the corridor ahead
 * ({@link IceTrackCorridor}) to draw the racing line through the next corner, its apex and the braking point (red
 * when you are already past it). Simulated once per client tick from a per-tick cell cache; the render callback only
 * shifts the stored points by the interpolation offset and hands them to the Shell's world kit. Off by default;
 * nothing drawn when not in a boat, on land, or in Clean-Screen (the kit is absent then).
 */
public final class BoatLineModule implements IModule, IConfigurable {

    public static final String ID = "boat_line";
    public static final String K_HORIZON = "horizon_ticks";
    public static final String K_RACING = "racing_line";
    public static final String K_BRAKE = "brake_marker";
    public static final String K_LOOK = "look_ahead";
    private static final float LIFT = 0.15f;

    private final BoatDriftSim sim = new BoatDriftSim();
    private final IceTrackCorridor corridor = new IceTrackCorridor();
    private final Long2IntOpenHashMap cellCache = new Long2IntOpenHashMap(512);
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final BoatDriftSim.Surface surface = new BoatDriftSim.Surface() {
        @Override
        public int surface(int x, int z) {
            return cell(x, z);
        }

        @Override
        public double flowX(int x, int z) {
            return (amount(x - 1, z) - amount(x + 1, z)) / 8.0;
        }

        @Override
        public double flowZ(int x, int z) {
            return (amount(x, z - 1) - amount(x, z + 1)) / 8.0;
        }
    };
    private final float[] pathBuf = new float[(BoatDriftSim.MAX_TICKS + 1) * 3];
    private final float[] lineBuf = new float[IceTrackCorridor.LINE_SAMPLES * 3];

    private boolean enabled;
    private int horizon = 60;
    private boolean racingLine = true;
    private boolean brakeMarker = true;
    private int lookAhead = 48;

    private ClientLevel level;
    private int boatY;
    private boolean active;
    private boolean hasCorner;
    private double baseY;

    public BoatLineModule() {
        cellCache.defaultReturnValue(-1);
    }

    @Override
    public void onTick() {
        active = false;
        hasCorner = false;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return;
        }
        Entity v = p.getVehicle();
        if (!(v instanceof Boat boat)) {
            return;
        }
        level = mc.level;
        boatY = Mth.floor(boat.getY() + 0.01);
        cellCache.clear();
        double x = boat.getX();
        double z = boat.getZ();
        int under = cell(Mth.floor(x), Mth.floor(z));
        if (!BoatDriftSim.isIce(under) && under != BoatDriftSim.WATER) {
            level = null;
            return;
        }
        Vec3 m = boat.getDeltaMovement();
        double turnRate = Mth.wrapDegrees(boat.getYRot() - boat.yRotO);
        boolean fwd = p.input.up;
        boolean back = p.input.down;
        boolean left = p.input.left;
        boolean right = p.input.right;
        sim.run(surface, x, z, m.x, m.z, boat.getYRot(), turnRate, fwd, back, left, right, horizon);
        baseY = boat.getY();
        active = sim.count() > 1;
        if (racingLine || brakeMarker) {
            double speed = Math.sqrt(m.x * m.x + m.z * m.z);
            double heading = speed > 0.05 ? Math.toDegrees(Math.atan2(-m.x, m.z)) : boat.getYRot();
            hasCorner = BoatDriftSim.isIce(under) && corridor.analyze(surface, x, z, heading, speed, lookAhead);
        }
        level = null;
    }

    /** Surface code at the boat's level for a cell, cached for this tick. */
    private int cell(int x, int z) {
        long key = BlockPos.asLong(x, boatY, z);
        int c = cellCache.get(key);
        if (c != -1) {
            return c;
        }
        c = classify(x, z);
        cellCache.put(key, c);
        return c;
    }

    private int classify(int x, int z) {
        ClientLevel lv = level;
        if (lv == null) {
            return BoatDriftSim.LAND;
        }
        BlockState at = lv.getBlockState(probe.set(x, boatY, z));
        int water = waterCode(at);
        if (water >= 0) {
            return water;
        }
        VoxelShape shape = at.getCollisionShape(lv, probe);
        if (!shape.isEmpty() && shape.max(Direction.Axis.Y) > 0.25) {
            return BoatDriftSim.WALL;
        }
        BlockState below = lv.getBlockState(probe.set(x, boatY - 1, z));
        water = waterCode(below);
        if (water >= 0) {
            return water;
        }
        if (below.is(Blocks.BLUE_ICE)) {
            return BoatDriftSim.BLUE_ICE;
        }
        if (below.is(Blocks.ICE) || below.is(Blocks.PACKED_ICE) || below.is(Blocks.FROSTED_ICE)) {
            return BoatDriftSim.ICE;
        }
        if (below.is(Blocks.SLIME_BLOCK)) {
            return BoatDriftSim.SLIME;
        }
        return below.getCollisionShape(lv, probe).isEmpty() ? BoatDriftSim.DROP : BoatDriftSim.LAND;
    }

    private static int waterCode(BlockState s) {
        if (s.getBlock() instanceof BubbleColumnBlock) {
            return s.getValue(BubbleColumnBlock.DRAG_DOWN) ? BoatDriftSim.BUBBLE_DOWN : BoatDriftSim.BUBBLE_UP;
        }
        FluidState f = s.getFluidState();
        return f.is(FluidTags.WATER) ? BoatDriftSim.WATER : -1;
    }

    /** Water level 1-8 at the boat's level (0 when no water): the flow approximation runs from high to low. */
    private int amount(int x, int z) {
        ClientLevel lv = level;
        if (lv == null) {
            return 0;
        }
        FluidState f = lv.getFluidState(probe.set(x, boatY, z));
        return f.is(FluidTags.WATER) ? f.getAmount() : 0;
    }

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (!enabled || !active || event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        IVisuals visuals = Services.visualsOrNull();
        IHoloWorld world = visuals != null ? visuals.world() : null;
        Minecraft mc = Minecraft.getInstance();
        if (world == null || mc.player == null || !(mc.player.getVehicle() instanceof Boat boat)) {
            return;
        }
        float pt = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        // shift the tick-rate samples so the line starts at the rendered boat
        double ox = Mth.lerp(pt, boat.xo, boat.getX()) - boat.getX();
        double oz = Mth.lerp(pt, boat.zo, boat.getZ()) - boat.getZ();
        float y = (float) (Mth.lerp(pt, boat.yo, boat.getY()) - cam.y) + LIFT;
        int n = sim.count();
        for (int i = 0; i < n; i++) {
            int o = i * 3;
            pathBuf[o] = (float) (sim.x(i) + ox - cam.x);
            pathBuf[o + 1] = y;
            pathBuf[o + 2] = (float) (sim.z(i) + oz - cam.z);
        }
        world.polyline(pathBuf, n, 0f, sim.hitWall() ? HoloToken.WARN : HoloToken.ACCENT, 0.8f);
        if (!hasCorner) {
            return;
        }
        float gy = (float) (baseY - cam.y) + 0.05f;
        if (racingLine && corridor.corner()) {
            for (int i = 0; i < IceTrackCorridor.LINE_SAMPLES; i++) {
                int o = i * 3;
                lineBuf[o] = (float) (corridor.lineX(i) - cam.x);
                lineBuf[o + 1] = gy;
                lineBuf[o + 2] = (float) (corridor.lineZ(i) - cam.z);
            }
            world.polyline(lineBuf, IceTrackCorridor.LINE_SAMPLES, 0.6f, HoloToken.LINE_STRONG, 0.7f);
            world.groundMark((float) (corridor.apexX() - cam.x), gy, (float) (corridor.apexZ() - cam.z), 0.6f,
                    HoloToken.ACCENT_GLOW, 0.9f);
        }
        if (brakeMarker) {
            world.ring((float) (corridor.brakeX() - cam.x), gy, (float) (corridor.brakeZ() - cam.z), 0.7f,
                    corridor.brakeNow() ? HoloToken.DANGER : HoloToken.WARN, 0.9f);
        }
    }

    // ---- module plumbing --------------------------------------------------------------------------------------------

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TRAVEL;
    }

    @Override
    public String displayName() {
        return "Boat Line";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        enabled = on;
        if (!on) {
            active = false;
            hasCorner = false;
        }
    }

    @Override
    public void onWorldUnload() {
        active = false;
        hasCorner = false;
        level = null;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_HORIZON, "Prediction (ticks)", 60, 20, 100, "",
                        "How far ahead the boat's path is predicted with the keys you hold (20 ticks = 1 s)."),
                SettingSpec.bool(K_RACING, "Racing line", true, "",
                        "On ice tracks: the outside-apex-outside line through the next corner and its apex."),
                SettingSpec.bool(K_BRAKE, "Braking point", true, "",
                        "Where to brake for the next corner (red: brake now)."),
                SettingSpec.integer(K_LOOK, "Track look-ahead (blocks)", 48, 16, 96, "",
                        "How far along the ice corridor the next corner is searched."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        horizon = Mth.clamp(p.getInt(ID, K_HORIZON, 60), 20, 100);
        racingLine = p.getBool(ID, K_RACING, true);
        brakeMarker = p.getBool(ID, K_BRAKE, true);
        lookAhead = Mth.clamp(p.getInt(ID, K_LOOK, 48), 16, 96);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
