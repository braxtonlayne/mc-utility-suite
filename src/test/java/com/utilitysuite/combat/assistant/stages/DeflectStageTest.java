package com.utilitysuite.combat.assistant.stages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.combat.assistant.CombatSettings;
import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.FightContext;
import com.utilitysuite.combat.tactics.memory.OpponentMemory;
import com.utilitysuite.combat.tactics.memory.RecalledHabits;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;

class DeflectStageTest {

    static final class Facts implements DeflectFacts {
        boolean found = true;
        double x, y = 65.6, z = 20, vx, vy, vz = -1;

        @Override
        public void refresh() {
        }

        @Override
        public boolean found() {
            return found;
        }

        @Override
        public double x() {
            return x;
        }

        @Override
        public double y() {
            return y;
        }

        @Override
        public double z() {
            return z;
        }

        @Override
        public double vx() {
            return vx;
        }

        @Override
        public double vy() {
            return vy;
        }

        @Override
        public double vz() {
            return vz;
        }
    }

    final CombatSettings cs = new CombatSettings();
    RecalledHabits recalled = RecalledHabits.NONE;
    int attacks;
    float aimYaw = Float.NaN;

    final FightContext ctx = (FightContext) Proxy.newProxyInstance(getClass().getClassLoader(),
            new Class<?>[] {FightContext.class}, (proxy, m, args) -> {
                switch (m.getName()) {
                    case "settings":
                        return cs;
                    case "tapAttack":
                        attacks++;
                        return null;
                    case "aimAt":
                        aimYaw = (Float) args[0];
                        return null;
                    case "recalledHabits":
                        return recalled;
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

    static CombatSnapshot snapshot(float yaw) {
        CombatSnapshot s = new CombatSnapshot();
        s.px = 0;
        s.py = 64;
        s.pz = 0;
        s.eyeY = 65.62;
        s.yaw = yaw;
        s.reach = 3.0;
        return s;
    }

    @Test
    void offByDefault() {
        assertFalse(cs.deflectProjectiles);
        assertFalse(new DeflectStage(new Facts()).step(snapshot(0f), ctx));
        assertTrue(Float.isNaN(aimYaw));
    }

    @Test
    void turnsToIncomingFireballAndSwingsInReach() {
        cs.deflectProjectiles = true;
        Facts f = new Facts();
        DeflectStage st = new DeflectStage(f);
        assertTrue(st.step(snapshot(0f), ctx)); // 20 blocks out: look, no swing yet
        assertEquals(0f, aimYaw, 1e-3);
        assertEquals(0, attacks);
        f.z = 2.5;
        assertTrue(st.step(snapshot(0f), ctx));
        assertEquals(1, attacks);
    }

    @Test
    void rememberedWindChargeThrowerWidensTheReadinessHorizon() {
        cs.deflectProjectiles = true;
        Facts f = new Facts();
        f.z = 50; // 50 ticks out: beyond the normal 40-tick horizon
        DeflectStage st = new DeflectStage(f);
        assertFalse(st.step(snapshot(0f), ctx));
        RecalledHabits r = new RecalledHabits();
        OpponentMemory.Profile p = new OpponentMemory.Profile();
        p.fights = 3;
        p.windShotsPerMin = 2.0;
        r.fill(p);
        recalled = r;
        assertTrue(st.step(snapshot(0f), ctx)); // a known thrower: start the look now
        assertEquals(0f, aimYaw, 1e-3);
        assertEquals(0, attacks);
    }

    @Test
    void skipsWhenTheTurnCannotBeMadeInTime() {
        cs.deflectProjectiles = true;
        Facts f = new Facts();
        f.z = 4; // from behind (we face north), 4 ticks out
        DeflectStage st = new DeflectStage(f);
        assertFalse(st.step(snapshot(180f), ctx));
        assertEquals(1, st.skipped());
        assertEquals(0, attacks);
    }
}
