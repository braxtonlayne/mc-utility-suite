package com.utilitysuite.ui.holo;

import com.mojang.logging.LogUtils;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.visual.TextRole;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionSerializer;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The suite font pipeline: which face each {@link TextRole} is drawn in. The TTF files are not shipped yet (the owner
 * approves the download), so everything here is built to switch itself on by presence and to stay exactly vanilla
 * otherwise.
 * <p>
 * How it works in 1.21.1 (checked against the mapped NeoForge 21.1.265 sources of {@code FontManager},
 * {@code TrueTypeGlyphProviderDefinition}, {@code FontSet}, {@code Font} and {@code AddPackFindersEvent}):
 * <ol>
 *     <li>A font definition JSON whose {@code ttf} provider names a missing file makes {@code FontManager.safeLoad}
 *     log a warning with a stack trace on every reload and the whole font id fails to resolve, so no definition
 *     JSON is shipped as a file. Instead, at {@link AddPackFindersEvent} (client resources), the mod file is
 *     checked for the three TTFs in {@code assets/utilitysuite/font/} ({@link #FILES}); only when all three are
 *     there is a small hidden, required, in-memory resource pack added ({@link Client.DefinitionPack}) that serves
 *     the three definitions {@code utilitysuite:font/ui.json}, {@code micro.json} and {@code mono.json}
 *     ({@link #definitionJson}).</li>
 *     <li>The vanilla {@code FontManager} then loads them like any other font: FreeType rasterising, vanilla glyph
 *     atlases, vanilla batching. Each definition is the TTF followed by a {@code reference} to
 *     {@code minecraft:default}, so a character the face lacks falls back to the vanilla glyph, and the TTF provider
 *     carries the filter {@code "uniform": false}, so "Force Unicode Font" turns it off inside vanilla too.</li>
 *     <li>Text is drawn with the game's own {@code Font} through a {@link Style} whose font id is the suite one
 *     ({@link Client#style}), wrapped in one reusable {@link FormattedCharSequence} ({@link Client.Run}): no access
 *     transformer, no mixin, nothing allocated per string by the suite. Widths are measured through the same font
 *     and style that draws.</li>
 *     <li>A client reload listener re-checks the TTFs through the {@code ResourceManager} after every reload, and
 *     the first frame after it probes the loaded face (a failed load resolves to vanilla's "missing" set, where
 *     every glyph has the same advance). Any failure falls back to vanilla and is logged once.</li>
 * </ol>
 * The decision itself ({@link #resolve(TextRole, boolean, boolean, boolean)}) is pure and unit tested. Client thread
 * for everything except the pack finder (mod loading) and the reload check (resource reload).
 */
public final class SuiteFonts {

    private static final Logger LOG = LogUtils.getLogger();

    /** Draw with the game's own font, unchanged. */
    public static final int VANILLA = -1;
    public static final int UI = 0;
    public static final int MICRO = 1;
    public static final int MONO = 2;

    /** Font ids (paths under {@code assets/utilitysuite/font/}, without {@code .json}), by face index. */
    public static final String[] FONT_IDS = {"ui", "micro", "mono"};
    /**
     * The TTF files the code looks for in {@code assets/utilitysuite/font/}, by face index: Rajdhani Medium (body),
     * Rajdhani SemiBold (micro captions), JetBrains Mono Regular (numerals). All three must be present.
     */
    public static final String[] FILES = {"rajdhani-medium.ttf", "rajdhani-semibold.ttf", "jetbrainsmono-regular.ttf"};
    /** TTF provider size (pixels per em at GUI scale 1), by face index. Tuned in the playtest. */
    static final float[] SIZES = {9.0f, 7.5f, 8.5f};
    /** Oversampling: glyphs rasterised at this multiple, so they stay crisp at GUI scale 2 to 4. */
    static final float OVERSAMPLE = 4.0f;
    /** Vertical shift in pixels (vanilla glyphs sit one pixel down from the line top), by face index. */
    static final float[] SHIFT_Y = {0.5f, 0.5f, 0.5f};

    private static final int[] BY_ROLE = new int[TextRole.values().length];

    static {
        BY_ROLE[TextRole.UI.ordinal()] = UI;
        BY_ROLE[TextRole.MICRO.ordinal()] = MICRO;
        BY_ROLE[TextRole.MONO.ordinal()] = MONO;
    }

    /** All three files are in the mod (pack finder) and in the resource manager (last reload). */
    private static volatile boolean filesPresent;
    /** The probe after the last reload found the faces loaded (true until a probe says otherwise). */
    private static volatile boolean loadedOk = true;
    /** The game's "Force Unicode Font" option, sampled once per frame. */
    private static boolean forceUnicode;
    /** Bumped whenever the resolved faces may have changed; text caches (digit widths) key on it. */
    private static int generation;

    private static boolean loggedAbsent;
    private static boolean loggedUnicode;
    private static boolean loggedFailed;

    private SuiteFonts() {
    }

    // ---- the decision (pure) ----------------------------------------------------------------------------------------

    /**
     * Face index for a role, or {@link #VANILLA}. The suite face is used only when its files are present and loaded,
     * the appearance setting says suite, and "Force Unicode Font" is off. Allocation free.
     */
    public static int resolve(TextRole role, boolean available, boolean suiteSetting, boolean unicodeForced) {
        if (!available || !suiteSetting || unicodeForced || role == null) {
            return VANILLA;
        }
        return BY_ROLE[role.ordinal()];
    }

    /** Face index for a role under the current state (client thread). */
    public static int resolve(TextRole role) {
        return resolve(role, filesPresent && loadedOk, HoloTheme.suiteFont, forceUnicode);
    }

    /** True when at least one role draws in a suite face right now. */
    public static boolean active() {
        return filesPresent && loadedOk && HoloTheme.suiteFont && !forceUnicode;
    }

    public static int generation() {
        return generation;
    }

    /**
     * Applies a new state (the pack finder, the reload check, the frame sample and tests). Logs once per reason
     * when the suite face would have been used but cannot be.
     */
    static void setState(boolean present, boolean loaded, boolean unicodeForced) {
        if (present != filesPresent || loaded != loadedOk || unicodeForced != forceUnicode) {
            generation++;
        }
        filesPresent = present;
        loadedOk = loaded;
        forceUnicode = unicodeForced;
        if (!HoloTheme.suiteFont) {
            return;
        }
        if (!present && !loggedAbsent) {
            loggedAbsent = true;
            LOG.info("Suite font files are not present in assets/utilitysuite/font; text uses the vanilla font");
        } else if (present && !loaded && !loggedFailed) {
            loggedFailed = true;
            LOG.warn("Suite font files are present but did not load; text uses the vanilla font");
        } else if (present && loaded && unicodeForced && !loggedUnicode) {
            loggedUnicode = true;
            LOG.info("Force Unicode Font is on; the suite font is off and text uses the vanilla font");
        }
    }

    static boolean filesPresent() {
        return filesPresent;
    }

    static boolean forceUnicode() {
        return forceUnicode;
    }

    /** Back to the start-up state (tests). */
    static void resetForTests() {
        filesPresent = false;
        loadedOk = true;
        forceUnicode = false;
        loggedAbsent = false;
        loggedUnicode = false;
        loggedFailed = false;
        generation++;
    }

    /**
     * The font definition JSON of a face: the TTF (off under "Force Unicode Font") with a fallback reference to the
     * vanilla default font. Provider fields checked against {@code TrueTypeGlyphProviderDefinition.CODEC} (file,
     * size, oversample, shift, skip) and {@code ProviderReferenceDefinition} (id); filter keys against
     * {@code FontOption} ("uniform"). Not a hot path (built when the pack opens).
     */
    public static String definitionJson(int face) {
        return "{\"providers\":["
                + "{\"type\":\"ttf\",\"file\":\"" + UtilitySuiteMod.MOD_ID + ":" + FILES[face] + "\","
                + "\"size\":" + SIZES[face] + ",\"oversample\":" + OVERSAMPLE + ","
                + "\"shift\":[0.0," + SHIFT_Y[face] + "],\"filter\":{\"uniform\":false}},"
                + "{\"type\":\"reference\",\"id\":\"minecraft:default\"}"
                + "]}";
    }

    // ---- game glue --------------------------------------------------------------------------------------------------

    /**
     * Everything that touches the game: the pack finder, the reload check, the per-frame sample, the cached styles
     * and the reusable text run. A nested class so the pure part above loads without the game.
     */
    public static final class Client {

        private static final String PACK_ID = UtilitySuiteMod.MOD_ID + "_suite_font";
        private static final Style[] STYLES = new Style[FONT_IDS.length];
        private static boolean probePending;

        private Client() {
        }

        /** Registers the pack finder and the reload check on the mod bus (start-up, once). */
        public static void init(IEventBus modBus) {
            modBus.addListener(AddPackFindersEvent.class, Client::onAddPackFinders);
            modBus.addListener(RegisterClientReloadListenersEvent.class,
                    e -> e.registerReloadListener((ResourceManagerReloadListener) Client::onReload));
        }

        private static void onAddPackFinders(AddPackFindersEvent event) {
            if (event.getPackType() != PackType.CLIENT_RESOURCES) {
                return;
            }
            boolean present = filesInModFile();
            setState(present, true, forceUnicode);
            if (!present) {
                return; // no definitions at all: the font manager never hears of the suite faces
            }
            PackLocationInfo info = new PackLocationInfo(PACK_ID, Component.literal("Utility Suite font"),
                    PackSource.BUILT_IN, Optional.empty());
            Pack.Metadata meta = new Pack.Metadata(Component.literal("Utility Suite font definitions"),
                    PackCompatibility.COMPATIBLE, FeatureFlagSet.of(), List.of(), true);
            Pack.ResourcesSupplier supplier = new Pack.ResourcesSupplier() {
                @Override
                public PackResources openPrimary(PackLocationInfo location) {
                    return new DefinitionPack(location);
                }

                @Override
                public PackResources openFull(PackLocationInfo location, Pack.Metadata metadata) {
                    return new DefinitionPack(location);
                }
            };
            Pack pack = new Pack(info, supplier, meta,
                    new net.minecraft.server.packs.PackSelectionConfig(true, Pack.Position.TOP, false));
            event.addRepositorySource(consumer -> consumer.accept(pack));
        }

        /** True when all three TTFs are inside the mod's own file (jar, or the dev resource folders). */
        private static boolean filesInModFile() {
            try {
                IModFileInfo file = ModList.get().getModFileById(UtilitySuiteMod.MOD_ID);
                if (file == null) {
                    return false;
                }
                for (String name : FILES) {
                    Path p = file.getFile().findResource("assets", UtilitySuiteMod.MOD_ID, "font", name);
                    if (p == null || !Files.isRegularFile(p)) {
                        return false;
                    }
                }
                return true;
            } catch (RuntimeException e) {
                return false;
            }
        }

        private static void onReload(ResourceManager manager) {
            boolean present = filesPresent;
            if (present) {
                for (String name : FILES) {
                    if (manager.getResource(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
                            "font/" + name)).isEmpty()) {
                        present = false;
                        break;
                    }
                }
            }
            setState(present, true, forceUnicode);
            probePending = present;
        }

        /**
         * Once per frame before any text (HoloKit.begin): samples "Force Unicode Font" and, on the first frame after
         * a reload, checks that the faces really loaded. Allocation free after that first frame.
         */
        public static void frame() {
            Minecraft mc = Minecraft.getInstance();
            boolean unicode = mc.options != null && Boolean.TRUE.equals(mc.options.forceUnicodeFont().get());
            if (unicode != forceUnicode) {
                setState(filesPresent, loadedOk, unicode);
            }
            if (probePending && mc.font != null) {
                probePending = false;
                setState(filesPresent, probe(mc), forceUnicode);
            }
        }

        /**
         * A face that failed to load resolves to the vanilla "missing" font set, where every glyph has the same
         * advance. A proportional face gives 'i' and 'W' different widths. The monospaced face ({@link #MONO}) gives
         * every glyph the same width by design, so it must instead differ from the missing set's advance, measured
         * through a font id that does not exist; should the two ever coincide, the probe fails closed (vanilla font).
         */
        private static boolean probe(Minecraft mc) {
            Run run = new Run(); // once per reload
            run.set("W", Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
                    "probe_missing")));
            int missing = mc.font.width(run);
            for (int f = 0; f < FONT_IDS.length; f++) {
                Style s = styleOf(f);
                run.set("i", s);
                int narrow = mc.font.width(run);
                run.set("W", s);
                int wide = mc.font.width(run);
                boolean loaded = f == MONO ? wide > 0 && wide != missing : narrow > 0 && narrow != wide;
                if (!loaded) {
                    LOG.warn("Suite font probe: face {} widths i={} W={} (missing set {})", FONT_IDS[f], narrow, wide,
                            missing);
                    return false;
                }
            }
            LOG.info("Suite fonts loaded (probe widths measured against the missing set {})", missing);
            return true;
        }

        /** The style for a face index, or null for {@link #VANILLA}. Created once per face. */
        public static Style styleOf(int face) {
            if (face < 0) {
                return null;
            }
            Style s = STYLES[face];
            if (s == null) {
                s = Style.EMPTY.withFont(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
                        FONT_IDS[face]));
                STYLES[face] = s;
            }
            return s;
        }

        /** The style a role draws with right now, or null for the plain vanilla path. */
        public static Style style(TextRole role) {
            return styleOf(resolve(role));
        }

        /**
         * One reusable styled text run: a {@link FormattedCharSequence} over a String with a fixed style, decoded the
         * same way the game decodes a plain String ({@code StringDecomposer.iterateFormatted}). Set, draw, reuse.
         */
        public static final class Run implements FormattedCharSequence {
            private String text = "";
            private Style style = Style.EMPTY;

            public Run set(String text, Style style) {
                this.text = text;
                this.style = style;
                return this;
            }

            @Override
            public boolean accept(FormattedCharSink sink) {
                return StringDecomposer.iterateFormatted(text, style, sink);
            }
        }

        /** The in-memory pack that serves the three definitions. Opened by the resource reload, not per frame. */
        static final class DefinitionPack implements PackResources {
            private static final Set<String> NAMESPACES = Set.of(UtilitySuiteMod.MOD_ID);
            private final PackLocationInfo location;

            DefinitionPack(PackLocationInfo location) {
                this.location = location;
            }

            private static int faceOf(ResourceLocation id) {
                if (!UtilitySuiteMod.MOD_ID.equals(id.getNamespace())) {
                    return -1;
                }
                for (int f = 0; f < FONT_IDS.length; f++) {
                    if (id.getPath().equals("font/" + FONT_IDS[f] + ".json")) {
                        return f;
                    }
                }
                return -1;
            }

            private static IoSupplier<InputStream> json(int face) {
                byte[] bytes = definitionJson(face).getBytes(StandardCharsets.UTF_8);
                return () -> new ByteArrayInputStream(bytes);
            }

            @Override
            public IoSupplier<InputStream> getRootResource(String... elements) {
                return null;
            }

            @Override
            public IoSupplier<InputStream> getResource(PackType type, ResourceLocation id) {
                if (type != PackType.CLIENT_RESOURCES) {
                    return null;
                }
                int face = faceOf(id);
                return face < 0 ? null : json(face);
            }

            @Override
            public void listResources(PackType type, String namespace, String path, ResourceOutput output) {
                if (type != PackType.CLIENT_RESOURCES || !UtilitySuiteMod.MOD_ID.equals(namespace)) {
                    return;
                }
                if (!"font".equals(path) && !"font/".equals(path) && !path.isEmpty()) {
                    return;
                }
                for (int f = 0; f < FONT_IDS.length; f++) {
                    output.accept(ResourceLocation.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
                            "font/" + FONT_IDS[f] + ".json"), json(f));
                }
            }

            @Override
            public Set<String> getNamespaces(PackType type) {
                return type == PackType.CLIENT_RESOURCES ? NAMESPACES : Set.of();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getMetadataSection(MetadataSectionSerializer<T> serializer) {
                if (serializer == PackMetadataSection.TYPE) {
                    return (T) new PackMetadataSection(Component.literal("Utility Suite font definitions"),
                            net.minecraft.SharedConstants.getCurrentVersion().getPackVersion(PackType.CLIENT_RESOURCES));
                }
                return null;
            }

            @Override
            public PackLocationInfo location() {
                return location;
            }

            @Override
            public void close() {
            }
        }
    }
}
