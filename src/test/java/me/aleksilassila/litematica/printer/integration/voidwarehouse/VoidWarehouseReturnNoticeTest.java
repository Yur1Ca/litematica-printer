package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死两件事：<b>回执明细的拼装</b>（三份平行清单 → 一条一条）与<b>那行提示的形状</b>。
 *
 * <p>文本断言一律用<b>解析不出来的 id</b>（{@code ???}）当物品名：这样它会走「报 id 原文」那条
 * 兜底，不必碰 {@code BuiltInRegistries} —— JUnit 里没有注册表，真去查译名会连带把测试环境
 * 搞成「必须有客户端」。名字本身的本地化不是这套测试要管的事。</p>
 */
class VoidWarehouseReturnNoticeTest {

    // ---- 三份清单对齐 ----

    @Test
    void zipsThreeListsIntoEntries() {
        List<VoidWarehouseReturnNetwork.Returned> out = VoidWarehouseReturnNetwork.zip(
                List.of("minecraft:dirt", "minecraft:cobblestone"),
                List.of(64, 32),
                List.of(128L, 256L));
        assertEquals(2, out.size());
        assertEquals("minecraft:dirt", out.get(0).itemId());
        assertEquals(64, out.get(0).count());
        assertEquals(128L, out.get(0).remaining());
        assertEquals(32, out.get(1).count());
    }

    @Test
    void zipTakesTheShortestList() {
        // 长度不一致（坏包 / 两端版本不齐）：只丢多出来的那几条，绝不抛异常
        List<VoidWarehouseReturnNetwork.Returned> out = VoidWarehouseReturnNetwork.zip(
                List.of("a", "b", "c"),
                List.of(1, 2),
                List.of(9L, 8L, 7L));
        assertEquals(2, out.size());
        assertEquals("b", out.get(1).itemId());
    }

    @Test
    void zipToleratesEmptyAndNull() {
        assertTrue(VoidWarehouseReturnNetwork.zip(List.of(), List.of(), List.of()).isEmpty());
        assertTrue(VoidWarehouseReturnNetwork.zip(null, null, null).isEmpty());
    }

    // ---- 提示的版式 ----

    @Test
    void oneKindReportsCountAndRemaining() {
        String line = VoidWarehouseReturnNotice.compose(List.of(
                new VoidWarehouseReturnNetwork.Returned("???", 64, 128L))).getString();
        assertEquals("[虚空仓库] 余料已退回 64 个 ???（仓库共 128 个）", line);
    }

    @Test
    void severalKindsAreJoinedWithOneSeparator() {
        String line = VoidWarehouseReturnNotice.compose(List.of(
                new VoidWarehouseReturnNetwork.Returned("???", 64, 128L),
                new VoidWarehouseReturnNetwork.Returned("!!!", 32, 256L))).getString();
        assertEquals("[虚空仓库] 余料已退回 64 个 ???（仓库共 128 个）、32 个 !!!（仓库共 256 个）", line);
    }

    @Test
    void numbersUseThousandsSeparators() {
        // 仓库动辄上亿，不分隔符读不出位数（与服务端 VoidWarehouseText#count 同一口径）
        String line = VoidWarehouseReturnNotice.compose(List.of(
                new VoidWarehouseReturnNetwork.Returned("???", 1728, 1234567L))).getString();
        assertTrue(line.contains("1,728"), line);
        assertTrue(line.contains("1,234,567"), line);
    }

    @Test
    void tooManyKindsFoldToCountAndTotal() {
        List<VoidWarehouseReturnNetwork.Returned> many = List.of(
                new VoidWarehouseReturnNetwork.Returned("a", 1, 1L),
                new VoidWarehouseReturnNetwork.Returned("b", 2, 2L),
                new VoidWarehouseReturnNetwork.Returned("c", 3, 3L),
                new VoidWarehouseReturnNetwork.Returned("d", 4, 4L),
                new VoidWarehouseReturnNetwork.Returned("e", 5, 5L));
        String line = VoidWarehouseReturnNotice.compose(many).getString();
        assertEquals("[虚空仓库] 余料已退回 5 种共 15 个", line);
    }

    @Test
    void unknownRemainingDropsOnlyTheParenthesis() {
        // -1 = 服务端没读到仓库剩余量：句子结构保持不变，只是不带那个括号
        String line = VoidWarehouseReturnNotice.compose(List.of(
                new VoidWarehouseReturnNetwork.Returned("???", 64, -1L))).getString();
        assertEquals("[虚空仓库] 余料已退回 64 个 ???", line);
        assertFalse(line.contains("仓库共"));
    }
}
//#endif
