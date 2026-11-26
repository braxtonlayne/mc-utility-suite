package com.utilitysuite.tools.inventory;

import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.food.FoodProperties;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.ShieldItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.Level;

import java.util.Arrays;

/**
 * Minecraft adapter: fills the engine-agnostic {@link InventoryModel} and container arrays from the live inventory.
 * Client thread only. Unpatched stacks use the raw item id as their key; stacks with components (enchanted, named,
 * damaged) get a negative item+components hash, so they only merge with identical stacks.
 */
final class McInventory {

    /** Reusable container scratch (a double chest has 54 slots). */
    final int[] cItem = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cKey = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cCount = new int[DepositPlanner.MAX_CONTAINER];
    final int[] cMax = new int[DepositPlanner.MAX_CONTAINER];
    final int[] menuSlotOfInv = new int[InventoryModel.SLOTS];
    int containerSlots;

    private Level protectionLevelOwner;
    private Holder<Enchantment> protection;

    static int itemId(Item item) {
        return BuiltInRegistries.ITEM.getId(item);
    }

    static int keyOf(ItemStack stack) {
        if (stack.isComponentsPatchEmpty()) {
            return itemId(stack.getItem());
        }
        return ItemStack.hashItemAndComponents(stack) | 0x80000000;
    }

    static int flagsOf(ItemStack stack) {
        Item item = stack.getItem();
        int f = 0;
        if (item instanceof BlockItem) {
            f |= InventoryModel.FLAG_BLOCK;
        }
        if (stack.has(DataComponents.FOOD)) {
            f |= InventoryModel.FLAG_FOOD;
        }
        if (item == Items.ENDER_PEARL) {
            f |= InventoryModel.FLAG_PEARL;
        }
        if (item == Items.FIREWORK_ROCKET) {
            f |= InventoryModel.FLAG_ROCKET;
        }
        return f;
    }

    /** Fills the 36 inventory slots (and armor scoring when {@code withArmor}). */
    void read(Player player, InventoryModel model, boolean withArmor) {
        Inventory inv = player.getInventory();
        for (int s = 0; s < InventoryModel.SLOTS; s++) {
            ItemStack st = inv.getNonEquipmentItems().get(s);
            if (st.isEmpty()) {
                model.set(s, 0, 0, 0, 0, 0);
                continue;
            }
            model.set(s, itemId(st.getItem()), keyOf(st), st.getCount(), st.getMaxStackSize(), flagsOf(st));
            EquipmentSlot armorSlot = withArmor ? GearFacts.armorSlot(st) : null;
            if (armorSlot != null) {
                model.armorType[s] = armorSlot.getIndex();
                model.armorScore[s] = candidateScore(player.level(), st, armorSlot);
            }
        }
        if (withArmor) {
            for (int a = 0; a < InventoryModel.ARMOR_SLOTS; a++) {
                ItemStack st = inv.getItem(36 + a);
                model.equippedCount[a] = st.getCount();
                EquipmentSlot armorSlot = GearFacts.armorSlot(st);
                boolean isArmor = armorSlot != null;
                model.equippedIsArmor[a] = isArmor;
                model.equippedLocked[a] = !st.isEmpty()
                        && EnchantmentHelper.has(st, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
                model.equippedScore[a] = isArmor ? rawScore(player.level(), st, armorSlot) : 0;
            }
        }
    }

    /**
     * Fills the item class and ranking score of the 36 inventory slots. Tools and weapons score by material tier, then
     * enchantment count, then remaining durability (under 10% left is -1: never used as a replacement); food by
     * nutrition and saturation (foods with side effects are not a class); blocks by stack size. No allocation.
     */
    void readClasses(Player player, HotbarRefillPlanner.ItemClasses out) {
        Inventory inv = player.getInventory();
        for (int s = 0; s < InventoryModel.SLOTS; s++) {
            ItemStack st = inv.getNonEquipmentItems().get(s);
            if (st.isEmpty()) {
                out.code[s] = HotbarRefillPlanner.ItemClasses.NONE;
                out.score[s] = 0;
                continue;
            }
            Item item = st.getItem();
            int cls = HotbarRefillPlanner.ItemClasses.NONE;
            int score = 0;
            if (st.is(ItemTags.PICKAXES)) {
                cls = HotbarRefillPlanner.ItemClasses.PICKAXE;
            } else if (st.is(ItemTags.AXES)) {
                cls = HotbarRefillPlanner.ItemClasses.AXE;
            } else if (st.is(ItemTags.SHOVELS)) {
                cls = HotbarRefillPlanner.ItemClasses.SHOVEL;
            } else if (st.is(ItemTags.HOES)) {
                cls = HotbarRefillPlanner.ItemClasses.HOE;
            } else if (st.is(ItemTags.SWORDS)) {
                cls = HotbarRefillPlanner.ItemClasses.SWORD;
            } else if (item instanceof BowItem) {
                cls = HotbarRefillPlanner.ItemClasses.BOW;
            } else if (item instanceof ShieldItem) {
                cls = HotbarRefillPlanner.ItemClasses.SHIELD;
            } else if (item == Items.TOTEM_OF_UNDYING) {
                cls = HotbarRefillPlanner.ItemClasses.TOTEM;
            } else {
                FoodProperties fp = st.get(DataComponents.FOOD);
                if (fp != null) {
                    if (!GearFacts.hasConsumeEffects(st)) { // side effects live on the consumable now
                        cls = HotbarRefillPlanner.ItemClasses.FOOD;
                        score = fp.nutrition() * 10 + (int) (fp.saturation() * 10f);
                    }
                } else if (item instanceof BlockItem) {
                    cls = HotbarRefillPlanner.ItemClasses.BLOCKS;
                    score = st.getCount();
                }
            }
            if (cls >= HotbarRefillPlanner.ItemClasses.PICKAXE && cls <= HotbarRefillPlanner.ItemClasses.SHIELD) {
                score = gearScore(st, item);
            }
            out.code[s] = cls;
            out.score[s] = score;
        }
    }

    private static int gearScore(ItemStack st, Item item) {
        int max = st.getMaxDamage();
        int durability = 9;
        if (st.isDamageableItem() && max > 0) {
            int left = max - st.getDamageValue();
            if (left * 10 < max) {
                return -1; // nearly broken
            }
            durability = left * 9 / max;
        }
        int tier = GearFacts.tierUses(st) / 10; // tool material durability (was Tier.getUses())
        return tier * 100 + st.getEnchantments().size() * 10 + durability;
    }

    private int candidateScore(Level level, ItemStack st, EquipmentSlot armor) {
        if (EnchantmentHelper.has(st, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE)) {
            return -1;
        }
        if (st.isDamageableItem() && st.getMaxDamage() > 0
                && (st.getMaxDamage() - st.getDamageValue()) * 10 < st.getMaxDamage()) {
            return -1; // under 10% durability left
        }
        return rawScore(level, st, armor);
    }

    private int rawScore(Level level, ItemStack st, EquipmentSlot armor) {
        int prot = 0;
        Holder<Enchantment> p = protection(level);
        if (p != null) {
            prot = EnchantmentHelper.getItemEnchantmentLevel(p, st);
        }
        return ArmorRanker.score(GearFacts.defense(st, armor), GearFacts.toughness(st, armor), prot);
    }

    private Holder<Enchantment> protection(Level level) {
        if (level != protectionLevelOwner) {
            protectionLevelOwner = level;
            protection = level == null ? null : level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT)
                    .get(Enchantments.PROTECTION).orElse(null);
        }
        return protection;
    }

    /**
     * Fills the container arrays and {@link #menuSlotOfInv} from an open menu: every slot backed by another container
     * is a container slot; slots backed by the player's inventory map back to their inventory index.
     */
    void readMenu(Player player, AbstractContainerMenu menu) {
        Arrays.fill(menuSlotOfInv, -1);
        Inventory inv = player.getInventory();
        int n = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            Slot slot = menu.slots.get(i);
            if (slot.container == inv) {
                int idx = slot.getContainerSlot();
                if (idx >= 0 && idx < InventoryModel.SLOTS) {
                    menuSlotOfInv[idx] = i;
                }
                continue;
            }
            if (n >= DepositPlanner.MAX_CONTAINER) {
                continue;
            }
            ItemStack st = slot.getItem();
            if (st.isEmpty()) {
                cItem[n] = 0;
                cKey[n] = 0;
                cCount[n] = 0;
                cMax[n] = 64;
            } else {
                cItem[n] = itemId(st.getItem());
                cKey[n] = keyOf(st);
                cCount[n] = st.getCount();
                cMax[n] = Math.min(st.getMaxStackSize(), slot.getMaxStackSize(st));
            }
            n++;
        }
        containerSlots = n;
    }
}
