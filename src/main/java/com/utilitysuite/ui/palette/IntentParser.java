package com.utilitysuite.ui.palette;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A tiny rule grammar for compound palette intents, engine free. Rules, tried in order (case-insensitive):
 * <ul>
 *   <li>{@code route|path|navigate to <target> [via <dimension>]} → {@link Kind#ROUTE} (target, via)</li>
 *   <li>{@code go to <name>} → {@link Kind#GO_TO} (waypoint name)</li>
 *   <li>{@code where are|is my <item>}, {@code find my <item>}, {@code where did i put <item>} → {@link Kind#FIND_ITEM}
 *       (item id guess: singular, spaces to underscores, {@code minecraft:} namespace)</li>
 *   <li>{@code pulse sonar} / {@code sonar pulse} / {@code ping sonar} → {@link Kind#ACTION} "sonar_pulse"</li>
 *   <li>{@code toggle|enable|disable <module>} → {@link Kind#TOGGLE} (module text, flag: null toggle, TRUE, FALSE)</li>
 * </ul>
 * Anything else is {@link Kind#NONE}: the palette then shows its fuzzy results only.
 */
public final class IntentParser {

    public enum Kind { NONE, ROUTE, GO_TO, FIND_ITEM, ACTION, TOGGLE }

    /**
     * @param target route target / waypoint name / item id / action id / module text
     * @param extra  route "via" dimension, or for FIND_ITEM the words as typed; empty otherwise
     * @param flag   TOGGLE only: null = flip, TRUE = enable, FALSE = disable
     */
    public record Intent(Kind kind, String target, String extra, Boolean flag) {
        public static final Intent NONE = new Intent(Kind.NONE, "", "", null);
    }

    private static final Pattern ROUTE = Pattern.compile(
            "^(?:route|path|navigate|travel)\\s+to\\s+(.+?)(?:\\s+via\\s+(?:the\\s+)?(\\w+))?$");
    private static final Pattern GO_TO = Pattern.compile("^go\\s*to\\s+(.+)$");
    private static final Pattern FIND = Pattern.compile(
            "^(?:where\\s+(?:are|is)\\s+(?:my|the)\\s+|find\\s+my\\s+|where\\s+did\\s+i\\s+put\\s+(?:my\\s+)?)(.+?)\\??$");
    private static final Pattern SONAR = Pattern.compile("^(?:pulse|ping|fire)\\s+sonar$|^sonar(?:\\s+pulse)?$");
    private static final Pattern TOGGLE = Pattern.compile("^(toggle|enable|disable|turn\\s+on|turn\\s+off)\\s+(.+)$");

    private IntentParser() {
    }

    public static Intent parse(String text) {
        if (text == null) {
            return Intent.NONE;
        }
        String t = text.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return Intent.NONE;
        }
        Matcher m = ROUTE.matcher(t);
        if (m.matches()) {
            return new Intent(Kind.ROUTE, m.group(1).trim(), m.group(2) == null ? "" : m.group(2), null);
        }
        m = GO_TO.matcher(t);
        if (m.matches()) {
            return new Intent(Kind.GO_TO, m.group(1).trim(), "", null);
        }
        m = FIND.matcher(t);
        if (m.matches()) {
            String words = m.group(1).trim();
            return new Intent(Kind.FIND_ITEM, itemId(words), words, null);
        }
        if (SONAR.matcher(t).matches()) {
            return new Intent(Kind.ACTION, "sonar_pulse", "", null);
        }
        m = TOGGLE.matcher(t);
        if (m.matches()) {
            String verb = m.group(1);
            Boolean flag = verb.equals("toggle") ? null : (verb.equals("enable") || verb.equals("turn on"));
            return new Intent(Kind.TOGGLE, m.group(2).trim(), "", flag);
        }
        return Intent.NONE;
    }

    /** "diamonds" → "minecraft:diamond", "iron ingots" → "minecraft:iron_ingot"; a namespaced id is kept. */
    static String itemId(String words) {
        String w = words.trim();
        if (w.contains(":")) {
            return w.replace(' ', '_');
        }
        String[] parts = w.split(" ");
        String last = parts[parts.length - 1];
        parts[parts.length - 1] = singular(last);
        return "minecraft:" + String.join("_", parts);
    }

    private static String singular(String w) {
        if (w.endsWith("ies") && w.length() > 4) {
            return w.substring(0, w.length() - 3) + "y";
        }
        if (w.endsWith("ches") || w.endsWith("shes") || w.endsWith("xes")) {
            return w.substring(0, w.length() - 2);
        }
        if (w.endsWith("s") && !w.endsWith("ss") && w.length() > 3) {
            return w.substring(0, w.length() - 1);
        }
        return w;
    }
}
