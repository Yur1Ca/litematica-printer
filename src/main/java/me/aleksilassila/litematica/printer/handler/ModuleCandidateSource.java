package me.aleksilassila.litematica.printer.handler;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import me.aleksilassila.litematica.printer.handler.scan.ScanIntent;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;

import java.util.List;
import java.util.function.Predicate;

/** Connects a feature's predicates to the shared scan engine. */
final class ModuleCandidateSource {
    private ModuleCandidateSource() {
    }

    static Iterable<BlockPos> raw(FeatureModuleBase module, PrinterBox box, Predicate<BlockPos> candidatePredicate) {
        return module.scanEngine.rawIterable(
                module.getId() + "_raw", box, module.player,
                module.getScanGuardLimit(), candidatePredicate);
    }

    static Iterable<BlockPos> cached(
            FeatureModuleBase module,
            PrinterBox interactionBox,
            ScanIntent intent,
            Predicate<BlockPos> candidatePredicate
    ) {
        List<PrinterBox> sourceBoxes = module.getScanSourceBoxes(interactionBox);
        if (sourceBoxes.isEmpty()) return List.of();
        Predicate<BlockPos> selection = module.createSelectionRangePredicate();
        Predicate<BlockPos> reach = module.createScanReachPredicate();
        return module.scanEngine.iterable(
                module.getId(), sourceBoxes, module.level,
                SchematicWorldHandler.getSchematicWorld(), module.player,
                module.getScanGuardLimit(), intent, candidatePredicate,
                pos -> reach.test(pos) && selection.test(pos));
    }
}
