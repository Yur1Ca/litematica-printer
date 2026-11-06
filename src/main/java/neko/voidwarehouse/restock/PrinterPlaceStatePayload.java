package neko.voidwarehouse.restock;

//#if MC >= 12111

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.LinkedHashMap;
import java.util.Map;

/** Shared with litematica-printer; schematic properties are semantic data, never physical hit coordinates. */
public record PrinterPlaceStatePayload(PrinterPlacePayload placement, String blockId,
                                       Map<String, String> properties) implements CustomPacketPayload {
    public static final int MAX_PROPERTIES = 64;
    public static final int MAX_BLOCK_ID_LENGTH = 256;
    public static final int MAX_PROPERTY_NAME_LENGTH = 64;
    public static final int MAX_PROPERTY_VALUE_LENGTH = 128;
    public static final Type<PrinterPlaceStatePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_place_state_v1"));
    public static final StreamCodec<FriendlyByteBuf, PrinterPlaceStatePayload> CODEC = new StreamCodec<>() {
        @Override
        public PrinterPlaceStatePayload decode(FriendlyByteBuf buf) {
            PrinterPlacePayload placement = PrinterPlacePayload.CODEC.decode(buf);
            String blockId = buf.readUtf(MAX_BLOCK_ID_LENGTH);
            int count = buf.readVarInt();
            if (count < 0 || count > MAX_PROPERTIES) throw new IllegalArgumentException("Invalid direct-place property count");
            Map<String, String> properties = new LinkedHashMap<>();
            for (int i = 0; i < count; i++) {
                String name = buf.readUtf(MAX_PROPERTY_NAME_LENGTH);
                String value = buf.readUtf(MAX_PROPERTY_VALUE_LENGTH);
                if (name.isEmpty() || properties.putIfAbsent(name, value) != null) {
                    throw new IllegalArgumentException("Invalid or duplicate direct-place property name");
                }
            }
            return new PrinterPlaceStatePayload(placement, blockId, Map.copyOf(properties));
        }

        @Override
        public void encode(FriendlyByteBuf buf, PrinterPlaceStatePayload payload) {
            Map<String, String> properties = payload.properties();
            if (properties.size() > MAX_PROPERTIES) throw new IllegalArgumentException("Invalid direct-place property count");
            PrinterPlacePayload.CODEC.encode(buf, payload.placement());
            buf.writeUtf(payload.blockId(), MAX_BLOCK_ID_LENGTH);
            buf.writeVarInt(properties.size());
            for (Map.Entry<String, String> entry : properties.entrySet()) {
                if (entry.getKey().isEmpty()) throw new IllegalArgumentException("Invalid direct-place property name");
                buf.writeUtf(entry.getKey(), MAX_PROPERTY_NAME_LENGTH);
                buf.writeUtf(entry.getValue(), MAX_PROPERTY_VALUE_LENGTH);
            }
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//#endif
