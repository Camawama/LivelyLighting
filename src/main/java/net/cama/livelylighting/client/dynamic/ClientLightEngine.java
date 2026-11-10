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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The client's light sources this tick: every entity the client knows that gives light by the shared rules
 * ({@link LightCalculator}: hands, armour, curios, carried block, burning, glowing, creepers, TNT, definitions, tags), the
 * forced levels and switched-off entities the server told us, the lamps of ships ({@link ShipLamps}) and contraptions, and
 * other mods' lights. Levels fade in and out as the server module's do ({@code smoothing}, {@code fade_in_rate},
 * {@code trail_decay_rate}, players and other mods' lights, every entity with {@code smoothing_all_entities}); a light's
 * reach follows its faded level (0.6.0: it took the wanted level, so a torch put away vanished at once instead of fading).
 * Point lights near each other add a merged light over them ({@link ClientClusters}, 0.6.3).
 * A light without a colour of its own takes the colour Colorful Lighting gives its source ({@link LightColors}). Positions
 * are continuous: an entity's light sits at its eye, a lamp at its block centre, and each light knows where it is at any
 * moment of the frame. Run once per client tick on the render thread; the result is an immutable list the renderers read.
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
    private final ClientClusters clusters = new ClientClusters();

    /** A light held dark while Colorful Lighting builds the slab that says where its light may go, then let up. */
    private static final class Hold {
        int age;
        /** The share of its level the light shows (1 once let up). */
        float shown;
        boolean ready;
        long lastSeen;
    }

    private final Map<Long, Hold> holds = new HashMap<>();
    /** The longest a new light waits for its slab: a light beyond the slabs never gets one. */
    private static final int HOLD_TICKS = 6;
    /** The share of its level a held light gains a tick once it is let up. */
    private static final float REVEAL_STEP = 0.5f;
    /** The API lights at the moment of the frame being drawn, by key (filled once per frame for the renderers). */
    private final Map<Long, net.camacraft.camafy.light.LightSource> apiFrame = new HashMap<>();
    /** The API lights as last given, by key, so one that stops being given fades out where it was. */
    private final Map<Long, net.camacraft.camafy.light.LightSource> apiLast = new HashMap<>();
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
        apiLast.clear();
        shipLamps.forget();
        contraptionLamps.forget();
        clusters.forget();
        holds.clear();
        ShipBlocks.forget();
        MapRefresh.forget();
        lights = Collections.emptyList();
    }

    /** How many merged lights there are now, the ones easing out included (for tests). */
    public int clusterCount() {
        return clusters.count();
    }

    /** One client tick: gathers, fades and publishes the lights. */
    public void tick(ClientLevel level) {
        LivelyConfig config = LivelyConfig.get();
        tick = level.getGameTime();
        List<DynamicLight> out = new ArrayList<>();
        if (!config.enable) {
            fades.clear();
            apiLast.clear();
            clusters.forget();
            holds.clear();
            lights = Collections.emptyList();
            return;
        }
        Falloff falloff = Falloff.parse(config.client.falloff, Falloff.DIAMOND);
        Minecraft mc = Minecraft.getInstance();
        Vec3 camera = mc.gameRenderer.getMainCamera().getPosition();
        double cull = config.light_cull_distance > 0 ? config.light_cull_distance : 192;
        int max = Math.max(1, config.max_light_sources);

        List<Entity> candidates = new ArrayList<>();
        Map<Entity, Vec3> places = new HashMap<>();
        boolean ships = Ships.available();
        for (Entity entity : level.entitiesForRendering()) {
            if (entity.isSpectator()) continue;
            if (!shouldCheck(entity, config)) continue;
            // where the entity is in the world: an item frame on a ship lives in the shipyard, millions of blocks off (0.6.1)
            Vec3 at = entity.position();
            if (ships) {
                ShipRef ship = Ships.managing(entity);
                if (ship != null) {
                    Vec3 w = ship.toWorld(at);
                    if (w != null) at = w;
                }
            }
            double dx = Math.abs(at.x - camera.x), dy = Math.abs(at.y - camera.y), dz = Math.abs(at.z - camera.z);
            if (Math.max(dx, Math.max(dy, dz)) > cull) continue;
            places.put(entity, at);
            candidates.add(entity);
        }
        candidates.sort((a, b) -> Double.compare(places.get(a).distanceToSqr(camera), places.get(b).distanceToSqr(camera)));
        int count = 0;
        for (Entity entity : candidates) {
            if (count >= max) break;
            if (collectEntity(entity, level, config, falloff, out)) count++;
        }
        shipLamps.collect(level, camera, config, falloff, out);
        contraptionLamps.collect(level, camera, config, falloff, out);
        collectApi(level, config, falloff, out);
        // lights near each other merge into one larger light (0.6.3)
        clusters.collect(config, falloff, camera, level, out);
        // a new light waits dark for the slab that keeps it behind walls (0.6.4)
        hold(config, out);
        // the ships' blocks for the volume the light is propagated through (Colorful Lighting's worker reads the snapshot)
        ShipBlocks.snapshot(level, shipLamps);
        // fades that were not wanted this tick run out
        fades.entrySet().removeIf(e -> tick - e.getValue().lastSeen > 1200);
        lights = Collections.unmodifiableList(out);
    }

    /**
     * New lights wait for their slab (0.6.4, {@code client.hold_new_lights}). Colorful Lighting learns of a light the frame after
     * it is first given and builds its slab on a worker; until that is uploaded the shaders drew the light with nothing in its
     * way, so a torch taken out behind a wall lit the room beyond for a tick or two. A new light is now given at a level that
     * shows nothing, with its own reach (so its slab is the one it keeps), until Colorful Lighting holds its slab or
     * {@link #HOLD_TICKS} have passed, and then let up over two ticks, drawn between ticks. Where no slabs are used (the
     * lightmap renderer, transmission off, no Colorful Lighting) nothing waits. The merged lights do the same themselves.
     */
    private void hold(LivelyConfig config, List<DynamicLight> out) {
        if (!config.client.hold_new_lights) {
            holds.clear();
            return;
        }
        for (int i = 0; i < out.size(); i++) {
            DynamicLight light = out.get(i);
            if (light.kind == DynamicLight.Kind.CLUSTER) continue;
            Hold hold = holds.get(light.ownerKey);
            if (hold == null) {
                holds.put(light.ownerKey, hold = new Hold());
                if (ColorfulBridge.slabReady(light.ownerKey)) {
                    hold.ready = true;
                    hold.shown = 1f;
                }
            }
            hold.lastSeen = tick;
            if (!hold.ready) {
                if (!ColorfulBridge.slabReady(light.ownerKey) && ++hold.age <= HOLD_TICKS) {
                    out.set(i, light.withLevel(Math.min(light.level, ClientClusters.HIDDEN), null, 0f));
                    continue;
                }
                hold.ready = true;
            }
            if (hold.shown >= 1f) continue;
            final float was = hold.shown, now = Math.min(1f, was + REVEAL_STEP);
            hold.shown = now;
            final DynamicLight full = light;
            out.set(i, light.withLevel(light.level * now, pt -> full.levelAtFrame(pt) * (pt >= 1.0f ? now : was + (now - was) * Math.max(0f, pt)),
                    light.glowLevel * now));
        }
        holds.values().removeIf(h -> h.lastSeen != tick);
    }

    /**
     * Other mods' lights through Camafy's DynamicLights (kart headlights, an NPC's torch), faded like the mod's own: a light
     * given this tick fades in to its level; one no longer given fades out where it was last seen.
     */
    private void collectApi(ClientLevel level, LivelyConfig config, Falloff falloff, List<DynamicLight> out) {
        if (!net.camacraft.camafy.light.DynamicLights.hasProviders() && apiLast.isEmpty()) return;
        List<net.camacraft.camafy.light.LightSource> sources = new ArrayList<>();
        net.camacraft.camafy.light.DynamicLights.collect(level, 1.0f, sources::add);
        Map<Long, net.camacraft.camafy.light.LightSource> now = new HashMap<>();
        for (net.camacraft.camafy.light.LightSource src : sources) now.put(src.key(), src);
        // the ones that went away this tick: faded out at their last position
        for (Map.Entry<Long, net.camacraft.camafy.light.LightSource> e : apiLast.entrySet()) {
            if (now.containsKey(e.getKey())) continue;
            net.camacraft.camafy.light.LightSource last = e.getValue();
            float lvl = fadeLevel(last.key() ^ API_KEY_BIT, 0f, config.smoothing, config);
            if (lvl > 0) {
                now.put(last.key(), last);
                addApi(level, last, lvl, falloff, out, false);
            }
        }
        for (net.camacraft.camafy.light.LightSource src : sources) {
            float lvl = fadeLevel(src.key() ^ API_KEY_BIT, src.level(), config.smoothing, config);
            if (lvl <= 0) continue;
            addApi(level, src, lvl, falloff, out, true);
        }
        apiLast.clear();
        apiLast.putAll(now);
    }

    private void addApi(ClientLevel level, net.camacraft.camafy.light.LightSource src, float lvl, Falloff falloff, List<DynamicLight> out, boolean live) {
        long key = src.key() ^ API_KEY_BIT;
        final Vec3 tickPos = src.pos();
        final Vec3 tickDir = src.direction();
        DynamicLight.Mover mover = !live ? pt -> tickPos : pt -> {
            net.camacraft.camafy.light.LightSource at = apiAt(level, pt, src.key());
            return at == null ? tickPos : at.pos();
        };
        DynamicLight.Mover dir = !src.isSpot() ? null : !live ? pt -> tickDir : pt -> {
            net.camacraft.camafy.light.LightSource at = apiAt(level, pt, src.key());
            return at == null || at.direction() == null ? tickDir : at.direction();
        };
        // the reach follows the faded level for a point; a spot keeps its range and dims
        float range = src.range() > 0f ? src.range() : src.isSpot() ? 32f : lvl;
        // the carrier's glow fades with the light
        float glow = src.glows() && src.level() > 0f ? src.glowLevel() * Math.min(1f, lvl / src.level()) : 0f;
        out.add(new DynamicLight(key, DynamicLight.Kind.API, mover, dir, lvl, src.rgb(), src.isSpot() ? LightShape.SPOT : LightShape.POINT,
                src.isSpot() ? src.outerDegrees() : 0f, Math.max(1f, range), falloff, glow > 0f ? src.glowEntity() : -1, glow));
    }

    /**
     * How bright an entity is drawn at least by the lights it carries that ask for it (0.6.2; Camafy's {@code LightSource.glow}),
     * and whoever rides such an entity with it; 0 for none. Render thread, per entity drawn.
     */
    public int glowOf(Entity entity) {
        List<DynamicLight> ls = lights;
        if (ls.isEmpty()) return 0;
        int id = entity.getId();
        float best = 0f;
        for (DynamicLight l : ls) {
            if (l.glowEntity < 0 || l.glowLevel <= best) continue;
            if (l.glowEntity == id || rides(entity, l.glowEntity)) best = l.glowLevel;
        }
        return Math.round(best);
    }

    /**
     * The glow of {@link #glowOf} with its light's colour (0.6.5): {@code level << 24 | 0xRRGGBB}, white for a light without a
     * colour of its own; 0 for none.
     */
    public int glowColorOf(Entity entity) {
        List<DynamicLight> ls = lights;
        if (ls.isEmpty()) return 0;
        int id = entity.getId();
        float best = 0f;
        int rgb = 0xFFFFFF;
        for (DynamicLight l : ls) {
            if (l.glowEntity < 0 || l.glowLevel <= best) continue;
            if (l.glowEntity == id || rides(entity, l.glowEntity)) {
                best = l.glowLevel;
                rgb = l.rgb < 0 ? 0xFFFFFF : l.rgb & 0xFFFFFF;
            }
        }
        int level = Math.min(15, Math.round(best));
        return level <= 0 ? 0 : level << 24 | rgb;
    }

    /**
     * The keys of the lights an entity is not to be lit by (0.6.5): the ones that glow on it or on what it rides (a kart's
     * headlights and its driver), and the beams it holds itself. They start at the entity and point away from it; lit by them,
     * a flashlight's holder flashed bright whenever the cone's edge passed over the body. Null for none.
     */
    @Nullable
    public long[] ownKeysOf(Entity entity) {
        List<DynamicLight> ls = lights;
        if (ls.isEmpty()) return null;
        int id = entity.getId();
        long[] own = null;
        int n = 0;
        for (DynamicLight l : ls) {
            boolean mine = l.glowEntity >= 0 && (l.glowEntity == id || rides(entity, l.glowEntity));
            if (!mine && l.isSpot() && (l.kind == DynamicLight.Kind.ENTITY || l.kind == DynamicLight.Kind.FORCED) && (l.ownerKey >>> 8) == id) mine = true;
            if (!mine) continue;
            if (own == null) own = new long[2];
            else if (n == own.length) own = java.util.Arrays.copyOf(own, n * 2);
            own[n++] = l.ownerKey;
        }
        return own == null ? null : n == own.length ? own : java.util.Arrays.copyOf(own, n);
    }

    private static boolean rides(Entity entity, int vehicleId) {
        for (Entity v = entity.getVehicle(); v != null; v = v.getVehicle()) if (v.getId() == vehicleId) return true;
        return false;
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
        List<DynamicLight> all = lights;
        double best = 0;
        if (all.size() >= INDEX_FROM) {
            // many lights: only the ones whose reach touches this 16-block column (0.6.6)
            Columns columns = index;
            if (columns == null || columns.of != all) index = columns = new Columns(all);
            DynamicLight[] near = columns.at(pos.x, pos.z);
            if (near != null) {
                for (DynamicLight light : near) {
                    double l = light.levelAt(pos.x, pos.y, pos.z);
                    if (l > best) best = l;
                }
            }
            for (DynamicLight light : columns.wide) {
                double l = light.levelAt(pos.x, pos.y, pos.z);
                if (l > best) best = l;
            }
            return (float) Math.min(15, best);
        }
        for (DynamicLight light : all) {
            double l = light.levelAt(pos.x, pos.y, pos.z);
            if (l > best) best = l;
        }
        return (float) Math.min(15, best);
    }

    /**
     * From this many lights on, {@link #levelAt} looks the lights up by column (0.6.6): the game asks the client's raw
     * brightness some hundreds of times a frame, and every question went through every light (a tenth of the render thread
     * with 256 lights). {@code -Dlivelylighting.lightIndexFrom=100000} for the way before.
     */
    private static final int INDEX_FROM = Math.max(1, Integer.getInteger("livelylighting.lightIndexFrom", 24));
    private volatile Columns index;

    /** The lights of one tick by the 16-block columns their reach touches; built at the tick's first question. */
    private static final class Columns {
        final List<DynamicLight> of;
        /** Lights that reach further than 128 blocks: asked every time. */
        final List<DynamicLight> wide = new ArrayList<>(0);
        private final it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<DynamicLight[]> cells = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();

        Columns(List<DynamicLight> lights) {
            of = lights;
            it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<List<DynamicLight>> lists = new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<>();
            for (DynamicLight light : lights) {
                double reach = Math.max(0.0, light.range);
                if (!(reach <= 128.0)) {
                    wide.add(light);
                    continue;
                }
                int x0 = cell(light.pos.x - reach), x1 = cell(light.pos.x + reach), z0 = cell(light.pos.z - reach), z1 = cell(light.pos.z + reach);
                for (int x = x0; x <= x1; x++) {
                    for (int z = z0; z <= z1; z++) lists.computeIfAbsent(key(x, z), k -> new ArrayList<>(4)).add(light);
                }
            }
            for (var e : lists.long2ObjectEntrySet()) cells.put(e.getLongKey(), e.getValue().toArray(new DynamicLight[0]));
        }

        @Nullable
        DynamicLight[] at(double x, double z) {
            return cells.get(key(cell(x), cell(z)));
        }

        private static int cell(double v) {
            return (int) Math.floor(v) >> 4;
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }
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
            return add(entity, 9, LightCalculator.EMPTY_LIGHT_DATA.withLevel(forced), null, config, falloff, out, DynamicLight.Kind.FORCED);
        }
        if (config.experimental.cluster_growing && entity instanceof LivingEntity living) {
            any |= add(entity, 1, LightCalculator.getItemLightLevel(living.getMainHandItem(), entity, level, config), living.getMainHandItem(), config, falloff, out, DynamicLight.Kind.ENTITY);
            any |= add(entity, 2, LightCalculator.getItemLightLevel(living.getOffhandItem(), entity, level, config), living.getOffhandItem(), config, falloff, out, DynamicLight.Kind.ENTITY);
            int slot = 3;
            for (EquipmentSlot s : EquipmentSlot.values()) {
                if (s.getType() == EquipmentSlot.Type.ARMOR) {
                    any |= add(entity, slot++, LightCalculator.getItemLightLevel(living.getItemBySlot(s), entity, level, config), living.getItemBySlot(s), config, falloff, out, DynamicLight.Kind.ENTITY);
                }
            }
            if (config.curios_lighting && CuriosLights.loaded()) {
                LightData curios = LightCalculator.EMPTY_LIGHT_DATA;
                ItemStack brightest = null;
                for (ItemStack worn : CuriosLights.worn(living)) {
                    LightData one = LightCalculator.getItemLightLevel(worn, entity, level, config);
                    if (one.level > curios.level) {
                        curios = one;
                        brightest = worn;
                    }
                }
                any |= add(entity, 7, curios, brightest, config, falloff, out, DynamicLight.Kind.ENTITY);
            }
            if (entity instanceof EnderMan enderman && enderman.getCarriedBlock() != null) {
                ItemStack carried = new ItemStack(enderman.getCarriedBlock().getBlock());
                any |= add(entity, 8, LightCalculator.getItemLightLevel(carried, entity, level, config), carried, config, falloff, out, DynamicLight.Kind.ENTITY);
            }
            any |= add(entity, 0, LightCalculator.getEntityLightLevel(entity, level, config), null, config, falloff, out, DynamicLight.Kind.ENTITY);
            return any;
        }
        LightData best = LightCalculator.getBestLightLevel(entity, level, config);
        // a frame's or a dropped item's colour is the item's; a mob's is its own, then what it holds, then fire
        ItemStack shown = entity instanceof ItemFrame frame ? frame.getItem() : entity instanceof ItemEntity item ? item.getItem() : null;
        return add(entity, 0, best, shown, config, falloff, out, entity instanceof ItemEntity ? DynamicLight.Kind.ITEM_ENTITY : DynamicLight.Kind.ENTITY);
    }

    /**
     * Fades one source toward its wanted level and, if it still shines, adds its light. {@code stack} is the item the light
     * comes from when one slot is known (its colour), else the entity's colour as Colorful Lighting resolves it.
     */
    private boolean add(Entity entity, int slot, LightData data, @Nullable ItemStack stack, LivelyConfig config, Falloff falloff, List<DynamicLight> out, DynamicLight.Kind kind) {
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
        LightShape shape = data.shape;
        float cone = data.coneDegrees;
        if (shape == LightShape.SPOT) {
            direction = pt -> entity instanceof LivingEntity ? entity.getViewVector(pt) : entity.getLookAngle();
        } else if (ship != null && entity instanceof ItemFrame frame && config.client.frames_on_ships_are_headlights) {
            // a lit item in a frame on a ship is a headlight: a cone along the frame's facing, turned with the ship (0.6.1)
            Vec3 local = Vec3.atLowerCornerOf(frame.getDirection().getNormal());
            direction = pt -> {
                Vec3 d = ship.dirToWorld(local);
                return d == null ? local : d;
            };
            shape = LightShape.SPOT;
            cone = LightShape.DEFAULT_CONE_DEGREES;
        }
        // the reach follows the faded level for a point (the wanted level is 0 while a light fades out); a spot keeps its range
        float range = data.range > 0 ? data.range : shape == LightShape.SPOT ? LightShape.DEFAULT_SPOT_RANGE : level;
        // a definition without a colour takes Colorful Lighting's for the stack, or for the entity (its own, what it holds, fire)
        int rgb = data.color;
        if (rgb < 0 && wanted > 0) {
            rgb = stack != null && !stack.isEmpty() ? LightColors.ofItem(stack) : LightColors.ofEntity(entity);
            if (rgb >= 0) colours.put(key, rgb);
        } else if (rgb < 0) {
            Integer kept = colours.get(key);
            if (kept != null) rgb = kept; // a light fading out keeps the colour it had
        }
        if (wanted <= 0 && fade == null) colours.remove(key);
        // whoever holds a beam is drawn a little lit in its colour (0.6.5, client.spot_holder_glow): the beam points away from them
        float holderGlow = shape == LightShape.SPOT && direction != null && entity instanceof LivingEntity
                ? Math.min(Math.max(0, Math.min(15, config.client.spot_holder_glow)), level) : 0f;
        out.add(new DynamicLight(key, kind, mover, direction, level, rgb, shape, cone, Math.max(1f, range), falloff, holderGlow > 0f ? entity.getId() : -1, holderGlow));
        return true;
    }

    /** The colour each light last had, so one fading out (its stack gone) keeps it. */
    private final Map<Long, Integer> colours = new HashMap<>();

    private static Vec3 eye(Entity entity, float pt) {
        Vec3 p = pt >= 1.0f ? entity.position() : entity.getPosition(pt);
        return entity instanceof ItemEntity ? p.add(0, 0.25, 0) : p.add(0, entity.getEyeHeight(), 0);
    }
}
