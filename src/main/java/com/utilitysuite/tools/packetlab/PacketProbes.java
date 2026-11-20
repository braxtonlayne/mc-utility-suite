package com.utilitysuite.tools.packetlab;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import net.minecraft.network.protocol.Packet;

/**
 * Read-only Netty handlers for the packet inspector. They never modify, delay or drop a message.
 * <ul>
 *   <li>{@link Frame} sits next to the decoder/encoder and sees serialized (decompressed) frames: it measures sizes.</li>
 *   <li>{@link Typed} sits right before {@code packet_handler} and sees decoded packets: it records the type.</li>
 * </ul>
 * Inbound, the frame comes first and its size is carried to the next decoded packet (bundles add up). Outbound, the
 * packet comes first and the frame size is added to that record afterwards. Single-player memory connections are not
 * serialized, so sizes there are {@link PacketRingBuffer#UNKNOWN_SIZE}. Both handlers run on the channel's event loop
 * and allocate nothing per packet.
 */
final class PacketProbes {

    static final String FRAME_NAME = "utilitysuite_packetlab_frame";
    static final String TYPED_NAME = "utilitysuite_packetlab_packet";

    private PacketProbes() {
    }

    /** Shared state of the two handlers of one connection (event-loop confined). */
    static final class Probe {
        final PacketRingBuffer ring;
        final PacketTypeTable types;
        final RateRing rates;
        int pendingInbound = PacketRingBuffer.UNKNOWN_SIZE;

        Probe(PacketRingBuffer ring, PacketTypeTable types, RateRing rates) {
            this.ring = ring;
            this.types = types;
            this.rates = rates;
        }
    }

    @ChannelHandler.Sharable
    static final class Frame extends ChannelDuplexHandler {
        private final Probe p;

        Frame(Probe p) {
            this.p = p;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof ByteBuf buf) {
                int n = buf.readableBytes();
                p.pendingInbound = p.pendingInbound == PacketRingBuffer.UNKNOWN_SIZE ? n : p.pendingInbound + n;
                p.rates.add(System.currentTimeMillis() / 1000L, PacketLab.RATE_IN_BYTES, n);
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof ByteBuf buf) {
                int n = buf.readableBytes();
                p.ring.addSizeToLast(n);
                p.rates.add(System.currentTimeMillis() / 1000L, PacketLab.RATE_OUT_BYTES, n);
            }
            ctx.write(msg, promise);
        }
    }

    @ChannelHandler.Sharable
    static final class Typed extends ChannelDuplexHandler {
        private final Probe p;

        Typed(Probe p) {
            this.p = p;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Packet<?> packet) {
                long now = System.currentTimeMillis();
                p.ring.record(now, p.types.idOf(packet.type()), PacketRingBuffer.INBOUND, p.pendingInbound);
                p.pendingInbound = PacketRingBuffer.UNKNOWN_SIZE;
                p.rates.add(now / 1000L, PacketLab.RATE_IN_COUNT, 1);
            }
            ctx.fireChannelRead(msg);
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
            if (msg instanceof Packet<?> packet) {
                long now = System.currentTimeMillis();
                p.ring.record(now, p.types.idOf(packet.type()), PacketRingBuffer.OUTBOUND,
                        PacketRingBuffer.UNKNOWN_SIZE);
                p.rates.add(now / 1000L, PacketLab.RATE_OUT_COUNT, 1);
            }
            ctx.write(msg, promise);
        }
    }
}
