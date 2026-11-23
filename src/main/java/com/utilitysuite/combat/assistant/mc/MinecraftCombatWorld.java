package com.utilitysuite.combat.assistant.mc;

import com.utilitysuite.combat.assistant.CombatSnapshot;
import com.utilitysuite.combat.assistant.CombatWorld;
import com.utilitysuite.combat.assistant.LineOfSight;
import com.utilitysuite.combat.assistant.SustainExtras;
import com.utilitysuite.core.ServerAllowList;
import com.utilitysuite.core.api.Services;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attribute;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import java.util.function.BiConsumer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import com.utilitysuite.combat.assistant.TacticsPlanner;
import com.utilitysuite.combat.assistant.TargetCandidates;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Ravager;
import net.minecraft.world.entity.monster.Zoglin;
import net.minecraft.world.entity.monster.hoglin.Hoglin;
import net.minecraft.world.entity.monster.piglin.AbstractPiglin;
import net.minecraft.world.entity.monster.warden.Warden;
import net.neoforged.neoforge.common.ItemAbilities;
import net.minecraft.world.entity.NeutralMob;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.monster.Blaze;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.monster.Ghast;
import net.minecraft.world.entity.monster.RangedAttackMob;
import net.minecraft.world.level.entity.EntityTypeTest;

import com.utilitysuite.combat.assistant.FoodObjective;
import com.utilitysuite.core.api.IStorageIndex;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.animal.Pig;
import net.minecraft.world.entity.animal.Rabbit;
import net.minecraft.world.entity.animal.Sheep;
import net.minecraft.world.entity.item.ItemEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.UUID;

/**
 * {@link CombatWorld} over the client level. Client thread only. {@link #sample} allocates nothing: entity lookups by
 * id, cached position/velocity/box fields, one reused {@link BlockPos.MutableBlockPos} for hazard probes and the
 * voxel line-of-sight walk.
 */
public final class MinecraftCombatWorld implements CombatWorld, LineOfSight.SolidTest {

    private static final double PROBE_DISTANCE = 1.0;
    private static final int CLIFF_DEPTH = 3;
    private static final int LOS_MAX_STEPS = 96;

    private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
    private ClientLevel level;

    private static Entity entity(int id) {
        ClientLevel l = Minecraft.getInstance().level;
        return l == null || id < 0 ? null : l.getEntity(id);
    }

    @Override
    public int localPlayerId() {
        LocalPlayer p = Minecraft.getInstance().player;
        return p == null ? -1 : p.getId();
    }

    @Override
    public boolean isLivingAlive(int entityId) {
        return entity(entityId) instanceof LivingEntity le && le.isAlive() && !le.isRemoved();
    }

    @Override
    public boolean isPlayer(int entityId) {
        return entity(entityId) instanceof Player;
    }

    @Override
    public UUID uuidOf(int entityId) {
        Entity e = entity(entityId);
        return e == null ? null : e.getUUID();
    }

    @Override
    public String describe(int entityId) {
        Entity e = entity(entityId);
        return e == null ? "entity #" + entityId : e.getName().getString() + " #" + entityId;
    }

    @Override
    public double distanceSqToLocal(int entityId) {
        LocalPlayer p = Minecraft.getInstance().player;
        Entity e = entity(entityId);
        return p == null || e == null ? Double.MAX_VALUE : p.distanceToSqr(e);
    }

    @Override
    public int totemRefillSlot() {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.screen != null || !p.isAlive()) {
            return -1;
        }
        Inventory inv = p.getInventory();
        if (inv.offhand.get(0).is(Items.TOTEM_OF_UNDYING)) {
            return -1;
        }
        for (int i = 0; i < inv.items.size(); i++) {
            if (inv.items.get(i).is(Items.TOTEM_OF_UNDYING)) {
                return i;
            }
        }
        return -1;
    }

    // allow-list cache: recomputed only when the server entry or the list text changes
    private Object allowServer;
    private String allowList;
    private boolean allowSingle;
    private boolean allowResult;

    @Override
    public boolean extrasAllowedHere() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) {
            return false;
        }
        boolean single = mc.hasSingleplayerServer();
        if (single) {
            return true;
        }
        ServerData server = mc.getCurrentServer();
        String list = Services.modState().profile().getString(ServerAllowList.SOURCE_MODULE_ID,
                ServerAllowList.SETTING_KEY, ServerAllowList.DEFAULT_LIST);
        if (server != allowServer || single != allowSingle || !list.equals(allowList)) {
            allowServer = server;
            allowSingle = single;
            allowList = list;
            allowResult = server != null && ServerAllowList.isAllowedServer(server.ip, list);
        }
        return allowResult;
    }

    @Override
    public int crosshairEntityId() {
        return Minecraft.getInstance().hitResult instanceof EntityHitResult ehr ? ehr.getEntity().getId() : -1;
    }

    @Override
    public boolean sample(int targetId, CombatSnapshot out) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null || targetId < 0) {
            return false;
        }
        if (!(lvl.getEntity(targetId) instanceof LivingEntity t) || !t.isAlive() || t.isRemoved()) {
            return false;
        }
        level = lvl;

        // ---- player
        out.px = p.getX();
        out.py = p.getY();
        out.pz = p.getZ();
        out.eyeY = p.getEyeY();
        out.yaw = p.getYRot();
        out.pitch = p.getXRot();
        out.health = p.getHealth();
        out.maxHealth = p.getMaxHealth();
        out.absorption = p.getAbsorptionAmount();
        out.foodLevel = p.getFoodData().getFoodLevel();
        out.saturation = p.getFoodData().getSaturationLevel();
        out.onGround = p.onGround();
        out.inWater = p.isInWater();
        out.climbing = p.onClimbable();
        out.usingItem = p.isUsingItem();
        out.screenOpen = mc.screen != null;
        out.fallDistance = p.fallDistance;
        out.attackStrength = p.getAttackStrengthScale(0.5f);
        out.reach = p.entityInteractionRange();
        Vec3 pv = p.getDeltaMovement(); // returns the stored vector: no allocation
        out.pvx = pv.x;
        out.pvy = pv.y;
        out.pvz = pv.z;
        out.sprinting = p.isSprinting();
        sampleInventory(p, out);

        // ---- target
        out.tx = t.getX();
        out.ty = t.getY();
        out.tz = t.getZ();
        AABB b = t.getBoundingBox();
        out.minX = b.minX;
        out.minY = b.minY;
        out.minZ = b.minZ;
        out.maxX = b.maxX;
        out.maxY = b.maxY;
        out.maxZ = b.maxZ;
        Vec3 v = t.getDeltaMovement();
        out.tvx = v.x;
        out.tvy = v.y;
        out.tvz = v.z;
        out.targetBlocking = t.isBlocking();
        out.targetYaw = t.yBodyRot;
        out.targetHurtTime = t.hurtTime;
        out.targetAttackSpeed = targetAttackSpeed(t);
        out.targetSwingStarted = swingStarted(t, lvl.getGameTime());
        out.targetHealth = t.getHealth() + t.getAbsorptionAmount();
        out.crosshairOnTarget = mc.hitResult instanceof EntityHitResult ehr && ehr.getEntity() == t;
        out.lineOfSight = LineOfSight.clear(out.px, out.eyeY, out.pz, t.getX(), t.getEyeY(), t.getZ(), this,
                LOS_MAX_STEPS);

        // ---- hazards around the player, relative to the direction toward the target
        double dx = out.tx - out.px;
        double dz = out.tz - out.pz;
        double len = Math.sqrt(dx * dx + dz * dz);
        double fx;
        double fz;
        if (len < 1.0e-3) {
            float yawRad = out.yaw * Mth.DEG_TO_RAD;
            fx = -Mth.sin(yawRad);
            fz = Mth.cos(yawRad);
        } else {
            fx = dx / len;
            fz = dz / len;
        }
        // Minecraft's left (strafe key A) for a forward vector (fx, fz) is (fz, -fx).
        double d = PROBE_DISTANCE;
        out.hazardFront = isHazard(out.px + fx * d, out.py, out.pz + fz * d);
        out.hazardBack = isHazard(out.px - fx * d, out.py, out.pz - fz * d);
        out.hazardLeft = isHazard(out.px + fz * d, out.py, out.pz - fx * d);
        out.hazardRight = isHazard(out.px - fz * d, out.py, out.pz + fx * d);
        out.targetOnGround = t.onGround();
        sampleMovementModel(p, t, out);
        if (out.wantTerrain) {
            sampleTerrain(out, fx, fz, len);
        }
        sampleHitChoice(p, t, lvl, out);
        return true;
    }

    // ---- movement model for the body sims ------------------------------------------------------------------------

    /** {@code LivingEntity.SPEED_MODIFIER_SPRINTING}'s id (private in vanilla): ADD_MULTIPLIED_TOTAL 0.3. */
    private static final ResourceLocation SPRINT_MODIFIER_ID = ResourceLocation.withDefaultNamespace("sprinting");
    private static final double SPRINT_FACTOR = 1.0 + 0.3F; // the modifier amount is the float 0.3F, widened

    /**
     * Seeds the snapshot's movement model when {@code out.wantMovementModel} is set, plain values otherwise (so the
     * body sims behave exactly as before while the setting is off). The target's model is read only for a player:
     * MOVEMENT_SPEED is a synced attribute (Speed and Slowness and the server's sprint modifier arrive with it), Jump
     * Boost is a synced effect and the sprint flag is synced entity data. Allocation free.
     */
    private static void sampleMovementModel(LocalPlayer p, LivingEntity t, CombatSnapshot out) {
        if (!out.wantMovementModel) {
            out.selfSpeed = 0.1;
            out.selfJumpBoost = 0.0;
            out.selfBodySprinting = false;
            out.targetSpeed = 0.1;
            out.targetJumpBoost = 0.0;
            out.targetBodySprinting = false;
            return;
        }
        out.selfSpeed = walkingSpeed(p);
        out.selfJumpBoost = p.getJumpBoostPower();
        out.selfBodySprinting = p.isSprinting();
        if (t instanceof Player) {
            out.targetSpeed = walkingSpeed(t);
            out.targetJumpBoost = t.getJumpBoostPower();
            out.targetBodySprinting = t.isSprinting();
        } else {
            out.targetSpeed = 0.1;
            out.targetJumpBoost = 0.0;
            out.targetBodySprinting = false;
        }
    }

    /** MOVEMENT_SPEED without the sprint modifier (the body sims add the sprint factor from the sprint key). */
    static double walkingSpeed(LivingEntity e) {
        AttributeInstance a = e.getAttribute(Attributes.MOVEMENT_SPEED);
        if (a == null) {
            return 0.1;
        }
        double v = a.getValue();
        return a.hasModifier(SPRINT_MODIFIER_ID) ? v / SPRINT_FACTOR : v;
    }

    // ---- hit choice and gear read --------------------------------------------------------------------------------

    /** The sweep box is re-scanned every this many samples (the proactive scan's cadence), not per tick. */
    static final int SWEEP_SCAN_INTERVAL = 5;
    private static final int SWEEP_SCAN_MAX = 16;
    private int sweepScanClock;
    private int sweepTargetId = -1;
    private int sweepHostiles;
    private int sweepProtected;

    /**
     * Own attributes, the target's synced armour (ARMOR and ARMOR_TOUGHNESS are syncable attributes, sent to
     * trackers for players and mobs), its estimated weapon damage, and the sweep-box census. Attribute reads do not
     * allocate; the census runs every {@value #SWEEP_SCAN_INTERVAL} samples (one small AABB and the level's section
     * walk, like the proactive scan) and is reused in between.
     */
    private void sampleHitChoice(LocalPlayer p, LivingEntity t, ClientLevel lvl, CombatSnapshot out) {
        out.ownAttackSpeed = p.getAttributeValue(Attributes.ATTACK_SPEED);
        out.ownAttackDamage = p.getAttributeValue(Attributes.ATTACK_DAMAGE);
        out.ownArmor = p.getArmorValue();
        out.ownToughness = p.getAttributeValue(Attributes.ARMOR_TOUGHNESS);
        out.sweepWeapon = p.getMainHandItem().canPerformAction(ItemAbilities.SWORD_SWEEP);
        out.targetArmor = t.getArmorValue();
        AttributeInstance tough = t.getAttribute(Attributes.ARMOR_TOUGHNESS);
        out.targetToughness = tough != null ? tough.getValue() : 0.0;
        out.targetAttackDamage = targetAttackDamage(t);
        out.targetUsingItem = t.isUsingItem() && !t.isBlocking();
        out.targetHeavyHitter = t instanceof IronGolem || t instanceof Ravager || t instanceof Hoglin
                || t instanceof Zoglin || t instanceof Warden;
        if (t.getId() != sweepTargetId || ++sweepScanClock >= SWEEP_SCAN_INTERVAL) {
            sweepScanClock = 0;
            sweepTargetId = t.getId();
            censusSweepBox(p, t, lvl);
        }
        out.sweepHostiles = sweepHostiles;
        out.sweepProtected = sweepProtected;
    }

    /**
     * Vanilla's sweep set ({@code Player.attack}): living entities in the target's box grown by 1 / 0.25 / 1, not the
     * player or the target, not a team ally, not a marker armour stand, with feet-to-feet distance below the
     * interaction range. Hostile = an {@code Enemy} that is not neutral (a wolf, an enderman or a zombified piglin
     * would anger) and not a piglin; everything else (players, pets, villagers, golems, passive animals, armour
     * stands) is protected.
     */
    private void censusSweepBox(LocalPlayer p, LivingEntity t, ClientLevel lvl) {
        int hostile = 0;
        int prot = 0;
        double reach = p.entityInteractionRange();
        double reachSq = reach * reach;
        scanBuffer.clear();
        lvl.getEntities(LIVING, t.getBoundingBox().inflate(1.0, 0.25, 1.0), MinecraftCombatWorld::sweepCandidate,
                scanBuffer, SWEEP_SCAN_MAX);
        for (int i = 0; i < scanBuffer.size(); i++) {
            LivingEntity e = scanBuffer.get(i);
            if (e == p || e == t || p.isAlliedTo(e) || p.distanceToSqr(e) >= reachSq) {
                continue;
            }
            if (e instanceof Enemy && !(e instanceof NeutralMob) && !(e instanceof AbstractPiglin)
                    && !(e instanceof Player)) {
                hostile++;
            } else {
                prot++;
            }
        }
        scanBuffer.clear();
        sweepHostiles = hostile;
        sweepProtected = prot;
    }

    private static boolean sweepCandidate(LivingEntity e) {
        return e.isAlive() && !e.isRemoved() && !(e instanceof ArmorStand as && as.isMarker());
    }

    private ItemAttributeModifiers damageCacheKey;
    private double damageCacheValue;
    private double damageScan;
    private final BiConsumer<Holder<Attribute>, AttributeModifier> damageVisitor = (attr, mod) -> {
        if (attr.value() == Attributes.ATTACK_DAMAGE.value()
                && mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
            damageScan += mod.amount();
        }
    };

    /**
     * The target's melee damage per hit before armour: its base attack damage (1 for players; the mob's default) plus
     * the held item's modifiers (attack damage is not a synced attribute, so the item is read), cached by the stack's
     * modifier component. Difficulty scaling and enchantments are left out.
     */
    private double targetAttackDamage(LivingEntity t) {
        AttributeInstance inst = t.getAttribute(Attributes.ATTACK_DAMAGE);
        double base = inst != null ? inst.getBaseValue() : 1.0;
        ItemAttributeModifiers mods = t.getMainHandItem().getAttributeModifiers();
        if (mods != damageCacheKey) {
            damageScan = 0.0;
            mods.forEach(EquipmentSlot.MAINHAND, damageVisitor);
            damageCacheKey = mods;
            damageCacheValue = damageScan;
        }
        return Math.max(0.5, base + damageCacheValue);
    }

    // ---- target arm-swing edge and attack speed (cooldown reading) -----------------------------------------------

    private static final double BARE_HAND_SPEED = 4.0;
    /** A baseline older than this many game ticks is stale: no swing edge is reported from it. */
    private static final long SWING_BASELINE_MAX_AGE = 2;

    private int swingTargetId = -1;
    private long swingSampleTime = Long.MIN_VALUE;
    private boolean prevSwinging;
    private int prevSwingTime;

    /**
     * True exactly on the sample where a new arm swing started. The server's animate packet runs
     * {@code LivingEntity.swing} on the client, which sets {@code swinging} and restarts {@code swingTime} (to -1, then 0
     * in the entity's own tick); a restart is only allowed once half of the swing is over, so a new swing shows as
     * swinging turning on or swingTime going down. Previous values are kept per target id; a target switch or a gap of
     * more than {@value #SWING_BASELINE_MAX_AGE} ticks since the last sample never fakes a swing.
     */
    private boolean swingStarted(LivingEntity t, long gameTime) {
        boolean sw = t.swinging;
        int st = t.swingTime;
        boolean started = swingTargetId == t.getId() && gameTime - swingSampleTime <= SWING_BASELINE_MAX_AGE
                && gameTime != swingSampleTime && sw && (!prevSwinging || st < prevSwingTime);
        if (gameTime != swingSampleTime || swingTargetId != t.getId()) {
            // a second sample inside the same tick keeps the baseline of the first
            prevSwinging = sw;
            prevSwingTime = st;
        }
        swingTargetId = t.getId();
        swingSampleTime = gameTime;
        return started;
    }

    // attack speed: the synced attribute wins; items are the fallback and are walked only when the held stack changes
    private ItemAttributeModifiers speedCacheKey;
    private double speedCacheValue = BARE_HAND_SPEED;
    private double speedScan;
    private final BiConsumer<Holder<Attribute>, AttributeModifier> speedVisitor = (attr, mod) -> {
        if (attr.value() == Attributes.ATTACK_SPEED.value() && mod.operation() == AttributeModifier.Operation.ADD_VALUE) {
            speedScan += mod.amount();
        }
    };

    /**
     * Players always have an attack speed attribute and the server syncs it to trackers
     * (ClientboundUpdateAttributesPacket, the attribute is marked syncable), including the held item's modifiers, so
     * its value is used directly. When it still equals the base value (a server that does not sync item modifiers) or
     * the entity has none (mobs), the speed is derived from the main-hand item's attribute modifiers, cached by the
     * stack's modifier component, else the bare-hand 4.0.
     */
    private double targetAttackSpeed(LivingEntity t) {
        AttributeInstance inst = t.getAttribute(Attributes.ATTACK_SPEED);
        if (inst != null) {
            double v = inst.getValue();
            if (Math.abs(v - inst.getBaseValue()) > 1.0e-9) {
                return v;
            }
            return itemSpeed(t, inst.getBaseValue());
        }
        return itemSpeed(t, BARE_HAND_SPEED);
    }

    private double itemSpeed(LivingEntity t, double base) {
        ItemAttributeModifiers mods = t.getMainHandItem().getAttributeModifiers();
        if (mods != speedCacheKey) {
            speedScan = 0.0;
            mods.forEach(EquipmentSlot.MAINHAND, speedVisitor);
            speedCacheKey = mods;
            speedCacheValue = speedScan;
        }
        return base + speedCacheValue;
    }

    private double meleeDamageScan;
    private double meleeSpeedScan;
    private final BiConsumer<Holder<Attribute>, AttributeModifier> meleeVisitor = (attr, mod) -> {
        if (mod.operation() != AttributeModifier.Operation.ADD_VALUE) {
            return;
        }
        if (attr.value() == Attributes.ATTACK_DAMAGE.value()) {
            meleeDamageScan += mod.amount();
        } else if (attr.value() == Attributes.ATTACK_SPEED.value()) {
            meleeSpeedScan += mod.amount();
        }
    };

    @Override
    public int bestMeleeSlot(int fallback) {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return fallback;
        }
        Inventory inv = p.getInventory();
        int best = fallback;
        double bestScore = BARE_HAND_SPEED; // bare hand: 1 damage at speed 4
        for (int i = 0; i < 9; i++) {
            ItemStack s = inv.getItem(i);
            if (s.isEmpty()) {
                continue;
            }
            meleeDamageScan = 0.0;
            meleeSpeedScan = 0.0;
            s.getAttributeModifiers().forEach(EquipmentSlot.MAINHAND, meleeVisitor);
            if (meleeDamageScan <= 0.0) {
                continue;
            }
            double score = (1.0 + meleeDamageScan) * Math.max(0.1, BARE_HAND_SPEED + meleeSpeedScan);
            if (score > bestScore + 1.0e-6) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static final int DROP_SCAN = 8;
    private static final double GAP_STEP = 0.5;
    private static final double GAP_MAX = 8.0;
    private static final double[] KNOCK_DIST = {1.0, 2.0};

    /** Gap on the line to the target, open blocks under the target, deepest drop around the target. */
    private void sampleTerrain(CombatSnapshot out, double fx, double fz, double len) {
        out.gapNear = -1;
        out.gapFar = -1;
        double limit = Math.min(len, GAP_MAX);
        for (double t = GAP_STEP; t <= limit; t += GAP_STEP) {
            int depth = dropBelow(out.px + fx * t, out.py, out.pz + fz * t);
            if (depth < 0) {
                break; // a wall: not a gap
            }
            if (out.gapNear < 0) {
                if (depth >= TacticsPlanner.GAP_DEPTH) {
                    out.gapNear = t - GAP_STEP * 0.5;
                }
            } else if (depth == 0) {
                out.gapFar = t - GAP_STEP * 0.5;
                break;
            }
        }
        if (out.gapFar < 0) {
            // the target itself may be mid-jump over the gap: count the line up to it as the gap
            out.gapFar = out.gapNear >= 0 && !out.targetOnGround ? len : -1;
            if (out.gapFar < 0) {
                out.gapNear = -1;
            }
        }
        int below = dropBelow(out.tx, out.ty, out.tz);
        out.targetDropBelow = Math.max(0, below);
        out.knockDepth = 0;
        out.knockX = 0;
        out.knockZ = 0;
        for (int i = 0; i < 8; i++) {
            double a = i * (Math.PI / 4);
            double dx = Math.cos(a);
            double dz = Math.sin(a);
            for (double r : KNOCK_DIST) {
                int depth = dropBelow(out.tx + dx * r, out.ty, out.tz + dz * r);
                if (depth < 0) {
                    break; // wall in that direction
                }
                if (depth > out.knockDepth) {
                    out.knockDepth = depth;
                    out.knockX = dx;
                    out.knockZ = dz;
                }
                if (depth > 0) {
                    break; // the nearest drop in this direction is what a hit pushes it into
                }
            }
        }
    }

    /**
     * Open blocks below the feet level at (x, z): 0 = solid floor right under, -1 = a wall at feet level,
     * {@link CombatSnapshot#DEEP} for lava, the void or deeper than {@value #DROP_SCAN}.
     */
    private int dropBelow(double x, double y, double z) {
        int bx = Mth.floor(x);
        int by = Mth.floor(y + 0.01);
        int bz = Mth.floor(z);
        mpos.set(bx, by, bz);
        BlockState feet = level.getBlockState(mpos);
        if (!feet.getCollisionShape(level, mpos).isEmpty()) {
            return -1;
        }
        for (int dy = 1; dy <= DROP_SCAN; dy++) {
            int yy = by - dy;
            if (yy < level.getMinBuildHeight()) {
                return CombatSnapshot.DEEP;
            }
            mpos.set(bx, yy, bz);
            BlockState st = level.getBlockState(mpos);
            if (st.getFluidState().is(FluidTags.LAVA)) {
                return CombatSnapshot.DEEP;
            }
            if (!st.getCollisionShape(level, mpos).isEmpty()) {
                return dy - 1;
            }
        }
        return CombatSnapshot.DEEP;
    }

    private static void sampleInventory(LocalPlayer p, CombatSnapshot out) {
        Inventory inv = p.getInventory();
        out.selectedSlot = inv.selected; // read only (no getter exists); slot changes go through the smoother
        int totem = -1;
        int gapple = -1;
        int axe = -1;
        int mace = -1;
        int wind = -1;
        int gappleKind = SustainExtras.KIND_NONE;
        int invSustain = -1;
        int invSustainKind = SustainExtras.KIND_NONE;
        int empty = -1;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (st.isEmpty()) {
                if (empty < 0 && i < Inventory.getSelectionSize()) {
                    empty = i;
                }
                continue;
            }
            Item it = st.getItem();
            int kind = consumableKind(st, it);
            if (it == Items.TOTEM_OF_UNDYING) {
                if (totem < 0) {
                    totem = i;
                }
            } else if (kind != SustainExtras.KIND_NONE) {
                if (i < Inventory.getSelectionSize()) {
                    if (SustainExtras.better(kind, gappleKind)) {
                        gapple = i;
                        gappleKind = kind;
                    }
                } else if (SustainExtras.better(kind, invSustainKind)) {
                    invSustain = i;
                    invSustainKind = kind;
                }
            } else if (i < Inventory.getSelectionSize()) {
                if (it instanceof AxeItem && axe < 0) {
                    axe = i;
                } else if (it == Items.MACE && mace < 0) {
                    mace = i;
                } else if (it == Items.WIND_CHARGE && wind < 0) {
                    wind = i;
                }
            }
        }
        out.totemInvSlot = totem;
        out.gappleSlot = gapple;
        out.axeSlot = axe;
        out.maceSlot = mace;
        out.windChargeSlot = wind;
        out.offhandTotem = inv.offhand.get(0).is(Items.TOTEM_OF_UNDYING);
        out.gappleOnCooldown = gapple >= 0 && p.getCooldowns().isOnCooldown(inv.items.get(gapple).getItem());
        out.windChargeOnCooldown = p.getCooldowns().isOnCooldown(Items.WIND_CHARGE);
        out.sustainInvSlot = invSustain;
        out.emptyHotbarSlot = empty;
    }

    /** Sustain kind of a stack: golden apples, or a drinkable (not splash) healing/regeneration potion. */
    private static int consumableKind(ItemStack st, Item it) {
        if (it == Items.ENCHANTED_GOLDEN_APPLE) {
            return SustainExtras.KIND_ENCHANTED_GOLDEN_APPLE;
        }
        if (it == Items.GOLDEN_APPLE) {
            return SustainExtras.KIND_GOLDEN_APPLE;
        }
        if (it == Items.POTION) {
            PotionContents pc = st.get(DataComponents.POTION_CONTENTS);
            if (pc != null) {
                if (pc.is(Potions.HEALING) || pc.is(Potions.STRONG_HEALING)) {
                    return SustainExtras.KIND_HEALING_POTION;
                }
                if (pc.is(Potions.REGENERATION) || pc.is(Potions.LONG_REGENERATION)
                        || pc.is(Potions.STRONG_REGENERATION)) {
                    return SustainExtras.KIND_REGEN_POTION;
                }
            }
        }
        return SustainExtras.KIND_NONE;
    }

    /**
     * The same edge/lava probe the assistant's spacing uses, at a horizontal offset from the local player's feet (for
     * the dodge and escape reflexes). False when there is no player. Allocation free.
     */
    public boolean hazardAtOffset(double dx, double dz) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        if (p == null || mc.level == null) {
            return false;
        }
        level = mc.level;
        return isHazard(p.getX() + dx, p.getY(), p.getZ() + dz);
    }

    /** Lava/fire/harmful blocks at or under the probe column, or a drop deeper than {@value #CLIFF_DEPTH} blocks. */
    private boolean isHazard(double x, double y, double z) {
        int bx = Mth.floor(x);
        int by = Mth.floor(y + 0.01);
        int bz = Mth.floor(z);
        for (int dy = 0; dy >= -1; dy--) {
            mpos.set(bx, by + dy, bz);
            BlockState st = level.getBlockState(mpos);
            if (st.getFluidState().is(FluidTags.LAVA) || harmful(st)) {
                return true;
            }
        }
        mpos.set(bx, by, bz);
        if (!level.getBlockState(mpos).getCollisionShape(level, mpos).isEmpty()) {
            return false; // a wall: not walkable, but not a hazard either
        }
        for (int dy = 1; dy <= CLIFF_DEPTH; dy++) {
            mpos.set(bx, by - dy, bz);
            if (!level.getBlockState(mpos).getCollisionShape(level, mpos).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static boolean harmful(BlockState st) {
        return st.is(BlockTags.FIRE) || st.is(BlockTags.CAMPFIRES) || st.is(Blocks.MAGMA_BLOCK)
                || st.is(Blocks.CACTUS) || st.is(Blocks.SWEET_BERRY_BUSH) || st.is(Blocks.WITHER_ROSE)
                || st.is(Blocks.POWDER_SNOW);
    }

    // proactive scan: one reused output list and a static type test; the AABB and the level's section walk allocate a
    // little per call, which is why the assistant scans at most a few times a second and only while armed and dormant
    private final List<LivingEntity> scanBuffer = new ArrayList<>();
    private static final EntityTypeTest<Entity, LivingEntity> LIVING = EntityTypeTest.forClass(LivingEntity.class);

    @Override
    public void scanTargets(double radius, boolean computeLos, TargetCandidates out) {
        out.clear();
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null) {
            return;
        }
        level = lvl;
        scanBuffer.clear();
        lvl.getEntities(LIVING, p.getBoundingBox().inflate(radius), MinecraftCombatWorld::scanCandidate, scanBuffer,
                TargetCandidates.CAPACITY * 2);
        double eyeX = p.getX();
        double eyeY = p.getEyeY();
        double eyeZ = p.getZ();
        double r2 = radius * radius;
        for (int i = 0; i < scanBuffer.size(); i++) {
            LivingEntity e = scanBuffer.get(i);
            if (e == p) {
                continue;
            }
            double d = p.distanceToSqr(e);
            if (d > r2) {
                continue;
            }
            int f = flags(e);
            if (computeLos && (f & (TargetCandidates.HOSTILE | TargetCandidates.PLAYER | TargetCandidates.FOOD)) != 0
                    && LineOfSight.clear(eyeX, eyeY, eyeZ, e.getX(), e.getEyeY(), e.getZ(), this, LOS_MAX_STEPS)) {
                f |= TargetCandidates.LOS;
            }
            if (!out.add(e.getId(), d, e.getHealth() + e.getAbsorptionAmount(), f)) {
                break;
            }
        }
        scanBuffer.clear();
    }

    // ---- food objective (evaluated every few seconds, not per tick) ----------------------------------------------

    private final Map<String, Boolean> safeFoodById = new HashMap<>();
    private final List<ItemEntity> dropBuffer = new ArrayList<>();
    private static final EntityTypeTest<Entity, ItemEntity> ITEMS = EntityTypeTest.forClass(ItemEntity.class);

    static boolean isSafeFood(ItemStack st) {
        return !st.isEmpty() && st.has(DataComponents.FOOD) && !st.is(Items.ROTTEN_FLESH) && !st.is(Items.SPIDER_EYE)
                && !st.is(Items.POISONOUS_POTATO) && !st.is(Items.PUFFERFISH) && !st.is(Items.CHORUS_FRUIT)
                && !st.is(Items.SUSPICIOUS_STEW);
    }

    private boolean isSafeFoodId(String id) {
        return safeFoodById.computeIfAbsent(id, k -> {
            ResourceLocation rl = ResourceLocation.tryParse(k);
            return rl != null && BuiltInRegistries.ITEM.containsKey(rl)
                    && isSafeFood(new ItemStack(BuiltInRegistries.ITEM.get(rl)));
        });
    }

    @Override
    public int inventoryFood() {
        LocalPlayer p = Minecraft.getInstance().player;
        if (p == null) {
            return 0;
        }
        Inventory inv = p.getInventory();
        int n = 0;
        for (int i = 0; i < inv.items.size(); i++) {
            ItemStack st = inv.items.get(i);
            if (isSafeFood(st)) {
                n += st.getCount();
            }
        }
        ItemStack off = inv.offhand.get(0);
        return isSafeFood(off) ? n + off.getCount() : n;
    }

    @Override
    public int storedFoodNearby(double radius) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        IStorageIndex idx = Services.storageOrNull();
        if (p == null || idx == null || mc.level == null) {
            return 0;
        }
        return FoodObjective.storedNearby(idx.entries(), mc.level.dimension().location().toString(), p.getX(),
                p.getY(), p.getZ(), radius, this::isSafeFoodId);
    }

    @Override
    public boolean nearestFoodDrop(double radius, double[] outXyz) {
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer p = mc.player;
        ClientLevel lvl = mc.level;
        if (p == null || lvl == null) {
            return false;
        }
        dropBuffer.clear();
        lvl.getEntities(ITEMS, p.getBoundingBox().inflate(radius), MinecraftCombatWorld::isFoodDrop, dropBuffer, 16);
        ItemEntity best = null;
        double bestD = radius * radius;
        for (int i = 0; i < dropBuffer.size(); i++) {
            ItemEntity it = dropBuffer.get(i);
            double d = p.distanceToSqr(it);
            if (d <= bestD && Math.abs(it.getY() - p.getY()) <= 1.5) {
                best = it;
                bestD = d;
            }
        }
        dropBuffer.clear();
        if (best == null) {
            return false;
        }
        outXyz[0] = best.getX() - p.getX();
        outXyz[1] = best.getY() - p.getY();
        outXyz[2] = best.getZ() - p.getZ();
        return true;
    }

    private static boolean isFoodDrop(ItemEntity e) {
        return e.isAlive() && isSafeFood(e.getItem());
    }

    /** Adult animals that drop food; named or leashed ones are someone's and are left alone. */
    private static boolean isFoodAnimal(LivingEntity e) {
        return (e instanceof Cow || e instanceof Pig || e instanceof Sheep || e instanceof Chicken
                || e instanceof Rabbit) && !e.isBaby() && !e.hasCustomName()
                && !(e instanceof Mob m && m.isLeashed());
    }

    private static boolean scanCandidate(LivingEntity e) {
        return e.isAlive() && !e.isRemoved() && !(e instanceof ArmorStand);
    }

    /** Candidate flags. A neutral mob (enderman, zombified piglin, wolf...) only counts as hostile once it is angry. */
    private static int flags(LivingEntity e) {
        int f = 0;
        if (e instanceof Player pl) {
            if (!pl.isSpectator() && !pl.isCreative()) {
                f |= TargetCandidates.PLAYER;
            }
            return f;
        }
        if (isFoodAnimal(e)) {
            return TargetCandidates.FOOD;
        }
        boolean aggressive = e instanceof Mob m && m.isAggressive()
                || e instanceof EnderMan em && em.isCreepy();
        if (aggressive) {
            f |= TargetCandidates.AGGRESSIVE;
        }
        if (e instanceof Enemy && (!(e instanceof NeutralMob) || aggressive)) {
            f |= TargetCandidates.HOSTILE;
        }
        if (e instanceof Creeper c && c.getSwellDir() > 0) {
            f |= TargetCandidates.SWELLING;
        }
        if (e instanceof RangedAttackMob || e instanceof Ghast || e instanceof Blaze) {
            f |= TargetCandidates.RANGED;
        }
        return f;
    }

    /** {@link LineOfSight.SolidTest}: a block with a collision shape blocks sight (vanilla's COLLIDER clip mode). */
    @Override
    public boolean solid(int x, int y, int z) {
        mpos.set(x, y, z);
        return !level.getBlockState(mpos).getCollisionShape(level, mpos).isEmpty();
    }
}
