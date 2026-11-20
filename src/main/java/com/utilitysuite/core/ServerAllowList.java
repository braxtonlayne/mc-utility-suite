package com.utilitysuite.core;

import java.util.Locale;

/**
 * Shared "single-player or the user's own allow-listed server" check. The allow list itself is the PacketLab latency
 * simulator's {@value #SETTING_KEY} setting (module {@value #SOURCE_MODULE_ID}); features that must stay off public
 * servers read it from the active profile through these constants. Pure Java.
 */
public final class ServerAllowList {

    /** Module id whose profile section holds the allow list. */
    public static final String SOURCE_MODULE_ID = "latency_simulator";
    public static final String SETTING_KEY = "allowed_servers";
    public static final String DEFAULT_LIST = "localhost,127.0.0.1";

    private ServerAllowList() {
    }

    /** Whether {@code address} (host or host:port) matches an allow-list entry ("host" or "host:port"). */
    public static boolean isAllowedServer(String address, String allowList) {
        if (address == null || address.isBlank() || allowList == null) {
            return false;
        }
        String a = address.trim().toLowerCase(Locale.ROOT);
        String host = a;
        int colon = a.lastIndexOf(':');
        if (colon > 0 && a.indexOf(':') == colon) {
            host = a.substring(0, colon);
        }
        for (String entry : allowList.split(",")) {
            String e = entry.trim().toLowerCase(Locale.ROOT);
            if (!e.isEmpty() && (e.equals(a) || e.equals(host))) {
                return true;
            }
        }
        return false;
    }

    /** Single-player always passes; otherwise the server address must be on the list. */
    public static boolean isAllowed(boolean singleplayer, String serverAddress, String allowList) {
        return singleplayer || isAllowedServer(serverAddress, allowList);
    }
}
