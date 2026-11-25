package com.utilitysuite.ui.tabs;

import com.utilitysuite.core.CoreBootstrap;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.config.ConfigDiff;
import com.utilitysuite.core.config.ProfileBundle;
import com.utilitysuite.core.input.KeybindManager;
import com.utilitysuite.ui.ConfigUndo;
import com.utilitysuite.ui.Panel;
import com.utilitysuite.ui.ProfileCopier;
import com.utilitysuite.ui.kit.PillButton;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * Tools tab → Profiles: profile bundles and setting undo. Export writes the active profile's module settings (Local
 * Rules, pinned slots and the rest live there) plus the suite key bindings to one JSON file under
 * {@code <gameDir>/utilitysuite/bundles/}. Import first shows a side-by-side diff (key: old → new) of what the bundle
 * would change; Apply writes it through {@link ProfileCopier} (suite-wide {@code core} settings excluded) and rebinds
 * keys. Undo reverts the last of up to {@link ConfigUndo#STEPS} setting changes.
 */
final class BundlePanel {

    private static final int MAX_DIFF_ROWS = 14;

    /** The bundle being previewed (client thread), or null. */
    private static ProfileBundle pending;
    private static String pendingName;
    private static List<ConfigDiff.Change> pendingChanges = List.of();

    private BundlePanel() {
    }

    static Path dir() {
        return Minecraft.getInstance().gameDirectory.toPath().resolve("utilitysuite").resolve("bundles");
    }

    static void render(Panel p) {
        ProfileConfig active = Services.modState().profile();
        int w = p.width();
        p.card("Bundles and undo", "one-file export of settings, rules, pinned slots and keys");
        String undo = ConfigUndo.peekLabel();
        p.rowLabel(undo == null ? "No setting changes to undo" : "Undo " + undo + " (" + ConfigUndo.size()
                + " of " + ConfigUndo.STEPS + ")", 0, w - 96, Panel.WHITE);
        p.button("Undo", "Reverts the last setting change (also on the Undo key)", w - 84, 82,
                PillButton.Style.SECONDARY, b -> {
                    p.toast(ConfigUndo.undo());
                    p.rebuild();
                });
        p.nextRow();
        p.rowLabel("Export " + active.profileId() + " to a bundle file", 0, w - 96, Panel.WHITE);
        p.button("Export", "Writes utilitysuite/bundles/<profile>-<time>.json in the game folder", w - 84, 82,
                PillButton.Style.SECONDARY, b -> {
                    p.toast(export(active));
                    p.rebuild();
                });
        p.nextRow();
        if (pending != null) {
            preview(p, w);
            return;
        }
        List<Path> files = list();
        if (files.isEmpty()) {
            p.paragraph("No bundles yet in utilitysuite/bundles.", Panel.DIM);
            return;
        }
        p.subheading("Import a bundle (preview first)");
        for (Path f : files) {
            p.listRow(null);
            p.rowLabel(f.getFileName().toString(), 4, w - 96, Panel.WHITE);
            p.button("Preview", "Shows what importing this bundle would change", w - 84, 82,
                    PillButton.Style.SECONDARY, b -> {
                        try {
                            pending = ProfileBundle.read(f);
                            pendingName = f.getFileName().toString();
                            pendingChanges = pending.previewOver(Services.modState().profile().root(), currentKeys(),
                                    ProfileCopier.EXCLUDED);
                        } catch (IOException e) {
                            p.toast(e.getMessage());
                        }
                        p.rebuild();
                    });
            p.nextRow();
        }
    }

    private static void preview(Panel p, int w) {
        p.subheading("Preview: " + pendingName + " (" + pendingChanges.size() + " changes)");
        int n = Math.min(MAX_DIFF_ROWS, pendingChanges.size());
        int half = (w - 8) / 2;
        for (int i = 0; i < n; i++) {
            ConfigDiff.Change c = pendingChanges.get(i);
            p.rowLabel(c.key(), 4, half - 4, Panel.WHITE);
            p.rowLabel((c.oldValue() == null ? "(unset)" : c.oldValue()) + " -> " + c.newValue(), half + 4,
                    w - half - 8, Panel.GRAY);
            p.nextRow();
        }
        if (pendingChanges.size() > n) {
            p.text("+ " + (pendingChanges.size() - n) + " more", Panel.GRAY);
        }
        if (pendingChanges.isEmpty()) {
            p.paragraph("The bundle matches the current settings.", Panel.DIM);
        }
        p.button("Apply", "Imports the bundle over the active profile (Undo does not cover imports)", 0, 90,
                PillButton.Style.PRIMARY, b -> {
                    p.toast(apply());
                    p.rebuild();
                });
        p.button("Cancel", null, 98, 90, PillButton.Style.SECONDARY, b -> {
            clearPending();
            p.toast("Import cancelled");
            p.rebuild();
        });
        p.nextRow();
    }

    private static void clearPending() {
        pending = null;
        pendingName = null;
        pendingChanges = List.of();
    }

    private static String export(ProfileConfig active) {
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmmss").format(new Date());
        Path f = dir().resolve(ProfileBundle.fileName(active.profileId() + "-" + stamp));
        try {
            new ProfileBundle(active.profileId(), active.root(), currentKeys()).write(f);
            return "Exported " + f.getFileName();
        } catch (IOException e) {
            return "Export failed: " + e.getMessage();
        }
    }

    private static String apply() {
        ProfileBundle b = pending;
        if (b == null) {
            return "Nothing to import";
        }
        ProfileConfig active = Services.modState().profile();
        int n = ProfileCopier.copy(b.settings(), active.root());
        CoreBootstrap.modules().applyProfile(active);
        Services.modState().markDirty();
        int keys = 0;
        Map<String, String> wanted = b.keybinds();
        for (KeyMapping m : KeybindManager.suiteMappings()) {
            String v = wanted.get(m.getName());
            if (v != null && !v.equals(KeybindManager.saveString(m)) && KeybindManager.applySaveString(m, v)) {
                keys++;
            }
        }
        String name = pendingName;
        clearPending();
        ConfigUndo.clear(); // recorded steps refer to values the import replaced
        return "Imported " + name + ": " + n + " modules, " + keys + " keys";
    }

    private static Map<String, String> currentKeys() {
        Map<String, String> out = new TreeMap<>();
        for (KeyMapping m : KeybindManager.suiteMappings()) {
            out.put(m.getName(), KeybindManager.saveString(m));
        }
        return out;
    }

    private static List<Path> list() {
        List<Path> out = new ArrayList<>();
        Path d = dir();
        if (!Files.isDirectory(d)) {
            return out;
        }
        try (Stream<Path> s = Files.list(d)) {
            s.filter(f -> f.getFileName().toString().endsWith(".json")).sorted().limit(12).forEach(out::add);
        } catch (IOException e) {
            // an unreadable folder lists nothing
        }
        return out;
    }
}
