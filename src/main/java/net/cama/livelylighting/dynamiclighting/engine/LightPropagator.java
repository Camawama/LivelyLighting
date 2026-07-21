package net.cama.livelylighting.dynamiclighting.engine;

import net.cama.livelylighting.data.LivelyLightingData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public class LightPropagator {

    public static void calculateLightField(ServerLevel level, Map<BlockPos, LightCluster> clusters, Map<BlockPos, Integer> desiredLights, Set<BlockPos> desiredPlayerLights, Map<BlockPos, Integer> desiredCarry, boolean smoothing, boolean clusterGrowing, int maxRadius) {
        for (LightCluster cluster : clusters.values()) {
            cluster.normalize();

            float strength = cluster.strength;
            if (!clusterGrowing) {
                strength = cluster.maxLightLevel;
            }

            // A radius-1 pass for strength <= 15 can never place anything (neighbors
            // always lose to the naturalLevel check below), so the BFS only runs for
            // overgrown clusters. Ordinary sources take the cheap single-block path.
            int radius = 0;
            if (strength > 15) {
                radius = (int) Math.ceil(strength - 15);
                radius = Math.min(radius, maxRadius);
            }

            BlockPos centerPos = BlockPos.containing(cluster.x, cluster.y, cluster.z);

            // A merged cluster's centroid is an average of its sources and can land
            // inside a wall between them; placement there fails every tick and the
            // light of all contributing entities vanishes. Snap to the nearest spot
            // that can actually host a light block.
            if (!isValidLightSpot(level, centerPos)) {
                BlockPos snapped = findValidLightPos(level, centerPos, centerPos, 3);
                if (snapped != null) centerPos = snapped;
            }

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
                    if (cluster.carryLevel > 0) {
                        desiredCarry.merge(centerPos, cluster.carryLevel, Integer::max);
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
                            if (cluster.carryLevel > 0) {
                                desiredCarry.merge(currentPos, cluster.carryLevel, Integer::max);
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
                        if (cluster.carryLevel > 0) {
                            desiredCarry.merge(currentPos, cluster.carryLevel, Integer::max);
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
    
    public static void applyChanges(ServerLevel level, Map<BlockPos, Integer> currentLights, Set<BlockPos> currentPlayerLights, Map<BlockPos, Integer> desiredLights, Set<BlockPos> desiredPlayerLights, Map<BlockPos, Integer> desiredCarry, boolean smoothing, boolean smoothingAllEntities, int decayRate, int fadeInRate) {
        Map<BlockPos, Integer> nextCurrentLights = new HashMap<>();
        Set<BlockPos> nextCurrentPlayerLights = new HashSet<>();

        Set<BlockPos> allPositions = new HashSet<>(currentLights.keySet());
        allPositions.addAll(desiredLights.keySet());

        Map<BlockPos, Integer> calculatedLevels = new HashMap<>();
        List<BlockPos> fadingOut = new ArrayList<>();

        // First, calculate the target light level for every position with a live source.
        // Positions whose source is gone (Case 3) are resolved afterwards, so their
        // removal test can run against the levels actually being placed this tick.
        for (BlockPos pos : allPositions) {
            int currentLevel = currentLights.getOrDefault(pos, 0);
            int desiredLevel = desiredLights.getOrDefault(pos, 0);
            boolean wasCurrent = currentLevel > 0;
            boolean isDesired = desiredLevel > 0;
            boolean wasPlayerLight = currentPlayerLights.contains(pos);
            boolean isDesiredPlayerLight = desiredPlayerLights.contains(pos);

            if (!isDesired) {
                if (wasCurrent) {
                    fadingOut.add(pos);
                }
                continue;
            }

            int newLevel;
            if (!wasCurrent) {
                // Case 1: New light. Fade in from the light this position already
                // receives, never from zero: block light combines by max, so fading
                // in from zero would visibly dim the area before brightening it.
                // The carried level (what the contributing source's block reached
                // last tick) also counts as a floor: an established source keeps its
                // brightness while moving, no matter how fast — only fresh ignitions
                // actually fade in. Without it, a source moving more blocks per tick
                // than fade_in_rate outruns its own ambient light and dims toward
                // darkness, which killed both the light and its trail at high speed.
                if (smoothing && (smoothingAllEntities || isDesiredPlayerLight)) {
                    int ambient = Math.max(shadowLevel(pos, currentLights), desiredCarry.getOrDefault(pos, 0));
                    newLevel = Math.min(desiredLevel, ambient + fadeInRate);
                } else {
                    newLevel = desiredLevel;
                }
            } else {
                // Case 2: Existing light (update)
                if (smoothing && (smoothingAllEntities || wasPlayerLight || isDesiredPlayerLight)) {
                    if (desiredLevel > currentLevel) {
                        int floor = Math.max(currentLevel, desiredCarry.getOrDefault(pos, 0));
                        newLevel = Math.min(desiredLevel, floor + fadeInRate);
                    } else { // desiredLevel <= currentLevel
                        newLevel = Math.max(desiredLevel, currentLevel - decayRate);
                    }
                } else {
                    newLevel = desiredLevel;
                }
            }
            calculatedLevels.put(pos, newLevel);
        }

        // Case 3: Light is not desired, but was current. Fade it out.
        // Snapshot the live placements so trail blocks don't shadow each other.
        Map<BlockPos, Integer> placedThisTick = new HashMap<>(calculatedLevels);
        for (BlockPos pos : fadingOut) {
            int currentLevel = currentLights.get(pos);
            boolean wasPlayerLight = currentPlayerLights.contains(pos);

            int newLevel = 0;
            if (smoothing && (smoothingAllEntities || wasPlayerLight)) {
                int shadowNow = shadowLevel(pos, placedThisTick);
                int faded = currentLevel - decayRate;
                // Sync with the incoming light: never fade below what the live
                // sources will provide here once their own fade-in completes.
                // Fading further and then being re-covered would dip and pulse;
                // holding at that floor makes the handoff seamless.
                int shadowTarget = shadowLevel(pos, desiredLights);
                int held = Math.max(faded, Math.min(currentLevel, shadowTarget));
                // Invisible handoff: once this block is no brighter than what the
                // live sources placed this tick propagate to this position, the
                // vanilla light engine fills in the same brightness the moment it's
                // gone — remove it now instead of leaving a redundant block behind.
                newLevel = held <= shadowNow ? 0 : held;
            }
            calculatedLevels.put(pos, newLevel);
        }

        // Pass 1: Apply newly placed blocks and increases first.
        // This ensures the new light is physically in the world BEFORE we fade anything out,
        // avoiding a flicker where the engine processes the removal before the new source.
        for (BlockPos pos : allPositions) {
            int newLevel = calculatedLevels.get(pos);
            int currentLevel = currentLights.getOrDefault(pos, 0);
            boolean isDesired = desiredLights.containsKey(pos);
            boolean wasPlayerLight = currentPlayerLights.contains(pos);
            boolean isDesiredPlayerLight = desiredPlayerLights.contains(pos);
            boolean trackAsPlayerLight = isDesiredPlayerLight || (wasPlayerLight && !isDesired);
            
            if (newLevel >= currentLevel && newLevel > 0) {
                boolean placeSuccess;
                if (newLevel > currentLevel || shouldRePlace(level, pos, newLevel)) {
                    placeSuccess = placeLight(level, pos, newLevel);
                } else {
                    placeSuccess = true; // Already correctly placed
                }
                
                if (placeSuccess) {
                    nextCurrentLights.put(pos, newLevel);
                    if (trackAsPlayerLight) {
                        nextCurrentPlayerLights.add(pos);
                    }
                } else {
                    // Failed to place. Set calculated level to 0 so Pass 2 will clean it up if needed.
                    calculatedLevels.put(pos, 0);
                }
            }
        }

        // Pass 2: Apply decreases and removals.
        for (BlockPos pos : allPositions) {
            int newLevel = calculatedLevels.get(pos);
            int currentLevel = currentLights.getOrDefault(pos, 0);
            boolean isDesired = desiredLights.containsKey(pos);
            boolean wasPlayerLight = currentPlayerLights.contains(pos);
            boolean isDesiredPlayerLight = desiredPlayerLights.contains(pos);
            boolean trackAsPlayerLight = isDesiredPlayerLight || (wasPlayerLight && !isDesired);
            
            if (newLevel < currentLevel) {
                if (newLevel > 0) {
                    if (placeLight(level, pos, newLevel)) {
                        nextCurrentLights.put(pos, newLevel);
                        if (trackAsPlayerLight) {
                            nextCurrentPlayerLights.add(pos);
                        }
                    }
                } else { // newLevel <= 0
                    if (currentLevel > 0) {
                        removeLight(level, pos);
                    }
                }
            }
        }

        // Atomically update the game state maps
        currentLights.clear();
        currentLights.putAll(nextCurrentLights);
        currentPlayerLights.clear();
        currentPlayerLights.addAll(nextCurrentPlayerLights);
    }
    
    // The block light level a position already receives from the desired light
    // sources, assuming unobstructed vanilla propagation (level - manhattan distance).
    // Trail positions were just walked through by the carrier, so the path is
    // almost always clear and this estimate holds.
    private static int shadowLevel(BlockPos pos, Map<BlockPos, Integer> desiredLights) {
        int best = 0;
        for (Map.Entry<BlockPos, Integer> entry : desiredLights.entrySet()) {
            BlockPos src = entry.getKey();
            int manhattan = Math.abs(src.getX() - pos.getX())
                          + Math.abs(src.getY() - pos.getY())
                          + Math.abs(src.getZ() - pos.getZ());
            int contribution = entry.getValue() - manhattan;
            if (contribution > best) {
                best = contribution;
            }
        }
        return best;
    }

    public static boolean shouldRePlace(ServerLevel level, BlockPos pos, int targetLevel) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            return state.getValue(LightBlock.LEVEL) != targetLevel;
        }
        return state.isAir() || (state.getFluidState().isSource() && state.is(Blocks.WATER));
    }

    public static boolean placeLight(ServerLevel level, BlockPos pos, int lightLevel) {
        if (!level.isLoaded(pos)) return false;

        BlockState state = level.getBlockState(pos);
        int flags = Block.UPDATE_CLIENTS;

        if (state.is(Blocks.LIGHT)) {
            // Never adopt a light block the mod didn't place (manually placed ones,
            // e.g. Colorful Lighting wildcard emitters) — adopting it would let the
            // fade logic overwrite its level and eventually delete it.
            if (!isPlacedByMod(level, pos)) {
                return false;
            }
            int currentLevel = state.getValue(LightBlock.LEVEL);
            if (currentLevel != lightLevel) {
                level.setBlock(pos, state.setValue(LightBlock.LEVEL, lightLevel), flags);
            }
            return true;
        }

        if (state.isAir()) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel), flags);
            LivelyLightingData.get(level).addPlacedLight(level.dimension(), pos);
            return true;
        } else if (state.getFluidState().isSource() && state.is(Blocks.WATER)) {
            level.setBlock(pos, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, lightLevel).setValue(LightBlock.WATERLOGGED, true), flags);
            LivelyLightingData.get(level).addPlacedLight(level.dimension(), pos);
            return true;
        }

        return false;
    }

    public static void removeLight(ServerLevel level, BlockPos pos) {
        // Not loaded: keep the persisted record so the chunk-load sweep can clean
        // the orphaned block when the chunk comes back.
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
        LivelyLightingData.get(level).removePlacedLight(level.dimension(), pos);
    }
    
    public static BlockPos findValidLightPos(Level level, BlockPos eyePos, BlockPos feetPos, int radius) {
        return findValidLightPos(level, eyePos, feetPos, radius, null);
    }

    public static BlockPos findValidLightPos(Level level, BlockPos eyePos, BlockPos feetPos, int radius, Vec3 losFrom) {
        if (isValidLightSpot(level, eyePos)) return eyePos;
        if (isValidLightSpot(level, eyePos.above())) return eyePos.above();
        if (isValidLightSpot(level, feetPos)) return feetPos;

        // Nearest-first, but a candidate must also have an unobstructed light path
        // back to the source — otherwise an enclosed entity's light gets anchored
        // on the far side of a wall and shines through it.
        List<BlockPos> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int x = -radius; x <= radius; x++) {
            for (int y = -radius; y <= radius; y++) {
                for (int z = -radius; z <= radius; z++) {
                    if (x == 0 && y == 0 && z == 0) continue;

                    cursor.setWithOffset(eyePos, x, y, z);
                    if (isValidLightSpot(level, cursor)) {
                        candidates.add(cursor.immutable());
                    }
                }
            }
        }

        // Selection instead of a full sort: the nearest candidate almost always
        // passes the light-path check, so repeatedly picking the closest
        // remaining one is O(n) in the common case. Ties resolve to the earliest
        // candidate, matching the stable sort this replaces.
        int n = candidates.size();
        double[] distSq = new double[n];
        double cx = eyePos.getX() + 0.5, cy = eyePos.getY() + 0.5, cz = eyePos.getZ() + 0.5;
        for (int i = 0; i < n; i++) {
            distSq[i] = candidates.get(i).distToCenterSqr(cx, cy, cz);
        }

        int remaining = n;
        while (remaining > 0) {
            int best = -1;
            double bestDist = Double.POSITIVE_INFINITY;
            for (int i = 0; i < n; i++) {
                if (distSq[i] < bestDist) {
                    bestDist = distSq[i];
                    best = i;
                }
            }
            if (best < 0) break;

            BlockPos pos = candidates.get(best);
            if (losFrom == null || hasLightPath(level, new Vec3(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5), losFrom)) {
                return pos;
            }
            distSq[best] = Double.POSITIVE_INFINITY;
            remaining--;
        }
        return null;
    }

    /**
     * Whether light can plausibly travel between two points: samples the segment
     * and fails on any intervening block that is fully opaque to light
     * (getLightBlock == 15), matching the light engine's own transparency rule so
     * glass and other light-passing blocks don't block the check. The endpoint
     * blocks themselves are exempt — a projectile's eye may sit just inside the
     * wall it is stuck in.
     */
    public static boolean hasLightPath(Level level, Vec3 from, Vec3 to) {
        Vec3 delta = to.subtract(from);
        double length = delta.length();
        if (length < 1.0E-4) return true;

        Vec3 step = delta.scale(1.0 / length);
        BlockPos fromPos = BlockPos.containing(from);
        BlockPos toPos = BlockPos.containing(to);

        for (double travelled = 0.25; travelled < length; travelled += 0.25) {
            Vec3 point = from.add(step.scale(travelled));
            BlockPos pos = BlockPos.containing(point);
            if (pos.equals(fromPos) || pos.equals(toPos)) continue;
            if (!level.isLoaded(pos)) return false;

            BlockState state = level.getBlockState(pos);
            if (state.getLightBlock(level, pos) >= 15) return false;
        }
        return true;
    }
    
    public static boolean isValidLightSpot(Level level, BlockPos pos) {
        if (!level.isLoaded(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.is(Blocks.LIGHT)) {
            // A light block is only a valid anchor if the mod placed it; foreign
            // ones (manually placed, other mods) must be left untouched.
            return level instanceof ServerLevel serverLevel && isPlacedByMod(serverLevel, pos);
        }
        return state.isAir() || (state.is(Blocks.WATER) && state.getFluidState().isSource());
    }

    private static boolean isPlacedByMod(ServerLevel level, BlockPos pos) {
        return LivelyLightingData.get(level).getPlacedLights(level.dimension()).contains(pos);
    }
}
