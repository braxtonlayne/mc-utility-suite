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
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.item.ElytraItem;

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

    private enum Run { IDLE, OVERLAND, BOAT, ELYTRA, BOUNCE }

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
        } else if (run == Run.ELYTRA || run == Run.BOUNCE) {
            elytra.release(input, lease);
        } else if (run == Run.OVERLAND) {
            overland.cancel();
        }
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
                                + "then take off again."));
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
        int arrival = p.getInt(ID, K_ARRIVAL, 3);
        overland.configure(arrival);
        boat.configure(p.getInt(ID, K_RAY_RANGE, 24), arrival);
        elytra.configure(p.getInt(ID, K_CLEARANCE, 24), p.getInt(ID, K_MIN_ALT, 0), p.getInt(ID, K_RESERVE, 3),
                p.getInt(ID, K_MIN_DURABILITY, 20), p.getInt(ID, K_ROCKET_COOLDOWN, 30), arrival);
        elytra.setLandToMend(p.getBool(ID, K_LAND_TO_MEND, false));
    }
}
