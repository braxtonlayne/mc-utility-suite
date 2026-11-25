package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.INavigator;
import com.utilitysuite.core.api.IWaypointService;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.Waypoint;
import com.utilitysuite.travel.LeaseSlot;
import com.utilitysuite.travel.TravelMessages;
import com.utilitysuite.world.overlays.ChunkAgeTracker;
import com.utilitysuite.world.overlays.TrailVector;
import com.utilitysuite.world.overlays.WorldScanner;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ElytraItem;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Travel Autopilot: routes to a waypoint or coordinates (one or more stops, see {@link RouteParser}) overland through
 * the navigator, by boat, by elytra, or along a Nether highway. Boat/elytra/bounce drive input through one TRAVEL lease
 * (MOVEMENT + CAMERA, plus WORLD_INTERACT + HOTBAR for rockets) and pause when it is preempted or the user takes the
 * keys, resuming when the arbiter reports the channels free. Health/food budgets stop the run (an elytra flight lands
 * first). Status lines go to the action bar on changes only.
 * <p>
 * The module being enabled only makes it available; a run starts with {@link #start()} (key "Travel autopilot") and
 * ends on arrival, failure, {@link #stop()}, Stop-All or leaving the world.
 */
public final class TravelAutopilot implements IModule, IConfigurable {

    public static final String ID = "travel_autopilot";

    static final String K_ROUTE = "route";
    static final String K_MODE = "mode";
    static final String K_MIN_HEALTH = "min_health";
    static final String K_MIN_FOOD = "min_food";
    static final String K_ARRIVAL = "arrival_radius";
    static final String K_RAY_RANGE = "boat_ray_range";
    static final String K_CLEARANCE = "elytra_clearance";
    static final String K_MIN_ALT = "elytra_min_altitude";
    static final String K_RESERVE = "rocket_reserve";
    static final String K_MIN_DURABILITY = "elytra_min_durability";
    static final String K_ROCKET_COOLDOWN = "rocket_cooldown";
    static final String K_LAND_TO_MEND = "elytra_land_to_mend";

    static final String K_HUB = "nether_hub";
    static final String K_HUB_MIN = "nether_hub_min_distance";
    static final String K_HUB_FACTOR = "nether_hub_cost_factor";

    private enum Run { IDLE, OVERLAND, BOAT, ELYTRA, BOUNCE, PORTAL_HUB, TRAIL }

    private static final int TRAIL_RADIUS_CHUNKS = 16;
    private static final int TRAIL_LOOKAHEAD_CHUNKS = 4;
    private static final int TRAIL_RETARGET_TICKS = 20;

    // Trail-following state
    private final TrailVector trailVector = new TrailVector();
    private double trailDirX;
    private double trailDirZ;
    private int trailRetargetTicks;
    private boolean trailFlight;
    private int trailTargetCx = Integer.MIN_VALUE;
    private int trailTargetCz = Integer.MIN_VALUE;

    // Nether-hub routing (overland runs only)
    private boolean hubEnabled = true;
    private int hubMinDistance = 1000;
    private double hubCostFactor = 1.5;
    private PortalHubRoute hub;
    private final BlockPos.MutableBlockPos portalProbe = new BlockPos.MutableBlockPos();

    private boolean enabled = true;
    private Run run = Run.IDLE;
    private RouteState route;
    private boolean landingForSafety;
    private boolean pausedNotified;

    private final LeaseSlot slot = new LeaseSlot(ID, Services::arbiter);
    private final LeaseRequest boatRequest =
            slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT, ControlChannel.CAMERA);
    private final LeaseRequest flightRequest = slot.request(ControlPriority.TRAVEL, 0, ControlChannel.MOVEMENT,
            ControlChannel.CAMERA, ControlChannel.WORLD_INTERACT, ControlChannel.HOTBAR);

    private final OverlandDriver overland = new OverlandDriver();
    private final BoatDriver boat = new BoatDriver();
    private final ElytraDriver elytra = new ElytraDriver();

    // settings
    private String routeSpec = "";
    private AutopilotMode mode = AutopilotMode.AUTO;
    private float minHealth = 8f;
    private int minFood = 6;

    // ---- IModule ------------------------------------------------------------------------------------------------------

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
        if (!enabled && run != Run.IDLE) {
            finish(null);
        }
    }

    @Override
    public void onTick() {
        if (run == Run.IDLE) {
            return;
        }
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        if (slot.terminated()) {
            finish("message.utilitysuite.autopilot.stopped");
            return;
        }
        SafetyBudget.Stop safety = SafetyBudget.check(player.getHealth(), player.getFoodData().getFoodLevel(),
                minHealth, minFood);
        if (safety != SafetyBudget.Stop.NONE) {
            if (run == Run.ELYTRA && player.isFallFlying()) {
                if (!landingForSafety) {
                    landingForSafety = true;
                    TravelMessages.status(safetyKey(safety));
                    elytra.requestLanding(player);
                }
            } else {
                finish(safetyKey(safety));
                return;
            }
        }
        switch (run) {
            case OVERLAND -> tickOverland(player);
            case BOAT -> tickBoat(player);
            case ELYTRA, BOUNCE -> tickElytra(player);
            case PORTAL_HUB -> tickHub(player);
            case TRAIL -> tickTrail(player);
            default -> {
            }
        }
    }

    @Override
    public void onStopAll() {
        if (run != Run.IDLE) {
            finish(null);
        }
    }

    @Override
    public void onWorldUnload() {
        overland.cancel();
        cancelNavigator();
        run = Run.IDLE;
        route = null;
        hub = null;
        trailTargetCx = Integer.MIN_VALUE;
        trailTargetCz = Integer.MIN_VALUE;
        trailDirX = 0.0;
        trailDirZ = 0.0;
        trailFlight = false;
        slot.reset();
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        readSettings(profile);
    }

    // ---- control ------------------------------------------------------------------------------------------------------

    public boolean isRunning() {
        return run != Run.IDLE;
    }

    /** Current run kind (IDLE, OVERLAND, BOAT, ELYTRA, BOUNCE) for status displays. */
    public String runName() {
        return run.name();
    }

    /**
     * The elytra driver's phase while an elytra or bounce run is active ({@code ELYTRA_CRUISE}, {@code BOUNCE}, ...),
     * the run kind otherwise ({@code OVERLAND}, {@code BOAT}); null when idle. Constant strings.
     */
    @Override
    public String phase() {
        Run r = run;
        if (r == Run.IDLE) {
            return null;
        }
        PortalHubRoute h = hub;
        if (r == Run.PORTAL_HUB && h != null) {
            return h.phase().name();
        }
        if (r == Run.TRAIL) {
            return trailFlight ? elytra.phaseLabel() : "TRAIL";
        }
        return r == Run.ELYTRA || r == Run.BOUNCE ? elytra.phaseLabel() : r.name();
    }

    /** Key handler: starts the configured route, or stops a running one. */
    public void toggle() {
        if (run != Run.IDLE) {
            stop();
        } else {
            start();
        }
    }

    /** Starts the route from the settings. */
    public boolean start() {
        return start(routeSpec);
    }

    /** Starts a route ("x y z", "x z", waypoint names; stops separated by ; or >). */
    public boolean start(String spec) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        if (!enabled) {
            TravelMessages.status("message.utilitysuite.autopilot.disabled");
            return false;
        }
        if (Services.arbiter().isAutomationStopped()) {
            TravelMessages.status("message.utilitysuite.travel.automation_stopped");
            return false;
        }
        if (mode == AutopilotMode.TRAIL) {
            return startTrailFollow();
        }
        String dimension = mc.level.dimension().location().toString();
        RouteParser.Result parsed = RouteParser.parse(spec, name -> resolveWaypoint(name, dimension));
        if (!parsed.ok()) {
            TravelMessages.status("message.utilitysuite.autopilot.bad_route", parsed.error());
            return false;
        }
        if (run != Run.IDLE) {
            finish(null);
        }
        Run chosen = choose(player, mc);
        if (chosen == null) {
            return false;
        }
        route = new RouteState(parsed.stops());
        slot.reset();
        landingForSafety = false;
        pausedNotified = false;
        hub = null;
        if (chosen == Run.OVERLAND && parsed.stops().size() == 1
                && tryStartHub(player, dimension, parsed.stops().get(0))) {
            return true;
        }
        switch (chosen) {
            case OVERLAND -> overland.begin();
            case BOAT -> boat.begin();
            case ELYTRA -> elytra.begin(false);
            case BOUNCE -> elytra.begin(true);
            default -> {
            }
        }
        run = chosen;
        TravelMessages.status("message.utilitysuite.autopilot.started", chosen.name().toLowerCase(java.util.Locale.ROOT),
                route.label());
        return true;
    }

    public void stop() {
        if (run != Run.IDLE) {
            finish("message.utilitysuite.autopilot.stopped");
        }
    }

    /**
     * Starts following the dominant {@link ChunkAgeTracker#OLD} exploration trail near the player using
     * {@link TrailVector} lookahead targets via {@link ElytraDriver} (if wearing an elytra without a ceiling) or
     * {@link OverlandDriver}, under the {@code TRAVEL} lease.
     */
    public boolean startTrailFollow() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null) {
            return false;
        }
        if (!enabled) {
            TravelMessages.status("message.utilitysuite.autopilot.disabled");
            return false;
        }
        if (Services.arbiter().isAutomationStopped()) {
            TravelMessages.status("message.utilitysuite.travel.automation_stopped");
            return false;
        }
        ChunkAgeTracker tracker = WorldScanner.activeChunkAge();
        if (tracker == null) {
            TravelMessages.status("message.utilitysuite.autopilot.no_trail");
            return false;
        }
        int pcx = player.getBlockX() >> 4;
        int pcz = player.getBlockZ() >> 4;
        if (!trailVector.estimate(tracker, pcx, pcz, TRAIL_RADIUS_CHUNKS, TRAIL_LOOKAHEAD_CHUNKS, 0.0, 0.0)
                || !trailVector.valid()) {
            TravelMessages.status("message.utilitysuite.autopilot.no_trail");
            return false;
        }
        boolean canFly = player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem
                && !mc.level.dimensionType().hasCeiling();
        if (!canFly) {
            INavigator nav = Services.navigatorOrNull();
            if (nav == null || !nav.isAvailable()) {
                TravelMessages.status("message.utilitysuite.autopilot.no_navigator");
                return false;
            }
        }
        if (run != Run.IDLE) {
            finish(null);
        }
        slot.reset();
        landingForSafety = false;
        pausedNotified = false;
        hub = null;
        trailFlight = canFly;
        trailDirX = trailVector.dx();
        trailDirZ = trailVector.dz();
        trailTargetCx = trailVector.targetChunkX();
        trailTargetCz = trailVector.targetChunkZ();
        trailRetargetTicks = 0;
        route = new RouteState(List.of(new RouteParser.Stop(
                (trailTargetCx << 4) + 8, player.getBlockY(), (trailTargetCz << 4) + 8, false, "Trail")));
        if (trailFlight) {
            elytra.begin(false);
        } else {
            overland.begin();
        }
        run = Run.TRAIL;
        TravelMessages.status("message.utilitysuite.autopilot.started", "trail", route.label());
        return true;
    }

    private Run choose(LocalPlayer player, Minecraft mc) {
        AutopilotMode m = mode;
        if (m == AutopilotMode.AUTO) {
            if (player.getVehicle() instanceof Boat) {
                m = AutopilotMode.BOAT;
            } else if (player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem) {
                m = mc.level.dimensionType().hasCeiling() ? AutopilotMode.NETHER_BOUNCE : AutopilotMode.ELYTRA;
            } else {
                m = AutopilotMode.OVERLAND;
            }
        }
        switch (m) {
            case BOAT -> {
                if (!(player.getVehicle() instanceof Boat)) {
                    TravelMessages.status("message.utilitysuite.autopilot.not_in_boat");
                    return null;
                }
                return Run.BOAT;
            }
            case ELYTRA -> {
                if (!(player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem)) {
                    TravelMessages.status("message.utilitysuite.autopilot.no_elytra");
                    return null;
                }
                if (mc.level.dimensionType().hasCeiling()) {
                    TravelMessages.status("message.utilitysuite.autopilot.ceiling");
                    return null;
                }
                return Run.ELYTRA;
            }
            case NETHER_BOUNCE -> {
                if (!(player.getItemBySlot(EquipmentSlot.CHEST).getItem() instanceof ElytraItem)) {
                    TravelMessages.status("message.utilitysuite.autopilot.no_elytra");
                    return null;
                }
                return Run.BOUNCE;
            }
            default -> {
                INavigator nav = Services.navigatorOrNull();
                if (nav == null || !nav.isAvailable()) {
                    TravelMessages.status("message.utilitysuite.autopilot.no_navigator");
                    return null;
                }
                return Run.OVERLAND;
            }
        }
    }

    private static RouteParser.Stop resolveWaypoint(String name, String dimension) {
        IWaypointService wps = Services.waypointsOrNull();
        if (wps == null) {
            return null;
        }
        Optional<Waypoint> wp = wps.byName(name);
        if (wp.isEmpty()) {
            return null;
        }
        long p = wps.positionIn(wp.get(), dimension);
        return new RouteParser.Stop(BlockPos.getX(p), BlockPos.getY(p), BlockPos.getZ(p), true, wp.get().name());
    }

    // ---- per-mode ticks -----------------------------------------------------------------------------------------------

    private void tickOverland(LocalPlayer player) {
        INavigator nav = Services.navigatorOrNull();
        if (nav == null || !nav.isAvailable()) {
            finish("message.utilitysuite.autopilot.no_navigator");
            return;
        }
        switch (overland.tick(player, route, nav)) {
            case ARRIVED -> finish("message.utilitysuite.autopilot.arrived");
            case FAILED -> finish("message.utilitysuite.autopilot.path_failed");
            // Lease refused, preempted, overridden by the user or revoked: the run already cancelled the pathfinder.
            case INTERRUPTED -> finish("message.utilitysuite.autopilot.stopped");
            default -> {
            }
        }
    }

    // ---- nether hub -----------------------------------------------------------------------------------------------

    /**
     * Waypoint "Go": starts a Nether-hub route to an overworld position when the player is in the overworld, hub
     * routing is on and a portal route is cheaper than walking. Returns false (nothing started) otherwise, so the
     * caller paths directly.
     */
    public boolean goViaNetherHub(int x, int y, int z, String label) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || !enabled || !hubEnabled
                || Services.arbiter().isAutomationStopped()) {
            return false;
        }
        INavigator nav = Services.navigatorOrNull();
        if (nav == null || !nav.isAvailable() || dimensionOf(player) != PortalHubPlanner.OVERWORLD) {
            return false;
        }
        if (run != Run.IDLE) {
            finish(null);
        }
        slot.reset();
        landingForSafety = false;
        pausedNotified = false;
        return tryStartHub(player, PortalHubPlanner.OVERWORLD, new RouteParser.Stop(x, y, z, true, label));
    }

    private boolean tryStartHub(LocalPlayer player, String dimension, RouteParser.Stop target) {
        hub = null;
        if (!hubEnabled || !PortalHubPlanner.OVERWORLD.equals(dimension)) {
            return false;
        }
        PortalHubPlanner.Plan plan = PortalHubPlanner.plan(player.getBlockX(), player.getBlockZ(), target.x(),
                target.hasY() ? target.y() : player.getBlockY(), target.z(), recordedPortals(),
                new PortalHubPlanner.Config(hubMinDistance, hubCostFactor, 64, 256));
        if (plan == null) {
            return false;
        }
        hub = new PortalHubRoute(plan, target);
        run = Run.PORTAL_HUB;
        startHubLeg(player);
        TravelMessages.status("message.utilitysuite.autopilot.hub_started", target.label(),
                (int) plan.directCost(), (int) plan.hubCost());
        return true;
    }

    /** Recorded Portals waypoints (overworld and nether), read once when a route starts. */
    private static List<PortalHubPlanner.Portal> recordedPortals() {
        IWaypointService wps = Services.waypointsOrNull();
        if (wps == null) {
            return List.of();
        }
        List<PortalHubPlanner.Portal> out = new ArrayList<>();
        for (Waypoint w : wps.all()) {
            if (PortalHubPlanner.GROUP_PORTALS.equals(w.group())) {
                out.add(new PortalHubPlanner.Portal(w.dimension(), w.x(), w.y(), w.z()));
            }
        }
        return out;
    }

    /** Interned dimension id without allocating (resource keys are canonical). */
    private static String dimensionOf(LocalPlayer player) {
        ResourceKey<Level> key = player.level().dimension();
        if (key == Level.NETHER) {
            return PortalHubPlanner.NETHER;
        }
        return key == Level.OVERWORLD ? PortalHubPlanner.OVERWORLD : "other";
    }

    /** Starts a fresh leased navigator leg for the hub's current walking phase (a new lease after a dimension change). */
    private void startHubLeg(LocalPlayer player) {
        RouteParser.Stop s = hub.currentStop();
        if (s == null) {
            overland.cancel();
            return;
        }
        if (!s.hasY() && PortalHubPlanner.NETHER.equals(hub.expectedDimension())) {
            // No surface heightmap in the nether (it would return the roof): keep the player's height.
            s = new RouteParser.Stop(s.x(), player.getBlockY(), s.z(), true, s.label());
        }
        route = new RouteState(List.of(s));
        if (hub.legEndsInPortal()) {
            overland.begin(0);
        } else {
            overland.begin();
        }
        hubStatus();
    }

    private void hubStatus() {
        switch (hub.phase()) {
            case TO_ENTRY -> TravelMessages.status("message.utilitysuite.autopilot.hub_to_entry",
                    hub.plan().entry().x(), hub.plan().entry().z());
            case IN_ENTRY_PORTAL, IN_EXIT_PORTAL -> TravelMessages.status("message.utilitysuite.autopilot.hub_portal");
            case NETHER_LEG -> TravelMessages.status("message.utilitysuite.autopilot.hub_nether",
                    hub.plan().exitNether().x(), hub.plan().exitNether().z());
            case AWAIT_PORTAL -> TravelMessages.status("message.utilitysuite.autopilot.hub_await_portal");
            case FINAL_LEG -> TravelMessages.status("message.utilitysuite.autopilot.hub_final", route.label());
            default -> {
            }
        }
    }

    private void tickHub(LocalPlayer player) {
        PortalHubRoute h = hub;
        if (h == null) {
            finish(null);
            return;
        }
        if (player.isDeadOrDying()) {
            finish("message.utilitysuite.autopilot.stopped");
            return;
        }
        if (h.onDimension(dimensionOf(player))) {
            overland.cancel();
            if (h.phase() == PortalHubRoute.Phase.FAILED) {
                finish("message.utilitysuite.autopilot.hub_failed_" + h.failure());
                return;
            }
            startHubLeg(player);
            return;
        }
        if (h.isWalking()) {
            INavigator nav = Services.navigatorOrNull();
            if (nav == null || !nav.isAvailable()) {
                finish("message.utilitysuite.autopilot.no_navigator");
                return;
            }
            switch (overland.tick(player, route, nav)) {
                case ARRIVED -> {
                    h.onLegArrived();
                    if (h.phase() == PortalHubRoute.Phase.DONE) {
                        finish("message.utilitysuite.autopilot.arrived");
                    } else {
                        hubStatus();
                    }
                }
                case FAILED -> finish("message.utilitysuite.autopilot.path_failed");
                case INTERRUPTED -> finish("message.utilitysuite.autopilot.stopped");
                default -> {
                }
            }
            return;
        }
        // Waiting in (or for) a portal: two block-state reads into a reused position, no allocation.
        int x = player.getBlockX();
        int y = player.getBlockY();
        int z = player.getBlockZ();
        Level level = player.level();
        boolean inPortal = level.getBlockState(portalProbe.set(x, y, z)).is(Blocks.NETHER_PORTAL)
                || level.getBlockState(portalProbe.set(x, y + 1, z)).is(Blocks.NETHER_PORTAL);
        h.tickWait(inPortal);
        if (h.phase() == PortalHubRoute.Phase.FAILED) {
            finish("message.utilitysuite.autopilot.hub_failed_" + h.failure());
        }
    }

    private void tickBoat(LocalPlayer player) {
        ControlLease lease = leaseOrPause(boatRequest);
        if (lease == null) {
            return;
        }
        switch (boat.tick(player, route, Services.input(), lease)) {
            case ARRIVED -> finish("message.utilitysuite.autopilot.arrived");
            case LEFT_BOAT -> finish("message.utilitysuite.autopilot.left_boat");
            default -> {
            }
        }
    }

    private void tickElytra(LocalPlayer player) {
        ControlLease lease = leaseOrPause(flightRequest);
        if (lease == null) {
            return;
        }
        switch (elytra.tick(player, route, Services.input(), lease)) {
            case LANDED -> finish(landingForSafety ? "message.utilitysuite.autopilot.landed_safety"
                    : "message.utilitysuite.autopilot.landed");
            case FAILED -> finish(elytra.failure());
            default -> {
            }
        }
    }

    private void tickTrail(LocalPlayer player) {
        ChunkAgeTracker tracker = WorldScanner.activeChunkAge();
        if (tracker != null && ++trailRetargetTicks % TRAIL_RETARGET_TICKS == 0) {
            int pcx = player.getBlockX() >> 4;
            int pcz = player.getBlockZ() >> 4;
            if (trailVector.estimate(tracker, pcx, pcz, TRAIL_RADIUS_CHUNKS, TRAIL_LOOKAHEAD_CHUNKS,
                    trailDirX, trailDirZ) && trailVector.valid()) {
                trailDirX = trailVector.dx();
                trailDirZ = trailVector.dz();
                int tx = trailVector.targetChunkX();
                int tz = trailVector.targetChunkZ();
                if (tx != trailTargetCx || tz != trailTargetCz) {
                    trailTargetCx = tx;
                    trailTargetCz = tz;
                    route = new RouteState(List.of(new RouteParser.Stop(
                            (tx << 4) + 8, player.getBlockY(), (tz << 4) + 8, false, "Trail")));
                    if (!trailFlight) {
                        overland.begin();
                    }
                }
            }
        }
        if (trailFlight) {
            tickElytra(player);
            return;
        }
        INavigator nav = Services.navigatorOrNull();
        if (nav == null || !nav.isAvailable()) {
            finish("message.utilitysuite.autopilot.no_navigator");
            return;
        }
        switch (overland.tick(player, route, nav)) {
            case ARRIVED -> {
                if (tracker != null && trailVector.estimate(tracker, player.getBlockX() >> 4,
                        player.getBlockZ() >> 4, TRAIL_RADIUS_CHUNKS, TRAIL_LOOKAHEAD_CHUNKS,
                        trailDirX, trailDirZ) && trailVector.valid()
                        && (trailVector.targetChunkX() != trailTargetCx
                        || trailVector.targetChunkZ() != trailTargetCz)) {
                    trailDirX = trailVector.dx();
                    trailDirZ = trailVector.dz();
                    trailTargetCx = trailVector.targetChunkX();
                    trailTargetCz = trailVector.targetChunkZ();
                    route = new RouteState(List.of(new RouteParser.Stop(
                            (trailTargetCx << 4) + 8, player.getBlockY(), (trailTargetCz << 4) + 8, false, "Trail")));
                    overland.begin();
                } else {
                    finish("message.utilitysuite.autopilot.arrived");
                }
            }
            case FAILED -> finish("message.utilitysuite.autopilot.path_failed");
            case INTERRUPTED -> finish("message.utilitysuite.autopilot.stopped");
            default -> {
            }
        }
    }

    private ControlLease leaseOrPause(LeaseRequest request) {
        ControlLease lease = slot.ensure(request);
        if (lease == null) {
            if (slot.isSuspended() && !pausedNotified) {
                pausedNotified = true;
                TravelMessages.status("message.utilitysuite.autopilot.paused");
            }
            return null;
        }
        if (pausedNotified) {
            pausedNotified = false;
            TravelMessages.status("message.utilitysuite.autopilot.resumed");
        }
        return lease;
    }

    private void finish(String messageKey) {
        IInputSmoother input = Services.input();
        ControlLease lease = slot.active();
        if (run == Run.BOAT) {
            boat.release(input, lease);
        } else if (run == Run.ELYTRA || run == Run.BOUNCE || (run == Run.TRAIL && trailFlight)) {
            elytra.release(input, lease);
        } else if (run == Run.OVERLAND || run == Run.PORTAL_HUB || run == Run.TRAIL) {
            overland.cancel();
        }
        hub = null;
        trailFlight = false;
        if (lease != null) {
            input.clearRotation(lease);
        }
        slot.reset();
        run = Run.IDLE;
        route = null;
        if (messageKey != null && !messageKey.isEmpty()) {
            TravelMessages.status(messageKey);
        }
    }

    private static void cancelNavigator() {
        INavigator nav = Services.navigatorOrNull();
        if (nav != null && nav.isAvailable()) {
            nav.cancel();
        }
    }

    private static String safetyKey(SafetyBudget.Stop s) {
        return s == SafetyBudget.Stop.LOW_HEALTH ? "message.utilitysuite.autopilot.low_health"
                : "message.utilitysuite.autopilot.low_food";
    }

    // ---- settings -----------------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Travel Autopilot";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.text(K_ROUTE, "Route", "", "",
                        "Destination: \"x y z\", \"x z\" or a waypoint name. Several stops: separate with ; or >."),
                SettingSpec.choice(K_MODE, "Mode", AutopilotMode.AUTO, "",
                        "AUTO: boat when riding one, elytra when wearing one, otherwise overland."),
                SettingSpec.decimal(K_MIN_HEALTH, "Stop at health", 8.0, 0.0, 20.0, "Safety",
                        "Stop (or land, when flying) at or below this health. 0 disables."),
                SettingSpec.integer(K_MIN_FOOD, "Stop at food", 6, 0, 20, "Safety",
                        "Stop (or land) at or below this food level. 0 disables."),
                SettingSpec.integer(K_ARRIVAL, "Arrival radius", 3, 1, 16, "",
                        "Blocks from a stop that count as arrived."),
                SettingSpec.integer(K_RAY_RANGE, "Water look-ahead", 24, 8, 64, "Boat",
                        "How far the boat samples open water ahead.").asAdvanced(),
                SettingSpec.integer(K_CLEARANCE, "Terrain clearance", 24, 8, 96, "Elytra",
                        "Blocks to stay above the terrain ahead."),
                SettingSpec.integer(K_MIN_ALT, "Minimum cruise height", 0, 0, 320, "Elytra",
                        "Never cruise below this y level (0 = terrain clearance only)."),
                SettingSpec.integer(K_RESERVE, "Rocket reserve", 3, 0, 64, "Elytra",
                        "Abort cruise and land when only this many rockets are left in the hotbar."),
                SettingSpec.integer(K_MIN_DURABILITY, "Elytra reserve", 20, 1, 400, "Elytra",
                        "Abort cruise and land when the elytra has this many seconds of flight left."),
                SettingSpec.integer(K_ROCKET_COOLDOWN, "Rocket spacing (ticks)", 30, 10, 200, "Elytra",
                        "Minimum ticks between rockets.").asAdvanced(),
                SettingSpec.bool(K_LAND_TO_MEND, "Land to mend", false, "Elytra",
                        "At the elytra reserve with no spare elytra: if the worn one has Mending and bottles o' "
                                + "enchanting are in the hotbar, land, throw them at your feet until it is repaired, "
                                + "then take off again."),
                SettingSpec.bool(K_HUB, "Nether hub routing", true, "Nether hub",
                        "Overland trips to a far overworld destination go through recorded Portals waypoints when "
                                + "that is cheaper: walk to a portal, cross the Nether at 1:8, take a portal back. "
                                + "Never lights or builds portals."),
                SettingSpec.integer(K_HUB_MIN, "Minimum distance", 1000, 200, 30000, "Nether hub",
                        "Only consider a Nether route for destinations at least this far (overworld blocks)."),
                SettingSpec.decimal(K_HUB_FACTOR, "Nether cost factor", 1.5, 1.0, 8.0, "Nether hub",
                        "How much one Nether block costs compared with one overworld block (hazards, slow ground).")
                        .asAdvanced());
    }

    @Override
    public void onSettingChanged(String key) {
        readSettings(Services.modState().profile());
    }

    private void readSettings(ProfileConfig p) {
        routeSpec = p.getString(ID, K_ROUTE, "");
        try {
            mode = AutopilotMode.valueOf(p.getString(ID, K_MODE, AutopilotMode.AUTO.name()));
        } catch (IllegalArgumentException e) {
            mode = AutopilotMode.AUTO;
        }
        minHealth = (float) p.getDouble(ID, K_MIN_HEALTH, 8.0);
        minFood = p.getInt(ID, K_MIN_FOOD, 6);
        hubEnabled = p.getBool(ID, K_HUB, true);
        hubMinDistance = p.getInt(ID, K_HUB_MIN, 1000);
        hubCostFactor = p.getDouble(ID, K_HUB_FACTOR, 1.5);
        int arrival = p.getInt(ID, K_ARRIVAL, 3);
        overland.configure(arrival);
        boat.configure(p.getInt(ID, K_RAY_RANGE, 24), arrival);
        elytra.configure(p.getInt(ID, K_CLEARANCE, 24), p.getInt(ID, K_MIN_ALT, 0), p.getInt(ID, K_RESERVE, 3),
                p.getInt(ID, K_MIN_DURABILITY, 20), p.getInt(ID, K_ROCKET_COOLDOWN, 30), arrival);
        elytra.setLandToMend(p.getBool(ID, K_LAND_TO_MEND, false));
    }
}
