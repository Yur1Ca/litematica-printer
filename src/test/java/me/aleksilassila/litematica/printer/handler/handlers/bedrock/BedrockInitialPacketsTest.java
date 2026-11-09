//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Initial raw placements share one ordered inventory snapshot without changing client state. */
class BedrockInitialPacketsTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    private static BedrockPlacer.PreparedPistonPlacement placement(BlockPos anchor, float yaw) {
        return new BedrockPlacer.PreparedPistonPlacement(
                new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false),
                new PlayerLook(yaw, 90F));
    }

    @SuppressWarnings("unchecked")
    private static List<Packet<?>> prepare(LocalPlayer player,
            List<BedrockPlacer.PreparedPistonPlacement> placements) throws Exception {
        var helper = assertDoesNotThrow(() -> Class.forName(
                "me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockInitialPackets"),
                "Initial placements need one packet builder preserving the placement order");
        var method = helper.getDeclaredMethod("prepare", LocalPlayer.class, List.class);
        method.setAccessible(true);
        return (List<Packet<?>>) method.invoke(null, player, placements);
    }

    @SuppressWarnings("unchecked")
    private static List<Packet<?>> powered(LocalPlayer player,
            List<BedrockPlacer.PreparedPistonPlacement> pistons, List<BlockHitResult> torches,
            int torchSlot) throws Exception {
        var method = assertDoesNotThrow(() -> BedrockInitialPackets.class.getDeclaredMethod("preparePowered",
                LocalPlayer.class, List.class, List.class, int.class),
                "Compatible priming pistons and their power sources must share one preparation batch");
        method.setAccessible(true);
        return (List<Packet<?>>) method.invoke(null, player, pistons, torches, torchSlot);
    }

    @Test void poweredPreparationPlacesPistonsThenTorchesAndRestoresTheToolWithoutAMenuRoundTrip() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 8));
            var first = placement(new BlockPos(0, 64, 0), 0F);
            var second = placement(new BlockPos(2, 64, 0), 0F);
            var firstTorch = new BlockHitResult(Vec3.atCenterOf(new BlockPos(1, 64, 0)),
                    Direction.EAST, new BlockPos(1, 64, 0), false);
            var secondTorch = new BlockHitResult(Vec3.atCenterOf(new BlockPos(3, 64, 0)),
                    Direction.UP, new BlockPos(3, 64, 0), false);
            var beforeWorld = new HashMap<>(f.level.blocks);

            var packets = powered(f.client.player, List.of(first, second), List.of(firstTorch, secondTorch), 6);

            assertEquals(7, packets.size());
            assertEquals(InteractionHand.OFF_HAND, assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(1)).getHand());
            assertEquals(InteractionHand.OFF_HAND, assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(2)).getHand());
            assertEquals(6, assertInstanceOf(ServerboundSetCarriedItemPacket.class, packets.get(3)).getSlot());
            var torchOne = assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(4));
            var torchTwo = assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(5));
            assertEquals(InteractionHand.MAIN_HAND, torchOne.getHand());
            assertEquals(InteractionHand.MAIN_HAND, torchTwo.getHand());
            assertSame(firstTorch, torchOne.getHitResult());
            assertSame(secondTorch, torchTwo.getHitResult());
            assertEquals(0, assertInstanceOf(ServerboundSetCarriedItemPacket.class, packets.get(6)).getSlot());
            assertTrue(packets.stream().noneMatch(ServerboundContainerClickPacket.class::isInstance));
            assertTrue(packets.stream().noneMatch(ServerboundClientCommandPacket.class::isInstance));
            assertEquals(8, f.client.player.getInventory().getItem(6).getCount());
            assertTrue(f.transport.packets.isEmpty(), "Freezing preparation cannot send a partial body");
            assertEquals(beforeWorld, f.level.blocks);
        }
    }

    @Test void invalidPoweredMaterialCannotLeaveAPistonOnlyPartialPreparation() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var first = placement(new BlockPos(0, 64, 0), 0F);
            var hit = new BlockHitResult(Vec3.atCenterOf(new BlockPos(1, 64, 0)), Direction.UP,
                    new BlockPos(1, 64, 0), false);
            for (int invalidSlot : new int[]{-1, 9, 40}) {
                assertTrue(powered(f.client.player, List.of(first), List.of(hit), invalidSlot).isEmpty());
            }
            f.client.player.getInventory().setItem(6, new ItemStack(Items.STONE, 8));
            assertTrue(powered(f.client.player, List.of(first), List.of(hit), 6).isEmpty());
            f.client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 1));
            assertTrue(powered(f.client.player, List.of(first), List.of(hit, hit), 6).isEmpty());
            assertTrue(f.transport.packets.isEmpty());
        }
    }

    @Test void twoPlacementsKeepTheirOwnLookAndUseWithoutAnInventoryRoundTrip() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var first = placement(new BlockPos(0, 64, 0), 15F);
            var second = placement(new BlockPos(2, 64, 0), -45F);

            var packets = prepare(f.client.player, List.of(first, second));

            assertEquals(4, packets.size());
            var firstLook = assertInstanceOf(ServerboundMovePlayerPacket.class, packets.get(0));
            assertTrue(firstLook.hasRotation());
            assertEquals(15F, firstLook.getYRot(0));
            assertEquals(90F, firstLook.getXRot(0));
            var firstUse = assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(1));
            assertEquals(InteractionHand.OFF_HAND, firstUse.getHand());
            assertSame(first.hit(), firstUse.getHitResult());
            var secondLook = assertInstanceOf(ServerboundMovePlayerPacket.class, packets.get(2));
            assertTrue(secondLook.hasRotation());
            assertEquals(-45F, secondLook.getYRot(0));
            assertEquals(90F, secondLook.getXRot(0));
            var secondUse = assertInstanceOf(ServerboundUseItemOnPacket.class, packets.get(3));
            assertEquals(InteractionHand.OFF_HAND, secondUse.getHand());
            assertSame(second.hit(), secondUse.getHitResult());
            assertTrue(packets.stream().noneMatch(ServerboundContainerClickPacket.class::isInstance));
            assertTrue(packets.stream().noneMatch(ServerboundClientCommandPacket.class::isInstance));
        }
    }

    @Test void preparingPacketsDoesNotSendPredictInventoryOrChangeTheWorld() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var beforeWorld = new HashMap<>(f.level.blocks);
            var beforePosition = f.client.player.position();
            int beforeCount = f.client.player.getInventory().getItem(40).getCount();
            var scopedLook = new PlayerLook(Direction.WEST);
            NetworkUtils.setScopedLookOverride(scopedLook);

            assertFalse(prepare(f.client.player, List.of(placement(new BlockPos(0, 64, 0), 0F),
                    placement(new BlockPos(2, 64, 0), 0F))).isEmpty());

            assertTrue(f.transport.packets.isEmpty(), "Construction must not submit even the look or sync packet");
            assertTrue(f.mode.hits.isEmpty(), "Raw placements must not use the local world prediction path");
            assertEquals(beforeCount, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(beforeWorld, f.level.blocks);
            assertEquals(beforePosition, f.client.player.position());
            assertTrue(f.client.player.inventoryMenu.getCarried().isEmpty());
            assertSame(scopedLook, NetworkUtils.getScopedLookOverride());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"player", "placements", "empty", "placement", "hit", "look", "cursor", "container", "sequence"})
    void invalidInputReturnsNoPartialBatchOrWrites(String invalid) throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            LocalPlayer player = f.client.player;
            var first = placement(new BlockPos(0, 64, 0), 0F);
            var second = placement(new BlockPos(2, 64, 0), 0F);
            List<BedrockPlacer.PreparedPistonPlacement> placements = List.of(first, second);
            switch (invalid) {
                case "player" -> player = null;
                case "placements" -> placements = null;
                case "empty" -> placements = List.of();
                case "placement" -> placements = Arrays.asList(first, null);
                case "hit" -> placements = List.of(first, new BedrockPlacer.PreparedPistonPlacement(null, second.look()));
                case "look" -> placements = List.of(first, new BedrockPlacer.PreparedPistonPlacement(second.hit(), null));
                case "cursor" -> player.inventoryMenu.setCarried(new ItemStack(Items.STONE, 3));
                case "container" -> player.containerMenu = ChestMenu.threeRows(7, player.getInventory());
                case "sequence" -> f.client.level = null;
                default -> fail("Unknown invalid input");
            }
            var beforeWorld = new HashMap<>(f.level.blocks);
            int beforeCount = f.client.player.getInventory().getItem(40).getCount();
            var beforeCursor = f.client.player.inventoryMenu.getCarried().copy();

            assertTrue(prepare(player, placements).isEmpty());

            assertTrue(f.transport.packets.isEmpty());
            assertTrue(f.mode.hits.isEmpty());
            assertEquals(beforeCount, f.client.player.getInventory().getItem(40).getCount());
            assertEquals(beforeWorld, f.level.blocks);
            assertTrue(ItemStack.matches(beforeCursor, f.client.player.inventoryMenu.getCarried()));
        }
    }
}
//#endif
