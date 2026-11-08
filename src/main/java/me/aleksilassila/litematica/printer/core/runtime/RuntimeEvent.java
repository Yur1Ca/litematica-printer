package me.aleksilassila.litematica.printer.core.runtime;

public sealed interface RuntimeEvent permits RuntimeEvent.EpochChanged, RuntimeEvent.BlockUpdated,
        RuntimeEvent.MaterialAvailabilityChanged, RuntimeEvent.BlockActionAcknowledged {
    record EpochChanged(RuntimeEpoch previous, RuntimeEpoch current, String reason) implements RuntimeEvent {
    }

    /** Exact server world update. Consumers may also wake immediate neighbours when required. */
    record BlockUpdated(int x, int y, int z) implements RuntimeEvent {
    }

    /** An external material source became usable without changing the player's inventory. */
    record MaterialAvailabilityChanged() implements RuntimeEvent {
    }

    /** An ordered server player-tick acknowledgement for sequenced predicted actions. */
    record BlockActionAcknowledged(Object connection, int sequence) implements RuntimeEvent { }
}
