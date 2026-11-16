package net.cama.livelylighting.data;

import net.cama.livelylighting.LivelyLighting;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.registries.ForgeRegistries;

public class ModTags {
    public static class Items {
        public static final TagKey<Item> LIGHT_LEVEL_15 = tag("light_level/15");
        public static final TagKey<Item> LIGHT_LEVEL_12 = tag("light_level/12");
        public static final TagKey<Item> LIGHT_LEVEL_9 = tag("light_level/9");
        public static final TagKey<Item> LIGHT_LEVEL_6 = tag("light_level/6");
        public static final TagKey<Item> LIGHT_LEVEL_3 = tag("light_level/3");
        /** An item in this tag is a spot light in hand with the default cone, when it has no definition of its own (0.5.0). */
        public static final TagKey<Item> FLASHLIGHTS = tag("flashlights");
        /**
         * Lanterns that hang from a belt (0.10.1): Curios' belt slot takes this tag ({@code data/curios/tags/items/belt.json}),
         * a lantern there is drawn hanging at the hip, and taking one out clinks its chain when it has no sound of its own.
         * Vanilla's two and the pack's mods' lanterns, each optional; a pack adds its own with a tag of the same name.
         */
        public static final TagKey<Item> BELT_LANTERNS = tag("belt_lanterns");

        private static TagKey<Item> tag(String name) {
            return ItemTags.create(ResourceLocation.fromNamespaceAndPath(LivelyLighting.MODID, name));
        }
    }

    public static class Blocks {
        /** A luminous block in this tag on a ship or contraption is a headlight: a spot light along its facing (0.5.0). */
        public static final TagKey<Block> HEADLIGHTS = BlockTags.create(ResourceLocation.fromNamespaceAndPath(LivelyLighting.MODID, "headlights"));
    }

    public static class Entities {
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_15 = tag("light_level/15");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_12 = tag("light_level/12");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_9 = tag("light_level/9");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_6 = tag("light_level/6");
        public static final TagKey<EntityType<?>> LIGHT_LEVEL_3 = tag("light_level/3");

        private static TagKey<EntityType<?>> tag(String name) {
            return TagKey.create(ForgeRegistries.ENTITY_TYPES.getRegistryKey(), ResourceLocation.fromNamespaceAndPath(LivelyLighting.MODID, name));
        }
    }
}
