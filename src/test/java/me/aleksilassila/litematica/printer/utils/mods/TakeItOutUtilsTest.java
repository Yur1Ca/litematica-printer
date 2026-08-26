package me.aleksilassila.litematica.printer.utils.mods;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class TakeItOutUtilsTest {
    @Test
    void unavailableOptionalIntegrationDoesNotExposeRemoteItems() {
        assertTrue(TakeItOutUtils.getAvailableItems().isEmpty());
    }
}
