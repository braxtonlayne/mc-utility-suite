package com.utilitysuite.devtest;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.ModuleRegistry;
import com.utilitysuite.core.api.IModule;

import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Measurement windows for the performance scenario: client tick time (ClientTickEvent.Pre at HIGHEST to Post at
 * LOWEST priority), frame time (RenderFrameEvent.Pre deltas), per-module {@code onTick} time (from the dev-only
 * {@link ModuleRegistry} timing counters) and per-thread allocated bytes via {@code com.sun.management.ThreadMXBean}.
 * Recording is a few array stores per tick/frame so it does not distort what it measures.
 */
final class Perf {

    private static final int MAX = 1 << 15;

    private final long[] tickNanos = new long[MAX];
    private final long[] frameNanos = new long[MAX];
    private int ticks;
    private int frames;
    private long tickStart;
    private long lastFrame;
    private boolean recording;

    private long windowStartNanos;
    private long[] moduleStart;
    private final Map<String, long[]> threadAllocStart = new LinkedHashMap<>();
    private final com.sun.management.ThreadMXBean mx =
            (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

    void onTickPre() {
        if (recording) {
            tickStart = System.nanoTime();
        }
    }

    void onTickPost() {
        if (recording && tickStart != 0 && ticks < MAX) {
            tickNanos[ticks++] = System.nanoTime() - tickStart;
        }
    }

    void onFrame() {
        long now = System.nanoTime();
        if (recording && lastFrame != 0 && frames < MAX) {
            frameNanos[frames++] = now - lastFrame;
        }
        lastFrame = now;
    }

    void start() {
        ticks = 0;
        frames = 0;
        tickStart = 0;
        lastFrame = 0;
        windowStartNanos = System.nanoTime();
        ModuleRegistry reg = CoreBootstrap.modules();
        moduleStart = reg.tickNanos().clone();
        threadAllocStart.clear();
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            long b = mx.getThreadAllocatedBytes(t.threadId());
            if (b >= 0) {
                threadAllocStart.put(t.getName() + "#" + t.threadId(), new long[] {t.threadId(), b});
            }
        }
        recording = true;
    }

    /** Ends the window and returns a summary. */
    Window stop(String label) {
        recording = false;
        double secs = (System.nanoTime() - windowStartNanos) / 1e9;
        Window w = new Window();
        w.label = label;
        w.seconds = secs;
        w.ticks = ticks;
        w.frames = frames;
        w.tickMeanMs = mean(tickNanos, ticks);
        w.tickP95Ms = pct(tickNanos, ticks, 0.95);
        w.tickMaxMs = pct(tickNanos, ticks, 1.0);
        w.frameMeanMs = mean(frameNanos, frames);
        w.frameP95Ms = pct(frameNanos, frames, 0.95);
        w.frameP99Ms = pct(frameNanos, frames, 0.99);
        w.fps = frames / Math.max(1e-9, secs);
        ModuleRegistry reg = CoreBootstrap.modules();
        long[] now = reg.tickNanos();
        List<IModule> mods = reg.all();
        for (int i = 0; i < mods.size() && i < now.length; i++) {
            long d = now[i] - (i < moduleStart.length ? moduleStart[i] : 0);
            if (d > 0) {
                w.moduleUsPerTick.put(mods.get(i).id(), d / 1000.0 / Math.max(1, ticks));
            }
        }
        for (Map.Entry<String, long[]> e : threadAllocStart.entrySet()) {
            long b = mx.getThreadAllocatedBytes(e.getValue()[0]);
            if (b < 0) {
                continue;
            }
            double perSec = (b - e.getValue()[1]) / secs;
            String name = e.getKey().substring(0, e.getKey().lastIndexOf('#'));
            if (perSec > 1024) {
                w.allocBytesPerSec.merge(name, perSec, Double::sum);
            }
        }
        return w;
    }

    private static double mean(long[] a, int n) {
        if (n == 0) {
            return 0;
        }
        long s = 0;
        for (int i = 0; i < n; i++) {
            s += a[i];
        }
        return s / 1e6 / n;
    }

    private static double pct(long[] a, int n, double p) {
        if (n == 0) {
            return 0;
        }
        long[] c = java.util.Arrays.copyOf(a, n);
        java.util.Arrays.sort(c);
        int idx = (int) Math.min(n - 1, Math.max(0, Math.ceil(p * n) - 1));
        return c[idx] / 1e6;
    }

    static final class Window {
        String label;
        double seconds;
        int ticks;
        int frames;
        double tickMeanMs;
        double tickP95Ms;
        double tickMaxMs;
        double frameMeanMs;
        double frameP95Ms;
        double frameP99Ms;
        double fps;
        final Map<String, Double> moduleUsPerTick = new LinkedHashMap<>();
        final Map<String, Double> allocBytesPerSec = new LinkedHashMap<>();

        String summary() {
            return String.format(Locale.ROOT,
                    "%s: %.1fs, %d ticks, tick mean %.3f ms / p95 %.3f / max %.3f; %d frames (%.1f fps), frame mean "
                            + "%.2f ms / p95 %.2f / p99 %.2f", label, seconds, ticks, tickMeanMs, tickP95Ms, tickMaxMs,
                    frames, fps, frameMeanMs, frameP95Ms, frameP99Ms);
        }

        List<String> topAlloc(int n) {
            List<Map.Entry<String, Double>> es = new ArrayList<>(allocBytesPerSec.entrySet());
            es.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
            List<String> out = new ArrayList<>();
            for (int i = 0; i < Math.min(n, es.size()); i++) {
                out.add(String.format(Locale.ROOT, "%s %.2f MB/s", es.get(i).getKey(), es.get(i).getValue() / 1e6));
            }
            return out;
        }
    }
}
