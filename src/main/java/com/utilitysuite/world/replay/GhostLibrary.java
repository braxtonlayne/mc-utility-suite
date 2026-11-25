package com.utilitysuite.world.replay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Saved parkour courses of one world (F2): per course the start/finish markers, the personal best in ticks and the
 * ghost track of that best run. The track is stored as float offsets from the start marker, one xyz triple per tick
 * (at most {@link ParkourSplits#MAX_TICKS}), so a course is a few KB; the file is gzip-compressed primitive arrays.
 * Size-capped at {@link #MAX_COURSES} courses (the least recently saved one goes first). A course is identified by
 * its dimension and the block positions of its two markers, so setting the same markers again finds it.
 * Engine-free and thread-safe (synchronized); {@link Course} is immutable.
 */
public final class GhostLibrary {

    private static final int MAGIC = 0x55474853; // "UGHS"
    private static final int VERSION = 1;
    public static final int MAX_COURSES = 32;
    /** Markers within this many blocks (each axis) count as the same course. */
    public static final double MATCH_TOLERANCE = 1.5;

    /**
     * One saved course. {@code track} holds xyz offsets from the start marker, 3 floats per tick ({@link #ticks()}
     * samples); never mutated after construction.
     */
    public record Course(String id, String name, String dimension, double sx, double sy, double sz, double fx,
                         double fy, double fz, int bestTicks, long savedMs, float[] track) {

        public int samples() {
            return track == null ? 0 : track.length / 3;
        }

        /** Copies the absolute ghost track into the arrays; returns the sample count. */
        public int copyTrack(double[] xs, double[] ys, double[] zs) {
            int n = Math.min(samples(), xs.length);
            for (int i = 0; i < n; i++) {
                xs[i] = sx + track[i * 3];
                ys[i] = sy + track[i * 3 + 1];
                zs[i] = sz + track[i * 3 + 2];
            }
            return n;
        }
    }

    private final Map<String, Course> courses = new LinkedHashMap<>();
    private boolean dirty;

    /** Stable id from the dimension and the markers' block positions. */
    public static String idFor(String dim, double sx, double sy, double sz, double fx, double fy, double fz) {
        return dim + "|" + (int) Math.floor(sx) + "," + (int) Math.floor(sy) + "," + (int) Math.floor(sz) + ">"
                + (int) Math.floor(fx) + "," + (int) Math.floor(fy) + "," + (int) Math.floor(fz);
    }

    /**
     * Builds a course from an absolute track (copied as offsets from the start).
     *
     * @param n samples in xs/ys/zs (clamped to {@link ParkourSplits#MAX_TICKS})
     */
    public static Course course(String name, String dim, double sx, double sy, double sz, double fx, double fy,
                                double fz, int bestTicks, long savedMs, double[] xs, double[] ys, double[] zs, int n) {
        int m = Math.max(0, Math.min(n, ParkourSplits.MAX_TICKS));
        float[] t = new float[m * 3];
        for (int i = 0; i < m; i++) {
            t[i * 3] = (float) (xs[i] - sx);
            t[i * 3 + 1] = (float) (ys[i] - sy);
            t[i * 3 + 2] = (float) (zs[i] - sz);
        }
        return new Course(idFor(dim, sx, sy, sz, fx, fy, fz), name, dim, sx, sy, sz, fx, fy, fz, bestTicks, savedMs,
                t);
    }

    /**
     * Adds or replaces a course. A replacement keeps the faster of the two bests unless {@code force} (an explicit
     * "save as ghost" always wins). Evicts the least recently saved course when over the cap. Returns the stored
     * course (the old one when it was kept).
     */
    public synchronized Course put(Course c, boolean force) {
        Course old = courses.get(c.id());
        if (old != null && !force && old.bestTicks() >= 0 && c.bestTicks() >= 0 && old.bestTicks() <= c.bestTicks()) {
            return old;
        }
        if (old != null && (c.name() == null || c.name().isEmpty())) {
            c = new Course(c.id(), old.name(), c.dimension(), c.sx(), c.sy(), c.sz(), c.fx(), c.fy(), c.fz(),
                    c.bestTicks(), c.savedMs(), c.track());
        }
        courses.remove(c.id());
        courses.put(c.id(), c);
        while (courses.size() > MAX_COURSES) {
            String oldest = null;
            long t = Long.MAX_VALUE;
            for (Course k : courses.values()) {
                if (k.savedMs() < t) {
                    t = k.savedMs();
                    oldest = k.id();
                }
            }
            courses.remove(oldest);
        }
        dirty = true;
        return c;
    }

    public synchronized Course get(String id) {
        return courses.get(id);
    }

    public synchronized boolean remove(String id) {
        boolean r = courses.remove(id) != null;
        dirty |= r;
        return r;
    }

    /** The course in {@code dim} whose start and finish are both within {@link #MATCH_TOLERANCE}, or null. */
    public synchronized Course match(String dim, double sx, double sy, double sz, double fx, double fy, double fz) {
        Course best = null;
        double bestD = Double.MAX_VALUE;
        for (Course c : courses.values()) {
            if (!c.dimension().equals(dim)) {
                continue;
            }
            double ds = Math.max(Math.abs(c.sx() - sx), Math.max(Math.abs(c.sy() - sy), Math.abs(c.sz() - sz)));
            double df = Math.max(Math.abs(c.fx() - fx), Math.max(Math.abs(c.fy() - fy), Math.abs(c.fz() - fz)));
            if (ds <= MATCH_TOLERANCE && df <= MATCH_TOLERANCE && ds + df < bestD) {
                bestD = ds + df;
                best = c;
            }
        }
        return best;
    }

    /** Every course, newest save first (a snapshot). */
    public synchronized List<Course> list() {
        List<Course> out = new ArrayList<>(courses.values());
        out.sort(Comparator.comparingLong(Course::savedMs).reversed());
        return out;
    }

    public synchronized int size() {
        return courses.size();
    }

    public synchronized void clear() {
        courses.clear();
        dirty = false;
    }

    /** Returns and clears the dirty flag (debounced saving). */
    public synchronized boolean consumeDirty() {
        boolean d = dirty;
        dirty = false;
        return d;
    }

    public synchronized byte[] toBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(new GZIPOutputStream(bytes))) {
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeInt(courses.size());
            for (Course c : courses.values()) {
                out.writeUTF(c.name() == null ? "" : c.name());
                out.writeUTF(c.dimension());
                out.writeDouble(c.sx());
                out.writeDouble(c.sy());
                out.writeDouble(c.sz());
                out.writeDouble(c.fx());
                out.writeDouble(c.fy());
                out.writeDouble(c.fz());
                out.writeInt(c.bestTicks());
                out.writeLong(c.savedMs());
                float[] t = c.track();
                out.writeInt(t.length);
                for (float f : t) {
                    out.writeFloat(f);
                }
            }
        }
        return bytes.toByteArray();
    }

    /** Replaces the contents with a saved file. Throws on a foreign or corrupt file (contents then cleared). */
    public synchronized void fromBytes(byte[] data) throws IOException {
        courses.clear();
        dirty = false;
        if (data == null || data.length == 0) {
            return;
        }
        try (DataInputStream in = new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(data)))) {
            if (in.readInt() != MAGIC) {
                throw new IOException("not a ghost file");
            }
            int v = in.readInt();
            if (v != VERSION) {
                throw new IOException("unsupported ghost file version " + v);
            }
            int n = in.readInt();
            if (n < 0 || n > MAX_COURSES * 4) {
                throw new IOException("bad course count " + n);
            }
            for (int i = 0; i < n; i++) {
                String name = in.readUTF();
                String dim = in.readUTF();
                double sx = in.readDouble();
                double sy = in.readDouble();
                double sz = in.readDouble();
                double fx = in.readDouble();
                double fy = in.readDouble();
                double fz = in.readDouble();
                int best = in.readInt();
                long saved = in.readLong();
                int len = in.readInt();
                if (len < 0 || len > ParkourSplits.MAX_TICKS * 3 || len % 3 != 0) {
                    throw new IOException("bad track length " + len);
                }
                float[] t = new float[len];
                for (int k = 0; k < len; k++) {
                    t[k] = in.readFloat();
                }
                Course c = new Course(idFor(dim, sx, sy, sz, fx, fy, fz), name, dim, sx, sy, sz, fx, fy, fz, best,
                        saved, t);
                courses.put(c.id(), c);
            }
        } catch (IOException e) {
            courses.clear();
            throw e;
        }
        while (courses.size() > MAX_COURSES) {
            courses.remove(courses.keySet().iterator().next());
        }
    }
}
