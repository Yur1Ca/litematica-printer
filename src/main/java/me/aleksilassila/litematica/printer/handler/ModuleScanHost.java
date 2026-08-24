package me.aleksilassila.litematica.printer.handler;

import me.aleksilassila.litematica.printer.printer.PrinterBox;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Adapts a feature module to the scan coordinator without making the module the scan engine. */
final class ModuleScanHost implements ModuleScanCoordinator.Host {
    private final FeatureModuleBase module;

    ModuleScanHost(FeatureModuleBase module) {
        this.module = module;
    }

    @Override
    public List<PrinterBox> scanSourceBoxes(PrinterBox interactionBox) {
        return this.module.getScanSourceBoxes(interactionBox);
    }

    @Override
    public @Nullable PrinterBox scanSourceBox(PrinterBox interactionBox) {
        return this.module.getScanSourceBox(interactionBox);
    }

    @Override
    public FeatureModuleBase.IterationOutcome runIteration(PrinterBox interactionBox) {
        return this.module.runIteration(interactionBox);
    }

    @Override
    public boolean hasRunnableTargets() {
        return this.module.hasRunnableIterationWork();
    }

    @Override
    public boolean hasWaitingTargets() {
        return this.module.hasWaitingIterationWork();
    }

    @Override
    public boolean usesDirtyRegionWakeup() {
        return this.module.usesDirtyRegionWakeup();
    }

    @Override
    public double playerX() {
        return this.module.player.getX();
    }

    @Override
    public double playerEyeY() {
        return this.module.player.getEyeY();
    }

    @Override
    public double playerZ() {
        return this.module.player.getZ();
    }
}
