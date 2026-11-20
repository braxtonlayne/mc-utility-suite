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
        H.mc().gui.getChat().clearMessages(false);
        H.mc().getToasts().clear();
        H.mc().getTutorial().setStep(net.minecraft.client.tutorial.TutorialSteps.NONE);
    }

    /** Captures the last rendered frame. Client thread. */
    static void take(Scenario s, String name) {
        try {
            Path png = Harness.dir().resolve("shots").resolve(name + ".png");
            Files.createDirectories(png.getParent());
            try (NativeImage img = Screenshot.takeScreenshot(H.mc().getMainRenderTarget())) {
                img.writeToFile(png);
            }
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
}
