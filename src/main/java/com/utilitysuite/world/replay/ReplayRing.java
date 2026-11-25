package com.utilitysuite.world.replay;

/**
 * Replay theatre recorder (F2): a ring of the last {@link #FRAMES} client ticks (30 s), each frame holding up to
 * {@link #SLOTS} entity poses (slot 0 is the local player by convention), plus a small event ring (hits dealt/taken,
 * autopilot notes). Everything lives in arrays preallocated at construction; {@link #beginFrame}, {@link #addEntity}
 * and {@link #addEvent} never allocate. While {@link #frozen()} the ring ignores writes so the replay screen can read
 * it in place (no copy). Frames are addressed oldest-first: frame 0 is the oldest kept, {@code frames()-1} the newest.
 * Engine free.
 */
public final class ReplayRing {

    public static final int FRAMES = 600;
    public static final int SLOTS = 16;
    public static final int EVENTS = 256;

    public static final byte F_HURT = 1;
    public static final byte F_SWING = 2;
    public static final byte F_PLAYER = 4;
    public static final byte F_HOSTILE = 8;
    public static final byte F_SELF = 16;

    public static final byte EV_HIT_TAKEN = 1;
    public static final byte EV_HIT_DEALT = 2;
    public static final byte EV_NOTE = 3;

    private final long[] frameTick = new long[FRAMES];
    private final int[] frameCount = new int[FRAMES];
    private final int[] id = new int[FRAMES * SLOTS];
    private final double[] px = new double[FRAMES * SLOTS];
    private final double[] py = new double[FRAMES * SLOTS];
    private final double[] pz = new double[FRAMES * SLOTS];
    private final float[] yaw = new float[FRAMES * SLOTS];
    private final float[] pitch = new float[FRAMES * SLOTS];
    private final float[] vx = new float[FRAMES * SLOTS];
    private final float[] vy = new float[FRAMES * SLOTS];
    private final float[] vz = new float[FRAMES * SLOTS];
    private final float[] width = new float[FRAMES * SLOTS];
    private final float[] height = new float[FRAMES * SLOTS];
    private final byte[] flags = new byte[FRAMES * SLOTS];
    private int head; // next frame slot to write
    private int size;
    private int cur = -1; // ring index of the frame being written

    private final long[] evTick = new long[EVENTS];
    private final byte[] evType = new byte[EVENTS];
    private final float[] evValue = new float[EVENTS];
    private final int[] evEntity = new int[EVENTS];
    private int evHead;
    private int evSize;

    private boolean frozen;

    public boolean frozen() {
        return frozen;
    }

    public void setFrozen(boolean f) {
        frozen = f;
    }

    public void clear() {
        head = size = evHead = evSize = 0;
        cur = -1;
        frozen = false;
    }

    /** Starts the frame for game tick {@code tick}, overwriting the oldest when full. */
    public void beginFrame(long tick) {
        if (frozen) {
            return;
        }
        cur = head;
        frameTick[cur] = tick;
        frameCount[cur] = 0;
        head = (head + 1) % FRAMES;
        if (size < FRAMES) {
            size++;
        }
    }

    /** Adds one entity pose to the current frame; false when frozen, no frame was begun or the frame is full. */
    public boolean addEntity(int entityId, double x, double y, double z, float yRot, float xRot, float dx, float dy,
            float dz, float w, float h, byte f) {
        if (frozen || cur < 0) {
            return false;
        }
        int n = frameCount[cur];
        if (n >= SLOTS) {
            return false;
        }
        int i = cur * SLOTS + n;
        id[i] = entityId;
        px[i] = x;
        py[i] = y;
        pz[i] = z;
        yaw[i] = yRot;
        pitch[i] = xRot;
        vx[i] = dx;
        vy[i] = dy;
        vz[i] = dz;
        width[i] = w;
        height[i] = h;
        flags[i] = f;
        frameCount[cur] = n + 1;
        return true;
    }

    public void addEvent(long tick, byte type, float value, int entityId) {
        if (frozen) {
            return;
        }
        evTick[evHead] = tick;
        evType[evHead] = type;
        evValue[evHead] = value;
        evEntity[evHead] = entityId;
        evHead = (evHead + 1) % EVENTS;
        if (evSize < EVENTS) {
            evSize++;
        }
    }

    // ---- reading (oldest first) ---------------------------------------------------------------------------------

    public int frames() {
        return size;
    }

    private int ring(int frame) {
        return (head - size + frame + FRAMES) % FRAMES;
    }

    public long tickOf(int frame) {
        return frameTick[ring(frame)];
    }

    public int count(int frame) {
        return frameCount[ring(frame)];
    }

    /** Flat index of (frame, slot) for the accessors below. */
    public int at(int frame, int slot) {
        return ring(frame) * SLOTS + slot;
    }

    public int id(int i) {
        return id[i];
    }

    public double x(int i) {
        return px[i];
    }

    public double y(int i) {
        return py[i];
    }

    public double z(int i) {
        return pz[i];
    }

    public float yaw(int i) {
        return yaw[i];
    }

    public float pitch(int i) {
        return pitch[i];
    }

    public float vx(int i) {
        return vx[i];
    }

    public float vy(int i) {
        return vy[i];
    }

    public float vz(int i) {
        return vz[i];
    }

    public float width(int i) {
        return width[i];
    }

    public float height(int i) {
        return height[i];
    }

    public byte flags(int i) {
        return flags[i];
    }

    /** Slot of {@code entityId} in {@code frame}, or -1. */
    public int find(int frame, int entityId) {
        int base = ring(frame) * SLOTS;
        int n = frameCount[ring(frame)];
        for (int s = 0; s < n; s++) {
            if (id[base + s] == entityId) {
                return s;
            }
        }
        return -1;
    }

    /**
     * Pose of {@code entityId} at fractional frame time {@code t} (0 = oldest frame) into {@code out} as x, y, z, yaw,
     * pitch. Positions lerp; yaw takes the short way round. If the entity is only in one of the two frames, that
     * frame's pose is used. False if it is in neither. Allocation free.
     */
    public boolean pose(double t, int entityId, double[] out) {
        if (size == 0) {
            return false;
        }
        double c = Math.max(0.0, Math.min(size - 1, t));
        int f0 = (int) Math.floor(c);
        int f1 = Math.min(size - 1, f0 + 1);
        double a = c - f0;
        int s0 = find(f0, entityId);
        int s1 = find(f1, entityId);
        if (s0 < 0 && s1 < 0) {
            return false;
        }
        if (s0 < 0 || s1 < 0) {
            int i = s0 >= 0 ? at(f0, s0) : at(f1, s1);
            out[0] = px[i];
            out[1] = py[i];
            out[2] = pz[i];
            out[3] = yaw[i];
            out[4] = pitch[i];
            return true;
        }
        int i0 = at(f0, s0);
        int i1 = at(f1, s1);
        out[0] = px[i0] + (px[i1] - px[i0]) * a;
        out[1] = py[i0] + (py[i1] - py[i0]) * a;
        out[2] = pz[i0] + (pz[i1] - pz[i0]) * a;
        out[3] = yaw[i0] + wrapDegrees(yaw[i1] - yaw[i0]) * a;
        out[4] = pitch[i0] + (pitch[i1] - pitch[i0]) * a;
        return true;
    }

    static double wrapDegrees(double d) {
        double r = d % 360.0;
        if (r >= 180.0) {
            r -= 360.0;
        }
        if (r < -180.0) {
            r += 360.0;
        }
        return r;
    }

    // ---- events (oldest first) ----------------------------------------------------------------------------------

    public int events() {
        return evSize;
    }

    private int evRing(int e) {
        return (evHead - evSize + e + EVENTS) % EVENTS;
    }

    public long eventTick(int e) {
        return evTick[evRing(e)];
    }

    public byte eventType(int e) {
        return evType[evRing(e)];
    }

    public float eventValue(int e) {
        return evValue[evRing(e)];
    }

    public int eventEntity(int e) {
        return evEntity[evRing(e)];
    }

    /** Tick of the last event of {@code type} at or before {@code tick}, or {@code Long.MIN_VALUE}. */
    public long lastEventAtOrBefore(byte type, long tick) {
        for (int e = evSize - 1; e >= 0; e--) {
            int r = evRing(e);
            if (evType[r] == type && evTick[r] <= tick) {
                return evTick[r];
            }
        }
        return Long.MIN_VALUE;
    }

    /**
     * Trade timing at {@code tick}: ticks from your last hit dealt to the last hit taken (both at or before
     * {@code tick}); positive = you hit first. {@code Integer.MIN_VALUE} when either is missing.
     */
    public int tradeGap(long tick) {
        long dealt = lastEventAtOrBefore(EV_HIT_DEALT, tick);
        long taken = lastEventAtOrBefore(EV_HIT_TAKEN, tick);
        if (dealt == Long.MIN_VALUE || taken == Long.MIN_VALUE) {
            return Integer.MIN_VALUE;
        }
        return (int) (taken - dealt);
    }

    /** Copies the {@code entityId} track (one sample per frame where present) into {@code out}; returns samples. */
    public int copyTrack(int entityId, int fromFrame, int toFrame, double[] xs, double[] ys, double[] zs) {
        int n = 0;
        for (int f = Math.max(0, fromFrame); f <= Math.min(size - 1, toFrame) && n < xs.length; f++) {
            int s = find(f, entityId);
            if (s < 0) {
                continue;
            }
            int i = at(f, s);
            xs[n] = px[i];
            ys[n] = py[i];
            zs[n] = pz[i];
            n++;
        }
        return n;
    }
}
