package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Services;

/** Scenario 1 (in-world part): profile id and world setup. */
final class BootScenario {

    static double[] spawn;

    private BootScenario() {
    }

    static Scenario inWorld() {
        Scenario s = new Scenario("1-world", "Boot: world profile + test world setup");
        s.run("profile id", () -> {
            String id = Services.modState().profile().profileId();
            s.check("profile id is " + Scenarios.PROFILE_ID, Scenarios.PROFILE_ID.equals(id), "profile=" + id);
            s.check("automation not stopped at start", !Services.arbiter().isAutomationStopped(), "stopped="
                    + Services.arbiter().isAutomationStopped());
            spawn = H.pos();
            s.note("spawn " + H.posStr() + " in " + H.mc().level.dimension().location());
        });
        s.cmd("gamerule doDaylightCycle false");
        s.cmd("gamerule doWeatherCycle false");
        s.cmd("gamerule doMobSpawning false");
        s.cmd("gamerule doInsomnia false");
        s.cmd("time set 6000");
        s.cmd("weather clear");
        s.cmd("effect give @s minecraft:saturation 5 10 true");
        return s;
    }
}
