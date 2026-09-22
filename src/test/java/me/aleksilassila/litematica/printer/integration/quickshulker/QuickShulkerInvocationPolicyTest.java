package me.aleksilassila.litematica.printer.integration.quickshulker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuickShulkerInvocationPolicyTest {
    @Test
    void anArmedContainerScreenTokenSuppressesExactlyTheContainerScreen() {
        assertTrue(QuickShulkerInvocationPolicy.shouldSuppressScreen(1, true));
        assertFalse(QuickShulkerInvocationPolicy.shouldSuppressScreen(0, true));
        assertFalse(QuickShulkerInvocationPolicy.shouldSuppressScreen(1, false));
    }

    @Test
    void suppressingAnOwnedContainerScreenConsumesItsLegacyToken() {
        assertEquals(0, QuickShulkerInvocationPolicy.consumeScreenToken(1));
        assertEquals(1, QuickShulkerInvocationPolicy.consumeScreenToken(2));
        assertEquals(0, QuickShulkerInvocationPolicy.consumeScreenToken(0));
    }

    @Test
    void allowsAdventureButNotCreativeOrSpectatorPickBlock() {
        assertTrue(QuickShulkerInvocationPolicy.allowsPickBlock(false, false));
        assertFalse(QuickShulkerInvocationPolicy.allowsPickBlock(true, false));
        assertFalse(QuickShulkerInvocationPolicy.allowsPickBlock(false, true));
    }
}
