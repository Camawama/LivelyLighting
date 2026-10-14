package net.cama.livelylighting.network;

import net.cama.livelylighting.LivelyLighting;
import net.camacraft.camafy.network.CamafyChannel;
import net.camacraft.camafy.network.Packets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lively Lighting's channel, {@code livelylighting:light_sources} (optional on both sides, clientbound only): what the
 * mod's light blocks round a player stand for (LightSources), so a client with Colorful Lighting gives each its source's
 * colour (light_source_colors). Clients without Lively Lighting join as before and are sent nothing. Registered only
 * with Camafy present (always, on a server: Camafy is required there).
 */
public final class LightSourceNet {
    /** Lights sent in one message at most. */
    private static final int MAX_ENTRIES = 2048;

    @Nullable
    private static CamafyChannel net;
    @Nullable
    private static volatile Consumer<List<Entry>> clientSink;

    private LightSourceNet() {}

    /** One light: its block position and its sources (LightSources pairs). */
    public record Entry(long pos, long[] sources) {}

    /** Called once from the mod's constructor, on both sides. */
    public static void register() {
        net = CamafyChannel.createOptional(new ResourceLocation(LivelyLighting.MODID, "light_sources"), "1");
        net.clientbound(Lights.class, Lights::encode, Lights::decode, Lights::handle);
    }

    public static boolean ready() {
        return net != null;
    }

    public static boolean hasChannel(ServerPlayer player) {
        return net != null && net.hasChannel(player);
    }

    /** What a client does with the lights it is sent (Lively Lighting's client half sets it). */
    public static void setClientSink(Consumer<List<Entry>> sink) {
        clientSink = sink;
    }

    public static void send(ServerPlayer player, List<Entry> entries) {
        if (net == null || entries.isEmpty()) return;
        for (int from = 0; from < entries.size(); from += MAX_ENTRIES) {
            net.toPlayer(player, new Lights(new ArrayList<>(entries.subList(from, Math.min(entries.size(), from + MAX_ENTRIES)))));
        }
    }

    record Lights(List<Entry> entries) {
        static void encode(Lights msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entries.size());
            for (Entry entry : msg.entries) {
                buf.writeLong(entry.pos);
                int pairs = Math.min(entry.sources.length / 2, 8);
                buf.writeByte(pairs);
                for (int i = 0; i < pairs; i++) {
                    buf.writeByte((int) entry.sources[2 * i]);
                    buf.writeLong(entry.sources[2 * i + 1]);
                }
            }
        }

        static Lights decode(FriendlyByteBuf buf) {
            int n = Math.min(buf.readVarInt(), MAX_ENTRIES);
            List<Entry> entries = new ArrayList<>(n);
            for (int k = 0; k < n; k++) {
                long pos = buf.readLong();
                int pairs = Math.min(buf.readUnsignedByte(), 8);
                long[] sources = new long[2 * pairs];
                for (int i = 0; i < pairs; i++) {
                    sources[2 * i] = buf.readUnsignedByte();
                    sources[2 * i + 1] = buf.readLong();
                }
                entries.add(new Entry(pos, sources));
            }
            return new Lights(entries);
        }

        static void handle(Lights msg, Supplier<NetworkEvent.Context> ctx) {
            Packets.handleEither(ctx, () -> {
                Consumer<List<Entry>> sink = clientSink;
                if (sink != null) sink.accept(msg.entries);
            });
        }
    }
}
