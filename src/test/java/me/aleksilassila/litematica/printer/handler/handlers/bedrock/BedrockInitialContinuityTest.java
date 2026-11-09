//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Captures the real initial builder; no fixture invents a successful placement or piston event. */
class BedrockInitialContinuityTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final List<BlockPos> TARGETS = List.of(FIRST, FIRST.east(2), FIRST.east(4),
            FIRST.south(3), FIRST.south(3).east(2), FIRST.south(3).east(4));

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(ints = {0, 1, 3, 6})
    void partialTorchStockDoesNotReduceTheSixAvailablePrimingPistons(int torchCount) throws Exception {
        try (var f = new BedrockInitialBatchPlacementTest.Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, torchCount));
            var requests = new ArrayList<BedrockPlacer.PistonRequest>();
            var expected = new ArrayList<BedrockPlacer.PistonRequest>();
            for (int i = 0; i < TARGETS.size(); i++) {
                var target = TARGETS.get(i);
                var support = target.north();
                f.level.blocks.put(target, Blocks.BEDROCK.defaultBlockState());
                f.level.blocks.put(support, Blocks.BEDROCK.defaultBlockState());
                var power = new BedrockTorchPlacement(support, Direction.UP);
                requests.add(new BedrockPlacer.PistonRequest(target.above(), Direction.UP, power));
                expected.add(new BedrockPlacer.PistonRequest(target.above(), Direction.UP, i < torchCount ? power : null));
            }
            var beforeWorld = new HashMap<>(f.level.blocks);
            f.readyInventory();

            var accepted = f.placer.placePistonBatch(requests);

            assertEquals(expected, accepted, "Power shortage defers only the unsupplied power source, not the priming piston");
            assertEquals(10, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.client.player.getInventory().getItem(6).isEmpty());
            var uses = f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(6, uses.stream().filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).count());
            assertEquals(torchCount, uses.stream().filter(packet -> packet.getHand() == InteractionHand.MAIN_HAND).count());
            assertEquals(1, f.batch.batches);
            assertTrue(f.batch.packets.stream().noneMatch(ServerboundContainerClickPacket.class::isInstance));
            assertEquals(beforeWorld, f.level.blocks, "Only actual server updates may confirm these machine blocks");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void oneSharedTorchStillPowersItsOwnersWhileAnUnsuppliedNeighbourIsPrimed() throws Exception {
        try (var f = new BedrockInitialBatchPlacementTest.Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 1));
            var second = FIRST.east(2);
            var third = FIRST.south(3);
            var sharedSupport = FIRST.east();
            var thirdSupport = third.south();
            f.level.blocks.put(second, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(third, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(sharedSupport, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(thirdSupport, Blocks.BEDROCK.defaultBlockState());
            var shared = new BedrockTorchPlacement(sharedSupport, Direction.UP);
            var firstRequest = new BedrockPlacer.PistonRequest(FIRST.above(), Direction.UP, shared);
            var secondRequest = new BedrockPlacer.PistonRequest(second.above(), Direction.UP, shared);
            var thirdRequest = new BedrockPlacer.PistonRequest(third.above(), Direction.UP,
                    new BedrockTorchPlacement(thirdSupport, Direction.UP));
            f.readyInventory();

            assertEquals(List.of(firstRequest, secondRequest, new BedrockPlacer.PistonRequest(third.above(), Direction.UP)),
                    f.placer.placePistonBatch(List.of(firstRequest, secondRequest, thirdRequest)));

            assertEquals(13, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.client.player.getInventory().getItem(6).isEmpty());
            var uses = f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(3, uses.stream().filter(packet -> packet.getHand() == InteractionHand.OFF_HAND).count());
            assertEquals(1, uses.stream().filter(packet -> packet.getHand() == InteractionHand.MAIN_HAND).count());
            assertEquals(1, f.batch.batches);
        }
    }
}
//#endif
