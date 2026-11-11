package neko.voidwarehouse.restock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Shared with litematica-printer; keep both copies identical. */
public record PrinterPlaceResultPayload(int nonce, int status) implements CustomPacketPayload {
    public static final int OK = 0;
    public static final int RULE_OFF = 1;
    public static final int EMPTY = 2;
    public static final int REJECTED = 3;
    public static final int THROTTLED = 4;
    public static final int BAD_REQUEST = 5;
    public static final int STALE = 6;
    public static final Type<PrinterPlaceResultPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_place_result"));
    public static final StreamCodec<FriendlyByteBuf, PrinterPlaceResultPayload> CODEC = new StreamCodec<>() {
        @Override
        public PrinterPlaceResultPayload decode(FriendlyByteBuf buf) {
            return new PrinterPlaceResultPayload(buf.readVarInt(), buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PrinterPlaceResultPayload payload) {
            buf.writeVarInt(payload.nonce());
            buf.writeVarInt(payload.status());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
