package com.utilitysuite.tools.commands;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Engine-free parser for chat dot-commands ({@code .t auto_fish}, {@code .goal gather 32 iron}, {@code .stop},
 * {@code .help}). A message that starts with the prefix is ALWAYS consumed locally: {@link #parse} returns a command for
 * it (an {@link Kind#UNKNOWN} or {@link Kind#EMPTY} one for typos), so nothing typed after the prefix ever reaches the
 * server. Only a message that does not start with the prefix returns {@code null} and is sent as normal chat.
 */
public final class DotCommandParser {

    public enum Kind { TOGGLE, GOAL, STOP, HELP, LIST, EMPTY, UNKNOWN }

    /**
     * @param name  the command word as typed (lowercase), empty for {@link Kind#EMPTY}
     * @param arg   the argument text (quotes removed for single-argument commands), empty when none
     */
    public record Command(Kind kind, String name, String arg) {
    }

    public static final String DEFAULT_PREFIX = ".";

    private DotCommandParser() {
    }

    /** True when the message would be consumed (starts with a non-empty prefix). */
    public static boolean isCommand(String message, String prefix) {
        String p = normalizePrefix(prefix);
        return message != null && message.startsWith(p);
    }

    /** Parses a chat message; {@code null} when it is ordinary chat (no prefix). */
    public static Command parse(String message, String prefix) {
        if (!isCommand(message, prefix)) {
            return null;
        }
        String body = message.substring(normalizePrefix(prefix).length()).trim();
        if (body.isEmpty()) {
            return new Command(Kind.EMPTY, "", "");
        }
        int sp = indexOfWhitespace(body);
        String word = (sp < 0 ? body : body.substring(0, sp)).toLowerCase(Locale.ROOT);
        String rest = sp < 0 ? "" : body.substring(sp + 1).trim();
        return switch (word) {
            case "t", "toggle" -> new Command(Kind.TOGGLE, word, unquote(rest));
            case "goal", "g" -> new Command(Kind.GOAL, word, rest);
            case "stop", "s" -> new Command(Kind.STOP, word, "");
            case "help", "?", "h" -> new Command(Kind.HELP, word, "");
            case "list", "l", "modules" -> new Command(Kind.LIST, word, "");
            default -> new Command(Kind.UNKNOWN, word, rest);
        };
    }

    /** An empty or blank prefix falls back to ".", so the module can never swallow every chat line. */
    public static String normalizePrefix(String prefix) {
        if (prefix == null) {
            return DEFAULT_PREFIX;
        }
        String p = prefix.trim();
        return p.isEmpty() ? DEFAULT_PREFIX : p;
    }

    /** Removes one pair of surrounding double or single quotes. */
    public static String unquote(String s) {
        String t = s.trim();
        if (t.length() >= 2) {
            char a = t.charAt(0);
            char b = t.charAt(t.length() - 1);
            if ((a == '"' && b == '"') || (a == '\'' && b == '\'')) {
                return t.substring(1, t.length() - 1).trim();
            }
        }
        return t;
    }

    /**
     * Finds the module a {@code .t} argument names: an exact id ("auto_fish"), then a display name ignoring case,
     * spaces, dashes and underscores ("Auto Fish", "autofish"), then a unique prefix of either. Returns the index into
     * {@code ids}, or -1 when nothing (or more than one candidate for a prefix) matches.
     */
    public static int matchModule(String query, List<String> ids, List<String> names) {
        String q = squash(query);
        if (q.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < ids.size(); i++) {
            if (ids.get(i).equalsIgnoreCase(query.trim())) {
                return i;
            }
        }
        for (int i = 0; i < ids.size(); i++) {
            if (squash(ids.get(i)).equals(q) || (i < names.size() && squash(names.get(i)).equals(q))) {
                return i;
            }
        }
        List<Integer> hits = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            if (squash(ids.get(i)).startsWith(q) || (i < names.size() && squash(names.get(i)).startsWith(q))) {
                hits.add(i);
            }
        }
        return hits.size() == 1 ? hits.get(0) : -1;
    }

    /** One-line help text. */
    public static String helpText(String prefix) {
        String p = normalizePrefix(prefix);
        return p + "t <module>  toggle | " + p + "goal <text>  start a goal | " + p + "stop  Stop-All | " + p
                + "list  modules | " + p + "jumpcap [note]  save this jump for a test | " + p + "help";
    }

    private static String squash(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) {
                b.append(Character.toLowerCase(c));
            }
        }
        return b.toString();
    }

    private static int indexOfWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
