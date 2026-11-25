package com.utilitysuite.world.replay;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.LayeredDraw;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Picture-in-picture camera (F1): a HUD corner panel showing the scene from a second camera pose.
 * <ul>
 *   <li><b>Rear view</b>: from your eye looking backwards (optionally mirrored like a real mirror).</li>
 *   <li><b>Drone</b>: a chase drone above and behind you until the park key pins the current camera pose; the panel
 *       then keeps showing the scene from there.</li>
 * </ul>
 * <b>What is shipped:</b> a stylised wireframe view (entity boxes coloured by kind, velocity ticks, a ground grid)
 * projected with {@link ViewProjector} from the second pose and refreshed every {@code refresh_every} frames into a
 * preallocated segment cache, then drawn into the panel each frame. <b>Not shipped (TODO):</b> a true second world
 * render into an offscreen {@code TextureTarget}. In 1.21.1 that needs {@code LevelRenderer.renderLevel} re-entered
 * with a swapped {@code Camera} and frustum mid-frame; vanilla's chunk-visibility state, entity render state and
 * Sodium's render-section graph are per-frame singletons, so a second pass corrupts culling (Sodium) or rebuilds
 * section visibility twice per frame. Doing it safely needs a mixin per renderer and an in-game Sodium test, which were
 * out of budget. The panel, pose maths, refresh gate and settings are the infrastructure that pass would reuse.
 * Off by default; nothing is drawn when off, in Clean-Screen, with F1 or with a screen open.
 */
public final class PipCameraModule extends WorldModule implements LayeredDraw.Layer {

    public static final String ID = "pip_camera";

    public enum Mode { REAR_VIEW, DRONE }

    public enum Corner { TOP_RIGHT, TOP_LEFT, BOTTOM_RIGHT, BOTTOM_LEFT }

    private static final int MAX_SEGS = 64 * 13 + 40;

    private final ViewProjector view = new ViewProjector();
    private final FrameGate gate = new FrameGate();
    private final GuiLines lines = new GuiLines();
    private final float[] segs = new float[MAX_SEGS * 4];
    private final int[] segColor = new int[MAX_SEGS];
    private int segCount;

    private KeyMapping parkKey;
    private Mode mode = Mode.REAR_VIEW;
    private Corner corner = Corner.TOP_RIGHT;
    private int refreshEvery = 3;
    private int panelWidth = 160;
    private double range = 24;
    private boolean mirror = true;

    private boolean parked;
    private double parkX;
    private double parkY;
    private double parkZ;
    private float parkYaw;
    private float parkPitch;

    public PipCameraModule() {
        super(ID, "Picture-in-Picture", false);
    }

    public void setParkKey(KeyMapping key) {
        parkKey = key;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("mode", "View", Mode.REAR_VIEW, "Camera",
                        "Rear view looks back from your eye; Drone chases you until the park key pins it."),
                SettingSpec.bool("mirror", "Mirror rear view", true, "Camera",
                        "Flip the rear view left-right, like a real mirror."),
                SettingSpec.decimal("range", "Range", 24.0, 8.0, 64.0, "Camera",
                        "Entities within this many blocks are drawn."),
                SettingSpec.integer("refresh_every", "Refresh every N frames", 3, 1, 10, "Performance",
                        "The view is recomputed once every N frames (drawn every frame from the cache)."),
                SettingSpec.integer("panel_width", "Panel width", 160, 80, 320, "Panel", "In GUI pixels (16:9)."),
                SettingSpec.choice("corner", "Corner", Corner.TOP_RIGHT, "Panel", "Screen corner of the panel."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        mode = choice(p, "mode", Mode.class);
        mirror = bool(p, "mirror");
        range = decimal(p, "range");
        refreshEvery = integer(p, "refresh_every");
        gate.setEvery(refreshEvery);
        panelWidth = integer(p, "panel_width");
        corner = choice(p, "corner", Corner.class);
    }

    @Override
    protected void onDisable() {
        parked = false;
        segCount = 0;
    }

    @Override
    public void onWorldUnload() {
        parked = false;
        segCount = 0;
    }

    @Override
    public void onTick() {
        while (parkKey != null && parkKey.consumeClick()) {
            if (parked) {
                parked = false; // second press: back to the chase drone
                continue;
            }
            Camera cam = Minecraft.getInstance().gameRenderer.getMainCamera();
            Vec3 pos = cam.getPosition(); // the camera's own field
            parkX = pos.x;
            parkY = pos.y;
            parkZ = pos.z;
            parkYaw = cam.getYRot();
            parkPitch = cam.getXRot();
            parked = true;
        }
    }

    @Override
    public void render(GuiGraphics g, DeltaTracker delta) {
        if (!isEnabled() || cleanScreen()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel level = mc.level;
        if (p == null || level == null || mc.options.hideGui || mc.screen != null
                || mc.getDebugOverlay().showDebugScreen()) {
            return;
        }
        int w = panelWidth;
        int h = w * 9 / 16;
        int x = corner == Corner.TOP_LEFT || corner == Corner.BOTTOM_LEFT ? 6 : g.guiWidth() - w - 6;
        int y = corner == Corner.TOP_LEFT || corner == Corner.TOP_RIGHT ? 6 : g.guiHeight() - h - 6;
        if (gate.tick()) {
            refresh(p, level, delta.getGameTimeDeltaPartialTick(false), w / (double) h);
        }
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xC0303844);
        g.fill(x, y, x + w, y + h, 0xB0080A10);
        boolean flip = mode == Mode.REAR_VIEW && mirror;
        lines.begin(g.pose().last().pose(), x, y, w, h);
        for (int s = 0; s < segCount; s++) {
            int k = s * 4;
            float ax = flip ? -segs[k] : segs[k];
            float bx = flip ? -segs[k + 2] : segs[k + 2];
            lines.segment(ax, segs[k + 1], bx, segs[k + 3], 1f, segColor[s]);
        }
        lines.end();
        g.drawString(mc.font, mode == Mode.REAR_VIEW ? "REAR" : parked ? "DRONE (parked)" : "DRONE", x + 3, y + 3,
                0xFF8090A0, false);
    }

    /** Recomputes the projected wireframe from the second pose. Allocation free apart from the level iterator. */
    private void refresh(LocalPlayer p, ClientLevel level, float pt, double aspect) {
        double px = p.xOld + (p.getX() - p.xOld) * pt;
        double py = p.yOld + (p.getY() - p.yOld) * pt;
        double pz = p.zOld + (p.getZ() - p.zOld) * pt;
        float yaw = p.getViewYRot(pt);
        if (mode == Mode.REAR_VIEW) {
            view.setRearView(px, py + p.getEyeHeight(), pz, yaw, 80, aspect);
        } else if (parked) {
            view.set(parkX, parkY, parkZ, parkYaw, parkPitch, 70, aspect);
        } else {
            double ya = Math.toRadians(yaw);
            double back = 7.0;
            view.set(px + Math.sin(ya) * back, py + 6.0, pz - Math.cos(ya) * back, yaw, 38f, 70, aspect);
        }
        int n = 0;
        int count = 0;
        double gy = Math.floor(py);
        int cx = (int) Math.floor(px);
        int cz = (int) Math.floor(pz);
        for (int k = -6; k <= 6; k += 2) {
            int before = n;
            n = view.projectSegment(cx + k, gy, cz - 6, cx + k, gy, cz + 6, segs, n);
            if (n > before) {
                segColor[count++] = 0x40FFFFFF;
            }
            before = n;
            n = view.projectSegment(cx - 6, gy, cz + k, cx + 6, gy, cz + k, segs, n);
            if (n > before) {
                segColor[count++] = 0x40FFFFFF;
            }
        }
        double r2 = range * range;
        boolean includeSelf = mode == Mode.DRONE;
        for (Entity e : level.entitiesForRendering()) {
            if (n + 13 * 4 > segs.length) {
                break;
            }
            if ((e == p && !includeSelf) || e.isRemoved() || e.distanceToSqr(px, py, pz) > r2) {
                continue;
            }
            int color = e == p ? 0xFF50E0FF : e instanceof Enemy ? 0xFFFF5050 : e instanceof Player ? 0xFFFFA040
                    : e instanceof LivingEntity ? 0xFFB0B0B0 : 0x80909090;
            double ex = e.xOld + (e.getX() - e.xOld) * pt;
            double ey = e.yOld + (e.getY() - e.yOld) * pt;
            double ez = e.zOld + (e.getZ() - e.zOld) * pt;
            double hw = e.getBbWidth() * 0.5;
            int before = n;
            n = view.projectBox(ex - hw, ey, ez - hw, ex + hw, ey + e.getBbHeight(), ez + hw, segs, n);
            for (int k = before; k < n; k += 4) {
                segColor[count++] = color;
            }
            Vec3 v = e.getDeltaMovement();
            before = n;
            double mid = ey + e.getBbHeight() * 0.5;
            n = view.projectSegment(ex, mid, ez, ex + v.x * 10, mid + v.y * 10, ez + v.z * 10, segs, n);
            if (n > before) {
                segColor[count++] = 0xFF60FF60;
            }
        }
        segCount = count;
    }
}
