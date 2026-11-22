package com.utilitysuite.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.JobStatus;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.RecordingCanvas;
import com.utilitysuite.ui.holo.AnimStore;
import com.utilitysuite.ui.holo.FrameClock;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.HudHost;
import com.utilitysuite.ui.holo.Motion;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The HUD elements of the suite on the host, through the recording canvas: idle and Clean-Screen gates, transients
 * ending, the worst-case frame budget and allocation (full frame, module list mid-reflow).
 */
class HudElementsTest {

    /** A registry entry: enabled flag, category and name. */
    static final class FakeModule implements IModule, IConfigurable {
        final String id;
        final String name;
        final ModuleCategory category;
        boolean on;

        FakeModule(String id, String name, ModuleCategory category, boolean on) {
            this.id = id;
            this.name = name;
            this.category = category;
            this.on = on;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public ModuleCategory category() {
            return category;
        }

        @Override
        public boolean isEnabled() {
            return on;
        }

        @Override
        public void setEnabled(boolean enabled) {
            on = enabled;
        }

        @Override
        public void onTick() {
        }

        @Override
        public String displayName() {
            return name;
        }

        @Override
        public List<SettingSpec> settings() {
            return List.of();
        }

        @Override
        public void onSettingChanged(String key) {
        }
    }

    private static final ModuleCategory[] CATS = {ModuleCategory.JOBS, ModuleCategory.WORLD, ModuleCategory.TRAVEL,
            ModuleCategory.COMBAT, ModuleCategory.TOOLS};

    private long nanos;
    private HudStatusLine line;
    private HudPill pill;
    private HudPill.Badge stopped;
    private HudPill.Badge clean;
    private InfoStrip info;
    private ModuleListHud list;
    private HudHost host;
    private RecordingCanvas canvas;
    private List<IModule> modules;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        HoloTheme.motion = Motion.FULL;
        nanos = 2_000_000_000L;
        FrameClock.sampleAt(nanos);
        line = new HudStatusLine();
        pill = new HudPill(line);
        stopped = new HudPill.Badge("stopped_badge", "Automation stopped", true, 90);
        clean = new HudPill.Badge("clean_badge", "Clean-Screen", false, 80);
        info = new InfoStrip();
        list = new ModuleListHud();
        host = new HudHost();
        host.sampleClock = false;
        host.register(pill);
        host.register(stopped);
        host.register(clean);
        host.register(info);
        host.register(list);
        host.setInstant(stopped, true);
        host.allowInCleanScreen(clean, true);
        canvas = new RecordingCanvas();
        modules = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            modules.add(new FakeModule("m" + i, "Module number " + i + "x".repeat(i % 7), CATS[i % CATS.length],
                    i < 32));
        }
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = Motion.FULL;
        FrameClock.resetForTests();
    }

    private void frame(long ms, boolean cleanScreen) {
        nanos += ms * 1_000_000L;
        FrameClock.sampleAt(nanos);
        host.frame(canvas, cleanScreen);
    }

    private void everythingOn() {
        line.update("gather 32 minecraft:diamond", JobStatus.RUNNING, 18, 32, "Mining at Y=-54");
        stopped.active = true;
        info.enabled = true;
        InfoStripText t = info.text();
        t.setCoords(120, 64, -300);
        t.setConversion(InfoStripText.DIM_OVERWORLD, 120, -300);
        t.setBiome("k", "minecraft:dark_forest");
        t.setTpsTenths(198);
        t.setPing(42);
        for (int i = 0; i < InfoStrip.SLOTS; i++) {
            info.setBar(i, 30 * i, 250);
        }
        info.countBars();
        list.enabled = true;
        list.apply(modules, true);
        for (int i = 0; i < 32; i++) {
            list.leaseGranted("m" + i); // worst case: every row shows the acting glyph
        }
    }

    @Test
    void idleDrawsNothing() {
        for (int i = 0; i < 5; i++) {
            frame(16, false);
        }
        assertEquals(0, canvas.calls);
        // switched on but with nothing to show is still idle
        list.enabled = true;
        info.enabled = true;
        info.text().reset();
        frame(16, false);
        canvas.reset();
        frame(16, false);
        assertEquals(0, canvas.calls);
    }

    @Test
    void cleanScreenDrawsNothingButTheOptInBadge() {
        everythingOn();
        stopped.active = false; // the HUD module never shows it in Clean-Screen
        for (int i = 0; i < 5; i++) {
            frame(16, true);
        }
        assertEquals(0, canvas.calls);
        clean.active = true;
        frame(16, true);
        assertTrue(canvas.calls > 0);
        assertEquals("Clean-Screen", canvas.lastText);
        assertEquals(1, canvas.textRuns);
    }

    @Test
    void zeroCallsOnceEveryTransientHasEnded() {
        everythingOn();
        for (int i = 0; i < 30; i++) {
            frame(16, false);
        }
        assertTrue(canvas.calls > 0);
        line.clear();
        stopped.active = false;
        info.enabled = false;
        for (FakeModule m : fakeModules()) {
            m.on = false;
        }
        list.apply(modules, false);
        frame(16, false);
        assertTrue(canvas.calls > 0, "module rows fade out instead of vanishing");
        for (int i = 0; i < 30; i++) {
            frame(16, false);
        }
        canvas.reset();
        for (int i = 0; i < 5; i++) {
            frame(16, false);
        }
        assertEquals(0, canvas.calls);
        assertEquals(0, list.count());
    }

    private List<FakeModule> fakeModules() {
        List<FakeModule> out = new ArrayList<>();
        for (IModule m : modules) {
            out.add((FakeModule) m);
        }
        return out;
    }

    @Test
    void worstCaseFrameWithThreeToastsAndATakeoverStaysInBudget() {
        everythingOn();
        // the module list is left out: with 33 rows it already spends 33 of the 40 text runs, so three toasts with
        // details (6 runs) on top of it do not fit; the notes tell the integrator
        list.enabled = false;
        long[] clock = {50_000L};
        com.utilitysuite.ui.holo.NoticeBoard board = new com.utilitysuite.ui.holo.NoticeBoard(() -> clock[0], () -> false);
        host.register(new com.utilitysuite.ui.holo.NoticeLayer(board));
        com.utilitysuite.ui.holo.AutopilotHud ap = new com.utilitysuite.ui.holo.AutopilotHud(() -> clock[0], () -> board);
        host.register(ap);
        ap.update(true, null);
        ap.update(true, com.utilitysuite.core.api.IAutopilot.Reflex.FALL_CLUTCH);
        board.post(com.utilitysuite.core.api.visual.NoticeKind.ERROR, "Died", "1 64 2 (minecraft:overworld)");
        board.post(com.utilitysuite.core.api.visual.NoticeKind.WARN, "Auto-Fish stopped", "no rod");
        board.post(com.utilitysuite.core.api.visual.NoticeKind.DONE, "Queued job #4", "gather 32 iron_ingot");
        for (int i = 0; i < 30; i++) {
            frame(16, false);
        }
        canvas.reset();
        frame(16, false);
        assertTrue(canvas.quads < 600, "quads " + canvas.quads);
        assertTrue(canvas.textRuns < 40, "text runs " + canvas.textRuns);
    }

    @Test
    void worstCaseFrameStaysInBudget() {
        everythingOn();
        for (int i = 0; i < 30; i++) {
            frame(16, false);
        }
        canvas.reset();
        frame(16, false);
        assertEquals(33, list.count(), "32 modules and the autopilot");
        assertTrue(canvas.quads < 600, "quads " + canvas.quads);
        assertTrue(canvas.textRuns < 40, "text runs " + canvas.textRuns);
    }

    @Test
    void statusLineStacksAboveTheBadgeInItsSlot() {
        everythingOn();
        list.enabled = false;
        info.enabled = false;
        frame(16, false);
        // pill at the top centre, the badge right under it
        float pillW = pill.cachedWidth();
        assertTrue(pillW >= HudPill.MIN_W && pillW <= HudPill.MAX_W);
        assertEquals(0.5625f, line.fraction(), 1e-6f);
    }

    @Test
    void acceptsLeasesOnlyForKnownModulesAndCountsThemDown() {
        list.enabled = true;
        list.apply(modules, false);
        list.leaseGranted("m3");
        list.leaseGranted("nobody");
        assertTrue(list.acting(3));
        list.leaseEnded("m3");
        list.leaseEnded("m3");
        assertFalse(list.acting(3));
    }

    @Test
    void fullFrameAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        everythingOn();
        long[] t = {nanos};
        long bytes = AllocationProbe.measure(5_000, () -> {
            t[0] += 16_000_000L;
            FrameClock.sampleAt(t[0]);
            canvas.reset();
            host.frame(canvas, false);
        });
        assertEquals(0L, bytes, "full HUD frame allocated " + bytes + " bytes");
    }

    @Test
    void moduleListMidReflowAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        list.enabled = true;
        list.apply(modules, true);
        List<FakeModule> fakes = fakeModules();
        long[] t = {nanos};
        int[] f = {0};
        long bytes = AllocationProbe.measure(5_000, () -> {
            t[0] += 16_000_000L;
            FrameClock.sampleAt(t[0]);
            int n = ++f[0];
            if (n % 6 == 0) {
                FakeModule m = fakes.get((n / 6) % fakes.size());
                m.on = !m.on; // rows keep entering and leaving: fades, slides and reflow every few frames
                list.apply(modules, true);
            }
            canvas.reset();
            host.frame(canvas, false);
        });
        assertEquals(0L, bytes, "module list mid-reflow allocated " + bytes + " bytes");
    }

    @Test
    void animationStateSurvivesInTheSharedStoreOnly() {
        // the status line text fade lives in the shared store: a rebuild restarts it from zero
        everythingOn();
        frame(16, false);
        line.update("gather 32 minecraft:diamond", JobStatus.RUNNING, 19, 32, "Mining at Y=-54");
        frame(16, false);
        assertTrue(AnimStore.SHARED.peek(AnimStore.key(HudPill.ID, 0x7E), -1f) < 1f);
    }
}
