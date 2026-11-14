//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.integration.quickshulker;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.interaction.MiningDurabilityGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.Tool;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** Real tool components and production accounting; no synthetic durability consumption or balance writes. */
class MiningDurabilityGuardCostTest {
    private static final BlockPos TARGET = BlockPos.ZERO;

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @Test
    void anOrdinaryPickaxeCanBreakZeroHardnessWithoutSpendingItsLastDurability() throws Exception {
        int previous = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        try (var f = new MiningInteractionRegressionTest.Fixture(0.8F, false)) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            f.player.held = withRemaining(Items.DIAMOND_PICKAXE, 1);
            var state = Blocks.TORCH.defaultBlockState();
            assertEquals(0.0F, state.getDestroySpeed(f.level, TARGET));
            var guard = new MiningDurabilityGuard(f.client);
            assertTrue(guard.prepare());
            int selected = InventoryUtils.getSelectedSlot(f.player.inventory);
            for (int attempt = 0; attempt < 32; attempt++) {
                assertTrue(guard.canUse(selected, state, TARGET));
                assertTrue(guard.reserve(state, TARGET));
            }
            assertEquals(1, balance(guard, selected), "Zero-hardness uses do not silently reduce even the shadow balance");
            assertEquals(1, f.player.held.getMaxDamage() - f.player.held.getDamageValue());
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previous);
        }
    }

    @Test
    void shearsStillSpendOneOnZeroHardnessAndKeepOneDelayedTailUse() throws Exception {
        int previous = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        try (var f = new MiningInteractionRegressionTest.Fixture(0.8F, false)) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            var state = Blocks.TORCH.defaultBlockState();
            assertEquals(0.0F, state.getDestroySpeed(f.level, TARGET));
            int selected = InventoryUtils.getSelectedSlot(f.player.inventory);
            f.player.held = withRemaining(Items.SHEARS, 21);
            var insufficient = new MiningDurabilityGuard(f.client);
            assertTrue(insufficient.prepare());
            assertFalse(insufficient.canUse(selected, state, TARGET), "Reserve 20 plus one delayed tail leaves no new shears use");
            assertFalse(insufficient.reserve(state, TARGET));
            assertEquals(21, balance(insufficient, selected));

            f.player.held = withRemaining(Items.SHEARS, 22);
            var enough = new MiningDurabilityGuard(f.client);
            assertTrue(enough.prepare());
            assertTrue(enough.reserve(state, TARGET));
            assertEquals(21, balance(enough, selected));
            assertTrue(enough.canContinue(state, TARGET), "The paid use remains valid while its hold continues");
            assertFalse(enough.reserve(state, TARGET.east()), "A second target would spend the delayed-tail reserve");
            assertEquals(21, balance(enough, selected));
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previous);
        }
    }

    @Test
    void multiDamageToolsKeepTheirFullTailCostAndCannotOverflowTheAdmissionThreshold() throws Exception {
        int previous = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        try (var f = new MiningInteractionRegressionTest.Fixture(0.8F, false)) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            f.player.held = withRemaining(Items.DIAMOND_SWORD, 24);
            Tool sword = f.player.held.get(DataComponents.TOOL);
            assertNotNull(sword);
            assertEquals(2, sword.damagePerBlock(), "The real sword component establishes a two-durability action");
            int selected = InventoryUtils.getSelectedSlot(f.player.inventory);
            var state = Blocks.STONE.defaultBlockState();
            var guard = new MiningDurabilityGuard(f.client);
            assertTrue(guard.prepare());
            assertTrue(guard.reserve(state, TARGET));
            assertEquals(22, balance(guard, selected), "Reserve 20 and a two-cost delayed tail remain intact");
            assertFalse(guard.reserve(state, TARGET.east()));
            assertEquals(22, balance(guard, selected));

            // Valid component values can exceed a signed-int sum even with the modest UI reserve.
            ItemStack extreme = new ItemStack(Items.DIAMOND_SWORD);
            extreme.set(DataComponents.MAX_DAMAGE, Integer.MAX_VALUE);
            extreme.set(DataComponents.TOOL,
                    new Tool(sword.rules(), sword.defaultMiningSpeed(), Integer.MAX_VALUE, sword.canDestroyBlocksInCreative()));
            f.player.held = extreme;
            var bounded = new MiningDurabilityGuard(f.client);
            assertTrue(bounded.prepare());
            assertFalse(bounded.isStackUsable(extreme));
            assertFalse(bounded.canUse(selected, state, TARGET));
            assertFalse(bounded.reserve(state, TARGET));
            assertEquals(Integer.MAX_VALUE, balance(bounded, selected), "Rejected overlarge cost cannot wrap the threshold or debit the balance");
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previous);
        }
    }

    @Test
    void enablingProtectionAfterUnobservedUsesRequiresAnAuthoritativeCalibration() throws Exception {
        int previous = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        try (var f = new MiningInteractionRegressionTest.Fixture(0.8F, false)) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(0);
            f.player.held = withRemaining(Items.DIAMOND_PICKAXE, 24);
            int damage = f.player.held.getDamageValue();
            var guard = new MiningDurabilityGuard(f.client);
            var state = Blocks.STONE.defaultBlockState();
            assertTrue(guard.prepare());
            for (int target = 0; target < 3; target++) assertTrue(guard.reserve(state, TARGET.east(target)));
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            for (int tick = 1; tick <= 20; tick++) {
                f.level.tick = tick;
                assertFalse(guard.prepare(), "No transport/fence receipt means the unchanged visible stack cannot become a fresh budget");
            }
            assertEquals(damage, f.player.held.getDamageValue());
            assertFalse(guard.canUse(InventoryUtils.getSelectedSlot(f.player.inventory), state, TARGET));
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previous);
        }
    }

    private static ItemStack withRemaining(Item item, int remaining) {
        ItemStack stack = new ItemStack(item);
        assertTrue(stack.isDamageableItem());
        stack.setDamageValue(stack.getMaxDamage() - remaining);
        return stack;
    }

    private static int balance(MiningDurabilityGuard guard, int slot) throws Exception {
        return ((int[]) get(guard, MiningDurabilityGuard.class, "remaining"))[slot];
    }
}
//#endif
