package com.utilitysuite.world.filters;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFiltersTest {

    @AfterEach
    void reset() {
        RenderFilterHooks.resetAll();
    }

    @Test
    void fixedTimeKeepsTheRealDayCount() {
        assertEquals(5 * 24000L + 6000L, RenderFilterHooks.displayedDayTime(5 * 24000L + 17000L, 6000L));
        assertEquals(123456L, RenderFilterHooks.displayedDayTime(123456L, RenderFilterHooks.NO_TIME));
        assertEquals(18000L, RenderFilterHooks.displayedDayTime(100L, 18000L));
    }

    @Test
    void weatherOverrideLevels() {
        assertTrue(Float.isNaN(RenderFiltersModule.rainFor(RenderFiltersModule.WeatherOverride.REAL)));
        assertEquals(0f, RenderFiltersModule.rainFor(RenderFiltersModule.WeatherOverride.CLEAR));
        assertEquals(1f, RenderFiltersModule.rainFor(RenderFiltersModule.WeatherOverride.RAIN));
        assertEquals(0f, RenderFiltersModule.thunderFor(RenderFiltersModule.WeatherOverride.RAIN));
        assertEquals(1f, RenderFiltersModule.thunderFor(RenderFiltersModule.WeatherOverride.THUNDER));
    }

    @Test
    void hooksDefaultToPassThrough() {
        RenderFilterHooks.resetAll();
        assertFalse(RenderFilterHooks.hideHurtTilt);
        assertFalse(RenderFilterHooks.hideWeather);
        assertFalse(RenderFilterHooks.hideItemActivation);
        assertFalse(RenderFilterHooks.hideExplosionParticles);
        assertEquals(RenderFilterHooks.NO_TIME, RenderFilterHooks.dayTime);
        assertTrue(Float.isNaN(RenderFilterHooks.rainLevel));
    }
}
