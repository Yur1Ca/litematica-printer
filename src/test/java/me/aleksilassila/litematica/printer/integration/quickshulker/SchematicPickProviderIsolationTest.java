//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.world.item.Items;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static me.aleksilassila.litematica.printer.integration.quickshulker.CancelledSwapLifecycleTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real input wrappers, common hook, bridge and coordinator; replace only provider transport. */
class SchematicPickProviderIsolationTest {
    private static final List<String> PROVIDERS = List.of("take_it_out", "void_warehouse", "quick_shulker", "chest_tracker");

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static Stream<Arguments> routes() {
        return Stream.of("print", "middle_click", "easy_place")
                .flatMap(entry -> PROVIDERS.stream().map(provider -> Arguments.of(entry, provider)));
    }

    @ParameterizedTest @MethodSource("routes")
    void foreignCallsCannotUseAnyProviderButExplicitRequestsKeepTheirFallbackOrder(String entry, String available)
            throws Exception {
        boolean previousQuick = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        try (var f = new LegacyEasyPlaceTargetTest.Fixture()) {
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            f.useRealRequests = true;
            set(f.game.runtime, PrinterRuntime.class, "actionBroker", new ActionBroker(f.game.runtime, new ActionManager()));
            var calls = new ArrayList<String>();
            var sent = new ArrayList<MaterialRequest>();
            var providers = PROVIDERS.stream().map(id -> (InventoryProvider) new InventoryProvider() {
                @Override public String id() { return id; }
                @Override public boolean supports(MaterialRequest request) {
                    return InventoryProvider.super.supports(request) || id.equals("take_it_out");
                }
                @Override public MaterialReservation request(MaterialRequest request) {
                    calls.add(id);
                    if (id.equals(available)) sent.add(request);
                    return new MaterialReservation(request.token(), id.equals(available)
                            ? MaterialReservation.State.PENDING : MaterialReservation.State.UNAVAILABLE);
                }
                @Override public MaterialReservation status(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.PENDING);
                }
            }).toList();
            var coordinator = new MaterialRequestCoordinator(providers);
            set(f.game.runtime, PrinterRuntime.class, "materialRequests", coordinator);

            assertTrue(f.programmaticPick().isCancelled());
            assertEquals(List.of("take_it_out"), calls, "TIO's own call must never enter another provider");
            assertEquals(available.equals("take_it_out"), coordinator.isBusy());
            if (!sent.isEmpty()) assertEquals(MaterialRequest.Source.TAKE_IT_OUT_PICK, sent.get(0).source());
            coordinator.reset();
            calls.clear();
            sent.clear();

            switch (entry) {
                case "print" -> assertEquals(MaterialReservation.State.PENDING,
                        QuickShulkerBridge.requestItem(Items.STONE, MaterialRequest.Source.PRINT).state());
                case "middle_click" -> f.middleClick();
                case "easy_place" -> assertTrue(f.rightClick());
                default -> fail("Unknown entry " + entry);
            }
            assertEquals(PROVIDERS.subList(0, PROVIDERS.indexOf(available) + 1), calls);
            assertEquals(1, sent.size());
            assertSame(Items.STONE, sent.get(0).preferredItem());
            assertEquals(entry.equals("print") ? MaterialRequest.Source.PRINT : MaterialRequest.Source.PICK_BLOCK,
                    sent.get(0).source());
            long token = coordinator.activeToken();

            for (int tick = 0; tick < 60; tick++) {
                f.programmaticPick();
                coordinator.tick();
            }
            assertEquals(token, coordinator.activeToken(), "Foreign calls must not replace a delayed legitimate request");
            assertEquals(1, sent.size());
            assertEquals(PROVIDERS.subList(0, PROVIDERS.indexOf(available) + 1), calls);
        } finally {
            Configs.Placement.QUICK_SHULKER.setBooleanValue(previousQuick);
        }
    }
}
//#endif
