package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;

/**
 * Tree feller / vein miner: the user breaks one log or ore by hand (optionally only while sneaking); the connected
 * same-type blocks are found with a budgeted {@link VeinCollector} flood fill (capped), then mined one at a time like a
 * player, nearest first, within reach and line of sight, with a smoothed look and a human pause between blocks. The
 * right tool comes from the survival autopilot's auto-tool on each attack. Stops on manual input, Stop-All, a tool
 * about to break, or a full inventory. Blocks out of reach are left (walking to them is listed in the notes).
 */
public final class VeinWorkModule extends FieldModule {

    public static final String ID = "vein_work";
    private static final int SCAN_BUDGET = 96;
    private static final int ARM_TICKS = 400;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final VeinCollector collector = new VeinCollector();
    private final VeinCollector.Lookup lookup = this::matches;

    private boolean requireSneak;
    private boolean doTrees = true;
    private boolean doOres = true;
    private int cap = VeinCollector.DEFAULT_CAP;
    private int protectUses = 8;

    private long tick;
    // armed: the user started breaking a matching block
    private long armed = Long.MIN_VALUE;
    private long armedSince;
    private Block armedBlock;
    private boolean armedTree;
    // run
    private Block target;
    private boolean scanning;
    private boolean running;
    private long current = Long.MIN_VALUE;
    private ClientLevel level;

    public VeinWorkModule() {
        super(ID, ControlPriority.GOAL);
    }

    /** NeoForge client event: arms the run when the user (not automation) starts breaking a log or ore. */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!enabled || running || scanning || !event.getLevel().isClientSide()
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        if (Services.arbiter().holder(ControlChannel.WORLD_INTERACT).isPresent()) {
            return; // automation is mining, not the user
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (requireSneak && !mc.player.isShiftKeyDown())) {
            return;
        }
        BlockState s = event.getLevel().getBlockState(event.getPos());
        boolean log = s.is(BlockTags.LOGS);
        boolean ore = s.is(Tags.Blocks.ORES);
        if ((log && doTrees) || (ore && doOres)) {
            armed = event.getPos().asLong();
            armedSince = tick;
            armedBlock = s.getBlock();
            armedTree = log;
        }
    }

    @Override
    public void onTick() {
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel lvl = mc.level;
        if (player == null || lvl == null) {
            return;
        }
        level = lvl;
        if (armed != Long.MIN_VALUE) {
            tickArmed(lvl);
            return;
        }
        if (scanning) {
            if (collector.step(lookup, SCAN_BUDGET)) {
                scanning = false;
                running = !collector.isEmpty();
                if (!running) {
                    collector.clear();
                }
            }
            return;
        }
        if (running) {
            tickRun(mc, player);
        }
    }

    private void tickArmed(ClientLevel lvl) {
        if (tick - armedSince > ARM_TICKS) {
            armed = Long.MIN_VALUE;
            return;
        }
        cursor.set(armed);
        if (lvl.getBlockState(cursor).is(armedBlock)) {
            return; // the user is still breaking it
        }
        target = armedBlock;
        collector.begin(armed, armedTree, cap);
        armed = Long.MIN_VALUE;
        scanning = true;
    }

    /** Lookup for the flood fill: loaded cell holding the same block. Allocation free. */
    private boolean matches(int x, int y, int z) {
        cursor.set(x, y, z);
        return level != null && level.isLoaded(cursor) && level.getBlockState(cursor).is(target);
    }

    private void tickRun(Minecraft mc, LocalPlayer player) {
        if (hands.consumePreempted() || toolAboutToBreak(player) || player.getInventory().getFreeSlot() < 0) {
            stopWork();
            return;
        }
        if (mc.screen != null) {
            return;
        }
        if (!hands.ensureLease()) {
            return; // the manual-override pause after the user's own break; starts when it lapses
        }
        if (hands.coolingDown()) {
            return;
        }
        if (current == Long.MIN_VALUE) {
            current = collector.nearest(player.getX(), player.getEyeY(), player.getZ(),
                    player.blockInteractionRange() - 0.25);
            if (current == Long.MIN_VALUE) {
                stopWork(); // nothing left within reach
                return;
            }
        }
        int x = BlockPos.getX(current), y = BlockPos.getY(current), z = BlockPos.getZ(current);
        cursor.set(x, y, z);
        if (!level.getBlockState(cursor).is(target)) {
            collector.remove(current);
            current = Long.MIN_VALUE;
            return;
        }
        int r = hands.tickBreak(mc, player, x, y, z, relaxed, !relaxed);
        if (r != FieldHands.RUNNING) {
            collector.remove(current); // mined, or skipped (not visible from here)
            current = Long.MIN_VALUE;
            hands.clearLook();
            hands.pauseHuman(4);
            if (collector.isEmpty()) {
                stopWork();
            }
        }
    }

    private boolean toolAboutToBreak(LocalPlayer player) {
        ItemStack s = player.getMainHandItem();
        return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= protectUses;
    }

    @Override
    protected void stopWork() {
        armed = Long.MIN_VALUE;
        scanning = false;
        running = false;
        current = Long.MIN_VALUE;
        collector.clear();
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        requireSneak = p.getBool(ID, "require_sneak", false);
        doTrees = p.getBool(ID, "trees", true);
        doOres = p.getBool(ID, "ores", true);
        cap = p.getInt(ID, "max_blocks", VeinCollector.DEFAULT_CAP);
        protectUses = p.getInt(ID, "protect_tool_uses", 8);
    }

    @Override
    public String displayName() {
        return "Vein Work";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.bool("trees", "Fell trees", true, "", "Breaking one log mines the connected logs"),
                SettingSpec.bool("ores", "Mine veins", true, "", "Breaking one ore mines the connected ore"),
                SettingSpec.bool("require_sneak", "Only while sneaking", false, "",
                        "Start only when the first block is broken while sneaking"),
                SettingSpec.integer("max_blocks", "Max blocks", VeinCollector.DEFAULT_CAP, 1, VeinCollector.MAX_CAP,
                        "", "Largest tree or vein worked in one go"),
                SettingSpec.integer("protect_tool_uses", "Stop when tool has uses left", 8, 0, 100, "",
                        "Stop before the held tool breaks"),
                relaxedSpec());
    }
}
