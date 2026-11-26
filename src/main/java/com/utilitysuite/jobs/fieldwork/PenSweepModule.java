package com.utilitysuite.jobs.fieldwork;

import com.utilitysuite.core.api.ControlPriority;
import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.core.api.SimKey;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.animal.sheep.Sheep;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;

import java.util.List;

/**
 * Animal pen sweep (off by default): around the spot where it was switched on (a square pen of the configured
 * radius), shears unsheared sheep, milks cows/mooshrooms/goats into empty buckets, and feeds breed-ready adults the
 * right food when a partner is ready ({@link PenRules}). It stands still and works within reach (look where you move:
 * it never turns while walking). Every action is a smoothed look plus one USE tap through the core
 * ({@code FieldHands.tickEntity}). The pen is re-scanned once a second into reused arrays.
 */
public final class PenSweepModule extends FieldModule {

    public static final String ID = "pen_sweep";
    private static final int CAP = 64;
    private static final int SCAN_TICKS = 20;
    private static final double REACH = 3.0;

    private final String[] types = new String[CAP];
    private final boolean[] adult = new boolean[CAP];
    private final boolean[] ready = new boolean[CAP];
    private final boolean[] love = new boolean[CAP];
    private final boolean[] sheared = new boolean[CAP];
    private final boolean[] inPen = new boolean[CAP];
    private final double[] distSq = new double[CAP];
    private final Animal[] animals = new Animal[CAP];
    private final PenRules.Means means = new PenRules.Means();
    /** Tick this sweep fed each animal (entity id), for the breeding cooldown the client cannot see. */
    private final it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap fedAt = new it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap();
    private static final int MAX_FED = 256;
    private int count;

    private int radius = 8;
    private int penX;
    private int penZ;
    private boolean penSet;
    private long tick;
    private Animal target;
    private PenRules.Action action = PenRules.Action.NONE;
    private Inventory scanInventory;

    public PenSweepModule() {
        super(ID, ControlPriority.GOAL);
        means.food = this::carriesFood;
        fedAt.defaultReturnValue(Long.MIN_VALUE);
    }

    @Override
    public void onTick() {
        if (!enabled) {
            return;
        }
        tick++;
        Minecraft mc = Minecraft.getInstance();
        LocalPlayer player = mc.player;
        if (player == null || mc.level == null || mc.gui.screen() != null) {
            return;
        }
        if (!penSet) {
            penX = player.getBlockX();
            penZ = player.getBlockZ();
            penSet = true;
        }
        if (hands.consumePreempted()) {
            stopWork();
            return;
        }
        if (target != null) {
            tickWork(player);
            return;
        }
        if (tick % SCAN_TICKS != 0 || hands.coolingDown()) {
            return;
        }
        scan(mc, player);
        int i = PenRules.pick(types, adult, ready, love, sheared, inPen, distSq, count, means);
        if (i >= 0 && distSq[i] <= REACH * REACH) {
            target = animals[i];
            action = PenRules.actionFor(i, types, adult, ready, love, sheared, count, means);
        }
        java.util.Arrays.fill(animals, 0, count, null);
    }

    private void tickWork(LocalPlayer player) {
        if (player.getDeltaMovement().horizontalDistanceSqr() > 1.0e-3 || !hands.ensureLease()) {
            return;
        }
        Item item = itemFor(target, action);
        if (item == null) {
            finish();
            return;
        }
        int held = hands.holdItem(player, item);
        if (held == FieldHands.FAILED) {
            finish();
            return;
        }
        if (held == FieldHands.RUNNING) {
            return;
        }
        int r = hands.tickEntity(player, target, SimKey.USE, REACH);
        if (r == FieldHands.DONE && action == PenRules.Action.BREED) {
            if (fedAt.size() >= MAX_FED) {
                fedAt.clear();
            }
            fedAt.put(target.getId(), tick);
        }
        if (r != FieldHands.RUNNING) {
            finish();
        }
    }

    private void finish() {
        target = null;
        action = PenRules.Action.NONE;
        hands.release();
        hands.pauseHuman(6);
    }

    private Item itemFor(Animal a, PenRules.Action act) {
        return switch (act) {
            case SHEAR -> Items.SHEARS;
            case MILK -> Items.BUCKET;
            case BREED -> firstFood(a.getType().builtInRegistryHolder().key().identifier().toString());
            default -> null;
        };
    }

    private Item firstFood(String type) {
        Inventory inv = scanInventory;
        if (inv == null) {
            return null;
        }
        for (String id : PenRules.breedingFoods(type)) {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(id));
            if (inv.countItem(item) > 0) {
                return item;
            }
        }
        return null;
    }

    private boolean carriesFood(String type) {
        return firstFood(type) != null;
    }

    private void scan(Minecraft mc, LocalPlayer player) {
        scanInventory = player.getInventory();
        means.haveShears = scanInventory.countItem(Items.SHEARS) > 0;
        means.haveEmptyBucket = scanInventory.countItem(Items.BUCKET) > 0;
        AABB box = new AABB(penX - radius, player.getY() - 4, penZ - radius, penX + radius + 1, player.getY() + 4,
                penZ + radius + 1);
        List<Animal> found = mc.level.getEntitiesOfClass(Animal.class, box);
        count = 0;
        for (int k = 0; k < found.size() && count < CAP; k++) {
            Animal a = found.get(k);
            animals[count] = a;
            types[count] = a.getType().builtInRegistryHolder().key().identifier().toString();
            adult[count] = !a.isBaby();
            long fed = fedAt.get(a.getId());
            ready[count] = PenRules.breedReady(adult[count], fed, tick);
            love[count] = PenRules.inLove(fed, tick);
            sheared[count] = !(a instanceof Sheep s) || s.isSheared();
            inPen[count] = PenRules.inPen(a.getX(), a.getY(), a.getZ(), penX - radius, Integer.MIN_VALUE / 2,
                    penZ - radius, penX + radius, Integer.MAX_VALUE / 2, penZ + radius);
            distSq[count] = player.distanceToSqr(a);
            count++;
        }
    }

    @Override
    protected void stopWork() {
        target = null;
        action = PenRules.Action.NONE;
        penSet = false;
        count = 0;
        scanInventory = null;
        java.util.Arrays.fill(animals, null);
        super.stopWork();
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        super.readSettings(p);
        radius = Math.max(2, Math.min(32, p.getInt(ID, "radius", 8)));
        means.breed = p.getBool(ID, "breed", true);
        means.shear = p.getBool(ID, "shear", true);
        means.milk = p.getBool(ID, "milk", true);
        means.populationCap = Math.max(2, Math.min(128, p.getInt(ID, "population_cap", 24)));
    }

    @Override
    public String displayName() {
        return "Pen Sweep";
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("radius", "Pen radius", 8, 2, 32, "",
                        "Square pen around the spot where the sweep was switched on"),
                SettingSpec.bool("breed", "Breed", true, "", "Feed breed-ready adults when a partner is ready"),
                SettingSpec.bool("shear", "Shear sheep", true, "", "Shear unsheared sheep (needs shears)"),
                SettingSpec.bool("milk", "Milk", true, "", "Milk cows, mooshrooms and goats into empty buckets"),
                SettingSpec.integer("population_cap", "Population cap", 24, 2, 128, "",
                        "Stop breeding a kind once the pen holds this many"));
    }
}
