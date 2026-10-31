package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import neko.voidwarehouse.restock.PrinterReturnDetailPayload;
import neko.voidwarehouse.restock.PrinterReturnPayload;
import neko.voidwarehouse.restock.PrinterReturnResultPayload;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import java.util.List;

/**
 * 客户端一侧的「返还」收发层：把槽位清单发出去，并把服务端回来的回执记住。
 *
 * <p>结构与 {@link VoidWarehouseRestockNetwork}（取货）对称，但有一处刻意的不同：
 * <b>C2S 那条自己也要注册</b>。取货当年只在单人环境验过 —— 同进程时服务端的注册表
 * 就是客户端那张，编码查得到 codec；到了真正的多人服务器，客户端进程有自己独立的
 * {@code SERVERBOUND_PLAY} 表，没登记过的类型在编码阶段会退化成
 * {@code DiscardedPayload}（数据整包丢掉，连接不断但等于没发）。所以这里两条都注册，
 * 撞名异常吞掉 —— 单人时服务端先注册、多人时客户端自己注册，两条路都能编码。</p>
 *
 * <h2>为什么收两条 S2C 通道</h2>
 * <p>{@code printer_return_result} 只带 {@code nonce + status + returned}（销账用），
 * 逐物品明细走 {@code printer_return_detail}（聊天提示用）。拆开是因为<b>加字段会解错包</b>：
 * 新客户端配旧服务端时，旧服务端不会打印细，新客户端按新结构读就会读穿。
 * 拆成两条通道后，收不到明细就是不报明细，其余一切照旧。</p>
 *
 * <h2>明细为什么不跟回执绑在一起写</h2>
 * <p>两条通道谁先到都不该丢数据，所以明细自己带一份 {@code nonce}（{@code itemsNonce}），
 * 读的人按流水号认领 —— {@link #returnedItemsOf} 只在两个 nonce 相等时才给内容。
 * 于是「先发明细后发回执」只是省事，而不是正确性的前提；反过来到也照样能拼出提示。</p>
 *
 * <h2>明细为什么存成「三份清单对齐后的一次性快照」</h2>
 * <p>网络上是三份分开的清单，读出方却想要「一条一条」的结构。这里在<b>接收的那一刻</b>
 * 就把它们 zip 成一个不可变的 {@link List}，之后谁读都是同一份快照 ——
 * 免得渲染线程读到「清单 A 已经是新的、清单 B 还是旧的」这种半截状态。</p>
 */
public final class VoidWarehouseReturnNetwork {

    private static volatile boolean initialized;

    /** 下一次请求要用的流水号；正数递增，绕回时跳过 0（0 不是合法 nonce）。 */
    private static int nextNonce = 1;

    private static volatile int latestNonce = 0;
    private static volatile int latestStatus = PrinterReturnResultPayload.NONE;
    private static volatile int latestReturned = 0;

    /** 逐物品明细属于哪一批（{@code 0} = 手上没有明细）。 */
    private static volatile int itemsNonce = 0;
    private static volatile List<Returned> latestItems = List.of();

    /**
     * 明细里的一条。
     *
     * @param itemId    注册名（{@code minecraft:dirt}）
     * @param count     这一批退回去的件数
     * @param remaining 入库做完之后仓库里这种物品的总数；{@code -1} = 服务端没给（读不到）
     */
    public record Returned(String itemId, int count, long remaining) {
    }

    private VoidWarehouseReturnNetwork() {
    }

    /** 在模组客户端初始化时调用一次。 */
    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;
        registerC2S();
        registerResultReceiver();
        registerDetailReceiver();
    }

    /**
     * 注册「客户端 → 服务端」的返还通道。
     *
     * <p>单人 / 局域网里服务端模组（{@code environment="*"}）已经在
     * {@code AutoMendingMod#onInitialize} 里注册过同一个 key 了 ——
     * {@code PayloadTypeRegistry} 在同一进程里是全局静态表，
     * {@code register} 的实现是 {@code containsKey → throw}（<b>严格去重</b>），
     * 所以必须吞掉撞名异常。</p>
     */
    private static void registerC2S() {
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.serverboundPlay().register(
                    PrinterReturnPayload.TYPE, PrinterReturnPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playC2S().register(
            //$$         PrinterReturnPayload.TYPE, PrinterReturnPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) {
            // 类型名与 codec 两端完全相同，谁注册的没有任何区别，直接用即可。
        }
    }

    /**
     * 注册「服务端 → 客户端」的回执通道。
     *
     * <p>客户端是<b>解包方</b>，不登记就解不出来；单人环境下服务端模组可能已经登记过，
     * 同样幂等处理。{@code registerGlobalReceiver} 按通道名挂 handler，
     * 与「谁注册了类型」是两件事，所以它无论如何都要自己挂。</p>
     */
    private static void registerResultReceiver() {
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.clientboundPlay().register(
                    PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playS2C().register(
            //$$         PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) {
            // 同上
        }
        ClientPlayNetworking.registerGlobalReceiver(
                PrinterReturnResultPayload.TYPE, VoidWarehouseReturnNetwork::receive);
    }

    /**
     * 注册「服务端 → 客户端」的明细通道。
     *
     * <p>与回执那条完全一样的理由与写法。单独一个方法是因为它<b>可以缺</b>：
     * 很旧的服务端不会发这条明细，此时 {@code canSend} 是 false，客户端只失去提示文字。</p>
     */
    private static void registerDetailReceiver() {
        try {
            //#if MC >= 260100
            PayloadTypeRegistry.clientboundPlay().register(
                    PrinterReturnDetailPayload.TYPE, PrinterReturnDetailPayload.CODEC);
            //#else
            //$$ PayloadTypeRegistry.playS2C().register(
            //$$         PrinterReturnDetailPayload.TYPE, PrinterReturnDetailPayload.CODEC);
            //#endif
        } catch (IllegalArgumentException alreadyRegisteredByServerMod) {
            // 同上
        }
        ClientPlayNetworking.registerGlobalReceiver(
                PrinterReturnDetailPayload.TYPE, VoidWarehouseReturnNetwork::receiveDetail);
    }

    /**
     * 服务端认不认这条返还通道。
     *
     * @return {@code true} = 对面装了支持返还的服务端模组（carpet-Neko-Addition 含本通道的版本）
     */
    public static boolean isSupported() {
        if (!initialized) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(PrinterReturnPayload.TYPE);
        } catch (RuntimeException ignored) {
            // canSend 在没有可用连接时会抛；那种情况下就是「不支持」的一种。
            return false;
        }
    }

    /**
     * 发出一批返还请求。
     *
     * @param slots 要交还给仓库的主背包槽位（0–35）
     * @return 本次请求的流水号；取不到流水号返回 0（表示没发出去）
     */
    public static int requestReturn(List<Integer> slots) {
        if (!initialized || slots == null || slots.isEmpty()) {
            return 0;
        }
        int nonce = allocateNonce();
        // 先清空再发：否则上一批的回执（或明细）会被当成这一批的结果
        latestNonce = nonce;
        latestStatus = PrinterReturnResultPayload.NONE;
        latestReturned = 0;
        itemsNonce = 0;
        latestItems = List.of();
        try {
            ClientPlayNetworking.send(new PrinterReturnPayload(nonce, List.copyOf(slots)));
        } catch (RuntimeException ignored) {
            latestNonce = 0;
            return 0;
        }
        return nonce;
    }

    /**
     * 查指定流水号的回执状态。
     *
     * @return 状态码；还没收到回执时返回 {@link PrinterReturnResultPayload#NONE}
     */
    public static int statusOf(int nonce) {
        if (nonce <= 0 || latestNonce != nonce) {
            return PrinterReturnResultPayload.NONE;
        }
        return latestStatus;
    }

    /**
     * 查指定流水号回执里「实收数量」。
     *
     * <p>客户端拿它把自己那本「仓库欠我的」账减掉 —— 只减不减会让账永远偏高，
     * 之后就可能把玩家自己带的材料也当成仓库给的退回去。</p>
     */
    public static int returnedOf(int nonce) {
        if (nonce <= 0 || latestNonce != nonce) {
            return 0;
        }
        return latestReturned;
    }

    /**
     * 查指定流水号回执里的逐物品明细。
     *
     * <p>可能是空清单 —— 服务端很旧、没发明细通道时就是空的，那不是错误状态，
     * 只是这一批没有可报的内容。拿它拼聊天提示的是 {@link VoidWarehouseReturnController}，
     * 时机是<b>销账之前</b>读出、销账之后再发 —— 顺序反了就会读到被 {@link #forget()} 清空的空清单。</p>
     */
    public static List<Returned> returnedItemsOf(int nonce) {
        if (nonce <= 0 || latestNonce != nonce || itemsNonce != nonce) {
            return List.of();
        }
        return latestItems;
    }

    /** 丢弃当前回执与明细，用于请求作废或超时收尾。 */
    public static void forget() {
        latestNonce = 0;
        latestStatus = PrinterReturnResultPayload.NONE;
        latestReturned = 0;
        itemsNonce = 0;
        latestItems = List.of();
    }

    /** 收到回执：只记销账要用的数，明细那条通道自己管自己。 */
    private static void receive(PrinterReturnResultPayload payload, ClientPlayNetworking.Context context) {
        latestNonce = payload.nonce();
        latestStatus = payload.status();
        latestReturned = payload.returned();
    }

    /**
     * 收到明细：属于当前这一批就收下，否则丢掉。
     *
     * <p>不属于当前请求的明细（nonce 对不上）直接忽略 —— 那不可能是「这一批」的内容，
     * 拿来拼提示只会报错数。这条路径跑在网络接收线程里，所以任何异常都不能往外抛。</p>
     */
    private static void receiveDetail(PrinterReturnDetailPayload payload, ClientPlayNetworking.Context context) {
        int nonce = payload.nonce();
        if (nonce <= 0 || latestNonce != nonce) {
            return;
        }
        itemsNonce = nonce;
        latestItems = zip(payload.itemIds(), payload.counts(), payload.remainings());
    }

    /**
     * 把三份平行清单压成一条一条。
     *
     * <p>长度按<b>最短</b>的那份取：坏包（或将来某一端加了字段但另一端还没跟上）最多丢几条明细，
     * 不能抛异常 —— 这条路径跑在网络接收线程里，抛出去就是断连。</p>
     */
    static List<Returned> zip(List<String> itemIds, List<Integer> counts, List<Long> remainings) {
        if (itemIds == null || counts == null || remainings == null) {
            return List.of();
        }
        int size = Math.min(itemIds.size(), Math.min(counts.size(), remainings.size()));
        if (size <= 0) {
            return List.of();
        }
        java.util.List<Returned> out = new java.util.ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            String id = itemIds.get(i);
            Integer count = counts.get(i);
            Long remaining = remainings.get(i);
            if (id == null || count == null || remaining == null) {
                continue;
            }
            out.add(new Returned(id, count, remaining));
        }
        return List.copyOf(out);
    }

    private static synchronized int allocateNonce() {
        int nonce = nextNonce;
        nextNonce = nonce >= Integer.MAX_VALUE ? 1 : nonce + 1;
        return nonce;
    }
}
//#endif
