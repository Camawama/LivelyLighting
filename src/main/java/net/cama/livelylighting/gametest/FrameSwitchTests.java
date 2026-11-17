package net.cama.livelylighting.gametest;

import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * A frame's light switch (0.10.2, {@code source.FrameSwitch}): a redstone signal into the block an item frame hangs on
 * switches its light off, as the server's light blocks and the gameplay light read it ({@link LightCalculator}).
 */
@GameTestHolder(LivelyLighting.MODID)
@PrefixGameTestTemplate(false)
public final class FrameSwitchTests {
    private static final String PLATFORM = "platform";

    private FrameSwitchTests() {}

    @GameTest(template = PLATFORM, timeoutTicks = 60)
    public static void aLeverOnTheFramesBlockSwitchesItsLight(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LivelyConfig config = LivelyConfig.get();
        BlockPos post = helper.absolutePos(new BlockPos(1, 2, 1));
        level.setBlock(post, Blocks.STONE.defaultBlockState(), 3);
        BlockPos leverAt = post.above();
        level.setBlock(leverAt, Blocks.LEVER.defaultBlockState().setValue(LeverBlock.FACE, AttachFace.FLOOR), 3);
        ItemFrame glow = new GlowItemFrame(level, post.south(), Direction.SOUTH);
        glow.setItem(new ItemStack(Items.TORCH));
        level.addFreshEntity(glow);
        ItemFrame plain = new ItemFrame(level, post.north(), Direction.NORTH);
        plain.setItem(new ItemStack(Items.TORCH));
        level.addFreshEntity(plain);
        float glowOff = LightCalculator.getBestLightLevel(glow, level, config).level;
        float plainOff = LightCalculator.getBestLightLevel(plain, level, config).level;
        if (glowOff <= 0 || plainOff <= 0) {
            helper.fail("unpowered frames should shine (glow " + glowOff + ", plain " + plainOff + ")");
            return;
        }
        BlockState lever = level.getBlockState(leverAt);
        ((LeverBlock) Blocks.LEVER).pull(lever, level, leverAt);
        helper.runAfterDelay(4, () -> {
            float glowOn = LightCalculator.getBestLightLevel(glow, level, config).level;
            float plainOn = LightCalculator.getBestLightLevel(plain, level, config).level;
            if (glowOn > 0 || plainOn > 0) {
                helper.fail("with the lever on, both frames should be dark (glow " + glowOn + ", plain " + plainOn + ")");
                return;
            }
            ((LeverBlock) Blocks.LEVER).pull(level.getBlockState(leverAt), level, leverAt);
            helper.runAfterDelay(4, () -> {
                float glowAgain = LightCalculator.getBestLightLevel(glow, level, config).level;
                if (glowAgain <= 0) {
                    helper.fail("with the lever off again, the frame should shine (" + glowAgain + ")");
                    return;
                }
                System.out.println("[LivelyLighting gametest] frame switch: unpowered glow " + glowOff + " plain " + plainOff
                        + ", lever on " + glowOn + "/" + plainOn + ", lever off " + glowAgain);
                glow.discard();
                plain.discard();
                helper.succeed();
            });
        });
    }

    @GameTest(template = PLATFORM, timeoutTicks = 40)
    public static void theSwitchCanBeTurnedOff(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        LivelyConfig config = LivelyConfig.get();
        boolean was = config.frame_lights_off_when_powered;
        try {
            config.frame_lights_off_when_powered = false;
            BlockPos post = helper.absolutePos(new BlockPos(1, 2, 1));
            level.setBlock(post, Blocks.STONE.defaultBlockState(), 3);
            level.setBlock(post.above(), Blocks.REDSTONE_BLOCK.defaultBlockState(), 3);
            ItemFrame frame = new GlowItemFrame(level, post.south(), Direction.SOUTH);
            frame.setItem(new ItemStack(Items.TORCH));
            level.addFreshEntity(frame);
            float lit = LightCalculator.getBestLightLevel(frame, level, config).level;
            frame.discard();
            if (lit <= 0) helper.fail("with frame_lights_off_when_powered off, a powered frame should still shine (" + lit + ")");
            else helper.succeed();
        } finally {
            config.frame_lights_off_when_powered = was;
        }
    }
}
