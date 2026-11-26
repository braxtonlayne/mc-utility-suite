package com.utilitysuite.devtest;

import com.utilitysuite.world.budget.RenderGovernorModule;
import com.utilitysuite.world.changes.ChangeTrackerModule;
import com.utilitysuite.world.fortress.FortressSpawnModule;
import com.utilitysuite.world.overlays.ProjectileRibbonModule;
import com.utilitysuite.world.seed.SeedLocatorModule;
import com.utilitysuite.world.stealth.StealthVisionModule;
import com.utilitysuite.world.stronghold.StrongholdFinderModule;
import com.utilitysuite.world.trial.TrialChamberModule;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Scenario 17-worldextra: in-game coverage for eight World modules no other scenario touched. Seed Locator (structure
 * candidates and slime AFK spots against vanilla's own placement maths, seed found with OreSim off), Stronghold Finder
 * (two real eye-of-ender throws 250 blocks apart, estimate against the server's nearest stronghold), Fortress Spawns
 * (the world's real nearest fortress, with OreSim off), Trial Chambers (placed vaults and an ominous trial spawner,
 * including a vault that already rewarded us), Change Tracker (snapshot, change blocks, diff), Stealth Vision (Warden,
 * Enderman, Piglin Brute, sculk sensor and wool), Projectile Ribbon (predicted landing against a real pearl and a real
 * arrow) and the FPS Governor (low frame cap steps the tier down, entity culling through the render-dispatcher mixin).
 * Every section turns its module and settings back off and removes what it placed. Select it alone with
 * {@code -PplaytestOnly=WorldExtra}.
 */
final class WorldExtraScenario {

    static final String[] MODULES = {SeedLocatorModule.ID, StrongholdFinderModule.ID, TrialChamberModule.ID,
            ChangeTrackerModule.ID, StealthVisionModule.ID, FortressSpawnModule.ID, ProjectileRibbonModule.ID,
            RenderGovernorModule.ID};

    private WorldExtraScenario() {
    }

    static Scenario build() {
        Scenario s = new Scenario("17-worldextra", "World extras: seed locator, stronghold finder, fortress spawns,"
                + " trial chambers, change tracker, stealth vision, projectile ribbon, FPS governor");
        Arena.ensure(s);
        s.cmd("kill @e[type=!minecraft:player]");
        s.cmd("clear @s");
        s.cmd("effect clear @s");
        s.cmd("effect give @s minecraft:resistance 900 4 true");
        s.cmd("effect give @s minecraft:saturation 900 0 true");
        s.cmd(() -> Arena.tp(0.5, 0.5, 0f));

        WorldExtraSeedTests.add(s);
        WorldExtraOverlayTests.add(s);

        s.always("worldextra: disarm", () -> {
            H.mc().options.keyUse.setDown(false);
            for (String id : MODULES) {
                H.enable(id, false);
            }
            Harness.command("gamemode survival");
            Harness.command("kill @e[type=!minecraft:player]");
            Harness.command("clear @s");
            Harness.command("effect clear @s");
        });
        return s;
    }

    // ---- shared helpers -------------------------------------------------------------------------------------------

    /** A private field of {@code o} (declared on its class or a superclass), or null. */
    static Object field(Object o, String name) {
        for (Class<?> c = o.getClass(); c != null; c = c.getSuperclass()) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(o);
            } catch (NoSuchFieldException e) {
                // try the superclass
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    static int intField(Object o, String name) {
        Object v = field(o, name);
        return v instanceof Integer i ? i : -1;
    }

    /** Runs {@code f} on the integrated server thread; the step polls the future. */
    static <T> CompletableFuture<T> onServer(Supplier<T> f) {
        MinecraftServer server = H.mc().getSingleplayerServer();
        return server.submit(f);
    }

    static <T> T done(CompletableFuture<T> f) {
        return f != null && f.isDone() && !f.isCompletedExceptionally() ? f.join() : null;
    }

    /** Points the camera at an absolute spot (test scaffolding, like FeaturesScenario.lookAt). */
    static void lookAtAbs(double x, double y, double z) {
        var p = H.p();
        double dx = x - p.getX();
        double dy = y - p.getEyeY();
        double dz = z - p.getZ();
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float pitch = (float) -Math.toDegrees(Math.atan2(dy, Math.sqrt(dx * dx + dz * dz)));
        p.setYRot(yaw);
        p.setXRot(pitch);
        p.yRotO = yaw;
        p.xRotO = pitch;
        p.setYHeadRot(yaw);
        p.yHeadRotO = yaw;
    }

    static void lookAt(double lx, double ly, double lz) {
        lookAtAbs(Arena.cx + lx, Arena.FLOOR + ly, Arena.cz + lz);
    }
}
