package neko.voidwarehouse.restock;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** Shared with litematica-printer; keep both copies identical. */
public record PrinterPlaceHelloPayload(int nonce) implements CustomPacketPayload {
    public static final Type<PrinterPlaceHelloPayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_place_hello"));
    public static final StreamCodec<FriendlyByteBuf, PrinterPlaceHelloPayload> CODEC = new StreamCodec<>() {
        @Override
        public PrinterPlaceHelloPayload decode(FriendlyByteBuf buf) {
            return new PrinterPlaceHelloPayload(buf.readVarInt());
        }

        @Override
        public void encode(FriendlyByteBuf buf, PrinterPlaceHelloPayload payload) {
            buf.writeVarInt(payload.nonce());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
