package com.utilitysuite.devtest;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderFrameEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * The playtest driver. On the title screen it deletes and recreates the fixed-seed world {@value #WORLD}, waits for the
 * player, then runs every scenario step by step on the client tick. At the end (or on the global timeout) it leaves the
 * world, checks the saved config files, writes {@code run/playtest/report.md} + {@code report.json} and stops the
 * client so the run exits on its own.
 */
public final class Harness {

    public static final String WORLD = "utilitysuite-playtest";
    public static final long SEED = 8_675_309L;
    private static final long GLOBAL_TIMEOUT_MS = Long.getLong("utilitysuite.playtest.timeoutMs", 40 * 60_000L); // the full suite runs about 27 min (2026-11-25)
    private static final int WORLD_SETTLE_TICKS = 100;

    enum Phase { BOOT, CREATE_REQUESTED, CONFIGURE, LOADING, SETTLE, RUN, LEAVING, AFTER_LEAVE, DONE }

    static Harness instance;

    final LogCapture logs = LogCapture.install();
    final Perf perf = new Perf();
    final List<Scenario> finished = new ArrayList<>();
    final List<String> unrelatedErrors = new ArrayList<>();
    final List<Supplier<Scenario>> queue = new ArrayList<>();
    final long launchMillis = System.currentTimeMillis();
    final List<String> perfLines = new ArrayList<>();
    final List<Perf.Window> perfWindows = new ArrayList<>();
    Phase phase = Phase.BOOT;
    int phaseTicks;
    Scenario current;
    static volatile String menuClosedBy = "";
    int stepIndex;
    int stepTicks;
    long worldReadyMillis;
    boolean reportWritten;
    Scenario bootChecks = new Scenario("1-boot", "Boot");
    Scenario afterLeave;

    Harness() {
        instance = this;
        Scenarios.addAll(queue);
    }

    void register(IEventBus bus) {
        bus.addListener(EventPriority.HIGHEST, ClientTickEvent.Pre.class, e -> perf.onTickPre());
        bus.addListener(EventPriority.LOWEST, ClientTickEvent.Post.class, e -> {
            perf.onTickPost();
            onTick();
        });
        bus.addListener(EventPriority.HIGHEST, RenderFrameEvent.Pre.class, e -> perf.onFrame());
        // Diagnostic: who closes the suite menu (a run once saw it close by itself right after opening).
        bus.addListener(net.neoforged.neoforge.client.event.ScreenEvent.Closing.class, e -> {
            if (e.getScreen() instanceof com.utilitysuite.ui.SuiteMenuScreen) {
                StringBuilder sb = new StringBuilder();
                for (StackTraceElement el : new Throwable().getStackTrace()) {
                    sb.append(" < ").append(el.getClassName().substring(el.getClassName().lastIndexOf('.') + 1))
                            .append('.').append(el.getMethodName());
                }
                menuClosedBy = sb.toString();
                PlaytestMod.LOG.info("[playtest] suite menu closed{}", menuClosedBy);
            }
        });
        bus.addListener(EventPriority.LOWEST, net.neoforged.neoforge.client.event.ClientChatReceivedEvent.class,
                ChatCapture::onChat);
        Thread watchdog = new Thread(this::watchdog, "utilitysuite-playtest-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    // ---- helpers for scenarios ------------------------------------------------------------------------------------

    static Minecraft mc() {
        return Minecraft.getInstance();
    }

    static void command(String cmd) {
        Minecraft mc = mc();
        if (mc.player != null) {
            PlaytestMod.LOG.info("[playtest] /{}", cmd);
            mc.player.connection.sendCommand(cmd);
        }
    }

    static Path dir() {
        return mc().gameDirectory.toPath().resolve("playtest");
    }

    // ---- main loop ------------------------------------------------------------------------------------------------

    private void onTick() {
        Minecraft mc = mc();
        phaseTicks++;
        try {
            switch (phase) {
                case BOOT -> {
                    if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && phaseTicks > 20) {
                        mc.options.pauseOnLostFocus = false;
                        Scenarios.bootPreWorld(bootChecks);
                        deleteWorld(mc);
                        CreateWorldScreen.openFresh(mc, mc.screen);
                        go(Phase.CREATE_REQUESTED);
                    }
                }
                case CREATE_REQUESTED -> {
                    if (mc.screen instanceof CreateWorldScreen) {
                        go(Phase.CONFIGURE);
                    } else if (phaseTicks > 1200) {
                        throw new IllegalStateException("Create World screen never opened");
                    }
                }
                case CONFIGURE -> {
                    if (phaseTicks < 5) {
                        return;
                    }
                    CreateWorldScreen s = (CreateWorldScreen) mc.screen;
                    WorldCreationUiState ui = s.getUiState();
                    ui.setName(WORLD);
                    ui.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                    ui.setDifficulty(Difficulty.NORMAL);
                    ui.setAllowCommands(true);
                    ui.setSeed(Long.toString(SEED));
                    ui.setBonusChest(false);
                    if (!WORLD.equals(ui.getTargetFolder())) {
                        throw new IllegalStateException("World folder resolved to " + ui.getTargetFolder());
                    }
                    pressButton(s, "selectWorld.create");
                    go(Phase.LOADING);
                }
                case LOADING -> {
                    if (mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null) {
                        go(Phase.SETTLE);
                    } else if (phaseTicks > 20 * 60 * 5) {
                        throw new IllegalStateException("World did not load within 5 minutes");
                    }
                }
                case SETTLE -> {
                    if (phaseTicks >= WORLD_SETTLE_TICKS) {
                        worldReadyMillis = System.currentTimeMillis();
                        go(Phase.RUN);
                    }
                }
                case RUN -> runScenarios(mc);
                case LEAVING -> {
                    if (phaseTicks == 1) {
                        if (mc.level != null) {
                            mc.level.disconnect();
                        }
                        mc.disconnect(new GenericMessageScreen(Component.literal("Playtest finished")));
                        mc.setScreen(new TitleScreen());
                    } else if (mc.level == null && phaseTicks > 40) {
                        go(Phase.AFTER_LEAVE);
                    } else if (phaseTicks > 20 * 60) {
                        go(Phase.AFTER_LEAVE);
                    }
                }
                case AFTER_LEAVE -> {
                    if (phaseTicks >= 20) {
                        afterLeave = new Scenario("1b-config-saved", "Config files written on leave");
                        Scenarios.afterLeave(afterLeave);
                        finished.add(afterLeave);
                        finish("all scenarios ran");
                    }
                }
                case DONE -> {
                }
            }
        } catch (Throwable t) {
            PlaytestMod.LOG.error("[playtest] harness failure in phase {}", phase, t);
            bootChecks.check("harness phase " + phase, false, t.toString());
            if (phase.ordinal() < Phase.RUN.ordinal()) {
                finish("harness failure: " + t);
            } else if (phase == Phase.RUN) {
                go(Phase.LEAVING);
            } else {
                finish("harness failure after run: " + t);
            }
        }
    }

    private void go(Phase p) {
        PlaytestMod.LOG.info("[playtest] phase {} -> {}", phase, p);
        phase = p;
        phaseTicks = 0;
    }

    private void runScenarios(Minecraft mc) {
        if (current == null) {
            if (queue.isEmpty()) {
                go(Phase.LEAVING);
                return;
            }
            Supplier<Scenario> next = queue.remove(0);
            try {
                current = next.get();
            } catch (Throwable t) {
                Scenario s = new Scenario("?", "scenario build failed");
                s.check("build", false, t.toString());
                finished.add(s);
                PlaytestMod.LOG.error("[playtest] scenario build failed", t);
                return;
            }
            current.startNanos = System.nanoTime();
            stepIndex = 0;
            stepTicks = 0;
            logs.drain();
            PlaytestMod.LOG.info("[playtest] === scenario {} ({}) ===", current.id, current.title);
        }
        Scenario s = current;
        s.ticks++;
        drainLogs(s);
        if (mc.player == null) {
            s.aborted = true;
            s.abortReason = "player left the world";
            endScenario();
            go(Phase.LEAVING);
            return;
        }
        if (mc.player.isDeadOrDying() && !s.deathExpected) {
            // A death invalidates the scenario (inventory gone, respawn far away): record it and respawn so the
            // remaining scenarios still run.
            if (mc.player.deathTime == 1) {
                s.check("player stays alive", false, "player died during step '" + (stepIndex < s.steps.size()
                        ? s.steps.get(stepIndex).name() : "?") + "' at " + H.posStr());
            }
            if (mc.player.deathTime >= 20) {
                mc.player.respawn();
                if (mc.screen instanceof net.minecraft.client.gui.screens.DeathScreen) {
                    mc.setScreen(null);
                }
            }
        }
        // Skip non-cleanup steps after an abort.
        while (stepIndex < s.steps.size() && s.aborted && !s.steps.get(stepIndex).always()) {
            stepIndex++;
        }
        if (stepIndex >= s.steps.size()) {
            endScenario();
            return;
        }
        Scenario.StepDef def = s.steps.get(stepIndex);
        boolean done;
        try {
            done = def.step().tick(stepTicks);
        } catch (Throwable t) {
            done = true;
            String msg = t instanceof Scenario.StepTimeout ? t.getMessage() : def.name() + " threw " + t;
            s.check(def.name(), false, msg);
            if (!(t instanceof Scenario.StepTimeout)) {
                PlaytestMod.LOG.error("[playtest] step {} threw", def.name(), t);
            }
            if (!def.always()) {
                s.aborted = true;
                s.abortReason = msg;
            }
        }
        if (done) {
            stepIndex++;
            stepTicks = 0;
        } else {
            stepTicks++;
            if (stepTicks % Shots.TRACE_EVERY_TICKS == 0) {
                Shots.trace(s, def.name(), stepTicks);
            }
        }
    }

    private void endScenario() {
        Scenario s = current;
        s.endNanos = System.nanoTime();
        drainLogs(s);
        finished.add(s);
        PlaytestMod.LOG.info("[playtest] === scenario {} -> {} ===", s.id, s.status());
        current = null;
        // Persist partial results after every scenario so a crash still leaves a report.
        writeReport("in progress");
    }

    private void drainLogs(Scenario s) {
        for (String e : logs.drain()) {
            if (e.contains("[playtest]")) {
                continue;
            }
            boolean ours = e.contains("utilitysuite") || e.contains("baritone");
            boolean error = e.startsWith("ERROR") || e.startsWith("FATAL") || e.contains(" | ");
            if (ours && error) {
                s.errors.add(e);
            } else if (ours) {
                s.notes.add("log: " + e);
            } else if (error) {
                unrelatedErrors.add("[" + s.id + "] " + e);
            }
        }
    }

    static void pressButton(net.minecraft.client.gui.screens.Screen screen, String translationKey) {
        for (GuiEventListener l : screen.children()) {
            if (l instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals(translationKey)) {
                b.onPress();
                return;
            }
        }
        throw new IllegalStateException("No button " + translationKey + " on " + screen.getClass().getSimpleName());
    }

    private static void deleteWorld(Minecraft mc) throws IOException {
        LevelStorageSource src = mc.getLevelSource();
        if (src.levelExists(WORLD)) {
            try (LevelStorageSource.LevelStorageAccess access = src.createAccess(WORLD)) {
                access.deleteLevel();
            }
            PlaytestMod.LOG.info("[playtest] deleted old world {}", WORLD);
        }
    }

    // ---- end --------------------------------------------------------------------------------------------------------

    private void watchdog() {
        try {
            Thread.sleep(GLOBAL_TIMEOUT_MS);
        } catch (InterruptedException e) {
            return;
        }
        PlaytestMod.LOG.error("[playtest] global timeout after {} ms", GLOBAL_TIMEOUT_MS);
        mc().execute(() -> {
            if (current != null) {
                current.aborted = true;
                current.abortReason = "global timeout";
                current.endNanos = System.nanoTime();
                finished.add(current);
                current = null;
            }
            finish("global timeout");
        });
        try {
            Thread.sleep(90_000L);
        } catch (InterruptedException e) {
            return;
        }
        synchronized (this) {
            if (!reportWritten) {
                writeReport("global timeout (client thread unresponsive)");
            }
        }
        Runtime.getRuntime().halt(3);
    }

    private void finish(String why) {
        if (phase == Phase.DONE) {
            return;
        }
        go(Phase.DONE);
        writeReport(why);
        synchronized (this) {
            reportWritten = true;
        }
        PlaytestMod.LOG.info("[playtest] finished ({}); stopping the client", why);
        mc().stop();
    }

    synchronized void writeReport(String outcome) {
        try {
            Path dir = dir();
            Files.createDirectories(dir);
            List<Scenario> all = new ArrayList<>();
            all.add(bootChecks);
            all.addAll(finished);
            Files.writeString(dir.resolve("report.md"), Report.markdown(this, all, outcome), StandardCharsets.UTF_8);
            Files.writeString(dir.resolve("report.json"), new GsonBuilder().setPrettyPrinting().create()
                    .toJson(Report.json(this, all, outcome)), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            PlaytestMod.LOG.error("[playtest] could not write the report", t);
        }
    }

    static String now() {
        return LocalDateTime.now().toString();
    }

    static String fmt(String f, Object... a) {
        return String.format(Locale.ROOT, f, a);
    }

    @SuppressWarnings("unused")
    private static JsonArray arr(List<String> xs) {
        JsonArray a = new JsonArray();
        xs.forEach(a::add);
        return a;
    }

    @SuppressWarnings("unused")
    private static JsonObject obj() {
        return new JsonObject();
    }
}
