package com.utilitysuite.ui.holo;

import com.mojang.logging.LogUtils;
import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.visual.TextRole;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FontDescription;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.PackLocationInfo;
import net.minecraft.server.packs.PackResources;
import net.minecraft.server.packs.PackType;
import net.minecraft.server.packs.metadata.MetadataSectionType;
import net.minecraft.server.packs.metadata.pack.PackMetadataSection;
import net.minecraft.server.packs.repository.Pack;
import net.minecraft.server.packs.repository.PackCompatibility;
import net.minecraft.server.packs.repository.PackSource;
import net.minecraft.server.packs.resources.IoSupplier;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.InclusiveRange;
import net.minecraft.util.FormattedCharSink;
import net.minecraft.util.StringDecomposer;
import net.minecraft.world.flag.FeatureFlagSet;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.AddClientReloadListenersEvent;
import net.neoforged.neoforge.event.AddPackFindersEvent;
import net.neoforged.neoforgespi.language.IModFileInfo;
import org.slf4j.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The suite font pipeline: which face each {@link TextRole} is drawn in. The TTF files are not shipped yet (the owner
 * approves the download), so everything here is built to switch itself on by presence and to stay exactly vanilla
 * otherwise.
 * <p>
 * How it works (ported to 26.3: font ids are {@code FontDescription.Resource}s, the reload check registers through
 * {@code AddClientReloadListenersEvent}; originally checked against the NeoForge 21.1.265 sources of {@code FontManager},
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
    /** Oversampling of the base definitions ({@link #FONT_IDS}): only the load probe and the fallback use them. */
    static final float OVERSAMPLE = 4.0f;
    /**
     * Text is drawn through per-scale variants ({@link #variantId}) whose oversample equals the effective pixel scale
     * (window GUI scale x menu pose scale), so one atlas texel lands on exactly one screen pixel. The atlas is sampled
     * with nearest filtering and no mipmaps, so a bitmap rasterised finer than the screen (e.g. oversample 4 drawn at
     * 2 px per GUI px) is minified by dropping every other texel row: thin horizontal strokes (the T crossbar, the F
     * top bar, the top of 'a' and '6') vanished whenever the dropped rows were the ones that carried them.
     */
    public static final int MAX_VARIANT = 6;
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
        return definitionJson(face, OVERSAMPLE);
    }

    /** The definition of a face rasterised at {@code oversample} texels per GUI px. */
    public static String definitionJson(int face, float oversample) {
        return "{\"providers\":["
                + "{\"type\":\"ttf\",\"file\":\"" + UtilitySuiteMod.MOD_ID + ":" + FILES[face] + "\","
                + "\"size\":" + SIZES[face] + ",\"oversample\":" + oversample + ","
                + "\"shift\":[0.0," + SHIFT_Y[face] + "],\"filter\":{\"uniform\":false}},"
                + "{\"type\":\"reference\",\"id\":\"minecraft:default\"}"
                + "]}";
    }

    /** Font id path of a face's variant rasterised at {@code scale} texels per GUI px (1..{@link #MAX_VARIANT}). */
    public static String variantId(int face, int scale) {
        return FONT_IDS[face] + "_x" + scale;
    }

    /**
     * The variant (oversample) for an effective pixel scale: the nearest integer, clamped to 1..{@link #MAX_VARIANT}.
     * Allocation free.
     */
    public static int variantFor(double pixelScale) {
        int k = (int) Math.round(pixelScale);
        return k < 1 ? 1 : Math.min(k, MAX_VARIANT);
    }

    // ---- game glue --------------------------------------------------------------------------------------------------

    /**
     * Everything that touches the game: the pack finder, the reload check, the per-frame sample, the cached styles
     * and the reusable text run. A nested class so the pure part above loads without the game.
     */
    public static final class Client {

        private static final String PACK_ID = UtilitySuiteMod.MOD_ID + "_suite_font";
        private static final Style[] STYLES = new Style[FONT_IDS.length];
        /** Per-scale variant styles, [face][scale], created once each. */
        private static final Style[][] VARIANT_STYLES = new Style[FONT_IDS.length][MAX_VARIANT + 1];
        private static boolean probePending;
        /** Extra pose scale of the current text pass (the HUD scale); 1 outside it. */
        private static double poseScale = 1.0;

        /**
         * Sets the extra pose scale the caller draws under (the HUD scale), so the variant matches the real pixel
         * size; {@link #resetPoseScale()} when the pass ends. The menu's scale comes from
         * {@link com.utilitysuite.ui.kit.MenuScale#active()}.
         */
        public static void setPoseScale(double scale) {
            poseScale = scale > 0 ? scale : 1.0;
        }

        public static void resetPoseScale() {
            poseScale = 1.0;
        }

        /**
         * The variant (oversample, 1..{@link #MAX_VARIANT}) for the current pass: window GUI scale x menu scale x
         * pose scale. Widths differ slightly between variants (hinted advances), so width caches key on it.
         */
        public static int variant() {
            Minecraft mc = Minecraft.getInstance();
            double gui = mc.getWindow() != null ? mc.getWindow().getGuiScale() : 1.0;
            return variantFor(gui * com.utilitysuite.ui.kit.MenuScale.active() * poseScale);
        }

        private Client() {
        }

        /** Registers the pack finder and the reload check on the mod bus (start-up, once). */
        public static void init(IEventBus modBus) {
            modBus.addListener(AddPackFindersEvent.class, Client::onAddPackFinders);
            modBus.addListener(AddClientReloadListenersEvent.class,
                    e -> e.addListener(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, "suite_fonts"),
                            (ResourceManagerReloadListener) Client::onReload));
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
                public PackResources openMetadata(PackLocationInfo location) {
                    return new DefinitionPack(location);
                }

                @Override
                public Stream<PackResources> openResources(PackLocationInfo location, Pack.Metadata metadata) {
                    return Stream.of(new DefinitionPack(location));
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
                    if (!file.getFile().getContents().containsFile("assets/" + UtilitySuiteMod.MOD_ID + "/font/" + name)) {
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
                    if (manager.getResource(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID,
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
            run.set("W", Style.EMPTY.withFont(fontOf(UtilitySuiteMod.MOD_ID,
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

        /** The font description of a suite font id (26.3 styles name fonts through a {@link FontDescription}). */
        private static FontDescription fontOf(String namespace, String path) {
            return new FontDescription.Resource(Identifier.fromNamespaceAndPath(namespace, path));
        }

        /** The style for a face index, or null for {@link #VANILLA}. Created once per face. */
        public static Style styleOf(int face) {
            if (face < 0) {
                return null;
            }
            Style s = STYLES[face];
            if (s == null) {
                s = Style.EMPTY.withFont(fontOf(UtilitySuiteMod.MOD_ID,
                        FONT_IDS[face]));
                STYLES[face] = s;
            }
            return s;
        }

        /**
         * The style a role draws with right now, or null for the plain vanilla path: the face's variant whose
         * oversample matches the current effective pixel scale (GUI scale x
         * {@link com.utilitysuite.ui.kit.MenuScale#active()}). Allocation free after the first use of each variant.
         */
        public static Style style(TextRole role) {
            int face = resolve(role);
            if (face < 0) {
                return null;
            }
            int k = variant();
            Style s = VARIANT_STYLES[face][k];
            if (s == null) {
                s = Style.EMPTY.withFont(fontOf(UtilitySuiteMod.MOD_ID,
                        variantId(face, k)));
                VARIANT_STYLES[face][k] = s;
            }
            return s;
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

            /** Encoded as face * (MAX_VARIANT + 1) + scale, where scale 0 is the base definition; -1 when unknown. */
            private static int faceOf(Identifier id) {
                if (!UtilitySuiteMod.MOD_ID.equals(id.getNamespace())) {
                    return -1;
                }
                for (int f = 0; f < FONT_IDS.length; f++) {
                    for (int k = 0; k <= MAX_VARIANT; k++) {
                        if (id.getPath().equals(path(f, k))) {
                            return f * (MAX_VARIANT + 1) + k;
                        }
                    }
                }
                return -1;
            }

            private static String path(int face, int scale) {
                return "font/" + (scale == 0 ? FONT_IDS[face] : variantId(face, scale)) + ".json";
            }

            private static IoSupplier<InputStream> json(int code) {
                int face = code / (MAX_VARIANT + 1);
                int k = code % (MAX_VARIANT + 1);
                String def = k == 0 ? definitionJson(face) : definitionJson(face, k);
                byte[] bytes = def.getBytes(StandardCharsets.UTF_8);
                return () -> new ByteArrayInputStream(bytes);
            }

            @Override
            public IoSupplier<InputStream> getRootResource(String... elements) {
                return null;
            }

            @Override
            public IoSupplier<InputStream> getResource(PackType type, Identifier id) {
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
                    for (int k = 0; k <= MAX_VARIANT; k++) {
                        output.accept(Identifier.fromNamespaceAndPath(UtilitySuiteMod.MOD_ID, path(f, k)),
                                json(f * (MAX_VARIANT + 1) + k));
                    }
                }
            }

            @Override
            public Set<String> getNamespaces(PackType type) {
                return type == PackType.CLIENT_RESOURCES ? NAMESPACES : Set.of();
            }

            @Override
            @SuppressWarnings("unchecked")
            public <T> T getMetadataSection(MetadataSectionType<T> type) {
                if (type == PackMetadataSection.CLIENT_TYPE || type == PackMetadataSection.FALLBACK_TYPE) {
                    return (T) new PackMetadataSection(Component.literal("Utility Suite font definitions"),
                            new InclusiveRange<>(net.minecraft.SharedConstants.getCurrentVersion()
                                    .packVersion(PackType.CLIENT_RESOURCES)));
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
