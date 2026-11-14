//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.Action;
import me.aleksilassila.litematica.printer.printer.action.ClickAction;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class UpstreamBlockSupportTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void aKelpStemUsesTheObtainableKelpItemInSourceWater() throws Exception {
        try (var f = new Fixture(1f, false)) {
            Action action = build(f, Blocks.WATER.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState());
            assertNotNull(action);
            assertArrayEquals(new net.minecraft.world.item.Item[]{Items.KELP}, action.getRequiredItems(Blocks.KELP_PLANT));
            assertEquals(java.util.Set.of(Direction.DOWN), action.getSides().keySet());
        }
    }

    @Test void aKelpTipAlreadyAtARequiredStemIsNotBrokenBeforeItsUpperNeighborArrives() throws Exception {
        try (var f = new Fixture(1f, false)) {
            assertNull(build(f, Blocks.KELP.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState()));
            assertFalse(f.interactions.isBreakPending(TARGET));
        }
    }

    @Test void tallGrassAndLargeFernsGrowTheirExistingObtainableSmallForms() throws Exception {
        try (var f = new Fixture(1f, false)) {
            Action grass = build(f, Blocks.SHORT_GRASS.defaultBlockState(), Blocks.TALL_GRASS.defaultBlockState());
            assertInstanceOf(ClickAction.class, grass);
            assertArrayEquals(new net.minecraft.world.item.Item[]{Items.BONE_MEAL}, grass.getRequiredItems(Blocks.TALL_GRASS));
            Action fern = build(f, Blocks.FERN.defaultBlockState(), Blocks.LARGE_FERN.defaultBlockState());
            assertInstanceOf(ClickAction.class, fern);
            assertArrayEquals(new net.minecraft.world.item.Item[]{Items.BONE_MEAL}, fern.getRequiredItems(Blocks.LARGE_FERN));
        }
    }

    @Test void tallSeagrassGrowsTheLowerSeagrassInsteadOfBreakingIt() throws Exception {
        try (var f = new Fixture(1f, false)) {
            Action action = build(f, Blocks.SEAGRASS.defaultBlockState(), Blocks.TALL_SEAGRASS.defaultBlockState());
            assertInstanceOf(ClickAction.class, action);
            assertArrayEquals(new net.minecraft.world.item.Item[]{Items.BONE_MEAL}, action.getRequiredItems(Blocks.TALL_SEAGRASS));
            assertFalse(f.interactions.isBreakPending(TARGET));
        }
    }

    @Test void leafLitterOnlyAddsMissingSegmentsWhenTheDirectionAlreadyMatches() throws Exception {
        try (var f = new Fixture(1f, false)) {
            BlockState one = Blocks.LEAF_LITTER.defaultBlockState().setValue(BlockStateProperties.SEGMENT_AMOUNT, 1);
            BlockState three = one.setValue(BlockStateProperties.SEGMENT_AMOUNT, 3);
            assertInstanceOf(ClickAction.class, build(f, one, three));
            assertNull(build(f, three, three));
        }
    }

    @Test void dryAquaticPlantsNeverProduceAnAction() throws Exception {
        try (var f = new Fixture(1f, false)) {
            assertNull(build(f, Blocks.AIR.defaultBlockState(), Blocks.KELP_PLANT.defaultBlockState()));
            assertNull(build(f, Blocks.AIR.defaultBlockState(), Blocks.SEAGRASS.defaultBlockState()));
        }
    }

    @Test void matureGrowthTipsUseShearsButNeverInventAnIntermediateAgeAdjustment() throws Exception {
        try (var f = new Fixture(1f, false)) {
            for (var block : java.util.List.of(Blocks.KELP, Blocks.CAVE_VINES, Blocks.WEEPING_VINES, Blocks.TWISTING_VINES)) {
                BlockState young = block.defaultBlockState().setValue(BlockStateProperties.AGE_25, 3);
                Action action = build(f, young, young.setValue(BlockStateProperties.AGE_25, 25));
                assertInstanceOf(ClickAction.class, action, block.toString());
                assertArrayEquals(new net.minecraft.world.item.Item[]{Items.SHEARS}, action.getRequiredItems(block));
                assertNull(build(f, young, young.setValue(BlockStateProperties.AGE_25, 10)));
            }
        }
    }

    @Test void smallDripleafUsesTheLowerHalfFacingAndNeverPlacesItsUpperHalfSeparately() throws Exception {
        try (var f = new Fixture(1f, false)) {
            BlockState lower = Blocks.SMALL_DRIPLEAF.defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING, Direction.EAST);
            Action action = build(f, Blocks.AIR.defaultBlockState(), lower);
            assertNotNull(action);
            assertEquals(java.util.Set.of(Direction.DOWN), action.getSides().keySet());
            assertEquals(new me.aleksilassila.litematica.printer.printer.PlayerLook(Direction.WEST).yaw, action.getPlayerLook().yaw);
            assertTrue(action.isNeedWaitModifyLook());
            assertNull(build(f, Blocks.AIR.defaultBlockState(), lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER)));
        }
    }

    private static Action build(Fixture f, BlockState current, BlockState required) throws Exception {
        if (!(f.level instanceof PlantLevel)) {
            var level = alloc(PlantLevel.class);
            set(f, Fixture.class, "level", level);
            f.client.level = level;
            set(f.player, net.minecraft.world.entity.Entity.class, "level", level);
        }
        f.level.states = new HashMap<>();
        f.level.states.put(TARGET, current);
        boolean grass = required.is(Blocks.TALL_GRASS) || required.is(Blocks.LARGE_FERN);
        boolean dripleaf = required.is(Blocks.SMALL_DRIPLEAF);
        f.level.states.put(TARGET.below(), (grass ? Blocks.FARMLAND : dripleaf ? Blocks.CLAY : Blocks.STONE).defaultBlockState());
        if (dripleaf && required.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)
                == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.UPPER) {
            f.level.states.put(TARGET.below(), required.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,
                    net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER));
        }
        f.level.states.put(TARGET.above(), grass || dripleaf ? Blocks.AIR.defaultBlockState() : Blocks.WATER.defaultBlockState());
        var schematic = alloc(DoublePlantUpperRecoveryTest.Schematic.class);
        var context = new SchematicBlockContext(f.client, f.level, schematic, TARGET);
        set(context, SchematicBlockContext.class, "currentState", current);
        set(context, SchematicBlockContext.class, "requiredState", required);
        var water = net.minecraft.core.registries.BuiltInRegistries.FLUID.wrapAsHolder(net.minecraft.world.level.material.Fluids.WATER);
        var soil = net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.FARMLAND);
        var oldWaterTags = water.tags().toList();
        var oldSoilTags = soil.tags().toList();
        var clay = net.minecraft.core.registries.BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.CLAY);
        var oldClayTags = clay.tags().toList();
        var bind = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bind.setAccessible(true);
        try {
            bind.invoke(water, java.util.List.of(net.minecraft.tags.FluidTags.WATER));
            bind.invoke(soil, java.util.List.of(net.minecraft.tags.BlockTags.SUPPORTS_VEGETATION));
            bind.invoke(clay, java.util.List.of(net.minecraft.tags.BlockTags.SUPPORTS_SMALL_DRIPLEAF));
            return new Guides().buildAction(context).orElse(null);
        } finally {
            bind.invoke(water, oldWaterTags);
            bind.invoke(soil, oldSoilTags);
            bind.invoke(clay, oldClayTags);
        }
    }

    static class PlantLevel extends Level {
        PlantLevel() { super(); }
        @Override public net.minecraft.world.level.material.FluidState getFluidState(net.minecraft.core.BlockPos pos) {
            return this.getBlockState(pos).getFluidState();
        }
    }
}
//#endif
