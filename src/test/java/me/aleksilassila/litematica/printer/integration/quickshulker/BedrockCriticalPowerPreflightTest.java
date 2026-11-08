//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.properties.AttachFace;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real critical preflight with vanilla signal-producing block states. */
class BedrockCriticalPowerPreflightTest {
    private static final BlockPos PISTON = BlockPos.ZERO.above();
    private static final BlockPos TORCH = PISTON.east();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"direct_block", "quasi_connected_block", "strong_conductor", "foreign_torch"})
    void residualPowerRejectsEveryDestructivePacket(String source) throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            if (source.equals("direct_block")) {
                f.traceLevel.blocks.put(PISTON.west(), Blocks.REDSTONE_BLOCK.defaultBlockState());
            } else if (source.equals("quasi_connected_block")) {
                f.traceLevel.blocks.put(PISTON.above(2), Blocks.REDSTONE_BLOCK.defaultBlockState());
            } else if (source.equals("strong_conductor")) {
                // The ceiling lever lies outside the piston's direct/QC signal query.
                // Only its strong signal through the stone can keep the piston powered.
                f.traceLevel.blocks.put(PISTON.west(), Blocks.STONE.defaultBlockState());
                f.traceLevel.blocks.put(PISTON.west().below(), Blocks.LEVER.defaultBlockState()
                        .setValue(LeverBlock.FACE, AttachFace.CEILING).setValue(LeverBlock.POWERED, true));
            } else {
                f.traceLevel.blocks.put(PISTON.west(), Blocks.REDSTONE_TORCH.defaultBlockState());
            }
            f.assertRejectedBeforeBreak(Set.of(TORCH));
        }
    }

    @Test void ownedTorchIsMaskedWithoutChangingTheClientWorld() throws Exception {
        try (var f = new BedrockCriticalFlowTest.Fixture()) {
            var torchBefore = f.traceLevel.getBlockState(TORCH);
            // The owned torch strongly powers this QC conductor. Removing that
            // source from the signal view must remove its indirect power as well.
            var conductor = TORCH.above();
            f.traceLevel.blocks.put(conductor, Blocks.STONE.defaultBlockState());
            assertTrue(f.submit(Set.of(TORCH)));
            assertEquals(torchBefore, f.traceLevel.getBlockState(TORCH));
            assertTrue(f.traceLevel.getBlockState(conductor).is(Blocks.STONE));
            assertEquals(1, f.transport.batches.size());
        }
    }
}
//#endif
