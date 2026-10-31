package neko.voidwarehouse.restock;

//#if MC >= 12111
import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 服务端 → 打印机：「刚才那批返还，每种退了多少、仓库里还剩多少」。
 *
 * <h2>为什么不塞进 {@link PrinterReturnResultPayload}</h2>
 * <p>那个包是三个数（{@code nonce / status / returned}），已经在现网跑过。
 * 在它末尾追加清单会让<b>新客户端配旧服务端</b>直接崩：旧服务端不会发清单，
 * 新客户端读到一半就没有字节了。所以明细单独一条通道，
 * 认不认得出这条通道由两端的 {@code canSend} 在发包前判定 —— 任何新旧组合都安全。</p>
 *
 * <h2>没收到这条通道怎么办</h2>
 * <p>服务端是很旧的版本时不会发这条明细。此时客户端只收到回执，照样能销账，
 * 只是聊天栏里那句提示没有内容可报 —— 那就干脆不报。</p>
 *
 * <h2>三份平行清单</h2>
 * <p>与 {@link PrinterQueryResultPayload} 同一套做法：{@code itemIds / counts / remainings}
 * 按同一个顺序排列，最多 {@link #MAX_ENTRIES} 条（一批最多覆盖主背包 36 格）。
 * 解码后仍要按 {@code min(size)} 兜底 —— 坏包不该让游戏崩。</p>
 *
 * <h2>剩余量的口径</h2>
 * <p>{@code remainings[i]} 是这批<b>全部入库做完之后</b>服务端现读的仓库总量
 * （物品 + 数据组件）。客户端<b>不推算</b>这个数：它看不到仓库，也没有权威数据。</p>
 *
 * <h2>⚠ 两端共用</h2>
 * <p>服务端原件在 {@code carpet-Neko-Addition/modern/<ver>/src/main/java/neko/voidwarehouse/restock/}，
 * 同包名、同类名，{@code CODEC} 结构必须与这里逐字节等价。
 * 判据不是人眼比对：{@code python .workbuddy/tools/check_return_protocol_parity.py}。</p>
 */
public record PrinterReturnDetailPayload(
        int nonce,
        List<String> itemIds,
        List<Integer> counts,
        List<Long> remainings
) implements CustomPacketPayload {

    /** 明细通道：{@code carpet_neko_addition:printer_return_detail}（S2C）。 */
    public static final CustomPacketPayload.Type<PrinterReturnDetailPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_return_detail"));

    /**
     * 明细最多几条。
     *
     * <p>一批最多覆盖主背包 + 快捷栏 36 格，所以「最多 36 种」是这一批的硬上限 ——
     * 与服务端 {@code PrinterReturnService} 的 {@code MAIN_INVENTORY_SLOTS} 必须一致。
     * 给到 36 而不是更小，是为了让「36 格全是不一样的方块」这种极端批次也能整批描述清楚。</p>
     */
    public static final int MAX_ENTRIES = 36;

    private static final StreamCodec<ByteBuf, List<String>> IDS_CODEC =
            ByteBufCodecs.<ByteBuf, String>list(MAX_ENTRIES).apply(ByteBufCodecs.STRING_UTF8);

    private static final StreamCodec<ByteBuf, List<Integer>> COUNTS_CODEC =
            ByteBufCodecs.<ByteBuf, Integer>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_INT);

    private static final StreamCodec<ByteBuf, List<Long>> REMAININGS_CODEC =
            ByteBufCodecs.<ByteBuf, Long>list(MAX_ENTRIES).apply(ByteBufCodecs.VAR_LONG);

    public static final StreamCodec<FriendlyByteBuf, PrinterReturnDetailPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterReturnDetailPayload::nonce,
            IDS_CODEC, PrinterReturnDetailPayload::itemIds,
            COUNTS_CODEC, PrinterReturnDetailPayload::counts,
            REMAININGS_CODEC, PrinterReturnDetailPayload::remainings,
            PrinterReturnDetailPayload::new
    );

    /** 空明细的简写（一格都没退成时不该发，但留个构造给测试用）。 */
    public static PrinterReturnDetailPayload of(int nonce) {
        return new PrinterReturnDetailPayload(nonce, List.of(), List.of(), List.of());
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//#endif
