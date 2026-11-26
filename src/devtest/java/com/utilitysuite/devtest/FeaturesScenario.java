package com.utilitysuite.devtest;

import com.utilitysuite.core.api.IModule;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.jobs.schematic.gen.MapArtPlanner;
import com.utilitysuite.jobs.schematic.gen.ShapeVoxelizer;
import com.utilitysuite.jobs.schematic.model.SchematicVolume;
import com.utilitysuite.ui.holo.HoloCardBook;
import com.utilitysuite.ui.holo.HoloCardModule;
import com.utilitysuite.ui.palette.CommandPaletteScreen;
import com.utilitysuite.world.atlas.WorldAtlasModule;
import com.utilitysuite.world.atlas.WorldAtlasScreen;
import com.utilitysuite.world.replay.ReplayScreen;
import com.utilitysuite.world.replay.ReplayTheatreModule;
import com.utilitysuite.world.sonar.CavityTomographyModule;
import com.utilitysuite.world.sonar.SonarPulseModule;
import com.mojang.blaze3d.platform.NativeImage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Scenario: a smoke pass over the Wave 1-3 features. Each feature is switched on in the dev world, poked once (open a
 * screen, pulse, rescan, look at a placed contraption, ride a boat on ice, plan) and checked for "no exception" (the
 * module is still on: the registry turns off a module that throws in its tick) plus one piece of basic state, with
 * one pass/fail line per feature in the report. Select it alone with {@code -PplaytestOnly=Features}.
 */
final class FeaturesScenario {

    /** Every module this scenario switches on (all switched off again by the cleanup step). */
    private static final String[] MODULES = {WorldAtlasModule.ID, SonarPulseModule.ID, CavityTomographyModule.ID,
            "redstone_tracer", "boat_line", HoloCardModule.ID, ReplayTheatreModule.ID, "anvil_planner",
            com.utilitysuite.world.replay.PipCameraModule.ID, "atmosphere"};

    private FeaturesScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("13-features", "Feature smoke: atlas, sonar, cavities, redstone, boat, holo,"
                + " replay, palette, anvil, shapes, map art");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
        atlas(s);
        sonar(s);
        cavity(s);
        redstone(s);
        boat(s);
        holoCards(s);
        replay(s);
        pip(s);
        sky(s);
        palette(s);
        anvil(s);
        shapes(s);
        mapArt(s);
        s.always("disarm", () -> {
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
            for (String id : MODULES) {
                H.enable(id, false);
            }
            Harness.command("ride @s dismount");
            Harness.command("kill @e[type=minecraft:oak_boat]");
            Harness.command(Arena.fill(-8, 1, 2, 4, 2, 7, "minecraft:air"));
            Harness.command(Arena.fill(8, 1, -12, 14, 1, -2, "minecraft:air"));
        });
        return s;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static IModule module(String id) {
        return Services.modules().byId(id).orElse(null);
    }

    private static boolean on(String id) {
        IModule m = module(id);
        return m != null && m.isEnabled();
    }

    /** A private field of {@code o} (diagnostics), or null. */
    private static Object field(Object o, String name) {
        try {
            java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static String face(double dx, double dz, double lx, double ly, double lz) {
        return "tp @s " + (Arena.cx + dx) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + dz) + " facing "
                + (Arena.cx + lx) + " " + (Arena.FLOOR + ly) + " " + (Arena.cz + lz);
    }


    /** Points the camera at an arena-relative spot directly (the tp "facing" rotation did not stick in the 2026-11-25 run). */
    private static void lookAt(double lx, double ly, double lz) {
        var p = H.p();
        double dx = Arena.cx + lx - p.getX();
        double dy = Arena.FLOOR + ly - p.getEyeY();
        double dz = Arena.cz + lz - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
    }

    /** What the crosshair hits, for the report. */
    private static String hitInfo() {
        var hit = H.mc().hitResult;
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh && hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK) {
            return "hit " + bh.getBlockPos().toShortString() + " " + H.mc().level.getBlockState(bh.getBlockPos()).getBlock();
        }
        return "hit " + (hit == null ? "null" : hit.getType()) + ", pitch " + H.p().getXRot() + ", yaw " + H.p().getYRot();
    }

    // ---- features -------------------------------------------------------------------------------------------------

    private static void atlas(Scenario s) {
        s.run("atlas on + open", () -> {
            H.enable(WorldAtlasModule.ID, true);
            WorldAtlasModule m = H.module(WorldAtlasModule.ID);
            m.openScreen();
        });
        s.waitUpTo("atlas screen", 20, () -> H.mc().gui.screen() instanceof WorldAtlasScreen);
        s.waitTicks(10);
        s.run("atlas close", () -> {
            boolean opened = H.mc().gui.screen() instanceof WorldAtlasScreen;
            H.mc().gui.setScreen(null);
            s.check("feature: world atlas opens and closes", opened && H.mc().gui.screen() == null
                    && on(WorldAtlasModule.ID), "opened " + opened + ", module on " + on(WorldAtlasModule.ID));
        });
    }

    private static void sonar(Scenario s) {
        s.run("sonar pulse", () -> {
            H.enable(SonarPulseModule.ID, true);
            SonarPulseModule m = H.module(SonarPulseModule.ID);
            m.pulse();
        });
        s.waitTicks(40);
        s.run("sonar result", () -> {
            SonarPulseModule m = H.module(SonarPulseModule.ID);
            Object req = field(m, "pulseRequested");
            s.check("feature: sonar pulse runs", on(SonarPulseModule.ID) && Boolean.FALSE.equals(req),
                    "module on " + on(SonarPulseModule.ID) + ", " + H.fields(m, "pulseRequested", "originX",
                            "originY", "originZ"));
        });
    }

    private static void cavity(Scenario s) {
        s.run("cavity rescan", () -> {
            H.enable(CavityTomographyModule.ID, true);
            CavityTomographyModule m = H.module(CavityTomographyModule.ID);
            m.rescan();
        });
        s.waitTicks(60);
        s.run("cavity result", () -> {
            CavityTomographyModule m = H.module(CavityTomographyModule.ID);
            s.check("feature: cavity rescan runs", on(CavityTomographyModule.ID)
                            && Boolean.FALSE.equals(field(m, "scanRequested")),
                    "module on " + on(CavityTomographyModule.ID) + ", " + H.fields(m, "scanRequested",
                            "lastChunkX", "lastChunkZ"));
        });
    }

    private static void redstone(Scenario s) {
        s.cmd(() -> "setblock " + (Arena.cx - 6) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 3)
                + " minecraft:lever[face=floor,powered=true]");
        s.cmd(() -> Arena.fill(-6, 1, 4, -6, 1, 6, "minecraft:redstone_wire"));
        s.cmd(() -> face(-5.5, 0.5, -5.5, 1.1, 3.5));
        s.waitTicks(2);
        s.run("aim at the lever", () -> lookAt(-5.5, 1.1, 3.5));
        s.run("tracer on", () -> H.enable("redstone_tracer", true));
        s.waitTicks(30);
        s.run("tracer result", () -> {
            IModule m = module("redstone_tracer");
            Object nodes = m == null ? null : field(m, "nodes");
            boolean traced = nodes instanceof Integer n && n > 0;
            s.check("feature: redstone tracer traces a lever + dust line", on("redstone_tracer") && traced,
                    (m == null ? "no module" : H.fields(m, "nodes", "tracedPos")) + ", " + hitInfo());
        });
    }

    private static void boat(Scenario s) {
        s.cmd(() -> Arena.fill(8, 1, -12, 14, 1, -2, "minecraft:packed_ice"));
        s.cmd(() -> "summon minecraft:oak_boat " + (Arena.cx + 11.5) + " " + (Arena.FLOOR + 2) + " " + (Arena.cz - 7.5));
        s.cmd(() -> "tp @s " + (Arena.cx + 11.5) + " " + (Arena.FLOOR + 2) + " " + (Arena.cz - 7.5));
        s.cmd("ride @s mount @e[type=minecraft:oak_boat,limit=1,sort=nearest]");
        s.run("boat line on", () -> H.enable("boat_line", true));
        s.waitUpTo("in the boat", 40, () -> H.p().getVehicle() != null);
        s.waitTicks(20);
        s.run("boat line result", () -> {
            IModule m = module("boat_line");
            boolean riding = H.p().getVehicle() != null;
            s.check("feature: boat line ticks in a boat on ice", on("boat_line") && riding
                            && m != null && Boolean.TRUE.equals(field(m, "active")),
                    "riding " + riding + ", " + (m == null ? "no module" : H.fields(m, "active", "hasCorner")));
        });
        s.cmd("ride @s dismount");
        s.cmd("kill @e[type=minecraft:oak_boat]");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));
    }

    private static void holoCards(Scenario s) {
        s.cmd(() -> "setblock " + (Arena.cx - 3) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 3)
                + " minecraft:chest");
        s.cmd(() -> face(-2.5, 0.5, -2.5, 1.5, 3.5));
        s.waitTicks(2);
        s.run("aim at the chest", () -> lookAt(-2.5, 1.5, 3.5));
        s.run("holo cards on", () -> H.enable(HoloCardModule.ID, true));
        HoloCardModule[] mod = new HoloCardModule[1];
        s.waitUpTo("card shown", 60, () -> {
            mod[0] = H.module(HoloCardModule.ID);
            return mod[0].book().shownKey() != HoloCardBook.NO_TARGET;
        });
        s.run("holo cards result", () -> {
            HoloCardModule m = H.module(HoloCardModule.ID);
            long key = m.book().shownKey();
            s.check("feature: holo card at a chest", on(HoloCardModule.ID) && key != HoloCardBook.NO_TARGET,
                    "shown key " + key + ", card " + m.book().card.kind + " '" + m.book().card.title + "', " + hitInfo());
        });
    }

    /**
     * The sky colour of the last extracted frame (26.3: vanilla stores it in the sky render state, where the
     * Atmosphere tint is applied; 1.21.1 read {@code ClientLevel.getSkyColor}). Zero when no frame has a sky yet.
     */
    private static net.minecraft.world.phys.Vec3 skyColor() {
        org.joml.Vector3fc c = H.mc().gameRenderer.gameRenderState().levelRenderState.skyRenderState.skyColor;
        return c == null ? net.minecraft.world.phys.Vec3.ZERO : new net.minecraft.world.phys.Vec3(c.x(), c.y(), c.z());
    }

    private static void sky(Scenario s) {
        double[] vanilla = new double[3];
        s.cmd("time set 6000");
        s.run("sky vanilla", () -> {
            var c = skyColor();
            vanilla[0] = c.x; vanilla[1] = c.y; vanilla[2] = c.z;
        });
        s.run("atmosphere sky ramp on", () -> {
            H.enable("atmosphere", true);
            H.setting("atmosphere", "preset", "SKY_RAMP");
        });
        s.waitTicks(10);
        Shots.add(s, "atmosphere-sky-ramp");
        s.run("sky result", () -> {
            var c = skyColor();
            double d = Math.abs(c.x - vanilla[0]) + Math.abs(c.y - vanilla[1]) + Math.abs(c.z - vanilla[2]);
            s.check("feature: atmosphere recolours the sky", on("atmosphere") && d > 0.02,
                    String.format("vanilla (%.3f %.3f %.3f) -> (%.3f %.3f %.3f), delta %.3f",
                            vanilla[0], vanilla[1], vanilla[2], c.x, c.y, c.z, d));
        });
        s.run("atmosphere off", () -> H.enable("atmosphere", false));
    }

    private static void pip(Scenario s) {
        s.run("pip full render on", () -> {
            H.enable(com.utilitysuite.world.replay.PipCameraModule.ID, true);
            H.setting(com.utilitysuite.world.replay.PipCameraModule.ID, "render", "FULL");
        });
        s.waitTicks(40);
        Shots.add(s, "pip-full-render");
        s.run("pip result", () -> {
            IModule m = module(com.utilitysuite.world.replay.PipCameraModule.ID);
            boolean valid = Boolean.TRUE.equals(field(m, "fullValid"));
            boolean failed = Boolean.TRUE.equals(field(m, "fullFailed"));
            s.check("feature: picture-in-picture full render draws a frame", on(com.utilitysuite.world.replay.PipCameraModule.ID)
                            && valid && !failed,
                    H.fields(m, "fullValid", "fullFailed", "rendererMod", "renderMode"));
        });
        s.run("pip off", () -> H.enable(com.utilitysuite.world.replay.PipCameraModule.ID, false));
    }

    private static void replay(Scenario s) {
        s.run("replay on", () -> H.enable(ReplayTheatreModule.ID, true));
        s.waitTicks(40); // a couple of seconds in the ring
        s.run("replay freeze + open", () -> {
            ReplayTheatreModule m = H.module(ReplayTheatreModule.ID);
            m.ring().setFrozen(true); // what the freeze key does
            H.mc().gui.setScreen(new ReplayScreen(m));
        });
        s.waitTicks(10);
        s.run("replay close", () -> {
            ReplayTheatreModule m = H.module(ReplayTheatreModule.ID);
            boolean opened = H.mc().gui.screen() instanceof ReplayScreen;
            boolean frozen = m.ring().frozen();
            int frames = m.ring().frames();
            if (H.mc().gui.screen() != null) {
                H.mc().gui.screen().onClose();
            }
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
            s.check("feature: replay freezes, opens and closes", opened && frozen && frames > 1
                            && !m.ring().frozen() && on(ReplayTheatreModule.ID),
                    "opened " + opened + ", frozen " + frozen + " -> " + m.ring().frozen() + ", frames " + frames);
        });
    }

    private static void palette(Scenario s) {
        s.run("palette open", () -> H.mc().gui.setScreen(new CommandPaletteScreen()));
        s.waitTicks(10);
        s.run("palette close", () -> {
            boolean opened = H.mc().gui.screen() instanceof CommandPaletteScreen;
            if (H.mc().gui.screen() != null) {
                H.mc().gui.screen().onClose();
            }
            s.check("feature: command palette opens and closes", opened && H.mc().gui.screen() == null,
                    "opened " + opened + ", screen after close " + H.mc().gui.screen());
            if (H.mc().gui.screen() != null) {
                H.mc().gui.setScreen(null);
            }
        });
    }

    private static void anvil(Scenario s) {
        s.cmd(() -> "setblock " + (Arena.cx + 3) + " " + (Arena.FLOOR + 1) + " " + (Arena.cz + 3) + " minecraft:anvil");
        s.cmd("clear @s");
        s.cmd("item replace entity @s weapon.mainhand with minecraft:diamond_sword");
        s.cmd("give @s minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:sharpness\":3}]");
        s.cmd("give @s minecraft:enchanted_book[minecraft:stored_enchantments={\"minecraft:unbreaking\":2}]");
        s.run("anvil planner", () -> {
            H.enable("anvil_planner", true);
            com.utilitysuite.tools.enchant.AnvilPlanModule m = H.module("anvil_planner");
            m.refresh();
            List<String> lines = m.lines();
            Object plan = field(m, "plan");
            s.check("feature: anvil planner plans a sword + 2 books", on("anvil_planner") && plan != null
                    && lines != null && !lines.isEmpty(), "plan " + (plan != null) + ", lines " + lines);
        });
        s.cmd("clear @s");
    }

    private static void shapes(Scenario s) {
        s.run("shape plan", () -> {
            SchematicVolume v = ShapeVoxelizer.voxelize(ShapeVoxelizer.Params.of(ShapeVoxelizer.Kind.DOME, 6, 6, 1,
                    "minecraft:stone"));
            int n = v.blockCount();
            s.check("feature: shape generator plans a dome", n > 0,
                    n + " blocks, " + v.sizeX() + "x" + v.sizeY() + "x" + v.sizeZ());
        });
    }

    private static void mapArt(Scenario s) {
        s.run("map art plan", () -> {
            Path png = Harness.dir().resolve("tiny-map-art.png");
            Files.createDirectories(png.getParent());
            try (NativeImage img = new NativeImage(8, 8, false)) {
                for (int y = 0; y < 8; y++) {
                    for (int x = 0; x < 8; x++) {
                        // NativeImage is ABGR: a red/blue checker with a green stripe
                        int abgr = y == 4 ? 0xFF00C000 : ((x + y) & 1) == 0 ? 0xFF0000E0 : 0xFFE00000;
                        img.setPixelABGR(x, y, abgr);
                    }
                }
                img.writeToFile(png);
            }
            int[][] read = MapArtPlanner.readImage(png);
            SchematicVolume v = MapArtPlanner.plan("smoke", read[1], read[0][0], read[0][1],
                    new MapArtPlanner.Options(1, true, false, false));
            int n = v.blockCount();
            s.check("feature: map-art planner plans a tiny generated PNG", n > 0,
                    n + " blocks from " + read[0][0] + "x" + read[0][1] + " px, volume " + v.sizeX() + "x"
                            + v.sizeY() + "x" + v.sizeZ());
        });
    }
}
