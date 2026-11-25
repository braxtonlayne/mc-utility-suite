package com.utilitysuite.tools.villager;

/**
 * Engine-free matching of a librarian's enchanted-book offer against the roller's target (enchantment id, minimum
 * level, maximum emerald price). Ids are compared namespaced: {@code mending} means {@code minecraft:mending}.
 */
public final class BookOfferMatch {

    private BookOfferMatch() {
    }

    /** {@code minecraft:} prefix for bare ids; trims and lower-cases. Empty for null/blank. */
    public static String normalize(String id) {
        if (id == null) {
            return "";
        }
        String s = id.trim().toLowerCase(java.util.Locale.ROOT);
        if (s.isEmpty()) {
            return "";
        }
        return s.indexOf(':') < 0 ? "minecraft:" + s : s;
    }

    /**
     * True when one book offer matches the target.
     *
     * @param offerEnchantId namespaced id of the book's stored enchantment
     * @param offerLevel     its level
     * @param emeraldPrice   emeralds asked (after price modifiers); a negative value means the price is not in emeralds
     * @param target         normalized target id (see {@link #normalize})
     * @param maxPrice       highest acceptable emerald price; 0 or less = any price
     */
    public static boolean matches(String offerEnchantId, int offerLevel, int emeraldPrice, String target, int minLevel,
                                  int maxPrice) {
        if (target == null || target.isEmpty() || offerEnchantId == null) {
            return false;
        }
        if (!target.equals(offerEnchantId)) {
            return false;
        }
        if (offerLevel < Math.max(1, minLevel)) {
            return false;
        }
        if (maxPrice > 0 && (emeraldPrice < 0 || emeraldPrice > maxPrice)) {
            return false;
        }
        return true;
    }
}
