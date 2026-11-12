//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler;
import me.aleksilassila.litematica.printer.handler.handlers.print.WaterPrintTask;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
//#if MC >= 260300
//$$ import net.minecraft.tags.BlockTags;
//$$ import net.minecraft.world.level.block.Block;
//#endif
import net.minecraft.world.attribute.EnvironmentAttributeSystem;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Runs the water workflow and scheduler against real block states, with only the client boundary replaced. */
class WaterSupportRegressionTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrapWater() { bootstrap(); }

    @ParameterizedTest
    @ValueSource(strings = {"scaffolding", "waterlogged_scaffolding", "air"})
    void unsupportedTargetNeverQueuesIceAndWaitsOutsideReadyQueue(String support) throws Exception {
        try (var f = new WaterFixture()) {
            f.level.blocks.put(POS.below(), switch (support) {
                case "scaffolding" -> Blocks.SCAFFOLDING.defaultBlockState();
                case "waterlogged_scaffolding" -> Blocks.SCAFFOLDING.defaultBlockState()
                        .setValue(BlockStateProperties.WATERLOGGED, true);
                default -> Blocks.AIR.defaultBlockState();
            });

            for (long tick : new long[]{0, 5, 20, 100}) {
                f.now = tick;
                assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
                f.workflows.tick(f.level, f.schematic);
                assertTrue(f.workflows.isActiveTaskPos(POS));
                assertFalse(f.workflows.hasReadyWorkflow(), "An unsupported target must not monopolize ready work");
            }
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void existingIceWithoutWaterSupportIsReservedWithoutMining(boolean waterloggedTarget) throws Exception {
        try (var f = new WaterFixture()) {
            if (!waterloggedTarget) f.schematic.states.put(POS, Blocks.WATER.defaultBlockState());
            f.level.blocks.put(POS, Blocks.ICE.defaultBlockState());
            f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());

            assertNotNull(WaterPrintTask.tryCreate(f.context(POS), () -> f.now));
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            f.now = 100;
            f.workflows.tick(f.level, f.schematic);
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            assertTrue(f.mining.requests.isEmpty(), "Ice which cannot leave water must be preserved");
            assertFalse(f.workflows.isBreakingTaskPos(POS));
            assertFalse(f.workflows.hasReadyWorkflow());
        }
    }

    @Test void dryFinalBlockIsPreservedUntilIceCanLeaveWater() throws Exception {
        try (var f = new WaterFixture()) {
            f.level.blocks.put(POS, Blocks.OAK_SLAB.defaultBlockState());
            f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());

            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));

            assertTrue(f.mining.requests.isEmpty(), "Do not destroy the final block to begin an impossible workflow");
            assertFalse(f.workflows.hasReadyWorkflow());
            f.level.blocks.put(POS.below(), Blocks.STONE.defaultBlockState());
            f.workflows.wake(POS.below());
            f.workflows.tick(f.level, f.schematic);
            f.build(POS);
            assertEquals(List.of(POS), f.mining.requests);
        }
    }

    @Test void evaporatingEnvironmentDoesNotConsumeIceOnSolidSupport() throws Exception {
        try (var f = new WaterFixture()) {
            f.environment(true);

            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            assertFalse(f.workflows.hasReadyWorkflow());
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    @Test void supportUpdateWakesOnlyTheAffectedWaitingTarget() throws Exception {
        try (var f = new WaterFixture()) {
            BlockPos other = POS.east(5);
            f.schematic.states.put(other, f.required);
            f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());
            f.level.blocks.put(other.below(), Blocks.SCAFFOLDING.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            assertSame(PrintTaskBuildResult.SKIP, f.build(other));
            f.workflows.tick(f.level, f.schematic);

            f.level.blocks.put(POS.below(), Blocks.STONE.defaultBlockState());
            f.workflows.wake(POS.below());
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            assertTrue(f.workflows.isActiveTaskPos(other));
            assertIceAction(f.build(POS));
        }
    }

    @Test void supportIsRecheckedWithoutReceivingANeighbourPacket() throws Exception {
        try (var f = new WaterFixture()) {
            f.level.blocks.put(POS.below(), Blocks.AIR.defaultBlockState());
            assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
            assertFalse(f.workflows.hasReadyWorkflow());

            f.level.blocks.put(POS.below(), Blocks.STONE.defaultBlockState());
            f.now = 5;
            f.workflows.tick(f.level, f.schematic);

            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            assertIceAction(f.build(POS));
        }
    }

    @Test void losingSupportAfterIcePlacementPausesMiningAndResumesThroughCompletion() throws Exception {
        try (var f = new WaterFixture()) {
            var placed = f.build(POS);
            assertIceAction(placed);
            f.workflows.onActionQueued(placed.actionHandle(), f.context(POS), placed.action());
            f.level.blocks.put(POS, Blocks.ICE.defaultBlockState());
            f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());
            f.workflows.wake(POS);
            f.workflows.tick(f.level, f.schematic);
            f.build(POS);

            assertTrue(f.mining.requests.isEmpty());
            assertFalse(f.workflows.hasReadyWorkflow());

            f.level.blocks.put(POS.below(), Blocks.STONE.defaultBlockState());
            f.workflows.wake(POS.below());
            f.workflows.tick(f.level, f.schematic);
            assertEquals(List.of(POS), f.workflows.readyTargetPositions());
            f.build(POS);
            assertEquals(List.of(POS), f.mining.requests);

            f.level.blocks.put(POS, Blocks.WATER.defaultBlockState());
            f.workflows.wake(POS);
            assertSame(PrintTaskBuildResult.PASS, f.build(POS));
            f.level.blocks.put(POS, f.required);
            f.workflows.wake(POS);
            f.workflows.tick(f.level, f.schematic);
            assertFalse(f.workflows.hasActiveWorkflow());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"stone", "water", "bubble_column"})
    void solidAndLiquidSupportStillAllowIcePlacement(String support) throws Exception {
        try (var f = new WaterFixture()) {
            f.level.blocks.put(POS.below(), switch (support) {
                case "water" -> Blocks.WATER.defaultBlockState();
                case "bubble_column" -> Blocks.BUBBLE_COLUMN.defaultBlockState();
                default -> Blocks.STONE.defaultBlockState();
            });
            assertIceAction(f.build(POS));
        }
    }

    @Test void existingWaterAllowsFinalPlacementWithoutIceSupport() throws Exception {
        try (var f = new WaterFixture()) {
            f.level.blocks.put(POS, Blocks.WATER.defaultBlockState());
            f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());
            f.environment(true);

            assertSame(PrintTaskBuildResult.PASS, f.build(POS));
            assertNotNull(f.workflows.createActionHandle(f.context(POS), new me.aleksilassila.litematica.printer.printer.action.Action()));
            assertTrue(f.workflows.hasReadyWorkflow());
            assertTrue(f.mining.requests.isEmpty());
        }
    }

    //#if MC >= 260300
    //$$ @Test void supportRuleFollowsTheIceMeltingTag() throws Exception {
    //$$     try (var f = new WaterFixture()) {
    //$$         f.bindTags(f.stone, List.of());
    //$$         assertSame(PrintTaskBuildResult.SKIP, f.build(POS));
    //$$         assertFalse(f.workflows.hasReadyWorkflow());
    //$$
    //$$         f.bindTags(f.scaffolding, List.of(BlockTags.ICE_MELTS_WHEN_DESTROYED_ABOVE));
    //$$         f.level.blocks.put(POS.below(), Blocks.SCAFFOLDING.defaultBlockState());
    //$$         f.workflows.wake(POS.below());
    //$$         f.workflows.tick(f.level, f.schematic);
    //$$
    //$$         assertEquals(List.of(POS), f.workflows.readyTargetPositions());
    //$$         assertIceAction(f.build(POS));
    //$$     }
    //$$ }
    //#endif

    private static void assertIceAction(PrintTaskBuildResult result) {
        assertTrue(result.hasAction());
        assertArrayEquals(new net.minecraft.world.item.Item[]{Items.ICE}, result.action().getRequiredItems(Blocks.OAK_SLAB));
        assertNotNull(result.actionHandle());
    }

    static class Schematic extends WorldSchematic {
        Map<BlockPos, BlockState> states;
        Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) { return states.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
    }

    static class WaterPlayer extends TestPlayer {
        @Override public float getDestroySpeed(BlockState state) { return 1.0F; }
        @Override public boolean hasCorrectToolForDrops(BlockState state) { return true; }
        @Override public boolean blockActionRestricted(Level level, BlockPos pos, GameType mode) { return false; }
    }

    static class WaterGameMode extends TestGameMode {
        List<BlockPos> requests;
        @Override public BlockBreakResult litematica_printer$continueDestroyBlock(boolean prediction, BlockPos pos,
                Direction direction, boolean delayed, boolean switchTool) {
            requests.add(pos.immutable());
            return BlockBreakResult.COMPLETED_WAIT;
        }
    }

    static class WaterFixture extends Fixture {
        final boolean oldIce = Configs.Print.PRINT_ICE_FOR_WATER.getBooleanValue();
        final boolean oldSkip = Configs.Print.SKIP_WATERLOGGED_BLOCK.getBooleanValue();
        final Holder<Fluid> water = BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER);
        final List<TagKey<Fluid>> oldWaterTags = water.tags().toList();
        //#if MC >= 260300
        //$$ final Holder<Block> stone = BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.STONE);
        //$$ final Holder<Block> scaffolding = BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.SCAFFOLDING);
        //$$ final List<TagKey<Block>> oldStoneTags = stone.tags().toList();
        //$$ final List<TagKey<Block>> oldScaffoldingTags = scaffolding.tags().toList();
        //#endif
        final Schematic schematic;
        final WaterGameMode mining;
        final BlockState required = Blocks.OAK_SLAB.defaultBlockState().setValue(BlockStateProperties.WATERLOGGED, true);
        long now;
        final PrintWorkflowScheduler workflows = new PrintWorkflowScheduler(() -> now, block -> null);

        WaterFixture() throws Exception {
            Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
            Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(false);
            bindTags(water, List.of(FluidTags.WATER));
            //#if MC >= 260300
            //$$ bindTags(stone, List.of(BlockTags.ICE_MELTS_WHEN_DESTROYED_ABOVE));
            //$$ bindTags(scaffolding, List.of());
            //#endif
            environment(false);
            level.blocks.clear();
            level.blocks.put(POS.below(), Blocks.STONE.defaultBlockState());
            schematic = allocate(Schematic.class);
            schematic.states = new HashMap<>();
            schematic.states.put(POS, required);
            var player = allocate(WaterPlayer.class);
            player.inventory = client.player.getInventory();
            player.inventory.clearContent();
            set(player, Player.class, "inventory", player.inventory);
            set(player, Entity.class, "level", level);
            set(player, LocalPlayer.class, "connection", connection);
            client.player = player;
            mining = allocate(WaterGameMode.class);
            mining.requests = new ArrayList<>();
            client.gameMode = mining;
        }

        void environment(boolean evaporates) throws Exception {
            set(level, ClientLevel.class, "environmentAttributes", EnvironmentAttributeSystem.builder()
                    .addConstantLayer(EnvironmentAttributes.WATER_EVAPORATES, value -> evaporates).build());
        }
        SchematicBlockContext context(BlockPos pos) { return new SchematicBlockContext(client, level, schematic, pos); }
        PrintTaskBuildResult build(BlockPos pos) { return workflows.buildAction(context(pos)); }
        <T> void bindTags(Holder<T> holder, Collection<TagKey<T>> tags) throws Exception {
            var method = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            method.setAccessible(true);
            method.invoke(holder, tags);
        }
        @Override public void close() throws Exception {
            Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(oldIce);
            Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(oldSkip);
            bindTags(water, oldWaterTags);
            //#if MC >= 260300
            //$$ bindTags(stone, oldStoneTags);
            //$$ bindTags(scaffolding, oldScaffoldingTags);
            //#endif
            super.close();
        }
    }
}
//#endif
