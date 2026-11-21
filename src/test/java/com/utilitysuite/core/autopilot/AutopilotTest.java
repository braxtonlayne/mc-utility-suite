package com.utilitysuite.core.autopilot;

import com.google.gson.JsonObject;
import com.utilitysuite.core.api.IAutopilot.Reach;
import com.utilitysuite.core.api.IAutopilot.Reflex;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AutopilotTest {

    private static Autopilot loaded(ProfileConfig p) {
        Autopilot a = new Autopilot();
        a.onProfileLoaded(p);
        return a;
    }

    @Test
    void offByDefaultAndAllowsNothing() {
        Autopilot a = loaded(new ProfileConfig("t", new JsonObject()));
        assertFalse(a.isEnabled(), "opt-in: off until the user turns it on");
        for (Reflex r : Reflex.values()) {
            assertFalse(a.allows(r), r.name());
        }
    }

    @Test
    void onWithDefaultsAllowsEveryReflex() {
        Autopilot a = loaded(new ProfileConfig("t", new JsonObject()));
        a.setEnabled(true);
        for (Reflex r : Reflex.values()) {
            assertTrue(a.allows(r), r.name());
        }
    }

    @Test
    void dangerOnlyKeepsTheClutchButNotTheRunHelpers() {
        ProfileConfig p = new ProfileConfig("t", new JsonObject());
        p.set(Autopilot.ID, Autopilot.K_REACH, Reach.DANGER_ONLY.name());
        Autopilot a = loaded(p);
        a.setEnabled(true);
        assertTrue(a.allows(Reflex.FALL_CLUTCH));
        assertTrue(a.allows(Reflex.TOTEM), "a totem is safety, not assistance");
        assertFalse(a.allows(Reflex.PARKOUR));
        assertFalse(a.allows(Reflex.PARKOUR_BLOCKS));
    }

    @Test
    void aDisallowedReflexStaysOff() {
        ProfileConfig p = new ProfileConfig("t", new JsonObject());
        p.set(Autopilot.ID, Autopilot.K_CLUTCH, false);
        Autopilot a = loaded(p);
        a.setEnabled(true);
        assertFalse(a.allows(Reflex.FALL_CLUTCH));
        assertTrue(a.allows(Reflex.PARKOUR));
    }
}
