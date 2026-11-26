package com.utilitysuite.ui.holo;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.IStorageIndex;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.StorageEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractFurnaceScreen;
import net.minecraft.client.gui.screens.inventory.BrewingStandScreen;
import net.minecraft.client.gui.screens.inventory.MerchantScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.npc.villager.Villager;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.BarrelBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.BrewingStandBlock;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Diegetic holo-cards (V3, off by default). When the crosshair rests on a villager, a container or a furnace-like
 * block for the dwell time, one small card is shown beside it ({@link HoloCardLayer}):
 * <ul>
 *     <li>villager: profession and level (synced to the client), and the offers its trade screen showed when the
 *     user last opened it (else "open once to learn");</li>
 *     <li>chest / barrel / shulker box: the top items the storage index ({@code Services.storageOrNull()}) knows;</li>
 *     <li>furnace / blast furnace / smoker / brewing stand: a progress ring from what the last opened menu showed plus
 *     the time since, labelled as an estimate (the server does not sync furnace progress).</li>
 * </ul>
 * Shows only what the client already saw. Content is built on target change only; the tick does no allocation while
 * the target stays the same.
 */
public final class HoloCardModule implements IModule, IConfigurable {

    public static final String ID = "holo_cards";
    public static final String K_DWELL = "dwell_ticks";
    public static final String K_VILLAGERS = "villagers";
    public static final String K_STORAGE = "storage";
    public static final String K_PROGRESS = "progress";

    private static final List<SettingSpec> SPECS = List.of(
            SettingSpec.integer(K_DWELL, "Dwell (ticks)", 6, 2, 40, "",
                    "How long the crosshair must rest on something before its card appears (20 ticks = 1 s)."),
            SettingSpec.bool(K_VILLAGERS, "Villager cards", true, "",
                    "Profession, level and the offers seen the last time you traded with that villager."),
            SettingSpec.bool(K_STORAGE, "Storage cards", true, "",
                    "Top items of a chest, barrel or shulker box, from Storage Memory."),
            SettingSpec.bool(K_PROGRESS, "Furnace and brewing cards", true, "",
                    "Estimated progress of a furnace, smoker, blast furnace or brewing stand you opened before."));

    private final HoloCardBook book = new HoloCardBook();
    private final ScreenProjector projector;
    private boolean enabled;
    private boolean villagers = true;
    private boolean storage = true;
    private boolean progress = true;
    private int lastEntity = -1;
    private long lastPos = HoloCardBook.NO_TARGET;

    public HoloCardModule(ScreenProjector projector) {
        this.projector = projector;
    }

    public HoloCardModule() {
        this(new ScreenProjector());
    }

    public HoloCardBook book() {
        return book;
    }

    // ---- tick -------------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null || mc.gui.screen() != null) {
            book.dwell(HoloCardBook.NO_TARGET);
            return;
        }
        HitResult hit = mc.hitResult;
        long key = HoloCardBook.NO_TARGET;
        Entity entity = null;
        BlockPos pos = null;
        Block block = null;
        if (hit instanceof EntityHitResult eh && villagers && eh.getEntity() instanceof Villager) {
            entity = eh.getEntity();
            key = HoloCardBook.entityKey(entity.getId());
        } else if (hit instanceof BlockHitResult bh && hit.getType() == HitResult.Type.BLOCK) {
            pos = bh.getBlockPos();
            block = mc.level.getBlockState(pos).getBlock();
            if (cardBlock(block)) {
                key = pos.asLong();
            }
        }
        if (!book.dwell(key)) {
            return;
        }
        // a new target: build its card once (allocation on change only) and anchor the projection
        if (entity instanceof Villager v) {
            String prof = v.getVillagerData().profession().unwrapKey().map(k -> k.identifier().getPath())
                    .orElse("none");
            book.fillVillager(v.getId(), title(prof), v.getVillagerData().level());
            projector.anchorEntity(v.getId());
        } else if (pos != null) {
            String dim = mc.level.dimension().identifier().toString();
            String name = block.getName().getString();
            if (block instanceof AbstractFurnaceBlock || block instanceof BrewingStandBlock) {
                book.fillProgress(name, dim, pos.asLong());
            } else {
                fillStorage(name, dim, pos.asLong());
            }
            projector.anchorBox(pos.getX(), pos.getY(), pos.getZ(), pos.getX() + 1, pos.getY() + 1, pos.getZ() + 1);
        }
    }

    private boolean cardBlock(Block b) {
        if (b instanceof ChestBlock || b instanceof BarrelBlock || b instanceof ShulkerBoxBlock) {
            return storage;
        }
        return progress && (b instanceof AbstractFurnaceBlock || b instanceof BrewingStandBlock);
    }

    private void fillStorage(String name, String dim, long pos) {
        IStorageIndex index = Services.storageOrNull();
        StorageEntry found = null;
        if (index != null) {
            List<StorageEntry> entries = index.entries();
            for (int i = 0, n = entries.size(); i < n; i++) {
                StorageEntry e = entries.get(i);
                if (e.packedPos() == pos && e.dimension().equals(dim)) {
                    found = e;
                    break;
                }
            }
        }
        book.fillStorage(name, found != null ? found.contents() : null, found != null && found.stale());
    }

    static String title(String id) {
        if (id == null || id.isEmpty() || id.equals("none")) {
            return "Villager";
        }
        String s = id.replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---- learning from opened menus ---------------------------------------------------------------------------------

    public void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (enabled && event.getLevel().isClientSide()) {
            lastEntity = event.getTarget().getId();
        }
    }

    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (enabled && event.getLevel().isClientSide()) {
            lastPos = event.getPos().asLong();
        }
    }

    public void onScreenClosing(ScreenEvent.Closing event) {
        if (!enabled) {
            return;
        }
        Screen s = event.getScreen();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) {
            return;
        }
        long now = System.nanoTime() / 1_000_000L; // the FrameClock time base
        if (s instanceof MerchantScreen ms && lastEntity >= 0) {
            MerchantOffers offers = ms.getMenu().getOffers();
            int n = Math.min(HoloCardBook.ROWS, offers.size());
            String[] cost = new String[n];
            int[] costN = new int[n];
            String[] result = new String[n];
            int[] resultN = new int[n];
            for (int i = 0; i < n; i++) {
                MerchantOffer o = offers.get(i);
                ItemStack c = o.getCostA();
                ItemStack r = o.getResult();
                cost[i] = BuiltInRegistries.ITEM.getKey(c.getItem()).toString();
                costN[i] = c.getCount();
                result[i] = BuiltInRegistries.ITEM.getKey(r.getItem()).toString();
                resultN[i] = r.getCount();
            }
            book.recordOffers(lastEntity, cost, costN, result, resultN);
        } else if (s instanceof AbstractFurnaceScreen<?> fs && lastPos != HoloCardBook.NO_TARGET) {
            AbstractFurnaceMenu menu = fs.getMenu();
            int cycle = menu instanceof BlastFurnaceMenu || menu instanceof SmokerMenu ? 100 : 200;
            boolean running = menu.isLit() && menu.getBurnProgress() > 0f;
            book.recordProgress(mc.level.dimension().identifier().toString(), lastPos, menu.getBurnProgress(), cycle,
                    running, now);
        } else if (s instanceof BrewingStandScreen bs && lastPos != HoloCardBook.NO_TARGET) {
            int left = bs.getMenu().getBrewingTicks();
            float p = left > 0 ? 1f - left / (float) HoloCardBook.BREW_TICKS : 0f;
            book.recordProgress(mc.level.dimension().identifier().toString(), lastPos, p, HoloCardBook.BREW_TICKS,
                    left > 0, now);
        }
    }

    // ---- module -----------------------------------------------------------------------------------------------------

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
        if (!enabled) {
            book.dwell(HoloCardBook.NO_TARGET);
        }
    }

    @Override
    public void onWorldUnload() {
        book.reset();
        projector.clearAnchor();
        lastEntity = -1;
        lastPos = HoloCardBook.NO_TARGET;
    }

    @Override
    public void onProfileLoaded(ProfileConfig p) {
        int d = p.getInt(ID, K_DWELL, 6);
        book.dwellTicks = d < 2 ? 2 : Math.min(d, 40);
        villagers = p.getBool(ID, K_VILLAGERS, true);
        storage = p.getBool(ID, K_STORAGE, true);
        progress = p.getBool(ID, K_PROGRESS, true);
    }

    @Override
    public String displayName() {
        return "Holo Cards";
    }

    @Override
    public List<SettingSpec> settings() {
        return SPECS;
    }

    @Override
    public void onSettingChanged(String key) {
        onProfileLoaded(Services.modState().profile());
    }
}
