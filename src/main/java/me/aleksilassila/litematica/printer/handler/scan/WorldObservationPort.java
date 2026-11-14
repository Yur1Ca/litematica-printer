package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Main-thread-safe observation contract consumed by the scan classifier. */
interface WorldObservationPort {
    boolean hasChunk(int chunkX, int chunkZ);

    default boolean hasCandidatesInChunk(
            ScanIntent intent,
            int chunkX,
            int chunkZ,
            boolean breakExtraBlocks
    ) {
        return this.hasChunk(chunkX, chunkZ);
    }

    /** Coarse source metadata may reject a section without observing each cell. */
    default boolean hasCandidatesInSection(ScanIntent intent, int sectionX, int sectionY,
                                           int sectionZ, boolean breakExtraBlocks) {
        return true;
    }

    BlockState worldState(BlockPos pos);

    @Nullable BlockState schematicState(BlockPos pos);

    boolean hasFillSupport(BlockPos pos);

    default byte classify(ScanIntent intent, BlockPos pos, boolean breakExtraBlocks) {
        BlockState schematicState = intent == ScanIntent.PRINT ? this.schematicState(pos) : null;
        // A normal print pass is driven by the schematic, not by every live block in the
        // interaction box. Avoid a ClientLevel lookup for schematic-air positions; on large
        // sparse projects this is the difference between checking targets and walking the whole
        // 3-D box. BREAK_EXTRA_BLOCK deliberately keeps the world lookup for cleanup targets.
        if (intent == ScanIntent.PRINT && !breakExtraBlocks
                && (schematicState == null || schematicState.isAir())) {
            return 0;
        }
        BlockState state = this.worldState(pos);
        boolean fillSupport = intent == ScanIntent.FILL && this.hasFillSupport(pos);
        return ScanClassifier.flags(intent, state, schematicState, fillSupport, breakExtraBlocks);
    }
}
