package neko.voidwarehouse.restock;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

import java.util.List;

/**
 * 打印机 → 服务端：「背包里这几格是刚才从虚空仓库拿的，用完了，收回仓库」。
 *
 * <h2>为什么发的是<b>槽位号</b>而不是物品</h2>
 * <p>这是本通道唯一一条安全红线：<b>服务端只认自己那份背包里的真实内容</b>。
 * 客户端说「第 12 格不要了」，服务端自己去读第 12 格，读到什么就把什么入库 ——
 * 客户端从头到尾没有机会描述物品内容，也就<b>不可能凭空造出物品</b>。
 * 如果改成「客户端把 ItemStack 发过来，服务端照单入库」，一个改过的客户端
 * 就能凭这条包无限刷物资，仓库直接变成刷物品机。</p>
 *
 * <p>换句话说：这条包的能力上界 = 「把玩家自己背包里的东西挪进玩家自己的仓库」，
 * 与漏斗、拆潜影盒完全同一量级，没有任何新增的凭空产出。</p>
 *
 * <h2>为什么一次带一批槽位</h2>
 * <p>背包被 30 多种材料占满时，需要退回去的往往是好几格。一格一个包的话，
 * 每包都要付一次 SavedData 写盘 + 一整套背包差分，纯浪费。<b>一次往返退一批</b>
 * 才是这个场景该有的粒度。</p>
 *
 * <h2>⚠ 这个类被两端共用</h2>
 * <p>与 {@link PrinterTakePayload} 同理：{@code litematica-printer} 里有同包名同类名的
 * 源码副本，字段与 {@code CODEC} 结构必须<b>逐字节等价</b>，否则单人环境下
 * 编解码会在 checkcast 处炸掉。改这里必须同步改客户端那一份。</p>
 */
public record PrinterReturnPayload(int nonce, List<Integer> slots) implements CustomPacketPayload {

    /** 唯一的返还通道：{@code carpet_neko_addition:printer_return}（C2S）。 */
    public static final CustomPacketPayload.Type<PrinterReturnPayload> TYPE =
            new CustomPacketPayload.Type<>(
                    Identifier.fromNamespaceAndPath("carpet_neko_addition", "printer_return"));

    /**
     * 一次最多带几个槽位。
     *
     * <p>取 36 是因为主背包 + 快捷栏就是 36 格 —— 完整退一批也就是这个上限。
     * 越界的槽位服务端会逐个跳过，不会因为客户端乱发就越界读写。</p>
     */
    public static final int MAX_SLOTS = 36;

    /**
     * 槽位号的编解码器。
     *
     * <p>与 {@link PrinterTakePayload#CODEC} 同款写法：{@code list} 的类型参数必须显式写出
     * （{@code <ByteBuf, Integer>}），否则推断退化成 {@code Object} 编译不过；
     * 结果保留成 {@code StreamCodec<ByteBuf, ...>} 以便同时喂给 26.3 与 1.21.11 的
     * {@code composite}（它的形参是 {@code StreamCodec<? super B, T>}）。</p>
     */
    private static final StreamCodec<ByteBuf, List<Integer>> SLOTS_CODEC =
            ByteBufCodecs.<ByteBuf, Integer>list(MAX_SLOTS).apply(ByteBufCodecs.VAR_INT);

    public static final StreamCodec<FriendlyByteBuf, PrinterReturnPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PrinterReturnPayload::nonce,
            SLOTS_CODEC, PrinterReturnPayload::slots,
            PrinterReturnPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
