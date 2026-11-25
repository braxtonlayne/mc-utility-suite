package com.utilitysuite.world.trial;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldIds;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.WorldRuntime;
import com.utilitysuite.world.overlays.BlockCategories;
import com.utilitysuite.world.overlays.HighlightIndex;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.TrialSpawnerBlock;
import net.minecraft.world.level.block.VaultBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Trial Chamber assistant: vaults and trial spawners found by the shared section scanner (the SPAWNER category) are
 * re-read from their block states and outlined by status: ominous vs normal, open / reward / cooldown / idle, and
 * vaults this player already opened. The reward list is server-only, so "looted" is inferred locally (you stood next
 * to an inactive vault with nobody else near for a few seconds) and remembered per dimension for the session.
 * <p>
 * The tick re-classifies a budgeted slice of the scanner's render list into a back buffer and swaps it when the pass
 * completes, so frames walk two flat arrays (no allocation).
 */
public final class TrialChamberModule extends WorldModule {

    public static final String ID = "trial_chambers";
    /** Render-list entries examined per tick. */
    private static final int ENTRIES_PER_TICK = 512;
    /** Ticks the "inactive while I stand next to it" condition must hold before a vault counts as looted. */
    private static final int LOOT_CONFIRM_TICKS = 60;
    private static final double OTHER_PLAYER_RANGE_SQ = 6.0 * 6.0;

    private final WorldRuntime runtime;
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final Object2ObjectOpenHashMap<String, LongOpenHashSet> lootedByDim = new Object2ObjectOpenHashMap<>();
    private LongOpenHashSet looted = new LongOpenHashSet();

    private int range;
    private boolean vaults;
    private boolean spawners;
    private boolean labels;
    private boolean hideLooted;
    private boolean inferLooted;

    // Front buffer (rendered) and back buffer (being filled).
    private long[] pos = new long[16];
    private int[] code = new int[16];
    private int count;
    private long[] backPos = new long[16];
    private int[] backCode = new int[16];
    private int backCount;
    private int listCursor;

    private String dimension;
    private long nearVault = Long.MIN_VALUE;
    private int nearTicks;

    public TrialChamberModule(WorldRuntime runtime) {
        super(ID, "Trial Chambers", false);
        this.runtime = runtime;
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("range", "Range", 64, 8, 256, "", "Outline vaults and trial spawners this close."),
                SettingSpec.bool("vaults", "Vaults", true, "", "Outline vaults (ominous red, normal teal)."),
                SettingSpec.bool("spawners", "Trial spawners", true, "", "Outline trial spawners by state."),
                SettingSpec.bool("labels", "Labels", true, "", "Status text above blocks within 24 blocks."),
                SettingSpec.bool("hide_looted", "Hide looted vaults", false, "",
                        "Do not draw vaults you already opened."),
                SettingSpec.bool("infer_looted", "Remember looted vaults", true, "",
                        "A vault that stays inactive while you stand next to it (nobody else near) is marked as "
                                + "opened by you for this session."));
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        range = integer(p, "range");
        vaults = bool(p, "vaults");
        spawners = bool(p, "spawners");
        labels = bool(p, "labels");
        hideLooted = bool(p, "hide_looted");
        inferLooted = bool(p, "infer_looted");
    }

    @Override
    public int scanMask() {
        return BlockCategories.SPAWNER;
    }

    @Override
    protected void onDisable() {
        count = 0;
        backCount = 0;
        listCursor = 0;
    }

    @Override
    public void onWorldUnload() {
        onDisable();
        lootedByDim.clear();
        looted = new LongOpenHashSet();
        dimension = null;
        nearVault = Long.MIN_VALUE;
    }

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel level = mc.level;
        LocalPlayer player = mc.player;
        if (level == null || player == null) {
            return;
        }
        String dim = WorldIds.dimension(level);
        if (!dim.equals(dimension)) {
            dimension = dim;
            looted = lootedByDim.computeIfAbsent(dim, d -> new LongOpenHashSet()); // once per dimension change
            onDisable();
        }
        classifySlice(level, player);
        if (inferLooted) {
            inferNearest(level, player);
        }
    }

    /** Examines up to {@link #ENTRIES_PER_TICK} scanner entries; swaps buffers when the pass wraps. */
    private void classifySlice(ClientLevel level, LocalPlayer player) {
        HighlightIndex observed = runtime.scanner().observed;
        int n = observed.renderCount();
        double rangeSq = (double) range * range;
        double px = player.getX();
        double py = player.getY();
        double pz = player.getZ();
        int end = Math.min(n, listCursor + ENTRIES_PER_TICK);
        for (int i = listCursor; i < end; i++) {
            if ((HighlightIndex.categories(observed.renderValue(i)) & BlockCategories.SPAWNER) == 0) {
                continue;
            }
            long p = observed.renderPos(i);
            int x = BlockPosPacking.x(p);
            int y = BlockPosPacking.y(p);
            int z = BlockPosPacking.z(p);
            double dx = x + 0.5 - px;
            double dy = y + 0.5 - py;
            double dz = z + 0.5 - pz;
            if (dx * dx + dy * dy + dz * dz > rangeSq) {
                continue;
            }
            int c = classifyAt(level, cursor.set(x, y, z), p);
            if (c != 0) {
                pushBack(p, c);
            }
        }
        listCursor = end;
        if (listCursor >= n) {
            swap();
            listCursor = 0;
        }
    }

    private int classifyAt(ClientLevel level, BlockPos at, long packed) {
        BlockState s = level.getBlockState(at);
        Block b = s.getBlock();
        if (b instanceof VaultBlock) {
            return TrialBlocks.classify(TrialBlocks.KIND_VAULT, s.getValue(VaultBlock.STATE).getSerializedName(),
                    s.getValue(VaultBlock.OMINOUS), looted.contains(packed));
        }
        if (b instanceof TrialSpawnerBlock) {
            return TrialBlocks.classify(TrialBlocks.KIND_SPAWNER,
                    s.getValue(TrialSpawnerBlock.STATE).getSerializedName(), s.getValue(TrialSpawnerBlock.OMINOUS),
                    false);
        }
        return 0;
    }

    private void pushBack(long p, int c) {
        if (backCount == backPos.length) {
            backPos = java.util.Arrays.copyOf(backPos, backCount * 2); // rare growth, not per tick
            backCode = java.util.Arrays.copyOf(backCode, backCount * 2);
        }
        backPos[backCount] = p;
        backCode[backCount] = c;
        backCount++;
    }

    private void swap() {
        long[] tp = pos;
        int[] tc = code;
        pos = backPos;
        code = backCode;
        count = backCount;
        backPos = tp;
        backCode = tc;
        backCount = 0;
    }

    /** Looted inference for the nearest vault within range (front buffer only, a handful of entries). */
    private void inferNearest(ClientLevel level, LocalPlayer player) {
        if (player.isSpectator()) {
            nearTicks = 0;
            return;
        }
        long best = Long.MIN_VALUE;
        double bestSq = Double.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (TrialBlocks.kind(code[i]) != TrialBlocks.KIND_VAULT) {
                continue;
            }
            long p = pos[i];
            double d = distSq(player, p);
            if (d < bestSq) {
                bestSq = d;
                best = p;
            }
        }
        if (best == Long.MIN_VALUE || looted.contains(best)) {
            nearTicks = 0;
            return;
        }
        BlockState s = level.getBlockState(cursor.set(BlockPosPacking.x(best), BlockPosPacking.y(best),
                BlockPosPacking.z(best)));
        if (!(s.getBlock() instanceof VaultBlock)) {
            nearTicks = 0;
            return;
        }
        String state = s.getValue(VaultBlock.STATE).getSerializedName();
        if (!TrialBlocks.inferLooted(state, bestSq, otherPlayerNear(level, player, best))) {
            nearTicks = 0;
            nearVault = best;
            return;
        }
        if (best != nearVault) {
            nearVault = best;
            nearTicks = 0;
        }
        if (++nearTicks >= LOOT_CONFIRM_TICKS) {
            looted.add(best);
            nearTicks = 0;
            listCursor = 0; // re-classify promptly
            backCount = 0;
        }
    }

    private static double distSq(Player player, long p) {
        double dx = BlockPosPacking.x(p) + 0.5 - player.getX();
        double dy = BlockPosPacking.y(p) + 0.5 - player.getY();
        double dz = BlockPosPacking.z(p) + 0.5 - player.getZ();
        return dx * dx + dy * dy + dz * dz;
    }

    private static boolean otherPlayerNear(ClientLevel level, LocalPlayer self, long p) {
        List<? extends Player> players = level.players();
        for (int i = 0, n = players.size(); i < n; i++) {
            Player o = players.get(i);
            if (o != self && !o.isSpectator() && distSq(o, p) <= OTHER_PLAYER_RANGE_SQ) {
                return true;
            }
        }
        return false;
    }

    private boolean shown(int c) {
        int kind = TrialBlocks.kind(c);
        if (kind == TrialBlocks.KIND_VAULT) {
            return vaults && !(hideLooted && TrialBlocks.status(c) == TrialBlocks.STATUS_LOOTED);
        }
        return spawners;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        for (int i = 0; i < count; i++) {
            int c = code[i];
            if (!shown(c)) {
                continue;
            }
            long p = pos[i];
            float x = ctx.rx(BlockPosPacking.x(p));
            float y = ctx.ry(BlockPosPacking.y(p));
            float z = ctx.rz(BlockPosPacking.z(p));
            WorldRenderKit.box(ctx.lines, ctx.pose, x + 0.02f, y + 0.02f, z + 0.02f, x + 0.98f, y + 0.98f, z + 0.98f,
                    TrialBlocks.color(c));
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (!labels) {
            return;
        }
        for (int i = 0; i < count; i++) {
            int c = code[i];
            long p = pos[i];
            if (!shown(c) || ctx.distSq(BlockPosPacking.x(p), BlockPosPacking.y(p), BlockPosPacking.z(p)) > 24 * 24) {
                continue;
            }
            String text = TrialBlocks.label(c);
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, ctx.rx(BlockPosPacking.x(p) + 0.5),
                    ctx.ry(BlockPosPacking.y(p) + 1.4), ctx.rz(BlockPosPacking.z(p) + 0.5));
            WorldRenderKit.text(ctx.font, ctx.buffers, m, text, -ctx.font.width(text) / 2f,
                    TrialBlocks.color(c) | 0xFF000000);
        }
    }
}
