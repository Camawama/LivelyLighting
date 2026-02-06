package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.compat.valkyrienskies.IVSCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;

import java.util.*;

public class LightPropagator {

    public static void calculateLightField(ServerLevel level, Map<BlockPos, LightCluster> clusters, Map<BlockPos, Integer> desiredLights, Set<BlockPos> desiredPlayerLights, boolean smoothing, boolean clusterGrowing, int maxRadius) {
        for (LightCluster cluster : clusters.values()) {
            cluster.normalize();

            float strength = cluster.strength;
            if (!clusterGrowing) {
                strength = cluster.maxLightLevel;
            }

            int radius = 0;
            if (strength > 15) {
                radius = (int) Math.ceil(strength - 15);
                radius = Math.min(radius, maxRadius);
            } else if (smoothing) {
                radius = 1;
            }

            BlockPos centerPos = BlockPos.containing(cluster.x, cluster.y, cluster.z);

            if (radius == 0) {
                int levelAtTarget = Math.min(15, (int)strength);
                if (levelAtTarget > 0) {
                    int existing = desiredLights.getOrDefault(centerPos, 0);
                    if (levelAtTarget > existing) {
                        desiredLights.put(centerPos, levelAtTarget);
                        if (cluster.isPlayer) {
                            desiredPlayerLights.add(centerPos);
                        }
                    }
                }
                continue;
            }

            Set<BlockPos> visited = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();

            queue.add(centerPos);
            visited.add(centerPos);

            while (!queue.isEmpty()) {
                BlockPos currentPos = queue.poll();

                double dist;
                if (smoothing) {
                    double dSq = (cluster.x - (currentPos.getX() + 0.5)) * (cluster.x - (currentPos.getX() + 0.5)) +
                                 (cluster.y - (currentPos.getY() + 0.5)) * (cluster.y - (currentPos.getY() + 0.5)) +
                                 (cluster.z - (currentPos.getZ() + 0.5)) * (cluster.z - (currentPos.getZ() + 0.5));
                    dist = Math.sqrt(dSq);
                } else {
                    dist = Math.abs(centerPos.getX() - currentPos.getX()) +
                           Math.abs(centerPos.getY() - currentPos.getY()) +
                           Math.abs(centerPos.getZ() - currentPos.getZ());
                }

                if (dist > radius) continue;

                int levelAtTarget = (int) Math.round(strength - dist);
                if (levelAtTarget > 15) levelAtTarget = 15;

                if (levelAtTarget > 0) {
                    if (!currentPos.equals(centerPos)) {
                        int manhattanDist = Math.abs(centerPos.getX() - currentPos.getX()) +
                                            Math.abs(centerPos.getY() - currentPos.getY()) +
                                            Math.abs(centerPos.getZ() - currentPos.getZ());
                        int naturalLevel = Math.max(0, 15 - manhattanDist);

                        if (levelAtTarget > naturalLevel) {
                            int existing = desiredLights.getOrDefault(currentPos, 0);
                            if (levelAtTarget > existing) {
                                desiredLights.put(currentPos, levelAtTarget);
                                if (cluster.isPlayer) {
                                    desiredPlayerLights.add(currentPos);
                                }
                            }
                        }
                    } else {
                        int existing = desiredLights.getOrDefault(currentPos, 0);
                        if (levelAtTarget > existing) {
                            desiredLights.put(currentPos, levelAtTarget);
                            if (cluster.isPlayer) {
                                desiredPlayerLights.add(currentPos);
                            }
                        }
                    }

                    if (level.isLoaded(currentPos)) {
                        BlockState state = level.getBlockState(currentPos);
                        if (state.getLightBlock(level, currentPos) < 15) {
                            for (Direction dir : Direction.values()) {
                                BlockPos neighbor = currentPos.relative(dir);
                                if (visited.add(neighbor)) {
                                    queue.add(neighbor);
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    
    public static void applyChanges(ServerLevel level, Map<BlockPos, Integer> currentLights, Set<BlockPos> currentPlayerLights, Map<BlockPos, Integer> desiredLights, Set<BlockPos> desiredPlayerLights, boolean smoothing, boolean smoothingAllEntities, int decayRate, int fadeInRate, Map<BlockPos, LightCluster> clusters, Object ship, IVSCompat vsCompat) {
        Iterator<Map.Entry<BlockPos, Integer>> it = currentLights.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<BlockPos, Integer> entry = it.next();
            BlockPos pos = entry.getKey();
            int currentLevel = entry.getValue();

            if (desiredLights.containsKey(pos)) {
                int desiredLevel = desiredLights.get(pos);
                int newLevel = currentLevel;

                if (smoothing) {
                    boolean shouldSmooth = smoothingAllEntities || currentPlayerLights.contains(pos) || desiredPlayerLights.contains(pos);

                    if (shouldSmooth) {
                        if (desiredLevel > currentLevel) {
                            newLevel = Math.min(desiredLevel, currentLevel + fadeInRate);
                        } else if (desiredLevel < currentLevel) {
                            newLevel = Math.max(desiredLevel, currentLevel - decayRate);
                        }
                    } else {
                        newLevel = desiredLevel;
                    }
                } else {
                    newLevel = desiredLevel;
                }

                if (currentLevel != newLevel || shouldRePlace(level, pos, newLevel)) {
                    if (placeLight(level, pos, newLevel, true, ship, vsCompat)) {
                        entry.setValue(newLevel);
                        if (desiredPlayerLights.contains(pos)) {
                            currentPlayerLights.add(pos);
                        } else {
                            currentPlayerLights.remove(pos);
                        }
                    } else {
                        it.remove();
                        currentPlayerLights.remove(pos);
                    }
                } else {
                    // Even if level didn't change, update player ownership
                    if (desiredPlayerLights.contains(pos)) {
                        currentPlayerLights.add(pos);
                    } else {
                        currentPlayerLights.remove(pos);
                    }
                }
                desiredLights.remove(pos);
            } else {
                if (smoothing) {
                    boolean shouldSmooth = smoothingAllEntities || currentPlayerLights.contains(pos);

                    if (shouldSmooth) {
                        int newLevel = currentLevel - decayRate;
                        if (newLevel > 0) {
                            if (placeLight(level, pos, newLevel, true, ship, vsCompat)) {
                                entry.setValue(newLevel);
                            } else {
                                it.remove();
                                currentPlayerLights.remove(pos);
                            }
                        } else {
                            removeLight(level, pos);
                            it.remove();
                            currentPlayerLights.remove(pos);
                        }
                    } else {
                        removeLight(level, pos);
                        it.remove();
                        currentPlayerLights.remove(pos);
                    }
                } else {
                    removeLight(level, pos);
                    it.remove();
                    currentPlayerLights.remove(pos);
                }
            }
        }

        for (Map.Entry<BlockPos, Integer> entry : desiredLights.entrySet()) {
            BlockPos pos = entry.getKey();
            int desiredLevel = entry.getValue();
            int newLevel = desiredLevel;

            boolean isNewSource = false;
            boolean isPlayer = desiredPlayerLights.contains(pos);
            
            if (smoothing) {
                for (LightCluster cluster : clusters.values()) {
                    if (cluster.isNewSource) {
                        double distSq = pos.distToCenterSqr(cluster.x, cluster.y, cluster.z);
                        if (distSq < 256) {
                            isNewSource = true;
                            break;
                        }
                    }
                }

                if (isNewSource) {
                    if (smoothingAllEntities || isPlayer) {
                        newLevel = Math.min(desiredLevel, fadeInRate);
                    }
                }
            }

            if (placeLight(level, pos, newLevel, true, ship, vsCompat)) {
                currentLights.put(pos, newLevel);
                if (isPlayer) {
                    currentPlayerLights.add(pos);
                }
            }
        }
    }
    
    public static boolean shouldRePlace(ServerLevel level, BlockPos pos, int targetLevel) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            return state.getValue(LightBlock.LEVEL) != targetLevel;
        }
        return state.isAir() || (state.getFluidState().isSource() && state.is(Blocks.WATER));
    }

    public static boolean placeLight(ServerLevel level, BlockPos pos, int lightLevel, boolean checkOwnership, Object ship, IVSCompat vsCompat) {
        if (!level.isLoaded(pos)) return false;
        
        if (ship != null && vsCompat != null) {
            if (!vsCompat.isPosInShipBounds(ship, pos)) {
                return false;
            }
            
            LevelChunk chunk = level.getChunkSource().getChunk(pos.getX() >> 4, pos.getZ() >> 4, false);
            if (chunk == null) return false;
            
            int sectionIndex = level.getSectionIndex(pos.getY());
            if (sectionIndex >= 0 && sectionIndex < chunk.getSections().length) {
                if (chunk.getSection(sectionIndex).hasOnlyAir()) {
                    return false;
                }
            }
        }

        BlockState state = level.getBlockState(pos);
        int flags = Block.UPDATE_CLIENTS;

        if (state.is(Blocks.LIGHT)) {
            int currentLevel = state.getValue(LightBlock.LEVEL);
            if (currentLevel != lightLevel) {
                level.setBlock(pos, state.setValue(LightBlock.LEVEL, lightLevel), flags);
            }
            return true;
        }
        
        if (state.isAir()) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), flags);
            return true;
        } else if (state.getFluidState().isSource() && state.is(Blocks.WATER)) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel).setValue(LightBlock.WATERLOGGED, true), flags);
            return true;
        }

        return false;
    }

    public static void removeLight(ServerLevel level, BlockPos pos) {
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        int flags = Block.UPDATE_CLIENTS;

        if (state.is(Blocks.LIGHT)) {
            if (state.getValue(LightBlock.WATERLOGGED)) {
                level.setBlock(pos, Blocks.WATER.defaultBlockState(), flags);
            } else {
                level.setBlock(pos, Blocks.AIR.defaultBlockState(), flags);
            }
        }
    }
    
    public static BlockPos findValidLightPos(Level level, BlockPos eyePos, BlockPos feetPos, int radius) {
        if (isValidLightSpot(level, eyePos)) return eyePos;
        if (isValidLightSpot(level, eyePos.above())) return eyePos.above();
        if (isValidLightSpot(level, feetPos)) return feetPos;

        BlockPos bestPos = null;
        double bestDistSq = Double.MAX_VALUE;

        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    BlockPos pos = eyePos.offset(x, y, z);
                    if (isValidLightSpot(level, pos)) {
                        double distSq = pos.distToCenterSqr(eyePos.getX() + 0.5, eyePos.getY() + 0.5, eyePos.getZ() + 0.5);
                        if (distSq < bestDistSq) {
                            bestDistSq = distSq;
                            bestPos = pos;
                        }
                    }
                }
            }
        }

        return bestPos;
    }
    
    public static boolean isValidLightSpot(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        return state.isAir() || (state.is(Blocks.WATER) && state.getFluidState().isSource()) || state.is(Blocks.LIGHT);
    }
}
