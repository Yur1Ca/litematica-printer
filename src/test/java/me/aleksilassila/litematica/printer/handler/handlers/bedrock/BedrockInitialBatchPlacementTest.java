//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.utils.minecraft.InventorySyncPackets;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Regression boundaries for the real placer; packet capture is not a native-server benchmark. */
class BedrockInitialBatchPlacementTest {
    private static final BlockPos FIRST = new BlockPos(0, 64, 0);
    private static final BlockPos SECOND = new BlockPos(2, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    private static Object request(BlockPos piston, Direction facing) throws Exception {
        var type = Arrays.stream(BedrockPlacer.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("PistonRequest")).findFirst().orElse(null);
        assertNotNull(type, "Initial piston requests need a batch placement boundary");
        assertTrue(type.isRecord());
        var constructor = type.getDeclaredConstructor(BlockPos.class, Direction.class);
        constructor.setAccessible(true);
        return constructor.newInstance(piston, facing);
    }

    private static Object poweredRequest(BlockPos piston, BedrockTorchPlacement torch) throws Exception {
        var type = Arrays.stream(BedrockPlacer.class.getDeclaredClasses())
                .filter(candidate -> candidate.getSimpleName().equals("PistonRequest")).findFirst().orElseThrow();
        var constructor = assertDoesNotThrow(() -> type.getDeclaredConstructor(
                BlockPos.class, Direction.class, BedrockTorchPlacement.class),
                "The reserved power source belongs to its initial machine preparation");
        constructor.setAccessible(true);
        return constructor.newInstance(piston, Direction.UP, torch);
    }

    @Test void compatiblePistonsAndReservedTorchesNeedNoInventoryRoundTripAfterPlacement() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 8));
            var firstSupport = FIRST.north();
            var secondSupport = SECOND.south();
            f.level.blocks.put(firstSupport, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(secondSupport, Blocks.BEDROCK.defaultBlockState());
            var first = poweredRequest(FIRST.above(), new BedrockTorchPlacement(firstSupport, Direction.UP));
            var second = poweredRequest(SECOND.above(), new BedrockTorchPlacement(secondSupport, Direction.UP));
            var worldBefore = new HashMap<>(f.level.blocks);

            assertEquals(List.of(first, second), send(f.placer, List.of(first, second)));

            assertEquals(1, f.batch.batches);
            assertEquals(14, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(6, f.client.player.getInventory().getItem(6).getCount());
            var uses = f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(List.of(net.minecraft.world.InteractionHand.OFF_HAND, net.minecraft.world.InteractionHand.OFF_HAND,
                    net.minecraft.world.InteractionHand.MAIN_HAND, net.minecraft.world.InteractionHand.MAIN_HAND),
                    uses.stream().map(ServerboundUseItemOnPacket::getHand).toList());
            assertEquals(List.of(FIRST, SECOND, firstSupport, secondSupport),
                    uses.stream().map(p -> p.getHitResult().getBlockPos()).toList());
            assertEquals(0, f.batch.packets.stream().filter(ServerboundContainerClickPacket.class::isInstance).count());
            assertEquals(0, f.batch.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count());
            assertEquals(worldBefore, f.level.blocks, "Only the server can confirm the machine blocks");
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void poweredPreparationDoesNotSwapItsOnlyTorchSlotBackToAnInventoryPick() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.REDSTONE_TORCH, 8));
            f.client.player.getInventory().setItem(11, new ItemStack(Items.DIAMOND_PICKAXE));
            var support = FIRST.north();
            f.level.blocks.put(support, Blocks.BEDROCK.defaultBlockState());
            var session = new BedrockInventorySession(f.client, false, new BedrockInventorySession.InventoryAccess() {
                public boolean selectOffhand(net.minecraft.world.item.Item item) { return true; }
                public boolean materialRequiresInventorySwap(net.minecraft.world.item.Item item) { return false; }
                public boolean selectMaterial(net.minecraft.world.item.Item item) { return true; }
                public boolean toolRequiresInventorySwap(BlockState state, boolean cleanup) { return true; }
                public boolean selectTool(BlockState state, boolean cleanup) {
                    throw new AssertionError("Priming cannot evict its only hotbar Torch before the machine is sent");
                }
            });
            set(f.placer, BedrockPlacer.class, "inventory", session);
            var request = poweredRequest(FIRST.above(), new BedrockTorchPlacement(support, Direction.UP));

            assertEquals(List.of(request), send(f.placer, List.of(request)));

            assertEquals(7, f.client.player.getInventory().getItem(0).getCount());
            assertTrue(f.client.player.getInventory().getItem(11).is(Items.DIAMOND_PICKAXE));
            assertEquals(1, f.batch.batches);
        }
    }

    @Test void aSharedReservedTorchIsPlacedAndConsumedOnlyOnceForTwoPistons() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 1));
            var support = FIRST.east();
            f.level.blocks.put(support, Blocks.BEDROCK.defaultBlockState());
            var power = new BedrockTorchPlacement(support, Direction.UP);
            var first = poweredRequest(FIRST.above(), power);
            var second = poweredRequest(SECOND.above(), power);

            assertEquals(List.of(first, second), send(f.placer, List.of(first, second)));

            assertEquals(14, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.client.player.getInventory().getItem(6).isEmpty());
            assertEquals(3, f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
        }
    }

    @Test void aSingleTorchCanPrepareACompatibleSubsetWithoutStarvingTheWholeBatch() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 1));
            f.level.blocks.put(FIRST.north(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(SECOND.south(), Blocks.BEDROCK.defaultBlockState());
            var first = poweredRequest(FIRST.above(), new BedrockTorchPlacement(FIRST.north(), Direction.UP));
            var second = poweredRequest(SECOND.above(), new BedrockTorchPlacement(SECOND.south(), Direction.UP));

            assertEquals(List.of(first), send(f.placer, List.of(first, second)));

            assertEquals(15, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.client.player.getInventory().getItem(6).isEmpty());
            assertEquals(2, f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
        }
    }

    @Test void anExistingCorrectTorchIsReusedWithoutAnExtraUseOrRequiringAnotherTorch() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var support = FIRST.north();
            var power = new BedrockTorchPlacement(support, Direction.UP);
            f.level.blocks.put(support, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(power.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            var request = poweredRequest(FIRST.above(), power);

            assertEquals(List.of(request), send(f.placer, List.of(request)));

            var uses = f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(1, uses.size());
            assertEquals(net.minecraft.world.InteractionHand.OFF_HAND, uses.get(0).getHand());
            assertEquals(15, f.client.player.getInventory().getItem(40).getCount());
        }
    }

    private static List<?> send(BedrockPlacer placer, List<?> requests) throws Exception {
        var method = Arrays.stream(BedrockPlacer.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals("placePistonBatch")
                        && Arrays.equals(candidate.getParameterTypes(), new Class<?>[]{List.class}))
                .findFirst().orElse(null);
        assertNotNull(method, "Initial placements must share one ordered batch and inventory response");
        method.setAccessible(true);
        return (List<?>) method.invoke(placer, requests);
    }

    @Test void twoVerticalRequestsConsumeOnlySentPistonsWithoutAnInventoryRoundTrip() throws Exception {
        try (var f = new Fixture()) {
            var up = request(FIRST.above(), Direction.UP);
            var down = request(SECOND.below(), Direction.DOWN);
            var beforeWorld = new HashMap<>(f.level.blocks);

            assertEquals(List.of(up, down), send(f.placer, List.of(up, down)));

            assertEquals(1, f.batch.batches);
            assertEquals(14, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(2, f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(0, f.batch.packets.stream().filter(ServerboundContainerClickPacket.class::isInstance).count());
            assertEquals(0, f.batch.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count());
            assertEquals(List.of(FIRST, SECOND), f.batch.packets.stream()
                    .filter(ServerboundUseItemOnPacket.class::isInstance).map(ServerboundUseItemOnPacket.class::cast)
                    .map(packet -> packet.getHitResult().getBlockPos()).toList());
            assertEquals(Set.of(FIRST.above(), SECOND.below()), f.batchLevel.collisionChecks);
            assertFalse(f.placer.isInventorySettling());
            assertEquals(beforeWorld, f.level.blocks);
            assertTrue(f.mode.hits.isEmpty(), "Initial batching cannot use local world prediction");
        }
    }

    @Test void aPendingInventoryFenceRejectsEveryRequestBeforeAnyPacketOrConsumption() throws Exception {
        try (var f = new Fixture()) {
            var up = request(FIRST.above(), Direction.UP);
            me.aleksilassila.litematica.printer.testsupport.BedrockInventoryTestAccess.recordFence(
                    f.placer, InventorySyncPackets.prepareFence(f.batch));
            f.tick(100);

            assertTrue(send(f.placer, List.of(up)).isEmpty());

            assertEquals(0, f.batch.batches);
            assertTrue(f.batch.packets.isEmpty());
            assertEquals(16, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.placer.isInventorySettling());
        }
    }

    @Test void illegalCollidingAndDuplicateRequestsDoNotConsumeASecondPiston() throws Exception {
        try (var f = new Fixture()) {
            var good = request(FIRST.above(), Direction.UP);
            f.batchLevel.blocked.add(SECOND.above());
            var collision = request(SECOND.above(), Direction.UP);
            var obstructed = FIRST.west(2);
            f.level.blocks.put(obstructed, Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(obstructed.above(2), Blocks.STONE.defaultBlockState());
            var occupiedHead = request(obstructed.above(), Direction.UP);
            var missingAnchor = request(FIRST.south(2).above(), Direction.UP);
            var side = request(SECOND.north(), Direction.NORTH);
            var outsideRange = FIRST.east(100);
            f.level.blocks.put(outsideRange, Blocks.BEDROCK.defaultBlockState());
            var far = request(outsideRange.above(), Direction.UP);
            var beforeWorld = new HashMap<>(f.level.blocks);

            assertEquals(List.of(good), send(f.placer,
                    Arrays.asList(collision, occupiedHead, missingAnchor, side, far, null,
                            request(null, Direction.UP), request(FIRST.above(), null), good, good)));

            assertEquals(1, f.batch.batches);
            assertEquals(15, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(1, f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertTrue(f.batchLevel.collisionChecks.contains(SECOND.above()), "Full collision checks must remain active");
            assertEquals(beforeWorld, f.level.blocks);
        }
    }

    @Test void availableOffhandCountCannotBeOvercommitted() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(40, new ItemStack(Items.PISTON, 1));
            var first = request(FIRST.above(), Direction.UP);
            var second = request(SECOND.above(), Direction.UP);

            assertEquals(List.of(first), send(f.placer, List.of(first, second)));

            assertTrue(f.client.player.getInventory().getItem(40).isEmpty());
            assertEquals(1, f.batch.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
        }
    }

    @Test void aRejectedSendDoesNotPredictConsumptionOrInstallAFence() throws Exception {
        try (var f = new Fixture()) {
            f.batch.reject = true;

            assertTrue(send(f.placer, List.of(request(FIRST.above(), Direction.UP))).isEmpty());

            assertEquals(1, f.batch.batches);
            assertEquals(16, f.client.player.getInventory().getItem(40).getCount());
            assertTrue(f.batch.packets.isEmpty());
            assertFalse(f.placer.isInventorySettling());
        }
    }

    @Test void anOffhandSwapIsPreparationAndCannotStartTheBatchInTheSameCall() throws Exception {
        try (var f = new Fixture()) {
            var mode = f.allocate(SwapMode.class);
            mode.rejectMutations = true;
            f.client.gameMode = mode;
            f.client.player.getInventory().setItem(10, new ItemStack(Items.PISTON, 8));
            f.client.player.getInventory().setItem(40, new ItemStack(Items.REDSTONE_TORCH, 4));
            var first = request(FIRST.above(), Direction.UP);

            assertTrue(send(f.placer, List.of(first)).isEmpty());

            assertEquals(1, mode.swaps);
            assertEquals(0, f.batch.batches);
            assertEquals(8, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(List.of(first), send(f.placer, List.of(first)));
            assertEquals(7, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(1, mode.swaps);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"cursor", "container", "player", "level", "mode", "transport", "null", "empty"})
    void unsafeSharedContextCannotSendAPartialBatch(String invalid) throws Exception {
        try (var f = new Fixture()) {
            var player = f.client.player;
            List<?> requests = List.of(request(FIRST.above(), Direction.UP));
            switch (invalid) {
                case "cursor" -> player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
                case "container" -> player.containerMenu = ChestMenu.threeRows(7, player.getInventory());
                case "player" -> f.client.player = null;
                case "level" -> f.client.level = null;
                case "mode" -> f.client.gameMode = null;
                case "transport" -> f.connection.transport = null;
                case "null" -> requests = null;
                case "empty" -> requests = List.of();
                default -> fail("Unknown invalid context");
            }

            assertTrue(send(f.placer, requests).isEmpty());

            assertEquals(0, f.batch.batches);
            assertTrue(f.batch.packets.isEmpty());
            assertEquals(16, player.getInventory().getItem(40).getCount());
        }
    }

    static class BatchLevel extends BedrockPlacementRegressionTest.TestLevel {
        Set<BlockPos> blocked;
        Set<BlockPos> collisionChecks;
        @Override public boolean isUnobstructed(BlockState state, BlockPos pos, CollisionContext context) {
            collisionChecks.add(pos.immutable());
            return !blocked.contains(pos);
        }
    }

    static class PoweredPlayer extends BedrockPlacementRegressionTest.TestPlayer {
        @Override public boolean hasEffect(net.minecraft.core.Holder<net.minecraft.world.effect.MobEffect> effect) { return false; }
        @Override public double getAttributeValue(net.minecraft.core.Holder<net.minecraft.world.entity.ai.attributes.Attribute> attribute) { return 1; }
        @Override public boolean isEyeInFluid(net.minecraft.tags.TagKey<net.minecraft.world.level.material.Fluid> tag) { return false; }
        @Override public boolean onGround() { return true; }
    }

    static class BatchTransport extends BedrockPlacementRegressionTest.CaptureTransport {
        int batches;
        boolean reject;
        @Override public boolean litematica_printer$sendBatch(List<Packet<?>> packets) {
            batches++;
            return !reject && super.litematica_printer$sendBatch(packets);
        }
    }

    static class SwapMode extends BedrockPlacementRegressionTest.TestGameMode {
        int swaps;
        @Override public void handleContainerInput(int container, int source, int button, ContainerInput input, Player player) {
            assertEquals(ContainerInput.SWAP, input);
            assertEquals(40, button);
            swaps++;
            var slot = player.inventoryMenu.getSlot(source);
            var previous = player.getInventory().getItem(40);
            player.getInventory().setItem(40, slot.getItem());
            slot.set(previous);
        }
    }

    static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final int oldWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        final Object oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        final BatchLevel batchLevel;
        final BatchTransport batch;
        Fixture() throws Exception {
            super();
            var previous = client.player;
            var player = allocate(PoweredPlayer.class);
            player.inventory = previous.getInventory();
            set(player, Player.class, "inventoryMenu", previous.inventoryMenu);
            player.containerMenu = previous.containerMenu;
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", previous.connection);
            set(player, net.minecraft.world.entity.Entity.class, "position", previous.position());
            set(player, net.minecraft.world.entity.Entity.class, "eyeHeight",
                    BedrockPlacementRegressionTest.get(previous, net.minecraft.world.entity.Entity.class, "eyeHeight"));
            client.player = player;
            set(client.player, Player.class, "abilities", new Abilities());
            Configs.Core.WORK_RANGE.setIntegerValue(8);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            batchLevel = allocate(BatchLevel.class);
            batchLevel.blocks = level.blocks;
            batchLevel.blocks.put(SECOND, Blocks.BEDROCK.defaultBlockState());
            batchLevel.blocked = new HashSet<>();
            batchLevel.collisionChecks = new HashSet<>();
            set(batchLevel, Level.class, "dimension", Level.OVERWORLD);
            client.level = batchLevel;
            batch = allocate(BatchTransport.class);
            batch.packets = new ArrayList<>();
            connection.transport = batch;
            mode.rejectMutations = true;
        }
        @Override public void close() throws Exception {
            Configs.Core.WORK_RANGE.setIntegerValue(oldWorkRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue((fi.dy.masa.malilib.config.IConfigOptionListEntry) oldShape);
            super.close();
        }
    }
}
//#endif
