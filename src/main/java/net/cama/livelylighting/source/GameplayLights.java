package net.cama.livelylighting.source;

import it.unimi.dsi.fastutil.ints.IntArrayFIFOQueue;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.dynamiclighting.engine.LightData;
import net.camacraft.camafy.light.DynamicLights;
import net.camacraft.camafy.light.LightSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.lighting.LayerLightEventListener;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.LevelEvent;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
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
 * answers from what it draws). With {@code experimental.cluster_growing} lights near each other also count as the merged
 * light the client draws over them ({@link LightClusters}, 0.6.3).
 *
 * <p>The light stops at walls (0.6.4, {@code gameplay_light_occlusion}): 0.6.0 counted it straight through everything, so a
 * torch carried along a corridor kept mobs from spawning in the cave behind its wall. Each light now has a field of the
 * levels a placed lamp in its cell would give the blocks round it, spread the way vanilla spreads block light (a level a
 * block, more through leaves and water, none through an opaque block, the faces of slabs and stairs as vanilla judges
 * them), and a place gets the light's level there less what the way round cost. A field is filled when something first asks
 * about a place its light could reach, from the server thread, at most {@link #FILLS_PER_TICK} a tick, and again when it is a
 * second old; an answer asked from another thread, or before its field's turn, is the straight count. A spot light (another
 * mod's, or a held flashlight) counts inside its cone only, dimming along its beam as the client draws it, and where a
 * straight line from it is clear. A ship's blocks are not in the world and stop nothing.
 */
public final class GameplayLights {
    /** The client's answer for its own level (what it draws), set by the client module. */
    public interface ClientAnswer {
        float levelAt(Level level, Vec3 pos);
    }

    private static final int ENTITY_SCAN_TICKS = 4;
    private static final double ENTITY_RANGE = 48.0;
    /** Fields filled per level and tick at most; the rest answer with what they have. */
    private static final int FILLS_PER_TICK = 6;
    /** A field is filled again when it is this old (ticks): blocks placed and broken round a light that stays. */
    private static final int FIELD_TICKS = 20;
    /** The farthest a field reaches: the strongest merged light. */
    private static final int MAX_REACH = (int) LightClusters.MAX_STRENGTH;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static volatile @Nullable ClientAnswer clientAnswer;
    private static final Map<LevelLightEngine, ServerLevel> SERVER_ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<LevelLightEngine, Level> CLIENT_ENGINES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ResourceKey<Level>, Dim> DIMS = new ConcurrentHashMap<>();
    private static final Src[] NONE = new Src[0];

    /** One light the server counts this tick. */
    private static final class Src {
        /** Where it is (the eye, a lamp's middle). */
        final double px, py, pz;
        /** The cell it is in. */
        final int cx, cy, cz;
        final float level;
        final boolean spot;
        final double dx, dy, dz;
        final float range, outerCos, innerCos;
        /** Where its light goes, once something asked; null until then. */
        volatile @Nullable Field field;

        Src(double x, double y, double z, float level) {
            this(x, y, z, level, null, 0f, 0f);
        }

        Src(double x, double y, double z, float level, @Nullable Vec3 direction, float outerDegrees, float range) {
            this.px = x;
            this.py = y;
            this.pz = z;
            this.cx = (int) Math.floor(x);
            this.cy = (int) Math.floor(y);
            this.cz = (int) Math.floor(z);
            this.level = level;
            this.spot = direction != null && direction.lengthSqr() > 1.0e-8;
            Vec3 d = spot ? direction.normalize() : Vec3.ZERO;
            this.dx = d.x;
            this.dy = d.y;
            this.dz = d.z;
            this.range = range;
            this.outerCos = (float) Math.cos(Math.toRadians(outerDegrees * 0.5));
            this.innerCos = (float) Math.cos(Math.toRadians(outerDegrees * LightShape.INNER_SHARE * 0.5));
        }

        long cell() {
            return BlockPos.asLong(cx, cy, cz);
        }
    }

    /** The levels a lamp of {@link #level} in the middle cell gives the cells round it; 0 where its light doesn't come. */
    private static final class Field {
        final int cx, cy, cz, reach, side, level;
        final byte[] got;
        final long built;

        Field(int cx, int cy, int cz, int reach, int level, byte[] got, long built) {
            this.cx = cx;
            this.cy = cy;
            this.cz = cz;
            this.reach = reach;
            this.side = 2 * reach + 1;
            this.level = level;
            this.got = got;
            this.built = built;
        }

        /** The levels the way to a block costs over the straight count; infinite where the light doesn't come. */
        float loss(int bx, int by, int bz) {
            int x = bx - cx + reach, y = by - cy + reach, z = bz - cz + reach;
            if (x < 0 || y < 0 || z < 0 || x >= side || y >= side || z >= side) return Float.POSITIVE_INFINITY;
            int g = got[(z * side + y) * side + x];
            if (g <= 0) return Float.POSITIVE_INFINITY;
            return Math.max(0, level - (Math.abs(bx - cx) + Math.abs(by - cy) + Math.abs(bz - cz)) - g);
        }
    }

    /** A level's lights: published every tick, read from any thread; the rest is the server thread's. */
    private static final class Dim {
        volatile Src[] lights = NONE;
        volatile boolean occlusion;
        @Nullable List<Src> entities;
        Map<Long, Field> fields = new HashMap<>();
        int fills;
    }

    private GameplayLights() {}

    public static void init() {
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Load e) -> {
            if (e.getLevel() instanceof ServerLevel level) SERVER_ENGINES.put(level.getLightEngine(), level);
            else if (e.getLevel() instanceof Level level && level.isClientSide) CLIENT_ENGINES.put(level.getLightEngine(), level);
        });
        MinecraftForge.EVENT_BUS.addListener((LevelEvent.Unload e) -> {
            if (e.getLevel() instanceof ServerLevel level) {
                SERVER_ENGINES.remove(level.getLightEngine());
                DIMS.remove(level.dimension());
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
        ResourceKey<Level> dimension = level.dimension();
        if (!active(config)) {
            DIMS.remove(dimension);
            return;
        }
        Dim dim = DIMS.computeIfAbsent(dimension, k -> new Dim());
        boolean occlusion = config.gameplay_light_occlusion;
        List<Src> found = new ArrayList<>();
        List<Player> players = new ArrayList<>(level.players());
        for (Player p : players) {
            if (p.isSpectator()) continue;
            LightData data = LightCalculator.getBestLightLevel(p, level, config);
            if (data.level > 0) found.add(source(p, new Vec3(p.getX(), p.getEyeY(), p.getZ()), data, occlusion));
        }
        // the other entities every few ticks: a mob on fire, a dropped torch, an item frame with a lantern
        List<Src> entities = dim.entities;
        if (entities == null || level.getGameTime() % ENTITY_SCAN_TICKS == 0) {
            entities = new ArrayList<>();
            if (!players.isEmpty()) {
                for (Entity entity : level.getAllEntities()) {
                    if (entity instanceof Player || entity.isSpectator() || !shouldCheck(entity, config)) continue;
                    boolean near = false;
                    // an entity in a ship's shipyard (an item frame on a ship) is where the ship is
                    Vec3 at = entity.position();
                    if (net.camacraft.camafy.compat.vs.Ships.available()) {
                        net.camacraft.camafy.compat.vs.ShipRef ship = net.camacraft.camafy.compat.vs.Ships.managing(entity);
                        if (ship != null) {
                            Vec3 w = ship.toWorld(at);
                            if (w != null) at = w;
                        }
                    }
                    for (Player p : players) {
                        if (Math.max(Math.abs(p.getX() - at.x), Math.max(Math.abs(p.getY() - at.y), Math.abs(p.getZ() - at.z))) <= ENTITY_RANGE) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) continue;
                    LightData data = LightCalculator.getBestLightLevel(entity, level, config);
                    if (data.level > 0) entities.add(source(entity, at.add(0, entity.getEyeHeight(), 0), data, occlusion));
                }
            }
            dim.entities = entities;
        }
        found.addAll(entities);
        if (DynamicLights.hasProviders()) {
            List<LightSource> api = new ArrayList<>();
            DynamicLights.collect(level, 1.0f, api::add);
            for (LightSource src : api) {
                if (src.level() <= 0f) continue;
                float lvl = (float) Math.floor(Math.min(15f, src.level()));
                if (occlusion && src.isSpot()) {
                    found.add(new Src(src.pos().x, src.pos().y, src.pos().z, lvl, src.direction(), src.outerDegrees(),
                            src.range() > 0f ? src.range() : LightShape.DEFAULT_SPOT_RANGE));
                } else {
                    found.add(new Src(src.pos().x, src.pos().y, src.pos().z, lvl));
                }
            }
        }
        // lights near each other count as the merged light the client draws over them (0.6.3)
        if (config.experimental.cluster_growing && config.experimental.cluster_merge_distance > 0) {
            List<Src> points = new ArrayList<>();
            for (Src s : found) if (!s.spot) points.add(s);
            int n = Math.min(LightClusters.MAX_LIGHTS, points.size());
            if (n >= 2) {
                double[] x = new double[n], y = new double[n], z = new double[n];
                float[] levels = new float[n];
                Vec3[] at = new Vec3[n];
                for (int i = 0; i < n; i++) {
                    Src s = points.get(i);
                    x[i] = s.px;
                    y[i] = s.py;
                    z[i] = s.pz;
                    levels[i] = s.level;
                    at[i] = new Vec3(s.px, s.py, s.pz);
                }
                for (LightClusters.Merged m : LightClusters.merge(x, y, z, levels, null, n, config.experimental.cluster_merge_distance,
                        LightClusters.cap(config.experimental.max_influence_radius), (i, j) -> LightSight.clear(level, at[i], at[j], false), false)) {
                    found.add(new Src(m.x, m.y, m.z, m.level));
                }
            }
        }
        // a light that stays in its cell at its level keeps its field
        Map<Long, Field> kept = new HashMap<>();
        if (occlusion) {
            for (Src s : found) {
                if (s.spot) continue;
                Field f = s.field != null ? s.field : dim.fields.get(s.cell());
                if (f == null || f.level != (int) s.level) continue;
                s.field = f;
                kept.put(s.cell(), f);
            }
        }
        dim.fields = kept;
        dim.fills = 0;
        dim.occlusion = occlusion;
        dim.lights = found.isEmpty() ? NONE : found.toArray(NONE);
    }

    /** A source from an entity's light: a point, or with occlusion a held spot light along where its holder looks. */
    private static Src source(Entity entity, Vec3 at, LightData data, boolean occlusion) {
        if (occlusion && data.shape == LightShape.SPOT) {
            Vec3 look = entity instanceof LivingEntity ? entity.getViewVector(1.0f) : entity.getLookAngle();
            return new Src(at.x, at.y, at.z, data.level, look, data.coneDegrees > 0 ? data.coneDegrees : LightShape.DEFAULT_CONE_DEGREES,
                    data.range > 0 ? data.range : LightShape.DEFAULT_SPOT_RANGE);
        }
        return new Src(at.x, at.y, at.z, data.level);
    }

    private static boolean shouldCheck(Entity entity, LivelyConfig config) {
        if (entity instanceof LivingEntity || entity instanceof ItemEntity || entity instanceof PrimedTnt || entity instanceof ItemFrame) return true;
        return config.enable_entity_lights;
    }

    /**
     * The dynamic light (0 to 15) at a point of a server level this tick: the most any source gives it. A point light counts
     * from the middle of the cell it is in, as the light blocks had it (a block beside the source's cell is a level down, as
     * vanilla's propagation makes it; from the exact eye a block beside it came to 11.9 and the snow there never melted),
     * less what the way round the blocks costs.
     */
    public static float levelAt(ServerLevel level, double x, double y, double z) {
        Dim dim = DIMS.get(level.dimension());
        if (dim == null) return 0f;
        Src[] lights = dim.lights;
        if (lights.length == 0) return 0f;
        boolean occlusion = dim.occlusion;
        boolean serverThread = occlusion && level.getServer().isSameThread();
        int bx = (int) Math.floor(x), by = (int) Math.floor(y), bz = (int) Math.floor(z);
        float best = 0f;
        for (Src s : lights) {
            if (s.spot) {
                double ox = x - s.px, oy = y - s.py, oz = z - s.pz;
                double d2 = ox * ox + oy * oy + oz * oz;
                if (d2 > s.range * s.range) continue;
                double d = Math.sqrt(d2);
                float l = s.level;
                if (d > 1.0e-6) {
                    double cos = (ox * s.dx + oy * s.dy + oz * s.dz) / d;
                    double cone = cos <= s.outerCos ? 0.0 : s.innerCos <= s.outerCos ? 1.0 : Math.min(1.0, (cos - s.outerCos) / (s.innerCos - s.outerCos));
                    l = (float) (s.level * (1.0 - d / s.range) * cone);
                }
                if (l <= best) continue;
                if (serverThread && !LightSight.clear(level, new Vec3(s.px, s.py, s.pz), new Vec3(x, y, z), false)) continue;
                best = l;
                continue;
            }
            float l = s.level - (float) (Math.abs(x - (s.cx + 0.5)) + Math.abs(y - (s.cy + 0.5)) + Math.abs(z - (s.cz + 0.5)));
            if (l <= best) continue;
            if (occlusion) {
                Field f = s.field;
                if (serverThread && (f == null || level.getGameTime() - f.built >= FIELD_TICKS) && dim.fills < FILLS_PER_TICK) {
                    dim.fills++;
                    f = fill(level, s.cx, s.cy, s.cz, (int) s.level, level.getGameTime());
                    s.field = f;
                    dim.fields.put(s.cell(), f);
                }
                if (f != null) {
                    l -= f.loss(bx, by, bz);
                    if (l <= best) continue;
                }
            }
            best = l;
        }
        return Math.min(15f, best);
    }

    // the server thread's scratch for fills: the blocks of the cube being filled, read once each
    private static final int SCRATCH_SIDE = 2 * MAX_REACH + 1;
    private static final BlockState[] STATES = new BlockState[SCRATCH_SIDE * SCRATCH_SIDE * SCRATCH_SIDE];
    private static final IntArrayFIFOQueue QUEUE = new IntArrayFIFOQueue();
    private static @Nullable LevelChunk lastChunk;
    private static int lastChunkX, lastChunkZ;
    private static boolean lastChunkKnown;

    /**
     * Spreads a lamp's light of {@code lvl} from a cell the way vanilla spreads block light: each step costs a level or the
     * block's own opacity if that is more, an opaque block takes none, and two blocks whose facing shapes close the face
     * between them (slabs, stairs) pass none. Chunks that are not loaded stop it. Server thread only.
     */
    private static Field fill(ServerLevel level, int cx, int cy, int cz, int lvl, long now) {
        int reach = Math.max(1, Math.min(MAX_REACH, lvl));
        int side = 2 * reach + 1, cells = side * side * side;
        byte[] got = new byte[cells];
        Arrays.fill(STATES, 0, cells, null);
        QUEUE.clear();
        lastChunkKnown = false;
        int ox = cx - reach, oy = cy - reach, oz = cz - reach;
        BlockPos.MutableBlockPos from = new BlockPos.MutableBlockPos(), to = new BlockPos.MutableBlockPos();
        int centre = (reach * side + reach) * side + reach;
        got[centre] = (byte) Math.min(MAX_REACH, lvl);
        QUEUE.enqueue(centre);
        while (!QUEUE.isEmpty()) {
            int index = QUEUE.dequeueInt();
            int current = got[index];
            if (current <= 1) continue;
            int x = index % side, y = (index / side) % side, z = index / (side * side);
            from.set(ox + x, oy + y, oz + z);
            BlockState here = state(level, index, from);
            for (Direction dir : DIRECTIONS) {
                int nx = x + dir.getStepX(), ny = y + dir.getStepY(), nz = z + dir.getStepZ();
                if (nx < 0 || ny < 0 || nz < 0 || nx >= side || ny >= side || nz >= side) continue;
                int next = (nz * side + ny) * side + nx;
                if (got[next] >= current - 1) continue;
                to.set(ox + nx, oy + ny, oz + nz);
                BlockState there = state(level, next, to);
                int opacity = there.isAir() ? 1 : Math.max(1, there.getLightBlock(level, to));
                if (opacity >= 15) continue;
                int left = current - opacity;
                if (left <= got[next]) continue;
                if ((here.useShapeForLightOcclusion() || there.useShapeForLightOcclusion()) && facesClose(level, here, from, there, to, dir)) continue;
                got[next] = (byte) left;
                QUEUE.enqueue(next);
            }
        }
        lastChunk = null;
        lastChunkKnown = false;
        return new Field(cx, cy, cz, reach, Math.min(MAX_REACH, lvl), got, now);
    }

    /** The block of a cell of the cube being filled; bedrock where the chunk is not loaded, air outside the world's height. */
    private static BlockState state(ServerLevel level, int index, BlockPos pos) {
        BlockState state = STATES[index];
        if (state != null) return state;
        if (level.isOutsideBuildHeight(pos)) {
            state = Blocks.AIR.defaultBlockState();
        } else {
            int chunkX = pos.getX() >> 4, chunkZ = pos.getZ() >> 4;
            if (!lastChunkKnown || chunkX != lastChunkX || chunkZ != lastChunkZ) {
                lastChunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                lastChunkX = chunkX;
                lastChunkZ = chunkZ;
                lastChunkKnown = true;
            }
            state = lastChunk == null ? Blocks.BEDROCK.defaultBlockState() : lastChunk.getBlockState(pos);
        }
        STATES[index] = state;
        return state;
    }

    /** Whether the shapes two neighbours turn to each other close the face between them (vanilla's rule for light). */
    private static boolean facesClose(ServerLevel level, BlockState here, BlockPos from, BlockState there, BlockPos to, Direction dir) {
        VoxelShape a = here.canOcclude() && here.useShapeForLightOcclusion() ? here.getFaceOcclusionShape(level, from, dir) : Shapes.empty();
        VoxelShape b = there.canOcclude() && there.useShapeForLightOcclusion() ? there.getFaceOcclusionShape(level, to, dir.getOpposite()) : Shapes.empty();
        if (a.isEmpty() && b.isEmpty()) return false;
        return Shapes.faceShapeOccludes(a, b);
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
