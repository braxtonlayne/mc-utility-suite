package com.utilitysuite.core.input;

import com.utilitysuite.core.api.IActionArbiter;
import com.utilitysuite.core.api.ManualInput;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Options;
import net.neoforged.neoforge.client.event.InputEvent;
import com.mojang.blaze3d.platform.InputConstants;

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
        if (LookCapture.active()) {
            return LookCapture.yaw(yawArg); // action wheel open: the motion selects a slice, the camera stays
        }
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
        if (LookCapture.active()) {
            return LookCapture.pitch(pitchArg);
        }
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
        boolean blocked = mc.gui.screen() != null || p.isSpectator() || p.isPassenger();
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
        boolean blocked = mc.gui.screen() != null || p.isSpectator() || p.isPassenger();
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
        return mc.player != null && mc.gui.screen() == null;
    }

    public void onKey(InputEvent.Key event) {
        int action = event.getAction();
        if (action == InputConstants.REPEAT) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        ManualFactsTracker f = facts;
        if (action == InputConstants.RELEASE) {
            // releases always count, screen or not, so no key stays "held" in the facts
            if (f != null && mc.options != null) {
                releaseKeys(f, mc.options, event.getKey());
            }
            return;
        }
        if (action != InputConstants.PRESS || !inGame(mc)) {
            return;
        }
        int key = event.getKey(); // SDL scancode (26.3)
        Options o = mc.options;
        if (f != null) {
            pressKeys(f, o, key);
        }
        if (KeyInputs.matchesKey(o.keyUp, key) || KeyInputs.matchesKey(o.keyDown, key) || KeyInputs.matchesKey(o.keyLeft, key)
                || KeyInputs.matchesKey(o.keyRight, key) || KeyInputs.matchesKey(o.keyJump, key) || KeyInputs.matchesKey(o.keyShift, key)
                || KeyInputs.matchesKey(o.keySprint, key)) {
            arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        } else if (KeyInputs.matchesKey(o.keyAttack, key) || KeyInputs.matchesKey(o.keyUse, key)) {
            arbiter.onManualInput(ManualInput.ATTACK_OR_USE);
        } else if (matchesAny(o.keyHotbarSlots, key)) {
            arbiter.onManualInput(ManualInput.HOTBAR_SELECT);
        }
    }

    public void onMouseButton(InputEvent.MouseButton.Pre event) {
        int action = event.getAction();
        Minecraft mc = Minecraft.getInstance();
        ManualFactsTracker f = facts;
        if (action == InputConstants.RELEASE) {
            if (f != null && mc.options != null) {
                releaseButtons(f, mc.options, event.getButton());
            }
            return;
        }
        if (action != InputConstants.PRESS || !inGame(mc)) {
            return;
        }
        int b = event.getButton();
        Options o = mc.options;
        if (f != null) {
            pressButtons(f, o, b);
        }
        if (KeyInputs.matchesButton(o.keyAttack, b) || KeyInputs.matchesButton(o.keyUse, b)) {
            arbiter.onManualInput(ManualInput.ATTACK_OR_USE);
        } else if (KeyInputs.matchesButton(o.keyUp, b) || KeyInputs.matchesButton(o.keyDown, b) || KeyInputs.matchesButton(o.keyLeft, b)
                || KeyInputs.matchesButton(o.keyRight, b) || KeyInputs.matchesButton(o.keyJump, b) || KeyInputs.matchesButton(o.keyShift, b)
                || KeyInputs.matchesButton(o.keySprint, b)) {
            arbiter.onManualInput(ManualInput.MOVEMENT_KEY);
        } else {
            for (KeyMapping m : o.keyHotbarSlots) {
                if (KeyInputs.matchesButton(m, b)) {
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

    private static void pressKeys(ManualFactsTracker f, Options o, int key) {
        if (KeyInputs.matchesKey(o.keyUp, key)) f.onPress(SimKey.FORWARD);
        if (KeyInputs.matchesKey(o.keyDown, key)) f.onPress(SimKey.BACK);
        if (KeyInputs.matchesKey(o.keyLeft, key)) f.onPress(SimKey.LEFT);
        if (KeyInputs.matchesKey(o.keyRight, key)) f.onPress(SimKey.RIGHT);
        if (KeyInputs.matchesKey(o.keyJump, key)) f.onPress(SimKey.JUMP);
        if (KeyInputs.matchesKey(o.keyShift, key)) f.onPress(SimKey.SNEAK);
        if (KeyInputs.matchesKey(o.keySprint, key)) f.onPress(SimKey.SPRINT);
        if (KeyInputs.matchesKey(o.keyAttack, key)) f.onPress(SimKey.ATTACK);
        if (KeyInputs.matchesKey(o.keyUse, key)) f.onPress(SimKey.USE);
    }

    private static void releaseKeys(ManualFactsTracker f, Options o, int key) {
        if (KeyInputs.matchesKey(o.keyUp, key)) f.onRelease(SimKey.FORWARD);
        if (KeyInputs.matchesKey(o.keyDown, key)) f.onRelease(SimKey.BACK);
        if (KeyInputs.matchesKey(o.keyLeft, key)) f.onRelease(SimKey.LEFT);
        if (KeyInputs.matchesKey(o.keyRight, key)) f.onRelease(SimKey.RIGHT);
        if (KeyInputs.matchesKey(o.keyJump, key)) f.onRelease(SimKey.JUMP);
        if (KeyInputs.matchesKey(o.keyShift, key)) f.onRelease(SimKey.SNEAK);
        if (KeyInputs.matchesKey(o.keySprint, key)) f.onRelease(SimKey.SPRINT);
        if (KeyInputs.matchesKey(o.keyAttack, key)) f.onRelease(SimKey.ATTACK);
        if (KeyInputs.matchesKey(o.keyUse, key)) f.onRelease(SimKey.USE);
    }

    private static void pressButtons(ManualFactsTracker f, Options o, int b) {
        if (KeyInputs.matchesButton(o.keyUp, b)) f.onPress(SimKey.FORWARD);
        if (KeyInputs.matchesButton(o.keyDown, b)) f.onPress(SimKey.BACK);
        if (KeyInputs.matchesButton(o.keyLeft, b)) f.onPress(SimKey.LEFT);
        if (KeyInputs.matchesButton(o.keyRight, b)) f.onPress(SimKey.RIGHT);
        if (KeyInputs.matchesButton(o.keyJump, b)) f.onPress(SimKey.JUMP);
        if (KeyInputs.matchesButton(o.keyShift, b)) f.onPress(SimKey.SNEAK);
        if (KeyInputs.matchesButton(o.keySprint, b)) f.onPress(SimKey.SPRINT);
        if (KeyInputs.matchesButton(o.keyAttack, b)) f.onPress(SimKey.ATTACK);
        if (KeyInputs.matchesButton(o.keyUse, b)) f.onPress(SimKey.USE);
    }

    private static void releaseButtons(ManualFactsTracker f, Options o, int b) {
        if (KeyInputs.matchesButton(o.keyUp, b)) f.onRelease(SimKey.FORWARD);
        if (KeyInputs.matchesButton(o.keyDown, b)) f.onRelease(SimKey.BACK);
        if (KeyInputs.matchesButton(o.keyLeft, b)) f.onRelease(SimKey.LEFT);
        if (KeyInputs.matchesButton(o.keyRight, b)) f.onRelease(SimKey.RIGHT);
        if (KeyInputs.matchesButton(o.keyJump, b)) f.onRelease(SimKey.JUMP);
        if (KeyInputs.matchesButton(o.keyShift, b)) f.onRelease(SimKey.SNEAK);
        if (KeyInputs.matchesButton(o.keySprint, b)) f.onRelease(SimKey.SPRINT);
        if (KeyInputs.matchesButton(o.keyAttack, b)) f.onRelease(SimKey.ATTACK);
        if (KeyInputs.matchesButton(o.keyUse, b)) f.onRelease(SimKey.USE);
    }

    private static boolean matchesAny(KeyMapping[] mappings, int key) {
        for (KeyMapping m : mappings) {
            if (KeyInputs.matchesKey(m, key)) {
                return true;
            }
        }
        return false;
    }
}
