package net.cama.livelylighting.event;

import net.cama.livelylighting.LivelyLighting;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.event.entity.living.MobSpawnEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = LivelyLighting.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ZombieTorchSpawnHandler {

    // 1 in 100 = 0.01 (1%). Tweak to taste.
    private static final float TORCH_CHANCE = 0.01f;

    @SubscribeEvent
    public static void onFinalizeSpawn(MobSpawnEvent.FinalizeSpawn event) {
        // Safety: only do this on the logical server
        if (event.getLevel().isClientSide()) return;

        if (!(event.getEntity() instanceof Zombie zombie)) return;

        // Optional: don't override existing held items
        if (!zombie.getMainHandItem().isEmpty()) return;

        // Optional: skip baby zombies if you want
        // if (zombie.isBaby()) return;

        // Use the mob's random for deterministic-ish behavior
        if (zombie.getRandom().nextFloat() >= TORCH_CHANCE) return;

        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));

        // Optional: prevent torch drops (keeps it from becoming a farmable loot source)
        zombie.setDropChance(EquipmentSlot.MAINHAND, 0.0f);
    }

    private ZombieTorchSpawnHandler() {}
}
