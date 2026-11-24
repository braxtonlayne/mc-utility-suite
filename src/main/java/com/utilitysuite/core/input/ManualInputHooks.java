package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SimKey;
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
    private ManualFactsTracker facts;
    private YawCoSteer coSteer;
    /** Seconds since the previous mouse frame, recorded at the HEAD of {@code turnPlayer}. */
    private double frameSeconds;
    /** Sub-GCD remainders carried across frames when turn-gain or co-steer alters manual mouse deltas. */
    private double remTurnYaw;
    private double remTurnPitch;

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
        beginTick(false);
    }

    /**
     * ClientTickEvent.Pre: as {@link #beginTick()}, and advances the manual-facts tick counter; while a screen is open
     * every key counts as released (the game does not see them either).
     */
    public void beginTick(boolean screenOpen) {
        mouseAccum = 0.0;
        mouseReported = false;
        ManualLookAssist a = look;
        if (a != null) {
            a.beginTick();
        }
        ManualFactsTracker f = facts;
        if (f != null) {
            f.beginTick();
            if (screenOpen) {
                f.releaseAll();
            }
        }
        if (screenOpen) {
            remTurnYaw = 0.0;
            remTurnPitch = 0.0;
        }
    }

    // ---- shared control (SharedInputOverlay) ------------------------------------------------------------------------

    /** Facts about the user's own keys and mouse, fed from the hardware events below. */
    public void setManualFacts(ManualFactsTracker facts) {
        this.facts = facts;
    }

    /** The yaw co-steer the mouse hook adds per frame (null: none). */
    public void setYawCoSteer(YawCoSteer steer) {
        this.coSteer = steer;
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
     * Returns it unchanged (the same double) unless a live turn-gain request scales it or a live co-steer adds to it.
     * Pitch never comes here. Runs every rendered frame the mouse is grabbed and the window is focused, a still mouse
     * included (vanilla then calls {@code turn(0, 0)}): allocation free, early-out when nothing is live.
     * <p>
     * Order: the hand's raw yaw is recorded for {@link ManualFactsTracker} (before any gain), then the turn gain scales
     * the hand's motion, then the co-steer ADDS its own step on top (computed from the gained motion, so a hand that
     * already turns toward the ideal leaves it less to add). The co-steer's part is never scaled by the gain.
     */
    public static double onTurnYaw(double yawArg) {
        ManualInputHooks h = instance;
        if (h == null) {
            return yawArg;
        }
        YawCoSteer cs = h.coSteer;
        boolean steerLive = cs != null && cs.isLive();
        if (yawArg == 0.0 && !steerLive) {
            return yawArg;
        }
        return h.turnYaw(h.look, cs, steerLive, yawArg);
    }

    /**
     * The pitch argument of {@code LocalPlayer.turn} (turn units): the opt-in pitch gain on the hand's own motion, same
     * math as yaw. Unchanged (the same double) unless a pitch request is live and the mouse moved.
     */
    public static double onTurnPitch(double pitchArg) {
        ManualInputHooks h = instance;
        if (h == null || pitchArg == 0.0) {
            return pitchArg;
        }
        ManualLookAssist a = h.look;
        if (a == null || !a.hasLivePitchRequest()) {
            h.remTurnPitch = 0.0;
            return pitchArg;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (p == null) {
            h.remTurnPitch = 0.0;
            return pitchArg;
        }
        boolean blocked = mc.screen != null || p.isSpectator() || p.isPassenger();
        if (blocked) {
            h.remTurnPitch = 0.0;
        }
        double raw = pitchArg * ManualLookAssist.DEG_PER_TURN_UNIT;
        double out = a.onFramePitch(p.getXRot(), raw, h.frameSeconds, blocked);
        if (out == raw) {
            return pitchArg;
        }
        double step = RotationGcd.stepDeg(mc.options.sensitivity().get());
        double q = RotationGcd.quantizeDelta(out, h.remTurnPitch, step);
        h.remTurnPitch = RotationGcd.remainder(out, h.remTurnPitch, q);
        return q / ManualLookAssist.DEG_PER_TURN_UNIT;
    }

    private double turnYaw(ManualLookAssist a, YawCoSteer cs, boolean steerLive, double yawArg) {
        double raw = yawArg * ManualLookAssist.DEG_PER_TURN_UNIT;
        ManualFactsTracker f = facts;
        if (f != null && raw != 0.0) {
            f.addHandYaw(raw);
        }
        boolean gainLive = a != null && a.hasLiveRequest();
        if (!gainLive && !steerLive) {
            if (a != null) {
                a.onFrameYaw(0f, raw, frameSeconds, true); // read-outs only
            }
            return yawArg;
        }
        Minecraft mc = Minecraft.getInstance();
        net.minecraft.client.player.LocalPlayer p = mc.player;
        if (p == null) {
            return yawArg;
        }
        boolean blocked = mc.screen != null || p.isSpectator() || p.isPassenger();
        float yaw = p.getYRot();
        if (blocked) {
            remTurnYaw = 0.0;
        }
        double out = raw;
        if (a != null && raw != 0.0) {
            out = a.onFrameYaw(yaw, raw, frameSeconds, blocked);
        }
        if (steerLive) {
            out += cs.onFrame(yaw, raw, out, frameSeconds, blocked || smoother.isAutomatedLookRunning());
        }
        if (out == raw) {
            return yawArg;
        }
        double step = RotationGcd.stepDeg(mc.options.sensitivity().get());
        double q = RotationGcd.quantizeDelta(out, remTurnYaw, step);
        remTurnYaw = RotationGcd.remainder(out, remTurnYaw, q);
        return q / ManualLookAssist.DEG_PER_TURN_UNIT;
    }

    private static boolean inGame(Minecraft mc) {
        return mc.player != null && mc.screen == null;
    }

    public void onKey(InputEvent.Key event) {
        int action = event.getAction();
        if (action == GLFW.GLFW_REPEAT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ManualFactsTracker f = facts;
        if (action == GLFW.GLFW_RELEASE) {
            // releases always count, screen or not, so no key stays "held" in the facts
            if (f != null && mc.options != null) {
                releaseKeys(f, mc.options, event.getKey(), event.getScanCode());
            }
            return;
        }
        if (action != GLFW.GLFW_PRESS || !inGame(mc)) {
            return;
        }
        int key = event.getKey();
        int scan = event.getScanCode();
        Options o = mc.options;
        if (f != null) {
            pressKeys(f, o, key, scan);
        }
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
        int action = event.getAction();
        Minecraft mc = Minecraft.getInstance();
        ManualFactsTracker f = facts;
        if (action == GLFW.GLFW_RELEASE) {
            if (f != null && mc.options != null) {
                releaseButtons(f, mc.options, event.getButton());
            }
            return;
        }
        if (action != GLFW.GLFW_PRESS || !inGame(mc)) {
            return;
        }
        int b = event.getButton();
        Options o = mc.options;
        if (f != null) {
            pressButtons(f, o, b);
        }
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

    // ---- manual facts: hardware key -> SimKey (a key may be bound to several mappings: each one counts) --------------

    private static void pressKeys(ManualFactsTracker f, Options o, int key, int scan) {
        if (o.keyUp.matches(key, scan)) f.onPress(SimKey.FORWARD);
        if (o.keyDown.matches(key, scan)) f.onPress(SimKey.BACK);
        if (o.keyLeft.matches(key, scan)) f.onPress(SimKey.LEFT);
        if (o.keyRight.matches(key, scan)) f.onPress(SimKey.RIGHT);
        if (o.keyJump.matches(key, scan)) f.onPress(SimKey.JUMP);
        if (o.keyShift.matches(key, scan)) f.onPress(SimKey.SNEAK);
        if (o.keySprint.matches(key, scan)) f.onPress(SimKey.SPRINT);
        if (o.keyAttack.matches(key, scan)) f.onPress(SimKey.ATTACK);
        if (o.keyUse.matches(key, scan)) f.onPress(SimKey.USE);
    }

    private static void releaseKeys(ManualFactsTracker f, Options o, int key, int scan) {
        if (o.keyUp.matches(key, scan)) f.onRelease(SimKey.FORWARD);
        if (o.keyDown.matches(key, scan)) f.onRelease(SimKey.BACK);
        if (o.keyLeft.matches(key, scan)) f.onRelease(SimKey.LEFT);
        if (o.keyRight.matches(key, scan)) f.onRelease(SimKey.RIGHT);
        if (o.keyJump.matches(key, scan)) f.onRelease(SimKey.JUMP);
        if (o.keyShift.matches(key, scan)) f.onRelease(SimKey.SNEAK);
        if (o.keySprint.matches(key, scan)) f.onRelease(SimKey.SPRINT);
        if (o.keyAttack.matches(key, scan)) f.onRelease(SimKey.ATTACK);
        if (o.keyUse.matches(key, scan)) f.onRelease(SimKey.USE);
    }

    private static void pressButtons(ManualFactsTracker f, Options o, int b) {
        if (o.keyUp.matchesMouse(b)) f.onPress(SimKey.FORWARD);
        if (o.keyDown.matchesMouse(b)) f.onPress(SimKey.BACK);
        if (o.keyLeft.matchesMouse(b)) f.onPress(SimKey.LEFT);
        if (o.keyRight.matchesMouse(b)) f.onPress(SimKey.RIGHT);
        if (o.keyJump.matchesMouse(b)) f.onPress(SimKey.JUMP);
        if (o.keyShift.matchesMouse(b)) f.onPress(SimKey.SNEAK);
        if (o.keySprint.matchesMouse(b)) f.onPress(SimKey.SPRINT);
        if (o.keyAttack.matchesMouse(b)) f.onPress(SimKey.ATTACK);
        if (o.keyUse.matchesMouse(b)) f.onPress(SimKey.USE);
    }

    private static void releaseButtons(ManualFactsTracker f, Options o, int b) {
        if (o.keyUp.matchesMouse(b)) f.onRelease(SimKey.FORWARD);
        if (o.keyDown.matchesMouse(b)) f.onRelease(SimKey.BACK);
        if (o.keyLeft.matchesMouse(b)) f.onRelease(SimKey.LEFT);
        if (o.keyRight.matchesMouse(b)) f.onRelease(SimKey.RIGHT);
        if (o.keyJump.matchesMouse(b)) f.onRelease(SimKey.JUMP);
        if (o.keyShift.matchesMouse(b)) f.onRelease(SimKey.SNEAK);
        if (o.keySprint.matchesMouse(b)) f.onRelease(SimKey.SPRINT);
        if (o.keyAttack.matchesMouse(b)) f.onRelease(SimKey.ATTACK);
        if (o.keyUse.matchesMouse(b)) f.onRelease(SimKey.USE);
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
