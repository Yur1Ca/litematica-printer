//#if MC == 260100 || MC == 260300
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import fi.dy.masa.litematica.world.SchematicWorldHandler;
import java.util.ArrayList;
import java.util.List;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.core.runtime.FeatureLifecycle;
import me.aleksilassila.litematica.printer.core.runtime.RuntimeEpoch;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintTaskBuildResult;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.PlayerInventoryProvider;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.action.ActionBroker;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.get;
import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

class WaterIcePlacementQueueTest {
    private static final BlockPos POS = new BlockPos(0, 64, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void selectsAReachableSupportAtTheEdgeOfARow() throws Exception {
        boolean sort = Configs.Print.PRINT_SORT_SIDES.getBooleanValue();
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(true);
            set(f.client.player, Entity.class, "position", new Vec3(5.75D, POS.getY() + 0.5D, 0.5D));
            f.level.blocks.put(POS.west(), Blocks.STONE.defaultBlockState());
            var ice = f.build(POS).action();
            assertTrue(f.client.player.isWithinBlockInteractionRange(POS, 1.0D));
            assertFalse(f.client.player.isWithinBlockInteractionRange(POS.west(), 1.0D));
            assertEquals(Direction.WEST, ice.getValidSide(f.level, POS));
            assertEquals(Direction.DOWN, ice.getValidSide(f.level, POS, f.client.player));
            f.level.blocks.put(POS.below(), Blocks.WATER.defaultBlockState());
            assertNull(ice.getValidSide(f.level, POS, f.client.player));
        } finally {
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(sort);
        }
    }

    @ParameterizedTest @ValueSource(strings = {"flow", "source", "secondSource", "finished", "schematicReplaced", "schematicCleared", "skipWaterlogged"})
    void iceRechecksTheLiveTargetWhenItsClickResumesAfterRotation(String afterRotation) throws Exception {
        boolean precision = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        boolean reserve = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        var holder = SchematicWorldHandler.INSTANCE;
        Object previousSchematic = get(holder, SchematicWorldHandler.class, "world");
        try (var f = new WaterSupportRegressionTest.WaterFixture()) {
            set(holder, SchematicWorldHandler.class, "world", f.schematic);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            f.installCachedClient(Reference.class.getDeclaredField("MINECRAFT"));
            set(f.client.player, Entity.class, "position", Vec3.atCenterOf(POS.north()));
            f.client.player.getInventory().setItem(0, new ItemStack(Items.ICE, 8));
            f.mining.hits = new ArrayList<>();
            f.mining.predictions = new ArrayList<>();
            var life = new FeatureLifecycle();
            life.observe("print", true);
            var broker = new ActionBroker(() -> RuntimeEpoch.INITIAL, () -> life, new ActionManager());
            var executor = new PrintPlacementExecutor(broker, new CooldownUtils(), f.runtime.inventorySwitchGuard(),
                    f.runtime.hudStats(), new MissingMaterialTracker(), new LitematicaAdapter(),
                    new FallingPlacementTracker(), new MaterialRequestCoordinator(List.of(new PlayerInventoryProvider(f.client))));
            var flow = Blocks.WATER.defaultBlockState().setValue(LiquidBlock.LEVEL, 1);
            f.level.blocks.put(POS, flow);
            if ("secondSource".equals(afterRotation)) f.level.blocks.put(POS.west(), Blocks.WATER.defaultBlockState());
            var context = f.context(POS);
            var ice = f.build(POS);
            ice.action().setLookDirection(Direction.EAST).setNeedWaitModifyLook();
            assertEquals(PrintPlacementResult.TaskEvent.QUEUED, executor.execute(context, ice.action(), ice.actionHandle()).taskEvent());
            f.workflows.onActionQueued(ice.actionHandle(), context, ice.action());
            assertTrue(f.mining.hits.isEmpty());

            f.level.blocks.put(POS, switch (afterRotation) {
                case "source" -> Blocks.WATER.defaultBlockState();
                case "finished" -> f.required;
                default -> flow;
            });
            if (afterRotation.equals("schematicReplaced")) {
                var replacement = f.allocate(WaterSupportRegressionTest.Schematic.class);
                replacement.states = java.util.Map.of(POS, f.required);
                set(holder, SchematicWorldHandler.class, "world", replacement);
            } else if (afterRotation.equals("schematicCleared")) {
                f.schematic.states.clear();
            } else if (afterRotation.equals("skipWaterlogged")) {
                Configs.Print.SKIP_WATERLOGGED_BLOCK.setBooleanValue(true);
            } else if (afterRotation.equals("secondSource")) {
                f.level.blocks.put(POS.east(), Blocks.WATER.defaultBlockState());
            }
            var sent = broker.sendQueue(f.client.player);
            if (afterRotation.equals("flow")) {
                assertEquals(ActionPort.SendResult.SENT, sent);
                assertEquals(1, f.mining.hits.size());
                assertTrue(f.runtime.hudStats().isPrintPlacementPending(POS));
            } else {
                assertEquals(ActionPort.SendResult.NO_QUEUED_ACTION, sent);
                assertTrue(f.mining.hits.isEmpty());
                assertFalse(f.runtime.hudStats().isPrintPlacementPending(POS));
                if (afterRotation.equals("source") || afterRotation.equals("finished")) {
                    f.now++;
                    f.workflows.wake(POS);
                    f.workflows.tick(f.level, f.schematic);
                    if (afterRotation.equals("source")) assertSame(PrintTaskBuildResult.PASS, f.build(POS));
                    else assertFalse(f.workflows.isActiveTaskPos(POS));
                }
            }
        } finally {
            set(holder, SchematicWorldHandler.class, "world", previousSchematic);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(precision);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(reserve);
        }
    }
}
//#endif
