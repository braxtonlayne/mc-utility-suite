package com.utilitysuite.devtest;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.config.ProfileIds;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** The scenario list, in run order. Each scenario is built when it starts (so it can read the live world). */
final class Scenarios {

    static final String PROFILE_ID = ProfileIds.singleplayer(Harness.WORLD);

    private Scenarios() {
    }

    /** With -PplaytestOnly=Name1,Name2 (scenario class names, e.g. Parkour), only those run (boot always runs). */
    static void addAll(List<Supplier<Scenario>> q) {
        add(q, "BootScenario", BootScenario::inWorld);
        add(q, "MenuScenario", MenuScenario::build);
        add(q, "ArbiterScenario", ArbiterScenario::build);
        add(q, "GoalScenario", GoalScenario::build);
        add(q, "SurvivalScenario", SurvivalScenario::build);
        add(q, "SchematicScenario", SchematicScenario::build);
        add(q, "WorldScenario", WorldScenario::build);
        add(q, "TravelScenario", TravelScenario::build);
        add(q, "ParkourScenario", ParkourScenario::build);
        add(q, "CombatScenario", CombatScenario::build);
        add(q, "CombatDepthScenario", CombatDepthScenario::build);
        add(q, "ToolsScenario", ToolsScenario::build);
        add(q, "ModulesScenario", ModulesScenario::build);
        add(q, "ControlsScenario", ControlsScenario::build);
        add(q, "FieldJobsScenario", FieldJobsScenario::build);
        add(q, "StationJobsScenario", StationJobsScenario::build);
        add(q, "FeaturesScenario", FeaturesScenario::build);
        add(q, "ReconScenario", ReconScenario::build);
        add(q, "WorldExtraScenario", WorldExtraScenario::build);
        add(q, "ToolsExtraScenario", ToolsExtraScenario::build);
        add(q, "CombatExtraScenario", CombatExtraScenario::build);
        add(q, "PerfScenario", PerfScenario::build);
    }

    private static void add(List<Supplier<Scenario>> q, String cls, Supplier<Scenario> s) {
        String only = System.getProperty("utilitysuite.playtestOnly", "");
        if (only.isEmpty() || cls.equals("BootScenario")
                || ("," + only + ",").contains("," + cls.replace("Scenario", "") + ",")) {
            q.add(s);
        }
    }

    /** Runs on the title screen before the world is created. */
    static void bootPreWorld(Scenario s) throws Exception {
        s.startNanos = System.nanoTime();
        String[] names = {"arbiter", "input", "clicker", "modState", "modules", "events", "goals", "navigator",
                "worldKnowledge", "storage", "waypoints"};
        List<Supplier<Object>> getters = List.of(Services::arbiter, Services::input, Services::clicker,
                Services::modState, Services::modules, Services::events, Services::goals, Services::navigator,
                Services::worldKnowledge, Services::storage, Services::waypoints);
        List<String> missing = new ArrayList<>();
        StringBuilder impls = new StringBuilder();
        for (int i = 0; i < names.length; i++) {
            try {
                Object o = getters.get(i).get();
                impls.append(names[i]).append('=').append(o.getClass().getSimpleName()).append(' ');
            } catch (RuntimeException e) {
                missing.add(names[i]);
            }
        }
        s.check("every Services entry registered", missing.isEmpty(),
                missing.isEmpty() ? impls.toString().trim() : "missing: " + missing);
        StringBuilder mods = new StringBuilder();
        for (IModule m : Services.modules().all()) {
            mods.append(m.id()).append(m.isEnabled() ? "(on) " : "(off) ");
        }
        s.check("module list", Services.modules().all().size() >= 18,
                Services.modules().all().size() + " modules: " + mods.toString().trim());
        // Start from a clean per-world profile so the "written on leave" check is meaningful.
        Path profileFile = CoreBootstrap.configStore().fileFor(PROFILE_ID);
        boolean existed = Files.deleteIfExists(profileFile);
        s.note("profile file " + profileFile + (existed ? " deleted before the run" : " did not exist before the run"));
        s.endNanos = System.nanoTime();
    }

    /** Runs after leaving the world. */
    static void afterLeave(Scenario s) throws Exception {
        s.startNanos = System.nanoTime();
        Path profileFile = CoreBootstrap.configStore().fileFor(PROFILE_ID);
        Path global = CoreBootstrap.configStore().fileFor(ProfileIds.GLOBAL);
        boolean pf = Files.isRegularFile(profileFile);
        s.check("per-world profile written on leave", pf,
                pf ? profileFile + " (" + Files.size(profileFile) + " bytes)" : "missing " + profileFile);
        boolean gf = Files.isRegularFile(global);
        s.check("global profile written", gf, gf ? global + " (" + Files.size(global) + " bytes)" : "missing");
        if (pf) {
            String json = Files.readString(profileFile);
            s.check("profile JSON has module sections", json.contains("\"enabled\""),
                    json.length() > 300 ? json.substring(0, 300) + "..." : json);
        }
        boolean stillActive = CoreBootstrap.modState().hasActiveProfile();
        s.check("profile unloaded after leaving", !stillActive, "hasActiveProfile=" + stillActive);
        s.endNanos = System.nanoTime();
    }
}
