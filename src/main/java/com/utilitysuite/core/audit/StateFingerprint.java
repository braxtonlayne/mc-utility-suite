package com.utilitysuite.core.audit;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Diagnostics only (never on a hot path): a reflective snapshot of an object's own state, used by the settings audit
 * to tell whether changing a setting changed anything inside a module. Walks instance fields of suite classes
 * ({@code com.utilitysuite.*}) depth-first, records primitives, strings, enums and boxed values as {@code path=value}
 * lines, and records only the class name of engine objects (Minecraft, Netty, threads), lambdas and executors so the
 * snapshot never reaches into the game world. Bounded in depth and node count.
 */
public final class StateFingerprint {

    private static final int MAX_DEPTH = 7;
    private static final int MAX_NODES = 40_000;
    private static final int MAX_ELEMENTS = 48;
    private static final String SUITE = "com.utilitysuite.";

    private static final Map<Class<?>, List<Field>> FIELDS = new java.util.concurrent.ConcurrentHashMap<>();

    private StateFingerprint() {
    }

    /** {@code path -> value} for every reachable leaf, sorted by path. */
    public static Map<String, String> of(Object root) {
        Map<String, String> out = new TreeMap<>();
        IdentityHashMap<Object, String> seen = new IdentityHashMap<>();
        int[] budget = {MAX_NODES};
        walk(root, "", 0, out, seen, budget);
        return out;
    }

    /** Paths whose values differ between two snapshots (added, removed or changed), at most {@code limit}. */
    public static List<String> diff(Map<String, String> a, Map<String, String> b, int limit) {
        List<String> d = new ArrayList<>();
        TreeMap<String, String> all = new TreeMap<>(a);
        b.forEach(all::putIfAbsent);
        for (String k : all.keySet()) {
            String va = a.get(k);
            String vb = b.get(k);
            if (va == null ? vb != null : !va.equals(vb)) {
                d.add(k + ": " + va + " -> " + vb);
                if (d.size() >= limit) {
                    break;
                }
            }
        }
        return d;
    }

    private static void walk(Object o, String path, int depth, Map<String, String> out,
                             IdentityHashMap<Object, String> seen, int[] budget) {
        if (--budget[0] < 0) {
            return;
        }
        if (o == null) {
            out.put(path, "null");
            return;
        }
        if (o instanceof Class<?> holder) {
            walkStatics(holder, path, depth, out, seen, budget);
            return;
        }
        Class<?> c = o.getClass();
        if (o instanceof CharSequence || o instanceof Number || o instanceof Boolean || o instanceof Character) {
            out.put(path, String.valueOf(o));
            return;
        }
        if (o instanceof Enum<?> e) {
            out.put(path, e.name());
            return;
        }
        if (c.isArray()) {
            int n = Array.getLength(o);
            out.put(path + "#len", Integer.toString(n));
            if (c.getComponentType().isPrimitive()) {
                out.put(path + "#hash", Integer.toString(primitiveArrayHash(o)));
                return;
            }
            if (depth >= MAX_DEPTH || seen.containsKey(o)) {
                return;
            }
            seen.put(o, path);
            for (int i = 0; i < Math.min(n, MAX_ELEMENTS); i++) {
                walk(Array.get(o, i), path + "[" + i + "]", depth + 1, out, seen, budget);
            }
            return;
        }
        String name = c.getName();
        if (o instanceof Collection<?> col) {
            out.put(path + "#size", Integer.toString(col.size()));
            if (depth >= MAX_DEPTH || seen.containsKey(o) || !isPlainJdkOrFastutil(name)) {
                return;
            }
            seen.put(o, path);
            int i = 0;
            for (Object e : col) {
                if (i >= MAX_ELEMENTS) {
                    break;
                }
                walk(e, path + "[" + i++ + "]", depth + 1, out, seen, budget);
            }
            return;
        }
        if (o instanceof Map<?, ?> m) {
            out.put(path + "#size", Integer.toString(m.size()));
            if (depth >= MAX_DEPTH || seen.containsKey(o) || !isPlainJdkOrFastutil(name)) {
                return;
            }
            seen.put(o, path);
            int i = 0;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (i++ >= MAX_ELEMENTS) {
                    break;
                }
                walk(e.getValue(), path + "{" + e.getKey() + "}", depth + 1, out, seen, budget);
            }
            return;
        }
        if (!name.startsWith(SUITE)) {
            // Engine object or JDK utility: only its class matters (whether it is set or not).
            out.put(path + "#type", simpleType(c));
            return;
        }
        if (name.contains("$$Lambda")) {
            // A suite lambda: its captured values are state (e.g. a Supplier<Planner> holding the planner).
            out.put(path + "#type", simpleType(c));
        }
        if (depth >= MAX_DEPTH) {
            return;
        }
        String prior = seen.get(o);
        if (prior != null) {
            out.put(path + "#ref", prior); // shared reference: point at the first path, do not walk it twice
            return;
        }
        seen.put(o, path);
        for (Field f : fieldsOf(c)) {
            Object v;
            try {
                v = f.get(o);
            } catch (ReflectiveOperationException | RuntimeException ex) {
                continue;
            }
            walk(v, path.isEmpty() ? f.getName() : path + "." + f.getName(), depth + 1, out, seen, budget);
        }
    }

    /** A class passed as a root stands for its static state (e.g. a theme the settings write into). */
    private static void walkStatics(Class<?> holder, String path, int depth, Map<String, String> out,
                                   IdentityHashMap<Object, String> seen, int[] budget) {
        if (!holder.getName().startsWith(SUITE)) {
            return;
        }
        String prefix = (path.isEmpty() ? "" : path + ".") + holder.getSimpleName() + "::";
        for (Field f : holder.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.isSynthetic()
                    || (Modifier.isFinal(f.getModifiers()) && (f.getType().isPrimitive() || f.getType() == String.class))) {
                continue; // constants never change
            }
            try {
                f.setAccessible(true);
                walk(f.get(null), prefix + f.getName(), depth + 1, out, seen, budget);
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // inaccessible: skip
            }
        }
    }

    private static boolean isPlainJdkOrFastutil(String className) {
        return className.startsWith("java.util.") || className.startsWith("it.unimi.dsi.fastutil.")
                || className.startsWith(SUITE);
    }

    private static String simpleType(Class<?> c) {
        String n = c.getName();
        int lambda = n.indexOf("$$Lambda");
        return lambda >= 0 ? n.substring(0, lambda) + "$$Lambda" : n;
    }

    private static int primitiveArrayHash(Object a) {
        return switch (a) {
            case int[] x -> java.util.Arrays.hashCode(x);
            case long[] x -> java.util.Arrays.hashCode(x);
            case double[] x -> java.util.Arrays.hashCode(x);
            case float[] x -> java.util.Arrays.hashCode(x);
            case boolean[] x -> java.util.Arrays.hashCode(x);
            case byte[] x -> java.util.Arrays.hashCode(x);
            case short[] x -> java.util.Arrays.hashCode(x);
            case char[] x -> java.util.Arrays.hashCode(x);
            default -> 0;
        };
    }

    private static List<Field> fieldsOf(Class<?> c) {
        return FIELDS.computeIfAbsent(c, k -> {
            List<Field> fs = new ArrayList<>();
            ArrayDeque<Class<?>> chain = new ArrayDeque<>();
            for (Class<?> x = k; x != null && x != Object.class; x = x.getSuperclass()) {
                chain.push(x);
            }
            for (Class<?> x : chain) {
                for (Field f : x.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) {
                        continue;
                    }
                    try {
                        f.setAccessible(true);
                        fs.add(f);
                    } catch (RuntimeException ignored) {
                        // module-protected field: skip it
                    }
                }
            }
            return List.copyOf(fs);
        });
    }
}
