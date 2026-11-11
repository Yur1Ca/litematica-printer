package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import io.netty.buffer.Unpooled;
import neko.voidwarehouse.restock.PrinterTakePayload;
import neko.voidwarehouse.restock.PrinterTakeResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.core.RegistryAccess;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class VoidWarehouseRestockNetworkTest {
    @Test
    void lateOrUnrequestedReceiptsCannotEraseTheActiveDelivery() throws Exception {
        var nonce = VoidWarehouseRestockNetwork.class.getDeclaredField("latestNonce");
        nonce.setAccessible(true);
        nonce.setInt(null, 20);
        try {
            receive(new PrinterTakeResultPayload(20, PrinterTakeResultPayload.OK));
            assertEquals(PrinterTakeResultPayload.OK, VoidWarehouseRestockNetwork.statusOf(20));
            receive(new PrinterTakeResultPayload(19, PrinterTakeResultPayload.EMPTY));
            assertEquals(PrinterTakeResultPayload.OK, VoidWarehouseRestockNetwork.statusOf(20),
                    "An old response cannot send the current confirmed delivery back into waiting");
            receive(new PrinterTakeResultPayload(21, PrinterTakeResultPayload.RULE_OFF));
            assertEquals(PrinterTakeResultPayload.OK, VoidWarehouseRestockNetwork.statusOf(20));
        } finally {
            VoidWarehouseRestockNetwork.forget();
        }
    }

    @Test
    void forgettingATakePreventsItsLateReceiptFromResurrectingIt() throws Exception {
        VoidWarehouseRestockNetwork.forget();
        try {
            receive(new PrinterTakeResultPayload(20, PrinterTakeResultPayload.OK));
            assertEquals(PrinterTakeResultPayload.NONE, VoidWarehouseRestockNetwork.statusOf(20));
        } finally {
            VoidWarehouseRestockNetwork.forget();
        }
    }

    private static void receive(PrinterTakeResultPayload payload) throws Exception {
        var receive = VoidWarehouseRestockNetwork.class.getDeclaredMethod("receive",
                PrinterTakeResultPayload.class, ClientPlayNetworking.Context.class);
        receive.setAccessible(true);
        receive.invoke(null, payload, null);
    }

    @Test
    void clientAloneRegistersAnEncodableTakePayloadAndInitializationIsIdempotent() throws Exception {
        var registry = (PayloadTypeRegistryImpl<RegistryFriendlyByteBuf>) PayloadTypeRegistry.serverboundPlay();
        assertNull(registry.get(PrinterTakePayload.TYPE), "No server mod initializes this test process");
        VoidWarehouseRestockNetwork.init();
        var registered = registry.get(PrinterTakePayload.TYPE);
        assertNotNull(registered, "Remote channel support does not register the client's encoder");
        var payload = new PrinterTakePayload(42, List.of("minecraft:stone", "minecraft:cobblestone"));
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), RegistryAccess.EMPTY);
        try {
            registered.codec().encode(buffer, payload);
            assertEquals(payload, PrinterTakePayload.CODEC.decode(buffer));
            assertFalse(buffer.isReadable());
        } finally {
            buffer.release();
        }
        assertDoesNotThrow(VoidWarehouseRestockNetwork::init);
        assertSame(registered, registry.get(PrinterTakePayload.TYPE));
        // An integrated server can have registered both payload types before client init.
        var initialized = VoidWarehouseRestockNetwork.class.getDeclaredField("initialized");
        initialized.setAccessible(true);
        initialized.setBoolean(null, false);
        assertDoesNotThrow(VoidWarehouseRestockNetwork::init);
        assertSame(registered, registry.get(PrinterTakePayload.TYPE));
    }
}
