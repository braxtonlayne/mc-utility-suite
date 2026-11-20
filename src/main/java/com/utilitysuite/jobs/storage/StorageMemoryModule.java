package com.utilitysuite.jobs.storage;

import com.utilitysuite.core.api.IConfigurable;
import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.ModuleCategory;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.config.ProfileIds;
import com.utilitysuite.jobs.planner.BackgroundWorker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * StorageMemory as a module: records container contents when the player closes a chest, barrel, shulker box or ender
 * chest; marks entries stale when their block is gone (budgeted verification, a few entries per tick) or by age; saves
 * {@code config/utilitysuite/storage/<profile>.json} debounced on the jobs worker thread.
 * <p>
 * The client does not learn which block a container screen belongs to, so the position comes from the player's last
 * right-click on a container block just before the screen opened.
 */
public final class StorageMemoryModule implements IModule, IConfigurable {

    public static final String ID = "storage_memory";
    private static final Logger LOG = LoggerFactory.getLogger("utilitysuite/storage");
    private static final int SAVE_DEBOUNCE_TICKS = 40;
    private static final int VERIFY_PER_TICK = 4;

    private final StorageMemory memory;
    private boolean enabled = true;
    private String profileFile;
    private int saveCountdown = -1;

    // last container interaction (client thread)
    private long pendingPos = Long.MIN_VALUE;
    private String pendingDim;
    private String pendingType;
    private long pendingTick;
    private boolean screenBound;
    private long tickCounter;
    private int verifyCursor;
    private final IdentityHashMap<Block, String> blockIds = new IdentityHashMap<>();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();

    public StorageMemoryModule(StorageMemory memory) {
        this.memory = memory;
    }

    public StorageMemory memory() {
        return memory;
    }

    public static boolean isContainerBlock(String blockId) {
        return switch (blockId) {
            case "minecraft:chest", "minecraft:trapped_chest", "minecraft:barrel", "minecraft:ender_chest" -> true;
            default -> blockId.endsWith("shulker_box");
        };
    }

    // ---- events -----------------------------------------------------------------------------------------------------

    /** Remembers which container block the player is about to open. */
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!enabled || !event.getLevel().isClientSide()) {
            return;
        }
        String id = blockId(event.getLevel().getBlockState(event.getPos()).getBlock());
        if (!isContainerBlock(id)) {
            return;
        }
        pendingPos = event.getPos().asLong();
        pendingDim = event.getLevel().dimension().location().toString();
        pendingType = id;
        pendingTick = tickCounter;
        screenBound = false;
    }

    public void onScreenOpening(ScreenEvent.Opening event) {
        if (enabled && event.getNewScreen() instanceof AbstractContainerScreen<?>
                && pendingPos != Long.MIN_VALUE && tickCounter - pendingTick <= 40) {
            screenBound = true;
        }
    }

    public void onScreenClosing(ScreenEvent.Closing event) {
        if (!enabled || !screenBound || !(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }
        screenBound = false;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        AbstractContainerMenu menu = screen.getMenu();
        Container playerInv = mc.player.getInventory();
        Map<String, Integer> contents = new LinkedHashMap<>();
        for (Slot s : menu.slots) {
            if (s.container == playerInv) {
                continue;
            }
            ItemStack st = s.getItem();
            if (!st.isEmpty()) {
                contents.merge(BuiltInRegistries.ITEM.getKey(st.getItem()).toString(), st.getCount(), Integer::sum);
            }
        }
        memory.record(pendingDim, pendingPos, pendingType, contents);
        pendingPos = Long.MIN_VALUE;
        scheduleSave();
    }

    // ---- IModule ----------------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        tickCounter++;
        if (saveCountdown > 0 && --saveCountdown == 0) {
            saveAsync();
        }
        if ((tickCounter & 63) == 0) {
            memory.refreshAges();
        }
        verifySome();
        if (memory.consumeDirty() && saveCountdown < 0) {
            scheduleSave();
        }
    }

    /** Budgeted check that indexed containers still exist (loaded chunks only). */
    private void verifySome() {
        Minecraft mc = Minecraft.getInstance();
        Level level = mc.level;
        if (level == null) {
            return;
        }
        List<com.utilitysuite.core.api.StorageEntry> entries = memory.entries();
        int n = entries.size();
        if (n == 0) {
            return;
        }
        String dim = level.dimension().location().toString();
        for (int i = 0; i < VERIFY_PER_TICK && i < n; i++) {
            verifyCursor = (verifyCursor + 1) % n;
            com.utilitysuite.core.api.StorageEntry e = entries.get(verifyCursor);
            if (!e.dimension().equals(dim)) {
                continue;
            }
            probe.set(BlockPos.getX(e.packedPos()), BlockPos.getY(e.packedPos()), BlockPos.getZ(e.packedPos()));
            if (!level.isLoaded(probe)) {
                continue;
            }
            BlockState state = level.getBlockState(probe);
            if (!isContainerBlock(blockId(state.getBlock()))) {
                memory.remove(dim, e.packedPos());
                return; // snapshot changed; continue next tick
            }
        }
    }

    private String blockId(Block b) {
        String id = blockIds.get(b);
        if (id == null) {
            id = BuiltInRegistries.BLOCK.getKey(b).toString();
            blockIds.put(b, id);
        }
        return id;
    }

    private void scheduleSave() {
        saveCountdown = SAVE_DEBOUNCE_TICKS;
    }

    private void saveAsync() {
        saveCountdown = -1;
        String file = profileFile;
        if (file == null) {
            return;
        }
        String json = memory.toJson();
        BackgroundWorker.executor().execute(() -> write(file, json));
    }

    private static void write(String file, String json) {
        try {
            Path p = Path.of(file);
            Files.createDirectories(p.getParent());
            Path tmp = p.resolveSibling(p.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            LOG.warn("Could not save storage index {}: {}", file, e.toString());
        }
    }

    @Override
    public void onProfileLoaded(ProfileConfig profile) {
        memory.setMaxAgeMillis(3_600_000L * profile.getInt(ID, "stale_after_hours", 72));
        String file = FMLPaths.CONFIGDIR.get().resolve("utilitysuite").resolve("storage")
                .resolve(ProfileIds.fileName(profile.profileId()) + ".json").toString();
        if (file.equals(profileFile)) {
            return;
        }
        profileFile = file;
        memory.clear();
        try {
            Path p = Path.of(file);
            if (Files.isRegularFile(p)) {
                memory.loadJson(Files.readString(p, StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            LOG.warn("Could not read storage index {}: {}", file, e.toString());
        }
    }

    @Override
    public void onWorldUnload() {
        if (saveCountdown > 0) {
            saveAsync();
        }
        memory.clear();
        profileFile = null;
        pendingPos = Long.MIN_VALUE;
        screenBound = false;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public ModuleCategory category() {
        return ModuleCategory.JOBS;
    }

    @Override
    public boolean isEnabled() {
        return enabled;
    }

    @Override
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    // ---- IConfigurable ----------------------------------------------------------------------------------------------

    @Override
    public String displayName() {
        return "Storage Memory";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(SettingSpec.integer("stale_after_hours", "Mark stale after (hours)", 72, 1, 24 * 30, "",
                "Container contents older than this are not used for planning"));
    }

    @Override
    public void onSettingChanged(String key) {
        memory.setMaxAgeMillis(3_600_000L * Services.modState().profile().getInt(ID, "stale_after_hours", 72));
    }
}
