package me.aleksilassila.litematica.printer.utils;

import net.minecraft.network.chat.Component;
//#if MC >= 12005
import net.minecraft.core.component.DataComponents;
//#endif
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * 识别「虚空仓库」触发物：<b>改名成「虚空仓库」的工作台</b>。
 *
 * <h2>为什么要专门认它</h2>
 * <p>服务端（carpet-Neko-Addition 的 {@code VoidWarehouseTrigger}）把工作台的三个右键入口
 * 全接管了：手里拿着这个改名工作台右键，不是放置方块，而是<b>打开仓库界面</b>。
 * 于是打印机一旦把它当建材换到手上右键，就会变成「右键 → 弹界面 → 方块没放下 →
 * 下一 tick 再右键」的死循环 —— 玩家看到的就是界面被反复弹开、按 Esc 也退不掉。</p>
 *
 * <p>所以这种堆叠在打印机眼里<b>不是建材</b>：既不能算「身上有这种材料」，
 * 也不能被换到手上。真缺工作台就走补货链路去要一个干净的（服务端仓库里那份没有名字）。</p>
 *
 * <h2>判定口径必须与服务端逐字一致</h2>
 * <p>两边不一致就会错位：服务端认为它是触发物、客户端认为不是（照旧循环），
 * 或者反过来（明明能放却永远不放）。服务端 {@code VoidWarehouseTrigger#isVoidWarehouse} 的口径是：</p>
 * <ul>
 *   <li>物品必须是原版工作台（{@link Items#CRAFTING_TABLE}）；</li>
 *   <li>中文名 {@code "虚空仓库"}：<b>精确匹配</b>，区分大小写、不 trim（多一个空格就不是）；</li>
 *   <li>英文名 {@code "Void Warehouse"}：<b>忽略大小写、容忍首尾空白</b>（英文玩家习惯随手打）。</li>
 * </ul>
 * <p>⚠️ 这两个字面量是服务端的 {@code VoidWarehouseProvider.TITLE_TEXT} /
 * {@code VoidWarehouseText.TITLE_TEXT_EN}，改服务端时要同步改这里。</p>
 */
public final class VoidWarehouseTriggerItem {

    /** 中文触发名，与服务端 {@code VoidWarehouseProvider.TITLE_TEXT} 一致，精确匹配。 */
    public static final String NAME_ZH = "虚空仓库";

    /** 英文触发名，与服务端 {@code VoidWarehouseText.TITLE_TEXT_EN} 一致，忽略大小写 + trim。 */
    public static final String NAME_EN = "Void Warehouse";

    private VoidWarehouseTriggerItem() {
    }

    /**
     * 这一堆是不是「虚空仓库」触发物。
     *
     * @param stack 待判定的堆叠，允许 {@code null} / 空
     */
    public static boolean isTrigger(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return false;
        }
        if (stack.getItem() != Items.CRAFTING_TABLE) {
            return false;
        }
        // 取名 API 分两条轨：1.20.5 起物品名变成组件（DataComponents.CUSTOM_NAME），
        // 更早只有 hasCustomHoverName() + getHoverName()（后者在没有自定义名时返回物品默认名）。
        // 不用 getCustomName()：那个方法名只有很新的版本才有。
        //#if MC >= 12005
        Component customName = stack.get(DataComponents.CUSTOM_NAME);
        //#else
        //$$ Component customName = stack.hasCustomHoverName() ? stack.getHoverName() : null;
        //#endif
        if (customName == null) {
            return false;
        }
        String name = customName.getString();
        // 中文：精确，不做 trim —— 与服端同一口径
        if (NAME_ZH.equals(name)) {
            return true;
        }
        // 英文别名：忽略大小写、容忍首尾空白
        return NAME_EN.equalsIgnoreCase(name.trim());
    }

    /**
     * 这堆东西能不能当建材用（能不能被换到手上右键）。
     *
     * <p>语义上等价于 {@code !isTrigger(stack)}，单独给一个正向名字，
     * 是因为调用点大多是在筛「可用材料」，写正向判断读起来不容易出错。</p>
     */
    public static boolean isUsableAsMaterial(ItemStack stack) {
        return !isTrigger(stack);
    }
}
