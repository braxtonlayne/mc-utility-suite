package com.utilitysuite.ui.holo;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.visual.EffectLevel;
import com.utilitysuite.core.api.visual.HoloToken;
import com.utilitysuite.core.api.visual.IHoloWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.SubmitNodeCollector;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.client.renderer.rendertype.RenderTypes;
import net.neoforged.neoforge.client.event.SubmitCustomGeometryEvent;
import org.joml.Matrix4f;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;

/**
 * {@link IHoloWorld}: the Shell's world drawing kit. Adopters hand it camera-relative segments in theme tokens during
 * the Shell's level-render pass; the kit collects them into preallocated arrays and, at the end of the pass, keeps
 * the nearest {@link #MAX_SEGMENTS} (the farthest are dropped first) and emits them in one batch:
 * <ul>
 *     <li>ribbons (effects SUBTLE/FULL, no shader pack): one camera-facing quad per segment whose width grows with
 *     the distance of each end, so it holds about {@link #CORE_GUI_PX} GUI pixels on screen, sampling the soft
 *     falloff row of the {@link HoloAtlas}; at FULL a second, three times wider quad on the glow row at half alpha.
 *     Render type {@link HoloRenderTypes#world()};</li>
 *     <li>plain 1 px lines ({@link EffectLevel#OFF}, or a shader pack is active): vanilla {@code RenderTypes.lines()}
 *     with the same palette.</li>
 * </ul>
 * Geometry emission is separate from the vertex sink ({@link Sink}), so tests count segments, check the cap and
 * the OFF fallback and measure allocation against a no-op sink. Dashed polylines advance along their direction
 * (phase from {@link FrameClock}; static unless {@link Motion#FULL}). Nothing is allocated per call or per frame.
 * Client render thread only.
 */
public final class HoloWorldKit implements IHoloWorld {

    /** Segments drawn per frame at most (stem-ui 5.4). */
    public static final int MAX_SEGMENTS = 512;
    /** Segments collected per frame at most; more are dropped as they come (see {@link #dropped()}). */
    public static final int STORE = MAX_SEGMENTS * 4;
    /** Ribbon core width in GUI pixels (scaled by the GUI scale), so the line reads the same at every resolution. */
    public static final float CORE_GUI_PX = 0.75f;
    /** Dash travel speed along a polyline, blocks per second. */
    public static final float DASH_SPEED = 1.5f;
    /** Closest an end may be to the camera for width purposes (a zero width would vanish). */
    static final float MIN_DIST = 0.05f;
    /** Float slack at dash boundaries, blocks. */
    static final float DASH_EPS = 1.0e-4f;
    /** Ring segment count. */
    public static final int RING_SEGMENTS = 32;
    /** Lift of ground marks above the surface, against z-fighting. */
    static final float GROUND_LIFT = 0.02f;

    private static final float[] RING_SIN = new float[RING_SEGMENTS + 1];
    private static final float[] RING_COS = new float[RING_SEGMENTS + 1];

    static {
        for (int i = 0; i <= RING_SEGMENTS; i++) {
            double a = i * (Math.PI * 2.0 / RING_SEGMENTS);
            RING_SIN[i] = (float) Math.sin(a);
            RING_COS[i] = (float) Math.cos(a);
        }
    }

    /** Receives what the kit emits. Four quad vertices in order per ribbon quad; two ends per plain line. */
    public interface Sink {
        void vertex(float x, float y, float z, float u, float v, int argb);

        void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb);
    }

    private final float[] seg = new float[STORE * 6];
    private final int[] colour = new int[STORE];
    private final float[] dist = new float[STORE];
    private final float[] scratch = new float[STORE];

    private int count;
    private int dropped;
    private boolean open;
    private boolean plain;
    private boolean glow;
    private float worldPerPx = 0.001f;
    private float corePx = 1.5f;
    private float dashClock;

    // ---- pass -------------------------------------------------------------------------------------------------------

    /**
     * Starts a pass. {@code plainLines}: 1 px lines instead of ribbons; {@code glowOn}: the glow quad (FULL);
     * {@code worldPerPixelAt1}: world units per screen pixel at distance 1 ({@code 2 / (proj.m11 * heightPx)});
     * {@code corePixels}: ribbon core width in screen pixels; {@code dashSeconds}: the dash clock (0 = static).
     */
    public void begin(boolean plainLines, boolean glowOn, float worldPerPixelAt1, float corePixels,
                      float dashSeconds) {
        count = 0;
        dropped = 0;
        open = true;
        plain = plainLines;
        glow = glowOn && !plainLines;
        worldPerPx = worldPerPixelAt1;
        corePx = corePixels;
        dashClock = dashSeconds;
    }

    /** Segments collected so far this pass. */
    public int count() {
        return count;
    }

    /** Segments that did not fit in the store this pass. */
    public int dropped() {
        return dropped;
    }

    public boolean isOpen() {
        return open;
    }

    public boolean plain() {
        return plain;
    }

    /** Stops collecting but keeps the segments for a later {@link #flush} (the deferred world draw). */
    public void close() {
        open = false;
    }

    /** Ends the pass without drawing. */
    public void cancel() {
        open = false;
        count = 0;
    }

    /**
     * Ends the pass: emits the nearest {@link #MAX_SEGMENTS} segments to {@code sink}. Returns the segments emitted.
     */
    public int flush(Sink sink) {
        open = false;
        int n = count;
        count = 0;
        if (n == 0) {
            return 0;
        }
        if (n <= MAX_SEGMENTS) {
            for (int i = 0; i < n; i++) {
                emit(sink, i);
            }
            return n;
        }
        // keep the nearest MAX_SEGMENTS: the k-th smallest distance is the cut; ties at the cut fill the remainder
        System.arraycopy(dist, 0, scratch, 0, n);
        float cut = select(scratch, n, MAX_SEGMENTS - 1);
        int below = 0;
        for (int i = 0; i < n; i++) {
            if (dist[i] < cut) {
                below++;
            }
        }
        int ties = MAX_SEGMENTS - below;
        int emitted = 0;
        for (int i = 0; i < n; i++) {
            float d = dist[i];
            if (d < cut || (d == cut && ties-- > 0)) {
                emit(sink, i);
                emitted++;
            }
        }
        return emitted;
    }

    /** k-th smallest (0-based) of the first {@code n} values, reordering {@code a} in place (Hoare quickselect). */
    static float select(float[] a, int n, int k) {
        int lo = 0;
        int hi = n - 1;
        while (lo < hi) {
            float pivot = a[(lo + hi) >>> 1];
            int i = lo;
            int j = hi;
            while (i <= j) {
                while (a[i] < pivot) {
                    i++;
                }
                while (a[j] > pivot) {
                    j--;
                }
                if (i <= j) {
                    float t = a[i];
                    a[i] = a[j];
                    a[j] = t;
                    i++;
                    j--;
                }
            }
            if (k <= j) {
                hi = j;
            } else if (k >= i) {
                lo = i;
            } else {
                break;
            }
        }
        return a[k];
    }

    private void emit(Sink sink, int i) {
        int o = i * 6;
        float x0 = seg[o];
        float y0 = seg[o + 1];
        float z0 = seg[o + 2];
        float x1 = seg[o + 3];
        float y1 = seg[o + 4];
        float z1 = seg[o + 5];
        int argb = colour[i];
        if (plain) {
            sink.line(x0, y0, z0, x1, y1, z1, argb);
            return;
        }
        quad(sink, x0, y0, z0, x1, y1, z1, corePx, argb, HoloKit.Geometry.ROW_SOFT);
        if (glow) {
            quad(sink, x0, y0, z0, x1, y1, z1, corePx * 3f, HoloTheme.fade(argb, 0.5f), HoloKit.Geometry.ROW_GLOW);
        }
    }

    /** One camera-facing quad (camera at the origin) whose half width at each end holds {@code px} on screen. */
    private void quad(Sink s, float x0, float y0, float z0, float x1, float y1, float z1, float px, int argb,
                      int row) {
        float dx = x1 - x0;
        float dy = y1 - y0;
        float dz = z1 - z0;
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float mz = (z0 + z1) * 0.5f;
        // side = dir x toMidpoint: perpendicular to the segment and to the view ray
        float sx = dy * mz - dz * my;
        float sy = dz * mx - dx * mz;
        float sz = dx * my - dy * mx;
        float sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        if (sl < 1.0e-6f) {
            return; // looking straight along the segment: it covers no area
        }
        float half = HoloKit.Geometry.halfWidth(px, row) * worldPerPx;
        float d0 = Math.max(MIN_DIST, (float) Math.sqrt(x0 * x0 + y0 * y0 + z0 * z0)) * half / sl;
        float d1 = Math.max(MIN_DIST, (float) Math.sqrt(x1 * x1 + y1 * y1 + z1 * z1)) * half / sl;
        float v = HoloKit.Geometry.rowV(row);
        float u0 = 0.5f / HoloKit.Geometry.ATLAS_W;
        float u1 = 15.5f / HoloKit.Geometry.ATLAS_W;
        s.vertex(x0 + sx * d0, y0 + sy * d0, z0 + sz * d0, u0, v, argb);
        s.vertex(x0 - sx * d0, y0 - sy * d0, z0 - sz * d0, u1, v, argb);
        s.vertex(x1 - sx * d1, y1 - sy * d1, z1 - sz * d1, u1, v, argb);
        s.vertex(x1 + sx * d1, y1 + sy * d1, z1 + sz * d1, u0, v, argb);
    }

    // ---- collection -------------------------------------------------------------------------------------------------

    private void add(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
        if (!open || (argb >>> 24) == 0) {
            return;
        }
        if (count >= STORE) {
            dropped++;
            return;
        }
        int o = count * 6;
        seg[o] = x0;
        seg[o + 1] = y0;
        seg[o + 2] = z0;
        seg[o + 3] = x1;
        seg[o + 4] = y1;
        seg[o + 5] = z1;
        colour[count] = argb;
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float mz = (z0 + z1) * 0.5f;
        dist[count] = mx * mx + my * my + mz * mz;
        count++;
    }

    private static int argb(HoloToken token, float alpha) {
        return token == null ? 0 : HoloTheme.argb(token, alpha);
    }

    @Override
    public void ribbon(float x0, float y0, float z0, float x1, float y1, float z1, HoloToken token, float alpha) {
        add(x0, y0, z0, x1, y1, z1, argb(token, alpha));
    }

    @Override
    public void polyline(float[] xyz, int count, float dash, HoloToken token, float alpha) {
        int c = argb(token, alpha);
        if (!open || (c >>> 24) == 0 || xyz == null || count < 2) {
            return;
        }
        int n = Math.min(count, xyz.length / 3);
        if (dash <= 0f) {
            for (int i = 1; i < n; i++) {
                int a = (i - 1) * 3;
                int b = i * 3;
                add(xyz[a], xyz[a + 1], xyz[a + 2], xyz[b], xyz[b + 1], xyz[b + 2], c);
            }
            return;
        }
        float period = dash * 2f;
        float offset = mod(dashClock * DASH_SPEED, period);
        float s = 0f; // arc length at the start of the current segment
        for (int i = 1; i < n; i++) {
            int a = (i - 1) * 3;
            int b = i * 3;
            float ax = xyz[a];
            float ay = xyz[a + 1];
            float az = xyz[a + 2];
            float dx = xyz[b] - ax;
            float dy = xyz[b + 1] - ay;
            float dz = xyz[b + 2] - az;
            float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (len < 1.0e-5f) {
                continue;
            }
            float t = 0f;
            int guard = 0;
            while (t < len && guard++ < 256) {
                // the pattern moves toward the end: position s is lit when (s - offset) mod period < dash
                float q = mod(s + t - offset, period);
                if (q > period - DASH_EPS) {
                    q = 0f; // rounding just short of the next dash: it starts here
                }
                boolean lit = q < dash - DASH_EPS; // a sliver left of a dash is rounding, not a dash
                float step = Math.min(lit ? dash - q : period - q, len - t);
                if (step < 1.0e-5f) {
                    step = 1.0e-5f;
                }
                if (lit) {
                    float f0 = t / len;
                    float f1 = Math.min(1f, (t + step) / len);
                    add(ax + dx * f0, ay + dy * f0, az + dz * f0, ax + dx * f1, ay + dy * f1, az + dz * f1, c);
                }
                t += step;
            }
            s += len;
        }
    }

    static float mod(float v, float m) {
        float r = v % m;
        return r < 0f ? r + m : r;
    }

    @Override
    public void ring(float cx, float cy, float cz, float radius, HoloToken token, float alpha) {
        int c = argb(token, alpha);
        if (!open || (c >>> 24) == 0 || radius <= 0f) {
            return;
        }
        for (int i = 0; i < RING_SEGMENTS; i++) {
            add(cx + RING_COS[i] * radius, cy, cz + RING_SIN[i] * radius,
                    cx + RING_COS[i + 1] * radius, cy, cz + RING_SIN[i + 1] * radius, c);
        }
    }

    @Override
    public void boxEdges(float minX, float minY, float minZ, float maxX, float maxY, float maxZ, HoloToken token,
                         float alpha) {
        int c = argb(token, alpha);
        if (!open || (c >>> 24) == 0) {
            return;
        }
        // bottom
        add(minX, minY, minZ, maxX, minY, minZ, c);
        add(maxX, minY, minZ, maxX, minY, maxZ, c);
        add(maxX, minY, maxZ, minX, minY, maxZ, c);
        add(minX, minY, maxZ, minX, minY, minZ, c);
        // top
        add(minX, maxY, minZ, maxX, maxY, minZ, c);
        add(maxX, maxY, minZ, maxX, maxY, maxZ, c);
        add(maxX, maxY, maxZ, minX, maxY, maxZ, c);
        add(minX, maxY, maxZ, minX, maxY, minZ, c);
        // verticals
        add(minX, minY, minZ, minX, maxY, minZ, c);
        add(maxX, minY, minZ, maxX, maxY, minZ, c);
        add(maxX, minY, maxZ, maxX, maxY, maxZ, c);
        add(minX, minY, maxZ, minX, maxY, maxZ, c);
    }

    @Override
    public void topBrackets(float minX, float minZ, float maxX, float maxZ, float y, float arm, HoloToken token,
                            float alpha) {
        int c = argb(token, alpha);
        if (!open || (c >>> 24) == 0) {
            return;
        }
        brackets(minX, minZ, maxX, maxZ, y, arm, c);
    }

    private void brackets(float minX, float minZ, float maxX, float maxZ, float y, float arm, int c) {
        float ax = Math.min(arm, (maxX - minX) * 0.5f);
        float az = Math.min(arm, (maxZ - minZ) * 0.5f);
        add(minX, y, minZ, minX + ax, y, minZ, c);
        add(minX, y, minZ, minX, y, minZ + az, c);
        add(maxX, y, minZ, maxX - ax, y, minZ, c);
        add(maxX, y, minZ, maxX, y, minZ + az, c);
        add(minX, y, maxZ, minX + ax, y, maxZ, c);
        add(minX, y, maxZ, minX, y, maxZ - az, c);
        add(maxX, y, maxZ, maxX - ax, y, maxZ, c);
        add(maxX, y, maxZ, maxX, y, maxZ - az, c);
    }

    @Override
    public void groundMark(float x, float y, float z, float size, HoloToken token, float alpha) {
        int c = argb(token, alpha);
        if (!open || (c >>> 24) == 0 || size <= 0f) {
            return;
        }
        float h = size * 0.5f;
        float yy = y + GROUND_LIFT;
        brackets(x - h, z - h, x + h, z + h, yy, size * 0.3f, c);
        // a small centre cross
        float k = size * 0.08f;
        add(x - k, yy, z, x + k, yy, z, c);
        add(x, yy, z - k, x, yy, z + k, c);
    }

    // ---- the level-render pass --------------------------------------------------------------------------------------

    /**
     * Drives the kit from the level render (26.3: {@link SubmitCustomGeometryEvent}, fired once per frame after
     * particles are submitted): {@link #onBegin} (highest priority) opens the pass and publishes the kit through
     * {@link Visuals#useWorld}; adopters draw at normal priority in their own {@code SubmitCustomGeometryEvent}
     * listener through {@code Services.visuals().world()}; {@link #onEnd} (lowest priority) withdraws the kit and submits
     * one custom-geometry node that flushes the batch when the game draws its submits. Nothing is submitted when nobody
     * drew, in Clean-Screen, or without a level. Registered by {@link MarksBootstrap}.
     */
    public static final class Pass {

        /** The event the pass runs in; adopters draw in a listener of the same event. */
        public static final Class<SubmitCustomGeometryEvent> EVENT = SubmitCustomGeometryEvent.class;

        private static final HoloWorldKit KIT = new HoloWorldKit();
        private static final BufferSink SINK = new BufferSink();
        /** The deferred draw: one instance, so a frame's submit allocates nothing of ours. */
        private static final SubmitNodeCollector.CustomGeometryRenderer DRAW = (pose, consumer) -> {
            SINK.bind(consumer, pose);
            KIT.flush(SINK);
            SINK.unbind();
        };

        private Pass() {
        }

        public static HoloWorldKit kit() {
            return KIT;
        }

        public static void onBegin(SubmitCustomGeometryEvent event) {
            Minecraft mc = Minecraft.getInstance();
            if (mc.level == null || Services.modState().isCleanScreen()) {
                Visuals.useWorld(null);
                return;
            }
            float heightPx = Math.max(1, mc.getWindow().getHeight());
            float m11 = event.getLevelRenderState().cameraRenderState.projectionMatrix.m11();
            float worldPerPx = m11 > 1.0e-4f ? 2f / (m11 * heightPx) : 0.001f;
            float core = CORE_GUI_PX * (float) Math.max(1.0, mc.getWindow().getGuiScale());
            boolean plain = HoloTheme.effects == EffectLevel.OFF || ShaderPacks.active();
            float dash = HoloTheme.motion == Motion.FULL ? (FrameClock.nowMs() % 3_600_000L) / 1000f : 0f;
            SINK.lineWidth = Math.max(1f, mc.gameRenderer.gameRenderState().windowRenderState.appropriateLineWidth);
            KIT.begin(plain, HoloTheme.effects == EffectLevel.FULL, worldPerPx, core, dash);
            Visuals.useWorld(KIT);
        }

        public static void onEnd(SubmitCustomGeometryEvent event) {
            Visuals.useWorld(null);
            if (!KIT.isOpen()) {
                return;
            }
            if (KIT.count() == 0) {
                KIT.cancel(); // idle: no submit, no batch
                return;
            }
            KIT.close(); // collection ends here; the segments wait for the deferred draw
            RenderType type = KIT.plain() ? RenderTypes.lines() : HoloRenderTypes.world();
            event.getSubmitNodeCollector().submitCustomGeometry(event.getPoseStack(), type, DRAW);
        }
    }

    /** Writes the kit's output into a vertex consumer with the stage's pose. One instance, rebound per frame. */
    static final class BufferSink implements Sink {
        private VertexConsumer vc;
        private PoseStack.Pose pose;
        private Matrix4f matrix;
        /** Width of plain lines (26.3 lines carry a per-vertex width), set per frame by the pass. */
        float lineWidth = 1f;

        void bind(VertexConsumer consumer, PoseStack.Pose p) {
            vc = consumer;
            pose = p;
            matrix = p.pose();
        }

        void unbind() {
            vc = null;
            pose = null;
            matrix = null;
        }

        @Override
        public void vertex(float x, float y, float z, float u, float v, int argb) {
            vc.addVertex(matrix, x, y, z).setUv(u, v).setColor(argb);
        }

        @Override
        public void line(float x0, float y0, float z0, float x1, float y1, float z1, int argb) {
            float nx = x1 - x0;
            float ny = y1 - y0;
            float nz = z1 - z0;
            float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
            if (len < 1.0e-5f) {
                return;
            }
            nx /= len;
            ny /= len;
            nz /= len;
            vc.addVertex(pose, x0, y0, z0).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
            vc.addVertex(pose, x1, y1, z1).setColor(argb).setNormal(pose, nx, ny, nz).setLineWidth(lineWidth);
        }
    }

    /**
     * Whether an Iris/Oculus shader pack is in use, through the Iris API by reflection (no dependency): a
     * {@link MethodHandle} bound once, so the per-frame check allocates nothing. False when Iris is absent.
     */
    static final class ShaderPacks {
        private static MethodHandle inUse;
        private static boolean looked;

        private ShaderPacks() {
        }

        static boolean active() {
            if (!looked) {
                looked = true;
                try {
                    Class<?> api = Class.forName("net.irisshaders.iris.api.v0.IrisApi");
                    Object instance = api.getMethod("getInstance").invoke(null);
                    inUse = MethodHandles.publicLookup()
                            .findVirtual(api, "isShaderPackInUse", MethodType.methodType(boolean.class))
                            .bindTo(instance);
                } catch (Throwable t) {
                    inUse = null;
                }
            }
            MethodHandle h = inUse;
            if (h == null) {
                return false;
            }
            try {
                return (boolean) h.invokeExact();
            } catch (Throwable t) {
                inUse = null;
                return false;
            }
        }
    }
}
