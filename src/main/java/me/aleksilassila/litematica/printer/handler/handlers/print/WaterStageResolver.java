package me.aleksilassila.litematica.printer.handler.handlers.print;

/** Pure transition table for the water workflow; safe to characterize without a live world. */
final class WaterStageResolver {
    enum Observation {
        COMPLETE,
        WATER_READY,
        DRY_FINAL_BLOCK,
        ICE,
        REPLACEABLE_WITH_SUPPORT,
        WITHOUT_WATER_SUPPORT,
        BLOCKED
    }

    private WaterStageResolver() {
    }

    static WaterTaskStage resolve(
            WaterTaskStage stage,
            Observation observation,
            boolean finalBlockRequired,
            boolean stalled,
            boolean retryReady
    ) {
        if (observation == Observation.COMPLETE) {
            return WaterTaskStage.COMPLETE;
        }
        if (observation == Observation.WITHOUT_WATER_SUPPORT) {
            return WaterTaskStage.WAIT_SUPPORT;
        }
        if (observation == Observation.DRY_FINAL_BLOCK
                && (stage == WaterTaskStage.WAIT_FINAL_CHANGE
                || stage == WaterTaskStage.WAIT_FINAL_CONFIRM && stalled)) {
            return WaterTaskStage.WAIT_FINAL_CHANGE;
        }
        if (stage == WaterTaskStage.RETRY_WAIT && !retryReady) {
            return stage;
        }
        if (stage == WaterTaskStage.WAIT_FINAL_CONFIRM && !stalled) {
            return stage;
        }
        if (stage == WaterTaskStage.WAIT_ICE_CONFIRM
                && observation != Observation.ICE
                && !stalled) {
            return stage;
        }
        if (observation == Observation.WATER_READY) {
            return finalBlockRequired ? WaterTaskStage.PLACE_FINAL_BLOCK : WaterTaskStage.COMPLETE;
        }
        if (stage == WaterTaskStage.WAIT_WATER_CONFIRM && !stalled) {
            return stage;
        }
        if (observation == Observation.ICE) {
            return WaterTaskStage.BREAK_ICE;
        }
        if (observation == Observation.REPLACEABLE_WITH_SUPPORT) {
            return WaterTaskStage.PLACE_ICE;
        }
        if (stage == WaterTaskStage.WAIT_REMOVE_CONFIRM && !stalled) {
            return stage;
        }
        return WaterTaskStage.REMOVE_EXISTING;
    }
}
