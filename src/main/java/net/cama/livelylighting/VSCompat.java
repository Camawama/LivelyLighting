package net.cama.livelylighting;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.Ship;
import org.valkyrienskies.mod.common.VSGameUtilsKt;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class VSCompat {

    public static boolean processEntity(Entity entity, ServerLevel level, LivelyConfig config, Map<Long, Map<BlockPos, LightManager.Cluster>> shipClusters, int lightLevel, boolean isNew, LightManager.LightType type) {
        Ship ship = VSGameUtilsKt.getShipObjectManagingPos(level, entity.blockPosition());
        if (ship != null) {
            Vector3d shipPos = new Vector3d(entity.getX(), entity.getEyeY(), entity.getZ());
            ship.getWorldToShip().transformPosition(shipPos);
            
            double mergeDistance = config.experimental.cluster_merge_distance;
            int gridX = (int) (shipPos.x / mergeDistance);
            int gridY = (int) (shipPos.y / mergeDistance);
            int gridZ = (int) (shipPos.z / mergeDistance);
            BlockPos gridPos = new BlockPos(gridX, gridY, gridZ);
            
            shipClusters.computeIfAbsent(ship.getId(), s -> new HashMap<>())
                        .computeIfAbsent(gridPos, k -> new LightManager.Cluster())
                        .add(shipPos.x, shipPos.y, shipPos.z, lightLevel, isNew, type);
            return true;
        }
        return false;
    }

    public static void pruneUnloadedShips(ServerLevel level, Map<Long, Map<BlockPos, Integer>> shipLights) {
        Set<Long> loadedShipIds = VSGameUtilsKt.getAllShips(level).stream().map(Ship::getId).collect(Collectors.toSet());
        shipLights.keySet().removeIf(id -> !loadedShipIds.contains(id));
    }

    public static void calculateShipLightFields(ServerLevel level, Map<Long, Map<BlockPos, LightManager.Cluster>> shipClusters, Map<Long, Map<BlockPos, Integer>> shipDesiredLights, Map<BlockPos, LightManager.Cluster> worldClusters, Map<BlockPos, Integer> worldDesiredLights, boolean smoothing, boolean clusterGrowing, int maxRadius) {
        Map<Long, Ship> shipLookup = VSGameUtilsKt.getAllShips(level).stream().collect(Collectors.toMap(Ship::getId, ship -> ship));

        for (Map.Entry<Long, Map<BlockPos, LightManager.Cluster>> entry : shipClusters.entrySet()) {
            Long shipId = entry.getKey();
            Map<BlockPos, LightManager.Cluster> clusters = entry.getValue();
            Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(shipId, k -> new HashMap<>());
            LightManager.calculateLightField(level, clusters, desired, smoothing, clusterGrowing, maxRadius);
        }
        
        for (LightManager.Cluster cluster : worldClusters.values()) {
            AABB aabb = new AABB(cluster.x - maxRadius, cluster.y - maxRadius, cluster.z - maxRadius, 
                                 cluster.x + maxRadius, cluster.y + maxRadius, cluster.z + maxRadius);
            for (Ship ship : VSGameUtilsKt.getShipsIntersecting(level, aabb)) {
                Vector3d shipPos = new Vector3d(cluster.x, cluster.y, cluster.z);
                ship.getWorldToShip().transformPosition(shipPos);
                
                Map<BlockPos, Integer> desired = shipDesiredLights.computeIfAbsent(ship.getId(), k -> new HashMap<>());
                
                LightManager.Cluster shipCluster = new LightManager.Cluster();
                shipCluster.add(shipPos.x, shipPos.y, shipPos.z, (int)cluster.strength, cluster.isNewSource, cluster.type);
                shipCluster.normalize();
                
                LightManager.calculateLightField(level, Map.of(BlockPos.containing(shipPos.x, shipPos.y, shipPos.z), shipCluster), desired, smoothing, clusterGrowing, maxRadius);
            }
        }
        
        for (Map.Entry<Long, Map<BlockPos, LightManager.Cluster>> entry : shipClusters.entrySet()) {
            Long shipId = entry.getKey();
            Ship ship = shipLookup.get(shipId);
            if (ship == null) continue;
            
            for (LightManager.Cluster cluster : entry.getValue().values()) {
                Vector3d worldPos = new Vector3d(cluster.x, cluster.y, cluster.z);
                ship.getShipToWorld().transformPosition(worldPos);
                
                LightManager.Cluster worldCluster = new LightManager.Cluster();
                worldCluster.add(worldPos.x, worldPos.y, worldPos.z, (int)cluster.strength, cluster.isNewSource, cluster.type);
                worldCluster.normalize();
                
                LightManager.calculateLightField(level, Map.of(BlockPos.containing(worldPos.x, worldPos.y, worldPos.z), worldCluster), worldDesiredLights, smoothing, clusterGrowing, maxRadius);
            }
        }
    }

    public static void applyShipChanges(ServerLevel level, Map<Long, Map<BlockPos, Integer>> shipLights, Map<Long, Map<BlockPos, Integer>> shipDesiredLights, Map<Long, Map<BlockPos, LightManager.Cluster>> shipClusters, boolean smoothing, int decayRate, int fadeInRate) {
        Map<Long, Ship> shipLookup = VSGameUtilsKt.getAllShips(level).stream().collect(Collectors.toMap(Ship::getId, ship -> ship));

        for (Map.Entry<Long, Map<BlockPos, Integer>> entry : shipDesiredLights.entrySet()) {
            Long shipId = entry.getKey();
            Ship ship = shipLookup.get(shipId);
            if (ship == null) continue;
            
            Map<BlockPos, Integer> desired = entry.getValue();
            Map<BlockPos, Integer> current = shipLights.computeIfAbsent(shipId, k -> new HashMap<>());
            
            Map<BlockPos, LightManager.Cluster> relevantClusters = shipClusters.getOrDefault(shipId, new HashMap<>());
            
            LightManager.applyChanges(level, current, desired, smoothing, decayRate, fadeInRate, relevantClusters, true);
        }
        
        Iterator<Map.Entry<Long, Map<BlockPos, Integer>>> shipIt = shipLights.entrySet().iterator();
        while (shipIt.hasNext()) {
            Map.Entry<Long, Map<BlockPos, Integer>> entry = shipIt.next();
            Long shipId = entry.getKey();
            if (!shipDesiredLights.containsKey(shipId)) {
                Map<BlockPos, Integer> current = entry.getValue();
                Ship ship = shipLookup.get(shipId);
                if (ship == null) {
                    shipIt.remove();
                    continue;
                }
                
                if (current.isEmpty()) {
                    shipIt.remove();
                } else {
                    LightManager.applyChanges(level, current, new HashMap<>(), smoothing, decayRate, fadeInRate, new HashMap<>(), true);
                    if (current.isEmpty()) {
                        shipIt.remove();
                    }
                }
            }
        }
    }
}
