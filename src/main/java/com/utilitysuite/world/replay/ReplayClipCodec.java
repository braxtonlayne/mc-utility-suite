package com.utilitysuite.world.replay;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Kill-cam clip files (F2): the whole {@link ReplayRing} (30 s of poses plus its events) as gzip-compressed primitive
 * records. Positions are stored as float offsets from the first frame's local player, ticks as offsets from the first
 * frame, so a clip is roughly 40 bytes per recorded pose before compression (a full 16-entity ring is about 400 KB raw,
 * typically well under 100 KB on disk). Engine-free; encode on the client thread (one copy of the frozen ring), gzip and
 * write on the world executor.
 */
public final class ReplayClipCodec {

    private static final int MAGIC = 0x55524350; // "URCP"
    private static final int VERSION = 1;
    /** Files larger than this are refused when reading (corrupt or foreign). */
    public static final int MAX_FILE_BYTES = 4 << 20;

    /** Header of a clip (what the list shows). */
    public record Info(long savedMs, int selfId, int frames, int events, double durationSeconds) {
    }

    /** A decoded clip: a frozen ring ready for {@link ReplayScreen}. */
    public record Clip(Info info, ReplayRing ring) {
    }

    private ReplayClipCodec() {
    }

    /** Raw (uncompressed) encoding of the ring; see {@link #compress}. */
    public static byte[] encode(ReplayRing ring, int selfId, long savedMs) throws IOException {
        int frames = ring.frames();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(64 + frames * 200);
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            double ox = 0;
            double oy = 0;
            double oz = 0;
            if (frames > 0) {
                int s = ring.find(0, selfId);
                int i = ring.at(0, Math.max(0, s));
                if (ring.count(0) > 0) {
                    ox = ring.x(i);
                    oy = ring.y(i);
                    oz = ring.z(i);
                }
            }
            long t0 = frames > 0 ? ring.tickOf(0) : 0;
            long tEnd = frames > 0 ? ring.tickOf(frames - 1) : 0;
            out.writeInt(MAGIC);
            out.writeInt(VERSION);
            out.writeLong(savedMs);
            out.writeInt(selfId);
            out.writeInt(frames);
            out.writeInt(ring.events());
            out.writeDouble((tEnd - t0) / (double) ReplayTimeline.TICKS_PER_SECOND);
            out.writeDouble(ox);
            out.writeDouble(oy);
            out.writeDouble(oz);
            out.writeLong(t0);
            for (int f = 0; f < frames; f++) {
                out.writeInt((int) (ring.tickOf(f) - t0));
                int n = ring.count(f);
                out.writeByte(n);
                for (int s = 0; s < n; s++) {
                    int i = ring.at(f, s);
                    out.writeInt(ring.id(i));
                    out.writeFloat((float) (ring.x(i) - ox));
                    out.writeFloat((float) (ring.y(i) - oy));
                    out.writeFloat((float) (ring.z(i) - oz));
                    out.writeFloat(ring.yaw(i));
                    out.writeFloat(ring.pitch(i));
                    out.writeFloat(ring.vx(i));
                    out.writeFloat(ring.vy(i));
                    out.writeFloat(ring.vz(i));
                    out.writeFloat(ring.width(i));
                    out.writeFloat(ring.height(i));
                    out.writeByte(ring.flags(i));
                }
            }
            int ev = ring.events();
            for (int e = 0; e < ev; e++) {
                out.writeInt((int) (ring.eventTick(e) - t0));
                out.writeByte(ring.eventType(e));
                out.writeFloat(ring.eventValue(e));
                out.writeInt(ring.eventEntity(e));
            }
        }
        return bytes.toByteArray();
    }

    public static byte[] compress(byte[] raw) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            gz.write(raw);
        }
        return bytes.toByteArray();
    }

    /** Only the header (for listing saved clips). */
    public static Info readInfo(byte[] file) throws IOException {
        try (DataInputStream in = open(file)) {
            return header(in);
        }
    }

    /** Decodes a saved (compressed) clip into a fresh, frozen ring. */
    public static Clip decode(byte[] file) throws IOException {
        try (DataInputStream in = open(file)) {
            Info info = header(in);
            double ox = in.readDouble();
            double oy = in.readDouble();
            double oz = in.readDouble();
            long t0 = in.readLong();
            ReplayRing ring = new ReplayRing();
            for (int f = 0; f < info.frames(); f++) {
                ring.beginFrame(t0 + in.readInt());
                int n = in.readUnsignedByte();
                if (n > ReplayRing.SLOTS) {
                    throw new IOException("bad slot count " + n);
                }
                for (int s = 0; s < n; s++) {
                    int id = in.readInt();
                    double x = ox + in.readFloat();
                    double y = oy + in.readFloat();
                    double z = oz + in.readFloat();
                    float yaw = in.readFloat();
                    float pitch = in.readFloat();
                    float vx = in.readFloat();
                    float vy = in.readFloat();
                    float vz = in.readFloat();
                    float w = in.readFloat();
                    float h = in.readFloat();
                    byte fl = in.readByte();
                    ring.addEntity(id, x, y, z, yaw, pitch, vx, vy, vz, w, h, fl);
                }
            }
            for (int e = 0; e < info.events(); e++) {
                long t = t0 + in.readInt();
                byte type = in.readByte();
                float v = in.readFloat();
                int who = in.readInt();
                ring.addEvent(t, type, v, who);
            }
            ring.setFrozen(true);
            return new Clip(info, ring);
        }
    }

    private static DataInputStream open(byte[] file) throws IOException {
        if (file == null || file.length == 0 || file.length > MAX_FILE_BYTES) {
            throw new IOException("missing or oversized clip");
        }
        return new DataInputStream(new GZIPInputStream(new ByteArrayInputStream(file)));
    }

    private static Info header(DataInputStream in) throws IOException {
        if (in.readInt() != MAGIC) {
            throw new IOException("not a replay clip");
        }
        int v = in.readInt();
        if (v != VERSION) {
            throw new IOException("unsupported clip version " + v);
        }
        long saved = in.readLong();
        int self = in.readInt();
        int frames = in.readInt();
        int events = in.readInt();
        double dur = in.readDouble();
        if (frames < 0 || frames > ReplayRing.FRAMES || events < 0 || events > ReplayRing.EVENTS) {
            throw new IOException("bad clip header");
        }
        return new Info(saved, self, frames, events, dur);
    }
}
