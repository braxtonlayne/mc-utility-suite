package com.utilitysuite.world.redstone;

import com.utilitysuite.core.api.ProfileConfig;
import com.utilitysuite.core.api.SettingSpec;
import com.utilitysuite.world.BlockPosPacking;
import com.utilitysuite.world.WorldModule;
import com.utilitysuite.world.render.RenderContext;
import com.utilitysuite.world.render.WorldRenderKit;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.BasePressurePlateBlock;
import net.minecraft.world.level.block.BellBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComparatorBlock;
import net.minecraft.world.level.block.CrafterBlock;
import net.minecraft.world.level.block.DaylightDetectorBlock;
import net.minecraft.world.level.block.DispenserBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FaceAttachedHorizontalDirectionalBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.HopperBlock;
import net.minecraft.world.level.block.NoteBlock;
import net.minecraft.world.level.block.ObserverBlock;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.RedstoneWireBlock;
import net.minecraft.world.level.block.RedstoneLampBlock;
import net.minecraft.world.level.block.RedstoneTorchBlock;
import net.minecraft.world.level.block.RedstoneWallTorchBlock;
import net.minecraft.world.level.block.RepeaterBlock;
import net.minecraft.world.level.block.TargetBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.TripWireHookBlock;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.joml.Matrix4f;

import java.util.List;

/**
 * Redstone Signal Tracer: while you look at a redstone component (lever, button, plate, observer, dust, repeater,
 * comparator, torch, piston, dropper, crafter, target, lamp ...), traces where its signal goes
 * ({@link RedstoneTracer}, bounded and budgeted, from the client's block states) and draws the network with a pulse
 * travelling along it hop by hop, a label per component with the cumulative game-tick delay from the looked-at block
 * and the signal strength readable from its state ("?" for comparators), and dashed boxes on the quasi-connectivity
 * zones above pistons, droppers and dispensers. The trace runs on the client tick when the looked-at block changes
 * and every few ticks after; frames only walk the result arrays. Off by default; nothing drawn when not looking at a
 * component or in Clean-Screen.
 */
public final class RedstoneTracerModule extends WorldModule {

    public static final String ID = "redstone_tracer";
    private static final int LOOKUP_BUDGET = 16_384;

    private final RedstoneTracer tracer = new RedstoneTracer();
    private final BlockPos.MutableBlockPos probe = new BlockPos.MutableBlockPos();
    private final RedstoneTracer.View view = this::classify;
    private final Int2ObjectOpenHashMap<String> labelCache = new Int2ObjectOpenHashMap<>();
    private ClientLevel level;
    private long tracedPos = Long.MIN_VALUE;
    private int ticksSinceTrace;
    private int nodes;

    private int maxNodes;
    private boolean labels;
    private boolean strength;
    private boolean qcZones;
    private int pulseSpeed;
    private int retraceTicks;

    public RedstoneTracerModule() {
        super(ID, "Redstone Tracer", false);
    }

    @Override
    public List<SettingSpec> settings() {
        return List.of(
                SettingSpec.integer("max_nodes", "Max components", 256, 32, RedstoneTracer.MAX_NODES, "Trace",
                        "Largest network traced from the block you look at."),
                SettingSpec.bool("labels", "Delay labels", true, "Display",
                        "Cumulative game-tick delay (+Ngt) from the looked-at block on each component."),
                SettingSpec.bool("strength", "Signal strength", true, "Display",
                        "Signal strength 0-15 from the block state on the labels (? for comparators)."),
                SettingSpec.bool("qc_zones", "Quasi-connectivity zones", true, "Display",
                        "Dashed box on the block above pistons, droppers and dispensers: power there also "
                                + "activates them."),
                SettingSpec.integer("pulse_speed", "Pulse speed (hops/s)", 6, 1, 20, "Display",
                        "How fast the animated pulse travels along the network."),
                SettingSpec.integer("retrace_ticks", "Retrace interval (ticks)", 10, 2, 40, "Advanced",
                        "How often the network is traced again while you keep looking at it.").asAdvanced());
    }

    @Override
    protected void readSettings(ProfileConfig p) {
        maxNodes = integer(p, "max_nodes");
        labels = bool(p, "labels");
        strength = bool(p, "strength");
        qcZones = bool(p, "qc_zones");
        pulseSpeed = integer(p, "pulse_speed");
        retraceTicks = integer(p, "retrace_ticks");
        labelCache.clear();
        tracedPos = Long.MIN_VALUE; // trace again with the new cap
    }

    @Override
    protected void onDisable() {
        reset();
    }

    @Override
    public void onWorldUnload() {
        reset();
        labelCache.clear();
    }

    private void reset() {
        tracer.clear();
        nodes = 0;
        tracedPos = Long.MIN_VALUE;
        level = null;
    }

    // ---- tick: trace ---------------------------------------------------------------------------------------------

    @Override
    public void onTick() {
        Minecraft mc = Minecraft.getInstance();
        ClientLevel lv = mc.level;
        HitResult hit = mc.hitResult;
        if (lv == null || !(hit instanceof BlockHitResult bh) || hit.getType() != HitResult.Type.BLOCK) {
            if (nodes != 0) {
                reset();
            }
            return;
        }
        BlockPos bp = bh.getBlockPos();
        long key = bp.asLong();
        ticksSinceTrace++;
        if (key == tracedPos && ticksSinceTrace < retraceTicks) {
            return;
        }
        level = lv;
        nodes = tracer.trace(view, bp.getX(), bp.getY(), bp.getZ(), maxNodes, LOOKUP_BUDGET);
        tracedPos = key;
        ticksSinceTrace = 0;
    }

    /** Block state to {@link RedstoneCell}; client thread, allocation free. */
    int classify(int x, int y, int z) {
        ClientLevel lv = level;
        if (lv == null) {
            return RedstoneCell.NONE;
        }
        BlockState s = lv.getBlockState(probe.set(x, y, z));
        if (s.isAir()) {
            return RedstoneCell.NONE;
        }
        Block b = s.getBlock();
        if (b instanceof RedstoneWireBlock) {
            int power = s.getValue(BlockStateProperties.POWER);
            return RedstoneCell.of(RedstoneCell.DUST, 0, power, power > 0);
        }
        if (b instanceof RepeaterBlock) {
            return RedstoneCell.of(RedstoneCell.REPEATER, out(s), s.getValue(BlockStateProperties.DELAY),
                    s.getValue(BlockStateProperties.POWERED));
        }
        if (b instanceof ComparatorBlock) {
            return RedstoneCell.withPowered(RedstoneCell.of(RedstoneCell.COMPARATOR, out(s)),
                    s.getValue(BlockStateProperties.POWERED));
        }
        if (b instanceof RedstoneWallTorchBlock) {
            return RedstoneCell.of(RedstoneCell.TORCH,
                    s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite().get3DDataValue(), 0,
                    s.getValue(BlockStateProperties.LIT));
        }
        if (b instanceof RedstoneTorchBlock) {
            return RedstoneCell.of(RedstoneCell.TORCH, RedstoneCell.DOWN, 0, s.getValue(BlockStateProperties.LIT));
        }
        if (b instanceof FaceAttachedHorizontalDirectionalBlock) { // levers and buttons
            int kind = b == Blocks.LEVER ? RedstoneCell.LEVER : RedstoneCell.BUTTON;
            return RedstoneCell.of(kind, attached(s), 0, s.getValue(BlockStateProperties.POWERED));
        }
        if (b instanceof TripWireHookBlock) {
            return RedstoneCell.of(RedstoneCell.BUTTON,
                    s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite().get3DDataValue(), 0,
                    s.getValue(BlockStateProperties.POWERED));
        }
        if (b instanceof BasePressurePlateBlock) {
            return RedstoneCell.of(RedstoneCell.PRESSURE_PLATE, RedstoneCell.DOWN);
        }
        if (b instanceof ObserverBlock) {
            return RedstoneCell.of(RedstoneCell.OBSERVER, s.getValue(BlockStateProperties.FACING).get3DDataValue(), 0,
                    s.getValue(BlockStateProperties.POWERED));
        }
        if (b == Blocks.REDSTONE_BLOCK) {
            return RedstoneCell.of(RedstoneCell.REDSTONE_BLOCK, 0);
        }
        if (b instanceof TargetBlock) {
            return RedstoneCell.of(RedstoneCell.TARGET, 0);
        }
        if (b instanceof DaylightDetectorBlock) {
            int power = s.getValue(BlockStateProperties.POWER);
            return RedstoneCell.of(RedstoneCell.DAYLIGHT, 0, power, power > 0);
        }
        if (b instanceof PistonBaseBlock) {
            return RedstoneCell.withPowered(RedstoneCell.of(RedstoneCell.PISTON,
                    s.getValue(BlockStateProperties.FACING).get3DDataValue()), s.getValue(BlockStateProperties.EXTENDED));
        }
        if (b instanceof CrafterBlock) {
            return RedstoneCell.of(RedstoneCell.CRAFTER, 0);
        }
        if (b instanceof DispenserBlock) { // droppers too
            return RedstoneCell.of(RedstoneCell.DROPPER, s.getValue(BlockStateProperties.FACING).get3DDataValue());
        }
        if (b instanceof RedstoneLampBlock) {
            return RedstoneCell.withPowered(RedstoneCell.of(RedstoneCell.LAMP, 0), s.getValue(BlockStateProperties.LIT));
        }
        if (b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock
                || b instanceof NoteBlock || b instanceof HopperBlock || b instanceof PoweredRailBlock
                || b instanceof BellBlock) {
            return RedstoneCell.of(RedstoneCell.OTHER_SINK, 0);
        }
        return s.isRedstoneConductor(lv, probe) ? RedstoneCell.of(RedstoneCell.SOLID, 0) : RedstoneCell.NONE;
    }

    /** Repeaters and comparators: vanilla FACING points at the input, the output is the opposite side. */
    private static int out(BlockState s) {
        return s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite().get3DDataValue();
    }

    private static int attached(BlockState s) {
        AttachFace face = s.getValue(BlockStateProperties.ATTACH_FACE);
        return switch (face) {
            case FLOOR -> Direction.DOWN.get3DDataValue();
            case CEILING -> Direction.UP.get3DDataValue();
            default -> s.getValue(BlockStateProperties.HORIZONTAL_FACING).getOpposite().get3DDataValue();
        };
    }

    // ---- rendering ------------------------------------------------------------------------------------------------

    private static int color(int kind) {
        return switch (kind) {
            case RedstoneCell.DUST -> 0xFFFF3030;
            case RedstoneCell.REPEATER, RedstoneCell.COMPARATOR -> 0xFFFFB040;
            case RedstoneCell.TORCH -> 0xFFFF7020;
            case RedstoneCell.OBSERVER -> 0xFFA0A0FF;
            case RedstoneCell.PISTON, RedstoneCell.DROPPER, RedstoneCell.CRAFTER, RedstoneCell.LAMP,
                 RedstoneCell.OTHER_SINK -> 0xFF60E0FF;
            default -> 0xFFFFFF60; // sources
        };
    }

    /** Pulse position in hops, cycling over the network depth plus a short rest. */
    private float pulse(RenderContext ctx) {
        ClientLevel lv = Minecraft.getInstance().level;
        if (lv == null) {
            return -1f;
        }
        float t = (lv.getGameTime() % 100_000L + ctx.partialTick) * pulseSpeed / 20f;
        float cycle = tracer.maxDepth() + 3f;
        return t % cycle;
    }

    @Override
    public void renderLines(RenderContext ctx) {
        if (nodes == 0) {
            return;
        }
        float p = pulse(ctx);
        int hop = (int) p;
        float frac = p - hop;
        for (int i = 0; i < nodes; i++) {
            long pos = tracer.pos(i);
            float x = ctx.rx(BlockPosPacking.x(pos));
            float y = ctx.ry(BlockPosPacking.y(pos));
            float z = ctx.rz(BlockPosPacking.z(pos));
            int c = color(tracer.nodeKind(i));
            boolean lit = tracer.depth(i) <= hop;
            int boxColor = lit ? c : (c & 0x00FFFFFF) | 0x90000000;
            WorldRenderKit.box(ctx.lines, ctx.pose, x + 0.1f, y + 0.02f, z + 0.1f, x + 0.9f, y + 0.6f, z + 0.9f,
                    boxColor);
            int par = tracer.parent(i);
            if (par < 0) {
                continue;
            }
            long pp = tracer.pos(par);
            float px = ctx.rx(BlockPosPacking.x(pp)) + 0.5f;
            float py = ctx.ry(BlockPosPacking.y(pp)) + 0.3f;
            float pz = ctx.rz(BlockPosPacking.z(pp)) + 0.5f;
            float cx = x + 0.5f;
            float cy = y + 0.3f;
            float cz = z + 0.5f;
            int edge = tracer.viaQuasiConnectivity(i) ? 0xC0FF9020 : 0x80FF4040;
            WorldRenderKit.line(ctx.lines, ctx.pose, px, py, pz, cx, cy, cz, edge);
            if (tracer.depth(i) == hop + 1) {
                float mx = px + (cx - px) * frac;
                float my = py + (cy - py) * frac;
                float mz = pz + (cz - pz) * frac;
                WorldRenderKit.box(ctx.lines, ctx.pose, mx - 0.08f, my - 0.08f, mz - 0.08f, mx + 0.08f, my + 0.08f,
                        mz + 0.08f, 0xFFFFFFFF);
            }
        }
        if (qcZones) {
            for (int i = 0, n = tracer.qcCount(); i < n; i++) {
                long q = tracer.qcZone(i);
                float x = ctx.rx(BlockPosPacking.x(q));
                float y = ctx.ry(BlockPosPacking.y(q));
                float z = ctx.rz(BlockPosPacking.z(q));
                WorldRenderKit.dashedBox(ctx.lines, ctx.pose, x, y, z, x + 1f, y + 1f, z + 1f, 0xC0FF9020);
            }
        }
    }

    @Override
    public void renderLabels(RenderContext ctx) {
        if (nodes == 0 || !labels) {
            return;
        }
        for (int i = 0; i < nodes; i++) {
            long pos = tracer.pos(i);
            float x = ctx.rx(BlockPosPacking.x(pos) + 0.5);
            float y = ctx.ry(BlockPosPacking.y(pos) + 1.0);
            float z = ctx.rz(BlockPosPacking.z(pos) + 0.5);
            Matrix4f m = ctx.kit.beginLabel(ctx.pose, x, y, z);
            String s = label(i);
            WorldRenderKit.text(ctx.font, ctx.buffers, m, s, -ctx.font.width(s) / 2f,
                    color(tracer.nodeKind(i)) | 0xFF000000);
        }
    }

    /** Cached per (delay, strength, flags): allocates only the first time a combination is shown. */
    private String label(int i) {
        int kind = tracer.nodeKind(i);
        int str = strength ? (kind == RedstoneCell.COMPARATOR ? 17 : tracer.nodeStrength(i) + 1) : 0;
        int flags = (tracer.viaQuasiConnectivity(i) ? 1 : 0) | (kind == RedstoneCell.PISTON ? 2 : 0);
        int delay = Math.min(tracer.delay(i), 0xFFFF);
        int key = delay << 8 | str << 2 | flags;
        String s = labelCache.get(key);
        if (s == null) {
            s = RedstoneLabels.format(delay, str == 17 ? -2 : str - 1, (flags & 1) != 0, (flags & 2) != 0);
            labelCache.put(key, s);
        }
        return s;
    }

    /** For tests and diagnostics. */
    RedstoneTracer tracer() {
        return tracer;
    }
}
