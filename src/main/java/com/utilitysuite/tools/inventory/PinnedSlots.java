package com.utilitysuite.tools.inventory;

/**
 * Pinned hotbar slots as a 9-bit mask. Stored in settings as the user-facing slot numbers 1-9, e.g. {@code "1,2,9"}.
 * Pinned slots are never overwritten with another item and never dumped into a container.
 */
public final class PinnedSlots {

    private PinnedSlots() {
    }

    /** Parses "1,2,9" (spaces and junk ignored, numbers outside 1-9 ignored). */
    public static int parse(String text) {
        if (text == null) {
            return 0;
        }
        int mask = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '1' && c <= '9') {
                boolean standalone = (i + 1 >= text.length() || !Character.isDigit(text.charAt(i + 1)))
                        && (i == 0 || !Character.isDigit(text.charAt(i - 1)));
                if (standalone) {
                    mask |= 1 << (c - '1');
                }
            }
        }
        return mask;
    }

    /** Formats a mask as "1,2,9" (empty string for none). */
    public static String format(int mask) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < InventoryModel.HOTBAR; i++) {
            if ((mask & (1 << i)) != 0) {
                if (sb.length() > 0) {
                    sb.append(',');
                }
                sb.append(i + 1);
            }
        }
        return sb.toString();
    }

    public static boolean isPinned(int mask, int hotbarSlot) {
        return hotbarSlot >= 0 && hotbarSlot < InventoryModel.HOTBAR && (mask & (1 << hotbarSlot)) != 0;
    }

    public static int toggle(int mask, int hotbarSlot) {
        return mask ^ (1 << hotbarSlot);
    }
}
