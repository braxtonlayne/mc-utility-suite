package com.utilitysuite.devtest;

import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Screenshot;

import java.nio.file.Files;
import java.nio.file.Path;

/** Saves in-game screenshots for the state-of-the-mod write-up (run/playtest/shots/). Dev-only. */
final class Shots {

    private Shots() {
    }

    /** Clears chat so the next frames are uncluttered (call a couple of ticks before {@link #take}). */
    static void clearChat() {
        H.mc().gui.hud.getChat().clearMessages(false);
        H.mc().gui.toastManager().clear();
        H.mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
    }

    /**
     * Captures the last rendered frame of the main target into {@code png}. 26.3 screenshots are an async GPU
     * read-back: the file is written by the callback a frame or so later (the callback owns the image); write
     * errors there are logged. Client thread.
     */
    static void capture(Path png) throws java.io.IOException {
        Files.createDirectories(png.getParent());
        Screenshot.takeScreenshot(H.mc().gameRenderer.mainRenderTarget(), img -> {
            try (NativeImage owned = img) {
                owned.writeToFile(png);
            } catch (Exception e) {
                PlaytestMod.LOG.warn("[playtest] screenshot {} failed: {}", png, e.toString());
            }
        });
    }

    /** Captures the last rendered frame. Client thread. */
    static void take(Scenario s, String name) {
        try {
            Path png = Harness.dir().resolve("shots").resolve(name + ".png");
            capture(png);
            s.note("screenshot " + png.getFileName());
        } catch (Exception e) {
            s.note("screenshot " + name + " failed: " + e);
        }
    }

    /** Scenario steps: clear chat, let two frames render, capture. */
    static void add(Scenario s, String name) {
        s.run("shot " + name + " (prep)", Shots::clearChat);
        s.waitTicks(3);
        s.run("shot " + name, () -> take(s, name));
    }

    // ---- automatic evidence: failure shots and a rolling trace of long steps -----------------------------------------

    /** A trace frame every this many ticks while one step keeps running (long waits: gathering, building, travel). */
    static final int TRACE_EVERY_TICKS = 100;
    /** Trace frames kept per scenario (oldest deleted first), so a long run doesn't fill the disk. */
    static final int TRACE_KEEP = 12;
    private static final java.util.Map<String, java.util.ArrayDeque<Path>> TRACES = new java.util.HashMap<>();
    private static int failures;

    /** Captures the screen right now for a failed check: shots/fail/<nn>-<scenario>-<check>.png. Client thread. */
    static void failure(Scenario s, String check) {
        String name = String.format("%02d-%s-%s", ++failures, s.id, slug(check));
        Path png = Harness.dir().resolve("shots").resolve("fail").resolve(name + ".png");
        if (write(png)) {
            s.note("failure screenshot shots/fail/" + png.getFileName() + " (the last trace frames are in shots/trace/"
                    + s.id + "/)");
        }
    }

    /** One trace frame of a long-running step: shots/trace/<scenario>/<tick>-<step>.png, rolling. Client thread. */
    static void trace(Scenario s, String step, int stepTicks) {
        String name = String.format("%06d-%s-t%d", s.ticks, slug(step), stepTicks);
        Path png = Harness.dir().resolve("shots").resolve("trace").resolve(s.id).resolve(name + ".png");
        if (!write(png)) {
            return;
        }
        java.util.ArrayDeque<Path> q = TRACES.computeIfAbsent(s.id, k -> new java.util.ArrayDeque<>());
        q.addLast(png);
        while (q.size() > TRACE_KEEP) {
            try {
                Files.deleteIfExists(q.removeFirst());
            } catch (Exception ignored) {
                // best effort
            }
        }
    }

    private static boolean write(Path png) {
        try {
            capture(png);
            return true;
        } catch (Exception e) {
            PlaytestMod.LOG.warn("[playtest] screenshot {} failed: {}", png, e.toString());
            return false;
        }
    }

    private static String slug(String s) {
        String t = s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        return t.length() > 48 ? t.substring(0, 48) : t;
    }
}
