package com.utilitysuite.ui.kit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.utilitysuite.core.AllocationProbe;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.ui.SettingWidgetMapper;
import com.utilitysuite.ui.holo.FrameClock;
import com.utilitysuite.ui.holo.HoloTheme;
import com.utilitysuite.ui.holo.Motion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Widget logic that needs no GL context: animation progress through AnimStore, stable keys, slider mapping. */
class KitWidgetsTest {

    private long nanos;

    @BeforeEach
    void setUp() {
        FrameClock.resetForTests();
        HoloTheme.motion = Motion.FULL;
        nanos = 1_000_000_000L;
        FrameClock.sampleAt(nanos);
    }

    @AfterEach
    void tearDown() {
        HoloTheme.motion = Motion.FULL;
        FrameClock.resetForTests();
    }

    private void frame(long ms) {
        nanos += ms * 1_000_000L;
        FrameClock.sampleAt(nanos);
    }

    @Test
    void toggleSlidesOverItsDurationAndLands() {
        ToggleSwitch t = new ToggleSwitch(false, v -> { }).animKey("test.toggle.slide");
        assertEquals(0f, t.slide(), 1e-6f, "a new switch starts at its end state");
        t.set(true, false);
        float prev = 0f;
        long elapsed = 0;
        while (elapsed < 140) {
            frame(20);
            elapsed += 20;
            float p = t.slide();
            assertTrue(p > prev - 1e-6f, "monotonic slide");
            prev = p;
        }
        assertTrue(prev > 0.6f, "mostly there after 140 ms of frames: " + prev);
        frame(20);
        assertEquals(1f, t.slide(), 1e-6f, "landed");
    }

    @Test
    void toggleIsInstantWithMotionOff() {
        HoloTheme.motion = Motion.OFF;
        ToggleSwitch t = new ToggleSwitch(false, v -> { }).animKey("test.toggle.off");
        t.set(true, false);
        assertEquals(1f, t.slide(), 1e-6f);
        t.set(false, false);
        assertEquals(0f, t.slide(), 1e-6f);
    }

    @Test
    void animationSurvivesARebuild() {
        long key = WidgetKeys.setting("test_module", "rebuild_toggle");
        ToggleSwitch a = new ToggleSwitch(false, v -> { }).animKey(key);
        a.slide();
        a.set(true, false);
        frame(20);
        float mid = a.slide();
        assertTrue(mid > 0f && mid < 1f, "mid-slide: " + mid);
        // the panel rebuilds: a new instance with the new value and the same key keeps sliding
        ToggleSwitch b = new ToggleSwitch(true, v -> { }).animKey(key);
        frame(20);
        float next = b.slide();
        assertTrue(next > mid, "continues from " + mid + " to " + next);
        assertTrue(next < 1f);
    }

    @Test
    void keysDifferPerSettingAndModuleAndAreStable() {
        long a = WidgetKeys.setting("fly", "speed");
        assertEquals(a, WidgetKeys.setting("fly", "speed"));
        assertNotEquals(a, WidgetKeys.setting("fly", "height"));
        assertNotEquals(a, WidgetKeys.setting("sprint", "speed"));
        assertNotEquals(WidgetKeys.module("fly"), WidgetKeys.module("sprint"));
        assertNotEquals(WidgetKeys.module("fly"), WidgetKeys.setting("fly", "fly"));
        SettingSpec s = SettingSpec.integer("speed", "Speed", 5, 0, 20, "", "");
        assertEquals(a, SettingWidgetMapper.widgetKey("fly", s));
        assertNotEquals(0L, a);
    }

    @Test
    void sliderMappingAndDefaultTick() {
        SettingSpec i = SettingSpec.integer("i", "I", 5, 0, 20, "", "");
        assertEquals(0.5, SettingWidgetMapper.toSliderPosition(i, 10), 1e-9);
        assertEquals(13.0, SettingWidgetMapper.fromSliderPosition(i, 0.651), 1e-9);
        assertEquals(0.25, SettingWidgetMapper.defaultPosition(i), 1e-9, "tick at the default");
        SettingSpec noRange = SettingSpec.integer("n", "N", 5, 0, 0, "", "");
        assertEquals(-1.0, SettingWidgetMapper.defaultPosition(noRange), 1e-9);
        SettingSpec flag = SettingSpec.bool("b", "B", true, "", "");
        assertEquals(-1.0, SettingWidgetMapper.defaultPosition(flag), 1e-9);
    }

    @Test
    void themeAliasesFollowTheTokens() {
        UiTheme.refresh();
        assertEquals(HoloTheme.argb(HoloToken.ACCENT), UiTheme.ACCENT);
        assertEquals(HoloTheme.argb(HoloToken.TEXT), UiTheme.TEXT);
        assertEquals(HoloTheme.argb(HoloToken.FIELD), UiTheme.FIELD);
        assertEquals(HoloTheme.argb(HoloToken.DANGER), UiTheme.DANGER);
        assertEquals(0x7F808080, UiTheme.fade(0xFF808080, 0.5f));
    }

    @Test
    void toggleAnimationStepAllocatesNothing() {
        Assumptions.assumeTrue(AllocationProbe.supported());
        ToggleSwitch t = new ToggleSwitch(false, v -> { }).animKey("test.toggle.alloc");
        Runnable body = () -> {
            t.set(!t.value(), false);
            frame(16);
            t.slide();
        };
        for (int i = 0; i < 2_000; i++) {
            body.run();
        }
        long bytes = AllocationProbe.measure(2_000, body);
        assertTrue(bytes < 2_000, "allocated " + bytes + " bytes over 2000 iterations");
    }
}
