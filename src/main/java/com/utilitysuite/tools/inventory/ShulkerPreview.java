package com.utilitysuite.tools.inventory;

import com.mojang.datafixers.util.Either;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemStackTemplate;
import net.minecraft.world.item.component.ItemContainerContents;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.neoforged.neoforge.client.event.RegisterClientTooltipComponentFactoriesEvent;
import net.neoforged.neoforge.client.event.RenderTooltipEvent;

import java.util.List;
import java.util.function.Supplier;

/**
 * Shulker box tooltip preview: replaces vanilla's "first five items" text list with a clean item grid. The tooltip
 * element, its data and the client component (with its item copies) are cached per contents instance, so hovering the
 * same box allocates nothing per frame beyond what vanilla's tooltip pipeline does.
 */
public final class ShulkerPreview {

    /** Tooltip data: the box's contents. */
    public record Data(ItemContainerContents contents) implements TooltipComponent {
    }

    private static final int COLUMNS = 9;
    private static final int CELL = 18;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int SLOT_DARK = 0xFF373737;
    private static final int SLOT_LIGHT = 0xFFFFFFFF;

    private final Supplier<SmartInventory> module;
    private ItemContainerContents cachedContents;
    private Either<FormattedText, TooltipComponent> cachedElement;
    private boolean cachedHasItems;
    private Data lastData;
    private ClientComponent lastComponent;

    public ShulkerPreview(Supplier<SmartInventory> module) {
        this.module = module;
    }

    /** Mod bus. */
    public void onRegisterFactories(RegisterClientTooltipComponentFactoriesEvent event) {
        event.register(Data.class, this::component);
    }

    private ClientTooltipComponent component(Data data) {
        if (data != lastData) {
            lastData = data;
            lastComponent = new ClientComponent(data.contents());
        }
        return lastComponent;
    }

    /** Game bus. */
    public void onGather(RenderTooltipEvent.GatherComponents event) {
        SmartInventory m = module.get();
        if (m == null || !m.shulkerPreviewEnabled()) {
            return;
        }
        ItemStack stack = event.getItemStack();
        if (!(stack.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) {
            return;
        }
        ItemContainerContents contents = stack.get(DataComponents.CONTAINER);
        if (contents == null) {
            return;
        }
        if (contents != cachedContents) {
            cachedContents = contents;
            cachedHasItems = contents.nonEmptyItems().iterator().hasNext();
            cachedElement = Either.right(new Data(contents));
        }
        if (!cachedHasItems) {
            return;
        }
        List<Either<FormattedText, TooltipComponent>> elements = event.getTooltipElements();
        for (int i = elements.size() - 1; i >= 1; i--) {
            if (isVanillaContentsLine(elements.get(i))) {
                elements.remove(i);
            }
        }
        elements.add(Math.min(1, elements.size()), cachedElement);
    }

    /**
     * Total of the item with raw id {@code itemId} inside the shulker boxes in the main inventory (container
     * component only; nothing is opened). Allocates (iterators): call it only on a rare event, never per tick.
     */
    static int countInShulkers(net.minecraft.world.entity.player.Inventory inv, int itemId) {
        int total = 0;
        for (int s = 0; s < inv.getNonEquipmentItems().size(); s++) {
            ItemStack box = inv.getNonEquipmentItems().get(s);
            if (!(box.getItem() instanceof BlockItem bi) || !(bi.getBlock() instanceof ShulkerBoxBlock)) {
                continue;
            }
            ItemContainerContents contents = box.get(DataComponents.CONTAINER);
            if (contents == null) {
                continue;
            }
            for (ItemStackTemplate in : contents.nonEmptyItems()) {
                if (McInventory.itemId(in.item().value()) == itemId) {
                    total += in.count();
                }
            }
        }
        return total;
    }

    private static boolean isVanillaContentsLine(Either<FormattedText, TooltipComponent> e) {
        FormattedText text = e.left().orElse(null);
        if (text instanceof Component c && c.getContents() instanceof TranslatableContents tc) {
            String key = tc.getKey();
            return key.equals("item.container.item_count") || key.equals("item.container.more_items")
                    || key.equals("container.shulkerBox.itemCount") || key.equals("container.shulkerBox.more");
        }
        return false;
    }

    /** Draws the grid: vanilla-style slot frames with items and counts. */
    static final class ClientComponent implements ClientTooltipComponent {

        private final ItemStack[] items;
        private final int rows;

        ClientComponent(ItemContainerContents contents) {
            int n = contents.getSlots();
            this.items = new ItemStack[n];
            for (int i = 0; i < n; i++) {
                items[i] = contents.getTemplateInSlot(i).map(ItemStackTemplate::create).orElse(ItemStack.EMPTY); // once per box
            }
            this.rows = Math.max(1, (n + COLUMNS - 1) / COLUMNS);
        }

        @Override
        public int getHeight(Font font) {
            return rows * CELL + 4;
        }

        @Override
        public int getWidth(Font font) {
            return COLUMNS * CELL + 2;
        }

        @Override
        public void extractImage(Font font, int x, int y, int w, int h, GuiGraphicsExtractor g) {
            for (int i = 0; i < rows * COLUMNS; i++) {
                int sx = x + 1 + (i % COLUMNS) * CELL;
                int sy = y + 1 + (i / COLUMNS) * CELL;
                g.fill(sx, sy, sx + CELL, sy + CELL, SLOT_DARK);
                g.fill(sx + 1, sy + 1, sx + CELL, sy + CELL, SLOT_LIGHT);
                g.fill(sx + 1, sy + 1, sx + CELL - 1, sy + CELL - 1, SLOT_BG);
                if (i < items.length) {
                    ItemStack st = items[i];
                    if (!st.isEmpty()) {
                        g.item(st, sx + 1, sy + 1);
                        g.itemDecorations(font, st, sx + 1, sy + 1);
                    }
                }
            }
        }
    }
}
