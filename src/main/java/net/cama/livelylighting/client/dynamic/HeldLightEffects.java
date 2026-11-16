package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.compat.CuriosLights;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.cama.livelylighting.dynamiclighting.sound.SoundData;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleType;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The particles and sounds of a light being taken out or going out (0.10.1), played by a client that draws its own light.
 * The server's block engine plays them for the players it lights for; a client that draws its own light was lit by no
 * engine and got nothing. Here each light in a hand or on a belt that this client sees is watched per tick: when one
 * starts to shine its definition's particles and sounds play at the hand (the belt: the hip), and when one held in water
 * or rain goes out while still held, its put-out sound and a little smoke. Nothing for a light first seen already shining
 * (someone walking into view with a torch), nothing for one lit again within a second of going out (switching back and
 * forth between slots), and at most one ignition per entity every half second. Played on this client alone: no packets.
 * {@code enable_particles}, {@code enable_sounds}, and the server's {@code /ll sound off} for the holder's own light.
 */
final class HeldLightEffects {
    /** Lit again within this many ticks of going out: the same light, no effects again (the server's IGNITION_REARM_TICKS). */
    private static final int REARM_TICKS = 20;
    /** At most one ignition per entity in this many ticks. */
    private static final int COOLDOWN_TICKS = 10;
    /** Entities further than this from the camera (blocks, on each axis) are not watched. */
    private static final double RANGE = 32;
    private static final int MAIN = 1, OFF = 2, BELT = 7;

    private static final class Watch {
        boolean lit;
        long lastLit = Long.MIN_VALUE / 2;
        long seen;
        @Nullable Item item;
        @Nullable LightData data;
    }

    private final Map<Long, Watch> watches = new HashMap<>();
    private final Map<Integer, Long> lastIgnition = new HashMap<>();
    private final RandomSource random = RandomSource.create();
    private long tick;
    /** For tests: the ignitions and put-outs played since the start. */
    private int ignitions, putOuts;

    int ignitions() {
        return ignitions;
    }

    int putOuts() {
        return putOuts;
    }

    void forget() {
        watches.clear();
        lastIgnition.clear();
    }

    void tick(ClientLevel level, LivelyConfig config) {
        tick = level.getGameTime();
        if (!config.enable || (!config.enable_particles && !config.enable_sounds)) {
            watches.clear();
            return;
        }
        Vec3 camera = Minecraft.getInstance().gameRenderer.getMainCamera().getPosition();
        for (Entity entity : level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity living) || entity.isSpectator() || entity.isRemoved()) continue;
            if (Math.abs(entity.getX() - camera.x) > RANGE || Math.abs(entity.getY() - camera.y) > RANGE || Math.abs(entity.getZ() - camera.z) > RANGE) continue;
            if (ClientLightMode.disabled(entity.getUUID()) || ClientLightMode.forcedLevel(entity.getUUID()) != null) continue;
            watch(living, MAIN, living.getMainHandItem(), level, config);
            watch(living, OFF, living.getOffhandItem(), level, config);
            if (entity instanceof Player && config.curios_lighting && CuriosLights.loaded()) {
                ItemStack brightest = ItemStack.EMPTY;
                int best = 0;
                for (ItemStack worn : CuriosLights.worn(living)) {
                    int l = LightCalculator.getItemLightLevel(worn, entity, level, config).level;
                    if (l > best) {
                        best = l;
                        brightest = worn;
                    }
                }
                watch(living, BELT, brightest, level, config);
            }
        }
        // entities gone from view for a few seconds: forgotten (one coming back holding a light is a light first seen)
        if (tick % 40 == 0) {
            watches.values().removeIf(w -> tick - w.seen > 60);
            lastIgnition.values().removeIf(t -> tick - t > 200);
        }
    }

    private void watch(LivingEntity entity, int slot, ItemStack stack, ClientLevel level, LivelyConfig config) {
        long key = ((long) entity.getId() << 8) | slot;
        LightData data = stack.isEmpty() ? LightCalculator.EMPTY_LIGHT_DATA : LightCalculator.getItemLightLevel(stack, entity, level, config);
        boolean lit = data.level > 0;
        Item item = stack.isEmpty() ? null : stack.getItem();
        Watch w = watches.get(key);
        if (w == null) {
            // first seen: whatever it holds is not being taken out now
            w = new Watch();
            watches.put(key, w);
            w.lit = lit;
            w.item = item;
            w.data = lit ? data : null;
            w.seen = tick;
            if (lit) w.lastLit = tick;
            return;
        }
        w.seen = tick;
        if (lit && !w.lit) {
            boolean relit = tick - w.lastLit <= REARM_TICKS;
            Long last = lastIgnition.get(entity.getId());
            if (!relit && (last == null || tick - last >= COOLDOWN_TICKS)) {
                lastIgnition.put(entity.getId(), tick);
                ignite(entity, slot, data, stack, level, config);
            }
        } else if (!lit && w.lit && w.data != null && w.data.waterSensitive && item != null && item == w.item) {
            // the same light still held, gone dark: water or rain put it out
            putOut(entity, slot, w.data, level, config);
        }
        if (lit) {
            w.lastLit = tick;
            w.data = data;
        }
        w.lit = lit;
        w.item = item;
    }

    private void ignite(LivingEntity entity, int slot, LightData data, ItemStack stack, ClientLevel level, LivelyConfig config) {
        ignitions++;
        Vec3 at = where(entity, slot);
        if (config.enable_sounds && !ClientLightMode.soundsOff(entity.getUUID())) {
            if (data.sounds != null && !data.sounds.isEmpty()) {
                for (SoundData sd : data.sounds) {
                    level.playLocalSound(at.x, at.y, at.z, sd.sound, source(entity), sd.volume, sd.pitch, false);
                }
            } else if (stack.is(net.cama.livelylighting.data.ModTags.Items.BELT_LANTERNS)) {
                // a lantern with no sound of its own (a mod's, lit by its block's light) clinks on its chain as vanilla's does
                level.playLocalSound(at.x, at.y, at.z, SoundEvents.CHAIN_STEP, source(entity), 0.5f, 1.2f, false);
            }
        }
        if (config.enable_particles && data.particles != null) {
            for (ParticleType<?> type : data.particles) {
                if (type instanceof ParticleOptions options) puff(level, options, at, 3, 0.05, 0.02);
            }
        }
    }

    private void putOut(LivingEntity entity, int slot, LightData last, ClientLevel level, LivelyConfig config) {
        putOuts++;
        Vec3 at = where(entity, slot);
        if (config.enable_sounds && !ClientLightMode.soundsOff(entity.getUUID())) {
            List<SoundData> sounds = last.extinguishSounds;
            if (sounds != null && !sounds.isEmpty()) {
                for (SoundData sd : sounds) level.playLocalSound(at.x, at.y, at.z, sd.sound, source(entity), sd.volume, sd.pitch, false);
            } else {
                level.playLocalSound(at.x, at.y, at.z, SoundEvents.FIRE_EXTINGUISH, source(entity), 0.35f, 1.8f, false);
            }
        }
        if (config.enable_particles) puff(level, ParticleTypes.SMOKE, at, 4, 0.06, 0.015);
    }

    private static SoundSource source(Entity entity) {
        return entity instanceof Player ? SoundSource.PLAYERS : SoundSource.NEUTRAL;
    }

    private void puff(ClientLevel level, ParticleOptions options, Vec3 at, int count, double spread, double speed) {
        for (int i = 0; i < count; i++) {
            level.addParticle(options,
                    at.x + random.nextGaussian() * spread, at.y + random.nextGaussian() * spread, at.z + random.nextGaussian() * spread,
                    random.nextGaussian() * speed, Math.abs(random.nextGaussian()) * speed, random.nextGaussian() * speed);
        }
    }

    /**
     * Where the effects of a slot's light are: a hand a little in front of and below the eyes, on the side of the arm that holds
     * it (in first person where the held item is drawn); the belt at the hip, on the right.
     */
    static Vec3 where(LivingEntity entity, int slot) {
        float yaw = entity.yBodyRot * Mth.DEG_TO_RAD;
        double fx = -Mth.sin(yaw), fz = Mth.cos(yaw);
        // the right of a body facing (fx, fz)
        double rx = -fz, rz = fx;
        HumanoidArm main = entity.getMainArm();
        boolean right = slot == OFF ? main == HumanoidArm.LEFT : main == HumanoidArm.RIGHT;
        double side = right ? 1 : -1;
        if (slot == BELT) {
            double h = entity.getBbHeight();
            return new Vec3(entity.getX() + rx * 0.3 * side + fx * 0.1, entity.getY() + h * 0.48, entity.getZ() + rz * 0.3 * side + fz * 0.1);
        }
        double scale = entity.getBbHeight() / 1.8;
        return new Vec3(entity.getX() + (fx * 0.4 + rx * 0.35 * side) * scale, entity.getEyeY() - 0.45 * scale, entity.getZ() + (fz * 0.4 + rz * 0.35 * side) * scale);
    }
}
