package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import static me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils.isReplaceable;

/** Pure candidate classification; it does not read Minecraft or scheduler state. */
final class ScanClassifier {
    private ScanClassifier() {
    }

    static byte flags(
            ScanIntent intent,
            BlockState worldState,
            @Nullable BlockState schematicState,
            boolean hasFillSupport,
            boolean breakExtraBlocks
    ) {
        return switch (intent) {
            case PRINT -> printFlags(worldState, schematicState, breakExtraBlocks);
            case MINE, BEDROCK -> worldState.isAir() || worldState.getBlock() instanceof LiquidBlock
                    ? 0 : ScanFlags.WORLD_NON_AIR;
            case FLUID -> worldState.getFluidState().isEmpty()
                    ? 0 : (byte) (ScanFlags.WORLD_NON_AIR | ScanFlags.WORLD_FLUID);
            case TRENCH -> isTrenchTarget(worldState) ? ScanFlags.WORLD_NON_AIR : 0;
            case FILL -> fillFlags(worldState, hasFillSupport);
            default -> 0;
        };
    }

    static boolean isTrenchTarget(BlockState state) {
        return state.is(Blocks.BUDDING_AMETHYST) || !state.getFluidState().isEmpty();
    }

    private static byte printFlags(
            BlockState worldState,
            @Nullable BlockState schematicState,
            boolean breakExtraBlocks
    ) {
        if (schematicState == null) return 0;
        if (schematicState.equals(worldState) && !(schematicState.getBlock() instanceof BaseRailBlock)) return 0;
        if (!schematicState.isAir()) {
            return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.SCHEMATIC_NON_AIR);
        }
        if (breakExtraBlocks && !worldState.isAir() && !(worldState.getBlock() instanceof LiquidBlock)) {
            return (byte) (ScanFlags.SCHEMATIC_SAMPLED | ScanFlags.WORLD_NON_AIR);
        }
        return 0;
    }

    private static byte fillFlags(BlockState state, boolean hasFillSupport) {
        boolean potential = state.isAir()
                || state.getBlock() instanceof LiquidBlock
                || isReplaceable(state);
        return potential && hasFillSupport ? ScanFlags.BASE_FILL_TARGET : 0;
    }
}
