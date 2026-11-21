package com.utilitysuite.core.input;

/**
 * Static entry for {@code LocalPlayerSilentRotationMixin}: hands the moment right before the movement packet to the
 * smoother's foreign rotation guard ({@link InputSmoother#captureForeignRotation()}). Client thread only.
 */
public final class ForeignRotationHook {

    private static InputSmoother smoother;

    private ForeignRotationHook() {
    }

    public static void install(InputSmoother s) {
        smoother = s;
    }

    public static void beforeSendPosition() {
        InputSmoother s = smoother;
        if (s != null) {
            s.captureForeignRotation();
        }
    }
}
