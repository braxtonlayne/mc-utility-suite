package com.utilitysuite.tools.enchant;

import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.EnchantmentTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.EnchantmentInstance;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;

/**
 * Reproduces the vanilla 26.3 enchanting-table roll (EnchantmentMenu#slotsChanged / #getEnchantmentList) for a known
 * player enchantment seed, using the vanilla {@link EnchantmentHelper} maths directly. Bookshelves count as one power
 * each (modded shelves with other bonuses and NeoForge's level-set event are not modelled). Client thread, on demand.
 */
public final class EnchantTableCalc {

    private EnchantTableCalc() {
    }

    /** Offers for bookshelf counts 0..maxShelves, three slots each. Empty when the item cannot be enchanted. */
    public static List<EnchantPlanner.Offer> offers(RegistryAccess access, ItemStack stack, int seed, int maxShelves) {
        List<EnchantPlanner.Offer> out = new ArrayList<>();
        if (stack.isEmpty() || !stack.isEnchantable()) {
            return out;
        }
        Optional<HolderSet.Named<Enchantment>> pool = access.lookupOrThrow(Registries.ENCHANTMENT)
                .get(EnchantmentTags.IN_ENCHANTING_TABLE);
        RandomSource random = RandomSource.create();
        int[] costs = new int[3];
        for (int shelves = 0; shelves <= maxShelves; shelves++) {
            random.setSeed(seed);
            for (int k = 0; k < 3; k++) {
                costs[k] = EnchantmentHelper.getEnchantmentCost(random, k, shelves, stack);
                if (costs[k] < k + 1) {
                    costs[k] = 0;
                }
            }
            for (int slot = 0; slot < 3; slot++) {
                List<EnchantPlanner.Ench> list = new ArrayList<>();
                if (costs[slot] > 0 && pool.isPresent()) {
                    random.setSeed((long) seed + slot);
                    List<EnchantmentInstance> rolled = EnchantmentHelper.selectEnchantment(random, stack, costs[slot],
                            pool.get().stream());
                    if (stack.is(Items.BOOK) && rolled.size() > 1) {
                        rolled.remove(random.nextInt(rolled.size()));
                    }
                    for (EnchantmentInstance e : rolled) {
                        list.add(new EnchantPlanner.Ench(idOf(e.enchantment()), e.level()));
                    }
                }
                out.add(new EnchantPlanner.Offer(shelves, slot, costs[slot], list));
            }
        }
        return out;
    }

    /**
     * Second cracker stage: does {@code seed} at bookshelf {@code power} reproduce the table's shown hints (the clue
     * enchantment network id and level per slot, -1 when hidden)? Mirrors EnchantmentMenu#slotsChanged exactly,
     * including the extra nextInt that picks the shown clue from the rolled list.
     */
    public static boolean matchesClues(RegistryAccess access, ItemStack stack, int seed, int power, int[] costs,
                                       int[] enchClue, int[] levelClue) {
        var registry = access.lookupOrThrow(Registries.ENCHANTMENT);
        Optional<HolderSet.Named<Enchantment>> pool = registry.get(EnchantmentTags.IN_ENCHANTING_TABLE);
        var idMap = registry.asHolderIdMap();
        RandomSource random = RandomSource.create();
        for (int slot = 0; slot < 3; slot++) {
            if (costs[slot] <= 0 || enchClue[slot] < 0) {
                continue;
            }
            if (pool.isEmpty()) {
                return false;
            }
            random.setSeed((long) seed + slot);
            List<EnchantmentInstance> rolled = EnchantmentHelper.selectEnchantment(random, stack, costs[slot],
                    pool.get().stream());
            if (stack.is(Items.BOOK) && rolled.size() > 1) {
                rolled.remove(random.nextInt(rolled.size()));
            }
            if (rolled.isEmpty()) {
                return false;
            }
            EnchantmentInstance shown = rolled.get(random.nextInt(rolled.size()));
            if (idMap.getId(shown.enchantment()) != enchClue[slot] || shown.level() != levelClue[slot]) {
                return false;
            }
        }
        return true;
    }

    /** Enchantments the table can ever roll on this item (target picker choices), sorted by id. */
    public static List<String> choices(RegistryAccess access, ItemStack stack) {
        TreeSet<String> ids = new TreeSet<>();
        if (stack.isEmpty() || !stack.isEnchantable()) {
            return List.of();
        }
        Optional<HolderSet.Named<Enchantment>> pool = access.lookupOrThrow(Registries.ENCHANTMENT)
                .get(EnchantmentTags.IN_ENCHANTING_TABLE);
        if (pool.isEmpty()) {
            return List.of();
        }
        // the table's modified level tops out around 50 for 15 shelves and high enchantability
        for (int level = 1; level <= 60; level++) {
            for (EnchantmentInstance e : EnchantmentHelper.getAvailableEnchantmentResults(level, stack,
                    pool.get().stream())) {
                ids.add(idOf(e.enchantment()));
            }
        }
        return List.copyOf(ids);
    }

    private static String idOf(Holder<Enchantment> h) {
        return h.unwrapKey().map(k -> k.identifier().toString()).orElse("unknown:unknown");
    }
}
