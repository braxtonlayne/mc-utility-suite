package com.utilitysuite.combat.assistant;

/**
 * Projectile spawns waiting for their velocity. On the client a projectile joins the level before its velocity is
 * known: {@code Entity#recreateFromPacket} reads only position and rotation, and the server's motion packet for an
 * arrow, pearl or trident is handled right after the join event (same pairing bundle). So the habit feed parks each
 * spawn here at join and reads the velocity on the next client tick, before the entity has moved.
 * <p>
 * Fixed capacity, primitive arrays, no allocation after construction; when full the oldest entry is dropped.
 * Engine-agnostic (entity ids only) so it is unit testable. Client thread.
 */
public final class PendingSpawns {

    /** Ticks a spawn may wait for a non-zero velocity before it is reported as it is. */
    public static final int MAX_WAIT_TICKS = 2;

    private final int[] id, kind, owner, age;
    private final double[] x, y, z;
    private int size;

    public PendingSpawns(int capacity) {
        id = new int[capacity];
        kind = new int[capacity];
        owner = new int[capacity];
        age = new int[capacity];
        x = new double[capacity];
        y = new double[capacity];
        z = new double[capacity];
    }

    /** Parks a spawn; drops the oldest when full. */
    public void add(int entityId, int projKind, int ownerId, double px, double py, double pz) {
        if (id.length == 0) {
            return;
        }
        if (size == id.length) {
            remove(0);
        }
        int i = size++;
        id[i] = entityId;
        kind[i] = projKind;
        owner[i] = ownerId;
        age[i] = 0;
        x[i] = px;
        y[i] = py;
        z[i] = pz;
    }

    public int size() {
        return size;
    }

    public int id(int i) {
        return id[i];
    }

    public int kind(int i) {
        return kind[i];
    }

    public int owner(int i) {
        return owner[i];
    }

    public double x(int i) {
        return x[i];
    }

    public double y(int i) {
        return y[i];
    }

    public double z(int i) {
        return z[i];
    }

    /**
     * Ages entry {@code i} by one tick and tells whether it should be reported now: at once when its velocity is known
     * (non-zero), otherwise only after {@link #MAX_WAIT_TICKS} (a projectile that really spawned at rest).
     */
    public boolean ready(int i, boolean velocityKnown) {
        return velocityKnown || ++age[i] >= MAX_WAIT_TICKS;
    }

    /** Removes entry {@code i}, keeping the rest in spawn order. */
    public void remove(int i) {
        int tail = size - i - 1;
        if (tail > 0) {
            System.arraycopy(id, i + 1, id, i, tail);
            System.arraycopy(kind, i + 1, kind, i, tail);
            System.arraycopy(owner, i + 1, owner, i, tail);
            System.arraycopy(age, i + 1, age, i, tail);
            System.arraycopy(x, i + 1, x, i, tail);
            System.arraycopy(y, i + 1, y, i, tail);
            System.arraycopy(z, i + 1, z, i, tail);
        }
        size--;
    }

    public void clear() {
        size = 0;
    }
}
