//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.Optional;
import me.aleksilassila.litematica.printer.printer.action.Action;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class DoublePlantUpperRecoveryTest {
    private static final BlockPos LOWER = TARGET;
    private static final BlockPos UPPER = LOWER.above();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static BlockState lower() { return Blocks.ROSE_BUSH.defaultBlockState(); }
    static BlockState upper() {
        return lower().setValue(BlockStateProperties.DOUBLE_BLOCK_HALF, DoubleBlockHalf.UPPER);
    }

    static class Schematic extends WorldSchematic {
        boolean includesLower;
        Schematic() { super(null, null, null, null); }
        @Override public BlockState getBlockState(BlockPos pos) {
            if (pos.equals(UPPER)) return upper();
            if (includesLower && pos.equals(LOWER)) return lower();
            return Blocks.AIR.defaultBlockState();
        }
    }

    static class RoseFixture extends Fixture {
        final boolean oldWrongState = Configs.Print.BREAK_WRONG_STATE_BLOCK.getBooleanValue();
        final boolean oldWrongBlock = Configs.Print.BREAK_WRONG_BLOCK.getBooleanValue();
        final Schematic schematic = alloc(Schematic.class);
        final Guides guides = new Guides();
        final List<?> oldSoilTags = Blocks.FARMLAND.builtInRegistryHolder().tags().toList();
        boolean repairAllowed = true;

        RoseFixture() throws Exception {
            super(1.0f, false);
            Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(true);
            Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(false);
            schematic.includesLower = true;
            var soilTags = new ArrayList<Object>(oldSoilTags);
            soilTags.add(net.minecraft.tags.BlockTags.SUPPORTS_VEGETATION);
            bindSoilTags(soilTags);
            level.states = new HashMap<>();
            level.states.put(LOWER, lower());
            level.states.put(UPPER, Blocks.AIR.defaultBlockState());
            level.states.put(LOWER.below(), Blocks.FARMLAND.defaultBlockState());
        }

        SchematicBlockContext context(BlockPos pos) {
            return new SchematicBlockContext(client, level, schematic, pos);
        }

        Optional<Action> build(BlockPos pos) throws Exception {
            return guides.buildAction(context(pos), ignored -> repairAllowed);
        }

        BooleanSupplier queuedValidity() throws Exception {
            var guards = (Map<?, ?>) get(interactions, InteractionUtils.class, "guardedBreaks");
            var guard = guards.get(LOWER);
            assertNotNull(guard, "Lower reset must use the existing guarded break queue");
            var accessor = guard.getClass().getDeclaredField("stillNeeded");
            accessor.setAccessible(true);
            return (BooleanSupplier) accessor.get(guard);
        }

        @Override public void close() throws Exception {
            bindSoilTags(oldSoilTags);
            Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(oldWrongState);
            Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(oldWrongBlock);
            super.close();
        }

        private void bindSoilTags(Collection<?> tags) throws Exception {
            var method = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            method.setAccessible(true);
            method.invoke(Blocks.FARMLAND.builtInRegistryHolder(), tags);
        }
    }

    @Test void upperOnlyMissingQueuesGuardedLowerResetWithoutSpeculativeWorldWrites() throws Exception {
        try (var f = new RoseFixture()) {
            assertTrue(f.build(UPPER).isEmpty());
            assertTrue(f.interactions.isBreakPending(LOWER),
                    "A missing upper half cannot be repaired by repeatedly skipping the upper position");
            assertTrue(f.queuedValidity().getAsBoolean());
            assertEquals(lower(), f.level.getBlockState(LOWER));
            assertTrue(f.level.getBlockState(UPPER).isAir());
            assertTrue(f.connection.packets.isEmpty(), "Planning only queues the existing break lifecycle");

            // A confirmed lower removal is then handled by ordinary lower-half placement.
            f.level.states.put(LOWER, Blocks.AIR.defaultBlockState());
            assertTrue(f.build(LOWER).isPresent());
        }
    }

    @Test void disabledWrongStateBreakNeverResetsCorrectLowerEvenIfWrongBlockBreakIsEnabled() throws Exception {
        try (var f = new RoseFixture()) {
            Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(false);
            Configs.Print.BREAK_WRONG_BLOCK.setBooleanValue(true);
            f.build(UPPER);
            assertFalse(f.interactions.isBreakPending(LOWER));
            assertEquals(lower(), f.level.getBlockState(LOWER));
        }
    }

    @Test void restoredUpperOrDisabledSettingInvalidatesQueuedReset() throws Exception {
        try (var f = new RoseFixture()) {
            f.build(UPPER);
            var validity = f.queuedValidity();
            assertTrue(validity.getAsBoolean());
            f.level.states.put(UPPER, upper());
            assertFalse(validity.getAsBoolean());
            f.level.states.put(UPPER, Blocks.AIR.defaultBlockState());
            Configs.Print.BREAK_WRONG_STATE_BLOCK.setBooleanValue(false);
            assertFalse(validity.getAsBoolean());
        }
    }

    @Test void anUnpairedSchematicLowerOrAChangedWorldLowerIsNeverReset() throws Exception {
        try (var f = new RoseFixture()) {
            f.schematic.includesLower = false;
            f.build(UPPER);
            assertFalse(f.interactions.isBreakPending(LOWER));
            f.schematic.includesLower = true;
            f.level.states.put(LOWER, Blocks.LILAC.defaultBlockState());
            f.build(UPPER);
            assertFalse(f.interactions.isBreakPending(LOWER));
        }
    }

    @Test void aLowerOutsideTheAllowedScopeCannotBeQueuedOrRemainAuthorized() throws Exception {
        try (var f = new RoseFixture()) {
            f.repairAllowed = false;
            f.build(UPPER);
            assertFalse(f.interactions.isBreakPending(LOWER));
            f.repairAllowed = true;
            f.build(UPPER);
            var validity = f.queuedValidity();
            assertTrue(validity.getAsBoolean());
            f.repairAllowed = false;
            assertFalse(validity.getAsBoolean(), "A changed scope must revoke the queued lower reset");
        }
    }

    @Test void theUnscopedEntryPointNeverAuthorizesDestructiveNeighborRepair() throws Exception {
        try (var f = new RoseFixture()) {
            f.guides.buildAction(f.context(UPPER));
            assertFalse(f.interactions.isBreakPending(LOWER));
        }
    }
}
//#endif
