//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Warehouse placement preserves the held tool, including a Silk Touch pickaxe. */
class IceBreakToolSelectorTest {
    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test
    void silkTouchFallsBackToAnEmptyHotbarSlot() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.player.inventory.setItem(4, ItemStack.EMPTY);

            assertTrue(fixture.select(), "Ice must remain breakable without a second pickaxe");
            assertEquals(4, InventoryUtils.getSelectedSlot(fixture.player.inventory));
            assertTrue(fixture.player.getMainHandItem().isEmpty());
        }
    }

    @Test
    void silkTouchFallsBackToAnOrdinaryHotbarItem() throws Exception {
        try (var fixture = new Fixture()) {
            ItemStack stone = new ItemStack(Items.STONE);
            fixture.player.inventory.setItem(4, stone);

            assertTrue(fixture.select(), "A block can break ice without preserving it");
            assertEquals(4, InventoryUtils.getSelectedSlot(fixture.player.inventory));
            assertSame(stone, fixture.player.getMainHandItem());
        }
    }

    @Test
    void effectiveNonSilkTouchToolStillTakesPriorityOverTheFallback() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.player.inventory.setItem(3, ItemStack.EMPTY);
            ItemStack pickaxe = new ItemStack(Items.DIAMOND_PICKAXE);
            fixture.player.inventory.setItem(5, pickaxe);

            assertTrue(fixture.select());
            assertEquals(5, InventoryUtils.getSelectedSlot(fixture.player.inventory));
            assertSame(pickaxe, fixture.player.getMainHandItem());
        }
    }

    @Test
    void anAlreadySafeHandDoesNotSwitchToAnotherSlowFallback() throws Exception {
        try (var fixture = new Fixture()) {
            ItemStack stone = new ItemStack(Items.STONE);
            fixture.player.inventory.setItem(0, stone);
            fixture.player.inventory.setItem(4, ItemStack.EMPTY);

            assertTrue(fixture.select());
            assertEquals(0, InventoryUtils.getSelectedSlot(fixture.player.inventory));
            assertSame(stone, fixture.player.getMainHandItem());
            assertTrue(fixture.connection.packets.isEmpty());
        }
    }

    @SuppressWarnings("deprecation")
    private static final class Fixture extends BedrockToolEligibilityTest.Fixture {
        private final List<?> oldIceTags = Blocks.ICE.builtInRegistryHolder().tags().toList();

        Fixture() throws Exception {
            super(0);
            bindIceTags(List.of(BlockTags.MINEABLE_WITH_PICKAXE));
            ItemStack silkTouch = new ItemStack(Items.DIAMOND_PICKAXE);
            //#if MC >= 260300
            //$$ silkTouch.enchant(VanillaRegistries.createWorldLookup().lookupOrThrow(Registries.ENCHANTMENT)
            //$$         .getOrThrow(Enchantments.SILK_TOUCH), 1);
            //#else
            silkTouch.enchant(VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.SILK_TOUCH), 1);
            //#endif
            for (int slot = 0; slot < 9; slot++) player.inventory.setItem(slot, silkTouch.copy());
            InventoryUtils.setSelectedSlot(player.inventory, 0);
        }

        boolean select() throws Exception {
            var selector = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.print.IceBreakToolSelector");
            var method = selector.getDeclaredMethod("switchToNonSilkTouchBreakItem", Minecraft.class);
            method.setAccessible(true);
            try {
                return (boolean) method.invoke(null, client);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                if (error.getCause() instanceof Error cause) throw cause;
                throw error;
            }
        }

        private static void bindIceTags(Collection<?> tags) throws Exception {
            var method = Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            method.setAccessible(true);
            method.invoke(Blocks.ICE.builtInRegistryHolder(), tags);
        }

        @Override public void close() throws Exception {
            bindIceTags(oldIceTags);
            super.close();
        }
    }
}
//#endif
