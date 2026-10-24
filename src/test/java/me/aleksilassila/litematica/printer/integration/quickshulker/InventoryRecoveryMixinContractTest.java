package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.mixin.printer.mc.MixinConnection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClosePacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import static org.junit.jupiter.api.Assertions.*;

/** Checks the real injected callbacks and their targets in every supported Minecraft JAR. */
class InventoryRecoveryMixinContractTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test void hooksPreservePacketsAndCountStatisticsOnlyOnTheWritePath() throws Exception {
        var connection = new MixinConnection();
        var ticket = InventoryRecoveryFence.prepare(connection);
        assertSame(ticket.packet(), call(connection, "printer$trackRecoveryInput", ticket.packet()));
        InventoryRecoveryFence.received(connection);
        assertFalse(ticket.complete(), "Enqueueing a marker is not a server reply");
        assertSame(ticket.packet(), call(connection, "printer$trackRecoveryFence", ticket.packet()));
        InventoryRecoveryFence.received(connection);
        assertTrue(ticket.complete());
        assertFalse(ticket.invalidated());
    }

    @Test void closeAndCreativeInventoryInputInvalidateWithoutCancellingManualPackets() throws Exception {
        for (Packet<?> input : List.of(new ServerboundContainerClosePacket(0),
                new ServerboundSetCreativeModeSlotPacket(36, ItemStack.EMPTY))) {
            var connection = new MixinConnection();
            var ticket = InventoryRecoveryFence.prepare(connection);
            assertSame(input, call(connection, "printer$trackRecoveryInput", input));
            assertTrue(ticket.invalidated());
        }
    }

    @Test void genericPacketArgumentHooksMatchUniqueTargetsAcrossVersions() throws Exception {
        var mixin = read("me/aleksilassila/litematica/printer/mixin/printer/mc/MixinConnection");
        var target = read("net/minecraft/network/Connection");
        int checked = 0;
        for (var handler : mixin.methods) {
            var annotations = handler.visibleAnnotations == null ? List.<AnnotationNode>of() : handler.visibleAnnotations;
            for (var annotation : annotations) {
                if (!annotation.desc.endsWith("/ModifyVariable;")) continue;
                checked++;
                @SuppressWarnings("unchecked") var names = (List<String>) value(annotation, "method");
                assertEquals(1, names.size());
                assertEquals(handler.name.endsWith("Input") ? "sendPacket" : "doSendPacket", names.get(0));
                var targets = target.methods.stream().filter(m -> m.name.equals(names.get(0))).toList();
                assertEquals(1, targets.size(), "Name-only selector must remain unambiguous");
                var packet = Type.getType("Lnet/minecraft/network/protocol/Packet;");
                assertEquals(packet, Type.getArgumentTypes(targets.get(0).desc)[0]);
                assertArrayEquals(new Type[]{packet}, Type.getArgumentTypes(handler.desc));
                assertEquals(packet, Type.getReturnType(handler.desc));
                assertEquals(Boolean.TRUE, value(annotation, "argsOnly"));
                assertEquals(0, value(annotation, "ordinal"));
            }
        }
        assertEquals(2, checked, "Both enqueue and actual-write boundaries must be hooked");
        var listener = read("net/minecraft/client/multiplayer/ClientPacketListener");
        for (String method : List.of("handleAwardStats", "handleContainerClose", "handleOpenScreen")) {
            assertEquals(1, listener.methods.stream().filter(m -> m.name.equals(method)).count());
        }
    }

    @Test void inventoryChangingInputsCannotLeaveARecoveryFenceValid() throws Exception {
        // These hooks classify packet types without inspecting payloads or serializing them.
        // Allocate the real classes to keep the test independent of recipe payload API changes.
        var types = new java.util.ArrayList<Class<? extends Packet<?>>>();
        types.add(net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket.class);
        types.add(net.minecraft.network.protocol.game.ServerboundInteractPacket.class);
        types.add(net.minecraft.network.protocol.game.ServerboundUseItemOnPacket.class);
        types.add(net.minecraft.network.protocol.game.ServerboundUseItemPacket.class);
        //#if MC >= 12104
        types.add(net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket.class);
        types.add(net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket.class);
        //#else
        //$$ types.add(net.minecraft.network.protocol.game.ServerboundPickItemPacket.class);
        //#endif
        var field = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        var unsafe = (sun.misc.Unsafe) field.get(null);
        for (var type : types) {
            var packet = (Packet<?>) unsafe.allocateInstance(type);
            var connection = new MixinConnection();
            var ticket = InventoryRecoveryFence.prepare(connection);
            assertSame(packet, call(connection, "printer$trackRecoveryInput", packet));
            assertTrue(ticket.invalidated(), type.getSimpleName());
        }
    }

    private static Packet<?> call(MixinConnection target, String name, Packet<?> packet) throws Exception {
        var method = MixinConnection.class.getDeclaredMethod(name, Packet.class);
        method.setAccessible(true);
        return (Packet<?>) method.invoke(target, packet);
    }

    private static ClassNode read(String name) throws Exception {
        try (var input = InventoryRecoveryMixinContractTest.class.getClassLoader().getResourceAsStream(name + ".class")) {
            assertNotNull(input, name);
            var node = new ClassNode();
            new ClassReader(input).accept(node, 0);
            return node;
        }
    }

    private static Object value(AnnotationNode annotation, String key) {
        for (int i = 0; i < annotation.values.size(); i += 2) {
            if (key.equals(annotation.values.get(i))) return annotation.values.get(i + 1);
        }
        return null;
    }
}
