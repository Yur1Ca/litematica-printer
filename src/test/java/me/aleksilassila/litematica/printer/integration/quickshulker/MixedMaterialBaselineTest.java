//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.List;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;

class MixedMaterialBaselineTest {
    @BeforeAll static void bootstrap() throws Exception { CancelledSwapLifecycleTest.bootstrap(); }

    @ParameterizedTest @EnumSource(value = MaterialRequest.Source.class, names = {"PRINT", "FILL", "FLUID", "MINE"})
    void manualPickAndOtherFeaturesWaitBehindOneDelayedMaterialRequest(MaterialRequest.Source source) throws Exception {
        try (var f = new PickBlockInventoryExclusionTest.Fixture()) {
            f.coordinator.request(Items.STONE, source);
            long token = f.coordinator.activeToken();
            for (int tick = 0; tick < 60; tick++) {
                for (var other : List.of(MaterialRequest.Source.PRINT, MaterialRequest.Source.FILL,
                        MaterialRequest.Source.FLUID, MaterialRequest.Source.MINE)) {
                    if (other != source) {
                        f.coordinator.request(Items.DIRT, other);
                        f.coordinator.cancelOwner(other.name().toLowerCase(java.util.Locale.ROOT));
                    }
                }
                assertTrue(QuickShulkerBridge.handlePickBlock(f.player, Items.DIAMOND));
                f.coordinator.tick();
                assertEquals(token, f.coordinator.activeToken());
                assertTrue(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            }
            assertEquals(List.of(Items.STONE), f.api.sent);
            // This fixture covers owner cancellation and manual entry while disabled. The real
            // total-switch initialization callback is exercised separately by WorkSwitchBaselineTest.
            Configs.Core.WORK_SWITCH.setBooleanValue(false);
            f.coordinator.cancelOwner(source.name().toLowerCase(java.util.Locale.ROOT));
            f.player.inventory.setItem(2, new ItemStack(Items.STONE, 16)); // late inventory arrival
            f.tick(100);
            assertFalse(f.coordinator.isBusy());
            assertEquals(List.of(Items.STONE), f.api.sent);
            assertTrue(QuickShulkerBridge.handlePickBlock(f.player, Items.DIAMOND));
            assertNotEquals(token, f.coordinator.activeToken());
            assertEquals(List.of(Items.STONE, Items.DIAMOND), f.api.sent);
            f.resetScope("client_scope_changed");
            assertFalse(f.coordinator.isBusy());
            assertFalse(f.broker.isResourceHeld(ResourceLease.INVENTORY));
            f.tick(100);
            assertEquals(List.of(Items.STONE, Items.DIAMOND), f.api.sent);
        }
    }
}
//#endif
