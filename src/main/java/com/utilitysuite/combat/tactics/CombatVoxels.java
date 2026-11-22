package com.utilitysuite.combat.tactics;

/**
 * Block kinds as the combat planning sees them: enough to read an arena (floors, ceilings, drops, hazards) and to stop
 * a simulated projectile. Engine free; the Minecraft implementation is {@code combat.assistant.mc.ArenaVoxels}, tests
 * use lambdas or arrays. Implementations must not allocate. Client thread only.
 */
public interface CombatVoxels {

    /** Nothing a body or projectile collides with (air, grass, flowers...). */
    int AIR = 0;
    /** Collides and can be stood on. */
    int SOLID = 1;
    /** Lava (lethal floor; also the void below the world). */
    int LAVA = 2;
    /** Damages on contact: fire, magma, cactus, sweet berries, campfire. */
    int HARM = 3;
    /** Cobweb: stops a body cold. */
    int COBWEB = 4;
    /** Solid but slows walking: soul sand, honey, slime (a bad place to fight from). */
    int SLOW = 5;
    /** Water (open, breaks falls, slows). */
    int WATER = 6;
    /** Not loaded or outside the world: unknown, never treated as safe ground. */
    int UNKNOWN = 7;

    int kind(int x, int y, int z);

    /** True for kinds a body stands on. */
    static boolean standable(int kind) {
        return kind == SOLID || kind == SLOW;
    }
}
