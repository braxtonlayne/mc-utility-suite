package com.utilitysuite.jobs;

import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.baritone.BaritoneAdapter;
import com.utilitysuite.jobs.fieldwork.AutoTorchModule;
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
import com.utilitysuite.jobs.planner.MinecraftRecipeSource;
import com.utilitysuite.jobs.storage.StorageMemory;
import com.utilitysuite.jobs.storage.StorageMemoryModule;
import com.utilitysuite.jobs.survival.ProtectedAreas;
import com.utilitysuite.jobs.survival.SurvivalAutopilot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.crafting.RecipeManager;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RecipesUpdatedEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.ArrayList;
import java.util.List;

/**
 * Wires the jobs feature: dependency planner, goal engine (IGoalEngine), Baritone navigator (INavigator), storage
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
        // Stop-All cancels every Baritone process unconditionally, leased or not.
        adapter.subscribeStopAll(Services.eventsOrNull());

        MinecraftInventoryView inventory = new MinecraftInventoryView();
        MinecraftWorldActions actions = new MinecraftWorldActions(() -> adapter, inventory);

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
        Services.modules().register(survival);

        // field modules (all off by default): Auto-Torch, Lava Plug, Vein Work, Crop Work
        AutoTorchModule autoTorch = new AutoTorchModule(inventory);
        LavaPlugModule lavaPlug = new LavaPlugModule(inventory);
        VeinWorkModule veinWork = new VeinWorkModule();
        CropWorkModule cropWork = new CropWorkModule(inventory);
        Services.modules().register(autoTorch);
        Services.modules().register(lavaPlug);
        Services.modules().register(veinWork);
        Services.modules().register(cropWork);

        IEventBus bus = NeoForge.EVENT_BUS;
        bus.addListener((ClientTickEvent.Post e) -> {
            ticks++;
            adapter.tick();
        });
        bus.addListener((RecipesUpdatedEvent e) -> rebuildPlanner(e.getRecipeManager()));
        bus.addListener(storageModule::onRightClickBlock);
        bus.addListener(storageModule::onScreenOpening);
        bus.addListener(storageModule::onScreenClosing);
        bus.addListener(survival::onLeftClickBlock);
        bus.addListener(autoTorch::onLeftClickBlock);
        bus.addListener(lavaPlug::onLeftClickBlock);
        bus.addListener(veinWork::onLeftClickBlock);
    }

    /** Snapshot recipes on the client thread, then let planning read it from the worker. */
    private static void rebuildPlanner(RecipeManager manager) {
        ClientLevel level = Minecraft.getInstance().level;
        if (manager == null || level == null) {
            return;
        }
        MapRecipeSource recipes = MinecraftRecipeSource.snapshot(manager, level.registryAccess());
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
