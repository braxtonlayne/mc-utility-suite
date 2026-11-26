package com.utilitysuite.jobs;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.baritone.BaritoneAdapter;
import com.utilitysuite.jobs.fieldwork.AfkGrinderModule;
import com.utilitysuite.jobs.fieldwork.AutoTorchModule;
import com.utilitysuite.jobs.fieldwork.PenSweepModule;
import com.utilitysuite.jobs.fieldwork.CropWorkModule;
import com.utilitysuite.jobs.fieldwork.LavaPlugModule;
import com.utilitysuite.jobs.fieldwork.VeinWorkModule;
import com.utilitysuite.jobs.goal.GoalEngine;
import com.utilitysuite.jobs.goal.GoalParser;
import com.utilitysuite.jobs.goal.ItemNameMatcher;
import com.utilitysuite.jobs.goal.JobExecutor;
import com.utilitysuite.jobs.goal.MinecraftInventoryView;
import com.utilitysuite.jobs.goal.MinecraftWorldActions;
import com.utilitysuite.jobs.planner.BackgroundWorker;
import com.utilitysuite.jobs.planner.DependencyPlanner;
import com.utilitysuite.jobs.planner.FuelTable;
import com.utilitysuite.jobs.planner.HarvestTable;
import com.utilitysuite.jobs.planner.MapRecipeSource;
import com.utilitysuite.jobs.planner.McRecipeCatalog;
import com.utilitysuite.jobs.planner.MinecraftRecipeSource;
import com.utilitysuite.jobs.storage.StorageMemory;
import com.utilitysuite.jobs.storage.StorageMemoryModule;
import com.utilitysuite.jobs.survival.ProtectedAreas;
import com.utilitysuite.jobs.survival.SurvivalAutopilot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.client.event.RecipesReceivedEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the jobs feature: dependency planner, goal engine (IGoalEngine), Baritone navigator (INavigator) and plan-only
 * route planner (IRoutePlanner, only when Baritone is installed), storage
 * memory (IStorageIndex) and the survival autopilot. Called once from {@code UtilitySuiteMod} after the core.
 */
public final class JobsBootstrap {

    private static final long STORAGE_MAX_AGE_MS = 72L * 3600_000L;

    private static volatile DependencyPlanner planner =
            new DependencyPlanner(MapRecipeSource.EMPTY, HarvestTable.vanilla(), FuelTable.vanilla());
    private static volatile ItemNameMatcher matcher;
    private static long ticks;
    private static BaritoneAdapter adapter;

    private JobsBootstrap() {
    }

    public static void init(IEventBus modBus) {
        StorageMemory storage = new StorageMemory(System::currentTimeMillis, STORAGE_MAX_AGE_MS);
        StorageMemoryModule storageModule = new StorageMemoryModule(storage);
        Services.registerStorage(storage);

        ProtectedAreas protectedAreas = new ProtectedAreas();
        adapter = new BaritoneAdapter(protectedAreas::isProtected);
        Services.registerNavigator(adapter.navigator());
        // Stop-All cancels every Baritone process unconditionally, leased or not (and drops any plan-only route).
        adapter.subscribeStopAll(Services.eventsOrNull());
        // Plan-only routes (Baritone computes, the suite drives): offered only when Baritone is installed.
        if (ModList.get() != null && ModList.get().isLoaded(BaritoneAdapter.BARITONE_MOD_ID)) {
            Services.registerRoutePlanner(adapter.routePlanner());
        }

        MinecraftInventoryView inventory = new MinecraftInventoryView();
        MinecraftWorldActions actions = new MinecraftWorldActions(() -> adapter, inventory);
        storage.setCarriedEnderAccess(inventory::canUseCarriedEnderChest); // PLACE_CARRIED only with Silk Touch
        actions.setCrafterTemplateSink(storage::putCrafterTemplate); // "crafter save <name>"

        JobExecutor executor = new JobExecutor(new JobExecutor.Deps(
                Services.arbiter(),
                Services::eventsOrNull,
                () -> inventory,
                () -> adapter,
                actions,
                Services::worldKnowledgeOrNull,
                Services::storageOrNull,
                storage::markStale,
                () -> planner,
                protectedAreas::isProtected,
                () -> ticks,
                System::currentTimeMillis,
                BackgroundWorker.executor()), new JobExecutor.Settings());
        executor.setStationSource(storage); // furnaces / brewing stands remembered when opened

        // Typed commands follow the World tab's routing data source unless they say source=...; waypoints are
        // converted 8:1 into the current dimension like the World tab "Go".
        GoalParser parser = new GoalParser(JobsBootstrap::matcher,
                name -> GoalParser.resolveWaypoint(Services.waypointsOrNull(), name, actions.dimension()),
                GoalParser.routingDefault(Services::worldKnowledgeOrNull),
                () -> actions.playerPos(), () -> actions.dimension());
        GoalEngine engine = new GoalEngine(parser, () -> planner, executor, BackgroundWorker.executor());
        Services.registerGoals(engine);

        SurvivalAutopilot survival = new SurvivalAutopilot(protectedAreas, inventory);

        Services.modules().register(storageModule);
        Services.modules().register(engine);
        com.utilitysuite.jobs.storage.hall.HallSorterModule hallSorter =
                new com.utilitysuite.jobs.storage.hall.HallSorterModule(storage, actions);
        Services.modules().register(hallSorter);
        executor.setHallSource(hallSorter); // "sort hall" jobs: the sorter plans, the executor walks the steps
        NeoForge.EVENT_BUS.addListener(hallSorter::onRenderLevel); // nearly-full outlines (Shell world kit)
        if (modBus != null) {
            modBus.addListener(hallSorter::onRegisterKeys);
        }
        Services.modules().register(survival);

        // field modules (all off by default): Auto-Torch, Lava Plug, Vein Work, Crop Work
        Services.registerBlockWork(new com.utilitysuite.jobs.fieldwork.FieldBlockWork());
        AutoTorchModule autoTorch = new AutoTorchModule(inventory);
        LavaPlugModule lavaPlug = new LavaPlugModule(inventory);
        VeinWorkModule veinWork = new VeinWorkModule();
        CropWorkModule cropWork = new CropWorkModule(inventory);
        Services.modules().register(autoTorch);
        Services.modules().register(lavaPlug);
        Services.modules().register(veinWork);
        Services.modules().register(cropWork);
        Services.modules().register(new PenSweepModule());
        Services.modules().register(new AfkGrinderModule());
        Services.modules().register(new com.utilitysuite.jobs.fieldwork.DebrisBlastModule(veinWork, lavaPlug));

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ClientTickEvent.Post e) -> {
            ticks++;
            adapter.tick();
            if (ticks % RECIPE_CHECK_TICKS == 0) {
                refreshPlannerIfStale();
            }
        });
        // A planned route belongs to the level it was computed in: drop it on respawn/dimension change, level unload
        // and logout.
        bus.addListener((ClientPlayerNetworkEvent.Clone e) -> adapter.cancelPlan());
        bus.addListener((ClientPlayerNetworkEvent.LoggingOut e) -> {
            adapter.cancelPlan();
            McRecipeCatalog.clear();
            recipeOrigin = null;
        });
        bus.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel().isClientSide()) {
                adapter.cancelPlan();
            }
        });
        // 26.3: recipes are server-side. Single player reads the integrated server; a server mod may sync recipes
        // (RecipesReceivedEvent); otherwise the planner knows the recipe book's unlocked recipes (refreshed on unlock).
        bus.addListener((RecipesReceivedEvent e) -> {
            McRecipeCatalog.onRecipesReceived(e.getRecipeMap());
            rebuildPlanner();
        });
        bus.addListener((ClientPlayerNetworkEvent.LoggingIn e) -> rebuildPlanner());
        bus.addListener(storageModule::onRightClickBlock);
        bus.addListener(storageModule::onScreenOpening);
        bus.addListener(storageModule::onScreenClosing);
        bus.addListener(survival::onLeftClickBlock);
        bus.addListener(autoTorch::onLeftClickBlock);
        bus.addListener(lavaPlug::onLeftClickBlock);
        bus.addListener(veinWork::onLeftClickBlock);
    }

    /** How often (ticks) the recipe source is checked for a change (cheap: identity compares). */
    private static final int RECIPE_CHECK_TICKS = 40;
    private static Object recipeOrigin;

    /**
     * Rebuilds when the recipe set changed: world joined, {@code /reload} (new server recipe manager), new synced
     * recipes, or recipe-book unlocks when planning from the book (the old {@code RecipesUpdatedEvent} cases).
     */
    private static void refreshPlannerIfStale() {
        Minecraft mc = Minecraft.getInstance();
        if (McRecipeCatalog.source(mc) == McRecipeCatalog.Source.NONE) {
            return;
        }
        if (McRecipeCatalog.origin(mc) != recipeOrigin) {
            rebuildPlanner();
        }
    }

    /** Snapshot recipes on the client thread, then let planning read it from the worker. */
    private static void rebuildPlanner() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        if (level == null) {
            return;
        }
        recipeOrigin = McRecipeCatalog.origin(mc);
        MapRecipeSource recipes = MinecraftRecipeSource.snapshot(mc);
        DependencyPlanner fresh = new DependencyPlanner(recipes, HarvestTable.vanilla(), FuelTable.vanilla());
        fresh.setEfficiencyTools(planner.efficiencyTools()); // keep the user's settings across recipe reloads
        planner = fresh;
    }

    private static ItemNameMatcher matcher() {
        ItemNameMatcher m = matcher;
        if (m == null) {
            List<String> ids = new ArrayList<>();
            BuiltInRegistries.ITEM.keySet().forEach(k -> ids.add(k.toString()));
            m = new ItemNameMatcher(ids);
            matcher = m;
        }
        return m;
    }
}
