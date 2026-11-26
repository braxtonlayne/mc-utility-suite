package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlChannel;
import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.IRouteRunner;
import com.utilitysuite.core.api.LeaseEndReason;
import com.utilitysuite.core.api.LeaseListener;
import com.utilitysuite.core.api.LeaseRequest;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.visual.NoticeKind;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.Tags;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Optional;

/**
 * Tree feller / vein miner: the user breaks one log or ore by hand (optionally only while sneaking); the connected
 * same-type blocks are found with a budgeted {@link VeinCollector} flood fill (capped), then mined one at a time like a
 * player, nearest first, within reach and line of sight, with a smoothed look and a human pause between blocks. The
 * right tool comes from the survival autopilot's auto-tool on each attack. Stops on manual input, Stop-All, a tool
 * about to break, or a full inventory.
 * <p>
 * Before each block is aimed at, its six neighbours are probed for lava and the block above for a gravity block
 * ({@link VeinRules#hazard}); either way the block is dropped from the queue (skipping is safer and simpler than
 * waiting: the lava or sand is still there after any wait), with one WARN notice per run for lava. Blocks out of reach
 * are left, unless the opt-in "Walk to remaining blocks" is on: then the route runner walks toward the nearest queued
 * block under a MOVEMENT + CAMERA lease (the hands' lease is released first), never more than
 * {@value #WALK_CAP} blocks from where the vein run started; blocks a walk that far cannot bring within reach are left.
 * <p>
 * In the Nether a run holds while Lag Guard reports a server stall ({@code Services.serverHealth()}, never while Lag
 * Guard is off): keys released, no break and no walk; the next block is re-picked and re-probed on recovery.
 */
public final class VeinWorkModule extends FieldModule {

    public static final String ID = "vein_work";
    private static final int SCAN_BUDGET = 96;
    private static final int ARM_TICKS = 400;
    /** Queued blocks hazard-checked per tick before giving the rest of the work to the next tick. */
    private static final int HAZARD_CHECKS_PER_TICK = 8;
    /** Horizontal blocks the player may walk away from where the run started. */
    private static final double WALK_CAP = 4.0;
    private static final double WALK_STOP_RANGE = 1.5;
    private static final int WALK_TIMEOUT_TICKS = 160;
    private static final int LEASE_WAIT_TICKS = 100;

    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private final BlockPos.MutableBlockPos probePos = new BlockPos.MutableBlockPos();
    private final VeinCollector collector = new VeinCollector();
    private final VeinCollector.Lookup lookup = this::matches;
    private final VeinRules.Probe probe = new VeinRules.Probe() {
        @Override
        public boolean lavaAt(int x, int y, int z) {
            probePos.set(x, y, z);
            return level.getFluidState(probePos).is(FluidTags.LAVA);
        }

        @Override
        public boolean fallingBlockAt(int x, int y, int z) {
            probePos.set(x, y, z);
            return level.getBlockState(probePos).getBlock() instanceof FallingBlock;
        }
    };
    private final LeaseRequest walkRequest;
    private final StallHold stall = new StallHold();

    private boolean requireSneak;
    private boolean doTrees = true;
    private boolean doOres = true;
    private int cap = VeinCollector.DEFAULT_CAP;
    private int protectUses = 8;
    private boolean walkToBlocks;

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
    private int waitTicks;
    private ClientLevel level;
    private boolean lavaWarned;
    /** Set by {@link #pickTarget} when it ran out of hazard checks this tick (try again next tick, do not stop). */
    private boolean pickBusy;
    private double originX;
    private double originZ;
    // walk to the remaining blocks
    private ControlLease walkLease;
    private long walkTarget = Long.MIN_VALUE;
    private int walkTicks;
    /** Ticks the walk lease was refused in a row (the hands' waitTicks resets every tick they hold their lease). */
    private int walkWaitTicks;
    private boolean walkPreempted;

    public VeinWorkModule() {
        super(ID, ControlPriority.GOAL);
        LeaseListener walkListener = this::onWalkPreempted;
        walkRequest = LeaseRequest.of(ID, ControlPriority.GOAL, walkListener,
                ControlChannel.MOVEMENT, ControlChannel.CAMERA);
    }

    /** NeoForge client event: arms the run when the user (not automation) starts breaking a log or ore. */
    public void onLeftClickBlock(PlayerInteractEvent.LeftClickBlock event) {
        if (!enabled || running || scanning || !event.getLevel().isClientSide()
                || event.getAction() != PlayerInteractEvent.LeftClickBlock.Action.START) {
            return;
        }
        if (Services.arbiter().holderOrNull(ControlChannel.WORLD_INTERACT) != null) {
            return; // automation is mining, not the user (allocation-free holder check)
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || (requireSneak && !mc.player.isShiftKeyDown())) {
            return;
        }
        BlockState s = event.getLevel().getBlockState(event.getPos());
        boolean log = s.is(BlockTags.LOGS);
        boolean ore = s.is(Tags.BlockItems.ORES.block());
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
                waitTicks = 0;
                originX = player.getX(); // the walk cap is measured from here
                originZ = player.getZ();
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

    /**
     * Starts a vein run at a block that is still standing (another field module's hand-off, e.g. ancient debris a
     * Debris Blasting blast revealed): that block and its connected same-type neighbours are mined exactly like a
     * user-armed vein (hazard probes, reach, optional short walk). Runs whether or not Vein Work itself is switched on.
     * False when a run is already armed or under way, or the cell holds nothing. Client thread.
     */
    public boolean arm(int x, int y, int z) {
        Minecraft mc = Minecraft.getInstance();
        if (isBusy() || mc.level == null) {
            return false;
        }
        BlockState s = mc.level.getBlockState(cursor.set(x, y, z));
        if (s.isAir() || s.canBeReplaced()) {
            return false;
        }
        level = mc.level;
        target = s.getBlock();
        collector.beginAt(BlockPos.asLong(x, y, z), false, cap);
        scanning = true;
        lavaWarned = false;
        return true;
    }

    /** True while a vein run is armed, scanning or mining. */
    public boolean isBusy() {
        return armed != Long.MIN_VALUE || scanning || running;
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
        lavaWarned = false;
    }

    /** Lookup for the flood fill: loaded cell holding the same block. Allocation free. */
    private boolean matches(int x, int y, int z) {
        cursor.set(x, y, z);
        return level != null && level.isLoaded(cursor) && level.getBlockState(cursor).is(target);
    }

    private void tickRun(Minecraft mc, LocalPlayer player) {
        if (hands.consumePreempted() || walkPreempted || toolAboutToBreak(player)
                || player.getInventory().getFreeSlot() < 0) {
            stopWork();
            return;
        }
        if (stall.update(Services.serverHealth().pauseHighRisk(), level.dimension() == Level.NETHER)) {
            // Lag Guard, Nether mining: no breaks (and no walk) during a server stall. The current block is dropped
            // back into the queue so it is re-picked and hazard-probed again (lava may sit next to it) on recovery.
            if (walkLease != null || walkTarget != Long.MIN_VALUE) {
                endWalk(false);
            }
            hands.release();
            current = Long.MIN_VALUE;
            waitTicks = 0;
            return;
        }
        if (walkLease != null || walkTarget != Long.MIN_VALUE) {
            tickWalk(mc, player);
            return;
        }
        if (mc.gui.screen() != null) {
            return;
        }
        if (!hands.ensureLease()) {
            if (++waitTicks > LEASE_WAIT_TICKS) {
                stopWork(); // the user kept the hands busy: give up the run
            }
            return; // the manual-override pause after the user's own break; starts when it lapses
        }
        waitTicks = 0;
        if (hands.coolingDown()) {
            return;
        }
        double reach = player.blockInteractionRange();
        if (current == Long.MIN_VALUE) {
            current = pickTarget(player, reach - 0.25);
            if (current == Long.MIN_VALUE) {
                if (pickBusy) {
                    return; // hazard checks continue next tick
                }
                if (walkToBlocks && startWalk(player, reach)) {
                    return;
                }
                if (walkToBlocks && pickBusy) {
                    return; // the walk target was hazardous and dropped, or the walk lease was refused: retry
                }
                stopWork(); // nothing left within reach (or within a short walk)
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
        // A hand-off run (e.g. debris after a blast) starts with whatever the last module held: take the best tool
        // first (playtest find: ancient debris "mined" with netherrack in hand until the run gave up).
        if (hands.holdBestTool(player, level.getBlockState(cursor)) != FieldHands.DONE) {
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

    /**
     * The nearest queued block within {@code maxDist} of the eyes that is safe to mine; hazardous ones met on the way
     * are dropped from the queue. {@link Long#MIN_VALUE} when none, with {@link #pickBusy} set when the per-tick
     * hazard-check budget ran out first. Allocation free (the lava notice is posted at most once per run).
     */
    private long pickTarget(LocalPlayer player, double maxDist) {
        pickBusy = false;
        for (int i = 0; i < HAZARD_CHECKS_PER_TICK; i++) {
            long c = collector.nearest(player.getX(), player.getEyeY(), player.getZ(), maxDist);
            if (c == Long.MIN_VALUE || safeToMine(c)) {
                return c;
            }
        }
        pickBusy = true;
        return Long.MIN_VALUE;
    }

    /** Probes the block's surroundings; a hazardous block is dropped from the queue (false). */
    private boolean safeToMine(long c) {
        int h = VeinRules.hazard(probe, BlockPos.getX(c), BlockPos.getY(c), BlockPos.getZ(c));
        if (h == VeinRules.SAFE) {
            return true;
        }
        collector.remove(c);
        if (h == VeinRules.LAVA) {
            warnLava();
        }
        return false;
    }

    private void warnLava() {
        if (lavaWarned) {
            return;
        }
        lavaWarned = true;
        if (!Services.modState().isCleanScreen()) {
            Services.visuals().notices().post(NoticeKind.WARN, "Vein Work: skipped blocks next to lava", null);
        }
    }

    // ---- walk to the remaining blocks ---------------------------------------------------------------------------

    /**
     * Starts a short walk toward the nearest queued block a walk within {@value #WALK_CAP} blocks of the run's start
     * can reach. False when there is none, the runner is missing or refused; {@link #pickBusy} is then set when it is
     * worth retrying next tick (a hazardous candidate was dropped, or the lease was refused for now).
     */
    private boolean startWalk(LocalPlayer player, double reach) {
        pickBusy = false;
        IRouteRunner runner = Services.routeRunnerOrNull();
        if (runner == null || !runner.isAvailable()) {
            return false;
        }
        double px = player.getX(), pz = player.getZ(), eyeY = player.getEyeY();
        long best = Long.MIN_VALUE;
        double bestD = Double.MAX_VALUE;
        for (int i = 0, n = collector.size(); i < n; i++) {
            long p = collector.get(i);
            int bx = BlockPos.getX(p), by = BlockPos.getY(p), bz = BlockPos.getZ(p);
            if (!VeinRules.walkable(originX, originZ, eyeY, bx, by, bz, reach, WALK_CAP)) {
                continue;
            }
            double dx = bx + 0.5 - px, dz = bz + 0.5 - pz;
            double d = dx * dx + dz * dz;
            if (d < bestD) {
                best = p;
                bestD = d;
            }
        }
        if (best == Long.MIN_VALUE) {
            return false;
        }
        if (!safeToMine(best)) {
            pickBusy = true; // dropped; look for the next one next tick
            return false;
        }
        hands.release(); // frees CAMERA for the walk lease; re-requested when the walk ends
        Optional<ControlLease> l = Services.arbiter().request(walkRequest); // once per walk, not per tick
        if (l.isEmpty()) {
            if (++walkWaitTicks > LEASE_WAIT_TICKS) {
                return false; // the user kept moving: give up the run
            }
            pickBusy = true;
            return false;
        }
        walkWaitTicks = 0;
        walkLease = l.get();
        walkPreempted = false;
        if (!runner.pursue(walkLease, BlockPos.getX(best) + 0.5, BlockPos.getY(best), BlockPos.getZ(best) + 0.5,
                WALK_STOP_RANGE, true)) {
            walkTarget = best;
            endWalk(true);
            pickBusy = true;
            return false;
        }
        walkTarget = best;
        walkTicks = 0;
        return true;
    }

    /** Watches the walk: ends it once the target is within reach, the cap or the timeout is hit, or the run ended. */
    private void tickWalk(Minecraft mc, LocalPlayer player) {
        if (walkLease == null || !walkLease.isActive()) {
            endWalk(true); // a higher module took the legs: leave this block
            return;
        }
        IRouteRunner runner = Services.routeRunnerOrNull();
        int x = BlockPos.getX(walkTarget), y = BlockPos.getY(walkTarget), z = BlockPos.getZ(walkTarget);
        cursor.set(x, y, z);
        if (!level.getBlockState(cursor).is(target)) {
            collector.remove(walkTarget);
            endWalk(false);
            return;
        }
        double reach = player.blockInteractionRange() - 0.25;
        double dx = x + 0.5 - player.getX(), dy = y + 0.5 - player.getEyeY(), dz = z + 0.5 - player.getZ();
        if (dx * dx + dy * dy + dz * dz <= reach * reach) {
            endWalk(false); // in reach: the hands take over
            return;
        }
        double ox = player.getX() - originX, oz = player.getZ() - originZ;
        boolean overCap = ox * ox + oz * oz > (WALK_CAP + 0.5) * (WALK_CAP + 0.5);
        if (runner == null || overCap || mc.gui.screen() != null || ++walkTicks > WALK_TIMEOUT_TICKS
                || !runner.isRunning(walkLease)) {
            endWalk(mc.gui.screen() == null); // arrived out of reach, failed, capped or timed out: leave this block
        }
    }

    /** Stops the runner and releases the walk lease; {@code giveUp} drops the walk target from the queue. */
    private void endWalk(boolean giveUp) {
        ControlLease l = walkLease;
        walkLease = null;
        if (l != null) {
            IRouteRunner runner = Services.routeRunnerOrNull();
            if (runner != null) {
                runner.stop(l);
            }
            Services.input().releaseFor(l);
            l.close();
        }
        if (giveUp && walkTarget != Long.MIN_VALUE) {
            collector.remove(walkTarget);
        }
        walkTarget = Long.MIN_VALUE;
        walkTicks = 0;
    }

    private void onWalkPreempted(ControlLease l, LeaseEndReason reason, String byModuleId) {
        if (l == walkLease) {
            // the user's own input or Stop-All ends the run; another module taking the legs only ends this walk
            walkPreempted = reason == LeaseEndReason.MANUAL_OVERRIDE || reason == LeaseEndReason.STOP_ALL;
        }
    }

    private boolean toolAboutToBreak(LocalPlayer player) {
        ItemStack s = player.getMainHandItem();
        return s.isDamageableItem() && s.getMaxDamage() - s.getDamageValue() <= protectUses;
    }

    @Override
    protected void stopWork() {
        endWalk(false);
        walkPreempted = false;
        walkWaitTicks = 0;
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
        walkToBlocks = p.getBool(ID, "walk_to_blocks", false);
        if (!walkToBlocks && walkLease != null) {
            endWalk(false);
        }
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
                SettingSpec.bool("walk_to_blocks", "Walk to remaining blocks", false, "",
                        "When nothing is left within reach, walk toward the rest (at most 4 blocks from the start)"),
                relaxedSpec());
    }
}
