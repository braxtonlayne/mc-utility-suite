package com.utilitysuite.tools.packetlab;

import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;

import java.util.SplittableRandom;
import java.util.concurrent.TimeUnit;

/**
 * Local test latency simulator: delays inbound and outbound packets by {@code latency ± jitter} on the channel's event
 * loop. Order is preserved per direction ({@link LatencyScheduler}). Installed only for single-player or an explicitly
 * allowed test server, off by default, removed on disconnect. When switched off it keeps passing packets after the
 * still-delayed ones until its queue is empty, then removes itself, so switching off never reorders packets.
 * <p>
 * This is a test tool: each delayed packet costs one scheduled task (Netty allocation), which is the price of
 * simulating latency. The read-only inspector ({@link PacketProbes}) stays allocation free.
 */
final class LatencyHandler extends ChannelDuplexHandler {

    static final String NAME = "utilitysuite_latency_sim";
    private static final java.nio.channels.ClosedChannelException CLOSED = new java.nio.channels.ClosedChannelException();

    private final LatencyScheduler scheduler = new LatencyScheduler(new SplittableRandom());
    private volatile int latencyMs;
    private volatile int jitterMs;
    private volatile boolean active = true;
    private int pending;

    LatencyHandler(int latencyMs, int jitterMs) {
        configure(latencyMs, jitterMs);
    }

    void configure(int latencyMs, int jitterMs) {
        this.latencyMs = Math.max(0, latencyMs);
        this.jitterMs = Math.max(0, jitterMs);
    }

    /** Stops delaying; the handler removes itself once queued packets are delivered. */
    void deactivate() {
        active = false;
    }

    boolean isActive() {
        return active;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        long now = System.nanoTime();
        long due;
        if (active) {
            due = scheduler.nextDue(now, PacketRingBuffer.INBOUND, latencyMs, jitterMs);
        } else if (pending > 0) {
            due = scheduler.drainDue(now, PacketRingBuffer.INBOUND);
        } else {
            ctx.fireChannelRead(msg);
            removeIfIdle(ctx);
            return;
        }
        pending++;
        ctx.executor().schedule(() -> {
            pending--;
            if (ctx.isRemoved() || !ctx.channel().isActive()) {
                ReferenceCountUtil.release(msg); // the connection closed while it waited: nobody reads it any more
            } else {
                ctx.fireChannelRead(msg);
            }
            removeIfIdle(ctx);
        }, due - now, TimeUnit.NANOSECONDS);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        long now = System.nanoTime();
        long due;
        if (active) {
            due = scheduler.nextDue(now, PacketRingBuffer.OUTBOUND, latencyMs, jitterMs);
        } else if (pending > 0) {
            due = scheduler.drainDue(now, PacketRingBuffer.OUTBOUND);
        } else {
            ctx.write(msg, promise);
            removeIfIdle(ctx);
            return;
        }
        pending++;
        ctx.executor().schedule(() -> {
            pending--;
            if (ctx.isRemoved() || !ctx.channel().isActive()) {
                ReferenceCountUtil.release(msg); // closed while it waited: fail the write instead of leaking it
                promise.tryFailure(CLOSED);
            } else {
                ctx.writeAndFlush(msg, promise);
            }
            removeIfIdle(ctx);
        }, due - now, TimeUnit.NANOSECONDS);
    }

    /** Event loop only. */
    void removeIfIdle(ChannelHandlerContext ctx) {
        if (!active && pending == 0 && !ctx.isRemoved()) {
            ctx.pipeline().remove(this);
        }
    }
}
