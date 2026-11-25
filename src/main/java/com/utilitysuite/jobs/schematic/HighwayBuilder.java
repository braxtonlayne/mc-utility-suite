package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.ControlPriority;
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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Optional;

/**
 * Highway builder (off by default): when switched on, builds a straight or 45-degree road from the player's position
 * towards the waypoint named in its settings. Segments ({@link HighwayDesign}) are generated ahead of the player and
 * handed to the Schematic Toolkit as the generated design {@code generated:highway} (ghost preview + Build Assist fill
 * floor, rails, seals and spawn-proofing as the player walks). In tunnel mode the centre line of the new segment is
 * given to the navigator (a leased path; Baritone mines its way through), which digs the walking space; the side
 * columns of a 3-wide tunnel are left to the player or a later pass.
 * <p>
 * Work per tick is a projection and a compare; a segment is generated only when the player nears the end of the
 * published road.
 */
public final class HighwayBuilder implements IModule, IConfigurable {

    public static final String ID = "highway_builder";
    public static final String KEY = "highway";

    public record Settings(HighwayDesign.Mode mode, int width, int segmentLength, int lookahead,
                           HighwayDesign.SpawnProof spawnProof, String target, boolean digTunnel) {
        static final Settings DEFAULTS = new Settings(HighwayDesign.Mode.TUNNEL, 3, 32, 16,
                HighwayDesign.SpawnProof.NONE, "", true);

        static Settings read(ProfileConfig p) {
            return new Settings(parse(HighwayDesign.Mode.class, p.getString(ID, "mode", DEFAULTS.mode.name()),
                    DEFAULTS.mode),
                    Math.max(1, Math.min(5, p.getInt(ID, "width", DEFAULTS.width))),
                    Math.max(8, Math.min(64, p.getInt(ID, "segment_length", DEFAULTS.segmentLength))),
                    Math.max(4, Math.min(64, p.getInt(ID, "lookahead", DEFAULTS.lookahead))),
                    parse(HighwayDesign.SpawnProof.class, p.getString(ID, "spawn_proof", DEFAULTS.spawnProof.name()),
                            DEFAULTS.spawnProof),
                    p.getString(ID, "target", DEFAULTS.target),
                    p.getBool(ID, "dig_tunnel", DEFAULTS.digTunnel));
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
    private Settings settings = Settings.DEFAULTS;
    private HighwayRun run;
    private String dimension;
    private NavigationRun digRun;
    private String status = "";

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
        if (run == null && !begin(player, mc.level.dimension().location().toString())) {
            enabled = false;
            return;
        }
        int progress = run.progress(player.getX(), player.getZ());
        int next = run.nextToPublish(progress, settings.lookahead());
        if (next >= 0) {
            publish(mc, next);
        }
        if (run.finished(progress)) {
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
                        "Path the centre line through rock (needs Baritone)"));
    }

    @Override
    public void onSettingChanged(String key) {
        settings = Settings.read(Services.modState().profile());
    }
}
