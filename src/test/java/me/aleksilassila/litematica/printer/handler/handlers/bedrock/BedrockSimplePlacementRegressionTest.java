package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Ordinary machine materials must not create untracked predictions or drifting air-clicks. */
class BedrockSimplePlacementRegressionTest {
    private static final BlockPos BEDROCK = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void simplePlacementUsesTheGameModesManagedPrediction() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            // A non-block item keeps the transport-boundary fixture independent of world writes.
            // The production choice of prediction mode is shared by every simple material.
            f.client.player.getInventory().setItem(40, new ItemStack(Items.STICK, 16));
            var mode = captureGameMode(f);

            assertThrows(PlacementCaptured.class, () -> f.placer.placeSimple(BEDROCK, Direction.UP, Items.STICK));

            assertEquals(List.of(true), mode.predictions,
                    "Use the game mode's sequence-managed prediction instead of raw send plus direct ItemStack.useOn");
            assertEquals(1, mode.hits.size());
        }
    }

    @Test void slimeSupportUsesAStableAnchorWhenTheSameRequestArrivesAfterPlacement() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            BlockPos slime = BEDROCK.east();
            f.client.player.getInventory().setItem(40, new ItemStack(Items.SLIME_BLOCK, 16));
            var mode = captureGameMode(f);
            var constructor = BedrockMachineLayout.class.getDeclaredConstructor(BlockPos.class, Direction.class);
            constructor.setAccessible(true);
            var layout = constructor.newInstance(BEDROCK, Direction.UP);
            var machine = new BedrockTargetMachine(f.level, layout, BEDROCK, BEDROCK.above(), BEDROCK.above(2),
                    new BedrockTargetFootprint(f.level, BEDROCK, BEDROCK.above(), BEDROCK.above(2)),
                    new BedrockTorchPlacement(slime, Direction.UP), slime, f.placer);

            // Stop at the outbound boundary so the fixture never invokes BlockItem's local world writes.
            assertThrows(PlacementCaptured.class, () -> machine.ensureTorchPlacement(placement -> false, () -> { }));
            assertEquals(1, mode.hits.size());
            BlockHitResult request = mode.hits.get(0);
            assertEquals(slime, f.serverPlacementPosition(request));

            // Replay the exact outbound hit after the server accepted the earlier placement.
            f.level.blocks.put(slime, Blocks.SLIME_BLOCK.defaultBlockState());
            assertEquals(slime, f.serverPlacementPosition(request),
                    "A delayed duplicate must still target the support, never the torch position above it");
            assertEquals(BEDROCK, request.getBlockPos(), "Click the existing neighbor rather than the empty support position");
            assertEquals(Direction.EAST, request.getDirection());
        }
    }

    private static CapturingGameMode captureGameMode(BedrockPlacementRegressionTest.Fixture fixture) throws Exception {
        var mode = fixture.allocate(CapturingGameMode.class);
        mode.hits = new ArrayList<>();
        mode.predictions = new ArrayList<>();
        fixture.client.gameMode = mode;
        return mode;
    }

    @Test void isolatedBedrockChoosesAnAnchorableLowerSlimeSupport() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            BedrockTorchPlacement placement = BedrockEnvironment.findPossibleSlimeTorchPlacement(
                    f.level, BEDROCK.above(), Direction.DOWN, BEDROCK, BEDROCK.above(), BEDROCK.above(2));

            assertNotNull(placement, "The same-height side support keeps isolated bedrock workable");
            assertEquals(BEDROCK.east(), placement.getSupportPos(),
                    "Skip the floating upper candidate and continue searching for a supported lower candidate");
            var interaction = BedrockEnvironment.findPlacementInteraction(f.level, placement.getSupportPos());
            assertNotNull(interaction);
            assertEquals(BEDROCK, interaction.anchorPos());
            assertEquals(Direction.EAST, interaction.clickedFace());
        }
    }

    /** Capture the real machine and placer's request before any game-mode world prediction. */
    private static final class CapturingGameMode extends BedrockPlacementRegressionTest.TestGameMode {
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            super.litematica_printer$useItemOn(prediction, hand, hit);
            throw new PlacementCaptured();
        }
    }

    private static final class PlacementCaptured extends RuntimeException { }
}
