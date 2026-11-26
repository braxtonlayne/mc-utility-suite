package com.utilitysuite.devtest;

import com.utilitysuite.tools.commands.DotCommands;
import com.utilitysuite.world.oresim.OreSimModule;
import com.utilitysuite.world.seed.BedrockPatternVerifier;
import com.utilitysuite.world.seed.SeedDiffModule;
import com.utilitysuite.world.seed.SeedDiffService;
import com.utilitysuite.world.seed.SeedScannerModule;
import com.utilitysuite.world.seed.Sha256Long;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.client.multiplayer.chat.GuiMessage;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.client.ClientHooks;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Playtest steps for {@link SeedScannerModule}, {@code .seed} dot-commands, live {@code BiomeManager} hash /
 * {@link BedrockPatternVerifier} checks against the integrated server, and {@link SeedDiffModule} live section scans.
 */
final class ReconSeedTests {

    private ReconSeedTests() {
    }

    static void add(Scenario s) {
        seedScannerAndDotCommands(s);
        liveSeedVerification(s);
        seedDiffLiveScan(s);
    }

    private static void seedScannerAndDotCommands(Scenario s) {
        s.run("recon-seed: enable seed_scanner + dot_commands", () -> {
            H.enable(SeedScannerModule.ID, true);
            H.enable(DotCommands.ID, true);
        });
        s.waitTicks(10);
        s.run("recon-seed: seed_scanner status", () -> {
            SeedScannerModule scanner = H.module(SeedScannerModule.ID);
            String status = scanner.statusLine();
            s.check("seed_scanner stays enabled with non-empty status",
                    scanner.isEnabled() && status != null && !status.isBlank(),
                    "enabled=" + scanner.isEnabled() + ", statusLine='" + status + "'");
        });

        s.run("recon-seed: .seed dot-commands", () -> {
            ChatCapture.drain();
            List<String> guiBefore = guiChatMessages();
            String statusOut = ClientHooks.onClientSendMessage(".seed status");
            List<String> statusCaptured = ChatCapture.drain();
            List<String> statusGuiDelta = newGuiMessages(guiBefore, guiChatMessages());

            boolean statusIntercepted = statusOut == null || statusOut.isEmpty();
            boolean hasSeedOutput = containsSeedOutput(statusCaptured) || containsSeedOutput(statusGuiDelta);
            s.check("'.seed status' intercepted locally with seed/scanner output",
                    statusIntercepted && hasSeedOutput,
                    "sendOut='" + statusOut + "', chatCapture=" + statusCaptured + ", guiChat=" + statusGuiDelta);

            ChatCapture.drain();
            List<String> rescanBefore = guiChatMessages();
            String rescanOut = ClientHooks.onClientSendMessage(".seed rescan");
            List<String> rescanCaptured = ChatCapture.drain();
            List<String> rescanGuiDelta = newGuiMessages(rescanBefore, guiChatMessages());

            ChatCapture.drain();
            List<String> resetBefore = guiChatMessages();
            String resetOut = ClientHooks.onClientSendMessage(".seed reset");
            List<String> resetCaptured = ChatCapture.drain();
            List<String> resetGuiDelta = newGuiMessages(resetBefore, guiChatMessages());

            boolean rescanOk = rescanOut == null || rescanOut.isEmpty();
            boolean resetOk = resetOut == null || resetOut.isEmpty();
            s.check("'.seed rescan' and '.seed reset' intercepted locally without throwing",
                    rescanOk && resetOk,
                    "rescanOut='" + rescanOut + "' capture=" + rescanCaptured + " gui=" + rescanGuiDelta
                            + "; resetOut='" + resetOut + "' capture=" + resetCaptured + " gui=" + resetGuiDelta);
        });
    }

    private static void liveSeedVerification(Scenario s) {
        s.run("recon-seed: BiomeManager hash & BedrockPatternVerifier", () -> {
            var server = H.mc().getSingleplayerServer();
            ClientLevel level = H.mc().level;
            long realSeed = server.overworld().getSeed();

            Class<?> scannerCls = Class.forName("com.utilitysuite.world.seed.ClientChunkClueScanner");
            Constructor<?> ctor = scannerCls.getDeclaredConstructor();
            ctor.setAccessible(true);
            Object clueScanner = ctor.newInstance();

            Method readHashedSeed = scannerCls.getDeclaredMethod("readHashedSeed", ClientLevel.class);
            readHashedSeed.setAccessible(true);
            long clientHashed = (Long) readHashedSeed.invoke(clueScanner, level);
            long expectedHashed = Sha256Long.hashLong(realSeed);

            s.check("BiomeManager hashed seed matches Sha256Long.hashLong(realSeed)",
                    clientHashed == expectedHashed,
                    "clientHashed=" + clientHashed + ", expected=" + expectedHashed + ", realSeed=" + realSeed);

            Class<?> sinkCls = Class.forName("com.utilitysuite.world.seed.ClientChunkClueScanner$BedrockSink");
            Method scanBedrock = scannerCls.getDeclaredMethod("scanBedrock",
                    ClientLevel.class, LevelChunk.class, sinkCls);
            scanBedrock.setAccessible(true);

            List<BedrockPatternVerifier.Sample> samples = new ArrayList<>();
            Object sink = Proxy.newProxyInstance(sinkCls.getClassLoader(), new Class<?>[] {sinkCls},
                    (proxy, method, args) -> {
                        if ("onBedrockSample".equals(method.getName())) {
                            samples.add(new BedrockPatternVerifier.Sample(
                                    (BedrockPatternVerifier.Layer) args[0],
                                    (Integer) args[1],
                                    (Integer) args[2],
                                    (Integer) args[3],
                                    (Boolean) args[4]));
                        }
                        return null;
                    });

            int pcx = H.floorX() >> 4;
            int pcz = H.floorZ() >> 4;
            int scannedChunks = 0;
            LevelChunk center = level.getChunkSource().getChunk(pcx, pcz, false);
            if (center != null && !center.isEmpty()) {
                scanBedrock.invoke(clueScanner, level, center, sink);
                scannedChunks++;
            }
            for (int dx = -1; dx <= 1 && samples.size() < 8; dx++) {
                for (int dz = -1; dz <= 1 && samples.size() < 8; dz++) {
                    if (dx == 0 && dz == 0) {
                        continue;
                    }
                    LevelChunk adj = level.getChunkSource().getChunk(pcx + dx, pcz + dz, false);
                    if (adj != null && !adj.isEmpty()) {
                        scanBedrock.invoke(clueScanner, level, adj, sink);
                        scannedChunks++;
                    }
                }
            }

            boolean enough = samples.size() >= 8;
            boolean matchesReal = enough && BedrockPatternVerifier.verifyWorldSeed(realSeed, samples);
            boolean matchesWrong = BedrockPatternVerifier.verifyWorldSeed(realSeed ^ 0x5A5A5A5A5A5A5A5AL, samples);
            s.check("BedrockPatternVerifier verifies live chunk bedrock samples",
                    enough && matchesReal && !matchesWrong,
                    "samples=" + samples.size() + " from " + scannedChunks + " chunk(s) around (" + pcx + "," + pcz
                            + "), dim=" + level.dimension().identifier()
                            + ", matchesReal=" + matchesReal + ", matchesWrong=" + matchesWrong);
        });
    }

    private static void seedDiffLiveScan(Scenario s) {
        boolean[] oreSimWasOn = new boolean[1];
        s.run("recon-seed: ensure OreSim seed profile", () -> {
            OreSimModule oreSim = H.module(OreSimModule.ID);
            oreSimWasOn[0] = oreSim.isEnabled();
            if (!oreSimWasOn[0]) {
                H.enable(OreSimModule.ID, true);
            }
        });
        s.waitUpTo("recon-seed: OreSim profile seedKnown", 40, () -> {
            OreSimModule oreSim = H.module(OreSimModule.ID);
            return oreSim.profile().seedKnown();
        });
        s.run("recon-seed: enable seed_diff", () -> {
            OreSimModule oreSim = H.module(OreSimModule.ID);
            s.check("OreSim GeneratorProfile knows singleplayer seed",
                    oreSim.profile().seedKnown(),
                    "profile=" + oreSim.profile());
            H.setting(SeedDiffModule.ID, "radius_chunks", 3);
            H.setting(SeedDiffModule.ID, "min_confidence", 0.2);
            H.setting(SeedDiffModule.ID, "chunks_per_tick", 4);
            H.enable(SeedDiffModule.ID, true);
        });
        s.waitUpTo("recon-seed: seed_diff scans live sections", 80, () -> {
            SeedDiffModule sd = H.module(SeedDiffModule.ID);
            return sd.isEnabled() && sectionSetSize(sd.service(), "scannedSections") > 0;
        });
        s.run("recon-seed: seed_diff result & cleanup", () -> {
            SeedDiffModule sd = H.module(SeedDiffModule.ID);
            SeedDiffService svc = sd.service();
            int scanned = sectionSetSize(svc, "scannedSections");
            int pending = sectionSetSize(svc, "pendingSections");
            s.check("seed_diff stays enabled and scans live sections",
                    sd.isEnabled() && scanned > 0,
                    "enabled=" + sd.isEnabled() + ", scannedSections=" + scanned
                            + ", pendingSections=" + pending + ", flagged=" + svc.flaggedCount()
                            + ", renderCount=" + svc.renderCount());

            H.enable(SeedDiffModule.ID, false);
            if (!oreSimWasOn[0]) {
                H.enable(OreSimModule.ID, false);
            }
            int scannedAfter = sectionSetSize(svc, "scannedSections");
            int pendingAfter = sectionSetSize(svc, "pendingSections");
            s.check("seed_diff disables cleanly and clears service state",
                    !sd.isEnabled() && scannedAfter == 0 && pendingAfter == 0
                            && svc.flaggedCount() == 0 && svc.renderCount() == 0,
                    "enabled=" + sd.isEnabled() + ", scannedSections=" + scannedAfter
                            + ", pendingSections=" + pendingAfter + ", flagged=" + svc.flaggedCount()
                            + ", renderCount=" + svc.renderCount());
        });
        s.always("recon-seed cleanup", () -> {
            H.enable(SeedDiffModule.ID, false);
            if (!oreSimWasOn[0]) {
                H.enable(OreSimModule.ID, false);
            }
        });
    }

    private static boolean containsSeedOutput(List<String> lines) {
        for (String line : lines) {
            String lower = line.toLowerCase(Locale.ROOT);
            if (lower.contains("seed") || lower.contains("clues:") || lower.contains("status:")) {
                return true;
            }
        }
        return false;
    }

    private static int sectionSetSize(SeedDiffService service, String fieldName) {
        try {
            Field f = SeedDiffService.class.getDeclaredField(fieldName);
            f.setAccessible(true);
            Object val = f.get(service);
            if (val instanceof LongOpenHashSet set) {
                return set.size();
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // fall through
        }
        return -1;
    }

    private static List<String> guiChatMessages() {
        List<String> out = new ArrayList<>();
        try {
            ChatComponent chat = H.mc().gui.hud.getChat();
            Field f = ChatComponent.class.getDeclaredField("allMessages");
            f.setAccessible(true);
            Object raw = f.get(chat);
            if (raw instanceof List<?> list) {
                for (Object item : list) {
                    if (item instanceof GuiMessage gm) {
                        out.add(gm.content().getString());
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // optional fallback
        }
        return out;
    }

    private static List<String> newGuiMessages(List<String> before, List<String> after) {
        int added = Math.max(0, after.size() - before.size());
        return added == 0 ? List.of() : new ArrayList<>(after.subList(0, added));
    }
}
