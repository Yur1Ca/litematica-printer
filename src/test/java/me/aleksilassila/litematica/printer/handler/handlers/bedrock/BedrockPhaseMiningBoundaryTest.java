//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.level.block.Blocks;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static org.junit.jupiter.api.Assertions.*;

/** A real cleanup START owner must survive every critical submission boundary until actual AIR. */
class BedrockPhaseMiningBoundaryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos RESIDUE = TARGET.west(4).above();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anOpenCleanupPreventsBothCriticalEntryPoints(boolean batch) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            f.cleanupWorld();
            f.setBreakSpeed(1.0D);
            f.level.blocks.put(RESIDUE, Blocks.PISTON.defaultBlockState());
            assertTrue(f.enginePlacer.breakCleanupBlock(RESIDUE, Direction.DOWN));
            assertTrue(f.enginePlacer.isCleanupPendingAt(RESIDUE));
            assertTrue(f.enginePlacer.hasActiveCleanupMining(), "Native destroy progress opens an owned slow attempt");
            var start = f.transport.packets.getLast();
            assertInstanceOf(ServerboundPlayerActionPacket.class, start);
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ((ServerboundPlayerActionPacket) start).getAction());
            assertEquals(RESIDUE, ((ServerboundPlayerActionPacket) start).getPos());
            f.setBreakSpeed(100.0D); // A capable critical tool must still respect the existing OPEN owner.
            f.machine(Direction.UP);
            var layout = f.target(TARGET, Direction.UP, f.enginePlacer).layout();
            var critical = (BedrockCriticalExecutor) get(f.engine, BedrockEngine.class, "criticalExecutor");
            var target = new BedrockTarget(TARGET, f.client.level, layout,
                    new BedrockTorchPlacement(TARGET.east(), Direction.UP), null, critical, f.enginePlacer, null);
            f.level.blocks.put(target.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            target.setStatus(BedrockTarget.Status.EXTENDED);
            f.transport.packets.clear();

            assertFalse(submit(critical, target, batch), "A second position cannot replace the native cleanup START owner");

            assertTrue(f.transport.packets.isEmpty(), "Neither critical START/STOP nor reverse OFF_HAND USE may cross OPEN");
            assertTrue(f.enginePlacer.hasActiveCleanupMining());
            f.level.blocks.remove(RESIDUE);
            f.enginePlacer.onCleanupBlockUpdate(RESIDUE);
            assertFalse(f.enginePlacer.hasActiveCleanupMining());
            assertTrue(submit(critical, target, batch), "The same otherwise-valid critical must resume after authoritative AIR");
            assertTrue(f.transport.packets.stream().anyMatch(ServerboundUseItemOnPacket.class::isInstance));
        }
    }

    private static boolean submit(BedrockCriticalExecutor critical, BedrockTarget target, boolean batch) {
        return batch ? !critical.submitBatch(List.of(target)).isEmpty()
                : critical.submit(target.level(), target.getBedrockPos(), target.getPistonPos(),
                target.layout().getExecuteFacing(), target.getOwnedTorchPositions());
    }
}
//#endif
