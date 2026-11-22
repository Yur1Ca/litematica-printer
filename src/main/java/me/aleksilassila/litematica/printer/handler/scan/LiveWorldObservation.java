package me.aleksilassila.litematica.printer.handler.scan;

import fi.dy.masa.litematica.world.WorldSchematic;
import fi.dy.masa.litematica.world.ChunkSchematicState;
import me.aleksilassila.litematica.printer.utils.minecraft.BlockUtils;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.jetbrains.annotations.Nullable;

/** Adapts live Minecraft and schematic worlds to the scan observation contract. */
final class LiveWorldObservation implements WorldObservationPort {
    private static final Direction[] DIRECTIONS = Direction.values();

    private final ClientLevel level;
    @Nullable
    private final WorldSchematic schematic;
    private final BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();

    LiveWorldObservation(ClientLevel level, @Nullable WorldSchematic schematic) {
        this.level = level;
        this.schematic = schematic;
    }

    @Override
    public boolean hasChunk(int chunkX, int chunkZ) {
        return this.level.hasChunk(chunkX, chunkZ);
    }

    @Override
    public boolean hasCandidatesInChunk(
            ScanIntent intent,
            int chunkX,
            int chunkZ,
            boolean breakExtraBlocks
    ) {
        if (!this.level.hasChunk(chunkX, chunkZ)) return false;
        if (intent != ScanIntent.PRINT || this.schematic == null) return true;
        var chunk = this.schematic.getChunk(chunkX, chunkZ);
        return chunk.getState().atLeast(ChunkSchematicState.FILLED) && (breakExtraBlocks || !chunk.isEmpty());
    }

    @Override
    public BlockState worldState(BlockPos pos) {
        return this.level.getBlockState(pos);
    }

    @Override
    public boolean hasCandidatesInSection(ScanIntent intent, int sectionX, int sectionY,
                                          int sectionZ, boolean breakExtraBlocks) {
        if (intent == ScanIntent.FLUID || intent == ScanIntent.TRENCH) {
            var source = this.level.getChunkSource();
            // Headless or incomplete observations are unknown, never proof of an empty section.
            if (source == null) return true;
            var chunk = source.getChunk(sectionX, sectionZ, ChunkStatus.FULL, false);
            if (chunk == null) return false;
            var sections = chunk.getSections();
            int index = this.level.getSectionIndexFromSectionY(sectionY);
            if (index < 0 || index >= sections.length || sections[index] == null) return false;
            var section = sections[index];
            //#if MC >= 260100
            return section.hasFluid() || intent == ScanIntent.TRENCH
                    && section.maybeHas(state -> state.is(Blocks.BUDDING_AMETHYST));
            //#else
            // 1.21.11 tracks ticking fluids only. Check the palette for still and waterlogged states as well.
            //$$ return intent == ScanIntent.TRENCH ? section.maybeHas(ScanClassifier::isTrenchTarget)
            //$$         : section.maybeHas(state -> !state.getFluidState().isEmpty());
            //#endif
        }
        if (intent != ScanIntent.PRINT || breakExtraBlocks || this.schematic == null) return true;
        var chunk = this.schematic.getChunk(sectionX, sectionZ);
        var sections = chunk.getSections();
        int index = ((sectionY << 4) - this.schematic.getMinY()) >> 4;
        return index >= 0 && index < sections.length && sections[index] != null && !sections[index].hasOnlyAir();
    }

    @Override
    public @Nullable BlockState schematicState(BlockPos pos) {
        return this.schematic == null ? null : this.schematic.getBlockState(pos);
    }

    @Override
    public boolean hasFillSupport(BlockPos pos) {
        for (Direction direction : DIRECTIONS) {
            this.neighbor.set(
                    pos.getX() + direction.getStepX(),
                    pos.getY() + direction.getStepY(),
                    pos.getZ() + direction.getStepZ()
            );
            BlockState state = this.level.getBlockState(this.neighbor);
            if (!state.isAir()
                    && !(state.getBlock() instanceof LiquidBlock)
                    && !BlockUtils.isReplaceable(state)) {
                return true;
            }
        }
        return false;
    }
}
