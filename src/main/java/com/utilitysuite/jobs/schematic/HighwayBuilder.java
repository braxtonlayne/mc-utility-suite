package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IBlockWork;
import com.utilitysuite.core.api.ITravel;
import com.utilitysuite.core.api.NavigationStatus;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.NavigationRun;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.jobs.planner.PackedPos;
import com.utilitysuite.jobs.schematic.generated.HighwayDesign;
import com.utilitysuite.jobs.schematic.generated.HighwayRun;
import com.utilitysuite.jobs.schematic.generated.TunnelDigQueue;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Highway builder (off by default): when switched on, builds a straight or 45-degree road from the player's position
 * towards the waypoint named in its settings. Segments ({@link HighwayDesign}) are generated ahead of the player and
 * handed to the Schematic Toolkit as the generated design {@code generated:highway} (ghost preview + Build Assist fill
 * floor, rails, seals and spawn-proofing as the player walks). In tunnel mode the centre line of the new segment is
 * given to the navigator (a leased path; Baritone mines its way through), which digs the walking space. With "Dig the
 * full width" on (and the field-work hands available through {@link IBlockWork}) every dig cell of the published
 * segments is cleared instead: nearest column first, top-down, within reach, never the block the player stands on,
 * through the lease-checked player-like hands; when nothing is in reach the navigator walks to the nearest remaining
 * cell. In ice mode the optional boat hand-off starts a travel route ({@link ITravel}) to the end of the built road
 * whenever a segment completes while the player rides a boat.
 * <p>
 * Work per tick is a projection and a compare; a segment is generated only when the player nears the end of the
 * published road.
 */
public final class HighwayBuilder implements IModule, IConfigurable {

    public static final String ID = "highway_builder";
    public static final String KEY = "highway";

    public record Settings(HighwayDesign.Mode mode, int width, int segmentLength, int lookahead,
                           HighwayDesign.SpawnProof spawnProof, String target, boolean digTunnel,
                           boolean digSides, boolean boatHandoff) {
        static final Settings DEFAULTS = new Settings(HighwayDesign.Mode.TUNNEL, 3, 32, 16,
                HighwayDesign.SpawnProof.NONE, "", true, true, false);

        static Settings read(ProfileConfig p) {
            return new Settings(parse(HighwayDesign.Mode.class, p.getString(ID, "mode", DEFAULTS.mode.name()),
                    DEFAULTS.mode),
                    Math.max(1, Math.min(5, p.getInt(ID, "width", DEFAULTS.width))),
                    Math.max(8, Math.min(64, p.getInt(ID, "segment_length", DEFAULTS.segmentLength))),
                    Math.max(4, Math.min(64, p.getInt(ID, "lookahead", DEFAULTS.lookahead))),
                    parse(HighwayDesign.SpawnProof.class, p.getString(ID, "spawn_proof", DEFAULTS.spawnProof.name()),
                            DEFAULTS.spawnProof),
                    p.getString(ID, "target", DEFAULTS.target),
                    p.getBool(ID, "dig_tunnel", DEFAULTS.digTunnel),
                    p.getBool(ID, "dig_sides", DEFAULTS.digSides),
                    p.getBool(ID, "boat_handoff", DEFAULTS.boatHandoff));
        }

        HighwayDesign.Spec spec() {
            HighwayDesign.Spec s = new HighwayDesign.Spec();
            s.mode = mode;
            s.width = mode == HighwayDesign.Mode.ICE ? Math.min(2, width) : width;
            s.segmentLength = segmentLength;
            s.spawnProof = spawnProof;
            return s;
        }
    }

    static <E extends Enum<E>> E parse(Class<E> type, String name, E def) {
        try {
            return Enum.valueOf(type, name.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (RuntimeException e) {
            return def;
        }
    }

    private boolean enabled;
    private static final int BEGIN_RETRY_TICKS = 40;
    private int beginRetry;
    private Settings settings = Settings.DEFAULTS;
    private HighwayRun run;
    private String dimension;
    private NavigationRun digRun;
    private String status = "";

    // full-width digging (allocated once)
    private static final double REACH = 4.5;
    private static final int MAX_NAV_FAILURES = 3;
    /** Steps the road must reach beyond the boat before a hand-off is worth starting. */
    private static final int BOAT_MIN_AHEAD = 6;
    private final TunnelDigQueue digQueue = new TunnelDigQueue();
    private IBlockWork.Hands hands;
    private long digCell = TunnelDigQueue.NONE;
    private NavigationRun stepRun;
    private long stepTarget = TunnelDigQueue.NONE;
    private int navFailures;
    private int lastHandoffStep = -1;
    private LevelSolid solid;

    public HighwayBuilder() {
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean on) {
        if (on == enabled) {
            return;
        }
        enabled = on;
        beginRetry = 0;
        if (!on) {
            reset();
        }
    }

    public String status() {
        return status;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return;
        }
        if (run == null) {
            // Stay on and wait (status says what is missing); retry every 2 s so setting a target later starts the run.
            if (beginRetry > 0) {
                beginRetry--;
                return;
            }
            if (!begin(player, mc.level.dimension().location().toString())) {
                beginRetry = BEGIN_RETRY_TICKS;
                return;
            }
        }
        int progress = run.progress(player.getX(), player.getZ());
        int next = run.nextToPublish(progress, settings.lookahead());
        if (next >= 0) {
            publish(mc, next);
        }
        if (!digQueue.isEmpty()) {
            tickDig(mc, player);
            if (run == null) {
                return; // stopped by manual input
            }
        }
        if (settings.mode() == HighwayDesign.Mode.ICE && settings.boatHandoff()) {
            tickBoatHandoff(player, progress);
        }
        if (digQueue.isEmpty() && (run.finished(progress) || (digsFullWidth() && run.allPublished()))) {
            status = "Highway finished";
            enabled = false;
            reset();
        }
    }

    private boolean begin(LocalPlayer player, String dim) {
        IWaypointService wp = Services.waypointsOrNull();
        String name = settings.target().trim();
        if (wp == null || name.isEmpty()) {
            status = "Set a target waypoint";
            return false;
        }
        Optional<Waypoint> w = wp.byName(name);
        if (w.isEmpty()) {
            status = "No waypoint " + name;
            return false;
        }
        long end = wp.positionIn(w.get(), dim);
        BlockPos feet = player.blockPosition();
        HighwayRun r = new HighwayRun(feet.getX(), feet.getY(), feet.getZ(), PackedPos.x(end), PackedPos.z(end),
                settings.segmentLength());
        if (r.segments == 0) {
            status = "Already at the target";
            return false;
        }
        run = r;
        dimension = dim;
        status = "Highway: " + r.totalSteps + " blocks";
        return true;
    }

    private void publish(Minecraft mc, int index) {
        HighwayRun r = run;
        HighwayDesign.Spec spec = settings.spec();
        HighwayDesign.Probe probe = new LevelProbe(mc);
        int ex = r.sx + r.dx * r.totalSteps;
        int ez = r.sz + r.dz * r.totalSteps;
        HighwayDesign.Segment seg = HighwayDesign.segment(spec, r.sx, r.sy, r.sz, ex, ez, index, probe);
        r.markPublished(index);
        SchematicToolkit t = SchematicBootstrap.toolkit();
        if (t != null) {
            t.replaceGenerated(KEY, seg.volume(), PackedPos.pack(seg.anchorX(), seg.anchorY(), seg.anchorZ()));
        }
        status = "Highway segment " + (index + 1) + "/" + r.segments;
        if (spec.mode == HighwayDesign.Mode.TUNNEL && settings.digTunnel() && seg.digCells().length > 0) {
            if (settings.digSides() && handsOrNull() != null) {
                digQueue.addAll(seg.digCells()); // the dig consumer clears the full width, centre included
                return;
            }
            INavigator nav = Services.navigatorOrNull();
            if (nav != null && nav.isAvailable()) {
                if (digRun != null && digRun.isActive()) {
                    digRun.cancel();
                }
                digRun = nav.followPathLeased(ID, ControlPriority.GOAL, r.centrePath(seg.fromStep(), seg.toStep()))
                        .orElse(null);
            }
        }
    }

    /** Tunnel mode clearing every dig cell with the field-work hands (not the navigator's centre-line path). */
    private boolean digsFullWidth() {
        return settings.mode() == HighwayDesign.Mode.TUNNEL && settings.digTunnel() && settings.digSides()
                && handsOrNull() != null;
    }

    private IBlockWork.Hands handsOrNull() {
        if (hands == null) {
            IBlockWork w = Services.blockWorkOrNull();
            if (w != null) {
                hands = w.hands(ID, ControlPriority.GOAL);
            }
        }
        return hands;
    }

    /**
     * One step of the full-width dig: keep breaking the current cell, else pick the next one in reach, else walk to
     * the nearest remaining cell. Never digs while its own walk is running (one owner of the body at a time).
     */
    private void tickDig(Minecraft mc, LocalPlayer player) {
        IBlockWork.Hands h = handsOrNull();
        if (h == null) {
            return;
        }
        if (h.consumePreempted()) { // manual input or Stop-All wins: end the run
            status = "Highway stopped: manual input";
            enabled = false;
            reset();
            return;
        }
        if (stepRun != null) {
            if (stepRun.isActive()) {
                return;
            }
            NavigationStatus end = stepRun.status();
            stepRun = null;
            if (end == NavigationStatus.FAILED || end == NavigationStatus.UNAVAILABLE) {
                if (++navFailures >= MAX_NAV_FAILURES && stepTarget != TunnelDigQueue.NONE) {
                    digQueue.remove(stepTarget); // unreachable: left to the player
                    navFailures = 0;
                }
            }
        }
        if (digCell != TunnelDigQueue.NONE) {
            int r = h.tickBreak(PackedPos.x(digCell), PackedPos.y(digCell), PackedPos.z(digCell), false, true);
            if (r == IBlockWork.Hands.RUNNING) {
                return;
            }
            digQueue.remove(digCell); // dug, or blocked from view: either way not retried from here
            digCell = TunnelDigQueue.NONE;
            navFailures = 0;
            h.clearLook();
            return;
        }
        if (solid == null) {
            solid = new LevelSolid(mc);
        }
        AABB box = player.getBoundingBox();
        int standY = (int) Math.floor(box.minY - 0.01);
        long c = digQueue.pick(player.getX(), player.getEyeY(), player.getZ(), REACH,
                (int) Math.floor(box.minX), (int) Math.floor(box.maxX - 1e-6),
                (int) Math.floor(box.minZ), (int) Math.floor(box.maxZ - 1e-6), standY, solid);
        if (c != TunnelDigQueue.NONE) {
            if (h.ensureLease()) {
                digCell = c;
            }
            return;
        }
        h.release();
        if (digQueue.isEmpty()) {
            return;
        }
        long n = digQueue.nearest(player.getX(), player.getZ());
        INavigator nav = Services.navigatorOrNull();
        if (n == TunnelDigQueue.NONE || nav == null || !nav.isAvailable()) {
            status = "Highway: walk on to keep digging";
            return;
        }
        stepTarget = n;
        stepRun = nav.goToLeased(ID, ControlPriority.GOAL, PackedPos.x(n), run.sy, PackedPos.z(n), 2).orElse(null);
    }

    /** Ice road: when the built road reaches further ahead and the player rides a boat, hand over to the boat driver. */
    private void tickBoatHandoff(LocalPlayer player, int progress) {
        if (!(player.getVehicle() instanceof Boat)) {
            return;
        }
        int step = run.boatHandoffStep(progress, BOAT_MIN_AHEAD, lastHandoffStep);
        if (step < 0) {
            return;
        }
        ITravel travel = Services.travelOrNull();
        if (travel == null) {
            return;
        }
        lastHandoffStep = step; // one attempt per completed segment, also when the autopilot declines
        if (travel.startRoute(run.stepX(step), run.sy, run.stepZ(step), ITravel.RouteMode.BOAT)) {
            status = "Highway: boat to step " + step;
        }
    }

    /** Solid-block probe for the dig queue. Client thread. */
    private static final class LevelSolid implements TunnelDigQueue.Solid {
        private final Minecraft mc;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        LevelSolid(Minecraft mc) {
            this.mc = mc;
        }

        @Override
        public boolean solidAt(int x, int y, int z) {
            BlockState s = mc.level.getBlockState(cursor.set(x, y, z));
            return !s.isAir() && !s.canBeReplaced();
        }
    }

    /** Reads the loaded level for the generator (solid floor already there, fluid to seal). Client thread. */
    private static final class LevelProbe implements HighwayDesign.Probe {
        private final Minecraft mc;
        private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        LevelProbe(Minecraft mc) {
            this.mc = mc;
        }

        @Override
        public boolean solidAt(int x, int y, int z) {
            BlockState s = mc.level.getBlockState(cursor.set(x, y, z));
            return !s.isAir() && s.getFluidState().isEmpty() && !s.canBeReplaced();
        }

        @Override
        public boolean fluidAt(int x, int y, int z) {
            return !mc.level.getFluidState(cursor.set(x, y, z)).isEmpty();
        }
    }

    private void reset() {
        if (digRun != null && digRun.isActive()) {
            digRun.cancel();
        }
        digRun = null;
        if (stepRun != null && stepRun.isActive()) {
            stepRun.cancel();
        }
        stepRun = null;
        if (hands != null) {
            hands.release();
        }
        digQueue.clear();
        digCell = TunnelDigQueue.NONE;
        stepTarget = TunnelDigQueue.NONE;
        navFailures = 0;
        lastHandoffStep = -1;
        solid = null;
        run = null;
        dimension = null;
    }

    @Override
    public void onStopAll() {
        enabled = false;
        reset();
    }

    @Override
    public void onWorldUnload() {
        reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        settings = Settings.read(profile);
    }

    @Override
    public String displayName() {
        return "Highway Builder";
    }

    @Override
    public List<SettingSpec> settings() {
        Settings d = Settings.DEFAULTS;
        return List.of(
                SettingSpec.text("target", "Target waypoint", d.target(), "Highway",
                        "Waypoint the road heads for (snapped to the nearest straight or 45-degree direction)"),
                SettingSpec.choice("mode", "Mode", d.mode(), "Highway",
                        "Tunnel: 3x3 tunnel with floor and lava seals. Ice: packed-ice boat road with side rails"),
                SettingSpec.integer("width", "Width", d.width(), 1, 5, "Highway",
                        "Floor width (ice roads use at most 2)"),
                SettingSpec.integer("segment_length", "Segment length", d.segmentLength(), 8, 64, "Highway",
                        "Blocks generated per segment"),
                SettingSpec.integer("lookahead", "Look-ahead", d.lookahead(), 4, 64, "Highway",
                        "Generate the next segment when this close to the end of the built road"),
                SettingSpec.choice("spawn_proof", "Spawn-proofing", d.spawnProof(), "Highway",
                        "Tunnel floor: none, bottom slabs or floor buttons"),
                SettingSpec.bool("dig_tunnel", "Dig the tunnel", d.digTunnel(), "Highway",
                        "Path the centre line through rock (needs Baritone)"),
                SettingSpec.bool("dig_sides", "Dig the full width", d.digSides(), "Highway",
                        "Clear every tunnel cell (side columns and top row) like a player, nearest first, top-down"),
                SettingSpec.bool("boat_handoff", "Boat hand-off", d.boatHandoff(), "Highway",
                        "Ice road: when a segment is done and you ride a boat, the travel autopilot drives it on"));
    }

    @Override
    public void onSettingChanged(String key) {
        settings = Settings.read(Services.modState().profile());
    }
}
