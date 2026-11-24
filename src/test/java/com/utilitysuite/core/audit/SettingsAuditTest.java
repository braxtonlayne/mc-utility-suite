package com.utilitysuite.core.audit;

import com.google.gson.JsonObject;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The audit itself: it must catch each kind of broken control, and stay quiet on a module that works. */
class SettingsAuditTest {

    enum Mode { A, B, C }

    /** A configurable module whose faults are switched on per test. */
    static final class Probe implements IModule, IConfigurable {
        final ProfileConfig profile;
        boolean enabled;
        boolean on;
        int level = 5;
        Mode mode = Mode.A;
        String text = "";
        boolean latched;
        // faults
        boolean deadToggle;
        boolean deadLevel;
        boolean latchOn;
        boolean ignoreOnLoad;
        boolean deadModeC;
        boolean throwOnText;

        Probe(ProfileConfig profile) {
            this.profile = profile;
        }

        @Override
        public String id() {
            return "probe";
        }

        @Override
        public ModuleCategory category() {
            return ModuleCategory.TOOLS;
        }

        @Override
        public boolean isEnabled() {
            return enabled;
        }

        @Override
        public void setEnabled(boolean e) {
            if (!deadToggle) {
                enabled = e;
            }
        }

        @Override
        public void onTick() {
        }

        @Override
        public String displayName() {
            return "Probe";
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of(SettingSpec.bool("on", "On", false, "", ""),
                    SettingSpec.integer("level", "Level", 5, 0, 10, "", ""),
                    SettingSpec.choice("mode", "Mode", Mode.A, "", ""),
                    SettingSpec.text("text", "Text", "", "", ""));
        }

        @Override
        public void onSettingChanged(String key) {
            read(profile, key);
        }

        @Override
        public void onProfileLoaded(ProfileConfig p) {
            if (!ignoreOnLoad) {
                read(p, null);
            }
        }

        private void read(ProfileConfig p, String key) {
            on = p.getBool("probe", "on", false);
            if (latchOn && on) {
                latched = true;
            }
            if (!deadLevel) {
                level = p.getInt("probe", "level", 5);
            }
            Mode m = Mode.valueOf(p.getString("probe", "mode", "A"));
            mode = deadModeC && m == Mode.C ? Mode.A : m;
            String t = p.getString("probe", "text", "");
            if (throwOnText && "text".equals(key) && !t.isEmpty()) {
                throw new IllegalStateException("bad text");
            }
            text = t.startsWith("minecraft:") ? t : ""; // only item ids take effect
        }
    }

    private static Probe probe() {
        return new Probe(new ProfileConfig("t", new JsonObject()));
    }

    private static SettingsAudit.Report audit(Probe p, Map<String, String> exempt) {
        return new SettingsAudit(exempt).run(List.of(SettingsAudit.Target.of(p, () -> p.profile)));
    }

    private static boolean has(SettingsAudit.Report r, String key, String words) {
        return r.findings().stream().anyMatch(f -> f.key().equals(key) && f.problem().contains(words));
    }

    @Test
    void aWorkingModuleIsClean() {
        SettingsAudit.Report r = audit(probe(), Map.of());
        assertTrue(r.clean(), r.summary());
        assertEquals(4, r.settings());
        // on: 1, level: 10 and 0, mode: B and C, text: tries samples until an item id works
        assertTrue(r.variantsTried() >= 6, r.summary());
    }

    @Test
    void reportsADeadSetting() {
        Probe p = probe();
        p.deadLevel = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "level", "changes nothing"), r.summary());
        assertEquals(2 + 0, r.findings().stream().filter(f -> f.key().equals("level")
                && f.problem().contains("changes nothing")).count(), r.summary());
    }

    @Test
    void reportsOneDeadEnumOption() {
        Probe p = probe();
        p.deadModeC = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "mode", "setting it to C changes nothing"), r.summary());
        assertTrue(r.findings().stream().noneMatch(f -> f.problem().contains("to B")), r.summary());
    }

    @Test
    void reportsAValueThatDoesNotSwitchBack() {
        Probe p = probe();
        p.latchOn = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "on", "does not restore"), r.summary());
    }

    @Test
    void reportsASettingIgnoredOnProfileLoad() {
        Probe p = probe();
        p.ignoreOnLoad = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "level", "ignored when the profile loads"), r.summary());
    }

    @Test
    void reportsADeadToggle() {
        Probe p = probe();
        p.deadToggle = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "", "toggle does nothing"), r.summary());
    }

    @Test
    void reportsAThrowingSetting() {
        Probe p = probe();
        p.throwOnText = true;
        SettingsAudit.Report r = audit(p, Map.of());
        assertTrue(has(r, "text", "threw when set to"), r.summary());
    }

    @Test
    void anExemptionThatIsNoLongerNeededIsReported() {
        SettingsAudit.Report r = audit(probe(), Map.of("probe.level", "read live"));
        assertTrue(has(r, "level", "remove the exemption"), r.summary());
    }

    @Test
    void anExemptDeadSettingIsQuiet() {
        Probe p = probe();
        p.deadLevel = true;
        SettingsAudit.Report r = audit(p, Map.of("probe.level", "read live"));
        assertTrue(r.clean(), r.summary());
    }

    @Test
    void skippedChecksAreNotRun() {
        Probe p = probe();
        p.deadToggle = true;
        p.deadLevel = true;
        SettingsAudit.Report r = new SettingsAudit(Map.of(), Set.of("probe#toggle", "probe.level"))
                .run(List.of(SettingsAudit.Target.of(p, () -> p.profile)));
        assertTrue(r.clean(), r.summary());
    }

    @Test
    void theProfileIsLeftAsItWas() {
        Probe p = probe();
        p.profile.set("probe", "level", 7);
        audit(p, Map.of());
        JsonObject o = p.profile.module("probe");
        assertEquals(7, o.get("level").getAsInt());
        assertTrue(!o.has("on") && !o.has("mode") && !o.has("text"), o.toString());
        assertEquals(7, p.level, "the module re-read the restored value");
    }

    @Test
    void badSpecsAreReported() {
        assertTrue(SettingsAudit.specProblem(SettingSpec.integer("k", "K", 50, 0, 10, "", "")).contains("outside"));
        assertTrue(SettingsAudit.specProblem(new SettingSpec("k", "K", SettingSpec.Type.ENUM, "", false, "Z", 0, 0,
                List.of("X", "Y"), "")).contains("not in"));
        assertTrue(SettingsAudit.specProblem(SettingSpec.bool("k", "", true, "", "")).contains("blank label"));
    }
}
