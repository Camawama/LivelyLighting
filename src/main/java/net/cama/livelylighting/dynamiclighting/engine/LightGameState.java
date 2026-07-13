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
    
    // Maps ShipID -> (shipyard BlockPos -> light emission) of the ship's own
    // light-emitting blocks, rescanned periodically. Projected into the world each
    // tick so a moving/rotating ship's lamps illuminate the world around it.
    // Nothing is ever placed inside the shipyard.
    public final Map<Long, Map<BlockPos, Integer>> shipEmitterCache = new HashMap<>();

    // Maps ShipID -> game time of the last emitter scan.
    public final Map<Long, Long> shipEmitterScanTime = new HashMap<>();
    
    // Maps Dimension -> (EntityID -> (SourceID -> LightData))
    public final Map<ResourceKey<Level>, Map<Integer, Map<String, LightData>>> entityLitState = new HashMap<>();

    // Maps Dimension -> (EntityID -> (SourceID -> established light level)).
    // Ramps toward the item's level at fade_in_rate while the source stays lit.
    // Purely temporal — movement can neither restart nor compound the ignition
    // fade, which is what makes a velocity-scaled fade-in safe at any speed.
    public final Map<ResourceKey<Level>, Map<Integer, Map<String, Integer>>> sourceEstablished = new HashMap<>();
    
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

    // Maps Dimension -> (EntityID -> exponentially smoothed velocity). The
    // predictive anchor lead uses this instead of the instantaneous velocity so a
    // direction change swings the lead over a few updates instead of snapping the
    // anchor block sideways, which caused visible flashing when turning.
    public final Map<ResourceKey<Level>, Map<Integer, Vec3>> smoothedVelocity = new HashMap<>();

    public void clear() {
        levelLights.clear();
        playerLights.clear();
        shipEmitterCache.clear();
        shipEmitterScanTime.clear();
        entityLitState.clear();
        sourceEstablished.clear();
        lastSoundTime.clear();
        lastSourcePos.clear();
        lastEntityPos.clear();
        smoothedVelocity.clear();
    }
}
