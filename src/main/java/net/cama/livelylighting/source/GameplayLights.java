package net.cama.livelylighting.source;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.camacraft.camafy.light.DynamicLights;
import net.camacraft.camafy.light.LightSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dynamic light as the world's own light (0.6.0): with the client drawing the light and no light block placed, the server's
 * world saw none of it, so mobs spawned in a torch's circle and snow stayed under a held lantern. This gives the server a
 * light level per level and tick from every carried, worn or burning light and the other mods' lights ({@code gameplay_light},
 * off in {@code server_blocks} mode, where the blocks are real light), a point light of the source's level losing a level per
 * block of Manhattan distance, and answers it wherever the world is asked how bright a place is: the block light layer
 * ({@code Level.getBrightness}: mob spawning, snow and ice melting) and the raw brightness ({@code getMaxLocalRawBrightness}:
 * crops, many mods) through {@code LevelLightEngineMixin}, and Camafy's {@code DynamicLights.lightAt} on both sides (the client
 * answers from what it draws). Walls don't stop it.
 */
public final class GameplayLights {
    /** The client's answer for its own level (what it draws), set by the client module. */
    public interface ClientAnswer {
        float levelAt(Level level, Vec3 pos);
    }

    private static final int ENTITY_SCAN_TICKS = 4;
    private static final double ENTITY_RANGE = 48.0;
    private static volatile @Nullable ClientAnswer clientAnswer;
    private static final Map<LevelLightEngine, ServerLevel> SERVER_ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LevelLightEngine, Level> CLIENT_ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    /** Per level: x, y, z, level in fours; replaced every tick, read from any thread. */
    private static final Map<ResourceKey<Level>, float[]> LIGHTS = new ConcurrentHashMap<>();
    private static final Map<ResourceKey<Level>, float[]> ENTITY_LIGHTS = new ConcurrentHashMap<>();
    private static final float[] NONE = new float[0];

    private GameplayLights() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) SERVER_ENGINES.put(level.getLightEngine(), level);
            else if (e.getLevel() instanceof Level level && level.isClientSide) CLIENT_ENGINES.put(level.getLightEngine(), level);
        });
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                SERVER_ENGINES.remove(level.getLightEngine());
                LIGHTS.remove(level.dimension());
                ENTITY_LIGHTS.remove(level.dimension());
            } else if (e.getLevel() instanceof Level level) {
                CLIENT_ENGINES.remove(level.getLightEngine());
            }
        });
        MinecraftForge.EVENT_BUS.addListener(GameplayLights::onLevelTick);
        // other mods ask Camafy how much dynamic light reaches a place: the server from this, the client from what it draws
        DynamicLights.setQuery((level, pos) -> {
            if (level instanceof ServerLevel server) return levelAt(server, pos.x, pos.y, pos.z);
            ClientAnswer c = clientAnswer;
            return c == null ? 0f : c.levelAt(level, pos);
        });
    }

    public static void setClientAnswer(@Nullable ClientAnswer answer) {
        clientAnswer = answer;
    }

    /** Whether the server answers dynamic light as the world's: the key on, and no light blocks placed for everyone. */
    public static boolean active(LivelyConfig config) {
        return config.enable && config.gameplay_light && LightModes.current() != LightMode.SERVER_BLOCKS;
    }

    private static void onLevelTick(TickEvent.LevelTickEvent event) {
        if (event.phase != TickEvent.Phase.END || !(event.level instanceof ServerLevel level)) return;
        LivelyConfig config = LivelyConfig.get();
        ResourceKey<Level> dim = level.dimension();
        if (!active(config)) {
            if (LIGHTS.containsKey(dim)) {
                LIGHTS.remove(dim);
                ENTITY_LIGHTS.remove(dim);
            }
            return;
        }
        List<float[]> found = new ArrayList<>();
        List<Player> players = new ArrayList<>(level.players());
        for (Player p : players) {
            if (p.isSpectator()) continue;
            LightData data = LightCalculator.getBestLightLevel(p, level, config);
            if (data.level > 0) found.add(cell(p.getX(), p.getEyeY(), p.getZ(), data.level));
        }
        // the other entities every few ticks: a mob on fire, a dropped torch, an item frame with a lantern
        float[] entities = ENTITY_LIGHTS.get(dim);
        if (entities == null || level.getGameTime() % ENTITY_SCAN_TICKS == 0) {
            List<float[]> scanned = new ArrayList<>();
            if (!players.isEmpty()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof Player || entity.isSpectator() || !shouldCheck(entity, config)) continue;
                    boolean near = false;
                    for (Player p : players) {
                        if (Math.max(Math.abs(p.getX() - entity.getX()), Math.max(Math.abs(p.getY() - entity.getY()), Math.abs(p.getZ() - entity.getZ()))) <= ENTITY_RANGE) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) continue;
                    LightData data = LightCalculator.getBestLightLevel(entity, level, config);
                    if (data.level > 0) scanned.add(cell(entity.getX(), entity.getEyeY(), entity.getZ(), data.level));
                }
            }
            entities = pack(scanned);
            ENTITY_LIGHTS.put(dim, entities);
        }
        if (DynamicLights.hasProviders()) {
            List<LightSource> api = new ArrayList<>();
            DynamicLights.collect(level, 1.0f, api::add);
            for (LightSource src : api) {
                if (src.level() <= 0f) continue;
                found.add(cell(src.pos().x, src.pos().y, src.pos().z, Math.min(15f, src.level())));
            }
        }
        float[] all = new float[found.size() * 4 + entities.length];
        int i = 0;
        for (float[] f : found) {
            all[i++] = f[0];
            all[i++] = f[1];
            all[i++] = f[2];
            all[i++] = f[3];
        }
        System.arraycopy(entities, 0, all, i, entities.length);
        LIGHTS.put(dim, all.length == 0 ? NONE : all);
    }

    /**
     * A source as the light blocks had it: the centre of the cell it is in, so a block beside the source's cell is two levels
     * down as vanilla's propagation makes it (from the exact eye a block beside it came to 11.9 and the snow there never melted).
     */
    private static float[] cell(double x, double y, double z, float level) {
        return new float[]{(float) Math.floor(x) + 0.5f, (float) Math.floor(y) + 0.5f, (float) Math.floor(z) + 0.5f, (float) Math.floor(level)};
    }

    private static float[] pack(List<float[]> lights) {
        if (lights.isEmpty()) return NONE;
        float[] out = new float[lights.size() * 4];
        int i = 0;
        for (float[] f : lights) {
            out[i++] = f[0];
            out[i++] = f[1];
            out[i++] = f[2];
            out[i++] = f[3];
        }
        return out;
    }

    private static boolean shouldCheck(Entity entity, LivelyConfig config) {
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof PrimedTnt || entity instanceof ItemFrame) return true;
        return config.enable_entity_lights;
    }

    /** The dynamic light (0 to 15) at a point of a server level this tick: the brightest source's level less its Manhattan distance. */
    public static float levelAt(ServerLevel level, double x, double y, double z) {
        float[] lights = LIGHTS.get(level.dimension());
        if (lights == null || lights.length == 0) return 0f;
        float best = 0f;
        for (int i = 0; i < lights.length; i += 4) {
            float l = lights[i + 3] - (float) (Math.abs(x - lights[i]) + Math.abs(y - lights[i + 1]) + Math.abs(z - lights[i + 2]));
            if (l > best) best = l;
        }
        return Math.min(15f, best);
    }

    /** The dynamic light at a block for a light engine: a server level's from this tick's sources, a client level's from what it draws. */
    public static int levelAt(LevelLightEngine engine, BlockPos pos) {
        ServerLevel server = SERVER_ENGINES.get(engine);
        if (server != null) return (int) levelAt(server, pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
        if (CLIENT_ENGINES.isEmpty()) return 0;
        Level client = CLIENT_ENGINES.get(engine);
        ClientAnswer c = clientAnswer;
        if (client == null || c == null) return 0;
        return (int) c.levelAt(client, Vec3.atCenterOf(pos));
    }

    /**
     * Whether this engine's block layer is answered with dynamic light: a server level's while {@code gameplay_light} is on, and a
     * client level's while {@code client.world_light} is on and the client draws (mods that read the world's light where the
     * player stands, such as Stellar View's light pollution, see the carried light; the meshes and Colorful Lighting's samples
     * don't read this layer).
     */
    public static boolean wrapsEngine(LevelLightEngine engine) {
        if (SERVER_ENGINES.containsKey(engine)) return active(LivelyConfig.get());
        if (clientAnswer == null || CLIENT_ENGINES.isEmpty() || !CLIENT_ENGINES.containsKey(engine)) return false;
        LivelyConfig config = LivelyConfig.get();
        return config.enable && config.client.world_light;
    }

    /** The block light layer of a server level, raised by the dynamic light where it reaches; everything else is the engine's own. */
    public static final class BlockListener implements LayerLightEventListener {
        private final LevelLightEngine engine;
        private final LayerLightEventListener inner;

        public BlockListener(LevelLightEngine engine, LayerLightEventListener inner) {
            this.engine = engine;
            this.inner = inner;
        }

        public LayerLightEventListener inner() {
            return inner;
        }

        @Override
        public int getLightValue(BlockPos pos) {
            int own = inner.getLightValue(pos);
            if (own >= 15) return own;
            return Math.max(own, GameplayLights.levelAt(engine, pos));
        }

        @Override
        @Nullable
        public DataLayer getDataLayerData(SectionPos pos) {
            return inner.getDataLayerData(pos);
        }

        @Override
        public void checkBlock(BlockPos pos) {
            inner.checkBlock(pos);
        }

        @Override
        public boolean hasLightWork() {
            return inner.hasLightWork();
        }

        @Override
        public int runLightUpdates() {
            return inner.runLightUpdates();
        }

        @Override
        public void updateSectionStatus(SectionPos pos, boolean notReady) {
            inner.updateSectionStatus(pos, notReady);
        }

        @Override
        public void setLightEnabled(ChunkPos pos, boolean enabled) {
            inner.setLightEnabled(pos, enabled);
        }

        @Override
        public void propagateLightSources(ChunkPos pos) {
            inner.propagateLightSources(pos);
        }
    }
}
