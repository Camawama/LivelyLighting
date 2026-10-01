package net.cama.livelylighting.gametest;

import com.mojang.authlib.GameProfile;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.data.LivelyLightingData;
import net.cama.livelylighting.dynamiclighting.engine.LightManager;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.BeforeBatch;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LightBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.FakePlayer;
import net.minecraftforge.common.util.FakePlayerFactory;
import net.minecraftforge.common.util.ITeleporter;
import net.minecraft.world.level.portal.PortalInfo;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * The lifecycle of a dynamic light: it appears next to its source and goes away with it, by every
 * way a source can go, and a purge leaves nothing behind. Each test runs on a 24x16x24 stone
 * platform (the structure block is one below it, so the floor is y = 1 and the first air layer
 * y = 2). The engine only lights entities near a player, so every test puts a fake player on the
 * level (the level's player list, not just an entity).
 */
@GameTestHolder(LivelyLighting.MODID)
@PrefixGameTestTemplate(false)
public class LightTests {
    private static final String PLATFORM = "platform";

    /**
     * The gametest world's weather is random, and rain under the open sky puts a torch out
     * (water-sensitive lights), which would make every "lit" expectation fail: a dry, still sky
     * for the whole batch.
     */
    @BeforeBatch(batch = "defaultBatch")
    public static void clearSkies(ServerLevel level) {
        dry(level);
    }

    @BeforeBatch(batch = "purge")
    public static void clearSkiesForThePurge(ServerLevel level) {
        dry(level);
    }

    private static void dry(ServerLevel level) {
        level.setWeatherParameters(Integer.MAX_VALUE / 2, 0, false, false);
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
    }
    private static final int AIR = 2;
    /** How long a light may take to appear (the ignition budget and the unlit-entity check interval) or to fade. */
    private static final int SETTLE = 40;

    private static FakePlayer player(GameTestHelper helper, String name, double x, double y, double z) {
        FakePlayer player = FakePlayerFactory.get(helper.getLevel(),
                new GameProfile(UUID.nameUUIDFromBytes(("livelylighting-" + name).getBytes()), name));
        Vec3 at = helper.absoluteVec(new Vec3(x, y, z));
        player.setPos(at.x, at.y, at.z);
        player.setHealth(player.getMaxHealth());
        for (EquipmentSlot slot : EquipmentSlot.values()) player.setItemSlot(slot, ItemStack.EMPTY);
        helper.getLevel().addNewPlayer(player); // the engine looks at the level's players; a fake isn't one until added
        return player;
    }

    private static Zombie torchZombie(GameTestHelper helper, double x, double y, double z) {
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new Vec3(x, y, z));
        zombie.setNoAi(true);
        zombie.setPersistenceRequired();
        zombie.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.TORCH));
        zombie.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET)); // no burning in daylight, which would be a light of its own
        return zombie;
    }

    /** The brightest light block within three blocks of the position, or 0. */
    private static int lightNear(GameTestHelper helper, int x, int y, int z) {
        ServerLevel level = helper.getLevel();
        int best = 0;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockState state = level.getBlockState(helper.absolutePos(new BlockPos(x + dx, y + dy, z + dz)));
                    if (state.is(Blocks.LIGHT)) best = Math.max(best, state.getValue(LightBlock.LEVEL));
                }
            }
        }
        return best;
    }

    private static void expectLit(GameTestHelper helper, int x, int y, int z, String who) {
        if (lightNear(helper, x, y, z) <= 0) helper.fail(who + " got no light block within three blocks");
    }

    private static void expectDark(GameTestHelper helper, int x, int y, int z, String what) {
        int level = lightNear(helper, x, y, z);
        if (level > 0) helper.fail("a light block of level " + level + " lingers near " + what);
    }



    /** A held torch lights its holder; the light goes when the holder is discarded (a dropped item picked up, a mob despawned). */
    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void aDiscardedSourceLeavesNoLight(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-discard", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR + 1, 12, "a zombie holding a torch");
            zombie.discard();
        });
        helper.runAfterDelay(2 * SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "the discarded zombie");
            watcher.discard();
            helper.succeed();
        });
    }

    /** A killed holder's light goes with it, once the body is gone. */
    @GameTest(template = PLATFORM, timeoutTicks = 240)
    public static void aKilledSourceLeavesNoLight(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-kill", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR + 1, 12, "a zombie holding a torch");
            zombie.kill();
        });
        helper.runAfterDelay(3 * SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "the killed zombie");
            watcher.discard();
            helper.succeed();
        });
    }

    /** A holder that leaves the dimension takes its light with it: nothing stays behind here. */
    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void aSourceLeavingTheDimensionLeavesNoLight(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-dimension", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR + 1, 12, "a zombie holding a torch");
            ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
            if (nether == null) {
                helper.fail("no Nether on this server");
                return;
            }
            Entity moved = zombie.changeDimension(nether, new ITeleporter() {
                @Override
                public PortalInfo getPortalInfo(Entity entity, ServerLevel destination, java.util.function.Function<ServerLevel, PortalInfo> defaultPortalInfo) {
                    return new PortalInfo(new Vec3(0.5, 64.0, 0.5), Vec3.ZERO, entity.getYRot(), entity.getXRot());
                }

                @Override
                public boolean playTeleportSound(net.minecraft.server.level.ServerPlayer player, ServerLevel sourceWorld, ServerLevel destWorld) {
                    return false;
                }
            });
            if (moved == null) helper.fail("the zombie could not change dimension");
        });
        helper.runAfterDelay(2 * SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "the zombie that left for the Nether");
            watcher.discard();
            helper.succeed();
        });
    }

    /** A dropped torch lights its spot; picked up, it leaves no light behind. */
    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void aPickedUpItemLeavesNoLight(GameTestHelper helper) {
        // well away from the torch: once picked up it is in the picker's hand, a light of its own
        FakePlayer picker = player(helper, "picker", 3, AIR, 3);
        Vec3 at = helper.absoluteVec(new Vec3(12.5, AIR + 0.5, 12.5));
        ItemEntity torch = new ItemEntity(helper.getLevel(), at.x, at.y, at.z, new ItemStack(Items.TORCH));
        torch.setDeltaMovement(Vec3.ZERO);
        torch.setPickUpDelay(10000);
        helper.getLevel().addFreshEntity(torch);
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR, 12, "a dropped torch");
            torch.setPickUpDelay(0);
            torch.playerTouch(picker); // what walking over it does (playerTouch needs no distance)
            if (!torch.isRemoved()) helper.fail("the torch was not picked up");
        });
        helper.runAfterDelay(2 * SETTLE, () -> {
            expectDark(helper, 12, AIR, 12, "the picked-up torch");
            // the player now holds the torch: their own light is at their eyes, not at the old spot
            picker.discard();
            helper.succeed();
        });
    }

    /** /ll toggle on a lit entity: its light goes and is never frozen in place by the distance LOD. */
    @GameTest(template = PLATFORM, timeoutTicks = 200)
    public static void aSwitchedOffSourceLeavesNoLight(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-toggle", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        LivelyLightingData data = LivelyLightingData.get(helper.getLevel());
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR + 1, 12, "a zombie holding a torch");
            data.toggleEntity(zombie.getUUID());
        });
        helper.runAfterDelay(2 * SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "the switched-off zombie");
            data.toggleEntity(zombie.getUUID()); // leave the save data as it was
            zombie.discard();
            watcher.discard();
            helper.succeed();
        });
    }

    /** The light block's own item, the tool for seeing light blocks, is never a light source. */
    @GameTest(template = PLATFORM, timeoutTicks = 120)
    public static void theLightItemIsNoSource(GameTestHelper helper) {
        FakePlayer editor = player(helper, "editor", 12, AIR, 12);
        editor.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(Items.LIGHT));
        helper.runAfterDelay(SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "a player holding the light item");
            editor.discard();
            helper.succeed();
        });
    }

    /**
     * A purge removes the mod's light blocks, and with "all" a light block the mod has no record of
     * too. In its own batch: a purge clears every light on the level, the other tests' included.
     */
    @GameTest(template = PLATFORM, timeoutTicks = 200, batch = "purge")
    public static void aPurgeLeavesNothing(GameTestHelper helper) {
        FakePlayer watcher = player(helper, "watcher-purge", 4, AIR, 4);
        Zombie zombie = torchZombie(helper, 12, AIR, 12);
        BlockPos orphan = helper.absolutePos(new BlockPos(18, AIR + 1, 18));
        helper.runAfterDelay(SETTLE, () -> {
            expectLit(helper, 12, AIR + 1, 12, "a zombie holding a torch");
            // a light block with no record, as a crash before the records were saved leaves behind
            helper.getLevel().setBlock(orphan, Blocks.LIGHT.defaultBlockState().setValue(LightBlock.LEVEL, 15), 3);
            zombie.discard(); // so nothing re-lights after the purge
            LightManager.PurgeResult plain = LightManager.purgeAllLights(helper.getLevel().getServer(), false);
            if (plain.recordedRemoved() <= 0) helper.fail("the purge removed none of the mod's light blocks");
            if (!helper.getLevel().getBlockState(orphan).is(Blocks.LIGHT)) helper.fail("a plain purge removed a light block it had no record of");
            LightManager.PurgeResult all = LightManager.purgeAllLights(helper.getLevel().getServer(), true);
            if (all.unrecordedRemoved() <= 0) helper.fail("purge all removed no unrecorded light block");
            if (helper.getLevel().getBlockState(orphan).is(Blocks.LIGHT)) helper.fail("the unrecorded light block survived purge all");
        });
        helper.runAfterDelay(2 * SETTLE, () -> {
            expectDark(helper, 12, AIR + 1, 12, "the purged zombie light");
            watcher.discard();
            helper.succeed();
        });
    }
}
