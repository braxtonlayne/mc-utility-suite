package com.utilitysuite.jobs.fieldwork;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Debris Blasting's hand-offs to Lava Plug and Vein Work run while those modules are switched off (the registry does
 * not tick them then). Stopping the blasting must also end such a hand-off, or the next cycle waits on a "busy" module
 * nobody ticks (found by the FieldJobs playtest).
 */
class DebrisBlastHandOffTest {

    private static void set(Object o, String name, Object value) throws ReflectiveOperationException {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, value);
    }

    @Test
    void switchingBlastingOffEndsHandOffsOfModulesThatAreOff() throws Exception {
        LavaPlugModule lava = new LavaPlugModule(null);
        VeinWorkModule vein = new VeinWorkModule();
        DebrisBlastModule blast = new DebrisBlastModule(vein, lava);
        set(lava, "lavaCell", 123L);
        set(vein, "scanning", true);
        assertTrue(lava.isBusy());
        assertTrue(vein.isBusy());
        blast.setEnabled(true);
        blast.setEnabled(false);
        assertFalse(lava.isBusy(), "lava plug hand-off still busy");
        assertFalse(vein.isBusy(), "vein work hand-off still busy");
    }

    /** One hand-off at a time: with a plug run going, the debris is not handed to Vein Work (and the reverse). */
    @Test
    void handsOffOneAtATime() throws Exception {
        LavaPlugModule lava = new LavaPlugModule(null);
        VeinWorkModule vein = new VeinWorkModule();
        DebrisBlastModule blast = new DebrisBlastModule(vein, lava);
        java.lang.reflect.Method mine = DebrisBlastModule.class.getDeclaredMethod("mineDebris",
                net.minecraft.client.player.LocalPlayer.class);
        java.lang.reflect.Method plug = DebrisBlastModule.class.getDeclaredMethod("plugLava",
                net.minecraft.client.player.LocalPlayer.class);
        mine.setAccessible(true);
        plug.setAccessible(true);
        set(lava, "lavaCell", 123L);
        mine.invoke(blast, (Object) null); // would need the player (reach check) if it went on to hand off
        assertTrue(blast.status().startsWith("Plugging"), blast.status());
        assertFalse(vein.isBusy());
        set(lava, "lavaCell", Long.MIN_VALUE);
        set(vein, "scanning", true);
        plug.invoke(blast, (Object) null);
        assertTrue(blast.status().startsWith("Mining"), blast.status());
        assertFalse(lava.isBusy());
    }

    @Test
    void handOffOfAModuleThatIsOnIsLeftToIt() throws Exception {
        LavaPlugModule lava = new LavaPlugModule(null);
        VeinWorkModule vein = new VeinWorkModule();
        DebrisBlastModule blast = new DebrisBlastModule(vein, lava);
        lava.setEnabled(true);
        set(lava, "lavaCell", 123L);
        blast.setEnabled(true);
        blast.setEnabled(false);
        assertTrue(lava.isBusy(), "an enabled Lava Plug finishes its own run");
    }
}
