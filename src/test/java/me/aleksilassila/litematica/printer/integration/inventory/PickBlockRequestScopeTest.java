package me.aleksilassila.litematica.printer.integration.inventory;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

class PickBlockRequestScopeTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void nativeResultIsPreservedAndAuthorizationEndsAtReturn(boolean result) {
        assertFalse(PickBlockRequestScope.isUserPick());
        assertEquals(result, PickBlockRequestScope.withUserPick(() -> {
            assertTrue(PickBlockRequestScope.isUserPick());
            return result;
        }));
        assertFalse(PickBlockRequestScope.isUserPick());
    }

    @Test void nestedFailureRestoresTheOuterScopeAndDoesNotAuthorizeALaterRequest() {
        var failure = new IllegalStateException("native failure");
        assertTrue(PickBlockRequestScope.withUserPick(() -> {
            assertSame(failure, assertThrows(IllegalStateException.class,
                    () -> PickBlockRequestScope.withUserPick(() -> { throw failure; })));
            assertTrue(PickBlockRequestScope.isUserPick());
            return true;
        }));
        assertFalse(PickBlockRequestScope.isUserPick());
        assertSame(failure, assertThrows(IllegalStateException.class,
                () -> PickBlockRequestScope.withUserPick(() -> { throw failure; })));
        assertFalse(PickBlockRequestScope.isUserPick());
    }

    @Test void anUnrelatedThreadCannotInheritUserInputAuthorization() {
        PickBlockRequestScope.withUserPick(() -> {
            assertFalse(CompletableFuture.supplyAsync(PickBlockRequestScope::isUserPick).join());
            assertTrue(PickBlockRequestScope.isUserPick());
            return true;
        });
        assertFalse(PickBlockRequestScope.isUserPick());
    }
}
