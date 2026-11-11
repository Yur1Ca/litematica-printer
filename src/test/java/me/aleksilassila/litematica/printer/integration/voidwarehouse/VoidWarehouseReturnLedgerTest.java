package me.aleksilassila.litematica.printer.integration.voidwarehouse;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest.Source;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「哪些来源的到货该记进余料返还账本」。
 *
 * <h2>这套断言防的是什么</h2>
 * <p>回归 bug：玩家中键 pick block 从仓库取出方块，取到的量被 {@code recordDelivery}
 * 无条件记成一笔「打印耗材」；玩家一关掉容器界面，
 * {@code VoidWarehouseReturnController#tick()} 恢复发货，这一笔就被当成余料退回仓库 ——
 * 手上一空，看起来像「刚拿到的东西凭空没了」。</p>
 *
 * <p>根因不是算错了数，而是<b>分母没分</b>：同一个补货适配器对「打印要料」和
 * 「玩家自己要一件东西」用了同一本账。另外两个适配器
 * （{@code TakeItOutAdapter} / {@code ChestTrackerAdapter}）早就按 {@code source} 分流，
 * 只有这里漏了。</p>
 *
 * <h2>为什么值得单独一个测试类</h2>
 * <p>这条判定的真实验证成本很高：要到真服务端里中键取物、再开一次仓库界面、
 * 还要让背包空槽紧张到能触发返还档位。而它本身只是一次枚举比较 ——
 * 抽成 {@link VoidWarehouseRestockAdapter#booksAsLeftover} 就能在这里逐个边界咬死。</p>
 */
class VoidWarehouseReturnLedgerTest {

    /**
     * pick block 是「玩家自己要用的东西」，从来不是余料。
     *
     * <p>这条一红，就是「中键取物 → 关界面 → 东西被收回仓库」这个 bug 回来了。</p>
     */
    @Test
    void pickBlockIsNeverBooked() {
        assertFalse(
                VoidWarehouseRestockAdapter.booksAsLeftover(Source.PICK_BLOCK),
                "pick block 取到的物品是玩家自己要用的，不能记进余料返还账本"
        );
        assertFalse(VoidWarehouseRestockAdapter.booksAsLeftover(Source.TAKE_IT_OUT_PICK),
                "TIO 自动取物也属于玩家取物，不能混进仓库余料返还账本");
    }

    /**
     * 除了玩家取物，其余来源都是打印耗材，一律照旧记账。
     *
     * <p>反方向兜底：修 bug 时把判定写成「只有 PRINT 才记账」也会让本方法翻红 ——
     * FILL / FLUID / MINE / COVER / BEDROCK 都是打印机的正常要料，漏了它们
     * 就等于余料永远退不回去，背包照样被零头塞满。</p>
     */
    @Test
    void everyPrintingSourceIsBooked() {
        for (Source source : Source.values()) {
            if (source == Source.PICK_BLOCK || source == Source.TAKE_IT_OUT_PICK) {
                continue;
            }
            assertTrue(
                    VoidWarehouseRestockAdapter.booksAsLeftover(source),
                    source + " 是打印耗材来源，必须记进余料返还账本"
            );
        }
    }

    /**
     * 新增来源必须显式归类，不许默认落在任何一边。
     *
     * <p>把「记账集合」写成字面量：将来有人往 {@code MaterialRequest.Source} 里加一个
     * {@code Source}（比如「快捷潜影盒取货」），本方法会立刻翻红，逼着新来源回答
     * 「它算不算打印耗材」。默认值（当前实现是「除 pick block 外都记账」）恰好是
     * 安全的那一边，但「恰好安全」不等于「想过」—— 这个 bug 就是「恰好」出来的。</p>
     */
    @Test
    void newSourcesMustBeClassifiedExplicitly() {
        Set<Source> booked = EnumSet.noneOf(Source.class);
        for (Source source : Source.values()) {
            if (VoidWarehouseRestockAdapter.booksAsLeftover(source)) {
                booked.add(source);
            }
        }
        assertEquals(
                EnumSet.of(
                        Source.PRINT,
                        Source.FILL,
                        Source.FLUID,
                        Source.MINE,
                        Source.COVER,
                        Source.BEDROCK,
                        Source.CHEST_TRACKER_SCREEN,
                        Source.OTHER
                ),
                booked,
                "记账来源变了：新来源请显式判断它算不算打印耗材，并同步更新本清单"
        );
    }
}
