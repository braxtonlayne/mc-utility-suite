package com.utilitysuite.ui;

import com.google.gson.JsonObject;
import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.JobSnapshot;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.SettingSpec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Settings → widget mapping, settings access, HUD status line and profile copy. */
class UiLogicTest {

    // ---- widget mapping ---------------------------------------------------------------------------------------------

    @Test
    void specTypesMapToWidgets() {
        assertEquals(SettingWidgetMapper.Kind.TOGGLE, SettingWidgetMapper.kindOf(
                SettingSpec.bool("a", "A", true, "", "")));
        assertEquals(SettingWidgetMapper.Kind.SLIDER, SettingWidgetMapper.kindOf(
                SettingSpec.integer("b", "B", 5, 0, 20, "", "")));
        assertEquals(SettingWidgetMapper.Kind.NUMBER_FIELD, SettingWidgetMapper.kindOf(
                SettingSpec.integer("c", "C", 5, 0, 100_000, "", "")), "too wide for a slider");
        assertEquals(SettingWidgetMapper.Kind.NUMBER_FIELD, SettingWidgetMapper.kindOf(
                SettingSpec.integer("d", "D", 5, 0, 0, "", "")), "no range");
        assertEquals(SettingWidgetMapper.Kind.SLIDER, SettingWidgetMapper.kindOf(
                SettingSpec.decimal("e", "E", 1.5, 0.5, 50, "", "")));
        assertEquals(SettingWidgetMapper.Kind.CYCLE, SettingWidgetMapper.kindOf(
                SettingSpec.choice("f", "F", RotationMode.SILENT, "", "")));
        assertEquals(SettingWidgetMapper.Kind.TEXT_FIELD, SettingWidgetMapper.kindOf(
                SettingSpec.text("g", "G", "x", "", "")));
    }

    @Test
    void sliderMathSnapsAndClamps() {
        SettingSpec i = SettingSpec.integer("i", "I", 10, 0, 20, "", "");
        assertEquals(0.5, SettingWidgetMapper.toSliderPosition(i, 10), 1e-9);
        assertEquals(1.0, SettingWidgetMapper.toSliderPosition(i, 99), 1e-9, "clamped");
        assertEquals(13.0, SettingWidgetMapper.fromSliderPosition(i, 0.651), 1e-9, "rounded to a whole number");
        assertEquals(20.0, SettingWidgetMapper.fromSliderPosition(i, 2.0), 1e-9);

        SettingSpec d = SettingSpec.decimal("d", "D", 240, 30, 1080, "", "");
        assertEquals(20.0, SettingWidgetMapper.step(d), 1e-9, "range 1050/100 = 10.5 -> rounded up to nice step 20");
        double v = SettingWidgetMapper.fromSliderPosition(d, 0.2);
        assertEquals(250.0, v, 1e-9, "raw 240 snaps to min + 11 steps");
        assertEquals(30.0 + 20 * 52, SettingWidgetMapper.fromSliderPosition(d, 0.9999), 1e-9);
        SettingSpec small = SettingSpec.decimal("s", "S", 6, 0.5, 50, "", "");
        assertEquals(0.5, SettingWidgetMapper.step(small), 1e-9);
        assertEquals("6.0", SettingWidgetMapper.format(small, 6.0));
        assertEquals("12", SettingWidgetMapper.format(i, 12.4));
    }

    @Test
    void numberFieldParsing() {
        SettingSpec i = SettingSpec.integer("i", "I", 10, 0, 20, "", "");
        assertEquals(7.0, SettingWidgetMapper.parseNumber(i, " 7 "));
        assertEquals(20.0, SettingWidgetMapper.parseNumber(i, "300"));
        assertEquals(3.0, SettingWidgetMapper.parseNumber(i, "2,6"), "comma decimal, rounded for INT");
        assertNull(SettingWidgetMapper.parseNumber(i, "abc"));
        assertNull(SettingWidgetMapper.parseNumber(i, "-"));
        assertNull(SettingWidgetMapper.parseNumber(i, ""));
    }

    @Test
    void sectionsGroupBasicFirstThenAdvanced() {
        List<SettingSpec> specs = List.of(
                SettingSpec.bool("a", "A", true, "Safety", ""),
                SettingSpec.bool("b", "B", true, "", ""),
                SettingSpec.bool("c", "C", true, "Elytra", "").asAdvanced(),
                SettingSpec.bool("d", "D", true, "Safety", ""),
                SettingSpec.bool("e", "E", true, "", "").asAdvanced(),
                SettingSpec.bool("f", "F", true, "Boat", ""));
        List<SettingWidgetMapper.Section> s = SettingWidgetMapper.layout(specs);
        assertEquals(5, s.size());
        assertEquals("", s.get(0).group());
        assertEquals("Safety", s.get(1).group());
        assertEquals(2, s.get(1).specs().size());
        assertEquals("Boat", s.get(2).group());
        assertTrue(s.get(3).advanced());
        assertEquals("", s.get(3).group());
        assertEquals("Elytra", s.get(4).group());
        assertTrue(SettingWidgetMapper.hasAdvanced(specs));
    }

    @Test
    void enumHelpers() {
        SettingSpec f = SettingSpec.choice("f", "F", RotationMode.VISIBLE, "", "");
        assertEquals("SILENT", SettingWidgetMapper.nextOption(f, "VISIBLE"));
        assertEquals("VISIBLE", SettingWidgetMapper.nextOption(f, "SILENT"));
        assertEquals("VISIBLE", SettingWidgetMapper.nextOption(f, "bogus"));
        assertEquals("Observed only", SettingWidgetMapper.prettyEnum("OBSERVED_ONLY"));
    }

    // ---- settings access --------------------------------------------------------------------------------------------

    @Test
    void settingsAccessReadsDefaultsWritesTypedAndNotifies() {
        ProfileConfig p = new ProfileConfig("sp_T", new JsonObject());
        List<String> changed = new ArrayList<>();
        int[] dirty = {0};
        SettingsAccess a = new SettingsAccess(p, "mod", changed::add, () -> dirty[0]++);
        SettingSpec b = SettingSpec.bool("on", "On", true, "", "");
        SettingSpec i = SettingSpec.integer("n", "N", 5, 0, 10, "", "");
        SettingSpec e = SettingSpec.choice("m", "M", RotationMode.SILENT, "", "");
        SettingSpec s = SettingSpec.text("t", "T", "hello", "", "");
        assertTrue(a.getBool(b));
        assertEquals(5.0, a.getNumber(i));
        assertEquals("SILENT", a.getString(e));
        assertEquals("hello", a.getString(s));

        a.setBool(b, false);
        a.setNumber(i, 7.6);
        a.setString(e, "VISIBLE");
        assertFalse(a.getBool(b));
        assertEquals(8, p.getInt("mod", "n", -1), "INT stored rounded as an integer");
        assertTrue(p.module("mod").get("n").getAsJsonPrimitive().toString().matches("\\d+"));
        assertEquals("VISIBLE", a.getString(e));
        assertEquals(List.of("on", "n", "m"), changed);
        assertEquals(3, dirty[0]);

        p.set("mod", "n", "garbage");
        p.set("mod", "m", "NOT_AN_OPTION");
        assertEquals(5.0, a.getNumber(i), "wrong JSON type falls back to the default");
        assertEquals("SILENT", a.getString(e), "unknown enum value falls back to the default");
        a.setNumber(i, 99);
        assertEquals(10, p.getInt("mod", "n", -1), "clamped to the bounds");
    }

    // ---- HUD status line --------------------------------------------------------------------------------------------

    private static JobSnapshot job(int cur, String step, JobStatus st) {
        return new JobSnapshot(1, "Gather 32 minecraft:diamond", st, step, cur, 32, "", "", 0);
    }

    @Test
    void statusLineMatchesTheSpecExample() {
        HudStatusLine l = new HudStatusLine();
        assertTrue(l.update(job(18, "Mining at Y=-54", JobStatus.RUNNING)));
        assertEquals("[Goal] Gathering 32 Diamonds (18/32) \u2014 Mining at Y=-54", l.text());
        assertTrue(l.update(job(18, "Mining at Y=-54", JobStatus.PAUSED)));
        assertEquals("[Goal] Gathering 32 Diamonds (18/32) \u2014 Paused", l.text());
        assertEquals("Crafting 16 Hoppers", HudStatusLine.prettyLabel("Craft 16 minecraft:hopper"));
        assertEquals("Crafting 1 Iron Pickaxe", HudStatusLine.prettyLabel("Craft 1 minecraft:iron_pickaxe"));
        assertEquals("Build C:\\schem\\house.litematic", HudStatusLine.prettyLabel("Build C:\\schem\\house.litematic")
                .replace("Building", "Build"));
    }

    @Test
    void statusLineRebuildsOnlyOnChange() {
        HudStatusLine l = new HudStatusLine();
        assertTrue(l.isEmpty());
        assertFalse(l.clear(), "already empty");
        JobSnapshot a = job(5, "Mining", JobStatus.RUNNING);
        l.update(a);
        int rebuilds = l.rebuilds();
        assertFalse(l.update(job(5, "Mining", JobStatus.RUNNING)), "equal snapshot, new instance");
        assertEquals(rebuilds, l.rebuilds());
        assertTrue(l.update(job(6, "Mining", JobStatus.RUNNING)));
        assertTrue(l.updateSchematic("Schematic house: 3/10 placed"));
        assertEquals("[Build] Schematic house: 3/10 placed", l.text());
        assertFalse(l.updateSchematic("Schematic house: 3/10 placed"));
        assertTrue(l.clear());
        assertTrue(l.isEmpty());
    }

    @Test
    void unchangedUpdatesAllocateNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        HudStatusLine l = new HudStatusLine();
        JobSnapshot s = job(18, "Mining at Y=-54", JobStatus.RUNNING);
        l.update(s);
        long bytes = AllocationProbe.measure(200_000, () -> l.update(s));
        assertTrue(bytes < 16 * 1024, "allocated " + bytes + " bytes");
    }

    @Test
    void statusLinePartsForThePill() {
        HudStatusLine l = new HudStatusLine();
        l.update(job(18, "Mining at Y=-54", JobStatus.RUNNING));
        assertEquals("18/32", l.progressText());
        assertEquals("Mining at Y=-54", l.detailText());
        assertEquals("minecraft:diamond", l.itemId());
        assertEquals(JobStatus.RUNNING, l.status());
        l.update(job(18, "Mining at Y=-54", JobStatus.PAUSED));
        assertEquals("Paused", l.detailText());
        l.update(new JobSnapshot(2, "Travel to 1 64 2", JobStatus.RUNNING, "", 0, 0, "", "", 0));
        assertEquals("", l.progressText(), "no count target");
        assertEquals("Travelling to 1 64 2", l.detailText(), "empty step falls back to the readable label");
        assertEquals("", l.itemId());
        l.updateSchematic("house: 3/10 placed");
        assertTrue(l.isSchematic());
        assertEquals("house: 3/10 placed", l.detailText());
        l.clear();
        assertEquals("", l.detailText());
        assertEquals("", l.itemId());
    }

    @Test
    void itemIdsComeFromRegistryLookingWordsOnly() {
        assertEquals("minecraft:hopper", HudStatusLine.itemIdOf("Craft 16 minecraft:hopper"));
        assertEquals("", HudStatusLine.itemIdOf("Build C:\\schem\\house.litematic"));
        assertEquals("", HudStatusLine.itemIdOf("Gather 3 item types (schematic)"));
        assertEquals("", HudStatusLine.itemIdOf(null));
    }

    // ---- drawing kit ------------------------------------------------------------------------------------------------

    @Test
    void roundedCornerInsetsShrinkTowardTheMiddle() {
        for (int r = 1; r <= 12; r++) {
            int prev = Integer.MAX_VALUE;
            for (int row = 0; row < r; row++) {
                int in = com.utilitysuite.ui.kit.UiRenderUtils.cornerInset(r, row);
                assertTrue(in >= 0 && in <= r, "inset within the radius");
                assertTrue(in <= prev, "monotonic toward the straight edge");
                prev = in;
            }
            assertTrue(r < 2 || com.utilitysuite.ui.kit.UiRenderUtils.cornerInset(r, 0) > 0, "the outermost row is inset");
        }
        assertEquals(0, com.utilitysuite.ui.kit.UiRenderUtils.cornerInset(6, 6), "below the corner: straight edge");
        assertEquals(0, com.utilitysuite.ui.kit.UiRenderUtils.cornerInset(0, 0));
    }

    @Test
    void colorMixAndFade() {
        assertEquals(0xFF000000, com.utilitysuite.ui.kit.UiTheme.mix(0xFF000000, 0xFFFFFFFF, 0f));
        assertEquals(0xFFFFFFFF, com.utilitysuite.ui.kit.UiTheme.mix(0xFF000000, 0xFFFFFFFF, 1f));
        assertEquals(0xFF7F7F7F, com.utilitysuite.ui.kit.UiTheme.mix(0xFF000000, 0xFFFFFFFF, 0.5f));
        assertEquals(0x7F38BDF8, com.utilitysuite.ui.kit.UiTheme.fade(0xFF38BDF8, 0.5f));
        assertEquals(1f, com.utilitysuite.ui.kit.UiRenderUtils.easeOut(2f));
        assertEquals(0f, com.utilitysuite.ui.kit.UiRenderUtils.easeOut(-1f));
    }

    @Test
    void sliderModelStepsSnapAndWrite() {
        ProfileConfig p = new ProfileConfig("sp_T", new JsonObject());
        SettingsAccess a = new SettingsAccess(p, "core", k -> { }, () -> { });
        SettingSpec d = SettingSpec.decimal("cam", "Cam", 240, 30, 1080, "", "");
        SettingsRenderer.SpecModel m = new SettingsRenderer.SpecModel(d, a);
        assertEquals("240", m.text().replace(".0", ""));
        assertTrue(m.step(1));
        assertEquals(250.0, p.module("core").get("cam").getAsDouble(), 1e-9, "next point of the 20-step grid");
        assertTrue(m.step(-1));
        assertEquals(230.0, p.module("core").get("cam").getAsDouble(), 1e-9);
        assertTrue(m.setPosition(1.0));
        assertEquals(1080.0, p.module("core").get("cam").getAsDouble(), 1e-9, "the end of the track is the max");
        assertFalse(m.setPosition(1.0), "unchanged value writes nothing");
        SettingSpec i = SettingSpec.integer("n", "N", 5, 0, 10, "", "");
        SettingsRenderer.SpecModel mi = new SettingsRenderer.SpecModel(i, a);
        assertTrue(mi.step(-1));
        assertEquals(4, p.getInt("core", "n", -1));
        assertEquals("10", mi.widestText());
    }

    // ---- profiles ---------------------------------------------------------------------------------------------------

    @Test
    void profileCopyReplacesModulesButNeverCore() {
        ProfileConfig global = new ProfileConfig("global", new JsonObject());
        global.set("core", "camera_speed_deg_per_sec", 500.0);
        global.set("smart_inventory", "refill", false);
        global.set("travel_autopilot", "route", "0 64 0");
        ProfileConfig world = new ProfileConfig("sp_W", new JsonObject());
        world.set("smart_inventory", "refill", true);
        world.set("smart_inventory", "auto_armor", true);
        world.set("combat_assistant", "enabled", true);
        int n = ProfileCopier.copy(global.root(), world.root());
        assertEquals(2, n);
        assertFalse(world.getBool("smart_inventory", "refill", true));
        assertFalse(world.root().getAsJsonObject("smart_inventory").has("auto_armor"), "module object replaced");
        assertEquals("0 64 0", world.getString("travel_autopilot", "route", ""));
        assertTrue(world.getBool("combat_assistant", "enabled", false), "modules missing in the source are kept");
        assertFalse(world.root().has("core"));
        global.set("travel_autopilot", "route", "changed");
        assertEquals("0 64 0", world.getString("travel_autopilot", "route", ""), "deep copy");
    }
}
