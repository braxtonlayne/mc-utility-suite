package com.utilitysuite.devtest;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.utilitysuite.travel.movement.MovementAssist;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.worldselection.CreateWorldScreen;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.network.chat.Component;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.ClientChatEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Dev-only live run of the parkour benchmark course ({@code ./gradlew runCourse}, inert unless
 * {@code -Dutilitysuite.course=true}). Creates a fresh world, builds the course from {@link CourseFile}, and times the
 * owner's runs with {@link CourseTimer}: a clock on screen, splits per checkpoint, falls put back on the last
 * checkpoint. Every finished run is appended to {@code run/course/results.jsonl} with the assist mode it was run in.
 * <p>
 * Chat commands (not sent to the server): {@code !mode off|corrections|copilot|takeover}, {@code !reset},
 * {@code !stats}, {@code !help}. Test scaffolding: allocation is fine here.
 */
final class CourseLive {

    static final String WORLD = "utilitysuite-course";
    static final long SEED = 8675309L;
    /** The course origin sits this high: above any terrain of the seed near spawn. */
    static final int ORIGIN_Y = 200;
    static final int BUILDS_PER_TICK = 24;

    enum Phase { BOOT, CREATE, CONFIGURE, LOADING, BUILD, READY }

    private Phase phase = Phase.BOOT;
    private int phaseTicks;
    private CourseFile course;
    private CourseTimer timer;
    private int ox, oy, oz;
    private int buildIndex;
    private String runMode = "OFF";
    private boolean modeChanged;
    private int finishedHold = -1;
    /** A teleport is on its way: until the body arrives (or 20 ticks) the timer only counts time, so the old
     *  position (still on the fail floor) is not read as another fall. */
    private int tpGrace;
    private double tpX, tpY, tpZ;
    private float tpYaw;
    private int tpTries;
    /** The automated runner, when {@code -Dutilitysuite.courseAuto} is set (null for human runs). */
    private CourseAuto auto;

    void register(IEventBus bus) {
        bus.addListener(this::onTick);
        bus.addListener(this::onChat);
        bus.addListener(this::onHud);
    }

    private void go(Phase p) {
        phase = p;
        phaseTicks = 0;
    }

    private static Minecraft mc() {
        return Minecraft.getInstance();
    }

    private void onTick(ClientTickEvent.Post e) {
        Minecraft mc = mc();
        phaseTicks++;
        try {
            switch (phase) {
                case BOOT -> {
                    if (mc.screen instanceof TitleScreen && mc.getOverlay() == null && phaseTicks > 20) {
                        course = CourseFile.load();
                        timer = new CourseTimer(course);
                        deleteWorld(mc);
                        CreateWorldScreen.openFresh(mc, mc.screen);
                        go(Phase.CREATE);
                    }
                }
                case CREATE -> {
                    if (mc.screen instanceof CreateWorldScreen s && phaseTicks > 5) {
                        WorldCreationUiState ui = s.getUiState();
                        ui.setName(WORLD);
                        ui.setGameMode(WorldCreationUiState.SelectedGameMode.SURVIVAL);
                        ui.setDifficulty(Difficulty.PEACEFUL);
                        ui.setAllowCommands(true);
                        ui.setSeed(Long.toString(SEED));
                        ui.setBonusChest(false);
                        Harness.pressButton(s, "selectWorld.create");
                        go(Phase.LOADING);
                    }
                }
                case LOADING -> {
                    if (mc.player != null && mc.level != null && mc.screen == null && mc.getOverlay() == null
                            && phaseTicks > 40) {
                        var spawn = mc.level.getSharedSpawnPos();
                        ox = spawn.getX();
                        oy = ORIGIN_Y;
                        oz = spawn.getZ() + 32;
                        buildIndex = 0;
                        setup();
                        go(Phase.BUILD);
                    }
                }
                case BUILD -> {
                    if (phaseTicks < 60) {
                        return; // the force-loaded chunks come in first
                    }
                    int end = Math.min(course.builds.size(), buildIndex + BUILDS_PER_TICK);
                    for (; buildIndex < end; buildIndex++) {
                        command(buildCommand(course.builds.get(buildIndex)));
                    }
                    if (buildIndex >= course.builds.size() && phaseTicks > 80) {
                        toStart();
                        setMode("off");
                        say("Course built: " + course.jumps.size() + " jumps, " + course.checkpoints.size()
                                + " checkpoints. Mode OFF (baseline). Run off the start pad to start the clock."
                                + " !help for commands.");
                        String spec = System.getProperty("utilitysuite.courseAuto", "");
                        if (!spec.isBlank()) {
                            auto = new CourseAuto(this, course, spec,
                                    Integer.getInteger("utilitysuite.courseAutoSeeds", 1));
                            say("Automated runner: " + spec);
                        }
                        go(Phase.READY);
                    }
                }
                case READY -> runTick(mc);
                default -> {
                }
            }
        } catch (Exception ex) {
            PlaytestMod.LOG.error("[course] {}", ex.toString(), ex);
            say("Course error: " + ex);
            go(Phase.READY);
        }
    }

    private void setup() {
        for (String g : new String[] {"randomTickSpeed 0", "fallDamage false", "doDaylightCycle false", "doWeatherCycle false",
                "doMobSpawning false", "keepInventory true", "doImmediateRespawn true", "announceAdvancements false"}) {
            command("gamerule " + g);
        }
        command("time set 6000");
        command("weather clear");
        // force-load the course in strips (a forceload command takes at most 256 chunks)
        int x0 = ox + course.minX - 2, x1 = ox + course.maxX + 2;
        for (int z = oz + course.minZ - 2; z <= oz + course.maxZ + 2; z += 16 * 8) {
            int z1 = Math.min(oz + course.maxZ + 2, z + 16 * 8 - 1);
            for (int x = x0; x <= x1; x += 16 * 30) {
                command("forceload add " + x + " " + z + " " + Math.min(x1, x + 16 * 30 - 1) + " " + z1);
            }
        }
        command("tp @s " + (ox + 0.5) + " " + (oy + 1) + " " + (oz + 0.5));
    }

    private String buildCommand(CourseFile.Build b) {
        if (b.single()) {
            return "setblock " + (ox + b.x1()) + " " + (oy + b.y1()) + " " + (oz + b.z1()) + " " + b.block();
        }
        return "fill " + (ox + b.x1()) + " " + (oy + b.y1()) + " " + (oz + b.z1()) + " " + (ox + b.x2()) + " "
                + (oy + b.y2()) + " " + (oz + b.z2()) + " " + b.block();
    }

    private void runTick(Minecraft mc) {
        if (mc.player == null || mc.isPaused() || timer == null) {
            return;
        }
        if (finishedHold >= 0) {
            if (--finishedHold < 0) {
                toStart();
            }
            return;
        }
        double x = mc.player.getX() - ox, y = mc.player.getY() - oy, z = mc.player.getZ() - oz;
        if (tpGrace > 0) {
            double ax = x - tpX, az = z - tpZ;
            if (ax * ax + az * az < 1.0 && Math.abs(y - tpY) < 1.0) {
                tpGrace = 0;
            } else {
                if (--tpGrace == 0 && tpTries < 3) {
                    // the teleport never arrived (seen 2026-11-23: the old position stayed for a whole second and
                    // read as falls again and again): send it again instead of reading the stale position
                    tpTries++;
                    PlaytestMod.LOG.warn(String.format(java.util.Locale.ROOT,
                            "[course] teleport to %.1f %.1f %.1f not arrived (body at %.2f %.2f %.2f), resending",
                            tpX, tpY, tpZ, x, y, z));
                    sendTp();
                }
                if (timer.state == CourseTimer.State.RUNNING) {
                    timer.ticks++;
                }
                return;
            }
        }
        String modeNow = currentMode();
        switch (timer.tick(x, y, z)) {
            case STARTED -> {
                runMode = modeNow;
                modeChanged = false;
                command("clear @s");
                for (CourseFile.Kit k : course.kits) {
                    command("item replace entity @s hotbar." + (k.slot() - 1) + " with " + k.item() + " " + k.count());
                }
            }
            case CHECKPOINT -> {
                CourseFile.Checkpoint cp = course.checkpoints.get(timer.lastCheckpoint);
                say(String.format("CP %d/%d %s  %s", timer.lastCheckpoint + 1, course.checkpoints.size(), cp.name(),
                        CourseTimer.clock(timer.ticks)));
            }
            case FELL -> {
                int k = timer.respawnIndex();
                respawn(k);
                if (auto != null) {
                    auto.onFell(k, timer, x, y, z);
                }
            }
            case FINISHED -> finish();
            default -> {
            }
        }
        if (timer.state == CourseTimer.State.RUNNING && !modeNow.equals(runMode)) {
            modeChanged = true;
        }
        NeoTrace.tick(mc, mc.player.getX() - ox, mc.player.getY() - oy, mc.player.getZ() - oz);
        if (auto != null && finishedHold < 0 && tpGrace == 0) { // not on the tick a respawn teleport was sent
            auto.tick(mc, timer, mc.player.getX() - ox, mc.player.getY() - oy, mc.player.getZ() - oz);
        }
    }

    /** Puts the body back on checkpoint {@code k} (-1 = the start pad). */
    void respawn(int k) {
        if (k < 0) {
            tpRel(course.startX, course.startY, course.startZ, course.startYaw);
        } else {
            CourseFile.Checkpoint cp = course.checkpoints.get(k);
            tpRel(cp.x(), cp.y(), cp.z(), cp.yaw());
        }
    }

    /** Teleports to a course-relative position facing {@code yaw}, pitch level. */
    void tpRel(double x, double y, double z, float yaw) {
        tpX = x;
        tpY = y;
        tpZ = z;
        tpYaw = yaw;
        tpTries = 0;
        sendTp();
    }

    private void sendTp() {
        command("tp @s " + (ox + tpX) + " " + (oy + tpY) + " " + (oz + tpZ) + " " + tpYaw + " 0");
        tpGrace = 20;
    }

    /** The mode the current run was started in. */
    String runModeNow() {
        return runMode;
    }

    private void finish() {
        String time = CourseTimer.clock(timer.ticks);
        JsonObject o = new JsonObject();
        o.addProperty("at", LocalDateTime.now().toString());
        o.addProperty("runner", "human");
        o.addProperty("mode", runMode);
        o.addProperty("modeChangedMidRun", modeChanged);
        o.addProperty("ticks", timer.ticks);
        o.addProperty("seconds", timer.ticks / 20.0);
        o.addProperty("falls", timer.falls);
        JsonArray sp = new JsonArray();
        for (int s : timer.splits) {
            sp.add(s);
        }
        o.add("splitTicks", sp);
        if (auto != null) {
            auto.onFinished(o);
        }
        append(o);
        PlaytestMod.LOG.info("[course] run finished: {}", o);
        say(String.format("FINISH %s  mode %s%s, %d falls. %s", time, runMode,
                modeChanged ? " (mode changed mid-run, kept apart)" : "", timer.falls, summaryLine(runMode)));
        finishedHold = 60;
    }

    /** Appends one run to results.jsonl. */
    static void append(JsonObject o) {
        try {
            Path f = resultsFile();
            Files.createDirectories(f.getParent());
            Files.writeString(f, o + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException ex) {
            PlaytestMod.LOG.error("[course] could not save the run", ex);
        }
    }

    void toStart() {
        timer.reset();
        tpRel(course.startX, course.startY, course.startZ, course.startYaw);
    }

    // ---- modes ----------------------------------------------------------------------------------------------------

    private static String currentMode() {
        var m = H.<MovementAssist>module(MovementAssist.ID);
        return m.isEnabled() ? m.controlMode().name() : "OFF";
    }

    void setMode(String arg) {
        String a = arg.toLowerCase(Locale.ROOT);
        switch (a) {
            case "off", "baseline" -> H.enable(MovementAssist.ID, false);
            case "corrections", "nudge" -> on(MovementAssist.ControlMode.CORRECTIONS);
            case "copilot", "co-pilot", "guide" -> on(MovementAssist.ControlMode.CO_PILOT);
            case "takeover", "carry" -> on(MovementAssist.ControlMode.TAKEOVER);
            default -> {
                say("Unknown mode '" + arg + "': off, corrections, copilot, takeover");
                return;
            }
        }
        if (timer != null && timer.state != CourseTimer.State.IDLE) {
            toStart(); // a mode switch starts a fresh run, so every run has one mode
        }
        say("Mode " + currentMode() + ". Back on the start pad.");
    }

    private static void on(MovementAssist.ControlMode m) {
        H.setting(MovementAssist.ID, "control_mode", m.name());
        H.enable(MovementAssist.ID, true);
    }

    // ---- chat commands --------------------------------------------------------------------------------------------

    private void onChat(ClientChatEvent e) {
        String msg = e.getMessage().strip();
        if (!msg.startsWith("!")) {
            return;
        }
        e.setCanceled(true);
        String[] t = msg.substring(1).split("\\s+");
        switch (t[0].toLowerCase(Locale.ROOT)) {
            case "mode" -> setMode(t.length > 1 ? t[1] : "");
            case "reset", "r" -> {
                toStart();
                say("Back on the start pad (run not saved).");
            }
            case "stats" -> stats();
            default -> say("!mode off|corrections|copilot|takeover   !reset   !stats  (every finished run is saved"
                    + " to run/course/results.jsonl)");
        }
    }

    private void stats() {
        Map<String, List<Integer>> by = byMode();
        if (by.isEmpty()) {
            say("No finished runs yet.");
            return;
        }
        for (var en : by.entrySet()) {
            say(en.getKey() + ": " + describe(en.getValue()));
        }
    }

    private String summaryLine(String mode) {
        Map<String, List<Integer>> by = byMode();
        List<Integer> mine = by.get(mode);
        List<Integer> base = by.get("OFF");
        String s = mode + " " + (mine == null ? "" : describe(mine));
        if (!"OFF".equals(mode) && base != null && mine != null) {
            double d = median(mine) - median(base);
            s += String.format(" | vs baseline median %s: %+.2f s", CourseTimer.clock((int) median(base)), d / 20.0);
        }
        return s;
    }

    private static String describe(List<Integer> t) {
        int best = t.stream().mapToInt(Integer::intValue).min().orElse(0);
        return String.format("%d runs, median %s, best %s", t.size(), CourseTimer.clock((int) median(t)),
                CourseTimer.clock(best));
    }

    private static double median(List<Integer> t) {
        List<Integer> s = new ArrayList<>(t);
        s.sort(null);
        int n = s.size();
        return n % 2 == 1 ? s.get(n / 2) : (s.get(n / 2 - 1) + s.get(n / 2)) / 2.0;
    }

    /** Completed human runs per mode, runs with a mid-run mode change left out. */
    private static Map<String, List<Integer>> byMode() {
        Map<String, List<Integer>> by = new LinkedHashMap<>();
        try {
            Path f = resultsFile();
            if (!Files.exists(f)) {
                return by;
            }
            for (String line : Files.readAllLines(f, StandardCharsets.UTF_8)) {
                if (line.isBlank()) {
                    continue;
                }
                JsonObject o = JsonParser.parseString(line).getAsJsonObject();
                if (!"human".equals(o.get("runner").getAsString()) || o.get("modeChangedMidRun").getAsBoolean()) {
                    continue;
                }
                by.computeIfAbsent(o.get("mode").getAsString(), k -> new ArrayList<>()).add(o.get("ticks").getAsInt());
            }
        } catch (IOException | RuntimeException ex) {
            PlaytestMod.LOG.warn("[course] could not read results", ex);
        }
        return by;
    }

    static Path resultsFile() {
        return mc().gameDirectory.toPath().resolve("course").resolve("results.jsonl");
    }

    // ---- HUD ------------------------------------------------------------------------------------------------------

    private void onHud(RenderGuiEvent.Post e) {
        if (phase != Phase.READY || timer == null || mc().player == null) {
            return;
        }
        GuiGraphics g = e.getGuiGraphics();
        String mode = timer.state == CourseTimer.State.IDLE ? currentMode() : runMode;
        String line = switch (timer.state) {
            case IDLE -> "COURSE  ready  [" + mode + "]  run off the pad to start";
            case RUNNING -> String.format("COURSE  %s  CP %d/%d  falls %d  [%s]", CourseTimer.clock(timer.ticks),
                    timer.lastCheckpoint + 1, course.checkpoints.size(), timer.falls, mode);
            case FINISHED -> String.format("COURSE  FINISH %s  falls %d  [%s]", CourseTimer.clock(timer.ticks),
                    timer.falls, mode);
        };
        g.fill(4, 4, 10 + mc().font.width(line), 17, 0x90000000);
        g.drawString(mc().font, line, 7, 6, 0xFFFFFFFF, false);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private static void command(String cmd) {
        Minecraft mc = mc();
        if (mc.player != null) {
            mc.player.connection.sendCommand(cmd);
        }
    }

    private void say(String s) {
        if (mc().player != null) {
            mc().player.displayClientMessage(Component.literal(s), false);
        }
        PlaytestMod.LOG.info("[course] {}", s);
    }

    private static void deleteWorld(Minecraft mc) throws IOException {
        LevelStorageSource src = mc.getLevelSource();
        if (src.levelExists(WORLD)) {
            try (LevelStorageSource.LevelStorageAccess access = src.createAccess(WORLD)) {
                access.deleteLevel();
            }
        }
    }
}
