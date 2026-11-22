package com.utilitysuite.ui;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The style scan (design 6): the UI code draws through the kit and the theme tokens, never with its own colours or
 * direct {@code GuiGraphics} calls. Plain text scan of the sources under {@code ui/}, {@code combat/hud},
 * {@code combat/coach} and {@code world/minimap}:
 * <ul>
 *   <li>rule COLOUR: no integer literal of 6 or 8 hex digits ({@code 0xRRGGBB}, {@code 0xAARRGGBB}) that is not the
 *       operand of a bit mask ({@code & 0xFFFFFF});</li>
 *   <li>rule DRAW: no {@code g.fill(}, {@code g.fillGradient(}, {@code g.drawString(}, {@code g.renderOutline(} and
 *       the like outside {@code ui/holo} and {@code ui/kit} (which are the drawing layer itself).</li>
 * </ul>
 * Files that legitimately or temporarily break a rule are in {@link #ALLOW} with the reason. The list is the
 * adoption backlog: it must only shrink. An entry that no longer offends fails the test too, so a fixed file has to
 * leave the list.
 */
class StyleScanTest {

    private static final Path ROOT = Paths.get("src", "main", "java", "com", "utilitysuite");
    private static final String[] SCOPE = {"ui", "combat/hud", "combat/coach", "world/minimap"};
    private static final String COLOUR = "COLOUR";
    private static final String DRAW = "DRAW";

    private static final Pattern HEX = Pattern.compile("(?<![\\w])0x[0-9A-Fa-f]{6}(?:[0-9A-Fa-f]{2})?(?![\\w])");
    private static final Pattern DIRECT = Pattern.compile(
            "\\b[a-z]\\w*\\.(fill|fillGradient|drawString|drawCenteredString|renderOutline)\\(");
    private static final Pattern COMMENTS = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL);

    /** Allow-list: "path|rule" to the reason. Paths are relative to {@code com/utilitysuite}, with forward slashes. */
    private static final Map<String, String> ALLOW = new TreeMap<>();

    static {
        // The theme itself and the drawing layer: token tables and texture/atlas pixel work.
        allow("ui/holo/HoloTheme.java", COLOUR, "the token tables: the one place theme colours are defined");
        allow("ui/holo/HoloAtlas.java", COLOUR, "atlas texture pixel work (white glyph pixels, channel swaps)");
        allow("ui/holo/VisualSettings.java", COLOUR, "the accent preset swatches the appearance settings offer");
        allow("ui/InfoStripText.java", COLOUR, "health/hunger traffic-light colours of the info strip (backlog: tokens)");
        allow("world/minimap/MinimapPalette.java", COLOUR, "map tile colours: the allow-listed minimap palette");
        // Backlog: files outside this task that still draw directly.
        allow("ui/SuiteMenuScreen.java", DRAW, "the menu window itself draws with g.fill on top of the kit (backlog)");
        allow("ui/AutopilotBar.java", DRAW, "legacy GuiGraphics bar (backlog: port to a HudElement)");
        allow("ui/UiPreview.java", COLOUR, "WIP-REMOVE");
        allow("ui/UiPreview.java", DRAW, "WIP-REMOVE");
        allow("ui/TabGui.java", DRAW, "tab GUI rows use g.fill (backlog: port to the canvas)");
    }

    private static void allow(String path, String rule, String reason) {
        ALLOW.put(path + "|" + rule, reason);
    }

    @Test
    void sourcesDrawThroughTheKitAndTokens() throws IOException {
        TreeSet<String> found = new TreeSet<>();
        List<String> report = new ArrayList<>();
        for (String dir : SCOPE) {
            Path base = ROOT.resolve(dir);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(base)) {
                for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                    String rel = ROOT.relativize(f).toString().replace('\\', '/');
                    String src = stripComments(Files.readString(f));
                    scan(rel, src, found, report);
                }
            }
        }
        List<String> problems = new ArrayList<>();
        for (String hit : found) {
            if (!ALLOW.containsKey(hit)) {
                problems.add("not allow-listed: " + hit + " (" + String.join("; ", reportFor(report, hit)) + ")");
            }
        }
        for (String allowed : ALLOW.keySet()) {
            if (!found.contains(allowed)) {
                problems.add("stale allow-list entry (file is clean or gone, remove it): " + allowed);
            }
        }
        assertTrue(problems.isEmpty(), String.join("\n", problems));
    }

    @Test
    void scanRecognisesOffendersAndIgnoresMasks() {
        TreeSet<String> found = new TreeSet<>();
        List<String> report = new ArrayList<>();
        scan("ui/Sample.java", "int c = 0xFF22C55E; int m = x & 0xFFFFFF; g.fill(0, 0, 1, 1, c); Arrays.fill(a, 0);",
                found, report);
        assertTrue(found.contains("ui/Sample.java|COLOUR"));
        assertTrue(found.contains("ui/Sample.java|DRAW"));
        found.clear();
        scan("ui/holo/Sample.java", "int m = x & 0xFFFFFF; g.fill(0, 0, 1, 1, c);", found, report);
        assertTrue(found.isEmpty(), "masks and the drawing layer are fine: " + found);
        scan("ui/kit/Sample.java", "g.drawString(font, s, 0, 0, c);", found, report);
        assertTrue(found.isEmpty());
    }

    private static void scan(String rel, String src, TreeSet<String> found, List<String> report) {
        Matcher m = HEX.matcher(src);
        while (m.find()) {
            if (!isMaskOperand(src, m.start())) {
                String key = rel + "|" + COLOUR;
                found.add(key);
                report.add(key + "@" + m.group());
            }
        }
        boolean drawingLayer = rel.startsWith("ui/holo/") || rel.startsWith("ui/kit/");
        if (!drawingLayer) {
            Matcher d = DIRECT.matcher(src);
            if (d.find()) {
                String key = rel + "|" + DRAW;
                found.add(key);
                report.add(key + "@" + d.group());
            }
        }
    }

    /** True when the literal is an operand of {@code &} (a mask), e.g. {@code c & 0x00FFFFFF}. */
    private static boolean isMaskOperand(String src, int start) {
        int i = start - 1;
        while (i >= 0 && Character.isWhitespace(src.charAt(i))) {
            i--;
        }
        return i >= 0 && src.charAt(i) == '&';
    }

    private static String stripComments(String s) {
        return COMMENTS.matcher(s).replaceAll("");
    }

    private static List<String> reportFor(List<String> report, String key) {
        List<String> out = new ArrayList<>();
        for (String r : report) {
            if (r.startsWith(key + "@") && out.size() < 3) {
                out.add(r.substring(key.length() + 1));
            }
        }
        return out;
    }
}
