package com.utilitysuite.core.api.visual;

/**
 * A fixed table of things in the world the Shell may put a reticle or tag on. Modules claim a slot once, write it
 * on tick while the thing matters and clear it when it does not; the Shell reads the table every frame. A mark adds
 * no information the publishing module did not already have under its own rules (line of sight included).
 * Client thread only; every method is allocation free.
 */
public interface IWorldMarks {

    int SLOTS = 16;
    /** Flag: the publisher currently has no line of sight (drawn dashed and dim). */
    int FLAG_NO_LOS = 1;
    /** Flag: lethal or blast threat (drawn in the danger hue). */
    int FLAG_LETHAL = 2;
    /** Flag: draw the name and readout tag next to the mark. */
    int FLAG_TAG = 4;

    /** Claims a free slot for a module (constant id), or -1 when the table is full. The claim lasts for the session. */
    int claim(String ownerId);

    /** Marks an entity by its network id. Health and charge are 0..1, or -1 for "not shown". */
    void setEntity(int slot, MarkKind kind, int entityId, float health01, float charge01, float distance,
                   String label, String stateWord, int flags);

    /** Marks a box in world coordinates (a landing top, an objective). */
    void setBox(int slot, MarkKind kind, double minX, double minY, double minZ, double maxX, double maxY,
                double maxZ, String label, int flags);

    /** +1 / -1 for the side a tracked target is expected to move to, 0 for none (drawn as a chevron). */
    void setSide(int slot, int side);

    void clear(int slot);

    // ---- read side (the Shell) --------------------------------------------------------------------------------------

    boolean occupied(int slot);

    /** Changes whenever a slot goes from empty to occupied (drives the lock-on transition). */
    int generation(int slot);

    MarkKind kind(int slot);

    /** Network id of the marked entity, or -1 for a box mark. */
    int entityId(int slot);

    /** Box corner component 0..5 (minX, minY, minZ, maxX, maxY, maxZ) of a box mark. */
    double box(int slot, int component);

    float health(int slot);

    float charge(int slot);

    float distance(int slot);

    String label(int slot);

    String stateWord(int slot);

    int flags(int slot);

    int side(int slot);
}
