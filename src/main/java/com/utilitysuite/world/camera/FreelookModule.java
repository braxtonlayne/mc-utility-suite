package com.utilitysuite.world.camera;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.WorldModule;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.ViewportEvent;

import java.util.List;

/**
 * Freelook: while the hold key is down, mouse motion turns only the camera; the player's real yaw/pitch (what the
 * server sees, where movement goes) stays on its heading. Nothing here writes a rotation: the turn is absorbed by the
 * existing turn hook ({@code EntityTurnFreecamMixin} via {@code WorldHooks}) and the offset is applied to the view in
 * {@link ViewportEvent.ComputeCameraAngles}. On release the view eases back (speed-capped, no snap).
 * <p>
 * Limitation: in third person the camera position is computed from the player's rotation before the angles event, so
 * there the view turns in place rather than orbiting the player.
 */
public final class FreelookModule extends WorldModule {

    public static final String ID = "freelook";
    /** Entity.turn scales raw mouse deltas by 0.15 to get degrees. */
    private static final double TURN_SCALE = 0.15;

    private final FreelookOrbit orbit = new FreelookOrbit();
    private KeyMapping key;
    private boolean holding;
    private boolean invertPitch;
    private long lastNanos;

    public FreelookModule() {
        super(ID, "Freelook", false);
    }

    public void setKey(KeyMapping key) {
        this.key = key;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.bool("invert_pitch", "Invert vertical", false, "Controls",
                "Flip up/down while looking around."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        invertPitch = bool(p, "invert_pitch");
    }

    @Override
    protected void onDisable() {
        holding = false;
        // The offset keeps easing back in the angles event; nothing snaps.
    }

    @Override
    public void onStopAll() {
        holding = false;
    }

    @Override
    public void onWorldUnload() {
        holding = false;
        orbit.reset();
    }

    /**
     * Game bus, every client tick: the hold key works even while the module is off (it switches the module on, the
     * way the Scout Camera key does for freecam). Before this the key showed in Controls but did nothing until the
     * Freelook card was switched on (owner report, 2026-11-23). One field read and a key check: no allocation.
     */
    public void pollKey() {
        if (isEnabled() || key == null || !key.isDown()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen == null && mc.player != null) {
            toggleFromKey();
        }
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        holding = isEnabled() && key != null && mc.screen == null && mc.player != null && key.isDown();
    }

    /** Turn hook (client thread): true when the mouse turn was consumed by the camera. */
    public boolean turn(double yRot, double xRot) {
        if (!holding) {
            return false;
        }
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return false;
        }
        double dp = xRot * TURN_SCALE;
        orbit.addTurn(yRot * TURN_SCALE, invertPitch ? -dp : dp, p.getXRot());
        return true;
    }

    /** Game bus: applies the orbit to the view only. */
    public void onCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        long now = System.nanoTime();
        double dt = lastNanos == 0L ? 0.0 : Math.min(0.1, (now - lastNanos) / 1.0e9);
        lastNanos = now;
        if (!holding) {
            orbit.ease(dt);
        }
        if (!orbit.active()) {
            return;
        }
        event.setYaw((float) (event.getYaw() + orbit.yaw()));
        event.setPitch((float) orbit.cameraPitch(event.getPitch()));
    }
}
