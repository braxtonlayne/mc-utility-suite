package com.utilitysuite.core.api.visual;

/** The one {@link IWorldMarks} table: primitive arrays, no allocation after construction. */
public final class WorldMarkTable implements IWorldMarks {

    /** Shared by every module and the Shell, with or without a registered {@link IVisuals}. */
    public static final WorldMarkTable SHARED = new WorldMarkTable();

    private static final MarkKind[] KINDS = MarkKind.values();

    private final String[] owner = new String[SLOTS];
    private final boolean[] used = new boolean[SLOTS];
    private final int[] generation = new int[SLOTS];
    private final byte[] kind = new byte[SLOTS];
    private final int[] entity = new int[SLOTS];
    private final double[] box = new double[SLOTS * 6];
    private final float[] health = new float[SLOTS];
    private final float[] charge = new float[SLOTS];
    private final float[] distance = new float[SLOTS];
    private final String[] label = new String[SLOTS];
    private final String[] word = new String[SLOTS];
    private final int[] flags = new int[SLOTS];
    private final int[] side = new int[SLOTS];

    @Override
    public int claim(String ownerId) {
        for (int i = 0; i < SLOTS; i++) {
            if (ownerId.equals(owner[i])) {
                return i;
            }
        }
        for (int i = 0; i < SLOTS; i++) {
            if (owner[i] == null) {
                owner[i] = ownerId;
                return i;
            }
        }
        return -1;
    }

    private boolean begin(int slot, MarkKind k) {
        if (slot < 0 || slot >= SLOTS) {
            return false;
        }
        if (!used[slot]) {
            used[slot] = true;
            generation[slot]++;
            side[slot] = 0;
        }
        kind[slot] = (byte) k.ordinal();
        return true;
    }

    @Override
    public void setEntity(int slot, MarkKind k, int entityId, float health01, float charge01, float dist,
                          String text, String stateWord, int f) {
        if (!begin(slot, k)) {
            return;
        }
        entity[slot] = entityId;
        health[slot] = health01;
        charge[slot] = charge01;
        distance[slot] = dist;
        label[slot] = text;
        word[slot] = stateWord;
        flags[slot] = f;
    }

    @Override
    public void setBox(int slot, MarkKind k, double minX, double minY, double minZ, double maxX, double maxY,
                       double maxZ, String text, int f) {
        if (!begin(slot, k)) {
            return;
        }
        int o = slot * 6;
        box[o] = minX;
        box[o + 1] = minY;
        box[o + 2] = minZ;
        box[o + 3] = maxX;
        box[o + 4] = maxY;
        box[o + 5] = maxZ;
        entity[slot] = -1;
        health[slot] = -1f;
        charge[slot] = -1f;
        distance[slot] = -1f;
        label[slot] = text;
        word[slot] = null;
        flags[slot] = f;
    }

    @Override
    public void setSide(int slot, int s) {
        if (slot >= 0 && slot < SLOTS) {
            side[slot] = s;
        }
    }

    @Override
    public void clear(int slot) {
        if (slot >= 0 && slot < SLOTS) {
            used[slot] = false;
            label[slot] = null;
            word[slot] = null;
        }
    }

    /** Clears every mark and claim (world unload, tests). */
    public void reset() {
        for (int i = 0; i < SLOTS; i++) {
            clear(i);
            owner[i] = null;
        }
    }

    @Override
    public boolean occupied(int slot) {
        return slot >= 0 && slot < SLOTS && used[slot];
    }

    @Override
    public int generation(int slot) {
        return generation[slot];
    }

    @Override
    public MarkKind kind(int slot) {
        return KINDS[kind[slot]];
    }

    @Override
    public int entityId(int slot) {
        return entity[slot];
    }

    @Override
    public double box(int slot, int component) {
        return box[slot * 6 + component];
    }

    @Override
    public float health(int slot) {
        return health[slot];
    }

    @Override
    public float charge(int slot) {
        return charge[slot];
    }

    @Override
    public float distance(int slot) {
        return distance[slot];
    }

    @Override
    public String label(int slot) {
        return label[slot];
    }

    @Override
    public String stateWord(int slot) {
        return word[slot];
    }

    @Override
    public int flags(int slot) {
        return flags[slot];
    }

    @Override
    public int side(int slot) {
        return side[slot];
    }
}
