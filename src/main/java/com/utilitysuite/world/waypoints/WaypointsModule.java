package com.utilitysuite.world.waypoints;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.ISuiteEventBus;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SuiteEvent;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldFiles;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import org.joml.Matrix4f;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Waypoints: groups (Bases, Mines, Portals, Death Sites) with tags, overworld/nether 8:1 conversion, an automatic
 * Death Site when the player dies, automatic Portals waypoints on overworld/nether portal trips, in-world beacons
 * with name and distance (hidden in Clean-Screen), and a breadcrumb
 * trail with "Take Me Back" (hands the reversed trail to the navigator when one is installed).
 * <p>
 * Saved per world/server in {@code config/utilitysuite/world/<profile>.waypoints.json}.
 */
public final class WaypointsModule extends WorldModule {

    public static final String ID = "waypoints";
    private static final int SAVE_CHECK_INTERVAL = 100;
    /** A dimension change counts as a portal trip only if the player stood in a portal this recently (ticks). */
    private static final int PORTAL_RECENT_TICKS = 40;
    /** Ticks (after the arrival chunk is loaded) to look for the arrival portal before using the player position. */
    private static final int ARRIVAL_WINDOW_TICKS = 20;

    private final BreadcrumbTrail trail = new BreadcrumbTrail(512, 4);
    private final WaypointStore store = new WaypointStore(() -> trail);
    private KeyMapping addKey;
    private KeyMapping backKey;
    /** Action-registry requests (wheel, palette), handled on the next tick like a key press. */
    private boolean pendingAdd;
    private boolean pendingBack;

    /** Drops a waypoint at the player on the next tick (same as the Add Waypoint key). */
    public void requestAddHere() {
        pendingAdd = true;
    }

    /** Starts Take Me Back on the next tick (same as its key). */
    public void requestTakeMeBack() {
        pendingBack = true;
    }

    private boolean beacons;
    private boolean breadcrumbsVisible;
    private int maxDistance;
    private String profileId;
    private boolean wasDead;
    private int ticks;

    // Portal recording: last position inside a portal block (old dimension) and a pending arrival in the new one.
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private boolean recordPortals;
    private String lastDim;
    private boolean portalSeen;
    private int lastPortalTick;
    private int lastPortalX;
    private int lastPortalY;
    private int lastPortalZ;
    private int arrivalTicksLeft;

    // Render cache, rebuilt when the store version or the dimension changes.
    private Waypoint[] shown = new Waypoint[0];
    private int[] shownX = new int[0];
    private int[] shownY = new int[0];
    private int[] shownZ = new int[0];
    private int shownCount;
    private int shownVersion = -1;
    private String shownDimension;

    public WaypointsModule() {
        super(ID, "Waypoints", true);
    }

    public WaypointStore store() {
        return store;
    }

    public BreadcrumbTrail trail() {
        return trail;
    }

    public void setKeys(KeyMapping add, KeyMapping back) {
        this.addKey = add;
        this.backKey = back;
    }

    /** Subscribes to the suite's death events (several modules may report the same death; the store de-duplicates). */
    public void subscribe(ISuiteEventBus events) {
        events.subscribe(SuiteEvent.PlayerDied.class, e -> store.addDeathSite(BlockPosPacking.x(e.packedPos()),
                BlockPosPacking.y(e.packedPos()), BlockPosPacking.z(e.packedPos()), e.dimension(), e.timeMillis()));
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("beacons", "Beacons", true, "Display", "Beams and labels for waypoints in this "
                        + "dimension (overworld/nether waypoints are shown at the 8:1 converted position)."),
                SettingSpec.integer("max_distance", "Max beacon distance", 2000, 64, 30000, "Display",
                        "Waypoints farther than this are not drawn."),
                SettingSpec.bool("show_breadcrumbs", "Show breadcrumbs", false, "Breadcrumbs",
                        "Draws the recorded trail."),
                SettingSpec.integer("breadcrumb_spacing", "Breadcrumb spacing", 4, 1, 32, "Breadcrumbs",
                        "Minimum distance in blocks between recorded points."),
                SettingSpec.integer("breadcrumb_capacity", "Breadcrumb points", 512, 16, 8192, "Breadcrumbs",
                        "Oldest points are dropped beyond this."),
                SettingSpec.bool("death_sites", "Mark death sites", true, "Groups",
                        "Adds a Death Sites waypoint where you die."),
                SettingSpec.bool("record_portals", "Record portals automatically", true, "Groups",
                        "Adds a Portals waypoint at the portal you leave and the one you arrive at when travelling "
                                + "between the Overworld and the Nether (skipped within 16 blocks of an existing one)."));
    }

    private boolean deathSites;

    @Override
    protected void readSettings(ProfileConfig p) {
        beacons = bool(p, "beacons");
        maxDistance = integer(p, "max_distance");
        breadcrumbsVisible = bool(p, "show_breadcrumbs");
        trail.setMinSpacing(integer(p, "breadcrumb_spacing"));
        trail.setCapacity(integer(p, "breadcrumb_capacity"));
        deathSites = bool(p, "death_sites");
        recordPortals = bool(p, "record_portals");
        if (!recordPortals) {
            arrivalTicksLeft = 0;
        }
        if (!"global".equals(p.profileId()) && !p.profileId().equals(profileId)) {
            save(false);
            profileId = p.profileId();
            load();
        }
    }

    // ---- tick ---------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        String dim = WorldIds.dimension(level);
        boolean dead = player.isDeadOrDying();
        if (dim != lastDim && !dim.equals(lastDim)) { // WorldIds caches one String per dimension: usually identical
            onDimensionChanged(player, lastDim, dim);
            lastDim = dim;
        }
        if (recordPortals && !dead) {
            tickPortals(mc, player, level, dim);
        }
        if (dead && !wasDead && deathSites) {
            ISuiteEventBus events = Services.eventsOrNull();
            SuiteEvent.PlayerDied e = new SuiteEvent.PlayerDied(System.currentTimeMillis(),
                    BlockPosPacking.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ()), dim);
            if (events != null) {
                events.post(e); // our own subscriber records it (de-duplicated with other reporters)
            } else {
                store.addDeathSite(player.getBlockX(), player.getBlockY(), player.getBlockZ(), dim, e.timeMillis());
            }
        }
        wasDead = dead;
        if (!dead) {
            trail.sample(dim, player.getBlockX(), player.getBlockY(), player.getBlockZ(), level.getGameTime());
        }
        while (addKey != null && addKey.consumeClick()) {
            addHere(player, dim);
        }
        while (backKey != null && backKey.consumeClick()) {
            takeMeBack(player);
        }
        if (pendingAdd) {
            pendingAdd = false;
            addHere(player, dim);
        }
        if (pendingBack) {
            pendingBack = false;
            takeMeBack(player);
        }
        if (++ticks % SAVE_CHECK_INTERVAL == 0) {
            save(true);
        }
    }

    // ---- portals ------------------------------------------------------------------------------------------------

    private void onDimensionChanged(LocalPlayer player, String from, String to) {
        arrivalTicksLeft = 0;
        // Respawning after a death (or a teleport) also changes dimension: only count trips that began in a portal.
        if (!recordPortals || wasDead || !PortalWaypoints.isPortalTransition(from, to)
                || !portalSeen || ticks - lastPortalTick > PORTAL_RECENT_TICKS) {
            return;
        }
        portalSeen = false;
        Waypoint w = store.addPortal(lastPortalX, lastPortalY, lastPortalZ, from);
        if (w != null) {
            message(player, "Waypoint added: " + w.name());
        }
        arrivalTicksLeft = ARRIVAL_WINDOW_TICKS;
    }

    /** Two block-state reads per tick into a reused mutable position; no allocation. */
    private void tickPortals(Minecraft mc, LocalPlayer player, ClientLevel level, String dim) {
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        boolean inPortal = level.getBlockState(probe.set(x, y, z)).is(Blocks.NETHER_PORTAL)
                || level.getBlockState(probe.set(x, y + 1, z)).is(Blocks.NETHER_PORTAL);
        if (inPortal) {
            portalSeen = true;
            lastPortalTick = ticks;
            lastPortalX = x;
            lastPortalY = y;
            lastPortalZ = z;
        }
        if (arrivalTicksLeft <= 0) {
            return;
        }
        // The arrival position is not final until the new level's chunk at the player has arrived.
        if (mc.gui.screen() instanceof LevelLoadingScreen || !level.getChunkSource().hasChunk(x >> 4, z >> 4)) {
            return;
        }
        if (inPortal || --arrivalTicksLeft == 0) {
            arrivalTicksLeft = 0;
            Waypoint w = store.addPortal(x, y, z, dim);
            if (w != null) {
                message(player, "Waypoint added: " + w.name());
            }
        }
    }

    private void addHere(LocalPlayer player, String dim) {
        int n = store.inGroup(WaypointStore.GROUP_BASES).size() + 1;
        Waypoint w = store.add("Waypoint " + n, WaypointStore.GROUP_BASES, player.getBlockX(), player.getBlockY(),
                player.getBlockZ(), dim);
        message(player, "Waypoint added: " + w.name());
    }

    /** "Take Me Back": follow the breadcrumbs newest-first through the navigator, if one is installed. */
    public void takeMeBack(LocalPlayer player) {
        long[] full = trail.reversed();
        long[] path = TrailPruner.prune(full); // a cave loop is walked once, not retraced
        if (path.length < 2) {
            message(player, "No breadcrumbs recorded yet");
            return;
        }
        INavigator nav = Services.navigatorOrNull();
        if (nav == null || !nav.isAvailable()) {
            message(player, "Take Me Back needs the pathing module (not available)");
            return;
        }
        if (nav.followPathLeased(ID, ControlPriority.TRAVEL, path).isEmpty()) {
            message(player, "Take Me Back: movement is busy or automation is stopped");
            return;
        }
        message(player, "Taking you back along " + path.length + " breadcrumbs"
                + (path.length < full.length ? " (" + (full.length - path.length) + " skipped by loop shortcuts)" : ""));
    }

    private void message(LocalPlayer player, String text) {
        if (!cleanScreen()) {
            com.utilitysuite.core.api.Services.visuals().notices().post(
                    com.utilitysuite.core.api.visual.NoticeKind.INFO, text, null);
        }
    }

    @Override
    public void onWorldUnload() {
        save(false);
        store.clear();
        trail.clear();
        profileId = null;
        wasDead = false;
        lastDim = null;
        portalSeen = false;
        arrivalTicksLeft = 0;
        shownVersion = -1;
        shownCount = 0;
    }

    // ---- persistence --------------------------------------------------------------------------------------------

    private void load() {
        store.clear();
        byte[] data = WorldFiles.read(WorldFiles.file(profileId, ".waypoints.json"));
        if (data != null) {
            try {
                store.fromJson(new String(data, StandardCharsets.UTF_8));
            } catch (RuntimeException e) {
                UtilitySuiteMod.LOGGER.warn("Ignoring unreadable waypoints file for {}", profileId, e);
                store.clear();
            }
        }
    }

    private void save(boolean async) {
        if (profileId == null || !store.isDirty()) {
            return;
        }
        byte[] data = store.toJson().getBytes(StandardCharsets.UTF_8);
        store.clearDirty();
        if (async) {
            WorldFiles.writeAsync(WorldFiles.file(profileId, ".waypoints.json"), data);
        } else {
            WorldFiles.writeNow(WorldFiles.file(profileId, ".waypoints.json"), data);
        }
    }

    // ---- rendering ----------------------------------------------------------------------------------------------

    private void refreshShown(String dim) {
        int v = store.version();
        if (v == shownVersion && dim.equals(shownDimension)) {
            return;
        }
        shownVersion = v;
        shownDimension = dim;
        List<Waypoint> all = store.all();
        if (shown.length < all.size()) {
            int cap = Math.max(16, all.size());
            shown = new Waypoint[cap];
            shownX = new int[cap];
            shownY = new int[cap];
            shownZ = new int[cap];
        }
        int n = 0;
        for (Waypoint w : all) {
            if (!DimensionScale.convertible(w.dimension(), dim)) {
                continue;
            }
            long p = store.positionIn(w, dim);
            shown[n] = w;
            shownX[n] = BlockPosPacking.x(p);
            shownY[n] = BlockPosPacking.y(p);
            shownZ[n] = BlockPosPacking.z(p);
            n++;
        }
        for (int i = n; i < shownCount; i++) {
            shown[i] = null;
        }
        shownCount = n;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        refreshShown(ctx.dimension);
        if (beacons) {
            double max2 = (double) maxDistance * maxDistance;
            float bottom = ctx.ry(-64);
            float top = ctx.ry(320);
            for (int i = 0; i < shownCount; i++) {
                int x = shownX[i];
                int y = shownY[i];
                int z = shownZ[i];
                if (horizontalDistSq(ctx, x, z) > max2) {
                    continue;
                }
                int color = shown[i].argbColor();
                float rx = ctx.rx(x + 0.5);
                float rz = ctx.rz(z + 0.5);
                WorldRenderKit.line(ctx.lines, ctx.pose, rx, bottom, rz, rx, top, rz, (color & 0xFFFFFF) | 0x90000000);
                float bx = ctx.rx(x);
                float by = ctx.ry(y);
                float bz = ctx.rz(z);
                WorldRenderKit.box(ctx.lines, ctx.pose, bx, by, bz, bx + 1, by + 1, bz + 1, color);
            }
        }
        if (breadcrumbsVisible && ctx.dimension.equals(trail.dimension())) {
            int n = trail.size();
            for (int i = 1; i < n; i++) {
                long a = trail.get(i - 1);
                long b = trail.get(i);
                WorldRenderKit.line(ctx.lines, ctx.pose, ctx.rx(BlockPosPacking.x(a) + 0.5),
                        ctx.ry(BlockPosPacking.y(a) + 0.1), ctx.rz(BlockPosPacking.z(a) + 0.5),
                        ctx.rx(BlockPosPacking.x(b) + 0.5), ctx.ry(BlockPosPacking.y(b) + 0.1),
                        ctx.rz(BlockPosPacking.z(b) + 0.5), 0xA0FFD040);
            }
        }
    }

    private static double horizontalDistSq(RenderContext ctx, int x, int z) {
        double dx = x + 0.5 - ctx.camX;
        double dz = z + 0.5 - ctx.camZ;
        return dx * dx + dz * dz;
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (!beacons) {
            return;
        }
        double max2 = (double) maxDistance * maxDistance;
        for (int i = 0; i < shownCount; i++) {
            int x = shownX[i];
            int y = shownY[i];
            int z = shownZ[i];
            double h2 = horizontalDistSq(ctx, x, z);
            if (h2 > max2) {
                continue;
            }
            // Far labels are pulled toward the camera so they stay on screen at a readable size.
            double dx = x + 0.5 - ctx.camX;
            double dy = y + 1.5 - ctx.camY;
            double dz = z + 0.5 - ctx.camZ;
            double d = Math.sqrt(dx * dx + dy * dy + dz * dz);
            double k = d > 64 ? 64 / d : 1.0;
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, (float) (dx * k), (float) (dy * k), (float) (dz * k));
            Waypoint w = shown[i];
            String dist = WorldRenderKit.number((int) d);
            float width = ctx.font.width(w.name()) + ctx.font.width(" ") + ctx.font.width(dist) + ctx.font.width("m");
            float tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, w.name(), -width / 2f, w.argbColor());
            tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, " ", tx, 0xFFFFFFFF);
            tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, dist, tx, 0xFFC0C0C0);
            WorldRenderKit.text(ctx.font, ctx.buffers, m, "m", tx, 0xFFC0C0C0);
        }
    }
}
