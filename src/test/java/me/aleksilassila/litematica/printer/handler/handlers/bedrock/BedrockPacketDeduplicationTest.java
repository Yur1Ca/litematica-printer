//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Captures production session selections and the frozen initial packet body on the real batch port. */
class BedrockPacketDeduplicationTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void materialSelectionSendsOneSlotPacketWhetherSwitchingHotbarOrAlreadyHolding(boolean alreadyHolding) throws Exception {
        try (var f = new Fixture()) {
            if (alreadyHolding) InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 6);

            assertTrue(f.session.prepareMaterial(Items.REDSTONE_TORCH));

            var selected = f.wire.packets.stream().filter(ServerboundSetCarriedItemPacket.class::isInstance)
                    .map(ServerboundSetCarriedItemPacket.class::cast).toList();
            assertEquals(1, selected.size(), "A selector's actual hotbar packet is sufficient, while its already-held early return still needs the session packet");
            assertEquals(6, selected.getFirst().getSlot());
            assertTrue(f.wire.packets.stream().noneMatch(ServerboundContainerClickPacket.class::isInstance));
            assertTrue(f.wire.packets.stream().noneMatch(ServerboundClientCommandPacket.class::isInstance));
            assertEquals(8, f.client.player.getMainHandItem().getCount());
            assertFalse(f.session.isSettling());
        }
    }

    @Test void aFrozenInitialBatchReusesConsecutiveEqualAnglesAndResendsEachActualChange() throws Exception {
        try (var f = new Fixture()) {
            var placements = List.of(placement(0, 0F, 90F), placement(1, 0F, 90F),
                    placement(2, 0F, -90F), placement(3, 0F, -90F),
                    placement(4, 45F, -90F), placement(5, 0F, -90F));

            assertTrue(NetworkUtils.sendPacketBatch(BedrockInitialPackets.prepare(f.client.player, placements)));

            var looks = f.wire.packets.stream().filter(ServerboundMovePlayerPacket.class::isInstance)
                    .map(ServerboundMovePlayerPacket.class::cast).toList();
            var uses = f.wire.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).toList();
            assertEquals(4, looks.size(), "Only adjacent equal rotations inside this immutable body may be coalesced");
            assertEquals(List.of(0F, 0F, 45F, 0F), looks.stream().map(packet -> packet.getYRot(0)).toList());
            assertEquals(List.of(90F, -90F, -90F, -90F), looks.stream().map(packet -> packet.getXRot(0)).toList());
            assertEquals(placements.stream().map(BedrockPlacer.PreparedPistonPlacement::hit).toList(),
                    uses.stream().map(ServerboundUseItemOnPacket::getHitResult).toList());
            assertEquals(List.of("LOOK", "USE", "USE", "LOOK", "USE", "USE", "LOOK", "USE", "LOOK", "USE"),
                    f.wire.packets.stream().map(packet -> packet instanceof ServerboundMovePlayerPacket ? "LOOK" : "USE").toList());
            assertEquals(1, f.wire.batches);
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void separateFrozenBodiesEachStartWithTheirOwnLookEvenIfTheirAnglesMatch() throws Exception {
        try (var f = new Fixture()) {
            var first = placement(0, 15F, 90F);
            var second = placement(1, 15F, 90F);

            assertTrue(NetworkUtils.sendPacketBatch(BedrockInitialPackets.prepare(f.client.player, List.of(first))));
            assertTrue(NetworkUtils.sendPacketBatch(BedrockInitialPackets.prepare(f.client.player, List.of(second))));

            assertEquals(2, f.wire.packets.stream().filter(ServerboundMovePlayerPacket.class::isInstance).count());
            assertEquals(2, f.wire.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance).count());
            assertEquals(2, f.wire.batches);
        }
    }

    private static BedrockPlacer.PreparedPistonPlacement placement(int x, float yaw, float pitch) {
        var anchor = new BlockPos(x, 64, 0);
        return new BedrockPlacer.PreparedPistonPlacement(
                new BlockHitResult(Vec3.atCenterOf(anchor), Direction.UP, anchor, false), new PlayerLook(yaw, pitch));
    }

    private static final class Fixture extends BedrockToolSyncTest.Fixture {
        final BedrockInventorySession session;

        Fixture() throws Exception {
            set(client.player, Player.class, "abilities", new Abilities());
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(client, runtime::currentTick));
            client.player.getInventory().setItem(6, new ItemStack(Items.REDSTONE_TORCH, 8));
            session = new BedrockInventorySession(client);
            assertFalse(session.preparePistons());
            client.player.inventoryMenu.initializeContents(client.player.inventoryMenu.getStateId() + 1,
                    client.player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            InventoryRecoveryFence.received(wire);
            assertTrue(session.preparePistons());
            assertFalse(session.prepareCriticalToolTick());
            int sequence = wire.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                    .map(ServerboundUseItemPacket.class::cast).mapToInt(ServerboundUseItemPacket::getSequence).max().orElseThrow();
            session.acknowledgeToolTick(wire, sequence);
            assertTrue(session.prepareCriticalToolTick());
            wire.packets.clear();
            wire.batches = 0;
        }
    }
}
//#endif
