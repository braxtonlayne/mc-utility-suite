package com.utilitysuite.world.trial;

/**
 * Engine-free classification of Trial Chamber blocks from their block-state values (the {@code state} property's
 * serialized name and the {@code ominous} flag). The result is a small packed int: kind | status | ominous bit.
 * <p>
 * Vault states (1.21.1): inactive, active, unlocking, ejecting. A vault is "active" while some nearby player can still
 * open it; the per-player reward list is server-only, so "looted by you" is inferred: standing within the activation
 * range of a vault that stays inactive while no other player is near means you already opened it (or the key tracker
 * saw you open it).
 * Trial spawner states: inactive, waiting_for_players, active, waiting_for_reward_ejection, ejecting_reward, cooldown.
 */
public final class TrialBlocks {

    public static final int KIND_NONE = 0;
    public static final int KIND_VAULT = 1;
    public static final int KIND_SPAWNER = 2;
    private static final int KIND_MASK = 0x3;

    public static final int STATUS_INACTIVE = 0;
    /** Vault: can be opened now. Spawner: waiting for players or fighting. */
    public static final int STATUS_ACTIVE = 1 << 2;
    /** Vault: unlocking/ejecting. Spawner: waiting for or ejecting its reward. */
    public static final int STATUS_REWARD = 2 << 2;
    /** Spawner cooldown (30 min after a cleared wave). */
    public static final int STATUS_COOLDOWN = 3 << 2;
    /** Vault this player already opened. */
    public static final int STATUS_LOOTED = 4 << 2;
    private static final int STATUS_MASK = 0x7 << 2;

    public static final int OMINOUS = 1 << 5;

    /** Vault activation range (VaultConfig default 4.0); inside it a vault you may still open turns active. */
    public static final double VAULT_ACTIVATION_RANGE = 4.0;
    /** Inference range: a bit inside the activation range so a step at the edge does not flip it. */
    public static final double LOOT_INFERENCE_RANGE = 3.0;

    private TrialBlocks() {
    }

    /** Kind from a block id ("minecraft:vault", "trial_spawner"). */
    public static int kindOf(String blockId) {
        String path = blockId.indexOf(':') >= 0 ? blockId.substring(blockId.indexOf(':') + 1) : blockId;
        return switch (path) {
            case "vault" -> KIND_VAULT;
            case "trial_spawner" -> KIND_SPAWNER;
            default -> KIND_NONE;
        };
    }

    /** Packed classification; {@code looted} marks a vault known to be opened by this player. */
    public static int classify(int kind, String state, boolean ominous, boolean looted) {
        if (kind == KIND_NONE) {
            return 0;
        }
        int status;
        if (kind == KIND_VAULT) {
            status = switch (state) {
                case "active" -> STATUS_ACTIVE;
                case "unlocking", "ejecting" -> STATUS_REWARD;
                default -> STATUS_INACTIVE;
            };
            if (looted && status != STATUS_REWARD) {
                status = STATUS_LOOTED;
            }
        } else {
            status = switch (state) {
                case "waiting_for_players", "active" -> STATUS_ACTIVE;
                case "waiting_for_reward_ejection", "ejecting_reward" -> STATUS_REWARD;
                case "cooldown" -> STATUS_COOLDOWN;
                default -> STATUS_INACTIVE;
            };
        }
        return kind | status | (ominous ? OMINOUS : 0);
    }

    public static int kind(int code) {
        return code & KIND_MASK;
    }

    public static int status(int code) {
        return code & STATUS_MASK;
    }

    public static boolean ominous(int code) {
        return (code & OMINOUS) != 0;
    }

    /**
     * True when a vault in state {@code state} tells us this player already opened it: the player stands inside the
     * inference range, no other player is near enough to keep it lit, and it is still inactive.
     */
    public static boolean inferLooted(String state, double playerDistSq, boolean otherPlayerNear) {
        return "inactive".equals(state) && !otherPlayerNear
                && playerDistSq <= LOOT_INFERENCE_RANGE * LOOT_INFERENCE_RANGE;
    }

    /** ARGB outline colour: ominous = red/orange family, normal = gold/teal family; spent or idle states are dimmed. */
    public static int color(int code) {
        boolean om = ominous(code);
        return switch (status(code)) {
            case STATUS_ACTIVE -> om ? 0xFFFF4040 : 0xFF40E0FF;
            case STATUS_REWARD -> 0xFFFFE040;
            case STATUS_COOLDOWN -> om ? 0x80A03030 : 0x80307090;
            case STATUS_LOOTED -> 0x80808080;
            default -> om ? 0xC0C06030 : 0xC0A0A0A0;
        };
    }

    /** Short label for the overlay text (static strings, no allocation). */
    public static String label(int code) {
        boolean vault = kind(code) == KIND_VAULT;
        boolean om = ominous(code);
        return switch (status(code)) {
            case STATUS_ACTIVE -> vault ? (om ? "Ominous vault: open" : "Vault: open")
                    : (om ? "Ominous spawner: active" : "Trial spawner: active");
            case STATUS_REWARD -> vault ? "Vault: unlocking" : "Spawner: reward";
            case STATUS_COOLDOWN -> om ? "Ominous spawner: cooldown" : "Trial spawner: cooldown";
            case STATUS_LOOTED -> om ? "Ominous vault: looted" : "Vault: looted";
            default -> vault ? (om ? "Ominous vault" : "Vault") : (om ? "Ominous spawner" : "Trial spawner");
        };
    }
}
