package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.ManualInput;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.event.InputEvent;
import org.lwjgl.glfw.GLFW;

/**
 * Detects the user's real (hardware) input and reports it to {@link IActionArbiter#onManualInput}.
 * <p>
 * Sources are hardware events only: {@link InputEvent.Key}, {@link InputEvent.MouseButton.Pre},
 * {@link InputEvent.MouseScrollingEvent} and the raw mouse delta from {@code MouseHandlerManualLookMixin}. Automation
 * changes key states through {@code KeyMapping.setDown}/{@code click}, which never fires these events, so simulated
 * presses cannot trigger an override. Input is ignored while a screen (chat, inventory, menu) is open.
 * <p>
 * Mouse look: raw cursor movement is summed per tick; above the threshold (pixels per tick, default 6) a
 * {@link ManualInput#MOUSE_LOOK} is reported once per tick. While a SILENT rotation is active the rendered camera
 * belongs to the user by design, so mouse movement is not an override then.
 */
public final class ManualInputHooks {

    private static ManualInputHooks instance;

    private final IActionArbiter arbiter;
    private final InputSmoother smoother;
    private double mouseThresholdPx = 6.0;
    private double mouseAccum;
    private boolean mouseReported;
    private ManualLookAssist look;
    /** Seconds since the previous mouse frame, recorded at the HEAD of {@code turnPlayer}. */
    private double frameSeconds;

    public ManualInputHooks(IActionArbiter arbiter, InputSmoother smoother) {
        this.arbiter = arbiter;
        this.smoother = smoother;
        instance = this;
    }

    public void setMouseThresholdPx(double px) {
        this.mouseThresholdPx = Math.max(0.5, px);
    }

    /** Called from the mixin with the raw accumulated cursor delta of this frame. */
    public static void onMouseTurn(double dx, double dy) {
        ManualInputHooks h = instance;
        if (h != null) {
            h.mouseDelta(dx, dy);
        }
    }

    private void mouseDelta(double dx, double dy) {
        smoother.noteManualMouse(dx, dy); // released momentum never pushes against the user's own motion
        if (mouseReported) {
            return;
        }
        mouseAccum += Math.abs(dx) + Math.abs(dy);
        if (mouseAccum > mouseThresholdPx && !smoother.isSilentRotationActive()) {
            mouseReported = true;
            // ending the camera lease inside this bracket hands the running turn over with decaying momentum
            smoother.setManualLookPreempting(true);
            try {
                arbiter.onManualInput(ManualInput.MOUSE_LOOK);
            } finally {
                smoother.setManualLookPreempting(false);
            }
        }
    }

    /** ClientTickEvent.Pre: starts a new mouse accumulation window (and the turn gain's tick: read-outs, ttl). */
    public void beginTick() {
        mouseAccum = 0.0;
        mouseReported = false;
        ManualLookAssist a = look;
        if (a != null) {
            a.beginTick();
        }
    }

    // ---- turn gain (MouseHandlerTurnGainMixin) ----------------------------------------------------------------------

    /** The turn gain the mouse hook feeds (null: the hook passes every delta through untouched). */
    public void setManualLookAssist(ManualLookAssist assist) {
        this.look = assist;
    }

    /** HEAD of {@code MouseHandler.turnPlayer}: the frame time vanilla passes in (seconds, from Blaze3D.getTime). */
    public static void onTurnFrame(double movementTime) {
        ManualInputHooks h = instance;
        if (h != null) {
            h.frameSeconds = movementTime;
        }
    }

    /**
     * The yaw argument of {@code LocalPlayer.turn} inside {@code turnPlayer}, in turn units (1 unit = 0.15 deg).
     * Returns it unchanged (the same double) unless a live turn-gain request scales it. Pitch never comes here.
     * Runs every rendered frame the mouse is grabbed: allocation free, early-out when nothing is live.
     */
    public static double onTurnYaw(double yawArg) {
        ManualInputHooks h = instance;
        if (h == null || yawArg == 0.0) {
            return yawArg;
        }
        ManualLookAssist a = h.look;
        return a == null ? yawArg : h.turnYaw(a, yawArg);
    }

    private double turnYaw(ManualLookAssist a, double yawArg) {
        double raw = yawArg * ManualLookAssist.DEG_PER_TURN_UNIT;
        if (!a.hasLiveRequest()) {
            a.onFrameYaw(0f, raw, frameSeconds, true); // read-outs only
            return yawArg;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (p == null) {
            return yawArg;
        }
        boolean blocked = mc.screen != null || p.isSpectator() || p.isPassenger();
        double out = a.onFrameYaw(p.getYRot(), raw, frameSeconds, blocked);
        return out == raw ? yawArg : out / ManualLookAssist.DEG_PER_TURN_UNIT;
    }

    private static boolean inGame(Minecraft mc) {
        return mc.player != null && mc.screen == null;
    }

    public void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!inGame(mc)) {
            return;
        }
        int key = event.getKey();
        int scan = event.getScanCode();
        Options o = mc.options;
        if (o.keyUp.matches(key, scan) || o.keyDown.matches(key, scan) || o.keyLeft.matches(key, scan)
                || o.keyRight.matches(key, scan) || o.keyJump.matches(key, scan) || o.keyShift.matches(key, scan)
                || o.keySprint.matches(key, scan)) {
            arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        } else if (o.keyAttack.matches(key, scan) || o.keyUse.matches(key, scan)) {
            arbiter.onManualInput(ManualInput.ATTACK_OR_USE);
        } else if (matchesAny(o.keyHotbarSlots, key, scan)) {
            arbiter.onManualInput(ManualInput.HOTBAR_SELECT);
        }
    }

    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        if (event.getAction() != GLFW.GLFW_PRESS) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (!inGame(mc)) {
            return;
        }
        int b = event.getButton();
        Options o = mc.options;
        if (o.keyAttack.matchesMouse(b) || o.keyUse.matchesMouse(b)) {
            arbiter.onManualInput(ManualInput.ATTACK_OR_USE);
        } else if (o.keyUp.matchesMouse(b) || o.keyDown.matchesMouse(b) || o.keyLeft.matchesMouse(b)
                || o.keyRight.matchesMouse(b) || o.keyJump.matchesMouse(b) || o.keyShift.matchesMouse(b)
                || o.keySprint.matchesMouse(b)) {
            arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        } else {
            for (KeyMapping m : o.keyHotbarSlots) {
                if (m.matchesMouse(b)) {
                    arbiter.onManualInput(ManualInput.HOTBAR_SELECT);
                    return;
                }
            }
        }
    }

    public void onScroll(InputEvent.MouseScrollingEvent event) {
        Minecraft mc = Minecraft.getInstance();
        if (inGame(mc) && event.getScrollDeltaY() != 0.0 && !mc.player.isSpectator()) {
            arbiter.onManualInput(ManualInput.HOTBAR_SELECT);
        }
    }

    private static boolean matchesAny(KeyMapping[] mappings, int key, int scan) {
        for (KeyMapping m : mappings) {
            if (m.matches(key, scan)) {
                return true;
            }
        }
        return false;
    }
}
