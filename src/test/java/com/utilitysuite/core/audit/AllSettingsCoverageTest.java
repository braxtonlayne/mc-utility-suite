package com.utilitysuite.core.audit;

import com.google.gson.JsonObject;
import com.utilitysuite.combat.FakeCombatWorld;
import com.utilitysuite.combat.allies.AllyRoster;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.IModState;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.arbiter.ActionArbiter;
import com.utilitysuite.core.input.FakeInputBackend;
import com.utilitysuite.core.input.InputSmoother;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every module with menu settings, found by scanning the sources (so a new module is covered automatically): each
 * on/off toggle and each setting is flipped through the same path as the menu and must change the module's state,
 * come back when set back, and also be read on profile load. Modules are built with test doubles; the few that need a
 * live game to construct are listed in {@link #NEEDS_GAME} and covered by the in-game ControlsAudit playtest scenario.
 */
class AllSettingsCoverageTest {

    /**
     * Settings that legitimately leave module state untouched when changed, with the reason. Keep this short: the
     * audit reports an exemption that is no longer needed.
     */
    static final Map<String, String> EXEMPT = Map.of();

    /** Modules that cannot be constructed without a running client; the in-game scenario audits them. */
    static final List<String> NEEDS_GAME = List.of(
            "MovementAssist", // static init loads Items (FallClutch)
            "OffhandManager"); // static init loads Items

    /**
     * Checks that touch the running game or FML's file paths, so only the in-game audit can run them:
     * {@code "id#toggle"} = the on/off switch, {@code "id.key"} = one setting.
     */
    static final java.util.Set<String> GAME_ONLY = java.util.Set.of(
            "latency_simulator#toggle", "packet_lab#toggle", "fly#toggle", "velocity_scale#toggle", // read mc.player
            "ally_roster.middle_click_toggle", // loads the roster file
            "storage_memory.stale_after_hours", // loads the storage file
            "world_knowledge.*", "waypoints.*"); // every change saves a per-world file

    private ProfileConfig profile;

    @BeforeEach
    void setUp() {
        Services.resetForTests();
        profile = new ProfileConfig("audit", new JsonObject());
        ProfileConfig global = new ProfileConfig("global", new JsonObject());
        Services.registerModState(new IModState() {
            @Override
            public boolean isCleanScreen() {
                return false;
            }

            @Override
            public void setCleanScreen(boolean active) {
            }

            @Override
            public boolean isAutomationStopped() {
                return false;
            }

            @Override
            public ProfileConfig profile() {
                return profile;
            }

            @Override
            public ProfileConfig global() {
                return global;
            }

            @Override
            public void markDirty() {
            }
        });
        ActionArbiter arbiter = new ActionArbiter();
        InputSmoother smoother = new InputSmoother(new FakeInputBackend(), new Random(1));
        ModuleRegistry registry = new ModuleRegistry();
        arbiter.bind(smoother, registry, null);
        Services.registerArbiter(arbiter);
        Services.registerInput(smoother);
        Services.registerClicker(smoother);
        Services.registerModules(registry);
    }

    @Test
    void everyToggleAndSettingDoesSomething() throws IOException {
        List<Class<?>> classes = configurableModules();
        assertTrue(classes.size() >= 40, "found only " + classes.size() + " configurable modules");
        List<SettingsAudit.Target> targets = new ArrayList<>();
        Map<String, String> unbuilt = new TreeMap<>();
        for (Class<?> c : classes) {
            Object o;
            try {
                o = build(c);
            } catch (Throwable t) {
                unbuilt.put(c.getSimpleName(), t.toString());
                continue;
            }
            if (o == null) {
                unbuilt.put(c.getSimpleName(), "no usable constructor");
                continue;
            }
            IModule m = (IModule) o;
            targets.add(SettingsAudit.Target.of(m, Services.modState()::profile));
        }
        SettingsAudit.Report report = new SettingsAudit(EXEMPT, GAME_ONLY).run(targets);
        System.out.println(report.summary());
        System.out.println("Not built in the unit test (in-game scenario covers them): " + unbuilt);
        assertEquals(new TreeMap<>(Map.of()), filterKnown(unbuilt),
                "new module(s) that cannot be built with test doubles: give them a factory here or add them to "
                        + "NEEDS_GAME");
        assertTrue(report.clean(), report.summary());
    }

    private static Map<String, String> filterKnown(Map<String, String> unbuilt) {
        Map<String, String> out = new TreeMap<>(unbuilt);
        NEEDS_GAME.forEach(out::remove);
        return out;
    }

    // ---- discovery ----------------------------------------------------------------------------------------------

    private static final Pattern DECL = Pattern.compile("^package\\s+([\\w.]+);", Pattern.MULTILINE);

    /** Concrete IModule + IConfigurable classes under src/main/java. */
    static List<Class<?>> configurableModules() throws IOException {
        Path root = Path.of("src/main/java");
        List<Class<?>> out = new ArrayList<>();
        try (Stream<Path> files = Files.walk(root)) {
            for (Path p : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                String src = Files.readString(p);
                if (!src.contains("class ")) {
                    continue;
                }
                Matcher pm = DECL.matcher(src);
                if (!pm.find()) {
                    continue;
                }
                String name = pm.group(1) + "." + p.getFileName().toString().replace(".java", "");
                Class<?> c;
                try {
                    c = Class.forName(name, false, AllSettingsCoverageTest.class.getClassLoader());
                } catch (Throwable t) {
                    continue;
                }
                if (IModule.class.isAssignableFrom(c) && IConfigurable.class.isAssignableFrom(c)
                        && !Modifier.isAbstract(c.getModifiers()) && !c.isInterface()) {
                    out.add(c);
                }
            }
        }
        out.sort(Comparator.comparing(Class::getName));
        return out;
    }

    // ---- construction with test doubles -------------------------------------------------------------------------

    /** Tries each constructor (fewest parameters first) with test doubles or nulls for its parameters. */
    static Object build(Class<?> c) throws Throwable {
        return build(c, 0);
    }

    private static Object build(Class<?> c, int depth) throws Throwable {
        Constructor<?>[] ctors = c.getDeclaredConstructors();
        java.util.Arrays.sort(ctors, Comparator.comparingInt(Constructor::getParameterCount));
        Throwable last = null;
        for (Constructor<?> k : ctors) {
            try {
                k.setAccessible(true);
                Class<?>[] ps = k.getParameterTypes();
                java.lang.reflect.Type[] gs = k.getGenericParameterTypes();
                Object[] args = new Object[ps.length];
                for (int i = 0; i < ps.length; i++) {
                    java.lang.reflect.Type g = i < gs.length ? gs[i] : ps[i];
                    args[i] = doubleFor(ps[i], g, c, depth);
                }
                return k.newInstance(args);
            } catch (java.lang.reflect.InvocationTargetException e) {
                last = e.getCause();
            } catch (Throwable t) {
                last = t;
            }
        }
        if (last != null) {
            throw last;
        }
        return null;
    }

    private static Object doubleFor(Class<?> p, java.lang.reflect.Type generic, Class<?> owner, int depth)
            throws Throwable {
        if (p == boolean.class) {
            return false;
        }
        if (p == int.class || p == long.class || p == double.class || p == float.class || p == short.class
                || p == byte.class || p == char.class) {
            return switch (p.getName()) {
                case "int" -> 0;
                case "long" -> 0L;
                case "double" -> 0.0;
                case "float" -> 0f;
                case "short" -> (short) 0;
                case "byte" -> (byte) 0;
                default -> (char) 0;
            };
        }
        if (p == String.class) {
            return "audit_" + owner.getSimpleName().toLowerCase();
        }
        if (p == IActionArbiter.class) {
            return Services.arbiter();
        }
        if (p == IInputSmoother.class) {
            return Services.input();
        }
        if (p == IContainerClicker.class) {
            return Services.clicker();
        }
        if (p == LongSupplier.class) {
            return (LongSupplier) () -> 0L;
        }
        if (p == BooleanSupplier.class) {
            return (BooleanSupplier) () -> false;
        }
        if (p == IntSupplier.class) {
            return (IntSupplier) () -> 0;
        }
        if (p == DoubleSupplier.class) {
            return (DoubleSupplier) () -> 0.0;
        }
        if (p == Supplier.class) {
            // Supplier<X>: supply a built X when X is a suite class (e.g. GoalEngine's planner)
            Object value = null;
            if (generic instanceof java.lang.reflect.ParameterizedType pt
                    && pt.getActualTypeArguments()[0] instanceof Class<?> x) {
                value = doubleFor(x, x, owner, depth);
            }
            Object v = value;
            return (Supplier<Object>) () -> v;
        }
        if (p.isEnum()) {
            return p.getEnumConstants()[0];
        }
        if (p.isAssignableFrom(FakeCombatWorld.class)) {
            return new FakeCombatWorld();
        }
        if (p == AllyRoster.class) {
            return new AllyRoster();
        }
        // A concrete suite class (a store, an executor, another module): build it the same way, a few levels deep.
        if (p.getName().startsWith("com.utilitysuite.") && !Modifier.isAbstract(p.getModifiers()) && !p.isInterface()
                && !p.isRecord() && depth < 3) {
            try {
                return build(p, depth + 1);
            } catch (Throwable ignored) {
                return null;
            }
        }
        if (!p.isInterface() && !Modifier.isAbstract(p.getModifiers())) {
            try {
                Constructor<?> k = p.getDeclaredConstructor();
                k.setAccessible(true);
                return k.newInstance();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // fall through to null
            }
        }
        return null;
    }
}
