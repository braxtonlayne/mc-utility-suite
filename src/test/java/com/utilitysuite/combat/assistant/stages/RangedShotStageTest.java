package com.utilitysuite.combat.assistant.stages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.core.api.SimKey;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class RangedShotStageTest {

    static final class Facts implements RangedFacts {
        int weapon = BOW;
        int use;
        boolean charged;

        @Override
        public void refresh() {
        }

        @Override
        public int weapon() {
            return weapon;
        }

        @Override
        public int useTicks() {
            return use;
        }

        @Override
        public boolean crossbowCharged() {
            return charged;
        }

        @Override
        public int crossbowChargeTicks() {
            return 25;
        }
    }

    final CombatSettings cs = new CombatSettings();
    Boolean useDown;
    int useTaps;
    float aimYaw = Float.NaN, aimPitch = Float.NaN;
    int moves;

    final FightContext ctx = (FightContext) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {FightContext.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "settings":
                        return cs;
                    case "eating":
                    case "targetIsPlayer":
                        return false;
                    case "hold":
                        if (args[0] == SimKey.USE) {
                            useDown = (Boolean) args[1];
                        }
                        return null;
                    case "tap":
                        if (args[0] == SimKey.USE) {
                            useTaps++;
                        }
                        return null;
                    case "aimAt":
                        aimYaw = (Float) args[0];
                        aimPitch = (Float) args[1];
                        return null;
                    case "move":
                        moves++;
                        return null;
                    default:
                        Class<?> r = m.getReturnType();
                        if (r == boolean.class) {
                            return false;
                        }
                        if (r == int.class) {
                            return 0;
                        }
                        if (r == long.class) {
                            return 0L;
                        }
                        if (r == float.class) {
                            return 0f;
                        }
                        if (r == double.class) {
                            return 0.0;
                        }
                        return null;
                }
            });

    /** Target 30 blocks south (+z), level, standing still; we face it. */
    static CombatSnapshot snapshot() {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.eyeY = 65.62;
        s.minX = -0.3;
        s.maxX = 0.3;
        s.minY = 64;
        s.maxY = 65.8;
        s.minZ = 29.7;
        s.maxZ = 30.3;
        s.lineOfSight = true;
        s.reach = 3.0;
        return s;
    }

    @Test
    void offByDefaultDoesNothing() {
        assertFalse(cs.rangedAim);
        RangedShotStage st = new RangedShotStage(new Facts());
        assertFalse(st.step(snapshot(), ctx));
        assertEquals(null, useDown);
        assertTrue(Float.isNaN(aimYaw));
    }

    @Test
    void bowDrawsAimsAndReleasesAtFullChargeOnceAimed() {
        cs.rangedAim = true;
        Facts f = new Facts();
        RangedShotStage st = new RangedShotStage(f);
        CombatSnapshot s = snapshot();
        assertTrue(st.step(s, ctx));
        assertEquals(Boolean.TRUE, useDown);
        assertTrue(st.lastSolved());
        assertEquals(0f, aimYaw, 1e-3);
        assertTrue(aimPitch < 0f, "aims above the line for drop: " + aimPitch); // up is negative pitch
        // camera not yet on the solution: full draw alone does not release
        f.use = 25;
        s.pitch = 0f;
        assertTrue(st.step(s, ctx));
        assertEquals(Boolean.TRUE, useDown);
        // the smoother got there: release
        s.pitch = st.lastPitch();
        assertTrue(st.step(s, ctx));
        assertEquals(Boolean.FALSE, useDown);
        assertEquals(1, st.shots());
    }

    @Test
    void meleeRangeOrNoSightHandsBack() {
        cs.rangedAim = true;
        RangedShotStage st = new RangedShotStage(new Facts());
        CombatSnapshot s = snapshot();
        s.lineOfSight = false;
        assertFalse(st.step(s, ctx));
        s = snapshot();
        s.minZ = 2.7;
        s.maxZ = 3.3;
        assertFalse(st.step(s, ctx));
        assertEquals(null, useDown);
    }

    @Test
    void movingTargetIsLed() {
        cs.rangedAim = true;
        RangedShotStage st = new RangedShotStage(new Facts());
        CombatSnapshot s = snapshot();
        s.tvx = 0.2; // walking +x (west->east): lead turns the yaw toward +x, i.e. negative yaw
        st.step(s, ctx);
        assertTrue(aimYaw < -2f, "yaw " + aimYaw);
    }

    @Test
    void chargedCrossbowTapsWhenAimed() {
        cs.rangedAim = true;
        Facts f = new Facts();
        f.weapon = RangedFacts.CROSSBOW;
        f.charged = true;
        RangedShotStage st = new RangedShotStage(f);
        CombatSnapshot s = snapshot();
        st.step(s, ctx);
        s.pitch = st.lastPitch();
        st.step(s, ctx);
        assertEquals(1, useTaps);
    }
}
