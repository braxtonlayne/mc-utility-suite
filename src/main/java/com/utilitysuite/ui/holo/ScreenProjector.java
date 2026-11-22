package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.visual.IWorldMarks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;

/**
 * Projects every occupied {@link IWorldMarks} slot to the screen once per rendered frame, so the reticle layer (GUI)
 * only reads primitive arrays. Runs in {@link RenderLevelStageEvent.Stage#AFTER_LEVEL}: in 1.21.1 that stage gets
 * the camera-rotation matrix as the model-view matrix and the projection matrix with view bobbing, hurt shake and the
 * nausea warp already multiplied in (see {@code GameRenderer.renderLevel}), and world coordinates relative to the
 * camera position. Both are copied into reused {@link Matrix4f}s.
 * <p>
 * Results are normalised (0..1 of the screen, y down) so the layer can scale them to whatever canvas size the HUD
 * host uses. Per slot: the screen rectangle of the eight box corners, a state (on screen, off screen, behind the
 * camera), the bearing of the mark around the crosshair (degrees, 0 = up, clockwise) and its distance from the
 * camera. The pure math is {@link #projectBox}; the instance only feeds it. Render thread only, no allocation.
 */
public final class ScreenProjector {

    /** Floats per slot in {@link #rects()}: minX, minY, maxX, maxY, bearingDeg, distance. */
    public static final int STRIDE = 6;
    public static final int MIN_X = 0;
    public static final int MIN_Y = 1;
    public static final int MAX_X = 2;
    public static final int MAX_Y = 3;
    public static final int BEARING = 4;
    public static final int DISTANCE = 5;

    /** Not projected this frame (empty slot, unknown entity). */
    public static final int NONE = 0;
    /** At least part of the box is inside the view. */
    public static final int ON_SCREEN = 1;
    /** In front of the camera but outside the view: draw an edge chevron along {@link #BEARING}. */
    public static final int OFF_SCREEN = 2;
    /** Every corner is behind the camera: also an edge chevron. */
    public static final int BEHIND = 3;

    /** Clip-space w below which a corner counts as behind the near plane. */
    static final float W_EPS = 1.0e-4f;

    private final Matrix4f view = new Matrix4f();
    private final Matrix4f projection = new Matrix4f();
    private final Matrix4f mvp = new Matrix4f();
    private final float[] rects = new float[IWorldMarks.SLOTS * STRIDE];
    private final byte[] states = new byte[IWorldMarks.SLOTS];
    private final int[] generations = new int[IWorldMarks.SLOTS];
    private final double[] bounds = new double[6];
    private LevelBounds levelBounds;
    private IWorldMarks marks;

    /** World-space bounds of an entity by network id, interpolated for this frame. */
    public interface EntityBounds {
        /** Writes minX, minY, minZ, maxX, maxY, maxZ into {@code out}; false when the entity is unknown. */
        boolean bounds(int entityId, double[] out);
    }

    /** Uses this table instead of {@code Services.visuals().marks()} (set once by the bootstrap; tests). */
    public void bind(IWorldMarks table) {
        marks = table;
    }

    // ---- event entry ------------------------------------------------------------------------------------------------

    public void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_LEVEL) {
            return;
        }
        IWorldMarks m = marks;
        if (m == null || !anyOccupied(m)) {
            clearStates();
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            clearStates();
            return;
        }
        LevelBounds lb = levelBounds;
        if (lb == null) {
            lb = levelBounds = new LevelBounds(); // once per session
        }
        lb.level = mc.level;
        lb.partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        Vec3 cam = event.getCamera().getPosition();
        project(m, event.getModelViewMatrix(), event.getProjectionMatrix(), cam.x, cam.y, cam.z, lb);
        lb.level = null;
    }

    // ---- per frame --------------------------------------------------------------------------------------------------

    /** Projects every occupied slot of {@code table}. Camera at {@code camX..camZ} in world coordinates. */
    public void project(IWorldMarks table, Matrix4fc modelView, Matrix4fc proj, double camX, double camY, double camZ,
                        EntityBounds entities) {
        view.set(modelView);
        projection.set(proj);
        projection.mul(view, mvp);
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            if (!table.occupied(s)) {
                states[s] = NONE;
                continue;
            }
            int id = table.entityId(s);
            double[] b = bounds;
            if (id >= 0) {
                if (entities == null || !entities.bounds(id, b)) {
                    states[s] = NONE;
                    continue;
                }
            } else {
                for (int i = 0; i < 6; i++) {
                    b[i] = table.box(s, i);
                }
            }
            states[s] = (byte) projectBox(mvp, view, (float) (b[0] - camX), (float) (b[1] - camY),
                    (float) (b[2] - camZ), (float) (b[3] - camX), (float) (b[4] - camY), (float) (b[5] - camZ),
                    rects, s * STRIDE);
            generations[s] = table.generation(s);
        }
    }

    /** Cheap early-out: is anything published at all. */
    public static boolean anyOccupied(IWorldMarks table) {
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            if (table.occupied(s)) {
                return true;
            }
        }
        return false;
    }

    public void clearStates() {
        for (int s = 0; s < IWorldMarks.SLOTS; s++) {
            states[s] = NONE;
        }
    }

    /** {@link #NONE} unless the slot was projected this frame for the mark it holds now. */
    public int state(int slot, int generation) {
        return generations[slot] == generation ? states[slot] : NONE;
    }

    public float value(int slot, int field) {
        return rects[slot * STRIDE + field];
    }

    /** The raw result table (tests). */
    public float[] rects() {
        return rects;
    }

    // ---- pure math --------------------------------------------------------------------------------------------------

    /**
     * Projects a camera-relative axis-aligned box. Writes {@link #STRIDE} floats at {@code o}: the normalised screen
     * rectangle of the corners in front of the camera (clamped to the screen when the box straddles the near plane),
     * the bearing of the box centre around the screen centre and the camera distance of the centre.
     *
     * @param mvp  projection x view
     * @param view the view (camera rotation) matrix alone, for the bearing
     * @return {@link #ON_SCREEN}, {@link #OFF_SCREEN} or {@link #BEHIND}
     */
    public static int projectBox(Matrix4fc mvp, Matrix4fc view, float x0, float y0, float z0, float x1, float y1,
                                 float z1, float[] out, int o) {
        float minX = Float.POSITIVE_INFINITY;
        float minY = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY;
        float maxY = Float.NEGATIVE_INFINITY;
        int front = 0;
        for (int i = 0; i < 8; i++) {
            float x = (i & 1) == 0 ? x0 : x1;
            float y = (i & 2) == 0 ? y0 : y1;
            float z = (i & 4) == 0 ? z0 : z1;
            float w = mvp.m03() * x + mvp.m13() * y + mvp.m23() * z + mvp.m33();
            if (w <= W_EPS) {
                continue;
            }
            float cx = mvp.m00() * x + mvp.m10() * y + mvp.m20() * z + mvp.m30();
            float cy = mvp.m01() * x + mvp.m11() * y + mvp.m21() * z + mvp.m31();
            float sx = cx / w * 0.5f + 0.5f;
            float sy = 0.5f - cy / w * 0.5f;
            if (sx < minX) {
                minX = sx;
            }
            if (sx > maxX) {
                maxX = sx;
            }
            if (sy < minY) {
                minY = sy;
            }
            if (sy > maxY) {
                maxY = sy;
            }
            front++;
        }
        float mx = (x0 + x1) * 0.5f;
        float my = (y0 + y1) * 0.5f;
        float mz = (z0 + z1) * 0.5f;
        out[o + DISTANCE] = (float) Math.sqrt(mx * mx + my * my + mz * mz);
        float vx = view.m00() * mx + view.m10() * my + view.m20() * mz + view.m30();
        float vy = view.m01() * mx + view.m11() * my + view.m21() * mz + view.m31();
        out[o + BEARING] = (float) Math.toDegrees(Math.atan2(vx, vy));
        if (front == 0) {
            out[o + MIN_X] = 0.5f;
            out[o + MIN_Y] = 0.5f;
            out[o + MAX_X] = 0.5f;
            out[o + MAX_Y] = 0.5f;
            return BEHIND;
        }
        if (front < 8) {
            // Straddles the near plane (the target is right on the camera): keep what is in front, clamped.
            minX = Math.max(0f, minX);
            minY = Math.max(0f, minY);
            maxX = Math.min(1f, maxX);
            maxY = Math.min(1f, maxY);
        }
        out[o + MIN_X] = minX;
        out[o + MIN_Y] = minY;
        out[o + MAX_X] = maxX;
        out[o + MAX_Y] = maxY;
        boolean inside = maxX >= 0f && minX <= 1f && maxY >= 0f && minY <= 1f && minX <= maxX && minY <= maxY;
        return inside ? ON_SCREEN : OFF_SCREEN;
    }

    /**
     * Grows the pixel rectangle {@code r[o..o+3]} (minX, minY, maxX, maxY) around its centre so each side is at least
     * {@code min} pixels.
     */
    public static void expandToMin(float[] r, int o, float min) {
        float w = r[o + 2] - r[o];
        if (w < min) {
            float c = (r[o] + r[o + 2]) * 0.5f;
            r[o] = c - min * 0.5f;
            r[o + 2] = c + min * 0.5f;
        }
        float h = r[o + 3] - r[o + 1];
        if (h < min) {
            float c = (r[o + 1] + r[o + 3]) * 0.5f;
            r[o + 1] = c - min * 0.5f;
            r[o + 3] = c + min * 0.5f;
        }
    }

    /** Entity bounds from the client level, interpolated by the partial tick the entity renderer uses. */
    private static final class LevelBounds implements EntityBounds {
        ClientLevel level;
        float partialTick;

        @Override
        public boolean bounds(int entityId, double[] out) {
            ClientLevel l = level;
            Entity e = l == null ? null : l.getEntity(entityId);
            if (e == null || e.isRemoved()) {
                return false;
            }
            double x = Mth.lerp(partialTick, e.xo, e.getX());
            double y = Mth.lerp(partialTick, e.yo, e.getY());
            double z = Mth.lerp(partialTick, e.zo, e.getZ());
            double hw = e.getBbWidth() * 0.5;
            out[0] = x - hw;
            out[1] = y;
            out[2] = z - hw;
            out[3] = x + hw;
            out[4] = y + e.getBbHeight();
            out[5] = z + hw;
            return true;
        }
    }
}
