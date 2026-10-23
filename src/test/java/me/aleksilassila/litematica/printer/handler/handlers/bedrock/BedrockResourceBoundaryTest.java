package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import com.google.common.collect.ImmutableList;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.action.ResourceLease;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.InventoryAvailabilityTracker;
import me.aleksilassila.litematica.printer.handler.TickContext;
import me.aleksilassila.litematica.printer.handler.handlers.BedrockHandler;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.concurrent.atomic.AtomicBoolean;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real scheduler, broker and delayed placement; only feature input and game transport are headless. */
class BedrockResourceBoundaryTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final String RESTOCK_OWNER = "same_tick_restock";

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test
    void defersExistingRetryWhenRestockStartsInSameTick() throws Exception {
        this.assertRetryWaitsForEarlierModule(EnumSet.of(
                ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER), false);
    }

    @Test
    void iterationCompletionAlsoDefersExistingRetryWhenRestockStartsInSameTick() throws Exception {
        this.assertRetryWaitsForEarlierModule(EnumSet.of(
                ResourceLease.MAIN_HAND, ResourceLease.INVENTORY, ResourceLease.CONTAINER), true);
    }

    @ParameterizedTest @EnumSource(ResourceLease.class)
    void defersExistingRetryForEachConflictingResource(ResourceLease resource) throws Exception {
        this.assertRetryWaitsForEarlierModule(EnumSet.of(resource), false);
    }

    private void assertRetryWaitsForEarlierModule(EnumSet<ResourceLease> resources, boolean finishIteration) throws Exception {
        boolean oldWork = Configs.Core.WORK_SWITCH.getBooleanValue();
        boolean oldLag = Configs.Core.LAG_CHECK.getBooleanValue();
        boolean oldAvailability = Configs.Core.RENDER_ONLY_HOLDING_ITEMS.getBooleanValue();
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            Configs.Core.WORK_SWITCH.setBooleanValue(true);
            Configs.Core.LAG_CHECK.setBooleanValue(false);
            Configs.Core.RENDER_ONLY_HOLDING_ITEMS.setBooleanValue(false);
            var broker = new ActionBroker(f.runtime, new ActionManager());
            set(f.runtime, PrinterRuntime.class, "actionBroker", broker);
            set(f.runtime, PrinterRuntime.class, "inventoryAvailability", new InventoryAvailabilityTracker());
            set(f.runtime, PrinterRuntime.class, "missingMaterials", new MissingMaterialTracker());
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard",
                    new InventorySwitchGuard(f.client, f.runtime::currentTick));
            set(f.client.player, Player.class, "inventoryMenu",
                    new InventoryMenu(f.client.player.getInventory(), true, f.client.player));
            f.client.player.containerMenu = f.client.player.inventoryMenu;

            var engine = f.engine();
            var placer = (BedrockPlacer) get(engine, BedrockEngine.class, "placer");
            placer.schedulePistonRetry(TARGET, TARGET.above(), Direction.DOWN);

            // The external provider's network work is outside this test. Its actual broker
            // lease is acquired by an earlier feature, after the scheduler's shared precheck.
            var acquireOnce = new AtomicBoolean(true);
            var earlier = f.allocate(InputModule.class);
            set(earlier, FeatureModuleBase.class, "id", "print");
            earlier.input = () -> {
                if (acquireOnce.getAndSet(false)) assertTrue(broker.tryAcquire(RESTOCK_OWNER, resources, 0));
            };
            var reservedGuiSlot = f.allocate(InputModule.class);
            set(reservedGuiSlot, FeatureModuleBase.class, "id", "gui");
            reservedGuiSlot.input = () -> { };
            var bedrock = f.allocate(ContinuationModule.class);
            bedrock.finishIteration = finishIteration;
            set(bedrock, FeatureModuleBase.class, "id", "bedrock");
            set(bedrock, FeatureModuleBase.class, "actionBroker", broker);
            set(bedrock, BedrockHandler.class, "candidatePlanner", new BedrockCandidatePlanner(null, null));

            Class<?> schedulerType = Class.forName("me.aleksilassila.litematica.printer.handler.TickScheduler");
            var constructor = schedulerType.getDeclaredConstructor(ImmutableList.class, PrinterRuntime.class);
            constructor.setAccessible(true);
            Object scheduler = constructor.newInstance(ImmutableList.of(reservedGuiSlot, earlier, bedrock), f.runtime);
            var tick = schedulerType.getDeclaredMethod("tick");
            tick.setAccessible(true);

            f.tick(8);
            tick.invoke(scheduler);
            assertTrue(broker.isResourceHeld(resources.iterator().next()));
            assertTrue(f.mode.hits.isEmpty(), "Existing bedrock work must not place through another owner's lease");

            broker.releaseOwner(RESTOCK_OWNER);
            f.tick(9);
            tick.invoke(scheduler);
            assertEquals(1, f.mode.hits.size(), "Releasing the lease resumes the existing retry without resubmission");
            assertEquals(TARGET.above(), f.serverPlacementPosition(f.mode.hits.get(0)));
        } finally {
            Configs.Core.WORK_SWITCH.setBooleanValue(oldWork);
            Configs.Core.LAG_CHECK.setBooleanValue(oldLag);
            Configs.Core.RENDER_ONLY_HOLDING_ITEMS.setBooleanValue(oldAvailability);
        }
    }

    static class InputModule extends FeatureModuleBase {
        Runnable input;
        InputModule() { super(null, "input", null, null, null, false); }
        @Override public void tick(TickContext context) { this.input.run(); }
    }

    static class ContinuationModule extends BedrockHandler {
        boolean finishIteration;
        ContinuationModule() { super(null); }
        // Keep the real continuation entry while bypassing unrelated schematic/HUD setup.
        @Override public void tick(TickContext context) {
            if (this.finishIteration) this.stopIteration(false);
            else this.canIterate();
        }
    }
}
