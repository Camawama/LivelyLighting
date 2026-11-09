package net.cama.livelylighting.client.dynamic;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.compat.CuriosLights;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightShape;
import net.camacraft.camafy.compat.vs.ShipRef;
import net.camacraft.camafy.compat.vs.Ships;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.EnderMan;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's light sources this tick: every entity the client knows that gives light by the shared rules
 * ({@link LightCalculator}: hands, armour, curios, carried block, burning, glowing, creepers, TNT, definitions, tags), the
 * forced levels and switched-off entities the server told us, and the lamps of ships ({@link ShipLamps}). Levels fade in
 * and out as the server module's do ({@code smoothing}, {@code fade_in_rate}, {@code trail_decay_rate}, players only unless
 * {@code smoothing_all_entities}). Positions are continuous: an entity's light sits at its eye, a lamp at its block centre,
 * and each light knows where it is at any moment of the frame. Run once per client tick on the render thread; the result is
 * an immutable list the renderers read.
 */
public final class ClientLightEngine {
    private static final int MAX_SLOTS = 8;

    /** A source's fade state: the level it has reached, and the tick it was last wanted. */
    private static final class Fade {
        float established;
        float held;
        long lastSeen;
    }

    private final Map<Long, Fade> fades = new HashMap<>();
    private final ShipLamps shipLamps = new ShipLamps();
    private final ContraptionLamps contraptionLamps = new ContraptionLamps();
    /** The API lights at the moment of the frame being drawn, by key (filled once per frame for the renderers). */
    private final Map<Long, net.camacraft.camafy.light.LightSource> apiFrame = new HashMap<>();
    private float apiFramePartialTick = Float.NaN;
    private long apiFrameTick = -1;
    private static final long API_KEY_BIT = 0x4000000000000000L;
    private volatile List<DynamicLight> lights = Collections.emptyList();
    private long tick;

    public List<DynamicLight> lights() {
        return lights;
    }

    public void forget() {
        fades.clear();
        shipLamps.forget();
        contraptionLamps.forget();
        lights = Collections.emptyList();
    }

    /** One client tick: gathers, fades and publishes the lights. */
    public void tick(ClientLevel level) {
        LivelyConfig config = LivelyConfig.get();
        tick = level.getGameTime();
        List<DynamicLight> out = new ArrayList<>();
        if (!config.enable) {
            fades.clear();
            lights = Collections.emptyList();
            return;
        }
        Falloff falloff = Falloff.parse(config.client.falloff, Falloff.DIAMOND);
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double cull = config.light_cull_distance > 0 ? config.light_cull_distance : 192;
        int max = Math.max(1, config.max_light_sources);

        List<Entity> candidates = new ArrayList<>();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue;
            if (!shouldCheck(entity, config)) continue;
            double dx = Math.abs(entity.getX() - camera.x), dy = Math.abs(entity.getY() - camera.y), dz = Math.abs(entity.getZ() - camera.z);
            if (Math.max(dx, Math.max(dy, dz)) > cull) continue;
            candidates.add(entity);
        }
        candidates.sort((a, b) -> Double.compare(a.distanceToSqr(camera), b.distanceToSqr(camera)));
        int count = 0;
        for (Entity entity : candidates) {
            if (count >= max) break;
            if (collectEntity(entity, level, config, falloff, out)) count++;
        }
        shipLamps.collect(level, camera, config, falloff, out);
        contraptionLamps.collect(level, camera, config, falloff, out);
        collectApi(level, config, falloff, out);
        // fades that were not wanted this tick run out
        fades.entrySet().removeIf(e -> tick - e.getValue().lastSeen > 1200);
        lights = Collections.unmodifiableList(out);
    }

    /** Other mods' lights through Camafy's DynamicLights (kart headlights, an NPC's torch), faded like the mod's own. */
    private void collectApi(ClientLevel level, LivelyConfig config, Falloff falloff, List<DynamicLight> out) {
        if (!net.camacraft.camafy.light.DynamicLights.hasProviders()) return;
        List<net.camacraft.camafy.light.LightSource> sources = new ArrayList<>();
        net.camacraft.camafy.light.DynamicLights.collect(level, 1.0f, sources::add);
        for (net.camacraft.camafy.light.LightSource src : sources) {
            long key = src.key() ^ API_KEY_BIT;
            float lvl = fadeLevel(key, src.level(), config.smoothing && config.smoothing_all_entities, config);
            if (lvl <= 0) continue;
            final Vec3 tickPos = src.pos();
            final Vec3 tickDir = src.direction();
            DynamicLight.Mover mover = pt -> {
                net.camacraft.camafy.light.LightSource now = apiAt(level, pt, src.key());
                return now == null ? tickPos : now.pos();
            };
            DynamicLight.Mover dir = !src.isSpot() ? null : pt -> {
                net.camacraft.camafy.light.LightSource now = apiAt(level, pt, src.key());
                return now == null || now.direction() == null ? tickDir : now.direction();
            };
            out.add(new DynamicLight(key, DynamicLight.Kind.API, mover, dir, lvl, src.rgb(), src.isSpot() ? LightShape.SPOT : LightShape.POINT,
                    src.isSpot() ? src.outerDegrees() : 0f, Math.max(1f, src.reach()), falloff));
        }
    }

    /** The API lights at a moment of the frame, collected once per frame (1 = the tick's own positions). */
    private net.camacraft.camafy.light.LightSource apiAt(ClientLevel level, float partialTick, long key) {
        if (partialTick >= 1.0f) return null;
        if (apiFramePartialTick != partialTick || apiFrameTick != tick) {
            apiFrame.clear();
            net.camacraft.camafy.light.DynamicLights.collect(level, partialTick, s -> apiFrame.put(s.key(), s));
            apiFramePartialTick = partialTick;
            apiFrameTick = tick;
        }
        return apiFrame.get(key);
    }

    /** Fades a source's level toward what is wanted (the server module's fade_in_rate and trail_decay_rate), 0 when out. */
    private float fadeLevel(long key, float wanted, boolean smoothing, LivelyConfig config) {
        Fade fade = fades.get(key);
        if (!smoothing) {
            if (wanted <= 0) {
                fades.remove(key);
                return 0;
            }
            if (fade == null) fades.put(key, fade = new Fade());
            fade.lastSeen = tick;
            fade.established = fade.held = wanted;
            return wanted;
        }
        if (fade == null) {
            if (wanted <= 0) return 0;
            fades.put(key, fade = new Fade());
        }
        fade.lastSeen = tick;
        int fadeIn = Math.max(1, config.fade_in_rate), decay = Math.max(1, config.trail_decay_rate);
        if (wanted > 0) {
            fade.established = Math.min(wanted, fade.established + fadeIn);
            fade.held = fade.established;
        } else {
            fade.established = 0;
            fade.held = Math.max(0, fade.held - decay);
            if (fade.held <= 0) fades.remove(key);
        }
        return fade.held;
    }

    /** The dynamic light (0 to 15) this client's lights give a world position, for Camafy's DynamicLights.lightAt. */
    public float levelAt(Vec3 pos) {
        double best = 0;
        for (DynamicLight light : lights) {
            double l = light.levelAt(pos.x, pos.y, pos.z);
            if (l > best) best = l;
        }
        return (float) Math.min(15, best);
    }

    private static boolean shouldCheck(Entity entity, LivelyConfig config) {
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof Creeper || entity instanceof PrimedTnt || entity instanceof ItemFrame) return true;
        return config.enable_entity_lights || ClientLightMode.forcedLevel(entity.getUUID()) != null;
    }

    /** The entity's lights this tick (one per slot with cluster_growing, else one combined); true when it gives any. */
    private boolean collectEntity(Entity entity, ClientLevel level, LivelyConfig config, Falloff falloff, List<DynamicLight> out) {
        if (ClientLightMode.disabled(entity.getUUID())) return false;
        boolean any = false;
        Integer forced = ClientLightMode.forcedLevel(entity.getUUID());
        if (forced != null) {
            return add(entity, 9, LightCalculator.EMPTY_LIGHT_DATA.withLevel(forced), config, falloff, out, DynamicLight.Kind.FORCED);
        }
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            any |= add(entity, 1, LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config), config, falloff, out, DynamicLight.Kind.ENTITY);
            any |= add(entity, 2, LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config), config, falloff, out, DynamicLight.Kind.ENTITY);
            int slot = 3;
            for (EquipmentSlot s : EquipmentSlot.values()) {
                if (s.getType() == EquipmentSlot.Type.ARMOR) {
                    any |= add(entity, slot++, LightCalculator.getItemLightLevel(living.getItemBySlot(s), entity, level, config), config, falloff, out, DynamicLight.Kind.ENTITY);
                }
            }
            if (config.curios_lighting && CuriosLights.loaded()) {
                LightData curios = LightCalculator.EMPTY_LIGHT_DATA;
                for (ItemStack worn : CuriosLights.worn(living)) {
                    LightData one = LightCalculator.getItemLightLevel(worn, entity, level, config);
                    if (one.level > curios.level) curios = one;
                }
                any |= add(entity, 7, curios, config, falloff, out, DynamicLight.Kind.ENTITY);
            }
            if (entity instanceof EnderMan enderman && enderman.getCarriedBlock() != null) {
                any |= add(entity, 8, LightCalculator.getItemLightLevel(new ItemStack(enderman.getCarriedBlock().getBlock()), entity, level, config), config, falloff, out, DynamicLight.Kind.ENTITY);
            }
            any |= add(entity, 0, LightCalculator.getEntityLightLevel(entity, level, config), config, falloff, out, DynamicLight.Kind.ENTITY);
            return any;
        }
        LightData best = LightCalculator.getBestLightLevel(entity, level, config);
        return add(entity, 0, best, config, falloff, out, entity instanceof ItemEntity ? DynamicLight.Kind.ITEM_ENTITY : DynamicLight.Kind.ENTITY);
    }

    /** Fades one source toward its wanted level and, if it still shines, adds its light. */
    private boolean add(Entity entity, int slot, LightData data, LivelyConfig config, Falloff falloff, List<DynamicLight> out, DynamicLight.Kind kind) {
        long key = ((long) entity.getId() << 8) | (slot & 0xFF);
        boolean smoothing = config.smoothing && (config.smoothing_all_entities || entity instanceof Player);
        float wanted = data.level;
        float level;
        Fade fade = fades.get(key);
        if (!smoothing) {
            level = wanted;
            if (wanted <= 0) {
                fades.remove(key);
                return false;
            }
            if (fade == null) fades.put(key, fade = new Fade());
            fade.lastSeen = tick;
            fade.established = fade.held = wanted;
        } else {
            if (fade == null) {
                if (wanted <= 0) return false;
                fades.put(key, fade = new Fade());
            }
            fade.lastSeen = tick;
            int fadeIn = Math.max(1, config.fade_in_rate), decay = Math.max(1, config.trail_decay_rate);
            if (wanted > 0) {
                fade.established = Math.min(wanted, fade.established + fadeIn);
                fade.held = fade.established;
            } else {
                fade.established = 0;
                fade.held = Math.max(0, fade.held - decay);
                if (fade.held <= 0) {
                    fades.remove(key);
                    return false;
                }
            }
            level = fade.held;
        }
        if (level <= 0) return false;
        // the light sits at the eye (a dropped item at its centre), where the server module anchors it, and goes with
        // the entity through the frame; a shipyard resident (an item frame on a ship) at its place in the world
        ShipRef ship = Ships.available() ? Ships.managing(entity) : null;
        DynamicLight.Mover mover;
        if (ship != null) {
            mover = pt -> {
                Vec3 p = eye(entity, pt);
                Vec3 w = ship.toWorld(p, pt >= 1.0f ? ShipRef.Pose.TICK : ShipRef.Pose.RENDER);
                return w == null ? p : w;
            };
        } else {
            mover = pt -> eye(entity, pt);
        }
        DynamicLight.Mover direction = null;
        if (data.shape == LightShape.SPOT) {
            direction = pt -> entity instanceof LivingEntity ? entity.getViewVector(pt) : entity.getLookAngle();
        }
        float range = data.reach();
        out.add(new DynamicLight(key, kind, mover, direction, level, data.color, data.shape, data.coneDegrees, Math.max(1f, range), falloff));
        return true;
    }

    private static Vec3 eye(Entity entity, float pt) {
        Vec3 p = pt >= 1.0f ? entity.position() : entity.getPosition(pt);
        return entity instanceof ItemEntity ? p.add(0, 0.25, 0) : p.add(0, entity.getEyeHeight(), 0);
    }
}
