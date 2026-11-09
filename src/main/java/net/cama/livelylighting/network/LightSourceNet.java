package net.cama.livelylighting.network;

import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.source.LightMode;
import net.camacraft.camafy.network.CamafyChannel;
import net.camacraft.camafy.network.Packets;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Lively Lighting's channel, {@code livelylighting:light_sources} (optional on both sides, clientbound only). Two
 * messages: {@code Lights}, what the mod's light blocks round a player stand for (LightSources), so a client with Colorful
 * Lighting gives each its source's colour (light_source_colors); and since 0.5.0 {@code Mode}, the server's lighting
 * mode, whether the server places light blocks for this player, and the forced and disabled entities of {@code /ll add}
 * and {@code /ll toggle}, so a client that draws its own light draws the same. Clients without Lively Lighting join as
 * before and are sent nothing. Registered only with Camafy present (always, on a server: Camafy is required there).
 */
public final class LightSourceNet {
    /** Lights sent in one message at most. */
    private static final int MAX_ENTRIES = 2048;

    @Nullable
    private static CamafyChannel net;
    @Nullable
    private static volatile Consumer<List<Entry>> clientSink;
    @Nullable
    private static volatile Consumer<ModeInfo> clientModeSink;

    private LightSourceNet() {}

    /** One light: its block position and its sources (LightSources pairs). */
    public record Entry(long pos, long[] sources) {}

    /** The server's mode as told to a client: the mode, whether the server lights for this client, forced levels, disabled entities. */
    public record ModeInfo(LightMode mode, boolean serverLightsForYou, Map<UUID, Integer> forced, Set<UUID> disabled) {}

    /** Called once from the mod's constructor, on both sides. */
    public static void register() {
        // "3" (2026-11-09): the Mode message; "2" (2026-10-17): each source's kind carries its level. An older client
        // doesn't have the channel and keeps Colorful Lighting's own guess; it is lit by the server's blocks as always.
        net = CamafyChannel.createOptional(new ResourceLocation(LivelyLighting.MODID, "light_sources"), "3");
        net.clientbound(Lights.class, Lights::encode, Lights::decode, Lights::handle);
        net.clientbound(Mode.class, Mode::encode, Mode::decode, Mode::handle);
    }

    public static boolean ready() {
        return net != null;
    }

    /** Whether the player's client has this version of the mod (and so draws its own light in the client modes). */
    public static boolean hasChannel(ServerPlayer player) {
        return net != null && net.hasChannel(player);
    }

    /** What a client does with the lights it is sent (Lively Lighting's client half sets it). */
    public static void setClientSink(Consumer<List<Entry>> sink) {
        clientSink = sink;
    }

    /** What a client does with the server's mode (the client module sets it). */
    public static void setClientModeSink(Consumer<ModeInfo> sink) {
        clientModeSink = sink;
    }

    public static void send(ServerPlayer player, List<Entry> entries) {
        if (net == null || entries.isEmpty()) return;
        for (int from = 0; from < entries.size(); from += MAX_ENTRIES) {
            net.toPlayer(player, new Lights(new ArrayList<>(entries.subList(from, Math.min(entries.size(), from + MAX_ENTRIES)))));
        }
    }

    public static void sendMode(ServerPlayer player, ModeInfo info) {
        if (net == null || !net.hasChannel(player)) return;
        net.toPlayer(player, new Mode(info));
    }

    record Lights(List<Entry> entries) {
        static void encode(Lights msg, FriendlyByteBuf buf) {
            buf.writeVarInt(msg.entries.size());
            for (Entry entry : msg.entries) {
                buf.writeLong(entry.pos);
                int pairs = Math.min(entry.sources.length / 2, 8);
                buf.writeByte(pairs);
                for (int i = 0; i < pairs; i++) {
                    buf.writeShort((int) entry.sources[2 * i] & 0xFFFF); // the kind and, above it, the source's level
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
                    sources[2 * i] = buf.readUnsignedShort();
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

    record Mode(ModeInfo info) {
        static void encode(Mode msg, FriendlyByteBuf buf) {
            buf.writeByte(msg.info.mode.ordinal());
            buf.writeBoolean(msg.info.serverLightsForYou);
            buf.writeVarInt(msg.info.forced.size());
            for (Map.Entry<UUID, Integer> e : msg.info.forced.entrySet()) {
                buf.writeUUID(e.getKey());
                buf.writeByte(e.getValue());
            }
            buf.writeVarInt(msg.info.disabled.size());
            for (UUID id : msg.info.disabled) buf.writeUUID(id);
        }

        static Mode decode(FriendlyByteBuf buf) {
            int ordinal = buf.readUnsignedByte();
            LightMode mode = ordinal < LightMode.values().length ? LightMode.values()[ordinal] : LightMode.SERVER_BLOCKS;
            boolean forYou = buf.readBoolean();
            int n = Math.min(buf.readVarInt(), 65536);
            Map<UUID, Integer> forced = new HashMap<>();
            for (int i = 0; i < n; i++) forced.put(buf.readUUID(), (int) buf.readUnsignedByte());
            int m = Math.min(buf.readVarInt(), 65536);
            Set<UUID> disabled = new HashSet<>();
            for (int i = 0; i < m; i++) disabled.add(buf.readUUID());
            return new Mode(new ModeInfo(mode, forYou, forced, disabled));
        }

        static void handle(Mode msg, Supplier<NetworkEvent.Context> ctx) {
            Packets.handleEither(ctx, () -> {
                Consumer<ModeInfo> sink = clientModeSink;
                if (sink != null) sink.accept(msg.info);
            });
        }
    }
}
