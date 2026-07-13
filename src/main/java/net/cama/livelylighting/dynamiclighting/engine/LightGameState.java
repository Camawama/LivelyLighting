package net.cama.livelylighting.dynamiclighting.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class LightGameState {
    // Maps dimension -> (BlockPos -> LightLevel)
    public final Map<ResourceKey<Level>, Map<BlockPos, Integer>> levelLights = new HashMap<>();
    
    // Maps dimension -> Set<BlockPos> of lights created/influenced by players
    public final Map<ResourceKey<Level>, Set<BlockPos>> playerLights = new HashMap<>();
    
    // Maps ShipID -> (BlockPos -> LightLevel)
    public final Map<Long, Map<BlockPos, Integer>> shipLights = new HashMap<>();
    
    // Maps ShipID -> Set<BlockPos> of lights created/influenced by players on ships
    public final Map<Long, Set<BlockPos>> shipPlayerLights = new HashMap<>();
    
    // Maps Dimension -> (EntityID -> (SourceID -> LightData))
    public final Map<ResourceKey<Level>, Map<Integer, Map<String, LightData>>> entityLitState = new HashMap<>();
    
    // All keyed by dimension first: per-level cleanup must only touch its own
    // dimension's entries — level.getEntity(id) returns null for entities that are
    // alive in another dimension, so a flat map would get wrongly purged.

    // Maps Dimension -> (EntityID -> Last Sound Time (Game Time))
    public final Map<ResourceKey<Level>, Map<Integer, Long>> lastSoundTime = new HashMap<>();

    // Maps Dimension -> (EntityID -> (SourceID -> LastBlockPos))
    public final Map<ResourceKey<Level>, Map<Integer, Map<String, BlockPos>>> lastSourcePos = new HashMap<>();

    // Maps Dimension -> (EntityID -> eye position last time the entity was processed);
    // used to derive velocity for predictive light anchoring.
    public final Map<ResourceKey<Level>, Map<Integer, Vec3>> lastEntityPos = new HashMap<>();

    public void clear() {
        levelLights.clear();
        playerLights.clear();
        shipLights.clear();
        shipPlayerLights.clear();
        entityLitState.clear();
        lastSoundTime.clear();
        lastSourcePos.clear();
        lastEntityPos.clear();
    }
}
