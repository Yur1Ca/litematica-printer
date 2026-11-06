//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehousePlacementQueueTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    private static final class Queue extends ActionManager {
        int sends;
        boolean accepted = true;
        BlockPos sentTarget;
        BlockHitResult sentHit;
        int sentBeforeState;
        int stateSends;
        BlockState sentDesiredState;
        Item expectedItem = Items.STONE;

        Queue() { super(source -> new PlacementPolicy(() -> 64, false, false)); }

        @Override protected boolean sendWarehousePlacement(Item[] items, BlockPos target, BlockHitResult hit, int state) {
            sends++;
            assertArrayEquals(new Item[]{expectedItem}, items);
            sentTarget = target;
            sentHit = hit;
            sentBeforeState = state;
            return accepted;
        }

        @Override protected boolean sendWarehouseStatePlacement(Item[] items, BlockPos target,
                BlockHitResult physicalHit, int state, BlockState desiredState) {
            stateSends++;
            assertArrayEquals(new Item[]{expectedItem}, items);
            sentTarget = target;
            sentHit = physicalHit;
            sentBeforeState = state;
            sentDesiredState = desiredState;
            return accepted;
        }
    }

    @Test void stateDispatchKeepsSupportGeometryAndDesiredPropertiesSeparate() throws Exception {
        for (int protocol : new int[]{2, 3}) {
            try (var fixture = new MaterialSelectionFixture()) {
                fixture.player.input = new net.minecraft.client.player.ClientInput();
                fixture.client.gameMode = null;
                var queue = new Queue();
                queue.expectedItem = Items.CHEST;
                BlockPos target = BlockPos.ZERO.north();
                Vec3 encoded = new Vec3(10, protocol == 2 ? .99 : 0, -1);
                BlockState desired = Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.WEST);
                assertTrue(queue.queueClick(BlockPos.ZERO, Direction.NORTH, Vec3.ZERO, false, 1,
                        new Item[]{Items.CHEST}));
                assertTrue(queue.setWarehouseStatePlacement(target, 0, desired));
                queue.useProtocolHitModifier(encoded);
                assertEquals(ActionManager.SendResult.SENT, queue.sendQueue(fixture.player));
                assertEquals(0, queue.sends, "State intent has its own channel");
                assertEquals(1, queue.stateSends);
                assertEquals(target, queue.sentTarget);
                assertEquals(BlockPos.ZERO, queue.sentHit.getBlockPos());
                assertEquals(Direction.NORTH, queue.sentHit.getDirection());
                assertEquals(new Vec3(.5, .5, 0), queue.sentHit.getLocation(),
                        "Physical reach and permission checks need the real support face");
                assertSame(desired, queue.sentDesiredState);
                assertTrue(fixture.player.getMainHandItem().isEmpty());
                assertTrue(fixture.traffic.isEmpty());
                assertTrue(fixture.requests.isEmpty());
            }
        }
    }

    @Test void stateDispatchDoesNotNeedAnyLegacyEncodedHit() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            fixture.player.input = new net.minecraft.client.player.ClientInput();
            var queue = new Queue();
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE});
            assertFalse(queue.setWarehouseStatePlacement(BlockPos.ZERO.above(), 0, null));
            assertTrue(queue.setWarehouseStatePlacement(BlockPos.ZERO.above(), 0, Blocks.STONE.defaultBlockState()));
            assertEquals(ActionManager.SendResult.SENT, queue.sendQueue(fixture.player),
                    "Structured properties do not depend on the old coordinate encoding");
            assertEquals(1, queue.stateSends);
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE});
            assertTrue(queue.setWarehousePlacement(BlockPos.ZERO.above(), 0));
            queue.useProtocolHitModifier(new Vec3(18, 0, 0));
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, queue.sendQueue(fixture.player));
            assertEquals(0, queue.sends);
            assertEquals(1, queue.stateSends);
        }
    }

    @Test void deniedStateDispatchDoesNotFallBackToAnInventoryBackedClick() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            fixture.player.input = new net.minecraft.client.player.ClientInput();
            var queue = new Queue();
            queue.accepted = false;
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE});
            queue.useProtocolHitModifier(Vec3.ZERO);
            assertTrue(queue.setWarehouseStatePlacement(BlockPos.ZERO.above(), 0, Blocks.STONE.defaultBlockState()));
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, queue.sendQueue(fixture.player));
            assertEquals(1, queue.stateSends);
            assertEquals(0, queue.sends);
            assertTrue(fixture.traffic.isEmpty());
            assertEquals(ActionManager.SendResult.NO_QUEUED_ACTION, queue.sendQueue(fixture.player));
        }
    }

    @Test void dispatchesWithoutHeldMaterialsInventoryWritesOrVanillaGameMode() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            fixture.player.input = new net.minecraft.client.player.ClientInput();
            fixture.client.gameMode = null;
            var queue = new Queue();
            var target = new BlockPos(0, 1, 0);
            assertTrue(queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE}));
            assertTrue(queue.setWarehousePlacement(target, Block.getId(Blocks.AIR.defaultBlockState())));
            assertEquals(ActionManager.SendResult.SENT, queue.sendQueue(fixture.player));
            assertEquals(1, queue.sends);
            assertEquals(target, queue.sentTarget);
            assertEquals(BlockPos.ZERO, queue.sentHit.getBlockPos());
            assertEquals(Direction.UP, queue.sentHit.getDirection());
            assertEquals(Block.getId(Blocks.AIR.defaultBlockState()), queue.sentBeforeState);
            assertTrue(fixture.player.getMainHandItem().isEmpty());
            assertTrue(fixture.traffic.isEmpty(), "Only the custom placement channel may send");
            assertTrue(fixture.requests.isEmpty(), "Must not request legacy restocking");
            assertEquals(ActionManager.SendResult.NO_QUEUED_ACTION, queue.sendQueue(fixture.player));
        }
    }

    @Test void deniedDispatchClearsQueueWithoutFallingThroughToOrdinaryItemUse() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            fixture.player.input = new net.minecraft.client.player.ClientInput();
            var queue = new Queue();
            queue.accepted = false;
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE});
            assertTrue(queue.setWarehousePlacement(BlockPos.ZERO.above(), 0));
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, queue.sendQueue(fixture.player));
            assertEquals(1, queue.sends);
            assertTrue(fixture.traffic.isEmpty());
            assertEquals(ActionManager.SendResult.NO_QUEUED_ACTION, queue.sendQueue(fixture.player));
        }
    }

    @Test void incompatibleQueuedActionsCannotBeMarkedForWarehouseDispatch() throws Exception {
        try (var fixture = new MaterialSelectionFixture()) {
            var queue = new Queue();
            assertFalse(queue.setWarehousePlacement(BlockPos.ZERO, 0));
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 2, new Item[]{Items.STONE});
            assertFalse(queue.setWarehousePlacement(BlockPos.ZERO.above(), 0));
            queue.cancelQueue();
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.WATER_BUCKET});
            assertFalse(queue.setWarehousePlacement(BlockPos.ZERO.above(), 0));
            queue.cancelQueue();
            queue.queueClick(BlockPos.ZERO, Direction.UP, Vec3.ZERO, false, 1, new Item[]{Items.STONE});
            queue.useProtocolHitModifier(new Vec3(18, 0, 0));
            assertFalse(queue.setWarehousePlacement(BlockPos.ZERO.above(), 0));
            assertEquals(0, queue.sends);
        }
    }
}
//#endif
