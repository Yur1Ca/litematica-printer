package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.printer.PrinterBox;

import java.util.List;

/** Adapts a feature module to the scan coordinator without owning source progress. */
final class ModuleScanHost implements ModuleScanCoordinator.Host {
    private final FeatureModuleBase module;

    ModuleScanHost(FeatureModuleBase module) { this.module = module; }

    @Override public List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox) {
        return this.module.getScanSourceBoxes(interactionBox);
    }

    @Override public FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox) {
        return this.module.runIteration(interactionBox);
    }
}
