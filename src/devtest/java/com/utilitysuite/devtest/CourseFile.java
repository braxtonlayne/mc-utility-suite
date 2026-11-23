package com.utilitysuite.devtest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * The parkour benchmark course as data (src/devtest/resources/course/benchmark-course.txt, designed in
 * docs/design/parkour-benchmark-course.md). One command per line, coordinates relative to the course origin (0,0,0 =
 * the start pad's top block):
 * <pre>
 *   fill x1 y1 z1 x2 y2 z2 block      set x y z block
 *   start x y z yaw                   finish x1 y1 z1 x2 y2 z2
 *   checkpoint n x y z yaw name...    route x y z
 *   jump id section technique difficulty note...
 *   clear x1 y1 z1 x2 y2 z2           (air; sliced under the /fill volume limit)
 *   fail y block                      (the fail floor: standing on it is a fall)
 *   kit slot item count               (given at the start of a run)
 *   alt id x y z tag                  (an optional shortcut line's route points)
 *   # comment                         (route lines may end in a tag: start, run, takeoff, land, ...)
 * </pre>
 * Plain Java (no game classes) so the parser and the timer are unit tested.
 */
final class CourseFile {

    static final String RESOURCE = "/course/benchmark-course.txt";

    /** A build command, relative coordinates; {@code x2..z2} equal {@code x1..z1} for a single block. */
    record Build(int x1, int y1, int z1, int x2, int y2, int z2, String block) {
        boolean single() {
            return x1 == x2 && y1 == y2 && z1 == z2;
        }
    }

    record Checkpoint(int n, double x, double y, double z, float yaw, String name) {
    }

    /** A route point; {@code jump} is the metadata of the jump it belongs to (or null on the run-up). */
    record RoutePoint(double x, double y, double z, Jump jump, String tag) {
    }

    record Kit(int slot, String item, int count) {
    }

    /** /fill refuses more than this many blocks at once. */
    static final int FILL_LIMIT = 32768;

    record Jump(String id, String section, String technique, int difficulty, String note) {
    }

    final List<Build> builds = new ArrayList<>();
    final List<Checkpoint> checkpoints = new ArrayList<>();
    final List<RoutePoint> route = new ArrayList<>();
    final List<Jump> jumps = new ArrayList<>();
    final List<Kit> kits = new ArrayList<>();
    final java.util.Map<String, List<RoutePoint>> alts = new java.util.LinkedHashMap<>();
    /** The fail floor's level (relative), or {@code Integer.MIN_VALUE} when the course has none. */
    int failY = Integer.MIN_VALUE;
    double startX, startY, startZ;
    float startYaw;
    int[] finish; // x1 y1 z1 x2 y2 z2, normalised min/max
    int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
    int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;

    static CourseFile load() throws IOException {
        try (InputStream in = CourseFile.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IOException("course resource missing: " + RESOURCE);
            }
            return parse(new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8)).lines().toList());
        }
    }

    static CourseFile parse(List<String> lines) {
        CourseFile c = new CourseFile();
        Jump pending = null;
        boolean started = false;
        int no = 0;
        for (String raw : lines) {
            no++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                switch (t[0]) {
                    case "fill" -> c.add(new Build(i(t[1]), i(t[2]), i(t[3]), i(t[4]), i(t[5]), i(t[6]), rest(t, 7)));
                    case "set" -> c.add(new Build(i(t[1]), i(t[2]), i(t[3]), i(t[1]), i(t[2]), i(t[3]), rest(t, 4)));
                    case "start" -> {
                        c.startX = d(t[1]);
                        c.startY = d(t[2]);
                        c.startZ = d(t[3]);
                        c.startYaw = (float) d(t[4]);
                        started = true;
                    }
                    case "finish" -> c.finish = new int[] {Math.min(i(t[1]), i(t[4])), Math.min(i(t[2]), i(t[5])),
                            Math.min(i(t[3]), i(t[6])), Math.max(i(t[1]), i(t[4])), Math.max(i(t[2]), i(t[5])),
                            Math.max(i(t[3]), i(t[6]))};
                    case "checkpoint" -> c.checkpoints.add(new Checkpoint(i(t[1]), d(t[2]), d(t[3]), d(t[4]),
                            (float) d(t[5]), rest(t, 6)));
                    case "route" -> c.route.add(new RoutePoint(d(t[1]), d(t[2]), d(t[3]), pending, rest(t, 4)));
                    case "alt" -> c.alts.computeIfAbsent(t[1], k -> new ArrayList<>())
                            .add(new RoutePoint(d(t[2]), d(t[3]), d(t[4]), null, rest(t, 5)));
                    case "kit" -> c.kits.add(new Kit(i(t[1]), t[2], t.length > 3 ? i(t[3]) : 1));
                    case "fail" -> {
                        c.failY = i(t[1]);
                        c.failBlock = t[2];
                    }
                    case "clear" -> c.clear(i(t[1]), i(t[2]), i(t[3]), i(t[4]), i(t[5]), i(t[6]));
                    case "jump" -> {
                        pending = new Jump(t[1], t[2], t[3], i(t[4]), rest(t, 5));
                        c.jumps.add(pending);
                    }
                    default -> throw new IllegalArgumentException("unknown command '" + t[0] + "'");
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("course line " + no + ": " + line + " (" + e.getMessage() + ")", e);
            }
        }
        if (!started) {
            throw new IllegalArgumentException("course has no start line");
        }
        if (c.finish == null) {
            throw new IllegalArgumentException("course has no finish line");
        }
        c.checkpoints.sort((a, b) -> Integer.compare(a.n(), b.n()));
        if (c.failY != Integer.MIN_VALUE) {
            // the fail floor spans the course's whole footprint (built after everything else is placed)
            c.add(new Build(c.minX, c.failY, c.minZ, c.maxX, c.failY, c.maxZ, c.failBlock));
        }
        return c;
    }

    String failBlock;

    /** An air volume, sliced along y into fills that each stay under {@link #FILL_LIMIT}. */
    private void clear(int x1, int y1, int z1, int x2, int y2, int z2) {
        int ax = Math.min(x1, x2), bx = Math.max(x1, x2), ay = Math.min(y1, y2), by = Math.max(y1, y2);
        int az = Math.min(z1, z2), bz = Math.max(z1, z2);
        long layer = (long) (bx - ax + 1) * (bz - az + 1);
        if (layer > FILL_LIMIT) {
            // also slice along x when one layer alone is too big
            int step = (int) Math.max(1, FILL_LIMIT / (bz - az + 1));
            for (int x = ax; x <= bx; x += step) {
                clear(x, ay, az, Math.min(bx, x + step - 1), by, bz);
            }
            return;
        }
        int rows = (int) Math.max(1, FILL_LIMIT / layer);
        for (int y = ay; y <= by; y += rows) {
            add(new Build(ax, y, az, bx, Math.min(by, y + rows - 1), bz, "minecraft:air"));
        }
    }

    /** True when the feet are on (or below) the fail floor; without one, well below the lowest built block. */
    boolean fell(double y) {
        return failY != Integer.MIN_VALUE ? y < failY + 1.5 : y < minY - 4;
    }

    private void add(Build b) {
        builds.add(b);
        minY = Math.min(minY, Math.min(b.y1(), b.y2()));
        maxY = Math.max(maxY, Math.max(b.y1(), b.y2()));
        minX = Math.min(minX, Math.min(b.x1(), b.x2()));
        maxX = Math.max(maxX, Math.max(b.x1(), b.x2()));
        minZ = Math.min(minZ, Math.min(b.z1(), b.z2()));
        maxZ = Math.max(maxZ, Math.max(b.z1(), b.z2()));
    }

    /** True when the feet position (relative) is inside the finish volume. */
    boolean inFinish(double x, double y, double z) {
        return x >= finish[0] && x < finish[3] + 1 && y >= finish[1] && y < finish[4] + 1 && z >= finish[2]
                && z < finish[5] + 1;
    }

    /** Horizontal reach of a checkpoint around its standing position: a 3x3 pad counts anywhere on it. */
    static final double PAD_REACH = 1.6;

    /** The checkpoint whose pad holds the feet (relative position), or -1. */
    int checkpointAt(double x, double y, double z) {
        for (int k = 0; k < checkpoints.size(); k++) {
            Checkpoint cp = checkpoints.get(k);
            if (Math.abs(x - cp.x()) < PAD_REACH && Math.abs(z - cp.z()) < PAD_REACH
                    && Math.abs(y - cp.y()) < 0.6) {
                return k;
            }
        }
        return -1;
    }

    private static int i(String s) {
        return Integer.parseInt(s);
    }

    private static double d(String s) {
        return Double.parseDouble(s);
    }

    private static String rest(String[] t, int from) {
        return from >= t.length ? "" : String.join(" ", java.util.Arrays.copyOfRange(t, from, t.length));
    }
}
