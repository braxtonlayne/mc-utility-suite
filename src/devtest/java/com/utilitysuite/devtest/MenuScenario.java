package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.ui.SuiteMenuScreen;
import com.utilitysuite.ui.UiBootstrap;
import com.utilitysuite.ui.kit.ToggleSwitch;
import com.utilitysuite.ui.kit.UiNode;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/** Scenario 2: the Right-Shift menu (five tabs, generic setting widgets) and the HUD status line. */
final class MenuScenario {

    private MenuScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("2-menu-hud", "Menu & HUD");
        String[] keyDiag = {""};
        s.run("press Right Shift", () -> {
            var m = com.utilitysuite.core.input.KeybindManager.mapping(
                    com.utilitysuite.core.input.KeybindManager.KeyId.OPEN_MENU);
            H.physicalKey(GLFW.GLFW_KEY_RIGHT_SHIFT, true);
            keyDiag[0] = "mapping=" + (m == null ? "null" : m.getKey().getName() + " down=" + m.isDown()
                    + " modifier=" + m.getKeyModifier() + " ctxActive=" + m.getKeyConflictContext().isActive());
        });
        s.run("release Right Shift", () -> H.physicalKey(GLFW.GLFW_KEY_RIGHT_SHIFT, false));
        s.waitUpTo("menu opens", 10, () -> H.mc().screen instanceof SuiteMenuScreen);
        s.run("menu open check", () -> {
            boolean open = H.mc().screen instanceof SuiteMenuScreen;
            s.check("Right Shift opens the menu", open, "screen=" + name(H.mc().screen) + "; " + keyDiag[0]);
            if (!open) {
                UiBootstrap.openMenu();
            }
        });
        for (SuiteMenuScreen.Tab tab : SuiteMenuScreen.Tab.values()) {
            String label = tab.name().charAt(0) + tab.name().substring(1).toLowerCase();
            s.run("switch to " + label, () -> {
                SuiteMenuScreen m = menu();
                m.selectTab(tab);
            });
            s.waitTicks(8);
            s.run("check " + label, () -> {
                SuiteMenuScreen m = menu();
                List<UiNode> ws = m.contentNodes();
                int toggles = toggles(m).size();
                s.check("tab " + label + " builds and renders", m.tab() == tab && ws.size() > 6,
                        "tab=" + m.tab() + ", " + ws.size() + " nodes (" + toggles + " toggles)");
                java.nio.file.Path png = Harness.dir().resolve("menu-" + label.toLowerCase() + ".png");
                java.nio.file.Files.createDirectories(png.getParent());
                try (com.mojang.blaze3d.platform.NativeImage img = net.minecraft.client.Screenshot.takeScreenshot(
                        H.mc().getMainRenderTarget())) {
                    img.writeToFile(png);
                }
                s.note("screenshot " + png.toAbsolutePath());
            });
            if (tab == SuiteMenuScreen.Tab.WORLD || tab == SuiteMenuScreen.Tab.TRAVEL
                    || tab == SuiteMenuScreen.Tab.COMBAT) {
                toggleRoundTrip(s, label);
            }
        }
        autopilotBar(s);
        s.run("close menu", () -> {
            menu().onClose();
        });
        // the autopilot bar step above posts ONLINE/OFFLINE toasts; those are transient notices (at most
        // NoticeBoard.LIFE_LONG_MS plus the fade), so idle is measured once they have run out
        s.waitTicks(110);
        s.run("menu closed + HUD idle", () -> {
            s.check("closing returns to the game", H.mc().screen == null, "screen=" + name(H.mc().screen));
            String text = UiBootstrap.hud().text();
            CountingGraphics g = CountingGraphics.render(UiBootstrap.hud());
            s.check("HUD draws nothing when idle", text.isEmpty() && g.calls() == 0,
                    "text='" + text + "', draw calls: " + g);
        });
        s.run("clean screen on", () -> Services.modState().setCleanScreen(true));
        s.waitTicks(2);
        s.run("clean screen check", () -> {
            CountingGraphics g = CountingGraphics.render(UiBootstrap.hud());
            s.check("HUD draws nothing in Clean-Screen", g.calls() == 0, "draw calls: " + g);
        });
        s.always("clean screen off", () -> Services.modState().setCleanScreen(false));
        s.always("close any screen", () -> {
            if (H.mc().screen != null) {
                H.mc().setScreen(null);
            }
        });
        return s;
    }

    /** The autopilot bar: off by default, a click turns it on (and into the profile), its Settings end opens its page. */
    private static void autopilotBar(Scenario s) {
        s.run("autopilot off by default", () -> {
            var ap = com.utilitysuite.core.CoreBootstrap.autopilot();
            s.check("autopilot is off by default", ap != null && !ap.isOn(), "on=" + (ap != null && ap.isOn()));
            int[] r = menu().autopilotBarRect();
            s.check("autopilot bar is pinned under the title bar, full width", r[2] > 200 && r[3] > 0,
                    "rect=" + java.util.Arrays.toString(r));
            menu().mouseClicked(r[0] + r[2] / 3.0, r[1] + r[3] / 2.0, 0);
        });
        s.waitTicks(12);
        s.run("autopilot on", () -> {
            var ap = com.utilitysuite.core.CoreBootstrap.autopilot();
            boolean saved = Services.modState().profile().getBool("autopilot", "enabled", false);
            s.check("clicking the bar turns the autopilot on and saves it", ap.isOn() && saved,
                    "on=" + ap.isOn() + " profile=" + saved + " summary='" + ap.summary() + "'");
            shot(s, "menu-autopilot-on.png");
            int[] r = menu().autopilotBarRect();
            menu().mouseClicked(r[0] + r[2] - 20.0, r[1] + r[3] / 2.0, 0);
        });
        s.waitTicks(8);
        s.run("autopilot page", () -> {
            SuiteMenuScreen m = menu();
            s.check("the bar's Settings end opens the autopilot page", m.autopilotPageOpen()
                    && m.contentNodes().size() > 4, "open=" + m.autopilotPageOpen() + ", "
                    + m.contentNodes().size() + " nodes");
            shot(s, "menu-autopilot-page.png");
            int[] r = m.autopilotBarRect();
            m.mouseClicked(r[0] + r[2] - 20.0, r[1] + r[3] / 2.0, 0);
            m.mouseClicked(r[0] + r[2] / 3.0, r[1] + r[3] / 2.0, 0);
        });
        s.waitTicks(4);
        s.run("autopilot back off", () -> {
            var ap = com.utilitysuite.core.CoreBootstrap.autopilot();
            s.check("a second click turns it off and closes the page", !ap.isOn() && !menu().autopilotPageOpen(),
                    "on=" + ap.isOn() + " page=" + menu().autopilotPageOpen());
        });
        s.always("autopilot off", () -> {
            var ap = com.utilitysuite.core.CoreBootstrap.autopilot();
            if (ap != null && ap.isOn()) {
                com.utilitysuite.core.CoreBootstrap.setAutopilot(false);
            }
        });
    }

    private static void shot(Scenario s, String name) throws java.io.IOException {
        java.nio.file.Path png = Harness.dir().resolve(name);
        java.nio.file.Files.createDirectories(png.getParent());
        try (com.mojang.blaze3d.platform.NativeImage img = net.minecraft.client.Screenshot.takeScreenshot(
                H.mc().getMainRenderTarget())) {
            img.writeToFile(png);
        }
        s.note("screenshot " + png.toAbsolutePath());
    }

    /** Presses a toggle through the menu's generic widget, checks the profile changed, presses it back. */
    private static void toggleRoundTrip(Scenario s, String tabLabel) {
        String[] before = new String[1];
        String[] message = new String[1];
        int[] index = new int[1];
        s.run(tabLabel + ": press a toggle", () -> {
            SuiteMenuScreen m = menu();
            List<ToggleSwitch> toggles = toggles(m);
            if (toggles.isEmpty()) {
                s.notTested(tabLabel + " toggle round trip", "no toggle widgets on the tab");
                index[0] = -1;
                return;
            }
            // Pick a toggle that is not a module on/off switch near the top (avoid enabling heavy modules).
            index[0] = Math.min(1, toggles.size() - 1);
            ToggleSwitch b = toggles.get(index[0]);
            message[0] = "#" + index[0] + (b.tooltipText() == null ? "" : " " + b.tooltipText());
            before[0] = Services.modState().profile().root().toString();
            b.set(!b.value(), true);
        });
        s.waitTicks(3);
        s.run(tabLabel + ": verify + press back", () -> {
            if (index[0] < 0) {
                return;
            }
            String after = Services.modState().profile().root().toString();
            s.check(tabLabel + " toggle '" + message[0] + "' lands in the profile", !after.equals(before[0]),
                    diff(before[0], after));
            List<ToggleSwitch> toggles = toggles(menu());
            if (index[0] < toggles.size()) {
                ToggleSwitch b = toggles.get(index[0]);
                b.set(!b.value(), true);
            }
        });
        s.waitTicks(3);
        s.run(tabLabel + ": restored", () -> {
            if (index[0] < 0) {
                return;
            }
            String now = Services.modState().profile().root().toString();
            s.check(tabLabel + " toggle pressed back restores the value", sameValues(before[0], now),
                    diff(before[0], now));
        });
    }

    /** True when every key that existed before has the same value (pressing may add default keys). */
    private static boolean sameValues(String before, String now) {
        com.google.gson.JsonObject a = com.google.gson.JsonParser.parseString(before).getAsJsonObject();
        com.google.gson.JsonObject b = com.google.gson.JsonParser.parseString(now).getAsJsonObject();
        for (String mod : b.keySet()) {
            com.google.gson.JsonObject bm = b.getAsJsonObject(mod);
            com.google.gson.JsonObject am = a.getAsJsonObject(mod);
            for (String k : bm.keySet()) {
                if (am != null && am.has(k) && !am.get(k).equals(bm.get(k))) {
                    return false;
                }
                if ((am == null || !am.has(k)) && bm.get(k).isJsonPrimitive()
                        && bm.get(k).getAsJsonPrimitive().isBoolean()) {
                    // A key that did not exist before: allowed only if it now holds the module default; we cannot
                    // know the default generically, so accept it (the first press wrote it).
                    continue;
                }
            }
        }
        return true;
    }

    static String diff(String before, String after) {
        com.google.gson.JsonObject a = com.google.gson.JsonParser.parseString(before).getAsJsonObject();
        com.google.gson.JsonObject b = com.google.gson.JsonParser.parseString(after).getAsJsonObject();
        List<String> out = new ArrayList<>();
        for (String mod : b.keySet()) {
            com.google.gson.JsonObject bm = b.getAsJsonObject(mod);
            com.google.gson.JsonObject am = a.has(mod) ? a.getAsJsonObject(mod) : new com.google.gson.JsonObject();
            for (String k : bm.keySet()) {
                if (!am.has(k) || !am.get(k).equals(bm.get(k))) {
                    out.add(mod + "." + k + ": " + (am.has(k) ? am.get(k) : "-") + " -> " + bm.get(k));
                }
            }
        }
        return out.isEmpty() ? "no change" : String.join("; ", out);
    }

    private static List<ToggleSwitch> toggles(SuiteMenuScreen m) {
        List<ToggleSwitch> out = new ArrayList<>();
        for (UiNode n : m.contentNodes()) {
            if (n instanceof ToggleSwitch t) {
                out.add(t);
            }
        }
        return out;
    }

    private static SuiteMenuScreen menu() {
        if (H.mc().screen instanceof SuiteMenuScreen m) {
            return m;
        }
        throw new IllegalStateException("menu not open (screen=" + name(H.mc().screen) + "); last close:"
                + Harness.menuClosedBy);
    }

    static String name(Object o) {
        return o == null ? "none" : o.getClass().getSimpleName();
    }
}
