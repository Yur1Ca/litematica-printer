package me.aleksilassila.litematica.printer.handler.handlers.print;

/** Explicit lifecycle for one ice-to-water print target. */
public enum WaterTaskStage {
    RESERVED,
    WAIT_SUPPORT,
    REMOVE_EXISTING,
    WAIT_REMOVE_CONFIRM,
    PLACE_ICE,
    WAIT_ICE_CONFIRM,
    BREAK_ICE,
    WAIT_WATER_CONFIRM,
    PLACE_FINAL_BLOCK,
    WAIT_FINAL_CONFIRM,
    WAIT_FINAL_CHANGE,
    RETRY_WAIT,
    COMPLETE;

    boolean waitsForWorldUpdate() {
        return this == WAIT_SUPPORT
                || this == WAIT_REMOVE_CONFIRM
                || this == WAIT_ICE_CONFIRM
                || this == WAIT_WATER_CONFIRM
                || this == WAIT_FINAL_CONFIRM
                || this == WAIT_FINAL_CHANGE;
    }
}
