package net.cama.livelylighting.dynamiclighting.engine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class LightGameState {
    // Maps dimension -> (BlockPos -> LightLevel)
    public final Map<ResourceKey<Level>, Map<BlockPos, Integer>> levelLights = new HashMap<>();
    
    // Maps ShipID -> (BlockPos -> LightLevel)
    public final Map<Long, Map<BlockPos, Integer>> shipLights = new HashMap<>();
    
    // Maps Dimension -> (EntityID -> (SourceID -> LightData))
    public final Map<ResourceKey<Level>, Map<Integer, Map<String, LightData>>> entityLitState = new HashMap<>();
    
    public void clear() {
        levelLights.clear();
        shipLights.clear();
        entityLitState.clear();
    }
}
