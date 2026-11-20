package com.utilitysuite.combat.assistant;

import java.util.Set;

/**
 * Classifies a damage type id (as carried by the clientbound damage event) for attribution. Pure Java.
 * <p>
 * Only {@link Kind#ENTITY_ATTACK} types can ever start an engagement, and only together with a resolvable cause
 * entity. Environmental types (fall, fire, lava, drowning, suffocation, ...) never do, even when the server attached
 * an entity. Unknown (e.g. modded) types are treated as ambiguous: the assistant stays dormant rather than guess.
 */
public final class DamageClassifier {

    public enum Kind { ENVIRONMENTAL, ENTITY_ATTACK, UNKNOWN }

    private static final Set<String> ENVIRONMENTAL = Set.of(
            "in_fire", "campfire", "lightning_bolt", "on_fire", "lava", "hot_floor", "in_wall", "cramming", "drown",
            "starve", "cactus", "fall", "ender_pearl", "fly_into_wall", "out_of_world", "generic", "magic", "wither",
            "dragon_breath", "dry_out", "sweet_berry_bush", "freeze", "stalagmite", "falling_block", "falling_anvil",
            "falling_stalactite", "bad_respawn_point", "outside_border", "generic_kill", "unattributed_fireball");

    private static final Set<String> ENTITY_ATTACK = Set.of(
            "mob_attack", "mob_attack_no_aggro", "player_attack", "arrow", "trident", "mob_projectile", "spit",
            "wind_charge", "fireworks", "fireball", "wither_skull", "thrown", "indirect_magic", "thorns", "explosion",
            "player_explosion", "sonic_boom", "sting", "mace_smash");

    private DamageClassifier() {
    }

    /** @param typeId registry id such as {@code minecraft:fall} or {@code fall}; null counts as unknown */
    public static Kind kind(String typeId) {
        if (typeId == null) {
            return Kind.UNKNOWN;
        }
        String path = typeId.startsWith("minecraft:") ? typeId.substring(10) : typeId;
        if (path.equals(typeId) && typeId.indexOf(':') >= 0) {
            return Kind.UNKNOWN; // another namespace (modded damage type)
        }
        if (ENVIRONMENTAL.contains(path)) {
            return Kind.ENVIRONMENTAL;
        }
        return ENTITY_ATTACK.contains(path) ? Kind.ENTITY_ATTACK : Kind.UNKNOWN;
    }
}
