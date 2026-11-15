package net.cama.livelylighting.gametest;

import com.google.gson.JsonObject;
import net.cama.livelylighting.LivelyConfig;
import net.cama.livelylighting.LivelyLighting;
import net.cama.livelylighting.dynamiclighting.engine.LightCalculator;
import net.cama.livelylighting.source.PlacedLight;
import net.cama.livelylighting.source.UvDefs;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * What a block's item gives in the hand (0.10.0, {@link PlacedLight}: the light of the block set down without power, not
 * of its default state), and the list of what glows under ultraviolet ({@link UvDefs}).
 */
@GameTestHolder(LivelyLighting.MODID)
@PrefixGameTestTemplate(false)
public class HeldLightTests {
    private static final String PLATFORM = "platform";

    private static void placed(GameTestHelper helper, Block block, int expected) {
        PlacedLight.forget();
        int light = PlacedLight.of(block, helper.getLevel());
        if (light != expected) helper.fail(block + " set down gives " + light + ", expected " + expected);
    }

    @GameTest(template = PLATFORM, batch = "heldLight")
    public static void aBlockIsAskedWhatItIsWhenSetDownWithoutPower(GameTestHelper helper) {
        placed(helper, Blocks.REDSTONE_LAMP, 0);
        placed(helper, Blocks.FURNACE, 0);
        placed(helper, Blocks.CANDLE, 0);
        placed(helper, Blocks.REDSTONE_ORE, 0);
        // its default state is in water and shines; set down on land it is dark
        placed(helper, Blocks.SEA_PICKLE, 0);
        placed(helper, Blocks.GLOWSTONE, 15);
        placed(helper, Blocks.REDSTONE_TORCH, 7);
        placed(helper, Blocks.CAMPFIRE, 15);
        // its placement rule asks where the player looks; without one its default state stands
        placed(helper, Blocks.LANTERN, 15);
        helper.succeed();
    }

    @GameTest(template = PLATFORM, batch = "heldLight")
    public static void aLampThatNeedsRedstoneGivesItsItemNoLight(GameTestHelper helper) {
        LivelyConfig config = LivelyConfig.get();
        boolean auto = config.auto_detect_block_light;
        config.auto_detect_block_light = true;
        try {
            LightCalculator.reloadConfig();
            // a holder is seen in the level first, as in play: the calculator learns where to ask
            LightCalculator.getItemLightLevel(new ItemStack(Items.STONE), helper.spawn(EntityType.ARMOR_STAND, 2, 2, 2), helper.getLevel(), config);
            int lamp = LightCalculator.definitionOf(new ItemStack(Items.REDSTONE_LAMP), config).level;
            int pickle = LightCalculator.definitionOf(new ItemStack(Items.SEA_PICKLE), config).level;
            int froglight = LightCalculator.definitionOf(new ItemStack(Items.OCHRE_FROGLIGHT), config).level;
            int torch = LightCalculator.definitionOf(new ItemStack(Items.REDSTONE_TORCH), config).level;
            if (lamp != 0) helper.fail("a redstone lamp in the hand has a level of " + lamp);
            if (pickle != 0) helper.fail("a sea pickle in the hand has a level of " + pickle);
            if (froglight != 15) helper.fail("a froglight in the hand has a level of " + froglight);
            if (torch != 7) helper.fail("a redstone torch in the hand has a level of " + torch);
        } finally {
            config.auto_detect_block_light = auto;
        }
        helper.succeed();
    }

    @GameTest(template = PLATFORM, batch = "heldLight")
    public static void aUvDefinitionIsReadAsWritten(GameTestHelper helper) {
        JsonObject json = new JsonObject();
        json.addProperty("glow_color", "#3DFFD2");
        json.addProperty("strength", 0.9f);
        json.addProperty("min_brightness", 0.25f);
        json.addProperty("hue", "#00FF00");
        json.addProperty("hue_range", 36);
        float[] glow = UvDefs.glow(json);
        if (glow == null || glow.length != UvDefs.PARAMETERS) helper.fail("no glow read");
        if (Math.abs(glow[0] - 0x3D / 255f) > 1e-4 || Math.abs(glow[1] - 1f) > 1e-4 || Math.abs(glow[2] - 0xD2 / 255f) > 1e-4) helper.fail("the colour is not as written");
        if (Math.abs(glow[3] - 0.9f) > 1e-4) helper.fail("the strength is " + glow[3]);
        if (Math.abs(glow[4] - 0.25f) > 1e-4 || glow[5] != 1f) helper.fail("the brightness window is " + glow[4] + " to " + glow[5]);
        if (Math.abs(glow[6] - 1f / 3f) > 1e-3 || Math.abs(glow[7] - 0.1f) > 1e-4) helper.fail("the hue is " + glow[6] + " within " + glow[7]);
        JsonObject none = new JsonObject();
        none.addProperty("glow_color", "uv");
        if (UvDefs.glow(none) != null) helper.fail("an ultraviolet glow colour was taken for a colour");
        // the files written into an empty folder: spiders by pattern, the skeleton by id, a cow not at all
        UvDefs.reload();
        if (UvDefs.any()) {
            if (UvDefs.get(EntityType.SPIDER) == null || UvDefs.get(EntityType.CAVE_SPIDER) == null) helper.fail("spiders are not on the default list");
            if (UvDefs.get(EntityType.SKELETON) == null) helper.fail("the skeleton is not on the default list");
            if (UvDefs.get(EntityType.COW) != null) helper.fail("a cow is on the list");
        }
        helper.succeed();
    }
}
