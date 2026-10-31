package me.aleksilassila.litematica.printer.integration.voidwarehouse;

//#if MC >= 12111
import me.aleksilassila.litematica.printer.utils.minecraft.MessageUtils;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

/**
 * 余料退回仓库之后，在聊天栏说一句「退了什么、仓库还剩多少」。
 *
 * <h2>为什么值得说</h2>
 * <p>余料返还是<b>背着玩家</b>发生的：背包里的东西悄悄少了几格、仓库里悄悄多了几组，
 * 中间没有任何可见反馈。玩家要判断「这一批退得对不对」只能记住自己带了什么再去开仓库对账 ——
 * 退错了（比如把正在用的那半组也退了）当场看不出来，等到打印机缺料才发现。
 * 所以每成功退一批就给一行字，把<b>这一批退了什么、各多少</b>和<b>仓库里现在各有多少</b>
 * 放在同一行里对照。</p>
 *
 * <h2>数字全部来自服务端</h2>
 * <p>两个数都不是客户端算的：</p>
 * <ul>
 *   <li>{@code count} —— 服务端真正收进仓库的件数（{@code PrinterReturnService} 在
 *       {@code insert} 之前取的堆叠数，拒收的格子不计）；</li>
 *   <li>{@code remaining} —— 这一批<b>全部入库做完之后</b>服务端现读的仓库总量
 *       （口径 = 物品 + 数据组件，与入库归并同一把尺子）。</li>
 * </ul>
 * <p>客户端不去猜剩余量：它看不到仓库，任何「背包件数加减」的推算都会在玩家手动存取、
 * 别处漏斗进料之后立刻错掉，而错报一个「还剩多少」比不报更糟。</p>
 *
 * <h2>版式</h2>
 * <p>与服务端的补货/收料提示同一套：{@code [虚空仓库] } 亮金前缀 + 浅灰正文 + 亮绿数字。</p>
 * <ul>
 *   <li>一种：{@code 余料已退回 64 个 泥土（仓库共 128 个）}</li>
 *   <li>多种：{@code 余料已退回 64 个 泥土（仓库共 128 个）、32 个 圆石（仓库共 256 个）}</li>
 *   <li>超过 4 种：{@code 余料已退回 5 种共 320 个}（这时不逐种报库存，
 *       否则一条提示能顶满半个聊天框）</li>
 * </ul>
 * <p>剩余量取不到（服务端读仓库出异常，{@code -1}）时只丢那个括号，句子结构不变。</p>
 */
public final class VoidWarehouseReturnNotice {

    /** 聊天提示前缀：与服务端所有虚空仓库提示一致，一眼认出「这是仓库在说话」 */
    private static final String PREFIX = "[虚空仓库] ";
    private static final int PREFIX_RGB = 0xFFD700;
    private static final int BODY_RGB = 0xAAAAAA;
    private static final int KEY_RGB = 0x55FF55;

    /** 一条提示最多列几种物品，再多就折成「N 种共 M 个」 */
    static final int MAX_LISTED_KINDS = 4;

    private VoidWarehouseReturnNotice() {
    }

    /**
     * 发一条「余料已退回」的提示。
     *
     * @param returned 服务端回执里的逐物品明细；空清单 = 什么都不发（不该出现，但别打成空行）
     */
    public static void announce(List<VoidWarehouseReturnNetwork.Returned> returned) {
        if (returned == null || returned.isEmpty()) {
            return;
        }
        MessageUtils.addMessage(compose(returned));
    }

    /** 拼那一行（拆出来是为了能直接断言文本，不必真去拦聊天通道）。 */
    static Component compose(List<VoidWarehouseReturnNetwork.Returned> returned) {
        MutableComponent body = body("余料已退回 ");
        if (returned.size() > MAX_LISTED_KINDS) {
            long total = 0L;
            for (VoidWarehouseReturnNetwork.Returned entry : returned) {
                total += entry.count();
            }
            body.append(key(count(returned.size())))
                    .append(body(" 种共 "))
                    .append(key(count(total)))
                    .append(body(" 个"));
            return prefix(body);
        }
        for (int i = 0; i < returned.size(); i++) {
            VoidWarehouseReturnNetwork.Returned entry = returned.get(i);
            if (i > 0) {
                body.append(body("、"));
            }
            body.append(key(count(entry.count())))
                    .append(body(" 个 "))
                    .append(name(entry.itemId()));
            if (entry.remaining() >= 0L) {
                body.append(body("（仓库共 "))
                        .append(key(count(entry.remaining())))
                        .append(body(" 个）"));
            }
        }
        return prefix(body);
    }

    /** 物品名：id 还原成物品再取译名组件，本地化交给客户端自己的语言表 */
    private static Component name(String itemId) {
        Item item = resolve(itemId);
        if (item == null) {
            // 认不出来（对面比本端新、注册了本端没有的物品）就照原样报 id，总比显示「空气」强
            return body(itemId);
        }
        return body(new ItemStack(item).getHoverName());
    }

    private static Item resolve(String itemId) {
        if (itemId == null || itemId.isEmpty()) {
            return null;
        }
        Identifier id = Identifier.tryParse(itemId);
        if (id == null) {
            return null;
        }
        try {
            Item item = BuiltInRegistries.ITEM.getValue(id);
            // DefaultedRegistry 认不出来时给的是 AIR —— 那个名字是「空气」，不如报 id
            return item == null || item == net.minecraft.world.item.Items.AIR ? null : item;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static MutableComponent prefix(MutableComponent body) {
        return Component.literal(PREFIX)
                .withStyle(Style.EMPTY.withColor(PREFIX_RGB).withItalic(false).withBold(false))
                .append(body);
    }

    private static MutableComponent body(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(BODY_RGB).withItalic(false));
    }

    /** 已经是组件的内容（物品译名）：只换颜色，保留可翻译结构 */
    private static MutableComponent body(Component text) {
        return text.copy().setStyle(Style.EMPTY.withColor(BODY_RGB).withItalic(false));
    }

    private static MutableComponent key(String text) {
        return Component.literal(text).withStyle(Style.EMPTY.withColor(KEY_RGB).withItalic(false));
    }

    private static String count(long value) {
        return String.format(Locale.ROOT, "%,d", value);
    }
}
//#endif
