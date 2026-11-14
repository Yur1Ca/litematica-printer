package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/** Reads live state once and serves subsequent passes from the incremental section cache. */
final class SnapshotWorldObservation implements WorldObservationPort {
    private final SectionSnapshotStore snapshots;
    private final WorldObservationPort source;

    SnapshotWorldObservation(SectionSnapshotStore snapshots, WorldObservationPort source) {
        this.snapshots = snapshots;
        this.source = source;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.source.hasChunk(chunkX, chunkZ);
    }

    @Override
    public boolean hasCandidatesInChunk(
            ScanIntent intent,
            int chunkX,
            int chunkZ,
            boolean breakExtraBlocks
    ) {
        return this.source.hasCandidatesInChunk(intent, chunkX, chunkZ, breakExtraBlocks);
    }

    @Override
    public BlockState worldState(BlockPos pos) {
        return this.source.worldState(pos);
    }

    @Override
    public boolean hasCandidatesInSection(ScanIntent intent, int sectionX, int sectionY,
                                          int sectionZ, boolean breakExtraBlocks) {
        return this.source.hasCandidatesInSection(intent, sectionX, sectionY, sectionZ, breakExtraBlocks);
    }

    @Override
    public @Nullable BlockState schematicState(BlockPos pos) {
        return this.source.schematicState(pos);
    }

    @Override
    public boolean hasFillSupport(BlockPos pos) {
        return this.source.hasFillSupport(pos);
    }

    @Override
    public byte classify(ScanIntent intent, BlockPos pos, boolean breakExtraBlocks) {
        return this.snapshots.classify(pos, intent, breakExtraBlocks, this.source);
    }
}
