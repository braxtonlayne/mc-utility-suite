package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.Services;
import com.utilitysuite.core.api.SettingSpec;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Ancient-debris blasting co-pilot (off by default, Nether only). Drives {@link DebrisBlastCycle}: while the player
 * walks a 1x2 tunnel at Y ~15 in the direction they faced when it was switched on, it digs the two cells ahead; every
 * N blocks it digs a two-deep side pocket, places a bed (or TNT), waits for the player to step back to the safe stand
 * distance (the player walks: the module never moves the body), places a blast-shield block ahead of the feet,
 * measures the real cover with vanilla's explosion seen-percent, and only then triggers (uses the bed / lights the TNT
 * with flint and steel). After the blast it hands exposed lava to {@link LavaPlugModule#plugAround} (filler blocks from
 * {@link FieldRules#PLUG_ITEMS}) and revealed ancient debris in reach to {@link VeinWorkModule#arm} (hazard probes,
 * connected debris), releasing its own hands while they work. Every look is smoothed and every action goes through the
 * core lease ({@link FieldHands}); safety aborts come from the cycle (low health, lava at the feet, no shield block,
 * unsafe stand spot) and switch the module off with a status message. While Lag Guard reports a server stall
 * ({@code Services.serverHealth()}) the cycle holds its phase with the keys released and resumes after recovery.
 */
public final class DebrisBlastModule extends FieldModule {

    public static final String ID = "debris_blast";
    private static final int AREA = 4;
    private static final double REACH = 4.5;
    static final String STALL_HELD = "Server stall: debris blasting paused";
    static final String STALL_LIVE = "Server stall: charge is live, stay behind the shield";

    private final DebrisBlastCycle.Config config = new DebrisBlastCycle.Config();
    private final DebrisBlastCycle cycle = new DebrisBlastCycle(config);
    private final DebrisBlastCycle.Observation obs = new DebrisBlastCycle.Observation();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

    private Direction heading = Direction.NORTH;
    private long lastDugColumn = Long.MIN_VALUE;
    private int blocksSincePocket;
    /** Charge cell (feet level of the pocket's first cell). */
    private int cx, cy, cz;
    private int pocketStep;
    private long tick;
    private String status = "";

    private final VeinWorkModule veinWork;
    private final LavaPlugModule lavaPlug;

    private static final int RETRY_TICKS = 40;
    private static final String WAITING_PREFIX = "Debris blast waiting: ";
    private int waitTicks;

    /** Missing preconditions keep the module on and waiting; safety aborts switch it off. */
    static boolean waitsFor(DebrisBlastCycle.Abort why) {
        return why == DebrisBlastCycle.Abort.NOT_NETHER || why == DebrisBlastCycle.Abort.WRONG_LEVEL
                || why == DebrisBlastCycle.Abort.NO_CHARGE;
    }

    public DebrisBlastModule(VeinWorkModule veinWork, LavaPlugModule lavaPlug) {
        super(ID, ControlPriority.GOAL);
        this.veinWork = veinWork;
        this.lavaPlug = lavaPlug;
    }

    public String status() {
        return status;
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (player == null || level == null || mc.screen != null) {
            return;
        }
        if (hands.consumePreempted()) {
            return; // the user's own input pauses the step; the cycle keeps its place
        }
        if (waitTicks > 0) {
            waitTicks--; // waiting for a missing precondition (Nether, charges); retried every 2 s
            return;
        }
        observe(level, player);
        if (cycle.phase() == DebrisBlastCycle.Phase.IDLE) {
            heading = player.getDirection();
            cycle.start(obs);
        }
        DebrisBlastCycle.Phase p = cycle.step(obs);
        if (p == DebrisBlastCycle.Phase.ABORTED) {
            DebrisBlastCycle.Abort why = cycle.abortReason();
            stopWork();
            if (waitsFor(why)) {
                // Nothing to do yet (not in the Nether, no TNT/bed): stay on, say what is missing, retry.
                status = WAITING_PREFIX + why;
                cycle.stop();
                waitTicks = RETRY_TICKS;
                return;
            }
            status = "Debris blast stopped: " + why;
            enabled = false;
            return;
        }
        if (cycle.stallHeld()) {
            // Lag Guard: server stall. Keys released, no TNT/bed placed or triggered, no block broken; the cycle
            // resumes in the same phase once the guard reports the server stable again.
            hands.release();
            status = cycle.chargeLive() ? STALL_LIVE : STALL_HELD; // constant strings
            return;
        }
        if (p == DebrisBlastCycle.Phase.PLUG_LAVA || p == DebrisBlastCycle.Phase.MINE_DEBRIS) {
            hands.release(); // the hand-off modules take their own leases
            if (p == DebrisBlastCycle.Phase.PLUG_LAVA) {
                plugLava(player);
            } else {
                mineDebris(player);
            }
            return;
        }
        if (veinWork.isBusy() || lavaPlug.isBusy()) {
            return; // a hand-off from the previous phase is still finishing
        }
        if (!hands.ensureLease()) {
            return;
        }
        switch (p) {
            case TUNNEL -> tunnel(mc, player);
            case POCKET -> pocket(mc, player);
            case PLACE_CHARGE -> placeCharge(mc, player);
            case RETREAT -> status = "Step back to " + (int) Math.ceil(cycle.standDistance() - 0.5) + " blocks";
            case PLACE_SHIELD -> placeShield(mc, player);
            case TRIGGER -> trigger(mc, player);
            default -> {
            }
        }
    }

    // ---- observation ------------------------------------------------------------------------------------------

    private void observe(ClientLevel level, LocalPlayer player) {
        Inventory inv = player.getInventory();
        obs.nether = level.dimension() == Level.NETHER;
        obs.feetY = player.getBlockY();
        obs.health = player.getHealth();
        obs.hasBed = has(inv, true);
        obs.hasTnt = inv.countItem(Items.TNT) > 0;
        obs.hasIgniter = inv.countItem(Items.FLINT_AND_STEEL) > 0 || inv.countItem(Items.FIRE_CHARGE) > 0;
        obs.hasShieldBlock = plugItem(inv) != null;
        obs.lavaNearFeet = lavaFlowNear(level, player.getBlockX(), player.getBlockY(), player.getBlockZ());
        obs.blocksSincePocket = blocksSincePocket;
        obs.serverStalled = Services.serverHealth().pauseHighRisk(); // always false while Lag Guard is off
        double ex = cx + 0.5, ey = cy + 0.5, ez = cz + 0.5;
        obs.distanceToCharge = Math.sqrt(player.distanceToSqr(ex, ey, ez));
        DebrisBlastCycle.Phase p = cycle.phase();
        if (p == DebrisBlastCycle.Phase.PLACE_SHIELD || p == DebrisBlastCycle.Phase.TRIGGER) {
            // Vanilla's own ray test (rays from the blast centre over the player's box); only in the two phases that
            // check it, so idle tunnelling allocates nothing here.
            obs.exposure = net.minecraft.world.level.Explosion.getSeenPercent(new Vec3(ex, ey, ez), player);
        }
        if (p == DebrisBlastCycle.Phase.RETREAT) {
            double want = Math.max(2.5, Math.min(cycle.standDistance(), cycle.requiredDistance(cycle.charge()) + 0.5));
            obs.stepDone = obs.distanceToCharge >= want;
        }
        if ((p == DebrisBlastCycle.Phase.PLUG_LAVA || p == DebrisBlastCycle.Phase.MINE_DEBRIS
                || p == DebrisBlastCycle.Phase.SETTLE) && tick % 10 == 0) {
            scanArea(level);
        }
    }

    private static boolean has(Inventory inv, boolean bed) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(ItemTags.BEDS)) {
                return true;
            }
        }
        return false;
    }

    private static Item bedItem(Inventory inv) {
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(ItemTags.BEDS)) {
                return inv.getItem(i).getItem();
            }
        }
        return null;
    }

    private static Item plugItem(Inventory inv) {
        for (String id : FieldRules.PLUG_ITEMS) {
            Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(id));
            if (item != Items.AIR && inv.countItem(item) > 0) {
                return item;
            }
        }
        return null;
    }

    /** Flowing lava in the 3x3x2 around the feet (a source the pocket opened). */
    private boolean lavaFlowNear(ClientLevel level, int x, int y, int z) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dy = 0; dy <= 1; dy++) {
                    if (level.getFluidState(cursor.set(x + dx, y + dy, z + dz)).is(FluidTags.LAVA)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private int lavaX, lavaY, lavaZ, debrisX, debrisY, debrisZ;

    /** Budgeted scan (9x9x9 every 10 ticks, only after a blast) for lava sources and debris near the pocket. */
    private void scanArea(ClientLevel level) {
        obs.exposedLava = 0;
        obs.debrisVisible = 0;
        for (int dx = -AREA; dx <= AREA; dx++) {
            for (int dy = -AREA; dy <= AREA; dy++) {
                for (int dz = -AREA; dz <= AREA; dz++) {
                    cursor.set(cx + dx, cy + dy, cz + dz);
                    BlockState s = level.getBlockState(cursor);
                    if (s.getFluidState().isSource() && s.getFluidState().is(FluidTags.LAVA) && hasAirNeighbour(level)) {
                        obs.exposedLava++;
                        lavaX = cursor.getX();
                        lavaY = cursor.getY();
                        lavaZ = cursor.getZ();
                    } else if (s.is(Blocks.ANCIENT_DEBRIS)) {
                        obs.debrisVisible++;
                        debrisX = cursor.getX();
                        debrisY = cursor.getY();
                        debrisZ = cursor.getZ();
                    }
                }
            }
        }
    }

    private boolean hasAirNeighbour(ClientLevel level) {
        int x = cursor.getX(), y = cursor.getY(), z = cursor.getZ();
        for (Direction d : FACES) {
            if (level.getBlockState(cursor.set(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())).isAir()) {
                cursor.set(x, y, z);
                return true;
            }
        }
        cursor.set(x, y, z);
        return false;
    }

    private static final Direction[] FACES = Direction.values();

    // ---- actions ----------------------------------------------------------------------------------------------

    private void tunnel(Minecraft mc, LocalPlayer player) {
        int fx = player.getBlockX() + heading.getStepX();
        int fz = player.getBlockZ() + heading.getStepZ();
        int y = player.getBlockY();
        for (int dy = 1; dy >= 0; dy--) {
            if (!mc.level.getBlockState(cursor.set(fx, y + dy, fz)).canBeReplaced()) {
                int r = hands.tickBreak(mc, player, fx, y + dy, fz, relaxed, !relaxed);
                if (r == FieldHands.FAILED) {
                    hands.pauseHuman(10);
                }
                status = "Tunnelling";
                return;
            }
        }
        long column = BlockPos.asLong(fx, y, fz);
        if (column != lastDugColumn) {
            lastDugColumn = column;
            blocksSincePocket++;
        }
        hands.clearLook();
    }

    private void pocket(Minecraft mc, LocalPlayer player) {
        Direction side = heading.getCounterClockWise();
        if (pocketStep == 0) {
            cx = player.getBlockX() + side.getStepX();
            cy = player.getBlockY();
            cz = player.getBlockZ() + side.getStepZ();
            pocketStep = 1;
        }
        for (int k = 0; k <= 1; k++) {
            int x = cx + side.getStepX() * k;
            int z = cz + side.getStepZ() * k;
            if (!mc.level.getBlockState(cursor.set(x, cy, z)).canBeReplaced()) {
                hands.tickBreak(mc, player, x, cy, z, relaxed, !relaxed);
                status = "Digging blast pocket";
                return;
            }
        }
        obs.stepDone = true;
        cycle.step(obs);
        blocksSincePocket = 0;
        pocketStep = 0;
    }

    private void placeCharge(Minecraft mc, LocalPlayer player) {
        Item item = cycle.charge() == DebrisBlastCycle.Charge.TNT ? Items.TNT : bedItem(player.getInventory());
        if (place(mc, player, item, cx, cy - 1, cz, Direction.UP)) {
            obs.stepDone = true;
            cycle.step(obs);
        }
        status = "Placing " + cycle.charge();
    }

    private void placeShield(Minecraft mc, LocalPlayer player) {
        int sx = player.getBlockX() - heading.getStepX();
        int sz = player.getBlockZ() - heading.getStepZ();
        // The shield goes on the charge side of the player at feet level: the cell toward the pocket.
        double toCx = cx + 0.5 - player.getX();
        double toCz = cz + 0.5 - player.getZ();
        if (toCx * heading.getStepX() + toCz * heading.getStepZ() > 0) {
            sx = player.getBlockX() + heading.getStepX();
            sz = player.getBlockZ() + heading.getStepZ();
        }
        int y = player.getBlockY();
        if (!mc.level.getBlockState(cursor.set(sx, y, sz)).canBeReplaced()) {
            obs.stepDone = true;
            cycle.step(obs);
            return;
        }
        Item plug = plugItem(player.getInventory());
        if (plug != null && place(mc, player, plug, sx, y - 1, sz, Direction.UP)) {
            obs.stepDone = true;
            cycle.step(obs);
        }
        status = "Placing blast shield";
    }

    private void trigger(Minecraft mc, LocalPlayer player) {
        boolean tnt = cycle.charge() == DebrisBlastCycle.Charge.TNT;
        Item use = tnt ? (player.getInventory().countItem(Items.FLINT_AND_STEEL) > 0 ? Items.FLINT_AND_STEEL
                : Items.FIRE_CHARGE) : plugItem(player.getInventory());
        if (use == null) {
            return;
        }
        if (place(mc, player, use, cx, cy, cz, Direction.UP)) {
            obs.stepDone = true;
            cycle.step(obs);
        }
        status = tnt ? "Lighting TNT: step back behind the shield" : "Using the bed";
    }

    /** Hands the scanned lava source to Lava Plug (one plug run at a time); the area re-scan keeps the count true. */
    private void plugLava(LocalPlayer player) {
        if (lavaPlug.isBusy()) {
            status = "Plugging lava";
            return;
        }
        if (player.distanceToSqr(lavaX + 0.5, lavaY + 0.5, lavaZ + 0.5) > REACH * REACH) {
            status = "Lava out of reach: move closer";
            return;
        }
        if (lavaPlug.plugAround(lavaX, lavaY, lavaZ, 0)) {
            obs.exposedLava = Math.max(0, obs.exposedLava - 1);
            status = "Plugging lava";
        }
    }

    /** Hands the scanned debris to Vein Work, which mines it and any connected debris with its hazard probes. */
    private void mineDebris(LocalPlayer player) {
        if (veinWork.isBusy()) {
            status = "Mining ancient debris";
            return;
        }
        if (player.distanceToSqr(debrisX + 0.5, debrisY + 0.5, debrisZ + 0.5) > REACH * REACH) {
            status = "Ancient debris at " + debrisX + " " + debrisY + " " + debrisZ;
            return;
        }
        if (veinWork.arm(debrisX, debrisY, debrisZ)) {
            obs.debrisVisible = Math.max(0, obs.debrisVisible - 1);
            status = "Mining ancient debris";
        }
    }

    /** Holds the item and uses it on a face. True once the use was sent. */
    private boolean place(Minecraft mc, LocalPlayer player, Item item, int x, int y, int z, Direction face) {
        if (item == null) {
            return false;
        }
        int held = hands.holdItem(player, item);
        if (held != FieldHands.DONE) {
            return false;
        }
        return hands.tickPlace(mc, player, x, y, z, face, relaxed) == FieldHands.DONE;
    }

    @Override
    protected void stopWork() {
        cycle.stop();
        pocketStep = 0;
        blocksSincePocket = 0;
        lastDugColumn = Long.MIN_VALUE;
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        config.minHealth = (float) Math.max(4.0, Math.min(20.0, p.getDouble(ID, "min_health", 12.0)));
        config.maxBlastDamage = (float) Math.max(1.0, Math.min(10.0, p.getDouble(ID, "max_blast_damage", 4.0)));
        config.pocketEvery = Math.max(3, Math.min(16, p.getInt(ID, "pocket_every", 6)));
        config.preferBed = p.getBool(ID, "prefer_bed", true);
    }

    @Override
    public String displayName() {
        return "Debris Blasting";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.decimal("min_health", "Stop below health", 12.0, 4.0, 20.0, "",
                        "Abort the cycle below this health (half hearts)"),
                SettingSpec.decimal("max_blast_damage", "Max blast damage", 4.0, 1.0, 10.0, "",
                        "Only trigger when the blast would deal less than this at the stand spot (before armour)"),
                SettingSpec.integer("pocket_every", "Pocket every", 6, 3, 16, "",
                        "Tunnel blocks between blast pockets"),
                SettingSpec.bool("prefer_bed", "Prefer beds", true, "",
                        "Use beds (explode in the Nether) before TNT"),
                relaxedSpec());
    }
}
