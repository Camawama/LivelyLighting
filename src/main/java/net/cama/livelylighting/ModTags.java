package net.cama.livelylighting;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;

public class ModTags {
    public static class Items {
        public static final TagKey<Item> LIGHT_LEVEL_15 = tag("light_level/15");
        public static final TagKey<Item> LIGHT_LEVEL_12 = tag("light_level/12");
        public static final TagKey<Item> LIGHT_LEVEL_9 = tag("light_level/9");
        public static final TagKey<Item> LIGHT_LEVEL_6 = tag("light_level/6");
        public static final TagKey<Item> LIGHT_LEVEL_3 = tag("light_level/3");

        private static TagKey<Item> tag(String name) {
            return ItemTags.create(new ResourceLocation(LivelyLighting.MODID, name));
        }
    }

    public static class Entities {
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_15 = tag("light_level/15");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_12 = tag("light_level/12");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_9 = tag("light_level/9");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_6 = tag("light_level/6");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_3 = tag("light_level/3");

        private static TagKey<EntityType<?>> tag(String name) {
            return TagKey.create(ForgeRegistries.ENTITY_TYPES.getRegistryKey(), new ResourceLocation(LivelyLighting.MODID, name));
        }
    }
}
