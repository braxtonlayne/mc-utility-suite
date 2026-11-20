package com.utilitysuite.core;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.SuiteEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModuleRegistryTest {

    static class Counter implements IModule {
        final String id;
        boolean enabled = true;
        int ticks;
        boolean explode;

        Counter(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
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
            enabled = e;
        }

        @Override
        public void onTick() {
            ticks++;
            if (explode) {
                throw new IllegalStateException("boom");
            }
        }
    }

    @Test
    void ticksEnabledModulesAndDisablesOnesThatThrow() {
        ModuleRegistry r = new ModuleRegistry();
        Counter a = new Counter("a");
        Counter b = new Counter("b");
        Counter c = new Counter("c");
        b.enabled = false;
        c.explode = true;
        r.register(a);
        r.register(b);
        r.register(c);
        r.tick();
        r.tick();
        assertEquals(2, a.ticks);
        assertEquals(0, b.ticks);
        assertEquals(1, c.ticks, "thrower disabled after its first failure");
        assertFalse(c.enabled);
        assertThrows(IllegalStateException.class, () -> r.register(new Counter("a")));
        assertSame(a, r.find(Counter.class).orElseThrow());
        assertEquals(3, r.byCategory(ModuleCategory.TOOLS).size());
        assertTrue(r.byId("b").isPresent());
    }

    @Test
    void tickDoesNotAllocate() {
        if (!AllocationProbe.supported()) {
            return;
        }
        ModuleRegistry r = new ModuleRegistry();
        for (int i = 0; i < 10; i++) {
            r.register(new Counter("m" + i));
        }
        long bytes = AllocationProbe.measure(200_000, r::tick);
        assertTrue(bytes < 2048, "registry tick allocated " + bytes + " bytes");
    }

    @Test
    void eventBusDispatchesByTypeAndToCatchAll() {
        SuiteEventBus bus = new SuiteEventBus();
        List<String> got = new ArrayList<>();
        bus.subscribe(SuiteEvent.StopAll.class, e -> got.add("stop:" + e.reason()));
        bus.subscribe(SuiteEvent.class, e -> got.add("any"));
        bus.subscribe(SuiteEvent.Diagnostic.class, e -> {
            throw new RuntimeException("listener failure must not break the bus");
        });
        bus.post(new SuiteEvent.StopAll(0, "x"));
        bus.post(new SuiteEvent.Diagnostic(0, "s", "m"));
        assertEquals(List.of("stop:x", "any", "any"), got);
    }
}
