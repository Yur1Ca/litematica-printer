//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PredictiveAction;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerGamePacketListener;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Verifies the real predicted sequence and packet boundary; server equipment timing is tested natively. */
class BedrockToolSyncTest {

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    static Object call(Object sync, String name, Class<?>[] types, Object... values) throws Exception {
        var method = sync.getClass().getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(sync, values);
    }
    static boolean prepare(Object sync) throws Exception {
        return (boolean) call(sync, "prepare", new Class<?>[0]);
    }
    static boolean pending(Object sync) throws Exception {
        return (boolean) call(sync, "isPending", new Class<?>[0]);
    }
    static void acknowledge(Object sync, Object connection, int sequence) throws Exception {
        call(sync, "acknowledged", new Class<?>[]{Object.class, int.class}, connection, sequence);
    }
    static void observe(Object sync) throws Exception { call(sync, "onMainHandSelection", new Class<?>[0]); }

    @Test void aSequencedAirUseWaitsForItsOwnAckWithoutTouchingMiningState() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            var before = new HashMap<>(f.level.blocks);
            assertFalse(prepare(sync));
            assertTrue(pending(sync));
            assertEquals(2, f.wire.packets.size());
            assertEquals(0, assertInstanceOf(ServerboundSetCarriedItemPacket.class, f.wire.packets.getFirst()).getSlot());
            var marker = assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast());
            assertEquals(InteractionHand.OFF_HAND, marker.getHand());
            assertEquals(100, marker.getSequence());
            assertFalse(prepare(sync));
            assertEquals(1, f.wire.batches);
            acknowledge(sync, f.wire, 99);
            acknowledge(sync, new Object(), 100);
            assertTrue(pending(sync));
            acknowledge(sync, f.wire, 100);
            assertFalse(pending(sync));
            assertTrue(prepare(sync));
            assertEquals(1, f.wire.batches);
            assertEquals(before, f.level.blocks);
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void damageAloneDoesNotInvalidateConfirmedEquipmentButComponentsOrSlotDo() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            f.confirm(sync);
            f.client.player.getInventory().getItem(0).setDamageValue(17);
            observe(sync);
            assertTrue(prepare(sync));
            f.client.player.getInventory().getItem(0).set(DataComponents.CUSTOM_NAME, Component.literal("another tool"));
            observe(sync);
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 100);
            assertTrue(pending(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(prepare(sync));
            f.client.player.getInventory().setItem(1, f.client.player.getInventory().getItem(0).copy());
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 1);
            observe(sync);
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 102);
            assertTrue(prepare(sync));
        }
    }

    @Test void anOldReadyToolCannotSurviveAMaterialRoundTripEvenWithIdenticalFinalStack() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            f.confirm(sync);
            f.client.player.getInventory().setItem(5, new ItemStack(Items.REDSTONE_TORCH));
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 5);
            observe(sync);
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 0);
            observe(sync);
            assertFalse(prepare(sync), "The old efficiency-ready state cannot approve a new tool selection");
            acknowledge(sync, f.wire, 100);
            assertTrue(pending(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(prepare(sync));
        }
    }

    @Test void anAckForADifferentCurrentToolCannotConfirmThePreviousSelection() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            assertFalse(prepare(sync));
            f.client.player.getInventory().setItem(0, new ItemStack(Items.IRON_PICKAXE));
            acknowledge(sync, f.wire, 100);
            assertFalse(pending(sync));
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(prepare(sync));
        }
    }

    @Test void explicitInvalidationAndEpochChangesRequireNewAcknowledgements() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            f.confirm(sync);
            call(sync, "invalidateTool", new Class<?>[0]);
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(prepare(sync));
            set(f.runtime, PrinterRuntime.class, "epoch", RuntimeEpoch.INITIAL.next());
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(pending(sync));
            acknowledge(sync, f.wire, 102);
            assertTrue(prepare(sync));
            call(sync, "reset", new Class<?>[0]);
            assertFalse(prepare(sync));
            assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast());
        }
    }

    @Test void aPistonOffhandIsSafeWithoutConsumingOrPredictingItsUse() throws Exception {
        try (var f = new Fixture()) {
            var pistons = new ItemStack(Items.PISTON, 23);
            f.client.player.getInventory().setItem(40, pistons);
            assertFalse(prepare(f.sync()));
            assertEquals(InteractionHand.OFF_HAND,
                    assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast()).getHand());
            assertEquals(23, pistons.getCount());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anActiveOffhandItemUsesOnlyTheSafeMainHandTool() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(40, new ItemStack(Items.ENDER_PEARL));
            assertFalse(prepare(f.sync()));
            assertEquals(InteractionHand.MAIN_HAND,
                    assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast()).getHand());
        }
    }

    @ParameterizedTest @ValueSource(strings = {"consumable", "equippable", "blocking", "kinetic"})
    void unsafeUseComponentsCannotBecomeAnEquipmentMarker(String component) throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(40, new ItemStack(Items.ENDER_PEARL));
            var tool = f.client.player.getMainHandItem();
            switch (component) {
                case "consumable" -> copyComponent(tool, DataComponents.CONSUMABLE, Items.APPLE);
                case "equippable" -> copyComponent(tool, DataComponents.EQUIPPABLE, Items.IRON_HELMET);
                case "blocking" -> copyComponent(tool, DataComponents.BLOCKS_ATTACKS, Items.SHIELD);
                case "kinetic" -> copyComponent(tool, DataComponents.KINETIC_WEAPON, Items.WOODEN_SPEAR);
                default -> throw new AssertionError(component);
            }
            var sync = f.sync();
            assertFalse(prepare(sync));
            assertFalse(pending(sync));
            assertTrue(f.wire.packets.isEmpty(), "Waiting for equipment must never eat, equip, throw or start using an item");
        }
    }

    private static <T> void copyComponent(ItemStack target, DataComponentType<T> type, net.minecraft.world.item.Item source) {
        T value = new ItemStack(source).get(type);
        assertNotNull(value);
        target.set(type, value);
    }

    @Test void twoActiveItemsCannotBeUsedAsAnEquipmentMarker() throws Exception {
        try (var f = new Fixture()) {
            f.client.player.getInventory().setItem(0, new ItemStack(Items.ENDER_PEARL));
            f.client.player.getInventory().setItem(40, new ItemStack(Items.ENDER_PEARL));
            var sync = f.sync();
            assertFalse(prepare(sync));
            assertFalse(pending(sync));
            assertTrue(f.wire.packets.isEmpty());
        }
    }

    @Test void aMaterialWithUseComponentsFallsBackToTheSafeTool() throws Exception {
        try (var f = new Fixture()) {
            var pistons = new ItemStack(Items.PISTON);
            pistons.set(DataComponents.CONSUMABLE, new ItemStack(Items.APPLE).get(DataComponents.CONSUMABLE));
            f.client.player.getInventory().setItem(40, pistons);
            assertFalse(prepare(f.sync()));
            assertEquals(InteractionHand.MAIN_HAND,
                    assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast()).getHand());
        }
    }

    @Test void anEquipmentMarkerPreservesTheScopedPlacementLook() throws Exception {
        try (var f = new Fixture()) {
            var look = new PlayerLook(90.0F, -90.0F);
            NetworkUtils.setScopedLookOverride(look);
            try {
                assertFalse(prepare(f.sync()));
                var marker = assertInstanceOf(ServerboundUseItemPacket.class, f.wire.packets.getLast());
                assertEquals(look.yaw, marker.getYRot());
                assertEquals(look.pitch, marker.getXRot());
                assertSame(look, NetworkUtils.getScopedLookOverride());
            } finally { NetworkUtils.clearScopedLookOverride(); }
        }
    }

    @Test void changingConnectionRejectsThePreviousConnectionsAck() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            assertFalse(prepare(sync));
            var replacement = f.allocate(Transport.class);
            replacement.packets = new ArrayList<>();
            f.connection.transport = replacement;
            acknowledge(sync, f.wire, 100);
            assertFalse(pending(sync));
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(pending(sync));
            acknowledge(sync, replacement, 101);
            assertTrue(prepare(sync));
        }
    }

    @Test void unsuccessfulTransportCannotInstallAPendingTicketOrAcceptItsSequence() throws Exception {
        try (var f = new Fixture()) {
            var sync = f.sync();
            f.wire.reject = true;
            assertFalse(prepare(sync));
            assertFalse(pending(sync));
            assertTrue(f.wire.packets.isEmpty());
            acknowledge(sync, f.wire, 100);
            f.wire.reject = false;
            assertFalse(prepare(sync));
            acknowledge(sync, f.wire, 100);
            assertTrue(pending(sync));
            acknowledge(sync, f.wire, 101);
            assertTrue(prepare(sync));
        }
    }

    static class SequencedLevel extends BedrockPlacementRegressionTest.TestLevel {
        int next;
        @Override public Packet<ServerGamePacketListener> litematica_printer3$predict(PredictiveAction action) {
            return action.predict(next++);
        }
    }
    static class Transport extends BedrockPlacementRegressionTest.CaptureTransport {
        boolean reject;
        int batches;
        @Override public boolean litematica_printer$sendBatch(List<Packet<?>> packets) {
            batches++;
            return !reject && super.litematica_printer$sendBatch(packets);
        }
    }
    static class Fixture extends BedrockPlacementRegressionTest.Fixture {
        final Transport wire;
        Fixture() throws Exception {
            super();
            client.player.getInventory().setItem(0, new ItemStack(Items.DIAMOND_PICKAXE));
            var sequence = allocate(SequencedLevel.class);
            sequence.blocks = level.blocks;
            sequence.next = 100;
            set(sequence, Level.class, "dimension", Level.OVERWORLD);
            client.level = sequence;
            wire = allocate(Transport.class);
            wire.packets = new ArrayList<>();
            connection.transport = wire;
        }
        Object sync() throws Exception {
            var type = assertDoesNotThrow(() -> Class.forName(BedrockPlacer.class.getPackageName() + ".BedrockToolSync"),
                    "Modern mining needs a real server equipment-tick acknowledgement");
            var constructor = type.getDeclaredConstructor(Minecraft.class);
            constructor.setAccessible(true);
            return constructor.newInstance(client);
        }
        void confirm(Object sync) throws Exception {
            assertFalse(prepare(sync));
            acknowledge(sync, wire, 100);
            assertTrue(prepare(sync));
        }
    }
}
//#endif
