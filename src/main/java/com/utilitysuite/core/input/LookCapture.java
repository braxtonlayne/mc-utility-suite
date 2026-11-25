package com.utilitysuite.core.input;

/**
 * Camera hold for gesture input (the action wheel): while active, the user's mouse motion is taken from the turn hook
 * ({@link ManualInputHooks#onTurnYaw} / {@link ManualInputHooks#onTurnPitch}) and accumulated here instead of turning
 * the camera; vanilla then calls {@code turn(0, 0)}. Nothing is ever written to the player's rotation, so this needs no
 * lease: it only withholds the user's own motion while the user holds the wheel key. Lapses by itself when not renewed
 * (a stuck wheel can never freeze the camera): the owner calls {@link #renew} every tick.
 * <p>
 * Units are vanilla turn units (1 unit = 0.15 deg at the user's sensitivity), screen convention: +x right, +y down.
 * Client/render thread; primitive statics, allocation free.
 */
public final class LookCapture {

    /** Ticks a hold stays live without {@link #renew}. */
    static final int TTL_TICKS = 3;

    private static int ttl;
    private static double dx;
    private static double dy;

    private LookCapture() {
    }

    /** Starts (or keeps) holding the camera and clears the accumulated motion when newly started. */
    public static void begin() {
        if (ttl <= 0) {
            dx = 0;
            dy = 0;
        }
        ttl = TTL_TICKS;
    }

    /** Keeps the hold alive for another few ticks. */
    public static void renew() {
        if (ttl > 0) {
            ttl = TTL_TICKS;
        }
    }

    /** Releases the camera at once. */
    public static void end() {
        ttl = 0;
        dx = 0;
        dy = 0;
    }

    /** Client tick: ages the hold. */
    public static void tick() {
        if (ttl > 0) {
            ttl--;
        }
    }

    public static boolean active() {
        return ttl > 0;
    }

    /** Motion since the last take, x (right +). Resets it. */
    public static double takeX() {
        double v = dx;
        dx = 0;
        return v;
    }

    /** Motion since the last take, y (down +). Resets it. */
    public static double takeY() {
        double v = dy;
        dy = 0;
        return v;
    }

    /** Turn hook: swallows the yaw while held. Returns the argument to pass on. */
    static double yaw(double yawArg) {
        dx += yawArg;
        return 0.0;
    }

    /** Turn hook: swallows the pitch while held. */
    static double pitch(double pitchArg) {
        dy += pitchArg;
        return 0.0;
    }
}
