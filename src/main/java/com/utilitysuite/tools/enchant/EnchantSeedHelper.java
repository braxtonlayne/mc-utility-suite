package com.utilitysuite.tools.enchant;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.server.level.ServerPlayer;
import it.unimi.dsi.fastutil.ints.IntLinkedOpenHashSet;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enchanting-table helper (single player): reads the player's enchantment seed from the integrated server, rolls what
 * the table offers for the held item at every bookshelf count, and advises the cheapest way to the wanted enchantment
 * (or one cheap dummy enchant to reroll the seed). Pure read-only advice: it never clicks anything. Work happens only
 * when the user presses Refresh (no ticking); results are published as an immutable {@link Snapshot}.
 */
public final class EnchantSeedHelper implements IModule, IConfigurable {

    public static final String ID = "enchant_seed_helper";
    public static final String K_MAX_SHELVES = "max_bookshelves";
    public static final String K_MIN_LEVEL = "min_level";

    /** Immutable result of one refresh. */
    public record Snapshot(String status, String item, int seed, List<EnchantPlanner.Offer> offers,
                           List<String> choices, int playerLevel) {
        public Snapshot {
            offers = List.copyOf(offers);
            choices = List.copyOf(choices);
        }

        static Snapshot message(String status) {
            return new Snapshot(status, "", 0, List.of(), List.of(), 0);
        }
    }

    private boolean enabled;
    private int maxShelves = 15;
    private int minLevel = 1;
    private String targetId = "";
    private volatile Snapshot snapshot = Snapshot.message("Hold the item to enchant and press Refresh");

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    private final int[] lastSeen = new int[10];
    private int stableTicks;

    /**
     * Remote cracker trigger only: while an enchanting table is open on a remote server, waits for its hints to settle
     * (they arrive as separate data packets) and records one observation per distinct table state. Allocation-free
     * unless an observation is taken.
     */
    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getSingleplayerServer() != null
                || !(mc.player.containerMenu instanceof EnchantmentMenu menu)) {
            stableTicks = 0;
            return;
        }
        boolean changed = false;
        for (int i = 0; i < 3; i++) {
            changed |= swap(i, menu.costs[i]) | swap(3 + i, menu.enchantClue[i]) | swap(6 + i, menu.levelClue[i]);
        }
        changed |= swap(9, menu.getEnchantmentSeed() & 0xFFFF);
        if (changed) {
            stableTicks = 0;
            return;
        }
        if (stableTicks >= 5) {
            return;
        }
        if (++stableTicks == 5 && menu.enchantClue[0] >= 0) {
            if (cracking) {
                stableTicks = 4; // previous observation still searching: retry next tick
            } else {
                crackFromOpenMenu();
            }
        }
    }

    private boolean swap(int i, int v) {
        if (lastSeen[i] == v) {
            return false;
        }
        lastSeen[i] = v;
        return true;
    }

    @Override
    public void onWorldUnload() {
        candidates = null;
        candidateLow16 = -1;
        crackedSeed = null;
        crackStatus = "no observations";
        snapshot = Snapshot.message("Hold the item to enchant and press Refresh");
    }

    @Override
    public String displayName() {
        return "Enchant Planner";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer(K_MAX_SHELVES, "Max bookshelves", 15, 0, 15, "",
                        "Plan only with bookshelf counts up to this (remove shelves or block them with torches to"
                                + " lower the count)."),
                SettingSpec.integer(K_MIN_LEVEL, "Wanted level (min)", 1, 1, 5, "",
                        "Lowest level of the wanted enchantment that counts as a match."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        maxShelves = p.getInt(ID, K_MAX_SHELVES, 15);
        minLevel = p.getInt(ID, K_MIN_LEVEL, 1);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    public Snapshot snapshot() {
        return snapshot;
    }

    public String targetId() {
        return targetId;
    }

    public void setTargetId(String id) {
        targetId = id == null ? "" : id;
    }

    public int minLevel() {
        return minLevel;
    }

    public int maxShelves() {
        return maxShelves;
    }

    // ---- remote cracker (stretch): low 16 bits from the open menu + cost/clue hints --------------------------------

    private static final ExecutorService CRACK_EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "UtilitySuite-EnchantCrack");
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private int[] candidates; // client thread only
    private int candidateLow16 = -1;
    private volatile Integer crackedSeed;
    private volatile String crackStatus = "no observations";
    private volatile boolean cracking;

    public String crackStatus() {
        return crackStatus;
    }

    public boolean isEnchantMenuOpen() {
        Minecraft mc = Minecraft.getInstance();
        return mc.player != null && mc.player.containerMenu instanceof EnchantmentMenu;
    }

    /**
     * Client thread: take one observation from the open enchanting menu (low 16 seed bits, the three costs and the
     * shown hints) and narrow the candidate seeds. The 2^16 x 16 cost scan runs on a background thread; the clue check
     * on the survivors (vanilla registry maths) runs back on the client thread. Observations accumulate until the seed
     * changes (after any enchant), so put different items in or change the bookshelves to narrow further.
     */
    public void crackFromOpenMenu() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || !(mc.player.containerMenu instanceof EnchantmentMenu menu)) {
            crackStatus = "open an enchanting table first";
            return;
        }
        if (cracking) {
            return;
        }
        ItemStack stack = menu.getSlot(0).getItem().copy();
        int[] costs = menu.costs.clone();
        int[] enchClue = menu.enchantClue.clone();
        int[] levelClue = menu.levelClue.clone();
        if (stack.isEmpty() || (costs[0] | costs[1] | costs[2]) == 0) {
            crackStatus = "put an enchantable item in the table";
            return;
        }
        int low16 = menu.getEnchantmentSeed() & 0xFFFF;
        if (low16 != candidateLow16) {
            candidates = null;
            crackedSeed = null;
            candidateLow16 = low16;
        }
        int[] prior = candidates;
        cracking = true;
        crackStatus = "searching";
        var access = mc.level.registryAccess();
        CompletableFuture.supplyAsync(() -> SeedCostFilter.candidates(low16, costs, prior), CRACK_EXECUTOR)
                .whenComplete((pairs, err) -> mc.execute(() -> {
                    cracking = false;
                    if (err != null || pairs == null) {
                        crackStatus = "search failed";
                        return;
                    }
                    IntLinkedOpenHashSet kept = new IntLinkedOpenHashSet();
                    for (int i = 0; i < pairs.size(); i += 2) {
                        int s = pairs.getInt(i);
                        if (!kept.contains(s) && EnchantTableCalc.matchesClues(access, stack, s, pairs.getInt(i + 1),
                                costs, enchClue, levelClue)) {
                            kept.add(s);
                        }
                    }
                    candidates = kept.toIntArray();
                    if (candidates.length == 1) {
                        crackedSeed = candidates[0];
                        crackStatus = "seed found";
                    } else if (candidates.length == 0) {
                        crackedSeed = null;
                        crackStatus = "no seed fits (modded shelves or table?); observations reset";
                        candidates = null;
                    } else {
                        crackedSeed = null;
                        crackStatus = candidates.length + " candidates: change item or bookshelves and Crack again";
                    }
                }));
    }

    /** Current advice, from the last snapshot and the current target. */
    public EnchantPlanner.Plan plan() {
        Snapshot s = snapshot;
        return EnchantPlanner.plan(s.offers(), targetId, minLevel, s.playerLevel());
    }

    /** Client thread: read the seed and roll the table for the held item (about 48 vanilla rolls). */
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            snapshot = Snapshot.message("Not in a world");
            return;
        }
        IntegratedServer server = mc.getSingleplayerServer();
        int seed;
        if (server == null) {
            Integer cracked = crackedSeed;
            if (cracked == null) {
                snapshot = Snapshot.message("Remote server: open an enchanting table with an item in it and press"
                        + " Crack (" + crackStatus + ")");
                return;
            }
            seed = cracked;
        } else {
            ServerPlayer sp = server.getPlayerList().getPlayer(mc.player.getUUID());
            if (sp == null) {
                snapshot = Snapshot.message("Player not found on the integrated server");
                return;
            }
            seed = sp.getEnchantmentSeed(); // a plain int field; changes only when the player enchants
        }
        ItemStack held = mc.player.getMainHandItem();
        if (held.isEmpty() || !held.isEnchantable()) {
            snapshot = new Snapshot("Hold an unenchanted, enchantable item (or a book)", "", seed, List.of(),
                    List.of(), mc.player.experienceLevel);
            return;
        }
        List<EnchantPlanner.Offer> offers = EnchantTableCalc.offers(mc.level.registryAccess(), held, seed, maxShelves);
        List<String> choices = EnchantTableCalc.choices(mc.level.registryAccess(), held);
        if (!targetId.isEmpty() && !choices.contains(targetId)) {
            targetId = "";
        }
        snapshot = new Snapshot("Seed read", held.getHoverName().getString(), seed, offers, choices,
                mc.player.experienceLevel);
    }
}
