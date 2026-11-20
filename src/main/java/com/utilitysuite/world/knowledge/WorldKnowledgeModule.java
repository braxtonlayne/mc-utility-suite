package com.utilitysuite.world.knowledge;

import com.utilitysuite.UtilitySuiteMod;
import com.utilitysuite.core.api.DataSourceMode;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldFiles;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.overlays.BlockCategories;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Hosts the {@link WorldKnowledgeService}: data-source modes (overlay and routing, chosen separately), verification of
 * predictions against loaded blocks, and the per-world ledger file ({@code config/utilitysuite/world/<profile>.ledger},
 * binary, packed longs). Enabled by default; when disabled the service still answers from what is recorded but nothing
 * new is verified.
 */
public final class WorldKnowledgeModule extends WorldModule {

    public static final String ID = "world_knowledge";
    private static final int REFILL_INTERVAL = 20;
    private static final int SAVE_INTERVAL = 20 * 60;

    private final WorldKnowledgeService service;
    private final VerificationTracker verification;
    private final VerificationTracker.BlockLookup lookup = this::lookup;
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private PredictionSource predictions = PredictionSource.NONE;
    private String profileId;
    private int verifyRadius;
    private int verifyPerTick;
    private boolean exposedOnlyRemote;
    private boolean exposedOnly;
    private int ticks;
    private ClientLevel lookupLevel;

    public WorldKnowledgeModule(WorldKnowledgeService service) {
        super(ID, "World Knowledge", true);
        this.service = service;
        this.verification = new VerificationTracker(service);
        service.setModeListener(this::persistModes);
    }

    public WorldKnowledgeService service() {
        return service;
    }

    public void setPredictionSource(PredictionSource source) {
        predictions = source == null ? PredictionSource.NONE : source;
        service.setPredictionSource(predictions);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.choice("overlay_mode", "Overlay data", DataSourceMode.HYBRID, "Data sources",
                        "What the world overlays show: blocks seen in loaded chunks, seed predictions, or both. "
                                + "Predictions are always drawn as dashed ghost outlines."),
                SettingSpec.choice("routing_mode", "Routing data", DataSourceMode.OBSERVED_ONLY, "Data sources",
                        "What automation (planner / pathing) may route to. Independent of the overlay setting."),
                SettingSpec.integer("verify_radius", "Verify radius", 48, 8, 128, "Verification",
                        "Predicted blocks within this distance are checked against the loaded world."),
                SettingSpec.integer("verify_per_tick", "Checks per tick", 32, 1, 256, "Verification",
                        "Upper bound on prediction checks per tick.").asAdvanced(),
                SettingSpec.bool("exposed_only_remote", "On servers, verify exposed blocks only", true, "Verification",
                        "Servers may send hidden blocks as stone; only blocks next to open space are trusted there."),
                SettingSpec.integer("suppress_after", "Hide cluster after misses", 2, 1, 10, "Verification",
                        "A predicted vein is hidden once this many of its blocks were found to be something else."),
                SettingSpec.decimal("downrank_per_miss", "Confidence kept per miss", 0.35, 0.0, 1.0, "Verification",
                        "Each miss multiplies the confidence of the rest of its vein by this.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        service.loadModes(choice(p, "overlay_mode", DataSourceMode.class), choice(p, "routing_mode",
                DataSourceMode.class));
        verifyRadius = integer(p, "verify_radius");
        verifyPerTick = integer(p, "verify_per_tick");
        exposedOnlyRemote = bool(p, "exposed_only_remote");
        service.ledger().setPolicy(integer(p, "suppress_after"), (float) decimal(p, "downrank_per_miss"));
        if (!"global".equals(p.profileId()) && !p.profileId().equals(profileId)) {
            saveLedger(false);
            profileId = p.profileId();
            loadLedger();
        }
    }

    private void persistModes() {
        ProfileConfig p = Services.modState().profile();
        p.set(ID, "overlay_mode", service.overlayMode().name());
        p.set(ID, "routing_mode", service.routingMode().name());
        Services.modState().markDirty();
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null) {
            return;
        }
        lookupLevel = level;
        exposedOnly = exposedOnlyRemote && mc.getSingleplayerServer() == null;
        if (++ticks % REFILL_INTERVAL == 0 || verification.pending() == 0 && ticks % 5 == 0) {
            verification.refill(predictions, WorldIds.dimension(level),
                    BlockPosPacking.pack(player.getBlockX(), player.getBlockY(), player.getBlockZ()), verifyRadius,
                    4096);
        }
        verification.step(lookup, verifyPerTick);
        if (ticks % SAVE_INTERVAL == 0) {
            saveLedger(true);
        }
    }

    /** Real block at a position, or null when unknown (unloaded, or hidden and not trustworthy). */
    private String lookup(int x, int y, int z) {
        ClientLevel level = lookupLevel;
        if (level == null || !level.hasChunk(x >> 4, z >> 4)) {
            return null;
        }
        BlockState s = level.getBlockState(probe.set(x, y, z));
        if (exposedOnly && !exposed(level, x, y, z)) {
            return null;
        }
        return WorldIds.block(s.getBlock());
    }

    private boolean exposed(ClientLevel level, int x, int y, int z) {
        return open(level, x + 1, y, z) || open(level, x - 1, y, z) || open(level, x, y + 1, z)
                || open(level, x, y - 1, z) || open(level, x, y, z + 1) || open(level, x, y, z - 1);
    }

    private boolean open(ClientLevel level, int x, int y, int z) {
        BlockState s = level.getBlockState(probe.set(x, y, z));
        return s.isAir() || !s.getFluidState().isEmpty();
    }

    /** Ores the scanner sees are recorded as observations for the ledger (bounded per dimension). */
    @Override
    public int scanMask() {
        return BlockCategories.ORES;
    }

    @Override
    public void onWorldUnload() {
        saveLedger(false);
        lookupLevel = null;
        profileId = null;
        service.ledger().clear();
        service.ledger().clearDirty();
    }

    // ---- persistence --------------------------------------------------------------------------------------------

    private Path ledgerFile() {
        return WorldFiles.file(profileId, ".ledger");
    }

    private void loadLedger() {
        KnowledgeLedger ledger = service.ledger();
        ledger.clear();
        byte[] data = WorldFiles.read(ledgerFile());
        if (data != null) {
            try {
                ledger.read(new DataInputStream(new ByteArrayInputStream(data)));
            } catch (IOException | RuntimeException e) {
                UtilitySuiteMod.LOGGER.warn("Ignoring unreadable knowledge ledger {}", ledgerFile(), e);
                ledger.clear();
            }
        }
        ledger.clearDirty();
    }

    private void saveLedger(boolean async) {
        KnowledgeLedger ledger = service.ledger();
        if (profileId == null || !ledger.isDirty()) {
            return;
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            ledger.write(new DataOutputStream(bytes));
        } catch (IOException e) {
            UtilitySuiteMod.LOGGER.warn("Could not serialise knowledge ledger", e);
            return;
        }
        ledger.clearDirty();
        if (async) {
            WorldFiles.writeAsync(ledgerFile(), bytes.toByteArray());
        } else {
            WorldFiles.writeNow(ledgerFile(), bytes.toByteArray());
        }
    }
}
