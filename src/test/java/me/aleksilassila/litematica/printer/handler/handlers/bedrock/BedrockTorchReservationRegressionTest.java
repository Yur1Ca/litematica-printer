package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.*;

/** Reserving one candidate must not hide another already usable power position. */
class BedrockTorchReservationRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void reservedFirstCandidateCannotHideALegalNaturalTorchPlacement() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var east = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            var west = new BedrockTorchPlacement(TARGET.west(), Direction.UP);
            f.level.blocks.put(east.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(west.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            f.mode.rejectMutations = true;
            var layout = f.target().layout();
            var machine = new BedrockTargetMachine(f.level, layout, TARGET, layout.getPistonPos(),
                    layout.getHeadPos(), new BedrockTargetFootprint(f.level, TARGET,
                    layout.getPistonPos(), layout.getHeadPos()), east, null, f.placer);
            Predicate<BedrockTorchPlacement> reserved = placement -> placement != null
                    && (placement.getSupportPos().getX() > TARGET.getX()
                    || placement.getTorchPos().getX() > TARGET.getX());
            assertEquals(east.getSupportPos(), BedrockEnvironment.findTorchPlacement(f.level,
                    layout.getPistonPos(), Direction.DOWN, TARGET, layout.getPistonPos(),
                    layout.getHeadPos()).getSupportPos(), "East is the first geometrically usable candidate");
            assertTrue(reserved.test(east));
            assertFalse(reserved.test(west));
            assertTrue(BedrockEnvironment.isTorchPlacementUsable(f.level, west));

            assertTrue(machine.ensureTorchPlacement(reserved,
                            () -> fail("An existing natural support needs no placement")),
                    "A reserved first candidate must not fail a machine with a legal west candidate");

            assertEquals(west.getSupportPos(), machine.torchPlacement().getSupportPos());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void anInstalledPoweredPrimingMachineKeepsItsUsableOwnedTorch() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var placement = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            var layout = f.target().layout();
            f.level.blocks.put(placement.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(placement.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            f.level.blocks.put(layout.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, true));
            f.mode.rejectMutations = true;
            var machine = new BedrockTargetMachine(f.level, layout, TARGET, layout.getPistonPos(),
                    layout.getHeadPos(), new BedrockTargetFootprint(f.level, TARGET,
                    layout.getPistonPos(), layout.getHeadPos()), placement, null, f.placer);
            assertTrue(BedrockEnvironment.isTorchPlacementUsable(f.level, placement));
            assertTrue(machine.hasOwnedTorchPowerSource());

            assertTrue(machine.ensureTorchPlacement(candidate -> true,
                            () -> fail("Retaining installed power must be observation only")),
                    "An inactive reservation cannot force rebuilding a correct powered machine");

            assertEquals(placement.getTorchPos(), machine.torchPos());
            assertTrue(f.mode.hits.isEmpty());
        }
    }

    @Test void aReservedCachedSlimeCandidateContinuesToTheNextAnchorableCandidate() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var east = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            var layout = f.target().layout();
            f.client.player.getInventory().setItem(40, new ItemStack(Items.SLIME_BLOCK, 16));
            var machine = new BedrockTargetMachine(f.level, layout, TARGET, layout.getPistonPos(),
                    layout.getHeadPos(), new BedrockTargetFootprint(f.level, TARGET,
                    layout.getPistonPos(), layout.getHeadPos()), east, east.getSupportPos(), f.placer);
            Predicate<BedrockTorchPlacement> reserved = placement -> placement != null
                    && (placement.getSupportPos().getX() > TARGET.getX()
                    || placement.getTorchPos().getX() > TARGET.getX());
            assertTrue(BedrockEnvironment.isSlimePlacementUsable(f.level, east));
            assertNull(BedrockEnvironment.findTorchPlacement(f.level, layout.getPistonPos(),
                    Direction.DOWN, TARGET, layout.getPistonPos(), layout.getHeadPos()));

            assertTrue(machine.ensureTorchPlacement(reserved, () -> { }),
                    "A reserved cached slime plan must not suppress the next anchorable plan");

            assertEquals(TARGET.west(), machine.torchSupportPos());
            assertEquals(1, f.mode.hits.size());
            assertEquals(TARGET, f.mode.hits.get(0).getBlockPos());
            assertEquals(Direction.WEST, f.mode.hits.get(0).getDirection());
        }
    }

    @Test void anInstalledOwnedTorchStillWaitsWhileAnotherMachineUsesItsPower() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            var engine = f.engine();
            var registry = (BedrockTargetRegistry) BedrockPlacementRegressionTest.get(
                    engine, BedrockEngine.class, "targets");
            var first = f.target();
            first.setHasTried(true);
            var second = f.target(TARGET.east(2), Direction.UP, f.placer);
            var shared = new BedrockTorchPlacement(TARGET.east(), Direction.UP);
            var machine = (BedrockTargetMachine) BedrockPlacementRegressionTest.get(
                    second, BedrockTarget.class, "machine");
            BedrockPlacementRegressionTest.set(machine, BedrockTargetMachine.class, "torchPlacement", shared);
            BedrockPlacementRegressionTest.set(machine, BedrockTargetMachine.class,
                    "torchSupportPos", shared.getSupportPos());
            f.level.blocks.put(second.getBedrockPos(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(second.getPistonPos(), Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, true));
            f.level.blocks.put(shared.getSupportPos(), Blocks.BEDROCK.defaultBlockState());
            f.level.blocks.put(shared.getTorchPos(), Blocks.REDSTONE_TORCH.defaultBlockState());
            registry.add(first);
            registry.add(second);
            f.mode.rejectMutations = true;
            assertTrue(second.hasOwnedTorchPowerSource());
            assertFalse(second.canMutatePower());

            assertEquals(BedrockTarget.Status.NEEDS_WAITING, second.tick(true, true));

            assertFalse(second.hasTried());
            assertTrue(f.level.getBlockState(second.getPistonPos()).getValue(PistonBaseBlock.EXTENDED));
            assertTrue(f.mode.hits.isEmpty(), "Inflight shared power still blocks preparation and execution");
        }
    }
}
