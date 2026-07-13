package net.cama.livelylighting.data;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LivelyLightingData extends SavedData {
    private static final String DATA_NAME = "lively_lighting_data";

    private final Set<UUID> disabledEntities = new HashSet<>();
    private final Map<UUID, Integer> forcedLightLevels = new HashMap<>();
    private final Set<UUID> soundDisabledPlayers = new HashSet<>();

    // Every light block position the mod has placed, per dimension id. Survives
    // restarts and crashes so orphaned light blocks (chunk unloaded mid-fade,
    // server killed, etc.) can be swept when their chunk next loads.
    private final Map<String, Set<BlockPos>> placedLights = new HashMap<>();

    public static LivelyLightingData get(ServerLevel level) {
        return level.getServer().overworld().getDataStorage().computeIfAbsent(LivelyLightingData::load, LivelyLightingData::new, DATA_NAME);
    }

    public boolean isEntityDisabled(UUID uuid) {
        return disabledEntities.contains(uuid);
    }

    public void toggleEntity(UUID uuid) {
        if (disabledEntities.contains(uuid)) {
            disabledEntities.remove(uuid);
        } else {
            disabledEntities.add(uuid);
        }
        setDirty();
    }

    public void addForcedEntity(UUID uuid, int level) {
        forcedLightLevels.put(uuid, level);
        setDirty();
    }

    public void removeForcedEntity(UUID uuid) {
        forcedLightLevels.remove(uuid);
        setDirty();
    }

    public Integer getForcedLevel(UUID uuid) {
        return forcedLightLevels.get(uuid);
    }

    public boolean arePlayerSoundsDisabled(UUID uuid) {
        return soundDisabledPlayers.contains(uuid);
    }

    public void togglePlayerSounds(UUID uuid) {
        if (!soundDisabledPlayers.remove(uuid)) {
            soundDisabledPlayers.add(uuid);
        }
        setDirty();
    }

    public void addPlacedLight(ResourceKey<Level> dimension, BlockPos pos) {
        if (placedLights.computeIfAbsent(dimension.location().toString(), k -> new HashSet<>()).add(pos.immutable())) {
            setDirty();
        }
    }

    public void removePlacedLight(ResourceKey<Level> dimension, BlockPos pos) {
        Set<BlockPos> positions = placedLights.get(dimension.location().toString());
        if (positions != null && positions.remove(pos)) {
            setDirty();
        }
    }

    public Set<BlockPos> getPlacedLights(ResourceKey<Level> dimension) {
        return placedLights.getOrDefault(dimension.location().toString(), Collections.emptySet());
    }

    public static LivelyLightingData load(CompoundTag tag) {
        LivelyLightingData data = new LivelyLightingData();
        
        ListTag disabledList = tag.getList("DisabledEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < disabledList.size(); i++) {
            CompoundTag entry = disabledList.getCompound(i);
            data.disabledEntities.add(entry.getUUID("UUID"));
        }

        ListTag forcedList = tag.getList("ForcedEntities", Tag.TAG_COMPOUND);
        for (int i = 0; i < forcedList.size(); i++) {
            CompoundTag entry = forcedList.getCompound(i);
            data.forcedLightLevels.put(entry.getUUID("UUID"), entry.getInt("Level"));
        }

        ListTag soundDisabledList = tag.getList("SoundDisabledPlayers", Tag.TAG_COMPOUND);
        for (int i = 0; i < soundDisabledList.size(); i++) {
            CompoundTag entry = soundDisabledList.getCompound(i);
            data.soundDisabledPlayers.add(entry.getUUID("UUID"));
        }

        CompoundTag placedTag = tag.getCompound("PlacedLights");
        for (String dimensionId : placedTag.getAllKeys()) {
            Set<BlockPos> positions = new HashSet<>();
            for (long packed : placedTag.getLongArray(dimensionId)) {
                positions.add(BlockPos.of(packed));
            }
            data.placedLights.put(dimensionId, positions);
        }

        return data;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag disabledList = new ListTag();
        for (UUID uuid : disabledEntities) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("UUID", uuid);
            disabledList.add(entry);
        }
        tag.put("DisabledEntities", disabledList);

        ListTag forcedList = new ListTag();
        for (Map.Entry<UUID, Integer> entry : forcedLightLevels.entrySet()) {
            CompoundTag e = new CompoundTag();
            e.putUUID("UUID", entry.getKey());
            e.putInt("Level", entry.getValue());
            forcedList.add(e);
        }
        tag.put("ForcedEntities", forcedList);

        ListTag soundDisabledList = new ListTag();
        for (UUID uuid : soundDisabledPlayers) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("UUID", uuid);
            soundDisabledList.add(entry);
        }
        tag.put("SoundDisabledPlayers", soundDisabledList);

        CompoundTag placedTag = new CompoundTag();
        for (Map.Entry<String, Set<BlockPos>> entry : placedLights.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            long[] packed = new long[entry.getValue().size()];
            int i = 0;
            for (BlockPos pos : entry.getValue()) {
                packed[i++] = pos.asLong();
            }
            placedTag.put(entry.getKey(), new LongArrayTag(packed));
        }
        tag.put("PlacedLights", placedTag);

        return tag;
    }
}
