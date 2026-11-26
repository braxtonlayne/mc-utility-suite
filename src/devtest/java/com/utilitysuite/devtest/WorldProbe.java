package com.utilitysuite.devtest;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.HudElement;
import com.utilitysuite.core.api.visual.IHoloCanvas;
import com.utilitysuite.core.api.visual.IHoloWorld;
import com.utilitysuite.core.api.visual.IVisuals;
import com.utilitysuite.ui.holo.Visuals;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import net.neoforged.neoforge.client.event.sound.PlaySoundEvent;
import net.neoforged.neoforge.common.NeoForge;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Test scaffolding that records what the overlays hand to the Shell's world kit: while armed, the kit is wrapped for
 * the level-render pass (26.3: SubmitCustomGeometryEvent, the Shell kit's Pass.EVENT), installed after the Shell's
 * listeners, and every call is counted by {@code method:TOKEN} before it is forwarded, so a scenario can assert e.g.
 * "the reach ring was drawn in the ALLY tone". Also counts the sounds the sound engine was asked to play (by sound id)
 * and records the text a HUD element draws.
 */
final class WorldProbe {

    private static boolean installed;
    private static boolean armed;
    private static IHoloWorld real;
    private static IHoloWorld proxy;
    private static boolean swapped;
    private static final Map<String, Integer> CALLS = new TreeMap<>();
    private static final Map<String, Integer> SOUNDS = new TreeMap<>();
    static int frames;

    private WorldProbe() {
    }

    static void install() {
        if (installed) {
            return;
        }
        installed = true;
        NeoForge.EVENT_BUS.addListener(EventPriority.HIGH, SubmitCustomGeometryEvent.class, WorldProbe::begin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOW, SubmitCustomGeometryEvent.class, WorldProbe::end);
        NeoForge.EVENT_BUS.addListener(PlaySoundEvent.class, e -> {
            if (e.getOriginalSound() != null) {
                SOUNDS.merge(e.getOriginalSound().getIdentifier().getPath(), 1, Integer::sum);
            }
        });
    }

    /** Starts (or restarts) counting world-kit calls from zero. */
    static void arm() {
        install();
        CALLS.clear();
        frames = 0;
        armed = true;
    }

    static void disarm() {
        armed = false;
    }

    /** Calls whose key starts with {@code prefix} (e.g. "ring:ALLY" or just "ring"). */
    static int calls(String prefix) {
        int n = 0;
        for (Map.Entry<String, Integer> e : CALLS.entrySet()) {
            if (e.getKey().startsWith(prefix)) {
                n += e.getValue();
            }
        }
        return n;
    }

    static String summary() {
        return frames + " frames " + CALLS;
    }

    /** Sounds played (sound id path, e.g. "block.note_block.hat") since start-up. */
    static int sounds(String path) {
        return SOUNDS.getOrDefault(path, 0);
    }

    private static void begin(SubmitCustomGeometryEvent e) {
        if (!armed) {
            return;
        }
        IVisuals v = Services.visualsOrNull();
        IHoloWorld w = v == null ? null : v.world();
        if (w == null || Proxy.isProxyClass(w.getClass())) {
            return;
        }
        if (w != real) {
            real = w;
            proxy = wrap(w);
        }
        frames++;
        Visuals.useWorld(proxy);
        swapped = true;
    }

    private static void end(SubmitCustomGeometryEvent e) {
        if (swapped) {
            swapped = false;
            Visuals.useWorld(real);
        }
    }

    private static IHoloWorld wrap(IHoloWorld target) {
        return (IHoloWorld) Proxy.newProxyInstance(IHoloWorld.class.getClassLoader(), new Class<?>[] {IHoloWorld.class},
                (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return objectMethod(p, m, a);
                    }
                    String token = "";
                    if (a != null) {
                        for (Object o : a) {
                            if (o instanceof HoloToken t) {
                                token = t.name();
                                break;
                            }
                        }
                    }
                    CALLS.merge(m.getName() + ":" + token, 1, Integer::sum);
                    try {
                        return m.invoke(target, a);
                    } catch (InvocationTargetException ex) {
                        throw ex.getCause();
                    }
                });
    }

    /** The strings a HUD element draws in one frame (onto a recording canvas). */
    static List<String> hudTexts(HudElement el) {
        List<String> texts = new ArrayList<>();
        IHoloCanvas c = (IHoloCanvas) Proxy.newProxyInstance(IHoloCanvas.class.getClassLoader(),
                new Class<?>[] {IHoloCanvas.class}, (p, m, a) -> {
                    if (m.getDeclaringClass() == Object.class) {
                        return objectMethod(p, m, a);
                    }
                    if (m.getName().equals("text") && a != null && a.length > 1 && a[1] instanceof String s) {
                        texts.add(s);
                    }
                    return zero(m.getReturnType());
                });
        el.draw(c, 100f, 100f, 1f);
        return texts;
    }

    private static Object objectMethod(Object p, Method m, Object[] a) {
        return switch (m.getName()) {
            case "equals" -> p == a[0];
            case "hashCode" -> System.identityHashCode(p);
            default -> "WorldProbe proxy";
        };
    }

    private static Object zero(Class<?> t) {
        if (t == float.class) {
            return 0f;
        } else if (t == int.class) {
            return 0;
        } else if (t == double.class) {
            return 0d;
        } else if (t == long.class) {
            return 0L;
        } else if (t == boolean.class) {
            return false;
        }
        return null;
    }
}
