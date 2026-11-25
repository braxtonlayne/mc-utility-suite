package com.utilitysuite.travel.autopilot;

import com.utilitysuite.core.api.ControlLease;
import com.utilitysuite.core.api.IInputSmoother;
import com.utilitysuite.core.api.RotationMode;
import com.utilitysuite.core.api.SimKey;
import com.utilitysuite.travel.Angles;
import com.utilitysuite.travel.BlockQuery;
import com.utilitysuite.travel.CameraAim;
import com.utilitysuite.travel.GridRay;
import com.utilitysuite.travel.HeldKeys;
import com.utilitysuite.travel.TravelMessages;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ElytraItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Elytra autopilot. Phases: TAKEOFF ({@link TakeoffSequence}: clear-air column check, jump, deploy on the second press,
 * pitch up, first rocket; aborts with a status message), CRUISE (with the {@link BackupElytraSwap} mid-flight spare
 * swap before the durability reserve), MEND (optional {@link MendLoop}: after a reserve landing, bottles o' enchanting
 * at the feet, then take off again), CRUISE (yaw to the stop, pitch from
 * {@link ElytraPitchController} toward a terrain-following altitude, rockets via {@link RocketPolicy}), SEARCH (budgeted
 * {@link LandingSearch} while gliding gently), APPROACH (glide slope to the site, flare, land). Cruise is aborted into a
 * landing when {@link FlightReserve} says rockets or elytra durability reached the reserve, or when health/food are low.
 * While a lookahead column is not loaded yet, cruise keeps the {@link UnloadedTerrainHold} margin (higher target, rockets
 * held back). Also the Nether highway BOUNCE mode (rhythmic jump-gliding along flat tunnels, experimental), which stops
 * when {@link BounceGuard} finds the way ahead blocked.
 * <p>
 * Rockets: the fireworks hotbar slot is selected through the smoother (HOTBAR), then USE is tapped (WORLD_INTERACT),
 * only when the crosshair targets nothing (a rocket used on a block would be placed instead of boosting).
 */
final class ElytraDriver {

    enum Result { RUNNING, LANDED, FAILED }

    private enum Phase {
        TAKEOFF("ELYTRA_TAKEOFF"), CRUISE("ELYTRA_CRUISE"), SEARCH("ELYTRA_SEARCH"), APPROACH("ELYTRA_APPROACH"),
        MEND("ELYTRA_MEND"), BOUNCE("BOUNCE");

        /** Constant status label (diagnostics HUD). */
        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    /** Constant label of the current phase (diagnostics HUD); allocation free. */
    String phaseLabel() {
        return phase.label;
    }

    static final double GLIDE_RATIO = 8.0;
    private static final int BOOST_TICKS = 20;
    private static final int[] LOOKAHEAD = {8, 16, 32, 48};
    private static final double APPROACH_RADIUS = 48.0;
    /** Collision up to this height is walked over without a jump (vanilla step height), so it is no obstacle. */
    private static final double STEP_HEIGHT = 0.6;

    private final ElytraPitchController controller = new ElytraPitchController();
    private final RocketPolicy rockets = new RocketPolicy();
    private final LandingSearch search = new LandingSearch();
    private final HeldKeys keys = new HeldKeys();
    private final CameraAim aim = new CameraAim(0.75f);
    private final SolidQuery solid = new SolidQuery();
    private final SolidQuery tall = new SolidQuery(STEP_HEIGHT);
    private final UnloadedTerrainHold hold = new UnloadedTerrainHold();
    private final BounceGuard bounceGuard = new BounceGuard();
    private final FlightRestockHands restock = new FlightRestockHands();
    private final TakeoffSequence takeoff = new TakeoffSequence();
    private final BackupElytraSwap backup = new BackupElytraSwap();
    private final MendLoop mend = new MendLoop();
    /** Takeoff climb pitch (degrees, up is negative). */
    private static final double TAKEOFF_PITCH = -25.0;
    private boolean landToMend;

    private Phase phase = Phase.TAKEOFF;
    private int ticksSinceRocket = 1000;
    private int keyCooldown;
    private int pendingRocketSlot = -1;
    private int airborneTicks;
    private int searchAttempts;
    private boolean unloadedAhead;
    private String failure = "";

    // settings
    private int clearance = 24;
    private int minAltitude;
    private int reserveRockets = 3;
    private int minDurability = 20;
    private int arrivalRadius = 3;

    void configure(int clearance, int minAltitude, int reserveRockets, int minDurability, int rocketCooldown,
                   int arrivalRadius) {
        this.clearance = clearance;
        this.minAltitude = minAltitude;
        this.reserveRockets = reserveRockets;
        this.minDurability = minDurability;
        this.arrivalRadius = arrivalRadius;
        rockets.configure(0.9, 6.0, rocketCooldown);
    }

    /** "Land to mend" setting: at the durability reserve, land and mend with bottles o' enchanting. */
    void setLandToMend(boolean on) {
        this.landToMend = on;
    }

    void begin(boolean bounce) {
        phase = bounce ? Phase.BOUNCE : Phase.TAKEOFF;
        ticksSinceRocket = 1000;
        keyCooldown = 0;
        pendingRocketSlot = -1;
        airborneTicks = 0;
        searchAttempts = 0;
        failure = "";
        unloadedAhead = false;
        hold.reset();
        bounceGuard.reset();
        restock.reset();
        takeoff.reset();
        backup.reset();
        mend.reset();
        search.cancel();
        keys.reset();
        aim.reset();
    }

    String failure() {
        return failure;
    }

    String phaseName() {
        return phase.name();
    }

    /** Low health/food from the module: land now instead of stopping in mid-air. */
    void requestLanding(LocalPlayer player) {
        if (phase == Phase.TAKEOFF || phase == Phase.CRUISE) {
            beginSearchAhead(player);
        }
    }

    Result tick(LocalPlayer player, RouteState route, IInputSmoother input, ControlLease lease) {
        ticksSinceRocket++;
        if (keyCooldown > 0) {
            keyCooldown--;
        }
        airborneTicks = player.onGround() ? 0 : airborneTicks + 1;
        ItemStack chest = player.getItemBySlot(EquipmentSlot.CHEST);
        if (!(chest.getItem() instanceof ElytraItem) || !ElytraItem.isFlyEnabled(chest)) {
            if (player.onGround()) {
                failure = "message.utilitysuite.autopilot.no_elytra";
                return Result.FAILED;
            }
        }
        if (phase == Phase.BOUNCE) {
            return tickBounce(player, route, input, lease);
        }
        int durabilityLeft = chest.getMaxDamage() - chest.getDamageValue() - 1;
        int rocketCount = countRockets(player.getInventory());

        if (pendingRocketSlot >= 0) {
            if (phase == Phase.TAKEOFF || phase == Phase.CRUISE) {
                fireSelectedRocket(player, input, lease);
            } else {
                pendingRocketSlot = -1; // a rocket held back for the crosshair is not owed to a landing search
            }
        }

        switch (phase) {
            case TAKEOFF -> {
                if (tickTakeoff(player, input, lease, rocketCount)) {
                    keys.releaseAll(input, lease);
                    return Result.FAILED;
                }
            }
            case CRUISE -> {
                tickBackupSwap(player, lease, durabilityLeft);
                FlightReserve.Decision d = restock.adjust(player, lease, FlightReserve.evaluate(rocketCount,
                        reserveRockets, durabilityLeft, minDurability));
                if (d == FlightReserve.Decision.ABORT_LOW_DURABILITY && backup.swapping()) {
                    d = FlightReserve.Decision.CONTINUE; // the spare is going on: no landing for this one
                }
                if (d != FlightReserve.Decision.CONTINUE) {
                    if (d == FlightReserve.Decision.ABORT_LOW_DURABILITY && MendLoop.shouldLandToMend(landToMend,
                            restock.hasMending(player, chest), countHotbar(player.getInventory(),
                                    Items.EXPERIENCE_BOTTLE))) {
                        mend.begin();
                        TravelMessages.status("message.utilitysuite.autopilot.mend_landing");
                    } else {
                        TravelMessages.status(d == FlightReserve.Decision.ABORT_LOW_ROCKETS
                                ? "message.utilitysuite.autopilot.reserve_rockets"
                                : restock.hasSpareElytra(player, durabilityLeft)
                                ? "message.utilitysuite.autopilot.reserve_durability_spare"
                                : "message.utilitysuite.autopilot.reserve_durability");
                    }
                    beginSearchAhead(player);
                } else {
                    tickCruise(player, route, input, lease, rocketCount);
                }
            }
            case SEARCH -> tickSearch(player, route, input, lease);
            case APPROACH -> {
                if (player.onGround() && !player.isFallFlying()) {
                    return landedOrMend();
                }
                tickApproach(player, input, lease);
            }
            case MEND -> {
                return tickMend(player, input, lease, chest, durabilityLeft);
            }
            default -> {
            }
        }
        if (player.onGround() && phase != Phase.TAKEOFF && !player.isFallFlying()) {
            return landedOrMend();
        }
        return Result.RUNNING;
    }

    /** On the ground after a flight: the run is over, unless a land-to-mend stop is pending. */
    private Result landedOrMend() {
        if (mend.active()) {
            phase = Phase.MEND;
            return Result.RUNNING;
        }
        return Result.LANDED;
    }

    /** Cruise tick of the backup elytra swap (scans the inventory only when the worn elytra nears the reserve). */
    private void tickBackupSwap(LocalPlayer player, ControlLease lease, int durabilityLeft) {
        if (!backup.swapping() && (backup.gaveUp() || !BackupElytraSwap.due(durabilityLeft, minDurability))) {
            return; // cheap early-out
        }
        int spare = backup.swapping() ? -1 : restock.pickBackupElytra(player, durabilityLeft);
        if (backup.step(durabilityLeft, minDurability, spare, FlightRestockHands.safelyHigh(player), restock.busy())
                == BackupElytraSwap.Action.SWAP) {
            if (restock.queueElytraSwap(lease, spare)) {
                TravelMessages.status("message.utilitysuite.autopilot.elytra_swapped");
            } else {
                backup.swapRejected();
            }
        }
    }

    private Result tickMend(LocalPlayer player, IInputSmoother input, ControlLease lease, ItemStack chest,
                            int durabilityLeft) {
        Inventory inv = player.getInventory();
        int bottleSlot = hotbarSlotOf(inv, Items.EXPERIENCE_BOTTLE);
        MendLoop.Action a = mend.step(player.onGround() && !player.isFallFlying(), player.getXRot(), durabilityLeft,
                chest.getMaxDamage(), minDurability, countHotbar(inv, Items.EXPERIENCE_BOTTLE),
                inv.getItem(inv.selected).is(Items.EXPERIENCE_BOTTLE));
        switch (a) {
            case AIM_DOWN -> aim.aim(input, lease, player.getYRot(), MendLoop.THROW_PITCH, RotationMode.VISIBLE, 1.0f);
            case SELECT_BOTTLE -> {
                aim.aim(input, lease, player.getYRot(), MendLoop.THROW_PITCH, RotationMode.VISIBLE, 1.0f);
                if (bottleSlot >= 0) {
                    input.selectHotbarSlot(lease, bottleSlot);
                }
            }
            case THROW -> {
                aim.aim(input, lease, player.getYRot(), MendLoop.THROW_PITCH, RotationMode.VISIBLE, 1.0f);
                input.scheduleKeyTap(lease, SimKey.USE, 1, 2);
            }
            case TAKE_OFF -> {
                mend.reset();
                takeoff.reset();
                ticksSinceRocket = 1000;
                phase = Phase.TAKEOFF;
                TravelMessages.status("message.utilitysuite.autopilot.mend_done");
            }
            case GIVE_UP -> {
                mend.reset();
                TravelMessages.status("message.utilitysuite.autopilot.mend_failed");
                return Result.LANDED;
            }
            default -> {
            }
        }
        return Result.RUNNING;
    }

    // ---- phases ---------------------------------------------------------------------------------------------------

    /**
     * Hands-free takeoff ({@link TakeoffSequence}): column check, jump, open the elytra with the second press, pitch
     * up (smoothed by the core), one rocket, cruise. Returns true when the takeoff was aborted ({@link #failure}).
     */
    private boolean tickTakeoff(LocalPlayer player, IInputSmoother input, ControlLease lease, int rocketCount) {
        aim.aim(input, lease, player.getYRot(), TAKEOFF_PITCH, RotationMode.VISIBLE, 1.5f);
        boolean inWater = player.isInWater();
        boolean clear = takeoff.phase() == TakeoffSequence.Phase.CHECK && (player.onGround() || inWater)
                && TakeoffSequence.clearForTakeoff(solid.bind(player.level()), player.getBlockX(), player.getBlockY(),
                player.getBlockZ());
        TakeoffSequence.Action a = takeoff.step(clear, player.onGround(), inWater, airborneTicks,
                player.getDeltaMovement().y, player.isFallFlying(), Math.abs(player.getXRot() - TAKEOFF_PITCH),
                rocketCount, ticksSinceRocket <= TakeoffSequence.BOOST_TIMEOUT);
        keys.set(input, lease, SimKey.JUMP, a == TakeoffSequence.Action.HOLD_JUMP);
        switch (a) {
            case JUMP, DEPLOY_JUMP -> input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
            case FIRE -> requestRocket(player, input, lease);
            case CRUISE -> phase = Phase.CRUISE;
            case ABORT_BLOCKED -> {
                failure = "message.utilitysuite.autopilot.takeoff_blocked";
                return true;
            }
            case ABORT_NO_ROCKETS -> {
                failure = "message.utilitysuite.autopilot.takeoff_no_rockets";
                return true;
            }
            case ABORT_NO_DEPLOY -> {
                failure = "message.utilitysuite.autopilot.takeoff_no_deploy";
                return true;
            }
            default -> {
            }
        }
        return false;
    }

    private void tickCruise(LocalPlayer player, RouteState route, IInputSmoother input, ControlLease lease,
                            int rocketCount) {
        double px = player.getX();
        double pz = player.getZ();
        double dist = route.horizontalDistance(px, pz);
        if (!route.isFinalStop() && dist < 16.0) {
            route.advance();
            dist = route.horizontalDistance(px, pz);
        }
        double bearing = route.bearing(px, pz);
        if (route.isFinalStop() && dist < APPROACH_RADIUS) {
            beginSearchAround(route, bearing);
            return;
        }
        Level level = player.level();
        double targetAlt = Math.max(minAltitude, groundAhead(level, px, pz, bearing) + clearance);
        hold.update(unloadedAhead, player.getY());
        targetAlt = hold.targetAltitude(targetAlt);
        if (hold.announce()) {
            TravelMessages.status("message.utilitysuite.autopilot.unloaded_ahead");
        }
        double dx = Angles.dirX(bearing);
        double dz = Angles.dirZ(bearing);
        double blocked = GridRay.march(px, player.getY() + 0.5, pz, dx, 0.0, dz, 48.0, solid.bind(level));
        if (blocked < 32.0) {
            targetAlt = Math.max(targetAlt, player.getY() + 20.0);
        }
        Vec3 v = player.getDeltaMovement();
        double vh = Math.sqrt(v.x * v.x + v.z * v.z);
        boolean boosting = ticksSinceRocket < BOOST_TICKS;
        double pitch = controller.pitch(player.getY(), targetAlt, v.y, vh, boosting);
        aim.aim(input, lease, bearing, pitch, RotationMode.VISIBLE, 1.5f);
        // During a hold the rocket target is the current altitude: rockets keep speed, they do not chase height.
        double rocketAlt = hold.active() ? player.getY() : targetAlt;
        if (pendingRocketSlot < 0 && hold.rocketAllowed(vh) && rockets.shouldFire(player.getY(), rocketAlt, vh,
                boosting, ticksSinceRocket, rocketCount, reserveRockets)) {
            requestRocket(player, input, lease);
        }
    }

    private void tickSearch(LocalPlayer player, RouteState route, IInputSmoother input, ControlLease lease) {
        Vec3 v = player.getDeltaMovement();
        double heading = v.horizontalDistanceSqr() > 1e-4 ? Angles.yawTo(v.x, v.z) : player.getYRot();
        aim.aim(input, lease, heading, 8.0, RotationMode.VISIBLE, 1.0f); // gentle glide while searching
        search.tick(player.level(), player.getX(), player.getY(), player.getZ(), GLIDE_RATIO);
        if (!search.done()) {
            return;
        }
        if (search.found()) {
            phase = Phase.APPROACH;
            TravelMessages.status("message.utilitysuite.autopilot.landing_site", search.x(), search.y(), search.z());
            return;
        }
        searchAttempts++;
        beginSearchAhead(player); // keep gliding forward and look again
    }

    private void tickApproach(LocalPlayer player, IInputSmoother input, ControlLease lease) {
        double dx = search.x() + 0.5 - player.getX();
        double dz = search.z() + 0.5 - player.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        double above = player.getY() - (search.y() + 1);
        double yaw = horizontal > 0.5 ? Angles.yawTo(dx, dz) : player.getYRot();
        double pitch;
        if (above < 3.0 && horizontal < 6.0) {
            pitch = -8.0; // flare: bleed speed just above the ground
        } else {
            pitch = Math.max(4.0, Math.min(30.0, Angles.pitchTo(horizontal, -above)));
        }
        if (player.getDeltaMovement().y < -0.55) {
            pitch = Math.min(pitch, 0.0); // keep the sink rate below fall-damage speed
        }
        aim.aim(input, lease, yaw, pitch, RotationMode.VISIBLE, 1.5f);
        if (horizontal > 24.0 && above < 4.0) {
            beginSearchAhead(player); // overshot or fell short: pick a new site
        }
    }

    private Result tickBounce(LocalPlayer player, RouteState route, IInputSmoother input, ControlLease lease) {
        double px = player.getX();
        double pz = player.getZ();
        double dist = route.horizontalDistance(px, pz);
        if (!route.isFinalStop() && dist < 8.0) {
            route.advance();
        }
        if (route.isFinalStop() && dist <= arrivalRadius * 3) {
            keys.releaseAll(input, lease);
            return Result.LANDED;
        }
        if (player.isInLava()) {
            failure = "message.utilitysuite.autopilot.lava";
            return Result.FAILED;
        }
        double bearing = route.bearing(px, pz);
        // Obstacle rays follow the actual motion (a turn or a slide along a wall), the bearing only from a standstill.
        Vec3 v = player.getDeltaMovement();
        double vhSqr = v.x * v.x + v.z * v.z;
        boolean moving = vhSqr > 0.01;
        Level level = player.level();
        bounceGuard.observe(player.getY(), player.onGround());
        if (bounceGuard.check(px, player.getY(), pz, moving ? v.x : Angles.dirX(bearing),
                moving ? v.z : Angles.dirZ(bearing), Math.sqrt(vhSqr), tall.bind(level), solid.bind(level))
                == BounceGuard.Verdict.BLOCKED) {
            keys.releaseAll(input, lease);
            failure = "message.utilitysuite.autopilot.bounce_blocked";
            return Result.FAILED;
        }
        aim.aim(input, lease, bearing, 0.0, RotationMode.VISIBLE, 1.5f);
        keys.set(input, lease, SimKey.FORWARD, true);
        keys.set(input, lease, SimKey.SPRINT, true);
        if (keyCooldown == 0) {
            if (player.onGround()) {
                input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
                keyCooldown = 4;
            } else if (!player.isFallFlying() && airborneTicks >= 2) {
                input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
                keyCooldown = 4;
            }
        }
        return Result.RUNNING;
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private void beginSearchAhead(LocalPlayer player) {
        Vec3 v = player.getDeltaMovement();
        double fx = v.x;
        double fz = v.z;
        if (fx * fx + fz * fz < 1e-4) {
            fx = Angles.dirX(player.getYRot());
            fz = Angles.dirZ(player.getYRot());
        }
        int reach = (int) Math.min(96, Math.max(16, (player.getY() - player.level().getMinBuildHeight()) * 2));
        search.start(player.getX(), player.getZ(), fx, fz, 2, Math.max(3, reach / 8), 3, 8);
        phase = Phase.SEARCH;
        if (searchAttempts == 0) {
            TravelMessages.status("message.utilitysuite.autopilot.searching_site");
        }
    }

    private void beginSearchAround(RouteState route, double bearing) {
        search.start(route.x() + 0.5, route.z() + 0.5, Angles.dirX(bearing), Angles.dirZ(bearing), -6, 6, 6, 4);
        phase = Phase.SEARCH;
        TravelMessages.status("message.utilitysuite.autopilot.searching_site");
    }

    private double groundAhead(Level level, double px, double pz, double bearing) {
        double dx = Angles.dirX(bearing);
        double dz = Angles.dirZ(bearing);
        int max = level.getHeight(Heightmap.Types.MOTION_BLOCKING, (int) Math.floor(px), (int) Math.floor(pz));
        boolean unloaded = false;
        for (int d : LOOKAHEAD) {
            int x = (int) Math.floor(px + dx * d);
            int z = (int) Math.floor(pz + dz * d);
            if (level.hasChunk(x >> 4, z >> 4)) {
                max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            } else {
                unloaded = true; // no height to follow there: the caller keeps the UnloadedTerrainHold margin
            }
        }
        unloadedAhead = unloaded;
        return max;
    }

    private void requestRocket(LocalPlayer player, IInputSmoother input, ControlLease lease) {
        int slot = rocketSlot(player.getInventory());
        if (slot < 0) {
            return;
        }
        if (input.selectHotbarSlot(lease, slot)) {
            pendingRocketSlot = slot;
        }
    }

    private void fireSelectedRocket(LocalPlayer player, IInputSmoother input, ControlLease lease) {
        Inventory inv = player.getInventory();
        if (inv.selected != pendingRocketSlot || !inv.getItem(pendingRocketSlot).is(Items.FIREWORK_ROCKET)) {
            return; // selection lands on the next tick
        }
        HitResult hit = Minecraft.getInstance().hitResult;
        if (hit != null && hit.getType() != HitResult.Type.MISS) {
            return; // would place the rocket on a block
        }
        if (input.scheduleKeyTap(lease, SimKey.USE, 1, 2)) {
            ticksSinceRocket = 0;
            pendingRocketSlot = -1;
        }
    }

    static int countRockets(Inventory inv) {
        int n = 0;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(Items.FIREWORK_ROCKET)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int countHotbar(Inventory inv, net.minecraft.world.item.Item item) {
        int n = 0;
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (s.is(item)) {
                n += s.getCount();
            }
        }
        return n;
    }

    private static int hotbarSlotOf(Inventory inv, net.minecraft.world.item.Item item) {
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).is(item)) {
                return i;
            }
        }
        return -1;
    }

    private static int rocketSlot(Inventory inv) {
        if (inv.getItem(inv.selected).is(Items.FIREWORK_ROCKET)) {
            return inv.selected;
        }
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (inv.getItem(i).is(Items.FIREWORK_ROCKET)) {
                return i;
            }
        }
        return -1;
    }

    void release(IInputSmoother input, ControlLease lease) {
        keys.releaseAll(input, lease);
        aim.reset();
        search.cancel();
        restock.cancel(lease);
        pendingRocketSlot = -1;
    }

    /**
     * Solid collision for the forward obstacle rays: any collision, or only collision taller than {@code minHeight}
     * (the bounce's foot row, where carpet and slabs are stepped over). Unloaded columns do not block.
     */
    private static final class SolidQuery implements BlockQuery {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final double minHeight;
        private Level level;

        SolidQuery() {
            this(0.0);
        }

        SolidQuery(double minHeight) {
            this.minHeight = minHeight;
        }

        SolidQuery bind(Level level) {
            this.level = level;
            return this;
        }

        @Override
        public boolean blocks(int x, int y, int z) {
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return false;
            }
            BlockState s = level.getBlockState(pos.set(x, y, z));
            if (s.isAir()) {
                return false;
            }
            VoxelShape shape = s.getCollisionShape(level, pos);
            return !shape.isEmpty() && (minHeight <= 0.0 || shape.max(Direction.Axis.Y) > minHeight);
        }
    }
}
