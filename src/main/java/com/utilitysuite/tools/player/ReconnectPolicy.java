package com.utilitysuite.tools.player;

import java.util.Locale;

/**
 * Engine-free auto-reconnect policy: exponential backoff ({@code base * 2^attempt}, capped), an attempt cap, and a reason
 * filter. Never reconnects after a manual disconnect, or when the server kicked or banned the player, the player is not
 * whitelisted, the versions do not match, or the account logged in elsewhere (reconnecting would fight the server or the
 * user's other session).
 */
public final class ReconnectPolicy {

    /** Lowercase fragments of disconnect messages that must never trigger a reconnect. */
    static final String[] REFUSED = {
            "kick", "banned", "ban ", "white-list", "whitelist", "not whitelisted", "outdated", "incompatible",
            "logged in from another location", "another location", "duplicate", "multiplayer.disconnect.kicked",
            "multiplayer.disconnect.banned", "multiplayer.disconnect.not_whitelisted", "multiplayer.disconnect.duplicate",
            "multiplayer.disconnect.incompatible", "multiplayer.disconnect.outdated"};

    public static final int MAX_DELAY_SECONDS = 300;

    private int maxAttempts = 5;
    private int baseDelaySeconds = 5;

    public ReconnectPolicy configure(int maxAttempts, int baseDelaySeconds) {
        this.maxAttempts = Math.max(0, maxAttempts);
        this.baseDelaySeconds = Math.max(1, baseDelaySeconds);
        return this;
    }

    public int maxAttempts() {
        return maxAttempts;
    }

    /** Wait before attempt number {@code attempt} (0-based), in ticks. */
    public int delayTicks(int attempt) {
        long s = (long) baseDelaySeconds << Math.min(Math.max(attempt, 0), 16);
        return (int) Math.min(s, MAX_DELAY_SECONDS) * 20;
    }

    /** True when a reason text (title + message of the disconnect screen) allows a reconnect. */
    public static boolean reasonAllows(String reason) {
        if (reason == null) {
            return true;
        }
        String r = reason.toLowerCase(Locale.ROOT);
        for (String bad : REFUSED) {
            if (r.contains(bad)) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param manualDisconnect the user left on purpose
     * @param attempt          attempts already made since the last successful join
     */
    public boolean shouldReconnect(String reason, boolean manualDisconnect, int attempt) {
        return !manualDisconnect && attempt < maxAttempts && reasonAllows(reason);
    }
}
