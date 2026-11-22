package net.cama.livelylighting.source;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The server's half of the beam zoom (0.11.0): a client whose player sneaks with a zoomable spot light in hand (a definition
 * with a {@link BeamZoom}) sends a notch of its mouse wheel ({@code LightSourceNet.Zoom}, +1 tighter, -1 wider); this moves the
 * stack's step by one and the stack's tag reaches every client through the game's own inventory sync, so each draws the
 * same cone. The message is a client's word: only its sign is taken, the item is read from the player's own hands on the
 * server, and a player gets a burst of {@link #BURST} notches and then one every {@link #REFILL_TICKS} ticks (a wheel flick is
 * three or four). The holder sees the setting on the action bar and hears a soft click.
 */
public final class BeamScroll {
    /** Notches a player may send at once; after them one every {@link #REFILL_TICKS} ticks. */
    static final int BURST = 8;
    static final int REFILL_TICKS = 2;

    private static final class Bucket {
        int tokens = BURST;
        long lastTick;
    }

    private static final Map<UUID, Bucket> BUCKETS = new ConcurrentHashMap<>();

    private BeamScroll() {}

    /** One notch from a player's client: {@code dir} +1 narrows the beam, -1 widens it. Server thread. */
    public static void serverScroll(ServerPlayer player, int dir) {
        if (player == null || (dir != 1 && dir != -1)) return;
        if (!LivelyConfig.get().beam_zoom) return;
        if (!allow(player)) return;
        InteractionHand hand = null;
        LightData def = null;
        for (InteractionHand h : InteractionHand.values()) {
            LightData d = LightCalculator.zoomableDefinition(player.getItemInHand(h));
            if (d != null && d.zoom != null) {
                hand = h;
                def = d;
                break;
            }
        }
        if (def == null) return;
        ItemStack stack = player.getItemInHand(hand);
        BeamZoom zoom = def.zoom;
        int current = zoom.currentStep(stack, def.coneDegrees);
        int next = zoom.clamp(current + dir);
        if (next != current || zoom.stepOf(stack) < 0) {
            // the inventory's own sync carries the tag to every client that sees the stack
            zoom.setStep(stack, next);
        }
        player.displayClientMessage(message(zoom, next), true);
        if (next != current) {
            float t = next / (float) zoom.steps();
            player.level().playSound(null, player.getX(), player.getEyeY(), player.getZ(), SoundEvents.ITEM_FRAME_ROTATE_ITEM, SoundSource.PLAYERS,
                    0.25f, 0.9f + 0.4f * t);
        }
    }

    /** The action bar line: {@code Beam 36° · 59 blocks}, with the end named at the ends. */
    public static MutableComponent message(BeamZoom zoom, int step) {
        MutableComponent text = Component.literal("Beam ").withStyle(ChatFormatting.GRAY)
                .append(Component.literal(zoom.describe(step)).withStyle(ChatFormatting.WHITE));
        String end = zoom.endOf(step);
        if (!end.isEmpty()) text.append(Component.literal(" (" + end + ")").withStyle(ChatFormatting.DARK_GRAY));
        return text;
    }

    private static boolean allow(ServerPlayer player) {
        long now = player.level().getGameTime();
        Bucket b = BUCKETS.computeIfAbsent(player.getUUID(), id -> {
            Bucket nb = new Bucket();
            nb.lastTick = now;
            return nb;
        });
        synchronized (b) {
            long passed = now - b.lastTick;
            if (passed > 0) {
                b.tokens = (int) Math.min(BURST, b.tokens + passed / REFILL_TICKS);
                if (passed / REFILL_TICKS > 0) b.lastTick = now - passed % REFILL_TICKS;
            }
            if (b.tokens <= 0) return false;
            b.tokens--;
        }
        if (BUCKETS.size() > 256) prune(now);
        return true;
    }

    /** Buckets of players not heard from for a minute are dropped. */
    private static void prune(long now) {
        for (Iterator<Bucket> it = BUCKETS.values().iterator(); it.hasNext(); ) {
            Bucket b = it.next();
            if (now - b.lastTick > 1200) it.remove();
        }
    }

    /** A player's bucket, for tests; null for none. */
    @Nullable
    static Integer tokensOf(UUID id) {
        Bucket b = BUCKETS.get(id);
        return b == null ? null : b.tokens;
    }

    public static void forget(UUID id) {
        BUCKETS.remove(id);
    }
}
