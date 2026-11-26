package com.utilitysuite.devtest;

import com.mojang.blaze3d.platform.InputConstants;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.holo.NoticeBoard;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.MouseHandler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Scenario 18-toolsextra: in-game coverage for eight Tools/UI modules no other scenario touches: Local Rules
 * (edge-triggered firing, the disabled/job/Stop-All gates, the pearl-escape action), Latency Simulator (measured round
 * trip, clean removal), Auto-Reconnect (single player: its disconnect-screen countdown, cancel button and reason
 * filter on a synthetic disconnect screen), Quick Pearl (throw through the leases with the hotbar restored, inventory
 * swap and swap back, blocked-throw hold), Enchant Planner (seed read and table roll checked against a real enchanting
 * table, remote cracker candidates), Villager Helpers (bulk trade on a real trade screen, librarian roller success
 * path), Appearance (HUD opacity, scale and accent seen in the drawn HUD) and the Action Wheel (open, flick-select,
 * fire, cancel, camera held). Select alone with {@code -PplaytestOnly=ToolsExtra}.
 */
final class ToolsExtraScenario {

    private ToolsExtraScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("18-toolsextra",
                "Tools extra: rules, latency, reconnect, quick pearl, enchant, villagers, appearance, wheel");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect clear @s");
        s.cmd("effect give @s minecraft:resistance 600 4 true"); // pearl landings cost no health
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));

        ToolsExtraPlayerTests.add(s);
        ToolsExtraStationTests.add(s);
        ToolsExtraUiTests.add(s);

        s.always("tools extra: final cleanup", () -> {
            if (Services.arbiter().isAutomationStopped()) {
                Services.arbiter().resumeAutomation();
            }
            Services.modState().setCleanScreen(false);
            if (H.mc().screen != null) {
                H.mc().setScreen(null);
            }
            Harness.command("kill @e[type=!minecraft:player]");
            Harness.command("clear @s");
            Harness.command("effect clear @s");
            Harness.command(Arena.fill(-12, 1, -12, 12, 9, 12, "minecraft:air"));
        });
        return s;
    }

    // ---- shared helpers ---------------------------------------------------------------------------------------------

    /** Removes a setting from the active profile (back to its default) and notifies the module like the menu does. */
    static void unset(String moduleId, String key) {
        var profile = Services.modState().profile();
        profile.module(moduleId).remove(key);
        IModule m = H.module(moduleId);
        if (m instanceof IConfigurable c) {
            c.onSettingChanged(key);
        }
        m.onProfileLoaded(profile);
    }

    /** Binds a key mapping for the test only (options.txt is not saved); {@code glfwKey < 0} unbinds it. */
    static void bind(KeyMapping m, int glfwKey) {
        m.setKey(glfwKey < 0 ? InputConstants.UNKNOWN : InputConstants.Type.KEYSYM.getOrCreate(glfwKey));
        KeyMapping.resetMapping();
    }

    /** True when a live notice's title contains {@code text}. */
    static boolean noticeSeen(String text) {
        if (Services.visuals().notices() instanceof NoticeBoard nb) {
            for (int i = 0; i < nb.count(); i++) {
                String t = nb.at(i).title;
                if (t != null && t.contains(text)) {
                    return true;
                }
            }
        }
        return false;
    }

    static String notices() {
        StringBuilder sb = new StringBuilder();
        if (Services.visuals().notices() instanceof NoticeBoard nb) {
            for (int i = 0; i < nb.count(); i++) {
                sb.append('\'').append(nb.at(i).title).append("' ");
            }
        }
        return sb.length() == 0 ? "none" : sb.toString().trim();
    }

    static Object field(Object o, String name) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no field " + name + " on " + o.getClass().getSimpleName(), e);
        }
    }

    static void setField(Object o, String name, Object value) {
        try {
            Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("no field " + name + " on " + o.getClass().getSimpleName(), e);
        }
    }

    private static Field fDX;
    private static Field fDY;
    private static Method mTurn;

    /**
     * Moves the mouse through vanilla's own turn path ({@code MouseHandler.turnPlayer}, where the core's turn hook sits)
     * by this many turn units (screen convention, +x right, +y down). 1 unit = 0.15 degrees of camera turn.
     */
    static void mouseUnits(double unitsX, double unitsY) {
        try {
            if (mTurn == null) {
                fDX = MouseHandler.class.getDeclaredField("accumulatedDX");
                fDY = MouseHandler.class.getDeclaredField("accumulatedDY");
                mTurn = MouseHandler.class.getDeclaredMethod("turnPlayer", double.class);
                fDX.setAccessible(true);
                fDY.setAccessible(true);
                mTurn.setAccessible(true);
            }
            var mc = H.mc();
            double sens = mc.options.sensitivity().get() * 0.6 + 0.2;
            double unitsPerPx = sens * sens * sens * 8.0;
            MouseHandler m = mc.mouseHandler;
            fDX.setDouble(m, unitsX / unitsPerPx);
            fDY.setDouble(m, unitsY / unitsPerPx * (mc.options.invertYMouse().get() ? -1 : 1));
            mTurn.invoke(m, 0.05);
            fDX.setDouble(m, 0.0);
            fDY.setDouble(m, 0.0);
        } catch (ReflectiveOperationException ex) {
            throw new IllegalStateException("cannot reach the mouse path", ex);
        }
    }
}
