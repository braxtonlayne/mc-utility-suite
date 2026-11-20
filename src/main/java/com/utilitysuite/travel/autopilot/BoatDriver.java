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
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.Boat;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Boat routing. Steers the boat with LEFT/RIGHT (a boat turns by paddling, not by the camera), paddles with FORWARD
 * through a {@link DutyCycle} driven by {@link BoatThrottle}, keeps the camera looking along the chosen heading, and
 * picks the heading from a fan of open-water rays ({@link BoatSteering}) so the boat follows the river's open middle.
 * Ray sampling is budgeted: {@value #RAYS_PER_TICK} of the {@link BoatSteering#OFFSETS} rays per tick, each an
 * allocation-free {@link GridRay} march. At the final stop it stops paddling, waits until the boat has nearly
 * stopped and dismounts with a SNEAK tap.
 */
final class BoatDriver {

    enum Result { RUNNING, ARRIVED, LEFT_BOAT }

    static final int RAYS_PER_TICK = 3;
    private static final double TURN_DEADBAND_DEG = 4.0;
    private static final double DISMOUNT_SPEED = 0.03;
    private static final float CAMERA_PITCH = 12f;

    private final double[] clearances = new double[BoatSteering.OFFSETS.length];
    private final WaterQuery water = new WaterQuery();
    private final DutyCycle duty = new DutyCycle();
    private final HeldKeys keys = new HeldKeys();
    private final CameraAim aim = new CameraAim(2f);
    private int rayCursor;
    private boolean dismounting;
    private int dismountWait;
    private double rayRange = 24.0;
    private int arrivalRadius = 3;

    void configure(double rayRange, int arrivalRadius) {
        this.rayRange = rayRange;
        this.arrivalRadius = arrivalRadius;
    }

    void begin() {
        java.util.Arrays.fill(clearances, rayRange);
        duty.reset();
        keys.reset();
        aim.reset();
        rayCursor = 0;
        dismounting = false;
        dismountWait = 0;
    }

    Result tick(LocalPlayer player, RouteState route, IInputSmoother input, ControlLease lease) {
        Entity vehicle = player.getVehicle();
        if (!(vehicle instanceof Boat boat)) {
            return dismounting ? Result.ARRIVED : Result.LEFT_BOAT;
        }
        double bx = boat.getX();
        double bz = boat.getZ();
        Vec3 v = boat.getDeltaMovement();
        double speed = Math.sqrt(v.x * v.x + v.z * v.z);
        double dist = route.horizontalDistance(bx, bz);

        if (route.isFinalStop() && dist <= arrivalRadius) {
            dismounting = true;
        } else if (!route.isFinalStop() && dist <= arrivalRadius + 2) {
            route.advance();
            java.util.Arrays.fill(clearances, rayRange);
            dist = route.horizontalDistance(bx, bz);
        }
        if (dismounting) {
            keys.set(input, lease, SimKey.FORWARD, false);
            keys.set(input, lease, SimKey.LEFT, false);
            keys.set(input, lease, SimKey.RIGHT, false);
            if (speed < DISMOUNT_SPEED && ++dismountWait >= 5) {
                input.scheduleKeyTap(lease, SimKey.SNEAK, 2, 4);
                dismountWait = -20; // give the dismount time before tapping again
            }
            return Result.RUNNING;
        }

        double bearing = route.bearing(bx, bz);
        sampleRays(boat.level(), bx, boat.getY(), bz, bearing);
        double heading = BoatSteering.chooseHeading(bearing, clearances, rayRange, dist);
        double err = Angles.delta(boat.getYRot(), heading);
        // A boat's yaw decreases with LEFT (deltaRotation--) and increases with RIGHT.
        keys.set(input, lease, SimKey.LEFT, err < -TURN_DEADBAND_DEG);
        keys.set(input, lease, SimKey.RIGHT, err > TURN_DEADBAND_DEG);
        double throttle = BoatThrottle.throttle(err, route.isFinalStop() ? Double.POSITIVE_INFINITY : dist,
                route.cornerTurn(bx, bz), route.remainingDistance(bx, bz), speed);
        keys.set(input, lease, SimKey.FORWARD, duty.next(throttle));
        aim.aim(input, lease, heading, CAMERA_PITCH, RotationMode.VISIBLE, 0.6f);
        return Result.RUNNING;
    }

    private void sampleRays(Level level, double x, double y, double z, double bearing) {
        water.bind(level, (int) Math.floor(y - 0.4));
        double rayY = Math.floor(y - 0.4) + 0.5;
        for (int n = 0; n < RAYS_PER_TICK; n++) {
            int i = rayCursor;
            rayCursor = (rayCursor + 1) % clearances.length;
            double yaw = bearing + BoatSteering.OFFSETS[i];
            clearances[i] = GridRay.march(x, rayY, z, Angles.dirX(yaw), 0.0, Angles.dirZ(yaw), rayRange, water);
        }
    }

    void release(IInputSmoother input, ControlLease lease) {
        keys.releaseAll(input, lease);
        aim.reset();
    }

    /** "Not open water": the ray stops where the water ends or something solid sits on it. */
    private static final class WaterQuery implements BlockQuery {
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private Level level;
        private int waterY;

        void bind(Level level, int waterY) {
            this.level = level;
            this.waterY = waterY;
        }

        @Override
        public boolean blocks(int x, int y, int z) {
            if (!level.hasChunk(x >> 4, z >> 4)) {
                return true;
            }
            if (!level.getFluidState(pos.set(x, waterY, z)).is(FluidTags.WATER)) {
                return true;
            }
            BlockState above = level.getBlockState(pos.set(x, waterY + 1, z));
            return !above.isAir() && !above.getCollisionShape(level, pos).isEmpty();
        }
    }
}
