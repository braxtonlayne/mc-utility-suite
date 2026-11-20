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
    public static ISuiteEventBus eventsOrNull() { return events; }

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

    /** Test hook: clears every registration. */
    public static void resetForTests() {
        arbiter = null; input = null; clicker = null; modState = null; modules = null; events = null; goals = null;
        navigator = null; worldKnowledge = null; storage = null; waypoints = null;
    }

    private static <T> T require(T value, String name) {
        return Objects.requireNonNull(value, () -> name + " is not registered yet");
    }
}
