package neko.voidwarehouse.restock;

//#if MC >= 12111
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/** Shared with litematica-printer; target is the actual placement position before it changes. */
public record PrinterPlacePayload(int nonce, List<String> candidates, BlockPos target,
                                  BlockHitResult hit, int expectedStateId, String dimensionId) implements CustomPacketPayload {
    public static final int MAX_CANDIDATES = 8;
    public static final int MAX_ITEM_ID_LENGTH = 256;
    public static final Type<PrinterPlacePayload> TYPE = new Type<>(
            Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_place"));
    public static final StreamCodec<FriendlyByteBuf, PrinterPlacePayload> CODEC = new StreamCodec<>() {
        @Override
        public PrinterPlacePayload decode(FriendlyByteBuf buf) {
            int nonce = buf.readVarInt();
            int size = buf.readVarInt();
            if (size < 1 || size > MAX_CANDIDATES) {
                throw new IllegalArgumentException("Invalid direct-place candidate count: " + size);
            }
            List<String> candidates = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                candidates.add(buf.readUtf(MAX_ITEM_ID_LENGTH));
            }
            BlockPos target = buf.readBlockPos();
            BlockPos clicked = buf.readBlockPos();
            Direction face = buf.readEnum(Direction.class);
            Vec3 location = new Vec3(buf.readDouble(), buf.readDouble(), buf.readDouble());
            BlockHitResult hit = new BlockHitResult(location, face, clicked, buf.readBoolean());
            return new PrinterPlacePayload(nonce, List.copyOf(candidates), target, hit, buf.readVarInt(), buf.readUtf(256));
        }

        @Override
        public void encode(FriendlyByteBuf buf, PrinterPlacePayload payload) {
            int size = payload.candidates().size();
            if (size < 1 || size > MAX_CANDIDATES) {
                throw new IllegalArgumentException("Invalid direct-place candidate count: " + size);
            }
            buf.writeVarInt(payload.nonce());
            buf.writeVarInt(size);
            for (String candidate : payload.candidates()) {
                buf.writeUtf(candidate, MAX_ITEM_ID_LENGTH);
            }
            buf.writeBlockPos(payload.target());
            buf.writeBlockPos(payload.hit().getBlockPos());
            buf.writeEnum(payload.hit().getDirection());
            Vec3 location = payload.hit().getLocation();
            buf.writeDouble(location.x);
            buf.writeDouble(location.y);
            buf.writeDouble(location.z);
            buf.writeBoolean(payload.hit().isInside());
            buf.writeVarInt(payload.expectedStateId());
            buf.writeUtf(payload.dimensionId(), 256);
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

//#endif
