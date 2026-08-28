package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.core.BlockPos;

/**
 * Compatibility facade for callers that have not yet received the runtime-owned bedrock engine.
 * All mutable state lives in the current {@link PrinterRuntime} component.
 */
public final class BedrockController {
    private BedrockController() {
    }

    private static BedrockEngine engine() {
        return RuntimeAccess.get().bedrockEngine();
    }

    public static void reset() {
        engine().reset();
    }

    public static void clearHorizontalLookState() {
        engine().clearHorizontalLookState();
    }

    public static void tick() {
        engine().tick();
    }

    public static boolean hasActiveWork() {
        return engine().hasActiveWork();
    }

    public static boolean hasPendingScanWork() {
        return engine().hasPendingScanWork();
    }

    public static int getPendingScanWorkCount() {
        return engine().getPendingScanWorkCount();
    }

    public static boolean canScanForTargets() {
        return engine().canScanForTargets();
    }

    public static boolean canSubmitInCurrentWindow() {
        return engine().canSubmitInCurrentWindow();
    }

    public static boolean canAccept(BlockPos pos) {
        return engine().canAccept(pos);
    }

    public static boolean isPositionOnRetryCooldown(BlockPos pos) {
        return engine().isPositionOnRetryCooldown(pos);
    }

    public static int getSchedulingPenalty(BlockPos pos) {
        return engine().getSchedulingPenalty(pos);
    }

    public static int getPredictedMachineOverlapPenalty(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement
    ) {
        return engine().getPredictedMachineOverlapPenalty(bedrockPos, layout, placement);
    }

    public static boolean shouldSkipSchedulingHotspot(BlockPos pos) {
        return engine().shouldSkipSchedulingHotspot(pos);
    }

    public static boolean submit(BlockPos pos) {
        return engine().submit(pos);
    }

    static boolean isPositionReservedByOtherTarget(BlockPos pos, BedrockTarget self) {
        return engine().isPositionReservedByOtherTarget(pos, self);
    }

    static boolean isTorchPlacementReservedByOtherTarget(
            BedrockTorchPlacement placement,
            BedrockTarget self
    ) {
        return engine().isTorchPlacementReservedByOtherTarget(placement, self);
    }

    public static void clearSubmissionPlans() {
        engine().clearSubmissionPlans();
    }

    public static void primeSubmissionPlan(
            BlockPos bedrockPos,
            BedrockMachineLayout layout,
            BedrockTorchPlacement placement,
            BlockPos slimePos
    ) {
        engine().primeSubmissionPlan(bedrockPos, layout, placement, slimePos);
    }

    public static BedrockEngine.HudSnapshot getHudSnapshot() {
        return engine().getHudSnapshot();
    }
}
