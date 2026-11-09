package net.cama.livelylighting.client.dynamic;

import com.mojang.logging.LogUtils;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.source.BlockLightDefs;
import net.cama.livelylighting.source.Falloff;
import net.cama.livelylighting.source.LightShape;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The lamps of Create contraptions as dynamic lights on the client (0.5.0): a contraption entity's luminous blocks
 * (its block map, scanned every 60 ticks), capped per contraption ({@code max_lamps_per_ship}) and with the ships' lamps
 * over all ({@code client.lightmap_max_moving_lamps}), each moving with the contraption's render transform
 * ({@code toGlobalVector(local, partialTick)}); a lamp with a block definition or in {@code #livelylighting:headlights} is a
 * spot light along its facing turned with the contraption ({@code applyRotation}). Create is reached by reflection and is
 * optional; without it, or when its classes have changed, this stands down with one log line.
 */
final class ContraptionLamps {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int RESCAN_TICKS = 60;
    private static Class<?> entityType;
    private static MethodHandle getContraption, getBlocks, toGlobalVector, applyRotation;
    private static boolean ready, failed;

    private record Lamp(BlockPos pos, BlockState state) {}

    private record Scan(long tick, List<Lamp> lamps) {}

    private final Map<Integer, Scan> scans = new HashMap<>();

    void forget() {
        scans.clear();
    }

    private static boolean bind() {
        if (ready) return true;
        if (failed || !ModList.get().isLoaded("create")) return false;
        try {
            MethodHandles.Lookup lookup = MethodHandles.publicLookup();
            entityType = Class.forName("com.simibubi.create.content.contraptions.AbstractContraptionEntity");
            Class<?> contraption = Class.forName("com.simibubi.create.content.contraptions.Contraption");
            getContraption = lookup.findVirtual(entityType, "getContraption", MethodType.methodType(contraption));
            getBlocks = lookup.findVirtual(contraption, "getBlocks", MethodType.methodType(Map.class));
            toGlobalVector = lookup.findVirtual(entityType, "toGlobalVector", MethodType.methodType(Vec3.class, Vec3.class, float.class));
            applyRotation = lookup.findVirtual(entityType, "applyRotation", MethodType.methodType(Vec3.class, Vec3.class, float.class));
            ready = true;
            LOGGER.info("[LivelyLighting] Create contraptions' lamps are dynamic lights");
        } catch (Throwable t) {
            failed = true;
            LOGGER.warn("[LivelyLighting] Create is present but its contraption classes aren't as expected; contraption lamps are not lit ({})", t.toString());
        }
        return ready;
    }

    void collect(ClientLevel level, Vec3 camera, LivelyConfig config, Falloff falloff, List<DynamicLight> out) {
        if (!bind()) return;
        long now = level.getGameTime();
        for (Entity entity : level.entitiesForRendering()) {
            if (!entityType.isInstance(entity)) continue;
            Scan scan = scans.get(entity.getId());
            if (scan == null || now - scan.tick >= RESCAN_TICKS) {
                scan = new Scan(now, scan(entity, config));
                scans.put(entity.getId(), scan);
            }
            List<Lamp> lamps = scan.lamps;
            int cap = config.max_lamps_per_ship;
            if (cap > 0 && lamps.size() > cap) {
                List<Lamp> ranked = new ArrayList<>(lamps);
                ranked.sort((a, b) -> Integer.compare(b.state.getLightEmission(), a.state.getLightEmission()));
                lamps = ranked.subList(0, cap);
            }
            for (Lamp lamp : lamps) out.add(toLight(entity, lamp, falloff));
        }
        scans.keySet().removeIf(id -> level.getEntity(id) == null);
    }

    @SuppressWarnings("unchecked")
    private List<Lamp> scan(Entity entity, LivelyConfig config) {
        List<Lamp> lamps = new ArrayList<>();
        try {
            Object contraption = getContraption.invoke(entity);
            if (contraption == null) return lamps;
            Map<BlockPos, Object> blocks = (Map<BlockPos, Object>) getBlocks.invoke(contraption);
            for (Map.Entry<BlockPos, Object> e : blocks.entrySet()) {
                // vanilla's structure block info is a compile-time class (remapped by the build; by name it isn't found in production)
                BlockState state = e.getValue() instanceof net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate.StructureBlockInfo info ? info.state() : null;
                if (state == null || state.getLightEmission() <= 0) continue;
                if (state.is(Blocks.LIGHT) && !config.light_blocks_are_lamps) continue;
                lamps.add(new Lamp(e.getKey().immutable(), state));
            }
        } catch (Throwable t) {
            failed = true;
            ready = false;
            LOGGER.warn("[LivelyLighting] reading a Create contraption's blocks failed; contraption lamps stand down ({})", t.toString());
        }
        return lamps;
    }

    private static DynamicLight toLight(Entity entity, Lamp lamp, Falloff falloff) {
        Vec3 centre = Vec3.atCenterOf(lamp.pos);
        DynamicLight.Mover mover = pt -> {
            try {
                return (Vec3) toGlobalVector.invoke(entity, centre, pt);
            } catch (Throwable t) {
                return entity.position().add(centre);
            }
        };
        long key = ((long) entity.getId() << 24) ^ (lamp.pos.asLong() & 0xFFFFFFL) ^ 0x3C00000000000000L;
        BlockLightDefs.BlockLightData def = BlockLightDefs.get(lamp.state.getBlock());
        if (def != null && def.shape() == LightShape.SPOT && def.isLit(lamp.state)) {
            Direction facing = def.directionOf(lamp.state);
            if (facing != null) {
                Vec3 local = new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ());
                DynamicLight.Mover dir = pt -> {
                    try {
                        return (Vec3) applyRotation.invoke(entity, local, pt);
                    } catch (Throwable t) {
                        return local;
                    }
                };
                float range = def.range() > 0 ? def.range() : LightShape.DEFAULT_SPOT_RANGE;
                return new DynamicLight(key, DynamicLight.Kind.CONTRAPTION_LAMP, mover, dir, def.levelOf(lamp.state), def.color() >= 0 ? def.color() : LightColors.ofBlock(lamp.state), LightShape.SPOT,
                        def.coneDegrees() > 0 ? def.coneDegrees() : LightShape.DEFAULT_CONE_DEGREES, range, falloff);
            }
        }
        int level = def != null ? def.levelOf(lamp.state) : lamp.state.getLightEmission();
        int rgb = def != null && def.color() >= 0 ? def.color() : LightColors.ofBlock(lamp.state);
        return new DynamicLight(key, DynamicLight.Kind.CONTRAPTION_LAMP, mover, null, level, rgb, LightShape.POINT, 0f, level, falloff);
    }
}
