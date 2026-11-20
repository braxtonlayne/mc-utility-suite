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

/**
 * Elytra autopilot. Phases: TAKEOFF (jump, deploy, first rocket), CRUISE (yaw to the stop, pitch from
 * {@link ElytraPitchController} toward a terrain-following altitude, rockets via {@link RocketPolicy}), SEARCH (budgeted
 * {@link LandingSearch} while gliding gently), APPROACH (glide slope to the site, flare, land). Cruise is aborted into a
 * landing when {@link FlightReserve} says rockets or elytra durability reached the reserve, or when health/food are low.
 * Also the Nether highway BOUNCE mode (rhythmic jump-gliding along flat tunnels, experimental).
 * <p>
 * Rockets: the fireworks hotbar slot is selected through the smoother (HOTBAR), then USE is tapped (WORLD_INTERACT),
 * only when the crosshair targets nothing (a rocket used on a block would be placed instead of boosting).
 */
final class ElytraDriver {

    enum Result { RUNNING, LANDED, FAILED }

    private enum Phase { TAKEOFF, CRUISE, SEARCH, APPROACH, BOUNCE }

    static final double GLIDE_RATIO = 8.0;
    private static final int BOOST_TICKS = 20;
    private static final int[] LOOKAHEAD = {8, 16, 32, 48};
    private static final double APPROACH_RADIUS = 48.0;

    private final ElytraPitchController controller = new ElytraPitchController();
    private final RocketPolicy rockets = new RocketPolicy();
    private final LandingSearch search = new LandingSearch();
    private final HeldKeys keys = new HeldKeys();
    private final CameraAim aim = new CameraAim(0.75f);
    private final SolidQuery solid = new SolidQuery();

    private Phase phase = Phase.TAKEOFF;
    private int ticksSinceRocket = 1000;
    private int keyCooldown;
    private int pendingRocketSlot = -1;
    private int airborneTicks;
    private int searchAttempts;
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

    void begin(boolean bounce) {
        phase = bounce ? Phase.BOUNCE : Phase.TAKEOFF;
        ticksSinceRocket = 1000;
        keyCooldown = 0;
        pendingRocketSlot = -1;
        airborneTicks = 0;
        searchAttempts = 0;
        failure = "";
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
            fireSelectedRocket(player, input, lease);
        }

        switch (phase) {
            case TAKEOFF -> tickTakeoff(player, input, lease, rocketCount);
            case CRUISE -> {
                FlightReserve.Decision d = FlightReserve.evaluate(rocketCount, reserveRockets, durabilityLeft,
                        minDurability);
                if (d != FlightReserve.Decision.CONTINUE) {
                    TravelMessages.status(d == FlightReserve.Decision.ABORT_LOW_ROCKETS
                            ? "message.utilitysuite.autopilot.reserve_rockets"
                            : "message.utilitysuite.autopilot.reserve_durability");
                    beginSearchAhead(player);
                } else {
                    tickCruise(player, route, input, lease, rocketCount);
                }
            }
            case SEARCH -> tickSearch(player, route, input, lease);
            case APPROACH -> {
                if (player.onGround() && !player.isFallFlying()) {
                    return Result.LANDED;
                }
                tickApproach(player, input, lease);
            }
            default -> {
            }
        }
        if (player.onGround() && phase != Phase.TAKEOFF && !player.isFallFlying()) {
            return Result.LANDED;
        }
        return Result.RUNNING;
    }

    // ---- phases ---------------------------------------------------------------------------------------------------

    private void tickTakeoff(LocalPlayer player, IInputSmoother input, ControlLease lease, int rocketCount) {
        aim.aim(input, lease, player.getYRot(), -25.0, RotationMode.VISIBLE, 1.5f);
        if (player.isFallFlying()) {
            if (rocketCount > 0 && ticksSinceRocket > 40) {
                requestRocket(player, input, lease);
            }
            if (airborneTicks > 10 || rocketCount == 0) {
                phase = Phase.CRUISE;
            }
            return;
        }
        if (keyCooldown > 0) {
            return;
        }
        if (player.onGround()) {
            input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2);
            keyCooldown = 8;
        } else if (airborneTicks >= 3 && player.getDeltaMovement().y < 0.1) {
            input.scheduleKeyTap(lease, SimKey.JUMP, 1, 2); // second press mid-air deploys the elytra
            keyCooldown = 8;
        }
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
        if (pendingRocketSlot < 0 && rockets.shouldFire(player.getY(), targetAlt, vh, boosting, ticksSinceRocket,
                rocketCount, reserveRockets)) {
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
        aim.aim(input, lease, route.bearing(px, pz), 0.0, RotationMode.VISIBLE, 1.5f);
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
        for (int d : LOOKAHEAD) {
            int x = (int) Math.floor(px + dx * d);
            int z = (int) Math.floor(pz + dz * d);
            if (level.hasChunk(x >> 4, z >> 4)) {
                max = Math.max(max, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        }
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
        pendingRocketSlot = -1;
    }

    /** Solid collision for the forward obstacle ray. */
    private static final class SolidQuery implements BlockQuery {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private Level level;

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
            return !s.isAir() && !s.getCollisionShape(level, pos).isEmpty();
        }
    }
}
