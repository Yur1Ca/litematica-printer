package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.guide.guides.ComposterGuide;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ComposterBlock;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.util.Arrays;
import java.util.List;
//#if MC >= 260300
//$$ import net.minecraft.core.Holder;
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//$$ import net.minecraft.core.component.DataComponentMap;
//$$ import net.minecraft.core.component.DataComponents;
    //$$ import net.minecraft.tags.TagKey;
    //$$ import me.aleksilassila.litematica.printer.utils.minecraft.IdentifierUtils;
//#endif
import static org.junit.jupiter.api.Assertions.*;

class ComposterMaterialLifecycleTest {
    private boolean previousEnabled;
    private List<String> previousWhitelist;

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @BeforeEach void configure() {
        previousEnabled = Configs.Print.FILL_COMPOSTER.getBooleanValue();
        previousWhitelist = List.copyOf(Configs.Print.FILL_COMPOSTER_WHITELIST.getStrings());
        Configs.Print.FILL_COMPOSTER.setBooleanValue(true);
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of());
    }

    @AfterEach void restore() {
        Configs.Print.FILL_COMPOSTER.setBooleanValue(previousEnabled);
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(previousWhitelist);
    }

    private static ComposterGuide guide(int current, int required) throws Exception {
        var context = InventoryRegressionTest.context(Blocks.COMPOSTER.defaultBlockState()
                .setValue(ComposterBlock.LEVEL, required));
        InventoryRegressionTest.set(context, "currentState", Blocks.COMPOSTER.defaultBlockState()
                .setValue(ComposterBlock.LEVEL, current));
        return new ComposterGuide(context);
    }

    private static Item[] materials(ComposterGuide guide) {
        Result result = guide.buildAction(BlockMatchResult.WRONG_STATE);
        return result.hasAction() ? result.action().getRequiredItems(Blocks.COMPOSTER) : new Item[0];
    }

    @Test void emptyOrUnmatchedWhitelistKeepsAllCandidatesAndTheirOrder() throws Exception {
        var guide = guide(0, 3);
        Item[] all = materials(guide);
        assertTrue(Arrays.asList(all).contains(Items.WHEAT));
        assertTrue(Arrays.asList(all).contains(Items.CARROT));
        assertFalse(Arrays.asList(all).contains(Items.STONE));
        //#if MC >= 260300
        //$$ Item[] registryOrder = BuiltInRegistries.ITEM.stream()
        //$$         .filter(item -> item.components().has(DataComponents.COMPOSTABLE)).toArray(Item[]::new);
        //$$ assertArrayEquals(registryOrder, all, "Keep the original registry scan's complete candidate order");
        //#endif
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("minecraft:not_an_item"));
        assertArrayEquals(all, materials(guide));
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of());
        assertArrayEquals(all, materials(guide));
    }

    @Test void whitelistUsesCandidateOrderAndChangingItTakesEffectImmediately() throws Exception {
        var guide = guide(0, 3);
        Item[] all = materials(guide);
        Item[] selected = Arrays.stream(all).filter(i -> i == Items.WHEAT || i == Items.CARROT).toArray(Item[]::new);
        assertEquals(2, selected.length);
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("minecraft:carrot", "minecraft:wheat", "minecraft:carrot"));
        assertArrayEquals(selected, materials(guide));
        Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("minecraft:carrot"));
        assertArrayEquals(new Item[]{Items.CARROT}, materials(guide));
    }

    @Test void disabledOrAlreadyFilledComposterDoesNotRequestMaterials() throws Exception {
        assertSame(Result.PASS, guide(3, 3).buildAction(BlockMatchResult.WRONG_STATE));
        assertSame(Result.PASS, guide(5, 3).buildAction(BlockMatchResult.WRONG_STATE));
        Configs.Print.FILL_COMPOSTER.setBooleanValue(false);
        assertSame(Result.PASS, guide(0, 3).buildAction(BlockMatchResult.WRONG_STATE));
    }

    //#if MC >= 260300
    //$$ @Test void incompleteComponentsPauseAndRecoverWithoutCachingPartialCandidates() throws Exception {
    //$$     var guide = guide(0, 3);
    //$$     Item[] before = materials(guide);
    //$$     var wheat = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(Items.WHEAT);
    //$$     var components = Holder.Reference.class.getDeclaredField("components");
    //$$     components.setAccessible(true);
    //$$     Object saved = components.get(wheat);
    //$$     try {
    //$$         components.set(wheat, null);
    //$$         assertEquals(0, materials(guide).length, "Do not issue an action using an incomplete item snapshot");
    //$$         components.set(wheat, saved);
    //$$         assertArrayEquals(before, materials(guide), "Component readiness must be retried");
    //$$     } finally {
    //$$         components.set(wheat, saved);
    //$$     }
    //$$ }
    //$$
    //$$ @Test void switchingComponentDefinitionsRefreshesWhitelistEvenWhenItsTextStaysTheSame() throws Exception {
    //$$     var guide = guide(0, 3);
    //$$     Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("minecraft:wheat", "minecraft:stone"));
    //$$     assertArrayEquals(new Item[]{Items.WHEAT}, materials(guide));
    //$$     var wheat = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(Items.WHEAT);
    //$$     var stone = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(Items.STONE);
    //$$     var wheatBefore = wheat.components();
    //$$     var stoneBefore = stone.components();
    //$$     try {
    //$$         wheat.bindComponents(DataComponentMap.builder().addAll(wheatBefore).set(DataComponents.COMPOSTABLE, null).build());
    //$$         stone.bindComponents(DataComponentMap.builder().addAll(stoneBefore)
    //$$                 .set(DataComponents.COMPOSTABLE, wheatBefore.get(DataComponents.COMPOSTABLE)).build());
    //$$         assertArrayEquals(new Item[]{Items.STONE}, materials(guide), "Rebinding must remove old and include new candidates");
    //$$         wheat.bindComponents(wheatBefore);
    //$$         stone.bindComponents(stoneBefore);
    //$$         assertArrayEquals(new Item[]{Items.WHEAT}, materials(guide), "Returning to the previous server must refresh again");
    //$$     } finally {
    //$$         wheat.bindComponents(wheatBefore);
    //$$         stone.bindComponents(stoneBefore);
    //$$     }
    //$$ }
    //$$
    //$$ @Test void tagReloadRefreshesWhitelistWithoutChangingCompostability() throws Exception {
    //$$     var guide = guide(0, 3);
    //$$     Configs.Print.FILL_COMPOSTER_WHITELIST.setStrings(List.of("#minecraft:issue25_whitelist"));
    //$$     var tag = TagKey.create(BuiltInRegistries.ITEM.key(), IdentifierUtils.of("minecraft", "issue25_whitelist"));
    //$$     var wheat = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(Items.WHEAT);
    //$$     var carrot = (Holder.Reference<Item>) BuiltInRegistries.ITEM.wrapAsHolder(Items.CARROT);
    //$$     var wheatTags = wheat.tags().toList();
    //$$     var carrotTags = carrot.tags().toList();
    //$$     var bindTags = Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
    //$$     bindTags.setAccessible(true);
    //$$     try {
    //$$         bindTags.invoke(wheat, List.of(tag));
    //$$         assertArrayEquals(new Item[]{Items.WHEAT}, materials(guide));
    //$$         bindTags.invoke(wheat, wheatTags);
    //$$         bindTags.invoke(carrot, List.of(tag));
    //$$         assertArrayEquals(new Item[]{Items.CARROT}, materials(guide));
    //$$     } finally {
    //$$         bindTags.invoke(wheat, wheatTags);
    //$$         bindTags.invoke(carrot, carrotTags);
    //$$     }
    //$$ }
    //#endif
}
