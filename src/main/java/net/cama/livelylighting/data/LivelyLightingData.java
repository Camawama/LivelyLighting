package net.cama.livelylighting.data;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class LivelyLightingData extends SavedData {
    private static final String DATA_NAME = "lively_lighting_data";

    private final Set<UUID> disabledEntities = new HashSet<>();
    private final Map<UUID, Integer> forcedLightLevels = new HashMap<>();

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
        
        return tag;
    }
}
