package me.aleksilassila.litematica.printer.utils;

import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import net.minecraft.SharedConstants;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 钉死「虚空仓库触发物」的识别口径。
 *
 * <p>这套判定必须与服务端 {@code VoidWarehouseTrigger#isVoidWarehouse} 逐字一致 ——
 * 差一点点就会出现两种事故：</p>
 * <ul>
 *   <li>客户端不认（漏判）⇒ 打印机拿它右键，服务端弹仓库界面，方块永远放不下，
 *       每 tick 重试一次（就是玩家报的那个循环）；</li>
 *   <li>客户端多认（误判）⇒ 明明能放的改名工作台永远放不下去。</li>
 * </ul>
 *
 * <p>最容易搞错的是两种名字的<b>口径不同</b>：中文精确（不 trim、区分大小写），
 * 英文忽略大小写且容忍首尾空白。</p>
 */
class VoidWarehouseTriggerItemTest {

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        // 26.2+ 的物品组件不在 bootStrap 里绑定，不手动 build 一次连 new ItemStack 都会抛
        // 「Components not bound yet」。写法照 CoverMaterialFilterTest。
        //#if MC >= 260200
        BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
                //#if MC >= 260300
                //$$ .build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup())
                //#else
                .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
                //#endif
                .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        //#endif
    }

    private static ItemStack named(net.minecraft.world.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(name));
        return stack;
    }

    // ---- 正例：两个名字都算触发物 ----

    @Test
    void chineseNameIsTrigger() {
        assertTrue(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "虚空仓库")));
    }

    @Test
    void englishAliasIgnoresCaseAndOuterSpaces() {
        assertTrue(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "Void Warehouse")));
        assertTrue(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "void warehouse")));
        assertTrue(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "VOID WAREHOUSE")));
        assertTrue(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "  Void Warehouse  ")));
    }

    // ---- 反例：中文名是精确匹配，多一个空格都不算 ----

    @Test
    void chineseNameIsExact() {
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "虚空仓库 ")));
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, " 虚空仓库")));
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "虚空仓库A")));
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.CRAFTING_TABLE, "虚空 仓库")));
    }

    // ---- 反例：只看工作台这一种物品 ----

    @Test
    void otherItemsAreNeverTriggers() {
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.STONE, "虚空仓库")));
        assertFalse(VoidWarehouseTriggerItem.isTrigger(named(Items.BARREL, "Void Warehouse")));
    }

    @Test
    void unnamedCraftingTableIsPlainMaterial() {
        assertFalse(VoidWarehouseTriggerItem.isTrigger(new ItemStack(Items.CRAFTING_TABLE)));
    }

    @Test
    void nullAndEmptyAreSafe() {
        assertFalse(VoidWarehouseTriggerItem.isTrigger(null));
        assertFalse(VoidWarehouseTriggerItem.isTrigger(ItemStack.EMPTY));
    }

    // ---- 正向名字与反向判定必须互补 ----

    @Test
    void usableAsMaterialIsTheComplement() {
        ItemStack trigger = named(Items.CRAFTING_TABLE, "虚空仓库");
        ItemStack plain = new ItemStack(Items.CRAFTING_TABLE);
        assertFalse(VoidWarehouseTriggerItem.isUsableAsMaterial(trigger));
        assertTrue(VoidWarehouseTriggerItem.isUsableAsMaterial(plain));
        assertTrue(VoidWarehouseTriggerItem.isUsableAsMaterial(ItemStack.EMPTY));
        assertTrue(VoidWarehouseTriggerItem.isUsableAsMaterial(null));
    }

    // ---- 补货闸门：所有来源共用 acceptsStack，「算不算有材料」只能在这里统一判 ----

    @Test
    void materialRequestRejectsTriggerAsMaterial() {
        MaterialRequest request = MaterialRequest.single(
                1L, Items.CRAFTING_TABLE, 1, MaterialRequest.Source.PRINT);
        assertFalse(request.acceptsStack(named(Items.CRAFTING_TABLE, "虚空仓库")),
                "改名工作台不能算「已有工作台」，否则取货链路永远补不出一个能放的");
        assertTrue(request.acceptsStack(new ItemStack(Items.CRAFTING_TABLE)));
        assertTrue(request.acceptsStack(named(Items.CRAFTING_TABLE, "别的名字")),
                "只有「虚空仓库」这个名字才是触发物，普通改名的工作台照旧能放");
    }

    @Test
    void onlyExplicitScreenRetrievalCanRequestTheTrigger() {
        ItemStack trigger = named(Items.CRAFTING_TABLE, "虚空仓库");
        for (MaterialRequest.Source source : MaterialRequest.Source.values()) {
            MaterialRequest request = MaterialRequest.single(1, Items.CRAFTING_TABLE, 1, source);
            assertEquals(source == MaterialRequest.Source.CHEST_TRACKER_SCREEN,
                    request.acceptsStack(trigger), source.name());
            assertTrue(request.acceptsStack(new ItemStack(Items.CRAFTING_TABLE)), source.name());
        }
    }

    @Test
    void screenRetrievalStillRequiresTheRequestedItemAndStackConstraints() {
        MaterialRequest request = MaterialRequest.single(
                1, Items.CRAFTING_TABLE, 1, MaterialRequest.Source.CHEST_TRACKER_SCREEN);
        assertFalse(request.acceptsStack(null));
        assertFalse(request.acceptsStack(ItemStack.EMPTY));
        assertFalse(request.acceptsStack(named(Items.STONE, "虚空仓库")));
        MaterialRequest shulkerOnly = new MaterialRequest(2, java.util.List.of(Items.CRAFTING_TABLE),
                Items.CRAFTING_TABLE, 1, MaterialRequest.Source.CHEST_TRACKER_SCREEN, true);
        assertFalse(shulkerOnly.acceptsStack(named(Items.CRAFTING_TABLE, "虚空仓库")));
    }
}
