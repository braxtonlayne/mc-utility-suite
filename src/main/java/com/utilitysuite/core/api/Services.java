package com.utilitysuite.core.api;

import java.util.Objects;

/**
 * Service locator for the shared contracts. Implementations register during mod construction (core first, then
 * feature modules). Consumers fetch lazily, never in static initializers.
 */
public final class Services {

    private static volatile IActionArbiter arbiter;
    private static volatile IInputSmoother input;
    private static volatile IContainerClicker clicker;
    private static volatile IModState modState;
    private static volatile IModuleRegistry modules;
    private static volatile ISuiteEventBus events;
    private static volatile IGoalEngine goals;
    private static volatile INavigator navigator;
    private static volatile IWorldKnowledgeService worldKnowledge;
    private static volatile IStorageIndex storage;
    private static volatile IWaypointService waypoints;
    private static volatile IAutopilot autopilot;
    private static volatile IHumanizer humanizer;
    private static volatile IBodySimProvider bodySims;
    private static volatile IRoutePlanner routePlanner;
    private static volatile IRouteRunner routeRunner;
    private static volatile IManualLookAssist manualLook;

    private Services() {
    }

    public static IActionArbiter arbiter() { return require(arbiter, "IActionArbiter"); }
    public static IInputSmoother input() { return require(input, "IInputSmoother"); }
    public static IContainerClicker clicker() { return require(clicker, "IContainerClicker"); }
    public static IModState modState() { return require(modState, "IModState"); }
    public static IModuleRegistry modules() { return require(modules, "IModuleRegistry"); }
    public static ISuiteEventBus events() { return require(events, "ISuiteEventBus"); }
    public static IGoalEngine goals() { return require(goals, "IGoalEngine"); }
    public static INavigator navigator() { return require(navigator, "INavigator"); }
    public static IWorldKnowledgeService worldKnowledge() { return require(worldKnowledge, "IWorldKnowledgeService"); }
    public static IStorageIndex storage() { return require(storage, "IStorageIndex"); }
    public static IWaypointService waypoints() { return require(waypoints, "IWaypointService"); }

    /** Null-returning variants for optional collaborators (a module may be absent or not built yet). */
    public static IWorldKnowledgeService worldKnowledgeOrNull() { return worldKnowledge; }
    public static IStorageIndex storageOrNull() { return storage; }
    public static IWaypointService waypointsOrNull() { return waypoints; }
    public static INavigator navigatorOrNull() { return navigator; }
    public static IGoalEngine goalsOrNull() { return goals; }
    /** The opt-in autopilot, or null before the core is up (reflexes then act only through their own module). */
    public static IAutopilot autopilotOrNull() { return autopilot; }
    public static ISuiteEventBus eventsOrNull() { return events; }
    /** Human-like timing and aim variation. Never null: {@link IHumanizer#NEUTRAL} until the core registers one. */
    public static IHumanizer humanizer() { IHumanizer h = humanizer; return h != null ? h : IHumanizer.NEUTRAL; }
    /** The autopilot's danger model. Never null: {@link IDangerModel#NONE} while there is no autopilot. */
    public static IDangerModel danger() { IAutopilot a = autopilot; return a != null ? a.danger() : IDangerModel.NONE; }

    /** Forward simulation of player bodies against the live level, or null before the movement module is up. */
    public static IBodySimProvider bodySimsOrNull() { return bodySims; }
    /** Plan-only pathing, or null when no path backend registered one. */
    public static IRoutePlanner routePlannerOrNull() { return routePlanner; }
    /** Pursuit / flee route runner, or null before the movement module is up. */
    public static IRouteRunner routeRunnerOrNull() { return routeRunner; }
    /** Turn gain on the user's own mouse. Never null: {@link IManualLookAssist#NONE} until the core registers one. */
    public static IManualLookAssist manualLook() {
        IManualLookAssist m = manualLook;
        return m != null ? m : IManualLookAssist.NONE;
    }

    public static void registerArbiter(IActionArbiter s) { arbiter = s; }
    public static void registerInput(IInputSmoother s) { input = s; }
    public static void registerClicker(IContainerClicker s) { clicker = s; }
    public static void registerModState(IModState s) { modState = s; }
    public static void registerModules(IModuleRegistry s) { modules = s; }
    public static void registerEvents(ISuiteEventBus s) { events = s; }
    public static void registerGoals(IGoalEngine s) { goals = s; }
    public static void registerNavigator(INavigator s) { navigator = s; }
    public static void registerWorldKnowledge(IWorldKnowledgeService s) { worldKnowledge = s; }
    public static void registerStorage(IStorageIndex s) { storage = s; }
    public static void registerWaypoints(IWaypointService s) { waypoints = s; }
    public static void registerAutopilot(IAutopilot s) { autopilot = s; }
    public static void registerHumanizer(IHumanizer s) { humanizer = s; }
    public static void registerBodySims(IBodySimProvider s) { bodySims = s; }
    public static void registerRoutePlanner(IRoutePlanner s) { routePlanner = s; }
    public static void registerRouteRunner(IRouteRunner s) { routeRunner = s; }
    public static void registerManualLook(IManualLookAssist s) { manualLook = s; }

    /** Test hook: clears every registration. */
    public static void resetForTests() {
        arbiter = null; input = null; clicker = null; modState = null; modules = null; events = null; goals = null;
        navigator = null; worldKnowledge = null; storage = null; waypoints = null; autopilot = null; humanizer = null;
        bodySims = null; routePlanner = null; routeRunner = null; manualLook = null;
    }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " is not registered yet");
    }
}
