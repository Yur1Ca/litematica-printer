package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;

/**
 * Failure-recovery fence, scoped to the actual connection rather than a runtime epoch.
 * Vanilla processes REQUEST_STATS on the server thread and replies exactly once, even
 * when no stats changed. Count all requests so an older Stats-screen reply cannot finish
 * our fence. Ordinary container snapshots have no corresponding request identifier.
 */
public final class InventoryRecoveryFence {
    private static final Map<Object, State> CONNECTIONS = new WeakHashMap<>();

    private InventoryRecoveryFence() { }

    /** Observe inventory-changing requests before they enter the transport queue. */
    public static void inputSent(Object connection, Packet<?> packet) {
        if (packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClickPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundContainerClosePacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlaceRecipePacket
                // World interactions can fill the empty source slot (armor stands, bookshelves).
                || packet instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket
                //#if MC >= 12104
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPickItemFromBlockPacket
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPickItemFromEntityPacket
                //#else
                //$$ || packet instanceof net.minecraft.network.protocol.game.ServerboundPickItemPacket
                //#endif
                || packet instanceof net.minecraft.network.protocol.game.ServerboundPlayerActionPacket action
                    && (action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND
                        || action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ITEM
                        || action.getAction() == net.minecraft.network.protocol.game.ServerboundPlayerActionPacket.Action.DROP_ALL_ITEMS)) {
            invalidate(connection);
        }
    }

    public static synchronized Ticket prepare(Object connection) {
        State state = CONNECTIONS.computeIfAbsent(connection, ignored -> new State());
        Ticket ticket = new Ticket(state);
        state.pending = ticket;
        return ticket;
    }

    /** Called before an input enters the transport queue, or when the server changes menus. */
    public static synchronized void invalidate(Object connection) {
        State state = CONNECTIONS.get(connection);
        if (state != null) state.inputGeneration++;
    }

    /** Called exactly once per packet, on the transport's ordered write path. */
    public static void sent(Object connection, Packet<?> packet) {
        if (!(packet instanceof ServerboundClientCommandPacket command)
                || command.getAction() != ServerboundClientCommandPacket.Action.REQUEST_STATS) return;
        synchronized (InventoryRecoveryFence.class) {
            State state = CONNECTIONS.computeIfAbsent(connection, ignored -> new State());
            long ordinal = ++state.requests;
            if (state.pending != null && state.pending.packet == packet) {
                state.pending.ordinal = ordinal;
                state.pending = null;
            }
        }
    }

    /** Called on the client thread after vanilla applied the corresponding statistics reply. */
    public static synchronized void received(Object connection) {
        State state = CONNECTIONS.get(connection);
        if (state != null && state.replies < state.requests) state.replies++;
    }

    private static final class State {
        long requests;
        long replies;
        long inputGeneration;
        Ticket pending;
    }

    public static final class Ticket {
        private final State state;
        private final long inputGeneration;
        private final ServerboundClientCommandPacket packet = new ServerboundClientCommandPacket(
                ServerboundClientCommandPacket.Action.REQUEST_STATS);
        private long ordinal;

        private Ticket(State state) {
            this.state = state;
            // Freeze before enqueueing the marker, NOT when it is eventually written.
            this.inputGeneration = state.inputGeneration;
        }

        public ServerboundClientCommandPacket packet() { return packet; }

        public boolean complete() {
            synchronized (InventoryRecoveryFence.class) {
                return ordinal > 0 && state.replies >= ordinal;
            }
        }

        public boolean invalidated() {
            synchronized (InventoryRecoveryFence.class) {
                return state.inputGeneration != inputGeneration;
            }
        }
    }
}
