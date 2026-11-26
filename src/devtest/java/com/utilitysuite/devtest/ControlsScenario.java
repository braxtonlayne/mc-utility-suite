package com.utilitysuite.devtest;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.audit.SettingsAudit;
import com.utilitysuite.ui.SuiteMenuScreen;
import com.utilitysuite.ui.kit.ModernSlider;
import com.utilitysuite.ui.kit.StyledTextField;
import com.utilitysuite.ui.kit.UiNode;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Every control, live in the game: (1) the settings audit over the real module registry (all modules, including the
 * ones the unit test cannot build), with each module switched on and ticked after every change; (2) every module
 * switched on for half a second and checked to keep ticking without errors; (3) every interactive widget on every
 * menu tab clicked once and checked to have a visible effect (profile, module switch, screen, page or widget
 * labels). State is restored after each step. Results: run/playtest/controls-report.txt.
 */
final class ControlsScenario {

    private static final String GOAL_PLANNER = "written into the shared planner the engine reaches through a static "
            + "supplier the fingerprint cannot follow; AllSettingsCoverageTest checks it with a captured planner";

    /** Live-only exemptions (the unit test, with test doubles, sees these settings work). */
    static final Map<String, String> EXEMPT = Map.of(
            "goal_engine.efficiency_tools", GOAL_PLANNER, "goal_engine.shovel_after", GOAL_PLANNER,
            "goal_engine.axe_after", GOAL_PLANNER);

    /** Modules that refuse to switch on under the default LEGIT tuning (they post a notice saying why). */
    static final Set<String> GATED = Set.of("fly", "velocity_scale");

    /** Widgets whose click legitimately has no effect the scenario can see, by "Tab:label" (with the reason). */
    static final Map<String, String> CLICK_EXEMPT = Map.of();

    /** Labels never clicked: they would leave the world or quit the game, which ends the playtest. */
    static final Set<String> NEVER_CLICK = Set.of("Disconnect", "Quit", "Leave world", "Save and quit");

    private static final int MODULE_TICKS = 10;
    private static final int MAX_CLICKS_PER_TAB = 400;

    private ControlsScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("12-controls", "Every toggle, setting and menu control");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("effect give @s minecraft:resistance 1000 4 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        Saved saved = new Saved();
        s.run("save profile", saved::capture);
        List<String> report = new ArrayList<>();
        settingsAudit(s, saved, report);
        moduleTicks(s, saved, report);
        menuClicks(s, saved, report);
        s.always("restore + write report", () -> {
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
            saved.finish();
            Path out = Harness.dir().resolve("controls-report.txt");
            Files.createDirectories(out.getParent());
            Files.write(out, report);
            s.note("report " + out.toAbsolutePath());
            Harness.command("effect clear @s");
        });
        return s;
    }

    // ---- 1: settings audit on the live registry -----------------------------------------------------------------

    private static void settingsAudit(Scenario s, Saved saved, List<String> report) {
        s.run("settings audit (live)", () -> {
            List<SettingsAudit.Target> targets = new ArrayList<>();
            for (IModule m : Services.modules().all()) {
                if (m instanceof IConfigurable) {
                    targets.add(SettingsAudit.Target.of(m, Services.modState()::profile));
                }
            }
            SettingsAudit.Report all = new SettingsAudit(EXEMPT, Set.of("fly#toggle", "velocity_scale#toggle")).run(targets);
            cleanStop();
            saved.restore();
            report.add(all.summary());
            s.check("every toggle and setting changes its module, switches back and is read on load (live)",
                    all.clean(), all.clean() ? all.modules() + " modules, " + all.settings() + " settings, "
                            + all.variantsTried() + " values" : all.summary());
        });
        // Every value of every setting through a live tick of the module (switched on): it must not throw.
        s.run("every setting value ticks (live)", () -> {
            List<String> thrown = new ArrayList<>();
            int ticked = 0;
            for (IModule m : Services.modules().all()) {
                if (!(m instanceof IConfigurable c)) {
                    continue;
                }
                boolean was = m.isEnabled();
                ProfileConfig p = Services.modState().profile();
                JsonObject obj = p.module(m.id());
                try {
                    if (!was && m.category() != ModuleCategory.CORE) {
                        m.setEnabled(true); // refused by gated modules (fly, velocity) on LEGIT tuning: fine
                    }
                    for (var spec : c.settings()) {
                        List<String> values = new ArrayList<>(SettingsAudit.variants(spec));
                        values.add(SettingsAudit.defaultString(spec));
                        for (String v : values) {
                            try {
                                SettingsAudit.write(obj, spec, v);
                                c.onSettingChanged(spec.key());
                                if (m.isEnabled()) {
                                    m.onTick();
                                }
                                ticked++;
                            } catch (RuntimeException e) {
                                thrown.add(m.id() + "." + spec.key() + "=" + v + ": " + e);
                            }
                        }
                    }
                } finally {
                    try {
                        m.onStopAll();
                        if (m.isEnabled() != was) {
                            m.setEnabled(was);
                        }
                    } catch (RuntimeException e) {
                        thrown.add(m.id() + " stop: " + e);
                    }
                    cleanStop();
                }
            }
            saved.restore();
            report.add("Setting values ticked live: " + ticked + ", threw: " + thrown);
            s.check("every setting value runs through a live tick without an exception", thrown.isEmpty(),
                    thrown.isEmpty() ? ticked + " value ticks" : String.join("; ", thrown));
        });
    }

    // ---- 2: every module on for a moment ------------------------------------------------------------------------

    private static void moduleTicks(Scenario s, Saved saved, List<String> report) {
        List<String> ids = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        int[] index = {0};
        int[] onAt = {-1};
        s.run("list modules", () -> {
            for (IModule m : Services.modules().all()) {
                if (m.category() != ModuleCategory.CORE) {
                    ids.add(m.id());
                }
            }
        });
        s.step("each module on for " + MODULE_TICKS + " ticks", t -> {
            if (index[0] >= ids.size()) {
                return true;
            }
            String id = ids.get(index[0]);
            IModule m = H.module(id);
            if (onAt[0] < 0) {
                H.enable(id, true);
                onAt[0] = t;
                if (!m.isEnabled() && !GATED.contains(id)) {
                    failed.add(id + ": did not switch on");
                }
                return false;
            }
            if (t - onAt[0] < MODULE_TICKS) {
                return false;
            }
            if (!m.isEnabled() && !GATED.contains(id)) {
                failed.add(id + ": switched itself off within " + MODULE_TICKS + " ticks (phase " + m.phase() + ")");
            }
            H.enable(id, false);
            cleanStop();
            if (H.mc().gui.screen() != null && !(H.mc().gui.screen() instanceof net.minecraft.client.gui.screens.DeathScreen)) {
                H.mc().gui.setScreen(null); // e.g. a module that opened a container
            }
            onAt[0] = -1;
            index[0]++;
            return false;
        });
        s.run("module tick result", () -> {
            saved.restore();
            report.add("Modules on for " + MODULE_TICKS + " ticks: " + ids.size() + ", problems: " + failed);
            // Exceptions while a module was on land in the scenario's captured errors (the harness fails it).
            s.check("every module switches on and keeps running for " + MODULE_TICKS + " ticks", failed.isEmpty(),
                    failed.isEmpty() ? ids.size() + " modules" : String.join("; ", failed));
        });
    }

    // ---- 3: every widget in every tab ---------------------------------------------------------------------------

    private static void menuClicks(Scenario s, Saved saved, List<String> report) {
        List<String> noEffect = new ArrayList<>();
        List<String> clicked = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        List<String> unreachable = new ArrayList<>();
        for (SuiteMenuScreen.Tab tab : SuiteMenuScreen.Tab.values()) {
            String tabName = tab.name().charAt(0) + tab.name().substring(1).toLowerCase();
            int[] index = {0};
            int[] phase = {0};
            String[] before = {null};
            String[] label = {null};
            s.step("click every control: " + tabName, t -> {
                if (phase[0] == 0) {
                    SuiteMenuScreen m = ensureMenu(tab);
                    if (m == null) {
                        return false; // opened this tick; continue next tick
                    }
                    List<UiNode> nodes = m.contentNodes();
                    // find the next interactive node
                    while (index[0] < nodes.size() && index[0] < MAX_CLICKS_PER_TAB) {
                        UiNode n = nodes.get(index[0]);
                        String d = describe(n);
                        if (!n.interactive()) {
                            index[0]++;
                            continue;
                        }
                        if (n instanceof StyledTextField || NEVER_CLICK.stream().anyMatch(d::contains)) {
                            skipped.add(tabName + ":" + d + (n instanceof StyledTextField ? " (text field)" : ""));
                            index[0]++;
                            continue;
                        }
                        break;
                    }
                    if (index[0] >= nodes.size() || index[0] >= MAX_CLICKS_PER_TAB) {
                        return true;
                    }
                    UiNode n = nodes.get(index[0]);
                    label[0] = tabName + ":" + describe(n);
                    clearMenuStatus(m); // a repeat of the same message must still show up as an effect
                    before[0] = signature(m, n);
                    if (!click(m, n, 0.5)) {
                        unreachable.add(label[0]);
                        index[0]++;
                        return false;
                    }
                    phase[0] = 1;
                    return false;
                }
                if (phase[0] == 1) { // one tick for rebuilds, screens and jobs to react
                    phase[0] = 2;
                    return false;
                }
                // phase 2: compare (the click may have opened another screen: compare before reopening the menu),
                // retry a slider or selector at its ends, then restore
                List<UiNode> nodes = H.mc().gui.screen() instanceof SuiteMenuScreen cur ? cur.contentNodes() : List.of();
                String after = signature(H.mc().gui.screen() instanceof SuiteMenuScreen sm ? sm : null, null);
                boolean effect = !after.equals(before[0]);
                // A slider or segmented selector clicked in the middle may land on its current value: try both ends.
                if (!effect && phase[0] < 4 && H.mc().gui.screen() instanceof SuiteMenuScreen sm2
                        && index[0] < nodes.size() && (nodes.get(index[0]) instanceof ModernSlider
                        || nodes.get(index[0]) instanceof com.utilitysuite.ui.kit.CycleSelector<?>)) {
                    click(sm2, nodes.get(index[0]), phase[0] == 2 ? 0.03 : 0.97);
                    phase[0] = phase[0] == 2 ? 3 : 4;
                    return false;
                }
                clicked.add(label[0]);
                if (!effect && !CLICK_EXEMPT.containsKey(label[0])) {
                    noEffect.add(label[0] + " {" + lastClick + ", " + noticeDiag() + "}");
                }
                restoreAfterClick(saved, tab);
                index[0]++;
                phase[0] = 0;
                return false;
            });
        }
        s.run("menu click result", () -> {
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
            saved.restore();
            report.add("Menu widgets clicked: " + clicked.size());
            report.add("No visible effect: " + noEffect);
            report.add("Not clicked: " + skipped);
            report.add("Could not be scrolled into view: " + unreachable);
            s.note(clicked.size() + " widgets clicked, " + skipped.size() + " skipped (text fields / leave-world)");
            s.check("every menu widget can be reached by scrolling", unreachable.isEmpty(),
                    unreachable.isEmpty() ? "all" : String.join("; ", unreachable));
            s.check("every menu widget has a visible effect when clicked", noEffect.isEmpty() && clicked.size() > 50,
                    noEffect.isEmpty() ? clicked.size() + " widgets" : noEffect.size() + " of " + clicked.size()
                            + " did nothing visible: " + String.join("; ", noEffect));
        });
    }

    /** Stop-All, then resume: later clicks must not be refused because automation was left stopped. */
    private static void cleanStop() {
        Services.arbiter().stopAll("controls audit");
        Services.arbiter().resumeAutomation();
    }

    private static SuiteMenuScreen ensureMenu(SuiteMenuScreen.Tab tab) {
        if (!(H.mc().gui.screen() instanceof SuiteMenuScreen)) {
            H.mc().gui.setScreen(new SuiteMenuScreen(null));
            return null;
        }
        SuiteMenuScreen m = (SuiteMenuScreen) H.mc().gui.screen();
        if (m.tab() != tab || m.autopilotPageOpen()) {
            m.selectTab(tab);
            return null;
        }
        return m;
    }

    private static String lastClick = "";

    private static String noticeDiag() {
        StringBuilder sb = new StringBuilder("cleanScreen=" + Services.modState().isCleanScreen());
        if (Services.visuals().notices() instanceof com.utilitysuite.ui.holo.NoticeBoard nb) {
            sb.append(", notices=").append(nb.count());
            for (int i = 0; i < nb.count(); i++) {
                sb.append(" '").append(nb.at(i).shown).append("' seq ").append(nb.at(i).seq);
            }
        } else {
            sb.append(", board=").append(Services.visuals().notices().getClass().getName());
        }
        return sb.toString();
    }

    /** A real click: scrolled into view, then press + release through the screen's own mouse handlers. */
    private static boolean click(SuiteMenuScreen m, UiNode n, double fraction) {
        double[] at = m.revealForHarness(n, fraction);
        if (at == null) {
            return false;
        }
        boolean consumed = m.mouseClicked(H.leftClick(at[0], at[1]), false);
        Object hit = null;
        try {
            Field f = SuiteMenuScreen.class.getDeclaredField("dragNode");
            f.setAccessible(true);
            hit = f.get(m);
        } catch (ReflectiveOperationException ignored) {
            // diagnostics only
        }
        lastClick = "consumed=" + consumed + ", target=" + (hit == null ? "-" : hit == n ? "same"
                : describe((UiNode) hit)) + String.format(", at %.0f,%.0f", at[0], at[1]);
        if (H.mc().gui.screen() == m) {
            m.mouseReleased(H.leftClick(at[0], at[1]));
        }
        return true;
    }

    private static void restoreAfterClick(Saved saved, SuiteMenuScreen.Tab tab) {
        cleanStop();
        if (!(H.mc().gui.screen() instanceof SuiteMenuScreen)) {
            H.mc().gui.setScreen(null);
        }
        saved.restoreIfChanged();
    }

    /** What a click can visibly change: the screen, tab, page, profiles, module switches and widget labels. */
    private static String signature(SuiteMenuScreen m, UiNode skipValueOf) {
        StringBuilder sb = new StringBuilder();
        sb.append(H.mc().gui.screen() == null ? "none" : H.mc().gui.screen().getClass().getName()).append('|');
        if (m != null) {
            sb.append(m.tab()).append('|').append(m.autopilotPageOpen()).append('|');
            for (UiNode n : m.contentNodes()) {
                sb.append(describe(n)).append(';');
            }
        }
        sb.append('|').append(Services.modState().profile().root()).append('|').append(Services.modState().global().root());
        for (IModule mod : Services.modules().all()) {
            sb.append(mod.isEnabled() ? '1' : '0');
        }
        sb.append('|').append(Services.modState().isCleanScreen()).append(Services.modState().isAutomationStopped());
        var ap = CoreBootstrap.autopilot();
        sb.append(ap != null && ap.isOn());
        // started work (a route, a job, bridging), the camera, and any message the click posted
        sb.append('|').append(H.arbiterState());
        var cam = H.mc().getCameraEntity();
        sb.append('|').append(cam == null ? "-" : cam.getClass().getSimpleName() + cam.getId());
        sb.append('|').append(menuStatus(m)).append('|').append(menuFormState());
        if (Services.visuals().notices() instanceof com.utilitysuite.ui.holo.NoticeBoard nb) {
            // the newest notice (seq + merge count): a new post or a repeat changes it, expiry of older ones does not
            long seq = -1;
            long posted = -1;
            for (int i = 0; i < nb.count(); i++) {
                var n = nb.at(i);
                seq = Math.max(seq, n.seq);
                posted = Math.max(posted, n.postedAt); // a merged repeat of the same notice re-stamps postedAt
            }
            sb.append('|').append(seq).append('@').append(posted);
        }
        return sb.toString();
    }

    /** The menu's form state (selected rows, draft options): what a form control visibly changes. */
    private static String menuFormState() {
        try {
            Field f = SuiteMenuScreen.class.getDeclaredField("STATE");
            f.setAccessible(true);
            return String.valueOf(new java.util.TreeMap<>((Map<?, ?>) f.get(null)));
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    private static void clearMenuStatus(SuiteMenuScreen m) {
        try {
            Field f = SuiteMenuScreen.class.getDeclaredField("status");
            f.setAccessible(true);
            f.set(m, "");
        } catch (ReflectiveOperationException ignored) {
            // the signature still sees new messages
        }
    }

    private static String menuStatus(SuiteMenuScreen m) {
        if (m == null) {
            return "";
        }
        try {
            Field f = SuiteMenuScreen.class.getDeclaredField("status");
            f.setAccessible(true);
            return String.valueOf(f.get(m));
        } catch (ReflectiveOperationException e) {
            return "";
        }
    }

    /** Class and text of a widget (its label, title, readout; not animation state). */
    static String describe(UiNode n) {
        StringBuilder sb = new StringBuilder(n.getClass().getSimpleName());
        for (Class<?> c = n.getClass(); c != null && c != UiNode.class && c != Object.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.getName().startsWith("shown")) {
                    continue;
                }
                try {
                    f.setAccessible(true);
                    Object v = f.get(n);
                    if (v instanceof String str && !str.isEmpty()) {
                        sb.append(' ').append(str);
                    } else if (v instanceof String[] arr && arr.length > 0) {
                        sb.append(' ').append(String.join("/", arr));
                    }
                } catch (ReflectiveOperationException | RuntimeException ignored) {
                    // skip
                }
            }
        }
        if (n instanceof com.utilitysuite.ui.kit.CycleSelector<?>) {
            try {
                Field f = com.utilitysuite.ui.kit.CycleSelector.class.getDeclaredField("index");
                f.setAccessible(true);
                sb.append(" #").append(f.get(n)); // the selected option is what the user sees change
            } catch (ReflectiveOperationException ignored) {
                // label only
            }
        }
        String tip = n.tooltipText();
        if (tip != null && !tip.isEmpty()) {
            sb.append(" [").append(tip.length() > 40 ? tip.substring(0, 40) : tip).append(']');
        }
        return sb.toString();
    }

    /** The profile and global JSON before the scenario, put back (and re-applied) after each step. */
    static final class Saved {
        JsonObject profile;
        JsonObject global;
        boolean cleanScreen;
        boolean originalCleanScreen;

        /** Saves the profiles; Clean-Screen is switched off for the run (it hides the notices the clicks post). */
        void capture() {
            originalCleanScreen = Services.modState().isCleanScreen();
            Services.modState().setCleanScreen(false);
            cleanScreen = false;
            profile = Services.modState().profile().root().deepCopy();
            global = Services.modState().global().root().deepCopy();
        }

        void finish() {
            restore();
            Services.modState().setCleanScreen(originalCleanScreen);
        }

        void restoreIfChanged() {
            if (!Services.modState().profile().root().equals(profile) || !Services.modState().global().root().equals(global)
                    || Services.modState().isCleanScreen() != cleanScreen) {
                restore();
            }
        }

        void restore() {
            if (profile == null) {
                return;
            }
            copyInto(profile, Services.modState().profile().root());
            copyInto(global, Services.modState().global().root());
            if (Services.modState().isCleanScreen() != cleanScreen) {
                Services.modState().setCleanScreen(cleanScreen); // held in memory, not re-read from the JSON
            }
            CoreBootstrap.modules().applyProfile(Services.modState().profile());
            for (IModule m : Services.modules().all()) {
                if (m instanceof IConfigurable c) {
                    for (var spec : c.settings()) {
                        try {
                            c.onSettingChanged(spec.key());
                        } catch (RuntimeException ignored) {
                            // logged by the capture if it matters
                        }
                        break; // one change call re-reads the module's settings
                    }
                }
            }
            Services.modState().markDirty();
        }

        private static void copyInto(JsonObject from, JsonObject to) {
            for (String k : new ArrayList<>(to.keySet())) {
                to.remove(k);
            }
            for (Map.Entry<String, JsonElement> e : from.entrySet()) {
                to.add(e.getKey(), e.getValue().deepCopy());
            }
        }
    }
}
