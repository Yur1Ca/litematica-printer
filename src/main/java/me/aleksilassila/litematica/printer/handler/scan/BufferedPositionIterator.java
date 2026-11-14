package me.aleksilassila.litematica.printer.handler.scan;

import net.minecraft.core.BlockPos;
import java.util.Iterator;
import java.util.function.Predicate;

/** Read-only lookahead over an already built finite snapshot, never a live scan. */
public interface BufferedPositionIterator extends Iterator<BlockPos> {
    BlockPos peek(Predicate<BlockPos> eligible);
}
