package com.utilitysuite.core.config;

import java.util.Locale;

/**
 * Profile ids and safe file names. Pure Java.
 * <ul>
 *   <li>Single player: {@code sp_<save folder name>}</li>
 *   <li>Multiplayer: {@code mp_<host>_<port>} (port defaults to 25565)</li>
 *   <li>Global settings: {@value #GLOBAL}</li>
 * </ul>
 */
public final class ProfileIds {

    public static final String GLOBAL = "global";
    private static final int MAX_NAME = 80;
    private static final String[] RESERVED = {"CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5",
            "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"};

    private ProfileIds() {
    }

    public static String singleplayer(String saveFolder) {
        return "sp_" + (saveFolder == null || saveFolder.isBlank() ? "unknown" : saveFolder);
    }

    /** {@code address} as typed in the server list: {@code host}, {@code host:port} or {@code [v6]:port}. */
    public static String multiplayer(String address) {
        if (address == null || address.isBlank()) {
            return "mp_unknown";
        }
        String a = address.trim().toLowerCase(Locale.ROOT);
        String host = a;
        String port = "25565";
        if (a.startsWith("[")) {
            int close = a.indexOf(']');
            if (close > 0) {
                host = a.substring(1, close);
                if (close + 1 < a.length() && a.charAt(close + 1) == ':') {
                    port = a.substring(close + 2);
                }
            }
        } else {
            int colon = a.lastIndexOf(':');
            if (colon > 0 && a.indexOf(':') == colon) {
                host = a.substring(0, colon);
                port = a.substring(colon + 1);
            }
        }
        if (port.isEmpty()) {
            port = "25565";
        }
        return "mp_" + host + "_" + port;
    }

    /** A file-system safe name (no extension) for a profile id. */
    public static String fileName(String profileId) {
        StringBuilder sb = new StringBuilder(profileId.length());
        for (int i = 0; i < profileId.length() && sb.length() < MAX_NAME; i++) {
            char c = profileId.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
                    || c == '-' || c == '.' || c == ' ';
            sb.append(ok ? c : '_');
        }
        // No leading/trailing dots or spaces (Windows strips them; ".." would escape the folder).
        int start = 0;
        int end = sb.length();
        while (start < end && (sb.charAt(start) == '.' || sb.charAt(start) == ' ')) {
            start++;
        }
        while (end > start && (sb.charAt(end - 1) == '.' || sb.charAt(end - 1) == ' ')) {
            end--;
        }
        String name = sb.substring(start, end);
        if (name.isEmpty()) {
            return "unnamed";
        }
        String base = name;
        int dot = base.indexOf('.');
        if (dot >= 0) {
            base = base.substring(0, dot);
        }
        for (String r : RESERVED) {
            if (r.equalsIgnoreCase(base.trim())) {
                return "_" + name;
            }
        }
        return name;
    }
}
