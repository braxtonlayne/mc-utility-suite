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
        out.onGround = p.onGround();
        out.inWater = p.isInWater();
        out.climbing = p.onClimbable();
        out.usingItem = p.isUsingItem();
        out.screenOpen = mc.screen != null;
        out.fallDistance = p.fallDistance;
        out.attackStrength = p.getAttackStrengthScale(0.5f);
        out.reach = p.entityInteractionRange();
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
        return true;
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

    /** {@link LineOfSight.SolidTest}: a block with a collision shape blocks sight (vanilla's COLLIDER clip mode). */
    @Override
    public boolean solid(int x, int y, int z) {
        mpos.set(x, y, z);
        return !level.getBlockState(mpos).getCollisionShape(level, mpos).isEmpty();
    }
}
