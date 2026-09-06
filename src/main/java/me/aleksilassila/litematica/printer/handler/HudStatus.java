package me.aleksilassila.litematica.printer.handler;

/** Stable, language-independent status identifiers consumed by the Printer HUD. */
public final class HudStatus {
    public static final String IDLE = "idle";
    public static final String RUNNING = "running";
    public static final String FILL_LIST_EMPTY = "fill_list_empty";
    public static final String MAIN_HAND_NO_BLOCK = "main_hand_no_block";
    public static final String LIST_NO_MATCH = "list_no_match";
    public static final String FALLING_NO_SUPPORT = "falling_no_support";
    public static final String WAITING_RETRIEVAL = "waiting_retrieval";
    public static final String MISSING_FILL_MATERIAL = "missing_fill_material";
    public static final String MISSING_FLUID_FILL_BLOCK = "missing_fluid_fill_block";
    public static final String MISSING_MATERIAL = "missing_material";
    public static final String NO_FLUID_BLOCK = "no_fluid_block";
    public static final String NO_FLUID_CONFIG = "no_fluid_config";
    public static final String NO_VALID_FACE = "no_valid_face";
    public static final String ACTION_QUEUE_BUSY = "action_queue_busy";
    public static final String WAITING_LOOK = "waiting_look";
    public static final String PLACEMENT_NOT_SENT = "placement_not_sent";
    public static final String WAITING_ITEM_SYNC = "waiting_item_sync";
    public static final String RTT_THROTTLED = "rtt_throttled";
    public static final String MOVED_STALE = "moved_stale";
    public static final String HELD_ITEM_CHANGED = "held_item_changed";
    public static final String RESERVE_LIMIT = "reserve_limit";
    public static final String CLIENT_NOT_READY = "client_not_ready";
    public static final String INTERACTION_REJECTED = "interaction_rejected";
    public static final String ACTION_NOT_QUEUED = "action_not_queued";
    public static final String ACTION_NOT_SENT = "action_not_sent";
    public static final String WAITING_SERVER_CONFIRM = "waiting_server_confirm";
    public static final String BREAKING = "breaking";
    public static final String MINING_INTERRUPTED = "mining_interrupted";
    public static final String BREAKING_FAILED = "breaking_failed";

    private HudStatus() {
    }
}
