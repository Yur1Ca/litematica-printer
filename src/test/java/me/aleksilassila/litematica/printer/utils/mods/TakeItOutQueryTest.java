//#if MC == 260100
package me.aleksilassila.litematica.printer.utils.mods;

import java.util.List;
import java.util.Set;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static me.aleksilassila.litematica.printer.utils.mods.TakeItOutQueryFixture.*;

class TakeItOutQueryTest {
    @BeforeAll static void bootstrap() { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap(); }

    @Test void everyQueryReadsCurrentEntryValuesAndCollectionTypes() throws Exception {
        try (var f = new TakeItOutQueryFixture(true)) {
            var stone = new Entry(new ItemStack(Items.STONE), 1);
            var dirt = new Entry(new ItemStack(Items.DIRT), 1);
            Client.WORLD_CONTAINER_ITEMS = List.of(stone, dirt);
            assertEquals(Set.of(Items.STONE, Items.DIRT), f.available());
            stone.count = 0; dirt.stack = new ItemStack(Items.GLASS);
            assertEquals(Set.of(Items.GLASS), f.available());
            Client.WORLD_CONTAINER_ITEMS = List.of(new OtherEntry(new ItemStack(Items.DIAMOND), 4));
            assertEquals(Set.of(Items.DIAMOND), f.available());
        }
    }

    @Test void emptySelectionReadsChangedEntriesAndReturnsAnIndependentStack() throws Exception {
        try (var f = new TakeItOutQueryFixture(true)) {
            var entry = new Entry(new ItemStack(Items.STONE), 1);
            var required = new ItemStack(Items.SHULKER_BOX);
            assertTrue(f.empty(required, List.of(entry)).is(Items.SHULKER_BOX));
            entry.stack = required.copy();
            entry.stack.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("current named box"));
            var selected = f.empty(required, List.of(entry));
            assertEquals(entry.stack.getHoverName(), selected.getHoverName());
            assertNotSame(entry.stack, selected);
        }
    }

    @Test void disabledAndAbsentPluginsDoNotExposePreviouslyAvailableMaterials() throws Exception {
        try (var f = new TakeItOutQueryFixture(true)) {
            Client.WORLD_CONTAINER_ITEMS = List.of(new Entry(new ItemStack(Items.STONE), 1));
            assertEquals(Set.of(Items.STONE), f.available());
            Client.AUTOTAKEOUT = false; assertTrue(f.available().isEmpty());
            Client.AUTOTAKEOUT = true; f.loaded = false; assertTrue(f.available().isEmpty());
            f.loaded = true; Client.WORLD_CONTAINER_ITEMS = List.of(); assertTrue(f.available().isEmpty());
            assertSame(ItemStack.EMPTY, f.call("getAwaitingStack", new Class<?>[0]));
            var newPending = new ItemStack(Items.DIRT); Client.awaitingStack = newPending;
            assertSame(newPending, f.call("getAwaitingStack", new Class<?>[0]));
        }
    }

    @Test void unavailableEntryShapeStillFallsBackWithoutAStaleSuccessfulResult() throws Exception {
        try (var f = new TakeItOutQueryFixture(true)) {
            Client.WORLD_CONTAINER_ITEMS = List.of(new Entry(new ItemStack(Items.STONE), 1));
            assertEquals(Set.of(Items.STONE), f.available());
            Client.WORLD_CONTAINER_ITEMS = List.of(new MissingEntry());
            assertTrue(f.available().isEmpty());
            f.call("requestAvailableItemsRefresh", new Class<?>[0]); // old dependency without cache API
        }
    }

    @Test void legacyAndModernRequestSignaturesKeepLiveSingleItemModeAndUnavailableFallback() throws Exception {
        try (var f = new TakeItOutQueryFixture(true)) {
            var stack = new ItemStack(Items.STONE);
            f.sourceType = LegacySources.class;
            assertEquals(true, f.call("tryRequestFromWorldContainers", new Class<?>[]{ItemStack.class}, stack));
            Client.TAKE_SINGLE_ITEM_MODE = true;
            assertEquals(true, f.call("tryRequestFromWorldContainers", new Class<?>[]{ItemStack.class}, stack));
            f.sourceType = ModernSources.class;
            assertEquals(true, f.call("tryRequestFromWorldContainers", new Class<?>[]{ItemStack.class}, stack));
            assertEquals(List.of(false, true, true), f.singleItemRequests);
            f.sourceType = MissingSources.class;
            assertEquals(false, f.call("tryRequestFromWorldContainers", new Class<?>[]{ItemStack.class}, stack));
            assertEquals(new LegacyPayload(2, 9), f.call("createShulkerPayload", new Class<?>[]{Class.class, int.class, int.class}, LegacyPayload.class, 2, 9));
            assertEquals(new ModernPayload(2, 9, true), f.call("createShulkerPayload", new Class<?>[]{Class.class, int.class, int.class}, ModernPayload.class, 2, 9));
        }
    }
}
//#endif
