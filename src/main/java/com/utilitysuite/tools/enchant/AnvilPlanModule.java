package com.utilitysuite.tools.enchant;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IContainerClicker;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.tools.enchant.AnvilPlanner.Piece;
import com.utilitysuite.ui.kit.UiRenderUtils;
import com.utilitysuite.ui.kit.UiTheme;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AnvilScreen;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.AnvilMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantment;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.client.event.ScreenEvent;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Anvil combine-order planner (roadmap W12). When an anvil opens (or on Refresh) it reads the left-slot item (else the
 * held item) and every enchanted book / same-type enchanted item in the inventory, runs {@link AnvilPlanner} once and
 * shows the order on a card beside the anvil and in the Tools tab. "Place next step" puts the next step's two inputs
 * into the anvil through {@link IContainerClicker} under a HOTBAR lease; the player takes the output (or, with the
 * setting on, it is shift-taken). Nothing runs while no anvil is open; the card is drawn from precomputed lines.
 */
public final class AnvilPlanModule implements IModule, IConfigurable, LeaseListener {

    public static final String ID = "anvil_planner";
    public static final String K_SHOW_CARD = "show_card";
    public static final String K_TAKE_OUTPUT = "take_output";

    private static final int LEFT = 0, RIGHT = 1, OUTPUT = 2, INV_START = 3;

    private boolean enabled;
    private boolean showCard = true;
    private boolean takeOutput;

    private AnvilMenu seenMenu;
    private ItemStack seenLeft; // reference compare only (no Items class load at construction)
    private volatile List<String> lines = List.of("Open an anvil or press Refresh");
    private AnvilPlanner.Plan plan;
    private Piece planTarget;
    private List<Piece> planInputs = List.of();
    private Set<String> planApplicable;
    private String status = "";

    private ControlLease lease;
    private LeaseRequest request;

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.TOOLS;
    }

    @Override
    public String displayName() {
        return "Anvil Planner";
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        if (!enabled) {
            releaseLease();
        }
    }

    /** Cheap identity checks; plans only when an anvil opens or its left slot changes. */
    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (lease != null && Services.clicker().pending() == 0) {
            releaseLease();
        }
        if (mc.player == null || !(mc.player.containerMenu instanceof AnvilMenu menu)) {
            seenMenu = null;
            return;
        }
        ItemStack left = menu.getSlot(LEFT).getItem();
        if (menu != seenMenu || left != seenLeft) {
            seenMenu = menu;
            seenLeft = left;
            refresh();
        }
    }

    @Override
    public void onStopAll() {
        releaseLease();
    }

    @Override
    public void onWorldUnload() {
        releaseLease();
        seenMenu = null;
        plan = null;
        lines = List.of("Open an anvil or press Refresh");
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool(K_SHOW_CARD, "Card beside the anvil", true, "",
                        "Show the combine order next to an open anvil."),
                SettingSpec.bool(K_TAKE_OUTPUT, "Shift-take the output", false, "",
                        "After placing a step's inputs, also shift-click the result (spends the levels). Off: you"
                                + " take it yourself."));
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        showCard = p.getBool(ID, K_SHOW_CARD, true);
        takeOutput = p.getBool(ID, K_TAKE_OUTPUT, false);
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }

    public List<String> lines() {
        return lines;
    }

    public String status() {
        return status;
    }

    // ---- planning (client thread; one DP run, a few ms for 8 books) ----------------------------------------------

    /** Re-reads the anvil / inventory and plans. */
    public void refresh() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        ItemStack target = ItemStack.EMPTY;
        if (mc.player.containerMenu instanceof AnvilMenu menu) {
            target = menu.getSlot(LEFT).getItem();
        }
        if (target.isEmpty()) {
            target = mc.player.getMainHandItem();
        }
        if (target.isEmpty()) {
            plan = null;
            lines = List.of("Put the item in the anvil's left slot (or hold it)");
            return;
        }
        boolean targetBook = target.is(Items.ENCHANTED_BOOK) || target.is(Items.BOOK);
        Set<String> applicable = targetBook ? null : applicableTo(mc, target);
        List<Piece> inputs = new ArrayList<>();
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.getNonEquipmentItems().size(); i++) {
            ItemStack s = inv.getNonEquipmentItems().get(i);
            if (s == target || s.isEmpty() || s.getCount() != 1) {
                continue;
            }
            boolean book = s.is(Items.ENCHANTED_BOOK);
            if (!book && !(s.is(target.getItem()) && s.isEnchanted())) {
                continue;
            }
            if (inputs.size() >= AnvilPlanner.MAX_SACRIFICES) {
                break;
            }
            inputs.add(piece(s, book));
        }
        planTarget = piece(target, targetBook);
        planInputs = List.copyOf(inputs);
        planApplicable = applicable;
        if (inputs.isEmpty()) {
            plan = null;
            lines = List.of("No enchanted books for " + target.getHoverName().getString());
            return;
        }
        boolean creative = mc.player.getAbilities().instabuild;
        plan = AnvilPlanner.plan(planTarget, planInputs, applicable, creative);
        if (plan.empty()) {
            lines = List.of("Nothing fits under 40 levels (Too Expensive)");
            return;
        }
        List<String> out = new ArrayList<>(AnvilPlanner.lines(plan, planTarget, planInputs));
        out.add("Result: " + AnvilPlanner.describe(plan.result()));
        lines = List.copyOf(out);
    }

    private static Set<String> applicableTo(Minecraft mc, ItemStack target) {
        Set<String> out = new HashSet<>();
        Registry<Enchantment> reg = mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        // NeoForge's AnvilMenu asks the stack (supportsEnchantment honours item overrides)
        reg.listElements().forEach(h -> {
            if (target.supportsEnchantment(h)) {
                out.add(h.key().identifier().toString());
            }
        });
        return out;
    }

    private static Piece piece(ItemStack s, boolean book) {
        ItemEnchantments ench = EnchantmentHelper.getEnchantmentsForCrafting(s);
        Map<String, Integer> map = new TreeMap<>();
        for (Object2IntMap.Entry<Holder<Enchantment>> e : ench.entrySet()) {
            Optional<net.minecraft.resources.ResourceKey<Enchantment>> key = e.getKey().unwrapKey();
            key.ifPresent(k -> map.put(k.identifier().toString(), e.getIntValue()));
        }
        String label = book ? "Book (" + AnvilPlanner.describe(new Piece("", true, 0, map)) + ")"
                : s.getHoverName().getString();
        return new Piece(label, book, s.getOrDefault(DataComponents.REPAIR_COST, 0), map);
    }

    // ---- stepping ------------------------------------------------------------------------------------------------

    /**
     * Places the inputs of the first step whose two inputs are both in the inventory (or already in the anvil):
     * empties the anvil inputs that do not belong, then moves left and right with pickup clicks.
     */
    public void placeNext() {
        Minecraft mc = Minecraft.getInstance();
        if (!enabled || plan == null || plan.empty() || mc.player == null
                || !(mc.player.containerMenu instanceof AnvilMenu menu)) {
            status = "Open the anvil first";
            return;
        }
        List<Piece> nodes = new ArrayList<>(planInputs);
        nodes.add(planTarget);
        for (AnvilPlanner.Step st : plan.steps()) {
            while (nodes.size() <= st.result()) {
                nodes.add(null);
            }
            nodes.set(st.result(), st.output());
        }
        for (int k = 0; k < plan.steps().size(); k++) {
            AnvilPlanner.Step st = plan.steps().get(k);
            int ls = findSlot(menu, nodes.get(st.left()), -1);
            int rs = findSlot(menu, nodes.get(st.right()), ls);
            if (ls < 0 || rs < 0) {
                continue;
            }
            if (!acquire()) {
                status = "Busy: another task holds the inventory";
                return;
            }
            IContainerClicker c = Services.clicker();
            if (ls != LEFT) {
                if (!menu.getSlot(LEFT).getItem().isEmpty()) {
                    c.queueClick(lease, LEFT, 0, IContainerClicker.Click.QUICK_MOVE);
                }
                c.queueClick(lease, ls, 0, IContainerClicker.Click.PICKUP);
                c.queueClick(lease, LEFT, 0, IContainerClicker.Click.PICKUP);
            }
            if (rs != RIGHT) {
                if (!menu.getSlot(RIGHT).getItem().isEmpty()) {
                    c.queueClick(lease, RIGHT, 0, IContainerClicker.Click.QUICK_MOVE);
                }
                c.queueClick(lease, rs, 0, IContainerClicker.Click.PICKUP);
                c.queueClick(lease, RIGHT, 0, IContainerClicker.Click.PICKUP);
            }
            if (takeOutput) {
                c.queueClick(lease, OUTPUT, 0, IContainerClicker.Click.QUICK_MOVE);
            }
            status = "Step " + (k + 1) + " placed (" + st.cost() + " levels)";
            return;
        }
        status = "Plan done (or its inputs are not in the inventory)";
    }

    /** Menu slot holding a stack that matches {@code p}, or -1. Inputs count only in their own anvil slot. */
    private static int findSlot(AnvilMenu menu, Piece p, int exclude) {
        if (p == null) {
            return -1;
        }
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == OUTPUT || i == exclude) {
                continue;
            }
            ItemStack s = menu.getSlot(i).getItem();
            if (s.isEmpty() || s.getCount() != 1) {
                continue;
            }
            boolean book = s.is(Items.ENCHANTED_BOOK);
            if (book != p.book()) {
                continue;
            }
            Piece q = piece(s, book);
            if (q.repairCost() == p.repairCost() && q.enchants().equals(p.enchants())) {
                return i;
            }
        }
        return -1;
    }

    private boolean acquire() {
        if (lease != null && lease.isActive()) {
            return true;
        }
        if (request == null) {
            request = new LeaseRequest(ID, ControlPriority.IDLE, EnumSet.of(ControlChannel.HOTBAR), 0, this);
        }
        Optional<ControlLease> got = Services.arbiter().request(request);
        lease = got.orElse(null);
        return lease != null;
    }

    private void releaseLease() {
        ControlLease l = lease;
        lease = null;
        if (l != null && l.isActive()) {
            Services.clicker().cancel(l);
            Services.arbiter().release(l);
        }
    }

    @Override
    public void onPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == lease) {
            lease = null;
        }
    }

    // ---- card ----------------------------------------------------------------------------------------------------

    /** Game bus: the plan card to the right of the anvil window. Draws precomputed lines only. */
    public void onScreenRender(ScreenEvent.Render.Post event) {
        if (!enabled || !showCard || !(event.getScreen() instanceof AnvilScreen screen)
                || Services.modState().isCleanScreen()) {
            return;
        }
        List<String> ls = lines;
        Font font = Minecraft.getInstance().font;
        GuiGraphicsExtractor g = event.getGuiGraphics();
        int x = screen.getLeftPos() + screen.getImageWidth() + 6;
        int y = screen.getTopPos();
        int w = Math.max(80, screen.width - x - 6);
        if (ls != fittedFrom || w != fittedWidth) { // re-fit only when the plan or the window changes
            String[] f = new String[ls.size()];
            for (int i = 0; i < f.length; i++) {
                f[i] = UiRenderUtils.ellipsize(font, ls.get(i), w - 8);
            }
            fitted = f;
            fittedFrom = ls;
            fittedWidth = w;
        }
        String[] f = fitted;
        int h = 8 + f.length * 10;
        UiRenderUtils.panel(g, x, y, w, h, 4, UiTheme.CARD, UiTheme.BORDER);
        for (int i = 0; i < f.length; i++) {
            int color = i == f.length - 1 ? UiTheme.TEXT_SECONDARY : UiTheme.TEXT;
            UiRenderUtils.text(g, font, f[i], x + 4, y + 4 + i * 10, color);
        }
    }

    private List<String> fittedFrom;
    private int fittedWidth = -1;
    private String[] fitted = new String[0];
}
