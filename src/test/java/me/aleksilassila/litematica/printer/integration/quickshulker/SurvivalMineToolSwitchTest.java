//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import fi.dy.masa.malilib.config.options.ConfigBoolean;
import fi.dy.masa.malilib.config.options.ConfigInteger;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.FeatureModuleBase;
import me.aleksilassila.litematica.printer.handler.handlers.MineHandler;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooAdapter;
import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.interaction.MiningInteractionPort;
import me.aleksilassila.litematica.printer.mixin_extension.BlockBreakResult;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import me.aleksilassila.litematica.printer.printer.PrinterBox;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.mods.TweakerooUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PacketBatchConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.PlayerEquipment;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.alloc;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.get;
import static me.aleksilassila.litematica.printer.integration.quickshulker.MiningInteractionRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** Real survival analysis, preparation, inventory prediction and START; only mixin invokers and transport are bounded. */
class SurvivalMineToolSwitchTest {
    private static final BlockPos TARGET = BlockPos.ZERO;

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    @ParameterizedTest @CsvSource({"1,4", "10,4", "1,5", "10,5"})
    void orderedMixedToolBatchesPrepareTheNextToolWithoutAnIdleDispatchTick(int axeSlot, int efficiencyLevel) throws Exception {
        var wood = BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.BOOKSHELF);
        var oldTags = wood.tags().toList();
        var bindTags = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
        bindTags.setAccessible(true);
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            bindTags.invoke(wood, List.of(net.minecraft.tags.BlockTags.MINEABLE_WITH_AXE));
            var pickaxe = prepareProtectedMining(f, tool, 200, true, false);
            var efficiency = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
            pickaxe.enchant(efficiency, efficiencyLevel);
            var axe = new ItemStack(Items.DIAMOND_AXE);
            axe.enchant(efficiency, efficiencyLevel);
            f.candidates.setSlot(axeSlot, axe);
            set(tool.player, LivingEntity.class, "activeEffects",
                    new HashMap<>(Map.of(MobEffects.HASTE, new MobEffectInstance(MobEffects.HASTE, 600, 1))));
            var order = List.of(TARGET, TARGET.east(), TARGET.east(2), TARGET.east(3), TARGET.east(4));
            f.bounds(TARGET, order.getLast());
            f.candidates.state(order.get(2), Blocks.BOOKSHELF.defaultBlockState());
            f.candidates.state(order.get(3), Blocks.BOOKSHELF.defaultBlockState());
            advanceMiningTick(f, tool, 0);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            for (var pos : order) f.iterate(pos);
            f.dispatch();

            assertEquals(order.subList(0, 2), tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList());
            assertTrue(tool.player.getMainHandItem().is(Items.DIAMOND_AXE), "Prepare the next ranked tool in the same dispatch");
            assertTrue(tool.guard.isWaiting(), "The inventory switch still needs its existing boundary before START");

            advanceMiningTick(f, tool, 1);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            f.continueActive();
            f.dispatch();
            assertEquals(order.subList(0, 4), tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList(), "No rank can be skipped to retain the current tool");
            assertTrue(tool.player.getMainHandItem().is(Items.DIAMOND_PICKAXE));
            assertTrue(tool.guard.isWaiting());

            advanceMiningTick(f, tool, 2);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            f.continueActive();
            f.dispatch();
            assertEquals(order, tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList());
        } finally {
            bindTags.invoke(wood, oldTags);
        }
    }

    @Test void anUnrelatedMaterialCountUpdateDoesNotRecalibrateAutomaticToolSelection() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            prepareProtectedMining(f, tool, 100, true, true);
            f.candidates.setSlot(5, new ItemStack(Items.COBBLESTONE));
            advanceMiningTick(f, tool, 0);
            mine(tool, TARGET, true);
            assertEquals(1, tool.connection.stacksAtStart.size());

            f.candidates.setSlot(5, new ItemStack(Items.COBBLESTONE, 2));
            advanceMiningTick(f, tool, 1);
            mine(tool, TARGET.east(), true);
            advanceMiningTick(f, tool, 2);
            mine(tool, TARGET.east(2), true);
            assertTrue(transport.batches.isEmpty(), "A non-damageable material candidate has no tool budget to recalibrate");
            assertEquals(3, tool.connection.stacksAtStart.size());
        }
    }

    @Test void aFormerToolSlotStillRequiresCalibrationWhenReplacedByMaterial() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            prepareProtectedMining(f, tool, 100, true, true);
            f.candidates.setSlot(5, new ItemStack(Items.IRON_PICKAXE));
            advanceMiningTick(f, tool, 0);
            mine(tool, TARGET, true);
            f.candidates.setSlot(5, new ItemStack(Items.COBBLESTONE));
            advanceMiningTick(f, tool, 1);
            mine(tool, TARGET.east(), true);
            advanceMiningTick(f, tool, 2);
            mine(tool, TARGET.east(2), true);
            assertCalibrationBatch(transport, 1);
            assertEquals(1, tool.connection.stacksAtStart.size(), "A tool disappearing from its recorded slot still invalidates its budget");
        }
    }

    @ParameterizedTest
    @CsvSource({"-1", "2", "10"})
    void iceWorkflowNeverCyclesBetweenAnUnsafeNonSilkToolAndASafeSilkTool(int safeNonSilkSlot) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f, true);
             var ice = new IceWorkflowFixture(f, tool)) {
            ItemStack worn = ice.pickaxe(21, false, false);
            tool.player.inventory.setItem(0, worn);
            tool.player.inventory.setItem(1, ice.pickaxe(200, true, false));
            if (safeNonSilkSlot >= 0) tool.player.inventory.setItem(safeNonSilkSlot, ice.pickaxe(200, false, false));
            var task = ice.task(TARGET);
            for (int tick = 0; tick < 12 && tool.connection.stacksAtStart.isEmpty(); tick++) {
                ice.step(task, tick);
            }
            assertEquals(1, tool.connection.stacksAtStart.size(),
                    () -> "Already placed ice must reach a real START instead of repeatedly changing tools: " + tool.connection.traffic);
            ItemStack sent = tool.connection.stacksAtStart.getFirst();
            assertTrue(sent.getEnchantments().keySet().stream()
                    .noneMatch(holder -> holder.is(Enchantments.SILK_TOUCH)), "Water recovery must never START with Silk Touch");
            if (safeNonSilkSlot >= 0) {
                assertTrue(sent.is(Items.DIAMOND_PICKAXE));
                assertEquals(200, sent.getMaxDamage() - sent.getDamageValue());
                assertEquals(safeNonSilkSlot < 9 ? safeNonSilkSlot : 0, InventoryUtils.getSelectedSlot(tool.player.inventory));
                if (safeNonSilkSlot >= 9) {
                    assertEquals(List.of("swap:10:0"), tool.connection.traffic.stream().filter(event -> event.startsWith("swap:")).toList(),
                            "The actual menu SWAP must use the qualified source slot, not another pickaxe matched by item");
                    assertEquals(21, tool.player.inventory.getItem(10).getMaxDamage() - tool.player.inventory.getItem(10).getDamageValue(),
                            "The old worn non-Silk pickaxe must occupy the exact source slot after swapping");
                    assertTrue(tool.player.inventory.getItem(1).getEnchantments().keySet().stream()
                            .anyMatch(holder -> holder.is(Enchantments.SILK_TOUCH)), "The healthy Silk Touch distractor stays untouched");
                }
            } else {
                assertTrue(sent.isEmpty(), "The available empty hotbar slot is a safe non-Silk fallback");
            }
            assertEquals(21, worn.getMaxDamage() - worn.getDamageValue(), "No test transport fabricates client wear");
        }
    }

    @Test
    void iceWorkflowCalibratesItsStillHealthyCurrentToolBeforeSelectingASpare() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f, true);
             var ice = new IceWorkflowFixture(f, tool)) {
            var transport = tool.enableCalibrationTransport();
            ItemStack held = ice.pickaxe(22, false, true);
            tool.player.inventory.setItem(0, held);
            tool.player.inventory.setItem(1, held.copy());
            ice.step(ice.task(TARGET), 0);
            assertEquals(1, tool.connection.stacksAtStart.size());
            var second = ice.task(TARGET.east());
            ice.step(second, 1);
            assertCalibrationBatch(transport, 1);
            for (int tick = 2; tick <= 8; tick++) ice.step(second, tick);
            assertEquals(1, tool.connection.stacksAtStart.size(), "No ordered inventory reply means no additional START");
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory));
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")));
            assertEquals(22, held.getMaxDamage() - held.getDamageValue());
            applyAuthoritativeInventory(tool);
            transport.receiveStats();
            ice.step(second, 9);
            assertEquals(2, tool.connection.stacksAtStart.size(), "The same original tool resumes after the actual inventory fence");
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory));
        }
    }

    @Test
    void iceWorkflowKeepsItsPaidSlowHoldWhenAuthoritativeWearReachesTheReserveFloor() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f, true);
             var ice = new IceWorkflowFixture(f, tool)) {
            ItemStack held = ice.pickaxe(22, false, false);
            tool.player.inventory.setItem(0, held);
            tool.player.inventory.setItem(1, ice.pickaxe(200, true, false));
            var task = ice.task(TARGET);
            ice.step(task, 0);
            assertTrue(f.mining.port.destroying, "The first START must establish a real slow hold");
            assertEquals(1, tool.connection.stacksAtStart.size());
            ItemStack receipt = held.copy();
            receipt.setDamageValue(receipt.getMaxDamage() - 21);
            tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, receipt);
            for (int tick = 1; tick <= 6 && f.mining.port.destroying; tick++) ice.step(task, tick);
            assertFalse(f.mining.port.destroying, "A paid hold is allowed to finish at reserve plus its delayed tail");
            assertEquals(1, tool.connection.stacksAtStart.size(), "Continuation must not reserve or START a second time");
            assertTrue(tool.connection.packets.stream().anyMatch(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK));
            assertTrue(tool.connection.packets.stream().noneMatch(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK));
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory));
        }
    }

    @Test
    void iceWorkflowHandlerPreservesPaidMiningOwnershipAcrossAnAuthoritativeWearReceipt() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f, true);
             var ice = new IceWorkflowFixture(f, tool)) {
            Configs.Core.WORK_MODE_TYPE.setOptionListValue(me.aleksilassila.litematica.printer.enums.PrintModeType.PRINTER);
            var handler = alloc(WaterMiningContinuityTest.TestHandler.class);
            handler.reachable = true;
            handler.selected = true;
            handler.schematic = true;
            set(handler, FeatureModuleBase.class, "mc", f.mining.client);
            set(handler, FeatureModuleBase.class, "level", f.mining.level);
            set(handler, FeatureModuleBase.class, "player", tool.player);
            set(handler, FeatureModuleBase.class, "connection", tool.connection);
            set(handler, FeatureModuleBase.class, "gameMode", tool.mode);
            var scan = (me.aleksilassila.litematica.printer.handler.scan.ScanEngine)
                    get(f.mining.mineHandler, FeatureModuleBase.class, "scanEngine");
            var targets = new me.aleksilassila.litematica.printer.handler.handlers.print.PrintTargetScheduler(
                    new me.aleksilassila.litematica.printer.handler.handlers.print.SortedSchematicTargetQueue(scan), () -> {});
            set(handler, me.aleksilassila.litematica.printer.handler.handlers.PrintHandler.class, "targets", targets);
            set(handler, me.aleksilassila.litematica.printer.handler.handlers.PrintHandler.class, "printTasks", f.mining.workflows);
            set(RuntimeAccess.get().modules(), me.aleksilassila.litematica.printer.handler.FeatureModuleSet.class, "print", handler);
            ItemStack held = ice.pickaxe(22, false, false);
            tool.player.inventory.setItem(0, held);
            var task = ice.task(TARGET);
            @SuppressWarnings("unchecked")
            var tasks = (Map<BlockPos, me.aleksilassila.litematica.printer.handler.handlers.print.PrintTask>)
                    get(f.mining.workflows, me.aleksilassila.litematica.printer.handler.handlers.print.PrintWorkflowScheduler.class, "tasks");
            tasks.put(TARGET, task);
            ice.step(task, 0);
            assertTrue(f.mining.port.destroying);
            assertTrue(handler.shouldKeepMiningOnIdleInput(), "The real handler must recognize the original paid hold before any receipt");
            ItemStack receipt = held.copy();
            receipt.setDamageValue(receipt.getMaxDamage() - 21);
            tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, receipt);
            assertFalse(tool.mode.matchesDestroyTarget(TARGET), "Vanilla's strict matcher really observes the changed DAMAGE component");
            assertTrue(handler.shouldKeepMiningOnIdleInput(), "Ordinary durability receipts must not release paid water mining to idle-input cancellation");
            BlockPos ordinary = TARGET.north();
            assertFalse(handler.canIterationBlockPos(ordinary), "Other printing must still wait for the paid slow hold");
            assertEquals(List.of(ordinary), targets.retainedTargets());
            f.mining.idleInput(false);
            assertTrue(f.mining.port.destroying, "The actual idle-input hook must retain the mining session");
            for (int tick = 1; tick <= 6 && f.mining.port.destroying; tick++) ice.step(task, tick);
            assertFalse(f.mining.port.destroying);
            assertEquals(1, tool.connection.stacksAtStart.size());
            assertTrue(tool.connection.packets.stream().noneMatch(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK));
            assertTrue(tool.connection.packets.stream().anyMatch(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK));
        }
    }

    /** Real water policy, selector, controller and shared runtime service; only transport/world storage are fixtures. */
    private static final class IceWorkflowFixture implements AutoCloseable {
        final MineSelectionRefreshTest.Fixture f;
        final ToolFixture tool;
        final List<?> oldTags = BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.ICE).tags().toList();
        IceWorkflowFixture(MineSelectionRefreshTest.Fixture f, ToolFixture tool) throws Exception {
            this.f = f;
            this.tool = tool;
            bindTags(List.of(net.minecraft.tags.BlockTags.MINEABLE_WITH_PICKAXE));
            Configs.Print.PRINT_ICE_FOR_WATER.setBooleanValue(true);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(false);
            Configs.Break.BREAK_USE_DELAYED_DESTROY.setBooleanValue(false);
            for (int slot = 0; slot < 36; slot++) tool.player.inventory.setItem(slot, ItemStack.EMPTY);
            InventoryUtils.setSelectedSlot(tool.player.inventory, 0);
            // This fixture has vanilla prediction/environment support, unlike the candidate-only level.
            f.mining.client.level = f.mining.level;
            set(tool.player, Entity.class, "level", f.mining.level);
            f.mining.level.states = new HashMap<>();
            assertSame(RuntimeAccess.get().toolSwitchService(), get(tool.controller, tool.controller.getClass(), "toolSwitchService"));
        }
        ItemStack pickaxe(int remaining, boolean silk, boolean fast) {
            ItemStack stack = new ItemStack(Items.DIAMOND_PICKAXE);
            stack.setDamageValue(stack.getMaxDamage() - remaining);
            var enchantments = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT);
            if (silk) stack.enchant(enchantments.getOrThrow(Enchantments.SILK_TOUCH), 1);
            if (fast) stack.enchant(enchantments.getOrThrow(Enchantments.EFFICIENCY), 4);
            return stack;
        }
        me.aleksilassila.litematica.printer.printer.SchematicBlockContext context(BlockPos pos) throws Exception {
            var context = alloc(me.aleksilassila.litematica.printer.printer.SchematicBlockContext.class);
            var type = context.getClass();
            set(context, type, "client", f.mining.client);
            set(context, type, "level", f.mining.level);
            set(context, type, "blockPos", pos);
            set(context, type, "currentState", f.mining.level.getBlockState(pos));
            set(context, type, "requiredState", Blocks.WATER.defaultBlockState());
            return context;
        }
        me.aleksilassila.litematica.printer.handler.handlers.print.WaterPrintTask task(BlockPos pos) throws Exception {
            f.mining.level.states.put(pos, Blocks.ICE.defaultBlockState());
            f.mining.level.states.put(pos.below(), Blocks.STONE.defaultBlockState());
            var task = me.aleksilassila.litematica.printer.handler.handlers.print.WaterPrintTask.tryCreate(context(pos), () -> f.mining.level.tick);
            assertNotNull(task);
            return task;
        }
        void step(me.aleksilassila.litematica.printer.handler.handlers.print.WaterPrintTask task, long tick) throws Exception {
            f.mining.level.tick = tick;
            advanceMiningTick(f, tool, tick);
            task.buildAction(context(task.pos()));
        }
        private static void bindTags(java.util.Collection<?> tags) throws Exception {
            var method = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", java.util.Collection.class);
            method.setAccessible(true);
            method.invoke(BuiltInRegistries.BLOCK.wrapAsHolder(Blocks.ICE), tags);
        }
        @Override public void close() throws Exception { bindTags(oldTags); }
    }

    @ParameterizedTest
    @CsvSource({"STICK,1,true", "EMPTY,1,true", "STICK,10,true", "EMPTY,10,true",
            "STICK,1,false", "EMPTY,1,false", "STICK,10,false", "EMPTY,10,false"})
    void survivalDispatchSelectsAnExistingToolOnlyWhenPrinterAutoToolIsEnabled(
            String hand, int toolSlot, boolean enabled) throws Exception {
        try (var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            tool.run(hand, toolSlot, enabled);
        }
    }

    @ParameterizedTest
    @CsvSource({"STICK,1", "EMPTY,1", "STICK,10", "EMPTY,10"})
    void survivalDispatchUsesTheActualTweakerooImplementationWithSlotsOneThroughNine(
            String hand, int toolSlot) throws Exception {
        try (var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(true);
             var tool = new ToolFixture(f)) {
            assertTrue(new TweakerooAdapter().isEffectiveToolSwitchEnabled());
            assertFalse(new TweakerooAdapter().isDurabilityGuardActive(), "Matches the saved swapAlmostBroken=false setting");
            tool.run(hand, toolSlot, true);
        }
    }

    @ParameterizedTest
    @CsvSource({"1,false", "10,false", "1,true", "10,true"})
    void aServerConfirmedToolLossRetriesPendingWorkWithTheSpareBeforeSectionRepair(int spareSlot, boolean external) throws Exception {
        try (var reserve = new ReserveSetting(0);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(external);
             var tool = new ToolFixture(f);
             var schematic = new MineSelectionRefreshTest.HeadlessSchematicBinding()) {
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(true);
            Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
            var efficiency = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
            var lastUse = new ItemStack(Items.DIAMOND_PICKAXE);
            lastUse.enchant(efficiency, 4);
            lastUse.setDamageValue(lastUse.getMaxDamage() - 1);
            var spare = new ItemStack(Items.DIAMOND_PICKAXE);
            spare.enchant(efficiency, 4);
            f.candidates.setSlot(0, lastUse);
            f.candidates.setSlot(spareSlot, spare);
            f.candidates.selectSlot(0);
            set(tool.player, LivingEntity.class, "activeEffects",
                    new HashMap<>(Map.of(MobEffects.HASTE, new MobEffectInstance(MobEffects.HASTE, 600, 1))));
            BlockPos remaining = TARGET.east();
            f.bounds(TARGET, remaining);
            for (BlockPos pos : List.of(TARGET, remaining)) f.candidates.state(pos, Blocks.STONE.defaultBlockState());
            var scan = f.installScan();
            var schematicWorld = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            var bounds = new PrinterBox(TARGET, remaining);
            float progress = Blocks.STONE.defaultBlockState().getDestroyProgress(tool.player, f.candidates.level, TARGET);
            assertTrue(progress >= 0.7F && progress < 1F, "The real survival batch must await server confirmation without local removal");
            scan.beginTick(f.candidates.level, schematicWorld, 0);
            handlerCall(f, "preprocess", new Class<?>[0]);
            String lastSourceState = deliverSource(f, bounds);
            f.dispatch();
            assertEquals(List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_PICKAXE), tool.connection.heldAtStart);
            assertFalse(lastUse.isEmpty(), "Vanilla client dispatch itself must not consume the tool in this server-sync regression");
            assertTrue(f.candidates.refresh().isEmpty(), "The entire discovered batch really left the Mine queue");
            assertTrue((boolean) call(tool.controller, "isPendingDelayedDestroy", new Class<?>[]{BlockPos.class}, remaining));
            var submitted = (Map<?, ?>) get(tool.controller, tool.controller.getClass(), "pendingDelayedDestroys");
            Object submittedEntry = submitted.get(remaining);
            long submittedDeadline;
            try {
                submittedDeadline = (long) get(submittedEntry, submittedEntry.getClass(), "deadline");
            } catch (NoSuchFieldException previousImplementation) {
                submittedDeadline = -1L;
            }

            // Apply the same menu/world writes that the server sync handlers apply between client ticks:
            // the first block exhausted the pickaxe; the next optimistic STOP was rejected.
            var contents = new ArrayList<>(tool.player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList());
            contents.set(36, ItemStack.EMPTY);
            tool.player.inventoryMenu.initializeContents(tool.player.inventoryMenu.getStateId() + 1, contents, ItemStack.EMPTY);
            assertTrue(tool.player.getMainHandItem().isEmpty());
            assertTrue(tool.player.inventory.getItem(spareSlot).is(Items.DIAMOND_PICKAXE));
            f.candidates.state(TARGET, Blocks.AIR.defaultBlockState());
            scan.invalidate(TARGET);
            scan.invalidate(remaining);
            InteractionUtils.getRuntime().confirmServerBlockUpdate(TARGET);
            InteractionUtils.getRuntime().confirmServerBlockUpdate(remaining);
            long dirty = scan.dirtyVersion();
            int originalTimeout = (int) Math.ceil(1F / progress) + 10;
            assertEquals(12, originalTimeout);
            var offers = new ArrayList<String>();

            for (int tick = 1; tick <= 19 && tool.connection.heldAtStart.size() < 3; tick++) {
                f.candidates.level.tick = tick;
                set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", (long) tick);
                scan.beginTick(f.candidates.level, schematicWorld, tick);
                f.cooldowns.tick();
                InteractionUtils.getRuntime().preprocess();
                call(tool.controller, "tick", new Class<?>[0]);
                handlerCall(f, "preprocess", new Class<?>[0]);
                lastSourceState = deliverSource(f, bounds);
                offers.add(tick + ":" + lastSourceState);
                f.dispatch();
                if (tick < originalTimeout) {
                    assertEquals(2, tool.connection.heldAtStart.size(), "Confirmation ownership forbids another START before the original deadline");
                    assertTrue((boolean) call(tool.controller, "isPendingDelayedDestroy", new Class<?>[]{BlockPos.class}, remaining));
                    assertTrue(((RetryPort) f.mining.port).expired.isEmpty(), "A hand change cannot release confirmation early");
                }
            }

            String recovery = recoveryState(tool, f, remaining, submittedDeadline, lastSourceState);
            assertEquals(List.of(TARGET, remaining, remaining), tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList(),
                    () -> "A real pending retry must regain the existing spare before the 20-tick repair fallback; "
                            + recovery + ", offers=" + offers + "; " + toolDiagnostics(tool));
            assertEquals(List.of(Items.DIAMOND_PICKAXE, Items.DIAMOND_PICKAXE, Items.DIAMOND_PICKAXE), tool.connection.heldAtStart);
            assertEquals(List.of(remaining), ((RetryPort) f.mining.port).expired,
                    "Only the expired Mine delivery is reoffered once; a confirmed AIR target is complete");
            assertEquals(dirty, scan.dirtyVersion(), "Tool recovery does not need invented world events");
        }
    }

    @ParameterizedTest
    @CsvSource({"1,true", "10,true", "1,false", "10,false"})
    void builtInReserveReplacesAnUnsafeToolWithAnEqualSpeedSpare(int spareSlot, boolean autoTool) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack unsafe = prepareProtectedMining(f, tool, 1, autoTool, true);
            ItemStack spare = unsafe.copy();
            spare.setDamageValue(0);
            f.candidates.setSlot(spareSlot, spare);
            for (int tick = 0; tick < 8 && tool.connection.stacksAtStart.isEmpty(); tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET, false);
            }
            assertEquals(1, tool.connection.stacksAtStart.size(), "Protection must find the existing spare even when ordinary auto-tool is disabled");
            assertEquals(0, tool.connection.stacksAtStart.getFirst().getDamageValue(), "Equal Item and speed are insufficient: START must use the healthy stack");
            assertEquals(unsafe.getMaxDamage() - 1, unsafe.getDamageValue(), "Transport must not fake client durability consumption");
            String preparation = spareSlot < 9 ? "select:" : "swap:";
            assertTrue(firstEvent(tool.connection.traffic, preparation) >= 0);
            assertTrue(firstEvent(tool.connection.traffic, preparation) < firstEvent(tool.connection.traffic, "destroy:START"));
        }
    }

    @Test
    void builtInReserveWithoutASpareWithholdsAllDamageProducingStarts() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack unsafe = prepareProtectedMining(f, tool, 1, true, true);
            for (int tick = 0; tick < 8; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(tick), true);
            }
            assertTrue(tool.connection.stacksAtStart.isEmpty(), "No spare means stop before START, not downgrade into damaging slow mining");
            assertEquals(unsafe.getMaxDamage() - 1, unsafe.getDamageValue());
        }
    }

    @Test
    void builtInReserveCannotUseASpareShovelToReplaceAPickaxeForStone() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack unsafe = prepareProtectedMining(f, tool, 1, false, true);
            var shovel = new ItemStack(Items.IRON_SHOVEL);
            assertTrue(Blocks.STONE.defaultBlockState().requiresCorrectToolForDrops());
            assertFalse(shovel.isCorrectToolForDrops(Blocks.STONE.defaultBlockState()));
            f.candidates.setSlot(1, shovel);
            for (int tick = 0; tick < 8; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(tick), false);
            }
            assertTrue(tool.connection.stacksAtStart.isEmpty(), "A healthy but incorrect shovel is not a safe stone-mining replacement");
            assertSame(unsafe, tool.player.getMainHandItem());
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")));
            Object service = get(tool.controller, tool.controller.getClass(), "toolSwitchService");
            Object protection = get(service, service.getClass(), "durabilityGuard");
            assertFalse((boolean) get(protection, protection.getClass(), "dirty"));
            assertNull(get(protection, protection.getClass(), "pending"), "Known insufficient tools without debt do not need calibration");
        }
    }

    @Test
    void builtInReserveCannotReplaceAShovelWithArmorForDirt() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            prepareProtectedMining(f, tool, 1, false, false);
            var unsafe = new ItemStack(Items.DIAMOND_SHOVEL);
            unsafe.setDamageValue(unsafe.getMaxDamage() - 1);
            var armor = new ItemStack(Items.DIAMOND_CHESTPLATE);
            assertTrue(armor.isDamageableItem());
            assertNull(armor.get(net.minecraft.core.component.DataComponents.TOOL));
            assertFalse(Blocks.DIRT.defaultBlockState().requiresCorrectToolForDrops());
            f.candidates.setSlot(0, unsafe);
            f.candidates.setSlot(1, armor);
            f.candidates.selectSlot(0);
            f.candidates.state(TARGET, Blocks.DIRT.defaultBlockState());

            for (int tick = 0; tick < 8; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET, false);
            }

            assertTrue(tool.connection.stacksAtStart.isEmpty(), "Armor is not a spare mining tool, even when dirt needs no correct tool for drops");
            assertSame(unsafe, tool.player.getMainHandItem(), "Protection must keep the worn shovel instead of selecting armor");
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")));
        }
    }

    @Test
    void aProtectionBlockedHandlerTargetHasNoSentCooldownOrExpectedWorldClear() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            prepareProtectedMining(f, tool, 1, false, true);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            f.bounds(TARGET, TARGET);
            assertTrue(f.mining.mineHandler.canIterationBlockPos(TARGET));
            f.iterate(TARGET);

            f.dispatch();

            assertTrue(tool.connection.packets.isEmpty(), "The real handler must stop before a protection-blocked START");
            assertEquals(0, f.cooldowns.getRemainingCooldown(f.candidates.level, MineHandler.NAME, TARGET),
                    "A target rejected before dispatch has no sent-action cooldown");
            Object hud = get(f.mining.mineHandler, FeatureModuleBase.class, "hudStats");
            assertTrue(((Map<?, ?>) get(hud, hud.getClass(), "pendingMineTargets")).isEmpty(),
                    "Protection cannot invent an expected world clear for an action it withheld");
        }
    }

    @Test
    void aWholeProtectedMineBatchYieldsItsBoundedQueueToLaterSourceWork() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f);
             var schematic = new MineSelectionRefreshTest.HeadlessSchematicBinding()) {
            prepareProtectedMining(f, tool, 1, false, true);
            f.candidates.setSlot(1, new ItemStack(Items.IRON_SHOVEL));
            f.bounds(TARGET, new BlockPos(7, 0, 8));
            for (int index = 0; index < 64; index++) {
                f.candidates.state(new BlockPos(index & 7, 0, index >>> 3), Blocks.STONE.defaultBlockState());
            }
            BlockPos freshDirt = new BlockPos(0, 0, 8);
            f.candidates.state(freshDirt, Blocks.DIRT.defaultBlockState());
            var scan = f.installScan();
            var world = fi.dy.masa.litematica.world.SchematicWorldHandler.getSchematicWorld();
            var bounds = new PrinterBox(TARGET, new BlockPos(7, 0, 8));
            scan.beginTick(f.candidates.level, world, 0);
            handlerCall(f, "preprocess", new Class<?>[0]);
            @SuppressWarnings("unchecked")
            var first = (Iterable<BlockPos>) handlerCall(f, "getIterationPositions", new Class<?>[]{PrinterBox.class}, bounds);
            var iterator = first.iterator();
            for (int index = 0; index < 64; index++) {
                assertTrue(iterator.hasNext());
                BlockPos pos = iterator.next();
                assertEquals(Blocks.STONE.defaultBlockState(), f.candidates.level.getBlockState(pos));
                f.iterate(pos);
            }
            Object queue = get(f.mining.mineHandler, MineHandler.class, "candidates");
            var entries = (Map<?, ?>) get(queue, queue.getClass(), "entries");
            assertEquals(64, entries.size(), "The real source must have filled a substantial blocked batch");
            long dirty = scan.dirtyVersion();

            f.dispatch();

            assertTrue(tool.connection.packets.isEmpty());
            assertTrue(entries.isEmpty(), "An entirely blocked batch must yield once, not discard one coordinate every tick while hiding the source tail");
            for (int tick = 1; tick <= 3 && tool.connection.heldAtStart.isEmpty(); tick++) {
                f.candidates.level.tick = tick;
                set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", (long) tick);
                scan.beginTick(f.candidates.level, world, tick);
                f.cooldowns.tick();
                InteractionUtils.getRuntime().preprocess();
                call(tool.controller, "tick", new Class<?>[0]);
                handlerCall(f, "preprocess", new Class<?>[0]);
                deliverSource(f, bounds);
                f.dispatch();
            }
            assertEquals(List.of(freshDirt), tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList(),
                    "The bounded batch must release discovery so the existing shovel can execute the later fresh dirt");
            assertEquals(List.of(Items.IRON_SHOVEL), tool.connection.heldAtStart);
            assertEquals(dirty, scan.dirtyVersion(), "Yielding temporary candidate ownership preserves positive world facts");
        }
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void builtInReserveRetainsUnconfirmedWearAcrossBatchesAndTicks(boolean separateTicks) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack hand = prepareProtectedMining(f, tool, 24, false, true);
            int initialDamage = hand.getDamageValue();
            for (int attempt = 0; attempt < 12; attempt++) {
                if (separateTicks || attempt == 0) advanceMiningTick(f, tool, attempt);
                mine(tool, TARGET.east(attempt), false);
            }
            assertEquals(3, tool.connection.stacksAtStart.size(), "24 remaining - 20 reserve - one delayed vanilla tail permits exactly three new targets, even without damage sync");
            assertEquals(initialDamage, hand.getDamageValue(), "The client sees stale durability throughout this regression");
            assertTrue(tool.connection.stacksAtStart.stream().allMatch(stack -> stack.getDamageValue() == initialDamage));
        }
    }

    @ParameterizedTest
    @CsvSource({"20,100,false", "0,1,false", "20,1,true"})
    void builtInReservePreservesHealthyBatchThroughputAndExplicitBypasses(int reserveValue, int remaining, boolean creative) throws Exception {
        try (var reserve = new ReserveSetting(reserveValue);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            prepareProtectedMining(f, tool, remaining, false, true);
            tool.player.abilities.instabuild = creative;
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 12; target++) mine(tool, TARGET.east(target), false);
            assertEquals(12, tool.connection.stacksAtStart.size(), "Healthy tools keep one-tick batching; off and creative preserve their existing behavior");
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("swap:") || event.startsWith("select:")));
        }
    }

    @Test
    void builtInReserveChargesASlowTargetOnceRatherThanEveryContinuationTick() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            prepareProtectedMining(f, tool, 24, false, false);
            float progress = Blocks.STONE.defaultBlockState().getDestroyProgress(tool.player, f.candidates.level, TARGET);
            assertTrue(progress > 0F && progress < 0.7F, "Use the actual survival slow path");
            int tick = 0;
            for (int target = 0; target < 3; target++) {
                BlockPos pos = TARGET.east(target);
                BlockBreakResult result = BlockBreakResult.IN_PROGRESS;
                for (int continuation = 0; continuation < 30 && result == BlockBreakResult.IN_PROGRESS; continuation++) {
                    advanceMiningTick(f, tool, tick++);
                    result = mine(tool, pos, false);
                }
                assertEquals(BlockBreakResult.COMPLETED, result, "Each reserved slow target must finish despite many continuation ticks");
            }
            advanceMiningTick(f, tool, tick);
            mine(tool, TARGET.east(3), false);
            assertEquals(List.of(TARGET, TARGET.east(), TARGET.east(2)), tool.connection.packets.stream()
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList(), "Three sessions consume three units; the fourth cannot spend the delayed-tail reserve");
        }
    }

    @ParameterizedTest
    @CsvSource({"22,true", "21,false"})
    void builtInReserveRestartsASlowHoldAfterAHotbarToolChange(int replacementRemaining, boolean canRestart) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            prepareProtectedMining(f, tool, 22, false, false);
            ItemStack replacement = new ItemStack(Items.IRON_PICKAXE);
            replacement.setDamageValue(replacement.getMaxDamage() - replacementRemaining);
            f.candidates.setSlot(1, replacement);
            advanceMiningTick(f, tool, 0);
            assertEquals(BlockBreakResult.IN_PROGRESS, mine(tool, TARGET, false));
            assertEquals(1, tool.connection.stacksAtStart.size());
            f.candidates.selectSlot(1);
            for (int tick = 1; tick <= 3; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET, false);
            }
            var actions = tool.connection.packets.stream().map(ServerboundPlayerActionPacket::getAction).toList();
            var expected = new ArrayList<>(List.of(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    ServerboundPlayerActionPacket.Action.ABORT_DESTROY_BLOCK));
            if (canRestart) expected.add(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK);
            assertEquals(expected, actions, "A changed tool cannot inherit the old held session's reservation; abort before a separately budgeted restart");
            if (canRestart) assertTrue(tool.connection.stacksAtStart.getLast().is(Items.IRON_PICKAXE));
        }
    }

    @ParameterizedTest
    @CsvSource({"DAMAGE_UPDATE", "AIR_CONFIRMATION", "RUNTIME_RESET"})
    void builtInReserveKeepsDebtAcrossOrdinaryFeedbackAndRuntimeReset(String event) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack hand = prepareProtectedMining(f, tool, 24, false, true);
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 3; target++) mine(tool, TARGET.east(target), false);
            assertEquals(3, tool.connection.stacksAtStart.size());
            switch (event) {
                case "DAMAGE_UPDATE" -> {
                    // A normal authoritative slot write reports only one of three outstanding uses.
                    // It is not the ordered full-snapshot/statistics calibration protocol.
                    ItemStack receipt = hand.copy();
                    receipt.setDamageValue(receipt.getDamageValue() + 1);
                    tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, receipt);
                    assertEquals(hand.getMaxDamage() - 23, tool.player.getMainHandItem().getDamageValue());
                }
                case "AIR_CONFIRMATION" -> {
                    for (int target = 0; target < 3; target++) {
                        BlockPos pos = TARGET.east(target);
                        f.candidates.state(pos, Blocks.AIR.defaultBlockState());
                        call(tool.controller, "confirmServerBlockUpdate", new Class<?>[]{BlockPos.class}, pos);
                    }
                }
                case "RUNTIME_RESET" -> call(tool.controller, "reset", new Class<?>[0]);
                default -> throw new AssertionError(event);
            }
            for (int tick = 1; tick <= 4; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(3 + tick), false);
            }
            assertEquals(3, tool.connection.stacksAtStart.size(), event + " cannot grant fresh durability credit for the same player/world/tool");
        }
    }

    @Test
    void builtInReserveExactMainInventorySwapCannotReviveTheOldToolsBudget() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack oldTool = prepareProtectedMining(f, tool, 24, false, true);
            ItemStack spare = oldTool.copy();
            spare.setDamageValue(spare.getMaxDamage() - 25);
            f.candidates.setSlot(10, spare);
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 3; target++) mine(tool, TARGET.east(target), false);
            assertEquals(3, tool.connection.stacksAtStart.size());
            // Shadow exhaustion alone now calibrates the held tool. Only actual server wear
            // reaching the protection line justifies replacing it with an equal-speed spare.
            ItemStack oldReceipt = oldTool.copy();
            oldReceipt.setDamageValue(oldReceipt.getMaxDamage() - 21);
            tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, oldReceipt);
            for (int tick = 1; tick <= 8 && tool.connection.stacksAtStart.size() < 7; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(tool.connection.stacksAtStart.size()), false);
            }
            assertEquals(7, tool.connection.stacksAtStart.size(), "Three old-tool plus four spare-tool uses remain available across the exact SWAP");
            assertEquals(oldReceipt.getDamageValue(), tool.player.inventory.getItem(10).getDamageValue(), "The server-confirmed spent original stack is now in the main inventory");
            ItemStack spareReceipt = tool.player.getMainHandItem().copy();
            spareReceipt.setDamageValue(spareReceipt.getMaxDamage() - 21);
            tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, spareReceipt);
            for (int tick = 9; tick <= 12; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(8), false);
            }
            assertEquals(7, tool.connection.stacksAtStart.size(), "Searching the old slot again must not revive its original visible budget");
            assertEquals(1, tool.connection.traffic.stream().filter(event -> event.startsWith("swap:")).count());
        }
    }

    @Test
    void builtInReserveDoesNotTreatUnbreakingAsGuaranteedFreeDamage() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack hand = prepareProtectedMining(f, tool, 24, false, true);
            var unbreaking = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.UNBREAKING);
            hand.enchant(unbreaking, 3);
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 12; target++) mine(tool, TARGET.east(target), false);
            assertEquals(3, tool.connection.stacksAtStart.size(), "Every unconfirmed use still reserves its worst-case wear, regardless of Unbreaking probability");
        }
    }

    @Test
    void builtInReserveDoesNotSwapTheMainInventoryWhileTheCursorIsOccupied() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            ItemStack unsafe = prepareProtectedMining(f, tool, 1, false, true);
            ItemStack spare = unsafe.copy();
            spare.setDamageValue(0);
            f.candidates.setSlot(10, spare);
            tool.player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
            for (int tick = 0; tick < 4; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET, false);
            }
            assertTrue(tool.connection.stacksAtStart.isEmpty());
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("swap:")));
            assertTrue(tool.player.inventoryMenu.getCarried().is(Items.STONE));
            assertEquals(0, tool.player.inventory.getItem(10).getDamageValue());
        }
    }

    @ParameterizedTest
    @CsvSource({"1", "10"})
    void builtInReserveRecalibratesAStillHealthyHeldToolBeforeUsingAnEqualSpare(int spareSlot) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            ItemStack held = prepareProtectedMining(f, tool, 22, false, true);
            f.candidates.setSlot(spareSlot, held.copy());
            assertTrue(ItemStack.isSameItemSameComponents(held, tool.player.inventory.getItem(spareSlot)),
                    "The spare has exactly the same item, efficiency and visible durability");
            advanceMiningTick(f, tool, 0);
            mine(tool, TARGET, false);
            assertEquals(1, tool.connection.stacksAtStart.size());
            mine(tool, TARGET.east(), false);
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory),
                    "Exhausting a conservative shadow allowance is not evidence that the healthy held tool needs replacement");
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")),
                    "Calibrate the original tool before rotating through equally healthy spares");
            assertCalibrationBatch(transport, 1);
            for (int tick = 1; tick <= 20; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(), false);
            }
            assertEquals(1, tool.connection.stacksAtStart.size(), "No reply means no extra use, even with a ready spare and expired block confirmation");
            assertEquals(1, transport.batches.size(), "The same pending calibration cannot be reissued each tick");
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory));
            assertEquals(22, tool.player.getMainHandItem().getMaxDamage() - tool.player.getMainHandItem().getDamageValue());
            // A valid server result: the attempted break or Unbreaking did not consume durability.
            applyAuthoritativeInventory(tool);
            transport.receiveStats();
            mine(tool, TARGET.east(), false);
            assertEquals(2, tool.connection.stacksAtStart.size(), () -> "The original tool resumes after real snapshot and fence: " + toolDiagnostics(tool));
            assertEquals(0, InventoryUtils.getSelectedSlot(tool.player.inventory));
            assertTrue(tool.connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")));
            assertEquals(22, tool.player.inventory.getItem(spareSlot).getMaxDamage()
                    - tool.player.inventory.getItem(spareSlot).getDamageValue(), "The spare remains untouched");
        }
    }

    @ParameterizedTest
    @CsvSource({"1", "10"})
    void builtInReserveSwitchesNormallyWhenAuthoritativeWearActuallyReachesTheProtectionLine(int spareSlot) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            ItemStack held = prepareProtectedMining(f, tool, 22, false, true);
            f.candidates.setSlot(spareSlot, held.copy());
            advanceMiningTick(f, tool, 0);
            mine(tool, TARGET, false);
            assertEquals(1, tool.connection.stacksAtStart.size());
            assertEquals(22, held.getMaxDamage() - held.getDamageValue(), "Sending packets itself does not mutate client durability");
            ItemStack wornReceipt = held.copy();
            wornReceipt.setDamageValue(wornReceipt.getMaxDamage() - 21);
            tool.player.inventoryMenu.setItem(36, tool.player.inventoryMenu.getStateId() + 1, wornReceipt);
            for (int tick = 1; tick <= 8 && tool.connection.stacksAtStart.size() < 2; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(), false);
            }
            assertEquals(2, tool.connection.stacksAtStart.size(), () -> "Actual wear should select the available safe spare: " + toolDiagnostics(tool));
            ItemStack dispatched = tool.connection.stacksAtStart.getLast();
            assertEquals(22, dispatched.getMaxDamage() - dispatched.getDamageValue());
            assertTrue(firstEvent(tool.connection.traffic, spareSlot < 9 ? "select:" : "swap:") >= 0);
            assertTrue(transport.batches.isEmpty(), "A visibly exhausted tool with a safe spare does not need a speculative full inventory calibration");
        }
    }

    @Test
    void builtInReserveRecalibratesOnlyAfterItsOwnOrderedInventoryFence() throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            ItemStack hand = prepareProtectedMining(f, tool, 24, false, true);
            hand.enchant(VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT)
                    .getOrThrow(Enchantments.UNBREAKING), 3);
            // An unrelated stats-screen request precedes the guard's own marker.
            tool.connection.send(new ServerboundClientCommandPacket(ServerboundClientCommandPacket.Action.REQUEST_STATS));
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 4; target++) mine(tool, TARGET.east(target), false);
            assertEquals(3, tool.connection.stacksAtStart.size());
            assertCalibrationBatch(transport, 1);
            for (int tick = 1; tick <= 20; tick++) {
                advanceMiningTick(f, tool, tick);
                mine(tool, TARGET.east(3), false);
            }
            assertEquals(3, tool.connection.stacksAtStart.size(), "Elapsed ticks and pending-destroy expiry cannot approve calibration");
            assertEquals(1, transport.batches.size(), "An outstanding calibration sends one query, not one per tick");
            transport.receiveStats();
            mine(tool, TARGET.east(3), false);
            assertEquals(3, tool.connection.stacksAtStart.size(), "The older stats reply is not this calibration's receipt");
            // A legitimate authoritative transcript: Unbreaking saved all three uses.
            // Apply vanilla's inventory write before publishing the subsequent stats notification.
            applyAuthoritativeInventory(tool);
            transport.receiveStats();
            for (int target = 3; target < 6; target++) mine(tool, TARGET.east(target), false);
            assertEquals(6, tool.connection.stacksAtStart.size(), () -> "The actual inventory snapshot and matching marker restore three uses: " + toolDiagnostics(tool));
            assertEquals(1, transport.batches.size());
        }
    }

    @ParameterizedTest
    @CsvSource({"CLICK", "OFFHAND"})
    void builtInReserveRejectsCalibrationCrossedByUnrelatedInventoryInput(String input) throws Exception {
        try (var reserve = new ReserveSetting(20);
             var f = new MineSelectionRefreshTest.Fixture(false);
             var optional = new OptionalToolBindings(false);
             var tool = new ToolFixture(f)) {
            var transport = tool.enableCalibrationTransport();
            prepareProtectedMining(f, tool, 24, false, true);
            advanceMiningTick(f, tool, 0);
            for (int target = 0; target < 4; target++) mine(tool, TARGET.east(target), false);
            assertEquals(3, tool.connection.stacksAtStart.size());
            assertCalibrationBatch(transport, 1);
            // This packet is submitted after the calibration marker. Its inventory effect is
            // therefore not certified by that earlier marker, even if a snapshot has arrived.
            Packet<?> mutation = input.equals("CLICK")
                    ? new ServerboundContainerClickPacket(tool.player.inventoryMenu.containerId,
                            tool.player.inventoryMenu.getStateId(), (short) 10, (byte) 0, ContainerInput.PICKUP,
                            new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(), net.minecraft.network.HashedStack.EMPTY)
                    : new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.SWAP_ITEM_WITH_OFFHAND,
                            BlockPos.ZERO, Direction.DOWN, 0);
            tool.connection.send(mutation);
            advanceMiningTick(f, tool, 1);
            mine(tool, TARGET.east(3), false);
            assertEquals(3, tool.connection.stacksAtStart.size(), "Mining must remain paused while the first fence is pending");
            applyAuthoritativeInventory(tool);
            transport.receiveStats();
            mine(tool, TARGET.east(3), false);
            assertEquals(3, tool.connection.stacksAtStart.size(), input + " prevents the obsolete snapshot from granting credit");
            assertCalibrationBatch(transport, 2);
            advanceMiningTick(f, tool, 2);
            mine(tool, TARGET.east(3), false);
            assertEquals(3, tool.connection.stacksAtStart.size(), "A new request is not itself a confirmation");
            applyAuthoritativeInventory(tool);
            transport.receiveStats();
            mine(tool, TARGET.east(3), false);
            assertEquals(4, tool.connection.stacksAtStart.size(), () -> "A fresh ordered fence must permit real mining again: " + toolDiagnostics(tool));
        }
    }

    private static void applyAuthoritativeInventory(ToolFixture tool) {
        var contents = tool.player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList();
        tool.player.inventoryMenu.initializeContents(tool.player.inventoryMenu.getStateId() + 1, contents, ItemStack.EMPTY);
    }

    private static void assertCalibrationBatch(CalibrationTransport transport, int expectedBatches) {
        assertEquals(expectedBatches, transport.batches.size());
        var batch = transport.batches.getLast();
        assertEquals(2, batch.size());
        var snapshot = assertInstanceOf(ServerboundContainerClickPacket.class, batch.getFirst());
        assertEquals(-1, snapshot.stateId());
        assertEquals(-1, snapshot.slotNum());
        assertEquals(ContainerInput.PICKUP, snapshot.containerInput());
        assertEquals(ServerboundClientCommandPacket.Action.REQUEST_STATS,
                assertInstanceOf(ServerboundClientCommandPacket.class, batch.getLast()).getAction());
    }

    /** Compatibility only: absent new configuration still runs the real protection assertions RED. */
    private static final class ReserveSetting implements AutoCloseable {
        final ConfigInteger config;
        final int previous;
        ReserveSetting(int value) throws Exception {
            ConfigInteger found;
            try { found = (ConfigInteger) Configs.Break.class.getField("BREAK_TOOL_RESERVE").get(null); }
            catch (NoSuchFieldException notImplementedYet) { found = null; }
            this.config = found;
            this.previous = found == null ? 20 : found.getIntegerValue();
            if (found != null) found.setIntegerValue(value);
        }
        @Override public void close() { if (this.config != null) this.config.setIntegerValue(this.previous); }
    }

    private static ItemStack prepareProtectedMining(MineSelectionRefreshTest.Fixture f, ToolFixture tool,
                                                    int remaining, boolean autoTool, boolean fast) throws Exception {
        Configs.Core.WORK_RANGE.setIntegerValue(64);
        Configs.Break.BREAK_BLOCKS_PER_TICK.setIntegerValue(0);
        Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(autoTool);
        Configs.Break.BREAK_USE_DELAYED_DESTROY.setBooleanValue(false);
        ItemStack hand = new ItemStack(Items.DIAMOND_PICKAXE);
        hand.setDamageValue(hand.getMaxDamage() - remaining);
        if (fast) {
            var efficiency = VanillaRegistries.createLookup().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.EFFICIENCY);
            hand.enchant(efficiency, 4);
            set(tool.player, LivingEntity.class, "activeEffects",
                    new HashMap<>(Map.of(MobEffects.HASTE, new MobEffectInstance(MobEffects.HASTE, 600, 1))));
        }
        f.candidates.setSlot(0, hand);
        f.candidates.selectSlot(0);
        for (int target = 0; target < 12; target++) f.candidates.state(TARGET.east(target), Blocks.STONE.defaultBlockState());
        if (fast) {
            float progress = Blocks.STONE.defaultBlockState().getDestroyProgress(tool.player, f.candidates.level, TARGET);
            assertTrue(progress >= 0.7F && progress < 1F, "Fast server-confirmed mining must leave client durability untouched");
        }
        assertFalse(new TweakerooAdapter().isDurabilityGuardActive(), "The independent built-in guard must work at range 64 and unlimited batch size");
        return hand;
    }

    private static void advanceMiningTick(MineSelectionRefreshTest.Fixture f, ToolFixture tool, long tick) throws Exception {
        f.candidates.level.tick = tick;
        set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", tick);
        call(tool.controller, "tick", new Class<?>[0]);
    }

    private static BlockBreakResult mine(ToolFixture tool, BlockPos pos, boolean allowToolSwitch) throws Exception {
        return (BlockBreakResult) call(tool.controller, "continueForMine",
                new Class<?>[]{BlockPos.class, Direction.class, boolean.class}, pos, Direction.DOWN, allowToolSwitch);
    }

    private static final class ToolFixture implements AutoCloseable {
        final MineSelectionRefreshTest.Fixture f;
        final InventoryPlayer player;
        final ToolConnection connection;
        final ToolMode mode;
        final InventorySwitchGuard guard;
        final Object controller;
        final boolean sharedRuntimeService;
        final Object previousInventoryClient;

        ToolFixture(MineSelectionRefreshTest.Fixture f) throws Exception {
            this(f, false);
        }

        ToolFixture(MineSelectionRefreshTest.Fixture f, boolean sharedRuntimeService) throws Exception {
            this.f = f;
            this.sharedRuntimeService = sharedRuntimeService;
            this.previousInventoryClient = sharedRuntimeService ? get(null, InventoryUtils.class, "client") : null;
            if (sharedRuntimeService) set(null, InventoryUtils.class, "client", f.mining.client);
            var previous = f.mining.player;
            player = alloc(InventoryPlayer.class);
            player.abilities = previous.abilities;
            player.abilities.instabuild = false;
            player.speed = previous.speed;
            player.setId(42);
            set(player, Entity.class, "position", Vec3.ZERO);
            set(player, Entity.class, "blockPosition", TARGET);
            set(player, Entity.class, "level", f.candidates.level);
            for (String field : List.of("activeEffects", "attributes")) {
                set(player, LivingEntity.class, field, get(previous, LivingEntity.class, field));
            }
            set(player, Entity.class, "fluidInteraction", get(previous, Entity.class, "fluidInteraction"));
            set(player, Entity.class, "onGround", true);
            var equipment = new PlayerEquipment(player);
            player.inventory = new Inventory(player, equipment);
            set(player, net.minecraft.world.entity.player.Player.class, "inventory", player.inventory);
            set(player, net.minecraft.world.entity.player.Player.class, "abilities", player.abilities);
            set(player, LivingEntity.class, "equipment", equipment);
            set(player, net.minecraft.world.entity.player.Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            player.containerMenu = player.inventoryMenu;
            connection = alloc(ToolConnection.class);
            connection.player = player;
            connection.packets = f.mining.connection.packets;
            connection.traffic = new ArrayList<>();
            connection.heldAtStart = new ArrayList<>();
            connection.stacksAtStart = new ArrayList<>();
            set(player, LocalPlayer.class, "connection", connection);
            f.mining.player = player;
            f.mining.connection = connection;
            f.mining.client.player = player;
            f.mining.level.player = player;
            f.candidates.level.player = player;
            set(f.mining.mineHandler, FeatureModuleBase.class, "player", player);
            set(f.mining.mineHandler, FeatureModuleBase.class, "connection", connection);

            var retryPort = new RetryPort();
            retryPort.client = f.mining.client;
            retryPort.fixture = f;
            f.mining.port = retryPort;

            mode = alloc(ToolMode.class);
            mode.fixture = f.mining;
            mode.port = f.mining.port;
            mode.controller = f.mining.controller;
            mode.traffic = connection.traffic;
            set(mode, MultiPlayerGameMode.class, "minecraft", f.mining.client);
            set(mode, MultiPlayerGameMode.class, "connection", connection);
            set(mode, MultiPlayerGameMode.class, "localPlayerMode", GameType.SURVIVAL);
            f.mining.client.gameMode = mode;
            set(f.mining.mineHandler, FeatureModuleBase.class, "gameMode", mode);
            guard = new InventorySwitchGuard(f.mining.client, () -> f.candidates.level.tick);
            set(RuntimeAccess.get(), PrinterRuntime.class, "inventorySwitchGuard", guard);

            // Production Mixin invokers are absent in JUnit. Preserve the production classes and
            // replace only those invoker calls, as MaterialSelectionFixture already does.
            ClassLoader loader = sharedRuntimeService ? SurvivalMineToolSwitchTest.class.getClassLoader() : new ToolLoader();
            var serviceType = loader.loadClass("me.aleksilassila.litematica.printer.interaction.ToolSwitchService");
            var service = serviceType.getConstructor(Minecraft.class, TweakerooToolSwitchPort.class, InventorySwitchGuard.class)
                    .newInstance(f.mining.client, new TweakerooAdapter(), guard);
            var controllerType = loader.loadClass("me.aleksilassila.litematica.printer.interaction.MiningInteractionController");
            controller = controllerType.getConstructor(MiningInteractionPort.class, serviceType).newInstance(f.mining.port, service);
            mode.realController = controller;
            if (sharedRuntimeService) set(RuntimeAccess.get(), PrinterRuntime.class, "toolSwitchService", service);
        }

        CalibrationTransport enableCalibrationTransport() throws Exception {
            assertTrue(connection.packets.isEmpty(), "Attach the connection before any guard scope or mining action is observed");
            var service = get(controller, controller.getClass(), "toolSwitchService");
            var transport = new CalibrationTransport(service);
            connection.transport = transport;
            return transport;
        }

        void run(String hand, int toolSlot, boolean enabled) throws Exception {
            Item initial = hand.equals("EMPTY") ? Items.AIR : Items.STICK;
            f.candidates.setSlot(0, initial == Items.AIR ? ItemStack.EMPTY : new ItemStack(initial));
            f.candidates.setSlot(toolSlot, new ItemStack(Items.IRON_PICKAXE));
            f.candidates.selectSlot(0);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(enabled);
            f.candidates.beginTick();
            f.callToolSession("beginTick");
            f.bounds(TARGET, TARGET);
            f.candidates.state(TARGET, Blocks.STONE.defaultBlockState());
            assertFalse(player.getAbilities().instabuild, "This regression exercises survival, not the creative shortcut");
            assertTrue(f.mining.mineHandler.canIterationBlockPos(TARGET));
            f.iterate(TARGET);
            var analyzed = f.candidates.views(f.candidates.refresh());
            assertEquals(1, analyzed.size());
            assertEquals(enabled ? Items.IRON_PICKAXE : initial, analyzed.getFirst().tool(),
                    "The real analyzer must discover the existing inventory tool before dispatch");

            f.dispatch();
            if (enabled) {
                assertTrue(connection.packets.isEmpty(), () -> "A predicted tool switch cannot send START in its own tick: " + toolDiagnostics(this));
                assertTrue(guard.isWaiting(), () -> "The real inventory switch must retain its confirmation boundary: " + toolDiagnostics(this));
                assertTrue(player.getMainHandItem().is(Items.IRON_PICKAXE), () -> "The actual selection/SWAP prediction equips the pickaxe: " + toolDiagnostics(this));
            }
            for (int tick = 0; tick < 5 && connection.heldAtStart.isEmpty(); tick++) {
                f.candidates.level.tick++;
                set(RuntimeAccess.get(), PrinterRuntime.class, "currentTick", f.candidates.level.tick);
                call(controller, "tick", new Class<?>[0]);
                f.continueActive();
                f.dispatch();
            }

            assertEquals(List.of(enabled ? Items.IRON_PICKAXE : initial), connection.heldAtStart,
                    () -> "The real survival START packet must observe the expected hand: " + toolDiagnostics(this));
            assertEquals(TARGET, connection.packets.getFirst().getPos());
            if (enabled) {
                String preparation = toolSlot < 9 ? "select:" : "swap:";
                int prepared = firstEvent(connection.traffic, preparation);
                assertTrue(prepared >= 0, "The existing tool must travel through actual inventory selection");
                assertTrue(prepared < connection.traffic.indexOf("destroy:START_DESTROY_BLOCK"));
                assertFalse(guard.isWaiting());
            } else {
                assertTrue(connection.traffic.stream().noneMatch(event -> event.startsWith("select:") || event.startsWith("swap:")));
                assertEquals(0, InventoryUtils.getSelectedSlot(player.inventory));
                assertTrue(player.inventory.getItem(toolSlot).is(Items.IRON_PICKAXE));
            }
        }

        @Override public void close() throws Exception {
            try {
                call(controller, "reset", new Class<?>[0]);
            } finally {
                if (sharedRuntimeService) set(null, InventoryUtils.class, "client", previousInventoryClient);
            }
        }
    }

    static class InventoryPlayer extends MiningInteractionRegressionTest.Player {
        @Override public ItemStack getMainHandItem() { return inventory.getItem(InventoryUtils.getSelectedSlot(inventory)); }
        @Override public float getDestroySpeed(BlockState state) {
            return me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils.getBlockBreakingSpeed(this, state, getMainHandItem());
        }
        @Override public boolean hasCorrectToolForDrops(BlockState state) { return getMainHandItem().isCorrectToolForDrops(state); }
        @Override public GameType gameMode() { return GameType.SURVIVAL; }
    }

    /** Same source-wakeup bridge as the production Mixin, with the real fixture-owned scan engine. */
    static class RetryPort extends MiningInteractionRegressionTest.Port {
        MineSelectionRefreshTest.Fixture fixture;
        final List<BlockPos> expired = new ArrayList<>();
        @Override public void onPendingDestroyExpired(BlockPos pos) {
            expired.add(pos.immutable());
            try {
                var scan = (me.aleksilassila.litematica.printer.handler.scan.ScanEngine)
                        get(fixture.mining.mineHandler, FeatureModuleBase.class, "scanEngine");
                scan.reoffer("mine", pos);
            } catch (Exception exception) {
                throw new AssertionError(exception);
            }
        }
    }

    static class ToolConnection extends MiningInteractionRegressionTest.Connection {
        InventoryPlayer player;
        List<String> traffic;
        List<Item> heldAtStart;
        List<ItemStack> stacksAtStart;
        CalibrationTransport transport;
        @Override public net.minecraft.network.Connection getConnection() {
            return transport == null ? super.getConnection() : transport;
        }
        @Override public void send(Packet<?> packet) {
            if (transport != null) transport.record(packet);
            if (packet instanceof ServerboundSetCarriedItemPacket carried) {
                traffic.add("select:" + carried.getSlot());
            } else if (packet instanceof ServerboundPlayerActionPacket action) {
                traffic.add("destroy:" + action.getAction());
                if (action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK) {
                    heldAtStart.add(player.getMainHandItem().getItem());
                    stacksAtStart.add(player.getMainHandItem().copy());
                }
            }
            super.send(packet);
        }
    }

    /** Implements only transport boundaries; policy, fence ordinals and inventory writes remain real. */
    static final class CalibrationTransport extends net.minecraft.network.Connection implements PacketBatchConnection {
        final Object service;
        final List<Packet<?>> outbound = new ArrayList<>();
        final List<List<Packet<?>>> batches = new ArrayList<>();
        CalibrationTransport(Object service) { super(PacketFlow.CLIENTBOUND); this.service = service; }
        @Override public boolean litematica_printer$sendBatch(List<Packet<?>> packets) {
            batches.add(List.copyOf(packets));
            for (Packet<?> packet : packets) record(packet);
            return true;
        }
        void record(Packet<?> packet) {
            InventoryRecoveryFence.inputSent(this, packet);
            try { call(service, "onInventoryInput", new Class<?>[]{Packet.class}, packet); }
            catch (Exception exception) { throw new AssertionError(exception); }
            outbound.add(packet);
            InventoryRecoveryFence.sent(this, packet);
        }
        void receiveStats() throws Exception {
            InventoryRecoveryFence.received(this);
            call(service, "onInventorySynchronized", new Class<?>[]{Object.class}, this);
        }
    }

    static class ToolMode extends MineSelectionRefreshTest.MineGameMode {
        Object realController;
        List<String> traffic;
        @Override public BlockBreakResult litematica_printer$continueDestroyBlock(boolean predict, BlockPos pos, Direction direction,
                boolean delayed, boolean allowToolSwitch) {
            try {
                return (BlockBreakResult) call(realController, "continueDestroy",
                        new Class<?>[]{boolean.class, BlockPos.class, Direction.class, boolean.class, boolean.class},
                        predict, pos, direction, delayed, allowToolSwitch);
            } catch (Exception exception) { throw new AssertionError(exception); }
        }
        @Override public BlockBreakResult litematica_printer$continueDestroyBlockForMine(BlockPos pos, Direction direction, boolean allowToolSwitch) {
            try {
                return (BlockBreakResult) call(realController, "continueForMine", new Class<?>[]{BlockPos.class, Direction.class, boolean.class},
                        pos, direction, allowToolSwitch);
            } catch (Exception exception) { throw new AssertionError(exception); }
        }
        @Override public boolean litematica_printer$isPendingDelayedDestroy(BlockPos pos) {
            try { return (boolean) call(realController, "isPendingDelayedDestroy", new Class<?>[]{BlockPos.class}, pos); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }
        @Override public void litematica_printer$confirmServerBlockUpdate(BlockPos pos) {
            try { call(realController, "confirmServerBlockUpdate", new Class<?>[]{BlockPos.class}, pos); }
            catch (Exception exception) { throw new AssertionError(exception); }
        }
        @Override public void handleContainerInput(int id, int slot, int button, ContainerInput input, net.minecraft.world.entity.player.Player player) {
            assertEquals(ContainerInput.SWAP, input);
            assertEquals(player.inventoryMenu.containerId, id);
            traffic.add("swap:" + slot + ":" + button);
            player.inventoryMenu.clicked(slot, button, input, player);
            var packet = new ServerboundContainerClickPacket(id, player.inventoryMenu.getStateId(),
                    (short) slot, (byte) button, input, new it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap<>(),
                    net.minecraft.network.HashedStack.EMPTY);
            var connection = (ToolConnection) fixture.connection;
            if (connection.transport == null) {
                try {
                    var service = get(realController, realController.getClass(), "toolSwitchService");
                    call(service, "onInventoryInput", new Class<?>[]{Packet.class}, packet);
                } catch (Exception error) { throw new AssertionError(error); }
            }
            connection.send(packet);
        }
    }

    /** Flags select the real optional implementation; no tool-search result or inventory outcome is stubbed. */
    private static final class OptionalToolBindings implements AutoCloseable {
        final Object loader = net.fabricmc.loader.api.FabricLoader.getInstance();
        final Object oldMods;
        final Map<String, Object> previous = new LinkedHashMap<>();
        final Map<String, List<Integer>> previousSlots = new LinkedHashMap<>();
        final Class<?> external;
        final Object externalNearBroken;
        final boolean oldExternalNearBroken;

        @SuppressWarnings("unchecked")
        OptionalToolBindings(boolean enabled) throws Exception {
            TweakerooUtils.isToolSwitchEnabled();
            oldMods = get(loader, loader.getClass(), "modMap");
            var mods = new HashMap<>((Map<String, Object>) oldMods);
            mods.put("tweakeroo", null);
            set(loader, loader.getClass(), "modMap", mods);
            for (String field : List.of("tweakToolSwitchEnum", "tweakSwapAlmostBrokenToolsEnum", "getBooleanValueMethod", "trySwitchToEffectiveToolMethod")) {
                previous.put(field, get(null, TweakerooUtils.class, field));
            }
            var effective = new ConfigBoolean("effectiveTool", enabled);
            set(null, TweakerooUtils.class, "tweakToolSwitchEnum", effective);
            set(null, TweakerooUtils.class, "tweakSwapAlmostBrokenToolsEnum", new ConfigBoolean("nearBroken", false));
            set(null, TweakerooUtils.class, "getBooleanValueMethod", effective.getClass().getMethod("getBooleanValue"));
            external = Class.forName("fi.dy.masa.tweakeroo.util.InventoryUtils");
            set(null, TweakerooUtils.class, "trySwitchToEffectiveToolMethod", external.getMethod("trySwitchToEffectiveTool", BlockPos.class));
            for (String field : List.of("TOOL_SWITCHABLE_SLOTS", "TOOL_SWITCH_IGNORED_SLOTS")) {
                previousSlots.put(field, List.copyOf((List<Integer>) get(null, external, field)));
            }
            externalNearBroken = Class.forName("fi.dy.masa.tweakeroo.config.FeatureToggle")
                    .getField("TWEAK_SWAP_ALMOST_BROKEN_TOOLS").get(null);
            oldExternalNearBroken = (boolean) externalNearBroken.getClass().getMethod("getBooleanValue").invoke(externalNearBroken);
            externalNearBroken.getClass().getMethod("setBooleanValue", boolean.class).invoke(externalNearBroken, false);
            // These are the user's saved Tweakeroo settings, not an artificial hotbar-only search.
            external.getMethod("setToolSwitchableSlots", String.class).invoke(null, "1-9");
            external.getMethod("setToolSwitchIgnoreSlots", String.class).invoke(null, "");
        }

        @SuppressWarnings("unchecked")
        @Override public void close() throws Exception {
            for (var entry : previousSlots.entrySet()) {
                var slots = (List<Integer>) get(null, external, entry.getKey());
                slots.clear();
                slots.addAll(entry.getValue());
            }
            externalNearBroken.getClass().getMethod("setBooleanValue", boolean.class)
                    .invoke(externalNearBroken, oldExternalNearBroken);
            for (var entry : previous.entrySet()) set(null, TweakerooUtils.class, entry.getKey(), entry.getValue());
            set(loader, loader.getClass(), "modMap", oldMods);
        }
    }

    public static final class PickSlots {
        public static List<Integer> getPICK_BLOCKABLE_SLOTS() { return List.of(2); }
        public static int getEmptyPickBlockableHotbarSlot(Inventory inventory) {
            assertTrue(inventory.getItem(2).isEmpty(), "The configured inventory destination starts empty");
            return 2;
        }
    }

    public static final class EasyPlace {
        public static void callSetEasyPlaceLastPickBlockTime() { }
    }

    private static final class ToolLoader extends ClassLoader {
        private static final String PREFIX = "me.aleksilassila.litematica.printer.";
        private static final Set<String> ISOLATED = Set.of("utils.InventoryUtils", "utils.ToolInventorySelector",
                "interaction.ToolSwitchService", "interaction.MiningInteractionController", "interaction.MiningFeedback");
        ToolLoader() { super(SurvivalMineToolSwitchTest.class.getClassLoader()); }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            String rootName = name.contains("$") ? name.substring(0, name.indexOf('$')) : name;
            if (!rootName.startsWith(PREFIX) || !ISOLATED.contains(rootName.substring(PREFIX.length()))) return super.loadClass(name, resolve);
            synchronized (getClassLoadingLock(name)) {
                Class<?> type = findLoadedClass(name);
                if (type == null) {
                    try (var input = getParent().getResourceAsStream(name.replace('.', '/') + ".class")) {
                        if (input == null) throw new ClassNotFoundException(name);
                        var node = new ClassNode();
                        new ClassReader(input).accept(node, 0);
                        for (var method : node.methods) for (var instruction : method.instructions) {
                            if (!(instruction instanceof MethodInsnNode invoke)) continue;
                            String replacement = switch (invoke.owner) {
                                case "me/aleksilassila/litematica/printer/mixin/printer/litematica/InventoryUtilsAccessor" -> Type.getInternalName(PickSlots.class);
                                case "me/aleksilassila/litematica/printer/mixin/printer/litematica/EasyPlaceUtilsAccessor" -> Type.getInternalName(EasyPlace.class);
                                default -> null;
                            };
                            if (replacement != null) {
                                invoke.owner = replacement;
                                invoke.itf = false;
                                invoke.setOpcode(Opcodes.INVOKESTATIC);
                            }
                        }
                        var writer = new ClassWriter(0);
                        node.accept(writer);
                        byte[] bytes = writer.toByteArray();
                        type = defineClass(name, bytes, 0, bytes.length);
                    } catch (java.io.IOException exception) { throw new ClassNotFoundException(name, exception); }
                }
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }

    private static int firstEvent(List<String> events, String prefix) {
        for (int i = 0; i < events.size(); i++) if (events.get(i).startsWith(prefix)) return i;
        return -1;
    }

    /** Called only by failing assertions; never primes a guard or changes a test decision. */
    private static String toolDiagnostics(ToolFixture tool) {
        try {
            Object service = get(tool.controller, tool.controller.getClass(), "toolSwitchService");
            Object durability = get(service, service.getClass(), "durabilityGuard");
            Class<?> type = durability.getClass();
            Minecraft client = (Minecraft) get(durability, type, "client");
            int selected = InventoryUtils.getSelectedSlot(tool.player.inventory);
            int[] remaining = (int[]) get(durability, type, "remaining");
            ItemStack[] identities = (ItemStack[]) get(durability, type, "identities");
            ItemStack[] baselines = (ItemStack[]) get(durability, type, "baselines");
            var values = new ArrayList<String>();
            for (String field : List.of("dirty", "initialized", "pending", "bypassed", "hasDebt")) {
                values.add(field + "=" + get(durability, type, field));
            }
            values.add("playerScope=" + identity(get(durability, type, "playerScope")) + "/current=" + identity(client.player));
            values.add("levelScope=" + identity(get(durability, type, "levelScope")) + "/current=" + identity(client.level));
            values.add("connectionScope=" + identity(get(durability, type, "connectionScope"))
                    + "/current=" + identity(tool.connection.getConnection()));
            values.add("guardClient=" + identity(client) + "/fixtureClient=" + identity(tool.f.mining.client));
            values.add("selected=" + selected + ", budget=" + remaining[selected]
                    + ", budgets=" + java.util.Arrays.toString(remaining));
            values.add("identity=" + identity(identities[selected]) + "/hand=" + identity(tool.player.getMainHandItem())
                    + ", sameRef=" + (identities[selected] == tool.player.getMainHandItem())
                    + ", baseline=" + baselines[selected] + ", currentHand=" + tool.player.getMainHandItem());
            values.add("active=" + get(tool.controller, tool.controller.getClass(), "activeDestroyPos")
                    + ", portDestroying=" + tool.f.mining.port.destroying + ", portPos=" + tool.f.mining.port.pos
                    + ", portProgress=" + tool.f.mining.port.progress);
            values.add("waiting=" + tool.guard.isWaiting() + ", traffic=" + tool.connection.traffic);
            return String.join("; ", values);
        } catch (Exception exception) {
            return "diagnostic unavailable: " + exception + "; traffic=" + tool.connection.traffic;
        }
    }

    private static String identity(Object value) {
        return value == null ? "null" : value.getClass().getSimpleName() + "@" + Integer.toHexString(System.identityHashCode(value));
    }

    @SuppressWarnings("unchecked")
    private static String deliverSource(MineSelectionRefreshTest.Fixture f, PrinterBox bounds) throws Exception {
        var source = (Iterable<BlockPos>) handlerCall(f, "getIterationPositions", new Class<?>[]{PrinterBox.class}, bounds);
        var positions = new ArrayList<String>();
        for (BlockPos pos : source) {
            boolean accepted = f.mining.mineHandler.canIterationBlockPos(pos);
            positions.add(pos + "=" + accepted);
            if (accepted) f.iterate(pos);
        }
        String availability = source instanceof me.aleksilassila.litematica.printer.handler.scan.ScanCandidateIterable scan
                ? scan.availability().toString() : source.getClass().getName();
        return availability + positions;
    }

    private static String recoveryState(ToolFixture tool, MineSelectionRefreshTest.Fixture f, BlockPos remaining,
                                        long deadline, String source) throws Exception {
        Object feedback = get(tool.controller, tool.controller.getClass(), "feedback");
        Object queue = get(f.mining.mineHandler, MineHandler.class, "candidates");
        var size = queue.getClass().getDeclaredMethod("size");
        size.setAccessible(true);
        return "expiry=" + ((RetryPort) f.mining.port).expired
                + ", pending=" + call(tool.controller, "isPendingDelayedDestroy", new Class<?>[]{BlockPos.class}, remaining)
                + ", feedback=" + get(feedback, feedback.getClass(), "pendingDelayedDestroys")
                + ", deadline=" + deadline + ", now=" + f.candidates.level.tick
                + ", source=" + source + ", candidates=" + size.invoke(queue)
                + ", selected=" + InventoryUtils.getSelectedSlot(tool.player.inventory)
                + ", hand=" + tool.player.getMainHandItem() + ", traffic=" + tool.connection.traffic;
    }

    private static Object handlerCall(MineSelectionRefreshTest.Fixture f, String name, Class<?>[] types, Object... values) throws Exception {
        var method = MineHandler.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        return method.invoke(f.mining.mineHandler, values);
    }

    private static Object call(Object target, String name, Class<?>[] types, Object... values) throws Exception {
        try { return target.getClass().getMethod(name, types).invoke(target, values); }
        catch (InvocationTargetException exception) {
            if (exception.getCause() instanceof Exception cause) throw cause;
            if (exception.getCause() instanceof Error cause) throw cause;
            throw exception;
        }
    }
}
//#endif
