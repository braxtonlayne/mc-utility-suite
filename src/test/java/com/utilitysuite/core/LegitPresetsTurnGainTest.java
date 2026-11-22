package com.utilitysuite.core;

import com.google.gson.JsonObject;
import com.utilitysuite.core.LegitPresets.Knob;
import com.utilitysuite.core.LegitPresets.Level;
import com.utilitysuite.core.api.ProfileConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Turn gain is a Balanced/Relaxed knob; a profile left on Legit before the knob existed must not gain it. */
class LegitPresetsTurnGainTest {

    private static final String MA = "movement_assist";
    private static final String TG = "turn_gain";

    private static ProfileConfig fresh(String id) {
        return new ProfileConfig(id, new JsonObject());
    }

    /** A profile on {@code level} written before the turn_gain knob existed (the key is absent). */
    private static ProfileConfig[] olderProfileOn(Level level) {
        ProfileConfig world = fresh("w");
        ProfileConfig global = fresh("global");
        LegitPresets.apply(level, world, global);
        world.root().getAsJsonObject(MA).remove(TG);
        return new ProfileConfig[] {world, global};
    }

    @Test
    void theKnobIsOffForLegitOnlyAndApplyingWritesIt() {
        Knob k = null;
        for (Knob kn : LegitPresets.KNOBS) {
            if (kn.moduleId().equals(MA) && kn.key().equals(TG)) {
                k = kn;
            }
        }
        assertEquals(false, k.value(Level.LEGIT));
        assertEquals(true, k.value(Level.BALANCED));
        assertEquals(true, k.value(Level.RELAXED));
        ProfileConfig world = fresh("w");
        LegitPresets.apply(Level.LEGIT, world, fresh("global"));
        assertFalse(world.getBool(MA, TG, true));
    }

    @Test
    void anAbsentKeyOnALegitProfileReadsOff() {
        ProfileConfig[] p = olderProfileOn(Level.LEGIT);
        assertFalse(LegitPresets.boolKnob(p[0], p[1], MA, TG), "Legit stays keys only");
        assertEquals(Level.LEGIT, LegitPresets.currentIgnoring(p[0], p[1], MA, TG));
        assertNull(LegitPresets.current(p[0], p[1]), "shows Custom until the level is applied again");
        LegitPresets.apply(Level.LEGIT, p[0], p[1]);
        assertEquals(Level.LEGIT, LegitPresets.current(p[0], p[1]));
    }

    @Test
    void anAbsentKeyOnBalancedOrRelaxedReadsOn() {
        for (Level level : new Level[] {Level.BALANCED, Level.RELAXED}) {
            ProfileConfig[] p = olderProfileOn(level);
            assertTrue(LegitPresets.boolKnob(p[0], p[1], MA, TG), level.name());
        }
        assertTrue(LegitPresets.boolKnob(fresh("w"), fresh("global"), MA, TG), "an empty profile is Balanced");
    }

    @Test
    void aStoredValueAlwaysWins() {
        ProfileConfig[] p = olderProfileOn(Level.RELAXED);
        p[0].set(MA, TG, false);
        assertFalse(LegitPresets.boolKnob(p[0], p[1], MA, TG));
        ProfileConfig[] q = olderProfileOn(Level.LEGIT);
        q[0].set(MA, TG, true);
        assertTrue(LegitPresets.boolKnob(q[0], q[1], MA, TG), "the stored value; the module still checks the level");
    }

    @Test
    void aCustomProfileUsesTheBalancedValue() {
        ProfileConfig[] p = olderProfileOn(Level.LEGIT);
        p[0].set(LegitPresets.COMBAT, "aim_speed", 1.7);
        assertNull(LegitPresets.currentIgnoring(p[0], p[1], MA, TG));
        assertTrue(LegitPresets.boolKnob(p[0], p[1], MA, TG));
    }
}
