package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeComponent;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEvent;
import me.aleksilassila.litematica.printer.integration.inventory.WorldIdentity;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import neko.voidwarehouse.restock.PrinterReturnResultPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * 把「从虚空仓库取出来的材料用不完」这件事收回去。
 *
 * <h2>它解决的具体问题</h2>
 * <p>仓库每次补货都给<b>一整组</b>（见服务端 {@code PrinterTakeService}）。一个材料种类
 * 超过 30 种的投影，打到后面背包会被一堆「只用掉三五个、剩下五六十」的整组材料占满 ——
 * 36 格全满之后再要新材料，服务端只能回 {@code INVENTORY_FULL}，打印直接卡死。
 * 原版打印机的对策是「有序存放」（{@code storeOrderly}）：把从背包潜影盒里取出来的
 * 东西<b>原样退回那个潜影盒</b>。仓库来的材料没有潜影盒可退，
 * 这个控制器就是给那条路补上「退到仓库」的对应实现。</p>
 *
 * <h2>为什么不去改原版那条路</h2>
 * <p>{@code OrderedStorageController} 的每一步都是「打开源潜影盒 → 点格子 → 关掉」，
 * 与仓库这条「发一个包、服务端塞回去」在时序上完全不兼容（前者要占容器与主手租约，
 * 后者什么都不占）。硬塞进同一个类只会让两条路互相踩状态。所以这里是<b>独立的组件</b>：
 * 只记账、只在返回值为真的那几条时机发包，与潜影盒那条路互不相干，
 * 原来开 {@code storeOrderly} 的玩家行为一个字节都不变。</p>
 *
 * <h2>账怎么记（与开关无关）</h2>
 * <p>只记<b>仓库给了什么、给了多少</b>（{@code injected}，按物品累计件数），
 * 不记槽位 —— 因为打印机会把材料在快捷栏与背包之间搬来搬去（{@code InventorySwitchGuard}），
 * 记槽位第二天就对不上了。每 tick 用「背包里现在还有多少」给它做一次上限收敛
 * （{@link #reconcile()}）：打印机消耗掉的部分会自动从账上掉下去，
 * 玩家的手工操作也不会让账越记越多。</p>
 *
 * <p>🔴 <b>记账不看开关</b>，只有「发不发包」看开关。上一版在 {@link #recordTaken} 里
 * 先判开关，结果是：玩家通常等背包已经被占满、打印卡住了才去打开开关 —— 那一刻账本是空的，
 * 而卡住之后打印机不会再取新材料（{@code findAvailableItem} 直接短路，不会再有发货），
 * 于是账本永远补不回来，开关开或关看起来毫无区别。</p>
 *
 * <h2>账本什么时候作废</h2>
 * <p>只在<b>断线</b>时（{@code disconnect}）—— 那是唯一确定「接下来看到的是另一份背包」的时刻。
 * 见 {@link #dropsLedger}。</p>
 *
 * <p>🔴 关打印总开关、改配置项、进世界时 level 引用换对象、重生/换维度重建玩家实例 ——
 * 这些都<b>不会换背包</b>，账本必须留着。上一版 {@code clear()} 接在<b>所有</b> epoch 变化上，
 * 于是「关掉打印开关」就把账本清空了；而关开关那一刻背包往往已经满了，账本一空 ⇒
 * 返还认不出仓库货 ⇒ 一格也退不了；背包满 ⇒ 服务端 {@code INVENTORY_FULL} ⇒ 取不到货 ⇒
 * 账本永远重建不起来。这个死锁只能靠玩家手动扔东西解开（2026-10-31 19:48 实测）。</p>
 *
 * <h2>什么时候发（以<b>背包现状</b>为准）</h2>
 * <ol>
 *   <li><b>打印已经停下</b>（工作总开关关掉）：把仓库给的余料整批退回去 ——
 *       这是「收工清背包」；</li>
 *   <li><b>打印途中快放不下新材料了</b>：空槽 ≤ {@link #FREE_SLOTS_TRIGGER} 时退一批，
 *       把空槽补回 {@link #FREE_SLOTS_TARGET} 个。</li>
 * </ol>
 *
 * <p>判据里必须有<b>仓库货参与</b>（{@link #countWarehouseSlots}）：背包如果被玩家自己的
 * 材料占满，退什么都没有意义，也轮不到这个组件动手。</p>
 *
 * <p>🔴 <b>为什么不再看「方块种类数」</b>：那曾经是主判据（≥ 30 种就退），预设是
 * 「36 格里放 30 种就快满了」。这个预设只在<b>投影材料种类很少</b>时成立。
 * 一旦投影要的材料种类超过格数（HUB 要 294 种），背包里永远有 30 种以上，
 * 判定<b>恒为真</b>，清理就变成常开 —— 每几 tick 把打印机马上要用的材料抢走一批。
 * 实测表现就是「打印一开始就卡壳」。</p>
 *
 * <h2>退完在聊天栏说一句</h2>
 * <p>返还全程背着玩家发生（背包少几格、仓库多几组，中间没有任何可见反馈），所以每成功一批
 * 就在聊天栏报一行：<b>这一批退了什么、各多少</b>，以及<b>入库做完之后仓库里各还剩多少</b>。
 * 两个数都来自服务端回执（{@link VoidWarehouseReturnNetwork.Returned}）——
 * 客户端不去推算剩余量，它看不到仓库。版式见 {@link VoidWarehouseReturnNotice}。</p>
 *
 * <p>只报<b>成功</b>（{@link PrinterReturnResultPayload#OK}）的那一批：规则没开、撞限流、
 * 一格都没退成时不发 —— 那些情况玩家没有「东西去哪了」的疑问，说了反而是噪音。</p>
 *
 * <h2>退哪些：按「最后被用掉的时刻」做 LRU 淘汰</h2>
 * <p>背包只有 36 格，而 HUB 那种投影要 <b>294 种</b>材料 —— 装不下是结构性的，
 * 所以「什么时候退」只是次要问题，关键是<b>退掉哪几格</b>。</p>
 *
 * <p>判据是 {@link #lastTouched}：每 tick 比对「该物品在背包里的件数」（{@link #reconcile(long)}），
 * <b>变少就算被用过</b>，记下当前 tick。要腾格子时按这个时刻升序排，最冷的先退
 * （{@link #evictionOrder(long[])}）。语义就是「打印机已经走开的那一片区域，把它剩下的料收走」。</p>
 *
 * <p>🔴 上一版用的是「到货超过 {@link #HOT_TICKS} 就退」，等于按<b>到货时间</b>做 FIFO，
 * 和「还在不在用」无关。实测（19:43–19:48）取走 17,872 件、退回 14,102 件，
 * 净消耗只占两成：材料刚到手、只用掉几个就整组被收回，转头又要。换成 LRU 之后，
 * 被退的是「最近没人碰」的那些，活跃轮换里的材料不会再被抽走。</p>
 *
 * <h2>为什么「刚到手的不许退」这条保护仍然保留</h2>
 * <p>补货一次给一整组，打印机只放掉三五个就接着干下一种。中间那段
 * 「已经到手、还没用完」的窗口里，{@code materialRequests.activeItems()} 是<b>空</b>的
 * （请求已经完成），所以「跳过正在要的物品」那道保险根本不生效。</p>
 *
 * <p>上一版就在这里出事：材料刚发到手就被退回去，打印机下一 tick 再要，
 * 于是形成「退 → 要 → 退」的死循环，每轮空烧两次包，一个方块也放不下去。
 * 现在按物品记 {@link #HOT_TICKS} 的到期时刻（{@link #markDelivered}），
 * 到期之前一律不退；原来那条「实在挑不出来就连正在要的一起退」的兜底已经删掉 ——
 * 它正是死循环的执行者。挑不出来就这一轮不动手，等热集过期。</p>
 *
 * <p>LRU 之后热集在多数时候是冗余的（刚到手 = 最热，本来就不会被选中），
 * 但它是平局时的保险：账本里所有物品都是同一 tick 到货时，排序会退化成插入顺序，
 * 这时靠热集挡住「刚落地就被自己退回去」。</p>
 *
 * <h2>只退「整格都是仓库货」的槽位</h2>
 * <p>协议只发槽位号，服务端会把这<b>一整格</b>都收走（它没有「只收 37 个」这种说法）。
 * 所以一格只有在账本<b>完整覆盖</b>它的堆叠数时才允许上交；账上只有半组、
 * 格子里却是「玩家的 64 + 仓库的 64」时一律跳过 —— 宁可少退，也绝不把玩家的存货退走。</p>
 */
public final class VoidWarehouseReturnController implements RuntimeComponent {

    /** 主背包 + 快捷栏的槽位数，与服务端的口径必须一致。 */
    private static final int MAIN_INVENTORY_SLOTS = 36;

    /**
     * 空槽降到这么少才动手。
     *
     * <p>🔴 <b>这里绝不能再看「方块种类数」</b>。上一版是「种类数 ≥ 30 就退」，当时的假设是
     * 「36 格里放 30 种就意味着快满了」—— 但那只对「投影材料种类很少」的场合成立。
     * HUB 这种投影要 <b>294 种</b>材料，36 格永远装着 30 种以上，于是这条判定
     * <b>在整场建造里恒为真</b>，控制器就变成了「每 4 tick 清一次背包」，
     * 而它清掉的正是打印机马上要用的材料 —— 打印直接被拖成「退 → 要 → 退」的空转。
     * 真正的判据只有一个：<b>还塞不塞得下新材料</b>。</p>
     */
    private static final int FREE_SLOTS_TRIGGER = 3;

    /**
     * 退完之后空槽要回到几个。
     *
     * <p>留 8 个（约 22% 的背包）是给「打印机这一带要连着换几种材料」的余量。
     * 原先只留 4 个，实测（2026-10-31 21:11）稳态就卡在 0~4 个空槽：
     * 45 秒里取了 68 次、退了 33 次 —— <b>平均每取一次就得退一次</b>，
     * 因为手上最多只放得下 4 种材料，每换一种就得先腾一格。</p>
     */
    private static final int FREE_SLOTS_TARGET = 8;

    /**
     * 刚发到手的材料在这段时间内不许退。
     *
     * <p>补货是「一次给一整组」，打印机只放掉三五个就接着干下一种。中间那段
     * 「已经到手、还没用完」的窗口里，{@code materialRequests.activeItems()} 是<b>空</b>的
     * （请求已经完成），所以「跳过正在要的物品」那道保险根本不生效 ——
     * 上一版就是在这个窗口里把材料抢回去，才形成死循环。这里按物品记一个到期时刻，
     * 到期之前一律不退。</p>
     */
    private static final long HOT_TICKS = 100L;

    /** 两批返还之间至少隔几个 tick（服务端限流是 50ms，这里给足 1 秒，避免清背包上瘾）。 */
    private static final long REQUEST_COOLDOWN_TICKS = 20L;

    /** 账本每隔多久落一次盘（tick）。30 秒 —— 关游戏与崩溃都能兜住，写盘开销可忽略。 */
    private static final long SNAPSHOT_INTERVAL_TICKS = 600L;

    /** 一批返还最多等多久回执；超时就当丢了，让下一批继续（否则一次丢包会永久卡住）。 */
    private static final long ACK_TIMEOUT_TICKS = 60L;

    /** 服务端明确拒绝（规则没开等）之后，多久之内不再尝试。 */
    private static final long REJECT_BACKOFF_TICKS = 200L;

    /**
     * 「账本丢了、背包又满了」这条告警最短多久打一次。
     *
     * <p>这个状态是<b>解不开</b>的（退不了也拿不到），不喊出来就只会表现成「打印无声无息停住」。
     * 但它是持续状态，每 tick 喊一次就是灾难，所以 30 秒一次。</p>
     */
    private static final long STUCK_WARN_COOLDOWN_TICKS = 600L;

    private final Minecraft client;

    /** 打印机现在是不是在干活（工作总开关开着）。 */
    private final BooleanSupplier printing;

    /** 背包是不是正被别的组件借用（切换物品/开容器），这时绝不能动背包。 */
    private final BooleanSupplier inventoryBusy;

    /** 当前材料请求里的候选物品 —— 退还时要避开它们。 */
    private final Supplier<List<Item>> requestedItems;

    /** 仓库还「欠」在背包里的件数：物品 → 件数。按注入顺序排列（最早的排前面）。 */
    private final Map<Item, Integer> injected = new LinkedHashMap<>();

    /**
     * 刚发到手的物品 → 到期 tick（到这一刻之前不许退）。
     *
     * <p>这就是「退 → 要 → 退」死循环的解药：补货到手的材料要在手里活过 {@link #HOT_TICKS}
     * 才算「余料」。淘汰由 {@link #sweepHot(long)} 顺手做，不需要单独遍历。</p>
     */
    private final Map<Item, Long> hotUntil = new LinkedHashMap<>();

    /**
     * 物品 → <b>最后一次被用掉</b>的 tick。LRU 淘汰的唯一依据。
     *
     * <p>「被用掉」= 这一 tick 里背包中该物品的件数比上一 tick 少（见 {@link #reconcile(long)}）。
     * 到货时也会写一次（{@link #markDelivered}），所以从没被用过的物品按「到货时刻」参与排序，
     * 不会被当成最冷的一批立刻退走。</p>
     *
     * <p>🔴 为什么不能用「到货时刻」当唯一依据（上一版就是这样）：补货一次给一整组，
     * 打印机用掉几个就去干别的，5 秒后这一组被当成余料退回，而它其实还在活跃轮换里 ——
     * 于是「取 64 / 退 60」反复空转（实测取退件数比 1.27）。真正该问的是
     * 「这东西最近还有人碰吗」，那就是这个时间戳。</p>
     */
    private final Map<Item, Long> lastTouched = new LinkedHashMap<>();

    /** 上一 tick 看到的持有件数，用来判断「这一 tick 少了」（= 正在被用）。 */
    private final Map<Item, Integer> lastHeld = new HashMap<>();

    /** 上一次看到的开关状态，只用来打一行「开/关」日志（{@code null} = 还没判过）。 */
    private Boolean lastEnabled;

    /**
     * 还有一份磁盘快照等着认回来。
     *
     * <p>置位时机 = 账本被作废（断线/换世界）；消费时机 = 下一次拿到玩家时。
     * 之所以不直接在 {@code onEpochChanged} 里读盘：那一刻玩家与世界对象可能还没就位，
     * 而认账需要拿背包逐条核对件数。</p>
     */
    private boolean restorePending = true;

    /** 上一次落盘的时刻（tick）与当时的账本指纹，用来避免每 30 秒都白写一次。 */
    private long lastSnapshotTick = Long.MIN_VALUE;
    private long lastSnapshotSignature = 0L;
    private String lastSnapshotWorld;
    private String lastSnapshotDimension;

    private int activeNonce;
    private long activeSince;
    private long lastRequestTick = Long.MIN_VALUE;
    private long rejectedUntilTick = Long.MIN_VALUE;

    /** 「账本丢了又腾不出格子」的告警下次最早可打的时刻（避免每 tick 刷屏）。 */
    private long stuckWarnedUntilTick = Long.MIN_VALUE;

    private Map<Item, Integer> inFlight;

    public VoidWarehouseReturnController(
            Minecraft client,
            BooleanSupplier printing,
            BooleanSupplier inventoryBusy,
            Supplier<List<Item>> requestedItems
    ) {
        this.client = client;
        this.printing = printing;
        this.inventoryBusy = inventoryBusy;
        this.requestedItems = requestedItems;
    }

    /**
     * 开关的唯一读取点。
     *
     * <p>整个组件只有这里碰配置项：「记账」与「判定」都不看它，只有「发不发包」看。
     * 这样开关就只是一个纯粹的阀门 —— 打开的那一瞬间，账本与背包现状都是现成的，
     * 下一次 tick 按新状态干活；关闭后不再发新请求，但已发请求仍需结算回执。</p>
     */
    private static boolean isEnabled() {
        return Configs.Special.VOID_WAREHOUSE_RETURN.getBooleanValue();
    }

    /**
     * 记一笔「仓库刚给了这些」。
     *
     * <p>由 {@link VoidWarehouseRestockAdapter} 在确认拿到货之后调用。
     * 数量取<b>背包增量的净值</b>（不是请求里写的 1 组）—— 服务端可能只放下了一部分。</p>
     *
     * <p>🔴 这里<b>不看开关</b>：开关管的是「退不退」，不是「记不记」。
     * 记账不看开关，玩家中途打开开关才有东西可退。</p>
     */
    public void recordTaken(Item item, int count) {
        if (item == null || count <= 0) {
            return;
        }
        this.injected.merge(item, count, Integer::sum);
    }

    /** 现在账上还欠着多少件（自查/日志用）。 */
    public int pendingCount() {
        int total = 0;
        for (int count : this.injected.values()) {
            total += count;
        }
        return total;
    }

    /** 账上记着多少种物品（自查/日志用）。 */
    public int pendingKinds() {
        return this.injected.size();
    }

    /**
     * 哪些 epoch 变化才真的意味着「背包换了一份」。
     *
     * <p>只有<b>断线</b>一个：那是唯一确定「接下来看到的是另一份背包」的时刻。
     * 其余 reason 全都<u>不会</u>换背包 —— 关打印总开关
     * （{@code work_switch_off} / {@code work_switch_disabled}）、改配置项
     * （{@code execution_scope_changed}）、进世界时 level 引用换了个对象
     * （{@code client_scope_changed}）、重生/换维度时重建 {@code LocalPlayer}
     * （{@code local_player_init}）—— 背包一格没动，账本必须留着。</p>
     *
     * <p>🔴 这正是本组件曾经死锁的原因：{@code clear()} 原先接在<b>所有</b> epoch 变化上，
     * 于是「玩家关掉打印开关」就把账本清空了。而关开关的那一刻背包往往已经满了
     * （只剩 0~1 个空槽），账本一空、返还就再也认不出哪些是仓库货 ⇒ 一格也退不了；
     * 同时背包满 ⇒ 服务端回 {@code INVENTORY_FULL} ⇒ 取不到货 ⇒ 账本永远重建不起来。
     * 三方互相锁死，只能靠玩家手动扔东西解开（19:48 实测）。</p>
     *
     * <p>留下 {@code local_player_init} 不清也是同一个道理：{@code LocalPlayer} 在重生与换维度时
     * 都会重建，而这两件事都<b>保留背包</b>。清掉只会白丢账本；
     * 真有物品消失（死亡掉落）时 {@link #reconcile()} 会把它夹到 0，不需要这里动手。</p>
     */
    private static final Set<String> DROP_LEDGER_REASONS = Set.of("disconnect");

    /**
     * 这个 reason 是否意味着「背包换了一份、账本该作废」。
     *
     * <p>{@code null} 按「该作废」处理：拿不到 reason 时保守一点，
     * 宁可丢账本（少退一点）也不能把玩家自己的物品当仓库货退走。</p>
     */
    static boolean dropsLedger(String reason) {
        return reason == null || DROP_LEDGER_REASONS.contains(reason);
    }

    @Override
    public void onEpochChanged(RuntimeEvent.EpochChanged event) {
        String reason = event.reason();
        if (dropsLedger(reason)) {
            long now = this.client.level == null ? this.lastSnapshotTick : this.client.level.getGameTime();
            pollAck(now);
            abandonInFlight();
            // 🔴 先把账本落盘再清。背包不会跟着清：不清盘的话，这一退一进之间
            // 身上那二十几格仓库材料就变成「认不出的死重」，永久占着格子（见 LedgerStore 类注释）。
            saveSnapshot();
            clear();
            this.restorePending = true;
        } else {
            // A same-session pause cannot cancel a request already sent to the server.
            // Retain its receipt so returned credit cannot migrate to personal stock.
            this.rejectedUntilTick = Long.MIN_VALUE;
        }
        this.lastEnabled = null;
    }

    /** Persist settled empty ledgers too, but never overwrite a snapshot before restoration. */
    private void saveSnapshot() {
        if (this.restorePending) {
            return;
        }
        Minecraft client = this.client;
        long gameTime = this.lastSnapshotTick;
        if (client.player != null && client.level != null) {
            this.lastSnapshotWorld = WorldIdentity.worldId(client);
            this.lastSnapshotDimension = WorldIdentity.dimensionId(client);
            gameTime = client.level.getGameTime();
        }
        if (this.lastSnapshotWorld == null) {
            return;
        }
        VoidWarehouseLedgerStore.save(
                this.lastSnapshotWorld,
                this.lastSnapshotDimension,
                gameTime,
                new LinkedHashMap<>(this.injected));
        this.lastSnapshotSignature = signature();
        this.lastSnapshotTick = gameTime;
    }

    /**
     * 账本的廉价指纹（件数合计 + 种类数），只用来判断「值不值得再写一次盘」。
     */
    private long signature() {
        long total = 0L;
        for (int count : this.injected.values()) {
            total += count;
        }
        return total * 31L + this.injected.size();
    }

    /**
     * 定期落盘。
     *
     * <p>为什么不能只靠 {@code disconnect}：那条路是 {@code client.execute(...)} 投递到客户端线程的，
     * 玩家直接关游戏时任务可能根本轮不到执行 —— 而「关游戏」正是最容易把满背包余料丢成死重的那次。
     * 每 {@link #SNAPSHOT_INTERVAL_TICKS} 写一次（内容没变就跳过），代价是每 30 秒一个小 JSON。</p>
     */
    private void maybeSaveSnapshot(long now) {
        if (this.restorePending) {
            return;
        }
        if (this.injected.isEmpty() && this.lastSnapshotSignature != 0L) {
            // Settlement must invalidate the old snapshot before another disconnect or crash.
            saveSnapshot();
            return;
        }
        if (this.lastSnapshotTick != Long.MIN_VALUE && now - this.lastSnapshotTick < SNAPSHOT_INTERVAL_TICKS) {
            return;
        }
        if (signature() == this.lastSnapshotSignature) {
            // 内容没变：只推进计时，省掉一次写盘
            this.lastSnapshotTick = now;
            return;
        }
        saveSnapshot();
    }

    /**
     * 读回上一次留下的账本，把「还认得出的余料」接着记上。
     *
     * <p>逐条校验见 {@link VoidWarehouseLedgerStore}：件数只许比快照多、不许少，
     * 少了的交给 {@link #reconcile(long)} 每 tick 夹住。</p>
     */
    private void restoreIfPending() {
        this.restorePending = false;
        if (!this.injected.isEmpty()) {
            return;
        }
        LocalPlayer player = this.client.player;
        if (player == null || this.client.level == null) {
            return;
        }
        VoidWarehouseLedgerStore.Snapshot snapshot = VoidWarehouseLedgerStore.load(
                WorldIdentity.worldId(this.client),
                WorldIdentity.dimensionId(this.client),
                this.client.level.getGameTime());
        if (snapshot == null) {
            return;
        }
        Inventory inventory = player.getInventory();
        int adoptedKinds = 0;
        int adoptedCount = 0;
        int dropped = 0;
        for (Map.Entry<String, Integer> entry : snapshot.items().entrySet()) {
            if (entry.getValue() < VoidWarehouseLedgerStore.MIN_ADOPT_COUNT) {
                dropped++;
                continue;
            }
            Item item = VoidWarehouseLedgerStore.itemOf(entry.getKey());
            if (item == null) {
                dropped++;
                continue;
            }
            if (countHeld(inventory, item) < entry.getValue()) {
                // 剩下的不够快照那么多：先不认，免得把玩家自己的东西算成仓库货
                dropped++;
                continue;
            }
            this.injected.merge(item, entry.getValue(), Integer::sum);
            adoptedKinds++;
            adoptedCount += entry.getValue();
        }
        if (adoptedKinds > 0) {
            Reference.LOGGER.info("[虚空仓库] 接着上次的账：认回 {} 种 / {} 件余料（丢弃 {} 条对不上的）",
                    adoptedKinds, adoptedCount, dropped);
        }
        if (dropped > 0) {
            // Rejected entries must not become valid again when personal stock is added later.
            saveSnapshot();
        }
    }

    /** 换世界/断线：账本作废（那是上一份背包的事）。 */
    public void clear() {
        this.injected.clear();
        this.hotUntil.clear();
        this.lastTouched.clear();
        this.lastHeld.clear();
        cancelInFlight();
        this.lastRequestTick = Long.MIN_VALUE;
        this.rejectedUntilTick = Long.MIN_VALUE;
        this.stuckWarnedUntilTick = Long.MIN_VALUE;
        this.lastSnapshotWorld = null;
        this.lastSnapshotDimension = null;
        this.lastSnapshotTick = Long.MIN_VALUE;
        this.lastSnapshotSignature = 0L;
    }

    /** An unconfirmed server mutation cannot leave reusable credit for later personal stock. */
    private void abandonInFlight() {
        boolean hadBatch = this.inFlight != null;
        if (hadBatch) {
            int count = 0;
            for (int amount : this.inFlight.values()) {
                count += amount;
            }
            deduct(count);
        }
        cancelInFlight();
        if (hadBatch) {
            saveSnapshot();
        }
    }

    /** 丢弃在飞的那一批，但不动账本。 */
    private void cancelInFlight() {
        this.activeNonce = 0;
        this.activeSince = 0L;
        this.inFlight = null;
        VoidWarehouseReturnNetwork.forget();
    }

    /** 每客户端 tick 调一次。 */
    public void tick() {
        if (this.client.player == null || this.client.level == null) {
            // World teardown can precede disconnect. Retire only the uncertain batch,
            // persisting it against the last known world while retaining other materials.
            abandonInFlight();
            this.lastEnabled = null;
            return;
        }
        if (this.restorePending) {
            // 先把上一次留下的账认回来，再判断退不退。放在这里而不是 epoch 回调里：
            // 认账要拿背包逐条核对件数，那时玩家与世界对象还不一定就位。
            restoreIfPending();
        }

        boolean enabled = isEnabled();
        if (!enabled) {
            // 🔴 只停手、不清账：清了的话「关一会儿再开」就再也退不动了
            if (!Boolean.FALSE.equals(this.lastEnabled)) {
                logSwitch(false);
            }
            this.lastEnabled = false;
            long now = this.client.level.getGameTime();
            // Disabling returns stops new requests, not inventory accounting.
            // Settle an available receipt before reconciling the synced inventory.
            if (pollAck(now)) {
                return;
            }
            reconcile(now);
            maybeSaveSnapshot(now);
            return;
        }
        if (!Boolean.TRUE.equals(this.lastEnabled)) {
            logSwitch(true);
        }
        this.lastEnabled = true;

        if (!VoidWarehouseReturnNetwork.isSupported()) {
            // 对面没装带这条通道的服务端模组：发也发不出去，把在飞的清掉就完事。
            // 账本同样留着 —— 换服回来还能接着用。
            abandonInFlight();
            return;
        }

        long now = this.client.level.getGameTime();
        if (pollAck(now)) {
            // 上一批还在飞：这 tick 什么都不做，等回执或超时
            return;
        }
        reconcile(now);
        maybeSaveSnapshot(now);
        LocalPlayer player = this.client.player;
        if (this.injected.isEmpty()) {
            warnIfLedgerLostAndFull(player, now);
            return;
        }
        if (!player.containerMenu.equals(player.inventoryMenu)) {
            // 开着别的容器时不碰背包：东西在玩家眼皮底下突然消失比卡住更吓人
            return;
        }
        if (this.inventoryBusy.getAsBoolean()) {
            return;
        }

        List<Integer> slots;
        if (!this.printing.getAsBoolean()) {
            // 收工清背包：账上有多少退多少。这时不受「热集」限制 —— 打印机已经不干活了，
            // 刚发到手的那点余料也不会再用上。
            slots = chooseSlots(Integer.MAX_VALUE, false, Set.of());
        } else {
            Inventory inventory = player.getInventory();
            int free = countFreeSlots(inventory);
            int warehouseSlots = countWarehouseSlots(inventory);
            if (!shouldReturnWhilePrinting(free, warehouseSlots)) {
                return;
            }
            int wanted = slotsWanted(free);
            slots = chooseSlots(wanted, true, hotItems(now));
            // 挑不出来就这一轮什么都不做。
            // 🔴 这里<b>绝不能</b>退而求其次去退「正在要的」那一份 —— 上一版有条 panic 兜底
            // 就是这么干的，结果打印机刚拿到手就被抢走，形成「退 → 要 → 退」的死循环，
            // 表现为打印完全停滞。热集到期后自然会有能退的东西，等着就是了。
        }
        if (slots.isEmpty()) {
            return;
        }
        if (now < this.rejectedUntilTick) {
            return;
        }
        if (this.lastRequestTick != Long.MIN_VALUE && now - this.lastRequestTick < REQUEST_COOLDOWN_TICKS) {
            return;
        }

        Map<Item, Integer> batch = measure(slots);
        int nonce = VoidWarehouseReturnNetwork.requestReturn(slots);
        if (nonce == 0) {
            return;
        }
        this.activeNonce = nonce;
        this.activeSince = now;
        this.inFlight = batch;
        this.lastRequestTick = now;
    }

    /**
     * 退成功就报一句：退了什么、仓库里还剩多少。
     *
     * <p>数字全部取自服务端回执（{@link VoidWarehouseReturnNetwork.Returned}），
     * 客户端不算、不猜 —— 理由见 {@link VoidWarehouseReturnNotice} 的类注释。</p>
     *
     * <p>提示是<b>附带</b>的：拼文本或发聊天出任何问题都只吞掉，绝不能让「已经退进仓库了」
     * 这件事因为一行字而变味。</p>
     */
    private static void announce(List<VoidWarehouseReturnNetwork.Returned> returnedItems) {
        try {
            VoidWarehouseReturnNotice.announce(returnedItems);
        } catch (RuntimeException ignored) {
            // 提示失败不影响返还
        }
    }

    private void logSwitch(boolean enabled) {
        Reference.LOGGER.info("[虚空仓库] 余料返还已{}（账上 {} 件 / {} 种，背包 {} 个空槽）",
                enabled ? "启用" : "关闭",
                pendingCount(),
                pendingKinds(),
                this.client.player == null ? -1 : countFreeSlots(this.client.player.getInventory()));
    }

    /**
     * 账本空 + 背包也塞不下新材料时，喊一声 —— 这是一个<b>自己解不开</b>的状态。
     *
     * <p>两条路同时断掉：</p>
     * <ul>
     *   <li><b>退不了</b>：账本是空的，{@link #chooseSlots} 认不出哪一格是仓库货，
     *       硬退就可能把玩家自己的存货退走；</li>
     *   <li><b>拿不到</b>：没有空槽、也没有可合并的同类堆，服务端 {@code tryGive}
     *       一件都放不下，直接回 {@code INVENTORY_FULL}，于是账本也永远重建不起来。</li>
     * </ul>
     *
     * <p>不喊出来的话，现象就是「打印无声无息停住、设置全都对」—— 19:48 实测踩过。
     * 所以这里必须留一条能直接对上号的日志，并且明确告诉玩家怎么解。</p>
     */
    private void warnIfLedgerLostAndFull(LocalPlayer player, long now) {
        if (player == null || !this.printing.getAsBoolean()) {
            return;
        }
        int free = countFreeSlots(player.getInventory());
        if (free > FREE_SLOTS_TRIGGER) {
            return;
        }
        if (now < this.stuckWarnedUntilTick) {
            return;
        }
        this.stuckWarnedUntilTick = now + STUCK_WARN_COOLDOWN_TICKS;
        Reference.LOGGER.warn("[虚空仓库] 余料返还的账本是空的，而背包只剩 {} 个空槽 —— "
                + "这种状态自己解不开：认不出哪些是仓库货所以退不了，"
                + "背包满了服务端又会回 INVENTORY_FULL 所以也拿不到。"
                + "手动腾出 1~2 格后账本会自动重建。", free);
    }

    /**
     * 收一批回执。
     *
     * @return {@code true} = 这批还没结束（继续等，本 tick 不要再发）
     */
    private boolean pollAck(long now) {
        if (this.activeNonce == 0) {
            return false;
        }
        if (now - this.activeSince > ACK_TIMEOUT_TICKS) {
            // The batch may already have been removed server-side. Do not reuse its credit
            // after a timeout; this can leave unreturned warehouse stock, but never sends it twice.
            abandonInFlight();
            return false;
        }
        int status = VoidWarehouseReturnNetwork.statusOf(this.activeNonce);
        if (status == PrinterReturnResultPayload.NONE) {
            return true;
        }
        if (status == PrinterReturnResultPayload.OK) {
            // 明细必须在 cancelInFlight() 之前取：那个方法会 forget()，把回执连同明细一起清掉。
            // 顺序是「读明细 → 销账 → 报一句」，三件事互不依赖，但读要最先。
            List<VoidWarehouseReturnNetwork.Returned> returnedItems =
                    VoidWarehouseReturnNetwork.returnedItemsOf(this.activeNonce);
            deduct(VoidWarehouseReturnNetwork.returnedOf(this.activeNonce));
            announce(returnedItems);
        } else if (status == PrinterReturnResultPayload.RULE_OFF) {
            // 服务端规则没开：别每 tick 撞一次，退避一段时间
            this.rejectedUntilTick = now + REJECT_BACKOFF_TICKS;
        } else if (status == PrinterReturnResultPayload.THROTTLED) {
            this.lastRequestTick = now;
        }
        cancelInFlight();
        return false;
    }

    /**
     * 按「服务端实收 N 件」把账减掉。
     *
     * <p>回执只带总量，所以按这批里各物品的件数<b>顺序扣</b>。近似是允许的：
     * 下一次 {@link #reconcile()} 会把账重新夹到背包实际持有量，
     * 误差最多活一个 tick。</p>
     */
    private void deduct(int returned) {
        if (returned <= 0 || this.inFlight == null || this.inFlight.isEmpty()) {
            return;
        }
        int left = returned;
        for (Map.Entry<Item, Integer> entry : this.inFlight.entrySet()) {
            if (left <= 0) {
                break;
            }
            int fromBatch = entry.getValue();
            int take = Math.min(fromBatch, left);
            Integer pending = this.injected.get(entry.getKey());
            if (pending == null) {
                continue;
            }
            int remaining = pending - take;
            if (remaining <= 0) {
                this.injected.remove(entry.getKey());
            } else {
                this.injected.put(entry.getKey(), remaining);
            }
            // 🔴 这次下降是「我们自己退回去的」，不是「打印机在用」。
            // 丢掉这一 tick 的比对基线，下一 tick reconcile 重建 ⇒ 不会被记成 lastTouched。
            // 代价是万一打印机在同一 tick 真的用掉了它，那一次使用信号丢失 —— 下一次还会再记。
            this.lastHeld.remove(entry.getKey());
            left -= take;
        }
    }

    /**
     * 用「背包里实际还有多少」给账做上限收敛，<b>顺手记下「谁刚被用过」</b>。
     *
     * <p>只往下夹、不往上加：打印机消耗掉的部分会自动掉出账外，
     * 而玩家自己攒的同种物品绝不会被算成「仓库给的」而多退。</p>
     *
     * <p>「件数比上一 tick 少了」就是「正在被用」—— 这是 LRU 唯一的输入。
     * 注意不能拿「账本值下降」当判据：账本只在 {@code held} 低于它时才夹，
     * 玩家手里本来就有同种物品时（{@code held = 仓库的 64 + 自己的 20}）漏掉前面的消耗，
     * 于是活跃物品看起来一直没动过，反而会先被淘汰。所以单独记 {@link #lastHeld}。</p>
     *
     * <p>🔴 <b>「我们自己退回去」造成的件数下降不算被用</b>。返还成功后服务端会把那一格收走，
     * 背包件数当场掉下去 —— 若不排除，刚退走的物品会被记成「刚刚过」= 最热，
     * LRU 排序立刻被污染（每退一批就加热一批，冷的那一端持续被抽走，排序退化成插入顺序）。
     * 两条时序都要挡：<b>背包先同步</b>（回执还在飞）靠 {@link #inFlightReturn}，
     * <b>回执先到</b>靠 {@link #deduct} 里丢掉基线。</p>
     */
    private void reconcile(long now) {
        if (this.injected.isEmpty()) {
            // 账上什么都没有，就没有「该不该退」的问题：两个辅助表一起清，
            // 维持「lastTouched 的键一定是账上的物品」这条不变式（否则旧时间戳会留到下一批）。
            this.lastHeld.clear();
            this.lastTouched.clear();
            return;
        }
        LocalPlayer player = this.client.player;
        if (player == null) {
            this.injected.clear();
            this.lastHeld.clear();
            return;
        }
        Inventory inventory = player.getInventory();
        Iterator<Map.Entry<Item, Integer>> iterator = this.injected.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Item, Integer> entry = iterator.next();
            Item item = entry.getKey();
            int held = countHeld(inventory, item);
            Integer previous = this.lastHeld.put(item, held);
            if (isUsage(previous, held, inFlightReturn(item))) {
                this.lastTouched.put(item, now);
            }
            int pending = Math.min(entry.getValue(), held);
            if (pending <= 0) {
                iterator.remove();
                this.lastHeld.remove(item);
                this.lastTouched.remove(item);
            } else if (pending != entry.getValue()) {
                entry.setValue(pending);
            }
        }
    }

    /**
     * 这种物品是不是「正有一笔返还带着它飞」。
     *
     * <p>飞行期间它的件数下降只可能是我们自己造成的（服务端收走了那一格），
     * 不能当成「打印机在用」—— 否则刚退走的最冷物品会被记成最热，LRU 排序立刻被污染。</p>
     */
    private boolean inFlightReturn(Item item) {
        return this.inFlight != null && this.inFlight.containsKey(item);
    }

    /**
     * 这一 tick 的件数变化算不算「打印机用掉了它」—— LRU 时间戳的<b>唯一</b>写入口判据。
     *
     * <p>抽成纯函数是因为它三个条件全都「写歪也能编译、也能跑」，只在游戏里静默失真：</p>
     * <ul>
     *   <li>{@code previous == null} —— 第一次见到它，没有基线可比。记成使用的话，
     *       每次到货都会把该物品刷成最热；</li>
     *   <li>{@code held < previous} —— 方向写反（变多算使用）会让「刚补完货」的材料
     *       永远最热，冷的那一端全是没补过货的，排序彻底失效；</li>
     *   <li>{@code returning} —— 我们必须排除自己的动作。返还成功后服务端把那一格收走，
     *       件数当场掉下去，不排除就等于「退谁谁变热」，每退一批污染一批。</li>
     * </ul>
     */
    static boolean isUsage(Integer previous, int held, boolean returning) {
        return previous != null && held < previous && !returning;
    }

    /**
     * 挑出这一批要交还给仓库的槽位。
     *
     * @param wanted        最多挑几格
     * @param skipRequested 是否跳过「当前材料请求正在要的物品」
     * @param blocked       额外不许动的物品（打印中的「热集」：刚发到手的还不算余料）
     */
    private List<Integer> chooseSlots(int wanted, boolean skipRequested, Set<Item> blocked) {
        LocalPlayer player = this.client.player;
        if (player == null) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int selected = InventoryUtils.getSelectedSlot(inventory);
        Set<Item> skip = skipRequested ? new HashSet<>(this.requestedItems.get()) : new HashSet<>();
        skip.addAll(blocked);
        // 账本按件数走：一种物品只允许上交「账本完整覆盖」的那些格，用掉一格就减掉一格
        Map<Item, Integer> budget = new HashMap<>(this.injected);
        // 🔴 LRU：最冷的先退。先按「最后被用掉的时刻」给物品排序，再在每一趟里按这个顺序挑槽位。
        List<Item> order = coldFirst(budget.keySet());
        List<Integer> slots = new ArrayList<>();

        // 两趟：先主背包（35→9），再快捷栏（8→0）。快捷栏是打印机取料的落点，能不动就不动。
        int hotbarSize = Math.min(9, size);
        for (int pass = 0; pass < 2 && slots.size() < wanted; pass++) {
            int from = pass == 0 ? size - 1 : hotbarSize - 1;
            int to = pass == 0 ? hotbarSize : 0;
            for (Item item : order) {
                if (slots.size() >= wanted) {
                    break;
                }
                if (skip.contains(item)) {
                    continue;
                }
                // PrinterTakeService delivers the item's default component stack.
                ItemStack sample = new ItemStack(item);
                for (int slot = from; slot >= to && slots.size() < wanted; slot--) {
                    if (slot == selected) {
                        continue;
                    }
                    ItemStack stack = inventory.getItem(slot);
                    if (stack.isEmpty() || !ItemStack.isSameItemSameComponents(stack, sample)) {
                        continue;
                    }
                    Integer left = budget.get(item);
                    if (left == null || left <= 0) {
                        continue;
                    }
                    if (left < stack.getCount()) {
                        // 🔴 这一格装的不全是仓库货（可能下层垫着玩家自己的同种材料）。
                        // 协议只能整格上交，所以宁可跳过，也不能把玩家的存货一起退走。
                        continue;
                    }
                    slots.add(slot);
                    budget.put(item, left - stack.getCount());
                }
            }
        }
        return slots;
    }

    /** 按「最后被用掉的时刻」升序排（越冷越前）。账本里的物品都记过时刻，缺的当最冷。 */
    private List<Item> coldFirst(Collection<Item> items) {
        List<Item> list = new ArrayList<>(items);
        long[] touched = new long[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Long tick = this.lastTouched.get(list.get(i));
            touched[i] = tick == null ? Long.MIN_VALUE : tick;
        }
        int[] order = evictionOrder(touched);
        List<Item> sorted = new ArrayList<>(list.size());
        for (int index : order) {
            sorted.add(list.get(index));
        }
        return sorted;
    }

    /**
     * LRU 淘汰顺序：返回下标，<b>最冷的排最前</b>。
     *
     * <p>抽成纯函数是为了能在单测里把方向钉死 —— 排序方向写反（最热的先淘汰）也能编译、
     * 也能跑，只在游戏里表现成「刚用完的材料立刻被收走」，正是我们要根治的那个症状。
     * 相等时保持传入顺序（{@code Arrays.sort} 对对象数组是稳定排序），
     * 所以同冷的一批仍按账本插入顺序走，行为可复现。</p>
     */
    static int[] evictionOrder(long[] lastTouched) {
        Integer[] indexes = new Integer[lastTouched.length];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = i;
        }
        Arrays.sort(indexes, (a, b) -> Long.compare(lastTouched[a], lastTouched[b]));
        int[] order = new int[indexes.length];
        for (int i = 0; i < indexes.length; i++) {
            order[i] = indexes[i];
        }
        return order;
    }

    /** 这批槽位里各物品的件数，用于回执到手后按实物核账。 */
    private Map<Item, Integer> measure(List<Integer> slots) {
        Map<Item, Integer> batch = new LinkedHashMap<>();
        LocalPlayer player = this.client.player;
        if (player == null) {
            return batch;
        }
        Inventory inventory = player.getInventory();
        for (int slot : slots) {
            if (slot < 0 || slot >= inventory.getContainerSize()) {
                continue;
            }
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            batch.merge(stack.getItem(), stack.getCount(), Integer::sum);
        }
        return batch;
    }

    static int countHeld(Inventory inventory, Item item) {
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int total = 0;
        ItemStack sample = new ItemStack(item);
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static int countFreeSlots(Inventory inventory) {
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int free = 0;
        for (int slot = 0; slot < size; slot++) {
            if (inventory.getItem(slot).isEmpty()) {
                free++;
            }
        }
        return free;
    }

    /**
     * 记一笔「这种材料刚到货，先别动它」。
     *
     * <p>由 {@link VoidWarehouseRestockAdapter} 在确认到货后调用。这是整条链上唯一
     * 知道「这批物品是刚发给打印机的」的地方 —— 只有它能在「请求已完成、还没用完」
     * 那段窗口里保护材料。</p>
     */
    public void markDelivered(Item item) {
        if (item == null) {
            return;
        }
        long now = this.client.level == null ? 0L : this.client.level.getGameTime();
        this.hotUntil.put(item, now + HOT_TICKS);
        // 到货也算「刚碰过」：从没被用过的物品按到货时刻参与 LRU 排序，
        // 不会因为「lastTouched 里没有它」被当成最冷的一批立刻退走。
        this.lastTouched.put(item, now);
    }

    /** 还没过期的热物品（顺手把过期的清掉）。 */
    private Set<Item> hotItems(long now) {
        sweepHot(now);
        return this.hotUntil.isEmpty() ? Set.of() : new HashSet<>(this.hotUntil.keySet());
    }

    private void sweepHot(long now) {
        if (this.hotUntil.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Item, Long>> iterator = this.hotUntil.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() <= now) {
                iterator.remove();
            }
        }
    }

    /**
     * 打印途中要不要动手退（判定的唯一实现，便于单测把边界钉死）。
     *
     * <p>规则只有 <b>两个</b>，都是「背包现状」，没有任何隐藏状态：</p>
     * <ul>
     *   <li>{@code warehouseSlots > 0} —— 背包里确实装着仓库货。背包被玩家自己的材料
     *       占满时，退什么都轮不到这个组件管；</li>
     *   <li>{@code freeSlots <= FREE_SLOTS_TRIGGER} —— 快放不下下一种材料了。</li>
     * </ul>
     *
     * <p>🔴 <b>「方块种类数」不参与判定</b>。它曾经是主判据（≥30 就退），但那个阈值
     * 预设了「投影材料种类不多」，对 HUB（294 种）这种投影恒为真 —— 于是清理变成了常开，
     * 打印机永远攒不够材料。判据必须回到「还塞不塞得下」这个唯一有物理意义的问题上。</p>
     */
    static boolean shouldReturnWhilePrinting(int freeSlots, int warehouseSlots) {
        if (warehouseSlots <= 0) {
            return false;
        }
        return freeSlots <= FREE_SLOTS_TRIGGER;
    }

    /**
     * 这一批最多退几格：把空槽补回 {@link #FREE_SLOTS_TARGET} 个就够。
     *
     * <p>至少 1 格 —— 调用方靠「空列表」来放弃，不靠 0。</p>
     */
    static int slotsWanted(int freeSlots) {
        return Math.max(1, FREE_SLOTS_TARGET - freeSlots);
    }

    /** 背包里有多少格装的是「仓库给的」东西（账本上还欠着的物品）。 */
    private int countWarehouseSlots(Inventory inventory) {
        int size = Math.min(MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int count = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.isEmpty()) {
                continue;
            }
            Integer pending = this.injected.get(stack.getItem());
            if (pending != null && pending > 0
                    && ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))) {
                count++;
            }
        }
        return count;
    }
}
//#endif
