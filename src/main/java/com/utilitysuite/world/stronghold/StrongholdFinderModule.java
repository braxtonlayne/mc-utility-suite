package com.utilitysuite.world.stronghold;

import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.core.api.visual.NoticeKind;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.PoseLineSink;
import com.utilitysuite.world.render.RenderContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.projectile.EyeOfEnder;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;

import java.util.List;
import java.util.Locale;

/**
 * Two-throw stronghold finder. Eyes of ender spawned next to the local player are followed for a few ticks
 * ({@link EyeFlight}); each gives a ray from the throw spot. The second throw from at least the baseline distance away
 * is intersected with the first ({@link Triangulator}), snapped onto the vanilla ring bands ({@link StrongholdMath})
 * and saved as a waypoint with a notice. The stored first throw is drawn as a guide line while waiting. Resets after
 * the configured minutes or on a dimension change. Event-driven: the tick only walks the few tracked eyes.
 */
public final class StrongholdFinderModule extends WorldModule {

    public static final String ID = "stronghold_finder";
    private static final int SLOTS = 4;
    /** An eye spawns at the thrower's eye height, this close horizontally counts as our throw. */
    private static final double OWN_THROW_RADIUS_SQ = 3.0 * 3.0;
    private static final int RAY_COLOR = 0xC0A060FF;
    private static final float RAY_LENGTH = 192f;

    private final EyeOfEnder[] eyes = new EyeOfEnder[SLOTS];
    private final EyeFlight[] flights = new EyeFlight[SLOTS];
    private final Triangulator triangulator = new Triangulator();
    private final PoseLineSink sink = new PoseLineSink();

    private int minBaseline;
    private int resetMinutes;
    private boolean snapRings;
    private boolean addWaypoint;
    private String group = "Strongholds";
    private boolean showRay;

    private String dimension;
    private double rayY;
    private float rayDx;
    private float rayDz;

    public StrongholdFinderModule() {
        super(ID, "Stronghold Finder", true);
        for (int i = 0; i < SLOTS; i++) {
            flights[i] = new EyeFlight();
        }
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("min_baseline", "Min distance between throws", 100, 20, 2000, "",
                        "The second throw must be at least this many blocks from the first (200-400 is most "
                                + "accurate)."),
                SettingSpec.integer("reset_minutes", "Forget a throw after (min)", 10, 1, 120, "",
                        "A first throw older than this is dropped. Changing dimension also resets."),
                SettingSpec.bool("snap_rings", "Snap to stronghold rings", true, "",
                        "Move an estimate that falls between the vanilla ring bands onto the nearest band."),
                SettingSpec.bool("add_waypoint", "Add a waypoint", true, "",
                        "Save each estimate as a waypoint in the group below."),
                SettingSpec.text("group", "Waypoint group", "Strongholds", "", "Waypoint group for the estimates."),
                SettingSpec.bool("show_ray", "Show first throw line", true, "",
                        "Draw the first throw's direction while waiting for the second."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        minBaseline = integer(p, "min_baseline");
        resetMinutes = integer(p, "reset_minutes");
        snapRings = bool(p, "snap_rings");
        addWaypoint = bool(p, "add_waypoint");
        String g = text(p, "group");
        group = g == null || g.isBlank() ? "Strongholds" : g.trim();
        showRay = bool(p, "show_ray");
    }

    @Override
    protected void onDisable() {
        clear();
    }

    @Override
    public void onWorldUnload() {
        clear();
        dimension = null;
    }

    private void clear() {
        for (int i = 0; i < SLOTS; i++) {
            eyes[i] = null;
        }
        triangulator.reset();
    }

    /** Client entity joins (registered by the world bootstrap). */
    public void onEntityJoin(EntityJoinLevelEvent event) {
        if (!isEnabled() || !event.getLevel().isClientSide() || !(event.getEntity() instanceof EyeOfEnder eye)) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        double dx = eye.getX() - player.getX();
        double dz = eye.getZ() - player.getZ();
        if (dx * dx + dz * dz > OWN_THROW_RADIUS_SQ) {
            return; // someone else's eye
        }
        int slot = -1;
        for (int i = 0; i < SLOTS; i++) {
            if (eyes[i] == null) {
                slot = i;
                break;
            }
        }
        if (slot < 0) {
            return;
        }
        eyes[slot] = eye;
        flights[slot].begin(eye.getX(), eye.getZ());
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        String dim = WorldIds.dimension(level);
        if (!dim.equals(dimension)) {
            if (dimension != null && triangulator.hasFirst()) {
                notice(NoticeKind.INFO, "Stronghold finder: reset (dimension changed)", null);
            }
            clear();
            dimension = dim;
        }
        long now = level.getGameTime();
        if (triangulator.expire(now, expiryTicks())) {
            notice(NoticeKind.INFO, "Stronghold finder: first throw forgotten (too old)", null);
        }
        for (int i = 0; i < SLOTS; i++) {
            EyeOfEnder eye = eyes[i];
            if (eye == null) {
                continue;
            }
            if (eye.isRemoved()) {
                eyes[i] = null;
                continue;
            }
            EyeFlight f = flights[i];
            if (f.tick(eye.getX(), eye.getZ())) {
                eyes[i] = null;
                if (f.moved()) {
                    onThrow(f, now, player.getY(), dim);
                }
            }
        }
    }

    private long expiryTicks() {
        return resetMinutes * 60L * 20L;
    }

    /** Once per finished eye flight (not a hot path). */
    private void onThrow(EyeFlight f, long now, double playerY, String dim) {
        Triangulator.Result r = triangulator.addThrow(f.spawnX(), f.spawnZ(), f.dirX(), f.dirZ(), now, minBaseline,
                snapRings, expiryTicks());
        double len = Math.hypot(f.dirX(), f.dirZ());
        rayDx = (float) (f.dirX() / len);
        rayDz = (float) (f.dirZ() / len);
        rayY = playerY + 1.0;
        switch (r) {
            case FIRST_STORED -> notice(NoticeKind.INFO, "Stronghold finder: first throw saved",
                    "Walk " + minBaseline + "+ blocks sideways (200-400 is best) and throw again.");
            case TOO_CLOSE -> notice(NoticeKind.WARN, "Stronghold finder: too close to the first throw",
                    "Throw replaced. Move at least " + minBaseline + " blocks away and throw again.");
            case NEAR_PARALLEL -> notice(NoticeKind.WARN, "Stronghold finder: throws are almost parallel",
                    "Throw replaced. Move sideways to the eye's path, not along it, and throw again.");
            case LOCATED -> located(triangulator.last(), playerY, dim);
        }
    }

    private void located(StrongholdMath.Estimate e, double playerY, String dim) {
        int x = (int) Math.floor(e.x());
        int z = (int) Math.floor(e.z());
        String title = String.format(Locale.ROOT, "Stronghold near %d, %d (ring %d)", x, z, e.ring());
        String detail = e.snapped()
                ? String.format(Locale.ROOT, "Moved onto the ring band from %d, %d.", (int) e.rawX(), (int) e.rawZ())
                : null;
        if (addWaypoint) {
            IWaypointService wps = Services.waypoints();
            wps.add(String.format(Locale.ROOT, "Stronghold (ring %d)", e.ring()), group, x, (int) playerY, z, dim);
        }
        notice(NoticeKind.DONE, title, detail);
    }

    private static void notice(NoticeKind kind, String title, String detail) {
        IVisuals v = Services.visuals();
        if (v != null && !cleanScreen()) {
            v.notices().post(kind, title, detail);
        }
    }

    @Override
    public String phase() {
        return triangulator.hasFirst() ? "WAITING_FOR_SECOND_THROW" : null;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (!showRay || !triangulator.hasFirst()) {
            return;
        }
        PoseLineSink s = sink.bind(ctx);
        float x0 = ctx.rx(triangulator.firstX());
        float z0 = ctx.rz(triangulator.firstZ());
        float y = ctx.ry(rayY);
        s.line(x0, y, z0, x0 + rayDx * RAY_LENGTH, y, z0 + rayDz * RAY_LENGTH, RAY_COLOR);
    }
}
