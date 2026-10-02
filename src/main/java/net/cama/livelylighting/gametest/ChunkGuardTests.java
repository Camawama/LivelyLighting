package net.cama.livelylighting.gametest;

import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.dynamiclighting.engine.LightPropagator;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.lang.reflect.Field;
import java.util.EnumMap;
import java.util.Map;

/**
 * The light engine never writes into a chunk whose heightmaps are gone. Valkyrien Skies 2.4.11 leaves
 * a ship's old shipyard chunks like that when the ship changes dimension (its {@code clearChunk}
 * empties the heightmap map and never primes it again), and every {@code setBlock} there throws
 * inside {@code LevelChunk.setBlockState}: that crashed the owner's server twice on 2026-10-01. These
 * tests empty a loaded chunk's heightmaps the same way, for one call, and put them back at once.
 */
@GameTestHolder(LivelyLighting.MODID)
@PrefixGameTestTemplate(false)
public class ChunkGuardTests {
    private static final String PLATFORM = "platform";
    private static final int AIR = 2;

    /** No light goes into such a chunk, nothing throws, and once the heightmaps are back the light goes in. */
    @GameTest(template = PLATFORM, timeoutTicks = 40)
    public static void aChunkWithoutHeightmapsTakesNoLight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(5, AIR, 5));
        int skippedBefore = LightPropagator.unwritableTotal;
        boolean placed = withoutHeightmaps(level.getChunkAt(pos), () -> LightPropagator.placeLight(level, pos, 12));
        if (placed) helper.fail("a light was placed into a chunk without heightmaps");
        if (!level.getBlockState(pos).isAir()) helper.fail("the block changed: " + level.getBlockState(pos));
        if (LightPropagator.unwritableTotal <= skippedBefore) helper.fail("the skip wasn't counted");
        if (LivelyLightingData.get(level).getPlacedLights(level.dimension()).contains(pos)) {
            helper.fail("a record was kept for a light that was never placed");
        }

        if (!LightPropagator.placeLight(level, pos, 12)) helper.fail("no light once the heightmaps were back");
        if (!level.getBlockState(pos).is(Blocks.LIGHT)) helper.fail("no light block once the heightmaps were back");
        LightPropagator.removeLight(level, pos);
        helper.succeed();
    }

    /** A write that throws anyway (the last line of defence) is reported as false, never thrown. */
    @GameTest(template = PLATFORM, timeoutTicks = 40)
    public static void aThrowingWriteIsCaught(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(7, AIR, 7));
        boolean wrote = withoutHeightmaps(level.getChunkAt(pos),
                () -> LightPropagator.trySetBlock(level, pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 9), 3));
        // vanilla changes the section before it reaches the heightmaps, so tidy whatever it left
        level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
        if (wrote) helper.fail("a write into a chunk without heightmaps reported success");
        helper.succeed();
    }

    /** A light block that can't be taken out now stays, and so does its record, for a later sweep. */
    @GameTest(template = PLATFORM, timeoutTicks = 40)
    public static void aLightThatCannotBeRemovedKeepsItsRecord(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(new BlockPos(9, AIR, 9));
        if (!LightPropagator.placeLight(level, pos, 10)) helper.fail("couldn't place the light");
        boolean removed = withoutHeightmaps(level.getChunkAt(pos), () -> LightPropagator.removeLight(level, pos));
        if (removed) helper.fail("removal reported success in a chunk without heightmaps");
        if (!level.getBlockState(pos).is(Blocks.LIGHT)) helper.fail("the light block went: " + level.getBlockState(pos));
        if (!LivelyLightingData.get(level).getPlacedLights(level.dimension()).contains(pos)) {
            helper.fail("the record went with nothing removed");
        }

        if (!LightPropagator.removeLight(level, pos)) helper.fail("no removal once the heightmaps were back");
        if (!level.getBlockState(pos).isAir()) helper.fail("the light block stayed once the heightmaps were back");
        if (LivelyLightingData.get(level).getPlacedLights(level.dimension()).contains(pos)) helper.fail("the record stayed");
        helper.succeed();
    }

    /** Runs {@code body} with the chunk's heightmaps emptied (as Valkyrien Skies leaves them), then puts them back. */
    private static boolean withoutHeightmaps(LevelChunk chunk, java.util.function.BooleanSupplier body) {
        Map<Heightmap.Types, Heightmap> maps = heightmaps(chunk);
        Map<Heightmap.Types, Heightmap> saved = new EnumMap<>(Heightmap.Types.class);
        saved.putAll(maps);
        maps.clear();
        try {
            return body.getAsBoolean();
        } finally {
            maps.putAll(saved);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<Heightmap.Types, Heightmap> heightmaps(ChunkAccess chunk) {
        for (Field field : ChunkAccess.class.getDeclaredFields()) {
            if (Map.class.isAssignableFrom(field.getType())
                    && field.getGenericType().getTypeName().contains("Heightmap$Types")) {
                try {
                    field.setAccessible(true);
                    return (Map<Heightmap.Types, Heightmap>) field.get(chunk);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(e);
                }
            }
        }
        throw new IllegalStateException("ChunkAccess has no heightmap map");
    }
}
