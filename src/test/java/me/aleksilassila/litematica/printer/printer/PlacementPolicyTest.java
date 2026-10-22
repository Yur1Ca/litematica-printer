package me.aleksilassila.litematica.printer.printer;

import me.aleksilassila.litematica.printer.feature.FeatureActionPolicies;
import me.aleksilassila.litematica.printer.config.Configs;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementPolicyTest {
    @Test void reserveIsEvaluatedAtExecutionAndAppliesOnlyToPrint() {
        boolean enabled = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        int count = Configs.Print.PRINT_RESERVE_ITEM_COUNT.getIntegerValue();
        try {
            var print = FeatureActionPolicies.forSource(ActionManager.ActionSource.PRINT);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            assertEquals(-1, print.reserveCount().getAsInt());
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(true);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(32);
            assertEquals(32, print.reserveCount().getAsInt());
            for (var source : ActionManager.ActionSource.values()) {
                if (source != ActionManager.ActionSource.PRINT) {
                    assertEquals(-1, FeatureActionPolicies.forSource(source).reserveCount().getAsInt());
                    assertFalse(FeatureActionPolicies.forSource(source).easyPlaceProtocol());
                }
            }
            assertTrue(print.easyPlaceProtocol());
            assertTrue(FeatureActionPolicies.forSource(ActionManager.ActionSource.MINE).suppressAnvilScreen(),
                    "trench placement keeps the previous FILL screen policy");
            assertFalse(FeatureActionPolicies.forSource(ActionManager.ActionSource.FLUID).suppressAnvilScreen());
        } finally {
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(enabled);
            Configs.Print.PRINT_RESERVE_ITEM_COUNT.setIntegerValue(count);
        }
    }
}
