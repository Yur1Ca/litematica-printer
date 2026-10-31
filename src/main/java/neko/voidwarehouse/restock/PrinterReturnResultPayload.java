package neko.voidwarehouse.restock;

//#if MC >= 12111
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 服务端 → 打印机：「刚才那批返还的结果」。
 *
 * <h2>这个包给客户端的两样东西</h2>
 * <ol>
 *   <li><b>销账依据</b>：{@code returned} 是这次真正收进仓库的<b>总数量</b>，
 *       客户端据此把「仓库欠我的」那本账减掉，避免把自己的库存也当成仓库给的退回去；</li>
 *   <li><b>失败原因</b>：规则没开 / 撞限流 / 包不合法，客户端应当退避而不是每 tick 重发。</li>
 * </ol>
 *
 * <h2>逐物品明细为什么<b>不在</b>这里</h2>
 * <p>明细走独立通道 {@link PrinterReturnDetailPayload}（{@code printer_return_detail}）。
 * 塞进本包末尾会让<b>新客户端配旧服务端</b>解码失败（旧服务端后面没有字节），
 * 而这条通道的设计前提是「不认识的包不发、不认识的版本不崩」。
 * 拆开之后两个包各自演进，任意新旧组合都安全。</p>
 *
 * <p>因此本包<b>保持三个数不变</b>，与老版本逐字节相同。</p>
 *
 * <h2>⚠ 两端共用</h2>
 * <p>服务端原件在 {@code carpet-Neko-Addition/modern/<ver>/src/main/java/neko/voidwarehouse/restock/}，
 * 同包名、同类名，状态码常量与 {@code CODEC} 的结构必须与这里逐字节等价。
 * 判据不是人眼比对：{@code python .workbuddy/tools/check_return_protocol_parity.py}。</p>
 */
public record PrinterReturnResultPayload(int nonce, int status, int returned) implements CustomPacketPayload {

    /** 唯一的返还回执通道：{@code carpet_neko_addition:printer_return_result}（S2C）。 */
    public static final CustomPacketPayload.Type<PrinterReturnResultPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_return_result"));

    /** 成功：至少有一格真的进了仓库 */
    public static final int OK = 0;
    /** 拒绝：Carpet 规则（{@code voidWarehouse} / {@code voidWarehouseRestock}）没开 */
    public static final int RULE_OFF = 1;
    /** 一格都没退成：槽位全空 / 全是潜影盒 / 全被跳过 */
    public static final int NOTHING = 2;
    /** 撞上限流：客户端发太快 */
    public static final int THROTTLED = 3;
    /** 包本身不合法（槽位清单为空） */
    public static final int BAD_REQUEST = 4;
    /** 本地状态：还没收到任何回执（服务端从 0 开始，所以用 -1 不冲突） */
    public static final int NONE = -1;

    public static final StreamCodec<FriendlyByteBuf, PrinterReturnResultPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::nonce,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::status,
            ByteBufCodecs.VAR_INT, PrinterReturnResultPayload::returned,
            PrinterReturnResultPayload::new
    );

    /** 失败路径的简写：只有状态码与 0 件。 */
    public static PrinterReturnResultPayload of(int nonce, int status) {
        return new PrinterReturnResultPayload(nonce, status, 0);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
//#endif
