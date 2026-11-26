package com.utilitysuite.tools.inventory;

import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.Consumable;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.equipment.Equippable;

/**
 * Item facts that used to come from the removed item classes ({@code ArmorItem}, {@code TieredItem}, the tool
 * classes): armour is an {@link Equippable} for a humanoid armour slot that grants armour or toughness, tools and
 * weapons are item tags, and the old tier durability is the item's max damage. Allocation-free.
 */
public final class GearFacts {

    private GearFacts() {
    }

    /** Humanoid armour slot (FEET..HEAD) of a wearable armour piece, or null (elytra, pumpkins, heads, non-armour). */
    public static EquipmentSlot armorSlot(ItemStack st) {
        if (st.isEmpty()) {
            return null;
        }
        Equippable eq = st.get(DataComponents.EQUIPPABLE);
        if (eq == null || eq.slot().getType() != EquipmentSlot.Type.HUMANOID_ARMOR) {
            return null;
        }
        if (defense(st, eq.slot()) > 0 || toughness(st, eq.slot()) > 0f
                || st.is(ItemTags.HEAD_ARMOR) || st.is(ItemTags.CHEST_ARMOR) || st.is(ItemTags.LEG_ARMOR)
                || st.is(ItemTags.FOOT_ARMOR)) {
            return eq.slot();
        }
        return null;
    }

    /** True when the stack is wearable armour (see {@link #armorSlot}). */
    public static boolean isArmor(ItemStack st) {
        return armorSlot(st) != null;
    }

    /** Armour points the stack grants in {@code slot} (the old {@code ArmorItem.getDefense()}). */
    public static int defense(ItemStack st, EquipmentSlot slot) {
        ItemAttributeModifiers mods = st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return (int) mods.compute(Attributes.ARMOR, 0.0, slot);
    }

    /** Armour toughness the stack grants in {@code slot} (the old {@code ArmorItem.getToughness()}). */
    public static float toughness(ItemStack st, EquipmentSlot slot) {
        ItemAttributeModifiers mods = st.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return (float) mods.compute(Attributes.ARMOR_TOUGHNESS, 0.0, slot);
    }

    /** Pickaxe, axe, shovel, hoe or sword (the old {@code TieredItem} family). */
    public static boolean isTiered(ItemStack st) {
        return st.is(ItemTags.PICKAXES) || st.is(ItemTags.AXES) || st.is(ItemTags.SHOVELS) || st.is(ItemTags.HOES)
                || st.is(ItemTags.SWORDS);
    }

    /**
     * The old {@code Tier.getUses()} for tools and weapons: a tool's max damage is its material's durability. Zero
     * for anything that is not a tiered tool.
     */
    public static int tierUses(ItemStack st) {
        return isTiered(st) ? st.getMaxDamage() : 0;
    }

    /** True when eating the stack applies extra effects (the old {@code FoodProperties.effects()} not empty). */
    public static boolean hasConsumeEffects(ItemStack st) {
        Consumable c = st.get(DataComponents.CONSUMABLE);
        return c != null && !c.onConsumeEffects().isEmpty();
    }
}
