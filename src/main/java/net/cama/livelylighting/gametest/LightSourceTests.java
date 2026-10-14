package net.cama.livelylighting.gametest;

import com.mojang.authlib.GameProfile;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.dynamiclighting.engine.LightManager;
import net.cama.livelylighting.dynamiclighting.engine.LightSources;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * What each light block stands for (LightSources, light_source_colors): a held light names its holder, the slot and
 * what is in it; a light fading out after its source was put away keeps the sources it was lit with; a light merged from
 * two holders names both. Clients take the colour from these (the owner, 2026-10-12: fast trails, a light put away and
 * merged ship lamps came out white). On the same 24x16x24 stone platform as LightTests.
 */
@GameTestHolder(LivelyLighting.MODID)
@PrefixGameTestTemplate(false)
public class LightSourceTests {
    private static final String PLATFORM = "platform";
    private static final int AIR = 2;
    private static final int SETTLE = 40;

    @BeforeBatch(batch = "lightSources")
    public static void clearSkies(ServerLevel level) {
        level.setWeatherParameters(Integer.MAX_VALUE / 2, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
    }

    private static FakePlayer player(GameTestHelper helper, String name, double x, double y, double z) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(("livelylighting-" + name).getBytes()), name));
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(at.x, at.y, at.z);
        player.setHealth(player.getMaxHealth());
        for (EquipmentSlot slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        helper.getLevel().addNewPlayer(player);
        return player;
    }

    private static Zombie torchZombie(GameTestHelper helper, double x, double y, double z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(x, y, z));
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        return zombie;
    }

    /** The placed lights within four blocks of a position (relative to the test) with the sources the engine keeps for them. */
    private static Map<BlockPos, long[]> sourcesNear(GameTestHelper helper, int x, int y, int z) {
        Map<BlockPos, long[]> all = LightManager.gameState().lightSources.get(helper.getLevel().dimension());
        Map<BlockPos, long[]> out = new HashMap<>();
        if (all == null) return out;
        BlockPos centre = helper.absolutePos(new BlockPos(x, y, z));
        all.forEach((pos, sources) -> {
            if (pos.distManhattan(centre) <= 6) out.put(pos, sources);
        });
        return out;
    }

    private static boolean names(long[] sources, Entity entity) {
        for (int i = 0; i + 1 < sources.length; i += 2) {
            if (sources[i] != LightSources.ENTITY || LightSources.entityId(sources[i + 1]) != entity.getId()) continue;
            int slot = LightSources.slot(sources[i + 1]);
            if ((LightSources.token(entity, slot) & 0x0FFFFFFF) == LightSources.token(sources[i + 1])) return true;
        }
        return false;
    }

    /** A zombie's torch light names the zombie, the slot it comes from and a token of the torch. */
    @GameTest(template = PLATFORM, timeoutTicks = 120, batch = "lightSources")
    public static void aHeldLightNamesItsHolderAndWhatItHolds(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-sources", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        helper.runAfterDelay(SETTLE, () -> {
            Map<BlockPos, long[]> near = sourcesNear(helper, 12, AIR + 1, 12);
            if (near.isEmpty()) helper.fail("no light round the torch zombie has its sources");
            near.forEach((pos, sources) -> {
                if (!names(sources, zombie)) helper.fail("the light at " + pos.toShortString() + " doesn't name the zombie and its torch: " + Arrays.toString(sources));
            });
            watcher.discard();
            zombie.discard();
            helper.succeed();
        });
    }

    /** A player's torch put away: its light fades over a few ticks and keeps naming the torch the whole time. */
    @GameTest(template = PLATFORM, timeoutTicks = 160, batch = "lightSources")
    public static void aLightPutAwayKeepsItsSourcesWhileItFades(GameTestHelper helper) {
        FakePlayer holder = player(helper, "holder-fade", 12, AIR, 12);
        holder.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));
        Map<BlockPos, long[]> lit = new HashMap<>();
        helper.runAfterDelay(SETTLE, () -> {
            lit.putAll(sourcesNear(helper, 12, AIR + 1, 12));
            if (lit.isEmpty()) helper.fail("no light round the player's torch has its sources");
            lit.forEach((pos, sources) -> {
                if (!names(sources, holder)) helper.fail("the light at " + pos.toShortString() + " doesn't name the player and its torch: " + Arrays.toString(sources));
            });
            holder.setItemSlot(EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        });
        int[] fadingSeen = new int[1];
        for (int t = 1; t <= 6; t++) {
            helper.runAfterDelay(SETTLE + t, () -> {
                Map<BlockPos, long[]> now = sourcesNear(helper, 12, AIR + 1, 12);
                now.forEach((pos, sources) -> {
                    long[] before = lit.get(pos);
                    if (before == null) return;
                    fadingSeen[0]++;
                    if (!Arrays.equals(before, sources)) helper.fail("the fading light at " + pos.toShortString() + " changed its sources: "
                            + Arrays.toString(before) + " -> " + Arrays.toString(sources));
                });
            });
        }
        helper.runAfterDelay(SETTLE + 8, () -> {
            if (fadingSeen[0] == 0) helper.fail("no light was seen fading after the torch was put away");
            holder.discard();
            helper.succeed();
        });
    }

    /** Two torch zombies in one spot are merged into one light, which names both. */
    @GameTest(template = PLATFORM, timeoutTicks = 120, batch = "lightSources")
    public static void aMergedLightNamesEverySource(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-merge", 4, AIR, 4);
        Zombie first = torchZombie(helper, 12.5, AIR, 12.5);
        Zombie second = torchZombie(helper, 12.5, AIR, 12.5);
        second.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.SOUL_TORCH));
        helper.runAfterDelay(SETTLE, () -> {
            Map<BlockPos, long[]> near = sourcesNear(helper, 12, AIR + 1, 12);
            boolean both = near.values().stream().anyMatch(sources -> names(sources, first) && names(sources, second));
            if (!both) {
                StringBuilder seen = new StringBuilder();
                near.forEach((pos, sources) -> seen.append(pos.toShortString()).append('=').append(Arrays.toString(sources)).append(' '));
                helper.fail("no light names both zombies: " + seen);
            }
            watcher.discard();
            first.discard();
            second.discard();
            helper.succeed();
        });
    }
}
