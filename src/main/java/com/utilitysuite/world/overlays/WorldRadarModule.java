package com.utilitysuite.world.overlays;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.tools.packetlab.AcousticRadar;
import com.utilitysuite.tools.packetlab.PacketLab;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.WorldRuntime;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

import java.util.List;

/**
 * World Radar: one renderer with filter groups.
 * <ul>
 *   <li><b>Players</b>: boxes and name tags with health, armour and distance.</li>
 *   <li><b>Storage</b>: containers seen in loaded chunks, colour-coded by type.</li>
 *   <li><b>Spawners</b>: spawners, trial spawners and vaults.</li>
 *   <li><b>Rare Drops</b>: ground items filtered by the highlight / junk lists.</li>
 *   <li><b>Trails</b>: chunks classified as newly generated (exploration-trail heuristic) and the aim arc of the held
 *       bow, crossbow, ender pearl, snowball/egg, trident or wind charge.</li>
 * </ul>
 * Entities are gathered on the tick into reused arrays; frames only iterate them. Nothing is drawn in Clean-Screen.
 */
public final class WorldRadarModule extends WorldModule {

    public static final String ID = "world_radar";
    private static final int MAX_ITEMS = 256;
    private static final int GATHER_INTERVAL = 4;
    private static final String HEART = " ❤";
    private static final String SHIELD = " ⛨";
    private static final String SPACE = " ";
    private static final String METERS = "m";
    private static final String TIMES = " x";

    private final WorldRuntime runtime;
    private final WorldScanner scanner;
    private final ItemFilter itemFilter = new ItemFilter();
    private final TrajectoryIntegrator integrator = new TrajectoryIntegrator(200);
    private final HeldProjectile held = new HeldProjectile();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final TrajectoryIntegrator.Collider collider = this::collide;
    private final Int2IntOpenHashMap storageColor = new Int2IntOpenHashMap();
    private final Reference2ObjectOpenHashMap<Item, ItemFilter.Verdict> itemVerdicts = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Item, String> itemNames = new Reference2ObjectOpenHashMap<>();

    private boolean players;
    private boolean storage;
    private boolean spawners;
    private boolean rareDrops;
    private boolean trails;
    private boolean aimArc;
    private int radius;
    private int trailRadius;
    private boolean mobs;
    private boolean tracers;
    private int maxMobs;
    private boolean acousticPings;
    private long acousticMaxAgeMs = 8000L;
    private RenderContext acousticCtx;
    private long acousticNowMs;
    private double acousticMaxDistSq;
    private final AcousticRadar.Visitor acousticLineVisitor = this::drawAcousticLine;
    private final AcousticRadar.Visitor acousticLabelVisitor = this::drawAcousticLabel;
    private boolean acousticRipples;
    private boolean acousticEdgeCues;
    private final AcousticRipples ripples = new AcousticRipples(96);
    private final AcousticRipples.RingVisitor ringVisitor = this::drawRing;
    private boolean trailClasses;
    private final TrailForensics trailForensics = new TrailForensics();
    /** Trails fade to a floor over this long (0 = no fading). */
    private long trailFadeMs;
    /** World profile whose trail files the scanner uses (null until a world profile is loaded). */
    private String trailProfile;
    private static final int TRAIL_SAVE_TICKS = 600;
    private static final int TRAIL_CELL = 0xA0FF4040;
    private static final String[] ACOUSTIC_LABELS = {
            "Container", "Portal", "Explosion", "Redstone", "Player", "Sound", "Footsteps", "Eating", "Sculk"
    };

    private static final int MAX_MOBS = 128;
    private final LivingEntity[] mobList = new LivingEntity[MAX_MOBS];
    private int mobCount;
    private final NametagCache tags = new NametagCache(NametagCache.DEFAULT_FORMAT,
            s -> Minecraft.getInstance().font.width(s));

    private final ItemEntity[] items = new ItemEntity[MAX_ITEMS];
    private final boolean[] itemHighlight = new boolean[MAX_ITEMS];
    private int itemCount;
    private int ticks;
    private ClientLevel colliderLevel;

    public WorldRadarModule(WorldRuntime runtime) {
        super(ID, "World Radar", false);
        this.runtime = runtime;
        this.scanner = runtime.scanner();
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("players", "Players", true, "Groups", "Boxes and tags with health, armour, distance."),
                SettingSpec.bool("storage", "Storage", true, "Groups", "Containers, colour-coded by type."),
                SettingSpec.bool("spawners", "Spawners", true, "Groups", "Spawners, trial spawners and vaults."),
                SettingSpec.bool("rare_drops", "Rare drops", true, "Groups", "Ground items on the highlight list."),
                SettingSpec.bool("trails", "New-chunk trails", false, "Groups",
                        "Outlines chunks that look freshly generated (liquids still settling when they arrived)."),
                SettingSpec.bool("aim_arc", "Aim arc", true, "Groups",
                        "Predicted path of the bow, crossbow, pearl, snowball, egg, trident or wind charge you hold."),
                SettingSpec.bool("mobs", "Hostile mobs", false, "Groups",
                        "Boxes and tags (health, armour, distance) on hostile mobs."),
                SettingSpec.bool("tracers", "Tracers", false, "Display",
                        "Lines from the crosshair to tracked players and hostile mobs."),
                SettingSpec.integer("max_mobs", "Max mobs", 48, 4, 128, "Advanced",
                        "Cap on hostile mobs tracked (nearest found first in the entity list).").asAdvanced(),
                SettingSpec.integer("radius", "Radius", 64, 8, 256, "Display", "Players, items and blocks beyond this "
                        + "distance are not shown."),
                SettingSpec.integer("trail_radius", "Trail radius (chunks)", 12, 2, 32, "Display",
                        "Reach of the new-chunk outlines."),
                SettingSpec.text("item_highlight", "Highlighted items", ItemFilter.DEFAULT_HIGHLIGHT, "Rare drops",
                        "Comma list of item ids."),
                SettingSpec.text("item_junk", "Never shown", ItemFilter.DEFAULT_JUNK, "Rare drops",
                        "Comma list of item ids that are never highlighted."),
                SettingSpec.bool("show_other_items", "Show other items", false, "Rare drops",
                        "Also outline items on neither list (dimmer)."),
                SettingSpec.integer("trail_window", "Trail window (ticks)", 60, 10, 200, "Advanced",
                        "How long after a chunk arrives fluid updates count as 'new'.").asAdvanced(),
                SettingSpec.bool("acoustic_pings", "Acoustic pings", false, "Groups",
                        "3D markers for recent sound and global packet activity."),
                SettingSpec.integer("acoustic_max_age_sec", "Acoustic ping age (s)", 8, 1, 30, "Display",
                        "How long acoustic ping markers remain visible."),
                SettingSpec.bool("acoustic_ripples", "Echo ripples", false, "Display",
                        "Acoustic pings also draw three expanding rings at the sound, coloured by source."),
                SettingSpec.bool("acoustic_edge_cues", "Off-screen sound cues", false, "Display",
                        "Arrows at the screen edge point to acoustic pings outside your view."),
                SettingSpec.bool("trail_classes", "Trail forensics", false, "Display",
                        "Classifies new-chunk trails (elytra highway, boat run, Nether tunnel, search zig-zag) by "
                                + "shape and measured speed, draws direction arrows along each and, near or looked "
                                + "at, how long ago it was made."),
                SettingSpec.integer("trail_fade_minutes", "Trail fade (minutes)", 0, 0, 10080, "Display",
                        "Trails fade with age over this many minutes (to a faint floor). 0 = no fading."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        players = bool(p, "players");
        storage = bool(p, "storage");
        spawners = bool(p, "spawners");
        rareDrops = bool(p, "rare_drops");
        trails = bool(p, "trails");
        aimArc = bool(p, "aim_arc");
        mobs = bool(p, "mobs");
        tracers = bool(p, "tracers");
        maxMobs = integer(p, "max_mobs");
        radius = integer(p, "radius");
        trailRadius = integer(p, "trail_radius");
        acousticPings = bool(p, "acoustic_pings");
        acousticMaxAgeMs = integer(p, "acoustic_max_age_sec") * 1000L;
        acousticRipples = bool(p, "acoustic_ripples");
        acousticEdgeCues = bool(p, "acoustic_edge_cues");
        updateAcousticDemand();
        trailClasses = bool(p, "trail_classes");
        if (!trailClasses) {
            trailForensics.clear();
        }
        trailFadeMs = integer(p, "trail_fade_minutes") * 60_000L;
        String profile = p.profileId();
        if (!"global".equals(profile) && !profile.equals(trailProfile)) {
            trailProfile = profile; // trails persist per world and dimension (merged in on load)
            scanner.setTrailFiles(dim -> com.utilitysuite.world.WorldFiles.file(profile,
                    ".trails." + com.utilitysuite.world.WorldFiles.safe(dim) + ".bin"));
        }
        itemFilter.set(text(p, "item_highlight"), text(p, "item_junk"), bool(p, "show_other_items"));
        itemVerdicts.clear();
        scanner.chunkAge.configure(integer(p, "trail_window"), 2);
        runtime.setTrackChunkAge(isEnabled() && trails);
    }

    @Override
    protected void onEnable() {
        runtime.setTrackChunkAge(trails);
        updateAcousticDemand();
    }

    @Override
    protected void onDisable() {
        runtime.setTrackChunkAge(false);
        clearItems();
        updateAcousticDemand();
    }

    /** Acoustic pings, ripples and edge cues get their sound feed without Packet Lab having to be on as well. */
    private void updateAcousticDemand() {
        PacketLab.setAcousticDemand(isEnabled() && (acousticPings || acousticRipples || acousticEdgeCues));
    }

    @Override
    public void onWorldUnload() {
        clearItems();
        ripples.clear();
        trailForensics.clear();
        colliderLevel = null;
    }

    private void clearItems() {
        java.util.Arrays.fill(items, null);
        itemCount = 0;
        java.util.Arrays.fill(mobList, null);
        mobCount = 0;
        tags.clear();
    }

    @Override
    public int scanMask() {
        return (storage ? BlockCategories.STORAGE : 0) | (spawners ? BlockCategories.SPAWNER : 0);
    }

    // ---- tick: gather entities ----------------------------------------------------------------------------------

    @Override
    public void onTick() {
        if (++ticks % TRAIL_SAVE_TICKS == 0) {
            scanner.persistTrails(true); // debounced: only when the trails changed; written on the world executor
        }
        if (ticks % GATHER_INTERVAL != 0) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        int n = 0;
        if (rareDrops && self != null && level != null) {
            double r2 = WorldRuntime.sq(radius);
            for (Entity e : level.entitiesForRendering()) {
                if (n >= MAX_ITEMS) {
                    break;
                }
                if (e instanceof ItemEntity ie && ie.distanceToSqr(self) <= r2) {
                    ItemFilter.Verdict v = verdict(ie.getItem().getItem());
                    if (v != ItemFilter.Verdict.HIDE) {
                        itemHighlight[n] = v == ItemFilter.Verdict.HIGHLIGHT;
                        items[n++] = ie;
                    }
                }
            }
        }
        for (int i = n; i < itemCount; i++) {
            items[i] = null;
        }
        itemCount = n;
        int m = 0;
        if (mobs && self != null && level != null) {
            double r2 = WorldRuntime.sq(radius);
            int cap = Math.min(maxMobs, MAX_MOBS);
            for (Entity e : level.entitiesForRendering()) {
                if (m >= cap) {
                    break;
                }
                if (e instanceof LivingEntity le && e instanceof Enemy && le.isAlive() && le.distanceToSqr(self) <= r2) {
                    mobList[m++] = le;
                }
            }
        }
        for (int i = m; i < mobCount; i++) {
            mobList[i] = null;
        }
        mobCount = m;
        if (ticks % 200 == 0) {
            tags.sweep();
        }
        if (trails && trailClasses && self != null && level != null && ticks % 40 == 0) {
            trailForensics.gather(scanner.chunkAge, level, self.getBlockX() >> 4, self.getBlockZ() >> 4, trailRadius,
                    level.dimension() == net.minecraft.world.level.Level.NETHER);
        }
    }

    /** For the edge-cue HUD layer. */
    boolean wantsEdgeCues() {
        return isEnabled() && acousticPings && acousticEdgeCues;
    }

    private ItemFilter.Verdict verdict(Item item) {
        ItemFilter.Verdict v = itemVerdicts.get(item);
        if (v == null) {
            v = itemFilter.classify(WorldIds.item(item));
            itemVerdicts.put(item, v);
        }
        return v;
    }

    private String itemName(Item item) {
        String s = itemNames.get(item);
        if (s == null) {
            s = net.minecraft.network.chat.Component.translatable(item.getDescriptionId()).getString(); // cached per item
            itemNames.put(item, s);
        }
        return s;
    }

    // ---- rendering ----------------------------------------------------------------------------------------------

    @Override
    public void renderLines(RenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null) {
            return;
        }
        double r2 = WorldRuntime.sq(radius);
        float pt = ctx.partialTick;
        if (players) {
            List<AbstractClientPlayer> list = level.players();
            for (int i = 0; i < list.size(); i++) {
                AbstractClientPlayer p = list.get(i);
                if (p == self || p.isSpectator()) {
                    continue;
                }
                double x = Mth.lerp(pt, p.xOld, p.getX());
                double y = Mth.lerp(pt, p.yOld, p.getY());
                double z = Mth.lerp(pt, p.zOld, p.getZ());
                double dx = x - ctx.camX;
                double dy = y - ctx.camY;
                double dz = z - ctx.camZ;
                if (dx * dx + dy * dy + dz * dz > r2) {
                    continue;
                }
                float hw = p.getBbWidth() / 2f;
                float rx = ctx.rx(x);
                float ry = ctx.ry(y);
                float rz = ctx.rz(z);
                WorldRenderKit.box(ctx.lines, ctx.pose, rx - hw, ry, rz - hw, rx + hw, ry + p.getBbHeight(), rz + hw,
                        0xFFFFFFFF);
                if (tracers) {
                    tracer(ctx, mc, rx, ry + p.getBbHeight() * 0.5f, rz, 0xC0FFFFFF);
                }
            }
        }
        if (mobs) {
            for (int i = 0; i < mobCount; i++) {
                LivingEntity e = mobList[i];
                if (e == null || e.isRemoved()) {
                    continue;
                }
                float hw = e.getBbWidth() / 2f;
                float rx = ctx.rx(Mth.lerp(pt, e.xOld, e.getX()));
                float ry = ctx.ry(Mth.lerp(pt, e.yOld, e.getY()));
                float rz = ctx.rz(Mth.lerp(pt, e.zOld, e.getZ()));
                WorldRenderKit.box(ctx.lines, ctx.pose, rx - hw, ry, rz - hw, rx + hw, ry + e.getBbHeight(), rz + hw,
                        0xFFFF5050);
                if (tracers) {
                    tracer(ctx, mc, rx, ry + e.getBbHeight() * 0.5f, rz, 0x90FF5050);
                }
            }
        }
        if (storage || spawners) {
            HighlightIndex idx = scanner.observed;
            if (ctx.dimension.equals(scanner.dimension())) {
                for (int i = 0, n = idx.renderCount(); i < n; i++) {
                    int v = idx.renderValue(i);
                    int cat = HighlightIndex.categories(v);
                    boolean isStorage = storage && (cat & BlockCategories.STORAGE) != 0;
                    boolean isSpawner = spawners && (cat & BlockCategories.SPAWNER) != 0;
                    if (!isStorage && !isSpawner) {
                        continue;
                    }
                    long pos = idx.renderPos(i);
                    int x = BlockPosPacking.x(pos);
                    int y = BlockPosPacking.y(pos);
                    int z = BlockPosPacking.z(pos);
                    if (ctx.distSq(x, y, z) > r2) {
                        continue;
                    }
                    int color = isSpawner ? 0xFFE040E0 : storageColor(v);
                    float rx = ctx.rx(x);
                    float ry = ctx.ry(y);
                    float rz = ctx.rz(z);
                    WorldRenderKit.box(ctx.lines, ctx.pose, rx + 0.0625f, ry, rz + 0.0625f, rx + 0.9375f,
                            ry + 0.875f, rz + 0.9375f, color);
                }
            }
        }
        if (rareDrops) {
            for (int i = 0; i < itemCount; i++) {
                ItemEntity e = items[i];
                if (e == null || e.isRemoved()) {
                    continue;
                }
                float rx = ctx.rx(Mth.lerp(pt, e.xOld, e.getX()));
                float ry = ctx.ry(Mth.lerp(pt, e.yOld, e.getY()));
                float rz = ctx.rz(Mth.lerp(pt, e.zOld, e.getZ()));
                int color = itemHighlight[i] ? 0xFF40FFFF : 0x80C0C0C0;
                WorldRenderKit.box(ctx.lines, ctx.pose, rx - 0.2f, ry, rz - 0.2f, rx + 0.2f, ry + 0.4f, rz + 0.2f,
                        color);
            }
        }
        if (trails) {
            drawTrails(ctx, self);
        }
        if (aimArc) {
            drawAimArc(ctx, self, level);
        }
        if (acousticPings) {
            AcousticRadar radar = PacketLab.acousticRadar();
            if (radar != null) {
                acousticCtx = ctx;
                acousticNowMs = System.currentTimeMillis();
                acousticMaxDistSq = r2;
                radar.forEachActive(acousticNowMs, acousticMaxAgeMs, acousticLineVisitor);
                if (acousticRipples) {
                    ripples.ingest(radar);
                    ripples.forEachRing(acousticNowMs, ringVisitor);
                }
                acousticCtx = null;
            }
        }
    }

    /** One horizontal ripple ring (32 segments) around a sound origin. */
    private void drawRing(double x, double y, double z, float radius, int argb) {
        RenderContext ctx = acousticCtx;
        if (ctx.distSq((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) > acousticMaxDistSq) {
            return;
        }
        float cx = ctx.rx(x);
        float cy = ctx.ry(y);
        float cz = ctx.rz(z);
        for (int i = 0; i < AcousticRipples.SEGMENTS; i++) {
            WorldRenderKit.line(ctx.lines, ctx.pose, cx + radius * AcousticRipples.cos(i), cy,
                    cz + radius * AcousticRipples.sin(i), cx + radius * AcousticRipples.cos(i + 1), cy,
                    cz + radius * AcousticRipples.sin(i + 1), argb);
        }
    }

    /** Line from just in front of the crosshair (camera look vector, camera-relative) to a target. */
    private static void tracer(RenderContext ctx, Minecraft mc, float tx, float ty, float tz, int argb) {
        org.joml.Vector3fc look = mc.gameRenderer.mainCamera().forwardVector();
        WorldRenderKit.line(ctx.lines, ctx.pose, look.x(), look.y(), look.z(), tx, ty, tz, argb);
    }

    private int storageColor(int value) {
        int idx = HighlightIndex.idIndexOf(value);
        int c = storageColor.get(idx);
        if (c == 0) {
            String id = scanner.observed.idAt(idx);
            String path = id == null ? "" : id.substring(id.indexOf(':') + 1);
            c = storageColorFor(path);
            storageColor.put(idx, c);
        }
        return c;
    }

    /** Colour per container type. */
    static int storageColorFor(String path) {
        if (path.equals("trapped_chest")) {
            return 0xFFFF5040;
        }
        if (path.equals("chest")) {
            return 0xFFE0A040;
        }
        if (path.equals("ender_chest")) {
            return 0xFF9050E0;
        }
        if (path.endsWith("shulker_box")) {
            return 0xFFE070C0;
        }
        if (path.equals("barrel")) {
            return 0xFFB08050;
        }
        if (path.equals("hopper")) {
            return 0xFF808890;
        }
        if (path.equals("dispenser") || path.equals("dropper") || path.equals("crafter")) {
            return 0xFF90A0B0;
        }
        return 0xFFC0A080;
    }

    private void drawTrails(RenderContext ctx, LocalPlayer self) {
        int pcx = self.getBlockX() >> 4;
        int pcz = self.getBlockZ() >> 4;
        float y = ctx.ry(self.getBlockY() - 1 + 0.02);
        int r = trailRadius;
        long now = System.currentTimeMillis();
        long fade = trailFadeMs;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (scanner.chunkAge.stateOf(cx, cz) != ChunkAgeTracker.NEW) {
                    continue;
                }
                float x0 = ctx.rx(cx << 4);
                float z0 = ctx.rz(cz << 4);
                int argb = fade <= 0 ? TRAIL_CELL
                        : TrailRecency.withAlpha(TRAIL_CELL, TrailRecency.fade(scanner.chunkAge.seenWallOf(cx, cz),
                        now, fade));
                WorldRenderKit.square(ctx.lines, ctx.pose, x0 + 0.5f, y, z0 + 0.5f, x0 + 15.5f, z0 + 15.5f, argb);
            }
        }
        if (trailClasses) {
            trailForensics.render(ctx, y + 0.5f, now, fade);
        }
    }

    private int collide(int x, int y, int z) {
        ClientLevel level = colliderLevel;
        BlockState s = level.getBlockState(probe.set(x, y, z));
        if (s.isAir()) {
            return TrajectoryIntegrator.Collider.FREE;
        }
        if (s.getFluidState().is(FluidTags.WATER) && s.getCollisionShape(level, probe).isEmpty()) {
            return TrajectoryIntegrator.Collider.WATER;
        }
        return s.getCollisionShape(level, probe).isEmpty() ? TrajectoryIntegrator.Collider.FREE
                : TrajectoryIntegrator.Collider.SOLID;
    }

    private void drawAimArc(RenderContext ctx, LocalPlayer self, ClientLevel level) {
        if (!held.read(self)) {
            return;
        }
        TrajectoryIntegrator.Kind kind = held.kind;
        double power = held.power;
        float pt = ctx.partialTick;
        float yaw = self.getViewYRot(pt);
        float pitch = self.getViewXRot(pt);
        double x = Mth.lerp(pt, self.xOld, self.getX());
        double y = Mth.lerp(pt, self.yOld, self.getY()) + self.getEyeHeight() - 0.1;
        double z = Mth.lerp(pt, self.zOld, self.getZ());
        Vec3 motion = self.getDeltaMovement();
        colliderLevel = level;
        integrator.simulate(kind, x, y, z, yaw, pitch, power, motion.x, self.onGround() ? 0.0 : motion.y, motion.z,
                collider);
        int n = integrator.count();
        for (int i = 1; i < n; i++) {
            WorldRenderKit.line(ctx.lines, ctx.pose, ctx.rx(integrator.x(i - 1)), ctx.ry(integrator.y(i - 1)),
                    ctx.rz(integrator.z(i - 1)), ctx.rx(integrator.x(i)), ctx.ry(integrator.y(i)),
                    ctx.rz(integrator.z(i)), 0xC0FFFF60);
        }
        if (integrator.hit()) {
            float hx = ctx.rx(integrator.hitX());
            float hy = ctx.ry(integrator.hitY());
            float hz = ctx.rz(integrator.hitZ());
            WorldRenderKit.box(ctx.lines, ctx.pose, hx - 0.15f, hy - 0.15f, hz - 0.15f, hx + 0.15f, hy + 0.15f,
                    hz + 0.15f, 0xFFFF4040);
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer self = mc.player;
        ClientLevel level = mc.level;
        if (self == null || level == null) {
            return;
        }
        tags.beginFrame();
        double r2 = WorldRuntime.sq(radius);
        float pt = ctx.partialTick;
        if (players) {
            List<AbstractClientPlayer> list = level.players();
            for (int i = 0; i < list.size(); i++) {
                AbstractClientPlayer p = list.get(i);
                if (p == self || p.isSpectator()) {
                    continue;
                }
                double x = Mth.lerp(pt, p.xOld, p.getX());
                double y = Mth.lerp(pt, p.yOld, p.getY()) + p.getBbHeight() + 0.6;
                double z = Mth.lerp(pt, p.zOld, p.getZ());
                double dx = x - ctx.camX;
                double dy = y - ctx.camY;
                double dz = z - ctx.camZ;
                double d2 = dx * dx + dy * dy + dz * dz;
                if (d2 > r2) {
                    continue;
                }
                Matrix4f m = ctx.kit.beginLabel(ctx.pose, (float) dx, (float) dy, (float) dz);
                String name = p.getGameProfile().name();
                int health = Mth.ceil(p.getHealth() + p.getAbsorptionAmount());
                int armour = p.getArmorValue();
                int dist = (int) Math.sqrt(d2);
                NametagCache.Entry tag = tags.get(p.getId(), health, armour, dist);
                float w = ctx.font.width(name) + ctx.font.width(SPACE) + tag.width;
                float tx = -w / 2f;
                tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, name, tx, 0xFFFFFFFF);
                tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, SPACE, tx, 0xFFFFFFFF);
                WorldRenderKit.text(ctx.font, ctx.buffers, m, tag.text, tx, 0xFFFFB0B0);
            }
        }
        if (mobs) {
            for (int i = 0; i < mobCount; i++) {
                LivingEntity e = mobList[i];
                if (e == null || e.isRemoved()) {
                    continue;
                }
                double x = Mth.lerp(pt, e.xOld, e.getX());
                double y = Mth.lerp(pt, e.yOld, e.getY()) + e.getBbHeight() + 0.4;
                double z = Mth.lerp(pt, e.zOld, e.getZ());
                double dx = x - ctx.camX;
                double dy = y - ctx.camY;
                double dz = z - ctx.camZ;
                int dist = (int) Math.sqrt(dx * dx + dy * dy + dz * dz);
                NametagCache.Entry tag = tags.get(e.getId(), Mth.ceil(e.getHealth() + e.getAbsorptionAmount()),
                        e.getArmorValue(), dist);
                Matrix4f m = ctx.kit.beginLabel(ctx.pose, (float) dx, (float) dy, (float) dz);
                WorldRenderKit.text(ctx.font, ctx.buffers, m, tag.text, -tag.width / 2f, 0xFFFFB0B0);
            }
        }
        if (rareDrops) {
            for (int i = 0; i < itemCount; i++) {
                ItemEntity e = items[i];
                if (e == null || e.isRemoved() || !itemHighlight[i]) {
                    continue;
                }
                ItemStack stack = e.getItem();
                float rx = ctx.rx(Mth.lerp(pt, e.xOld, e.getX()));
                float ry = ctx.ry(Mth.lerp(pt, e.yOld, e.getY()) + 0.7);
                float rz = ctx.rz(Mth.lerp(pt, e.zOld, e.getZ()));
                Matrix4f m = ctx.kit.beginLabel(ctx.pose, rx, ry, rz);
                String name = itemName(stack.getItem());
                float w = ctx.font.width(name);
                float tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, name, -w / 2f, 0xFF40FFFF);
                if (stack.getCount() > 1) {
                    tx = WorldRenderKit.text(ctx.font, ctx.buffers, m, TIMES, tx, 0xFFC0C0C0);
                    WorldRenderKit.text(ctx.font, ctx.buffers, m, WorldRenderKit.number(stack.getCount()), tx,
                            0xFFC0C0C0);
                }
            }
        }
        if (trails && trailClasses) {
            trailForensics.renderLabels(ctx, ctx.ry(self.getBlockY() - 1 + 0.52), System.currentTimeMillis(),
                    Minecraft.getInstance().gameRenderer.mainCamera().forwardVector());
        }
        if (acousticPings) {
            AcousticRadar radar = PacketLab.acousticRadar();
            if (radar != null) {
                acousticCtx = ctx;
                acousticNowMs = System.currentTimeMillis();
                acousticMaxDistSq = r2;
                radar.forEachActive(acousticNowMs, acousticMaxAgeMs, acousticLabelVisitor);
                acousticCtx = null;
            }
        }
    }

    private void drawAcousticLine(boolean global, double x, double y, double z, double dx, double dz,
                                  long timestampMs, int categoryId) {
        long age = acousticNowMs - timestampMs;
        float fade = acousticMaxAgeMs > 0 ? Math.max(0f, Math.min(1f, 1.0f - (float) age / (float) acousticMaxAgeMs))
                : 1.0f;
        int alpha = Math.max(0x28, Math.min(0xFF, (int) (fade * 255f)));
        int argb = (alpha << 24) | (AcousticRadar.categoryColor(categoryId) & 0x00FFFFFF);
        if (global) {
            float dist = Math.min(radius * 0.5f, 16.0f);
            float tx = (float) (dx * dist);
            float tz = (float) (dz * dist);
            WorldRenderKit.line(acousticCtx.lines, acousticCtx.pose, (float) (dx * 2.0), 0f, (float) (dz * 2.0),
                    tx, 0f, tz, argb);
            WorldRenderKit.box(acousticCtx.lines, acousticCtx.pose, tx - 0.25f, -0.25f, tz - 0.25f,
                    tx + 0.25f, 0.25f, tz + 0.25f, argb);
            return;
        }
        if (acousticCtx.distSq((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)) > acousticMaxDistSq) {
            return;
        }
        float rx = acousticCtx.rx(x);
        float ry = acousticCtx.ry(y);
        float rz = acousticCtx.rz(z);
        float half = 0.25f + 0.15f * (1.0f - fade);
        WorldRenderKit.box(acousticCtx.lines, acousticCtx.pose, rx - half, ry - half, rz - half,
                rx + half, ry + half, rz + half, argb);
    }

    private void drawAcousticLabel(boolean global, double x, double y, double z, double dx, double dz,
                                   long timestampMs, int categoryId) {
        long age = acousticNowMs - timestampMs;
        float fade = acousticMaxAgeMs > 0 ? Math.max(0f, Math.min(1f, 1.0f - (float) age / (float) acousticMaxAgeMs))
                : 1.0f;
        int alpha = Math.max(0x40, Math.min(0xFF, (int) (fade * 255f)));
        int argb = (alpha << 24) | (AcousticRadar.categoryColor(categoryId) & 0x00FFFFFF);
        int catIdx = (categoryId >= 0 && categoryId < ACOUSTIC_LABELS.length) ? categoryId : AcousticRadar.OTHER;
        String label = ACOUSTIC_LABELS[catIdx];
        if (global) {
            float dist = Math.min(radius * 0.5f, 16.0f);
            float rx = (float) (dx * dist);
            float tz = (float) (dz * dist);
            Matrix4f m = acousticCtx.kit.beginLabel(acousticCtx.pose, rx, 0.45f, tz);
            float w = acousticCtx.font.width(label);
            WorldRenderKit.text(acousticCtx.font, acousticCtx.buffers, m, label, -w / 2f, argb);
            return;
        }
        double d2 = acousticCtx.distSq((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z));
        if (d2 > acousticMaxDistSq) {
            return;
        }
        int dist = (int) Math.sqrt(d2);
        String distStr = WorldRenderKit.number(dist);
        float w = acousticCtx.font.width(label) + acousticCtx.font.width(SPACE)
                + acousticCtx.font.width(distStr) + acousticCtx.font.width(METERS);
        float rx = acousticCtx.rx(x);
        float ry = acousticCtx.ry(y + 0.5);
        float rz = acousticCtx.rz(z);
        Matrix4f m = acousticCtx.kit.beginLabel(acousticCtx.pose, rx, ry, rz);
        float tx = -w / 2f;
        tx = WorldRenderKit.text(acousticCtx.font, acousticCtx.buffers, m, label, tx, argb);
        tx = WorldRenderKit.text(acousticCtx.font, acousticCtx.buffers, m, SPACE, tx, argb);
        tx = WorldRenderKit.text(acousticCtx.font, acousticCtx.buffers, m, distStr, tx, argb);
        WorldRenderKit.text(acousticCtx.font, acousticCtx.buffers, m, METERS, tx, argb);
    }
}
