package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钉死「打印途中要不要退余料」的边界。
 *
 * <p>这套判定全是整数运算，所以把它抽成静态方法、在这里逐个边界咬死 ——
 * 免得下次又靠「跑一遍游戏看看」来判断改对了没有。</p>
 *
 * <h2>这套断言防的是什么</h2>
 * <p>上一版的判据里有一条「背包里的方块<b>种类数</b> ≥ 30 就退」，预设是
 * 「36 格里放 30 种就意味着快满了」。这个预设只在<b>投影材料种类很少</b>时成立。
 * 用 HUB 实测数据算一下：投影要 <b>294 种</b>方块（71,985 块），
 * 所以 36 格永远装着 30 种以上 ⇒ 那条判定<b>在整场建造里恒为真</b> ⇒ 控制器变成
 * 「每 4 tick 清一次背包」，而它清掉的正是打印机马上要用的材料 ⇒ 打印停滞。</p>
 *
 * <p>所以这里的首要一条就是 {@link #manyKindsAloneNeverTriggers()}：
 * <b>「种类多」不再构成任何触发理由</b>，判据只剩「还塞不塞得下」。</p>
 */
class VoidWarehouseReturnControllerTest {

    // ---- 回归钉：种类数与触发无关 ----

    /**
     * HUB 那种「要几百种材料」的投影，早期满背包是常态，绝不能因此就退。
     *
     * <p>新签名里已经没有 kinds 这个参数了 —— 这条断言的真正含义是：
     * 只要空槽还够（≥ 4，超过触发档），无论背包里塞着多少种材料，都<b>不许动手</b>。
     * 上一版会在 30 种时就开始清，正好把打印抽干。</p>
     */
    @Test
    void manyKindsAloneNeverTriggers() {
        // freeSlots = 4..36 全部不动手。HUB 只用到 35 格时也落在这一段里。
        for (int free = 4; free <= 36; free++) {
            assertFalse(
                    VoidWarehouseReturnController.shouldReturnWhilePrinting(free, 35),
                    "free=" + free + "（294 种投影的常态）不该触发返还"
            );
        }
    }

    // ---- 仓库货是前提：自己背包满了不归本组件管 ----

    @Test
    void doesNothingWithoutWarehouseItems() {
        // 一格仓库货都没有：哪怕背包完全占满也一格不动
        // （背包被玩家自己的材料占满时，退什么都轮不到这个组件管）
        for (int free = 0; free <= 36; free++) {
            assertFalse(
                    VoidWarehouseReturnController.shouldReturnWhilePrinting(free, 0),
                    "没有仓库货时 free=" + free + " 也不该触发"
            );
        }
    }

    // ---- 真正的触发条件：空槽快见底 ----

    @Test
    void triggersOnlyWhenNoFreeSlotLeft() {
        assertTrue(VoidWarehouseReturnController.shouldReturnWhilePrinting(0, 1));
        assertTrue(VoidWarehouseReturnController.shouldReturnWhilePrinting(3, 1));
        // 还剩四格：够放下一批货，不许动
        assertFalse(VoidWarehouseReturnController.shouldReturnWhilePrinting(4, 1));
    }

    // ---- 退多少：把空槽补到目标值，至少一格 ----

    @Test
    void returnsEnoughToReachTarget() {
        // 一个空槽都不剩 → 退 8 格（FREE_SLOTS_TARGET）
        assertEquals(8, VoidWarehouseReturnController.slotsWanted(0));
        // 剩三格（触发档）→ 退 5 格
        assertEquals(5, VoidWarehouseReturnController.slotsWanted(3));
        // 已经达标 → 仍然至少退一格（调用方靠「空列表」来放弃，不靠 0）
        assertEquals(1, VoidWarehouseReturnController.slotsWanted(8));
        assertEquals(1, VoidWarehouseReturnController.slotsWanted(35));
    }

    /**
     * 退完一批之后不能立刻又满足触发条件。
     *
     * <p>这是「退 → 触发 → 再退」空转的源头，所以按格子数守恒逐点验一遍：
     * 退掉 N 格 ⇒ 空槽 +N，结果必须落在判定之外。这条性质由
     * {@code FREE_SLOTS_TARGET > FREE_SLOTS_TRIGGER} 保证，是代码里的硬约束 ——
     * 谁把 target 调到 trigger 以下，这个循环就会当场咬出来。</p>
     */
    @Test
    void oneBatchAlwaysLeavesRoomForTheNext() {
        for (int free = 0; free <= 36; free++) {
            if (!VoidWarehouseReturnController.shouldReturnWhilePrinting(free, 35)) {
                continue;
            }
            int wanted = VoidWarehouseReturnController.slotsWanted(free);
            int freeAfter = free + wanted;
            assertFalse(
                    VoidWarehouseReturnController.shouldReturnWhilePrinting(freeAfter, 35 - wanted),
                    "free=" + free + " wanted=" + wanted + " -> free=" + freeAfter + " 仍在触发"
            );
        }
    }

    /**
     * 只有「空槽 ≤ 1」这一档会动手 —— 把 36 个可能的空槽数全扫一遍。
     *
     * <p>这条是上面几条的合并表述，写死成一张真值表，改常量时最先红。</p>
     */
    @Test
    void triggerTruthTableOverAllFreeSlotCounts() {
        for (int free = 0; free <= 36; free++) {
            boolean expected = free <= 3;
            assertEquals(
                    expected,
                    VoidWarehouseReturnController.shouldReturnWhilePrinting(free, 12),
                    "free=" + free
            );
        }
    }

    /**
     * 触发点必须留出真正的余量，不能等到「一格都没有」才动手。
     *
     * <p>实测（2026-10-31 21:11，45 秒）：稳态卡在 0~4 个空槽，取了 68 次、退了 33 次 ——
     * 平均每取一次退一次，因为手上最多只放得下 4 种材料，每换一种就得先腾一格。
     * 判据取 3 / 目标 8 之后，打印机一次能连着拿好几种再干活。</p>
     */
    @Test
    void triggerKeepsRealHeadroomInsteadOfWaitingForZero() {
        // 3 个空槽还够周转，不动手
        assertFalse(VoidWarehouseReturnController.shouldReturnWhilePrinting(4, 12));
        // 降到 3 就动手（不是等到 1 或 0）
        assertTrue(VoidWarehouseReturnController.shouldReturnWhilePrinting(3, 12));
        assertTrue(VoidWarehouseReturnController.shouldReturnWhilePrinting(0, 12));
        // 背包里没有仓库货时一律不动手，哪怕一格不剩
        assertFalse(VoidWarehouseReturnController.shouldReturnWhilePrinting(0, 0));
    }

    /**
     * 只有断线才允许丢账本 —— 这条钉的是死锁的入口。
     *
     * <p>2026-10-31 19:48 实测：玩家关掉打印总开关（{@code work_switch_off}），
     * {@code clear()} 把账本清空，而那一刻背包只剩 0 个空槽。于是
     * 「返还认不出仓库货 ⇒ 退不了」＋「背包满 ⇒ 服务端 INVENTORY_FULL ⇒ 拿不到」
     * 互相锁死，账本永远重建不起来，打印无声无息停住，只能手动扔东西才解开。</p>
     *
     * <p>所以这五条<b>必须</b>保留账本 —— 它们都不会换背包：</p>
     * <ul>
     *   <li>{@code work_switch_off} / {@code work_switch_disabled} —— 关打印开关；</li>
     *   <li>{@code execution_scope_changed} —— 改配置项；</li>
     *   <li>{@code client_scope_changed} —— 进世界时 level 引用换了个对象；</li>
     *   <li>{@code local_player_init} —— 重生/换维度重建 {@code LocalPlayer}，背包保留。</li>
     * </ul>
     */
    @Test
    void ledgerSurvivesEveryEpochChangeExceptDisconnect() {
        assertFalse(VoidWarehouseReturnController.dropsLedger("work_switch_off"));
        assertFalse(VoidWarehouseReturnController.dropsLedger("work_switch_disabled"));
        assertFalse(VoidWarehouseReturnController.dropsLedger("execution_scope_changed"));
        assertFalse(VoidWarehouseReturnController.dropsLedger("client_scope_changed"));
        // 重生/换维度会重建 LocalPlayer，但背包是保留的 —— 清了只会白丢账本
        assertFalse(VoidWarehouseReturnController.dropsLedger("local_player_init"));
    }

    @Test
    void onlyDisconnectDropsTheLedger() {
        assertTrue(VoidWarehouseReturnController.dropsLedger("disconnect"));
        // 拿不到 reason 时保守：宁可丢账本（少退一点），也不能把玩家的物品当仓库货退走
        assertTrue(VoidWarehouseReturnController.dropsLedger(null));
    }

    // ---- LRU：退哪几格 ----

    /**
     * 最冷的排最前。
     *
     * <p>方向写反（最热先淘汰）能编译、能跑，只在游戏里表现成「刚用完的材料立刻被收走」——
     * 正是 19:43–19:48 那轮实测的症状（取 17,872 / 退 14,102，净消耗只占两成）。
     * 所以这里把顺序逐个咬死，而不是只断言「长度对」。</p>
     */
    @Test
    void evictionOrderPutsColdestFirst() {
        assertArrayEquals(new int[]{1, 2, 0},
                VoidWarehouseReturnController.evictionOrder(new long[]{5L, 1L, 3L}));
        assertArrayEquals(new int[]{2, 0, 1},
                VoidWarehouseReturnController.evictionOrder(new long[]{100L, 200L, 50L}));
    }

    /** 从没被碰过的物品（记 {@code Long.MIN_VALUE}）必须最先被淘汰。 */
    @Test
    void neverTouchedGoesFirst() {
        assertArrayEquals(new int[]{0, 2, 1},
                VoidWarehouseReturnController.evictionOrder(
                        new long[]{Long.MIN_VALUE, 3L, 2L}));
    }

    /** 同冷时保持传入顺序（稳定排序）—— 行为必须可复现，否则同一状态两次挑的格子不一样。 */
    @Test
    void tiesKeepInputOrder() {
        assertArrayEquals(new int[]{2, 0, 1},
                VoidWarehouseReturnController.evictionOrder(new long[]{4L, 4L, 1L}));
        assertArrayEquals(new int[]{0, 1, 2},
                VoidWarehouseReturnController.evictionOrder(new long[]{9L, 9L, 9L}));
    }

    @Test
    void evictionOrderHandlesDegenerateInput() {
        assertEquals(0, VoidWarehouseReturnController.evictionOrder(new long[0]).length);
        assertArrayEquals(new int[]{0},
                VoidWarehouseReturnController.evictionOrder(new long[]{42L}));
    }

    /**
     * 什么才算「打印机用掉了它」—— LRU 时间戳的唯一写入口。
     *
     * <p>三个条件各自写歪都能编译能跑，只在游戏里静默失真，所以逐条咬死。</p>
     */
    @Test
    void onlyADecreaseCountsAsUsage() {
        // 正常消耗：64 → 60
        assertTrue(VoidWarehouseReturnController.isUsage(64, 60, false));
        // 没动
        assertFalse(VoidWarehouseReturnController.isUsage(64, 64, false));
        // 变多（刚补完货）绝不是「被用」
        assertFalse(VoidWarehouseReturnController.isUsage(60, 64, false));
        // 第一次见到：没有基线，不许当成使用
        assertFalse(VoidWarehouseReturnController.isUsage(null, 10, false));
        assertFalse(VoidWarehouseReturnController.isUsage(null, 0, false));
    }

    /**
     * 🔴 返还造成的下降不算「被用」。
     *
     * <p>返还成功后服务端把那一格收走，件数当场掉下去。若不排除，刚被淘汰的<b>最冷</b>
     * 物品会被记成「刚刚过」= 最热，于是每次都退掉次冷的、把最冷的留下来 ——
     * LRU 语义直接反了，而且每退一批污染一批，最终排序退化成插入顺序。</p>
     */
    @Test
    void selfInflictedDropNeverCountsAsUsage() {
        assertFalse(VoidWarehouseReturnController.isUsage(64, 0, true));
        assertFalse(VoidWarehouseReturnController.isUsage(128, 66, true));
        // 返还飞行期间即便真的被用掉，也宁可不记 —— 下一次使用还会再记一次，代价是一个 tick
        assertFalse(VoidWarehouseReturnController.isUsage(64, 60, true));
    }
}
//#endif
