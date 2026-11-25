package com.utilitysuite.jobs.schematic;

import com.utilitysuite.core.api.Services;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;

/**
 * Wires the Schematic Toolkit: creates the library (schematic folders + background thread) and the module, registers
 * the module and subscribes the ghost-preview renderer. Called from {@code UtilitySuiteMod} after the core.
 * <p>
 * The goal engine hands {@code GoalRequest.Build} jobs to {@link SchematicToolkit#handleBuild}; it can find the module
 * with {@code Services.modules().byId(SchematicToolkit.ID)} until a dedicated hook exists.
 */
public final class SchematicBootstrap {

    private static SchematicToolkit toolkit;
    private static SymmetryBuilder symmetry;

    private SchematicBootstrap() {
    }

    public static void init(IEventBus modBus) {
        SchematicLibrary library = new SchematicLibrary(FMLPaths.GAMEDIR.get());
        toolkit = new SchematicToolkit(library);
        Services.modules().register(toolkit);
        symmetry = new SymmetryBuilder();
        Services.modules().register(symmetry);
        Services.modules().register(new HighwayBuilder());
        com.utilitysuite.jobs.goal.GoalEngine.registerBuildHandler(new SchematicBuildBridge(toolkit));
        NeoForge.EVENT_BUS.addListener(SchematicBootstrap::onRenderLevel);
    }

    private static void onRenderLevel(RenderLevelStageEvent event) {
        SchematicToolkit t = toolkit;
        if (t != null) {
            t.onRenderLevel(event);
        }
        SymmetryBuilder s = symmetry;
        if (s != null) {
            s.onRenderLevel(event);
        }
    }

    /** The symmetry builder (null before {@link #init}). */
    public static SymmetryBuilder symmetry() {
        return symmetry;
    }

    /** The module instance (null before {@link #init}). */
    public static SchematicToolkit toolkit() {
        return toolkit;
    }
}
