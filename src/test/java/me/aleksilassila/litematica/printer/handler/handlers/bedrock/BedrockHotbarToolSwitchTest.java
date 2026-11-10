//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.tweakeroo.TweakerooToolSwitchPort;
import me.aleksilassila.litematica.printer.interaction.ToolPreparationResult;
import me.aleksilassila.litematica.printer.interaction.ToolSwitchService;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundClientCommandPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** The real cleanup boundary distinguishes unchanged inventory from actual menu mutations. */
class BedrockHotbarToolSwitchTest {
    private static final BlockPos PISTON = new BlockPos(0, 65, 0);

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @ParameterizedTest @ValueSource(strings = {"hotbar", "inventory", "inventory_then_hotbar"})
    void onlyASelectionWithUnchangedInventoryCanSkipTheFullMenuFence(String change) throws Exception {
        try (var f = new Fixture(change)) {
            assertFalse(f.owner.breakCleanupBlock(PISTON, Direction.DOWN));

            assertTrue(f.wire.packets.stream().noneMatch(ServerboundPlayerActionPacket.class::isInstance),
                    "A changed tool cannot authorize a hard START before its real acknowledgement");
            if (change.equals("hotbar")) {
                assertEquals(1, f.inventoryMarkers(), "A pure SetCarried needs the equipment tick, without a second full inventory snapshot");
                assertFalse(f.session.isSettling());
                assertFalse(f.guard.isWaiting(), "Only this known hotbar selection's generic receipt is superseded");
                var marker = f.wire.packets.stream().filter(ServerboundUseItemPacket.class::isInstance)
                        .map(ServerboundUseItemPacket.class::cast).findFirst().orElseThrow();
                assertTrue(f.session.isToolSettling());
                f.session.acknowledgeToolTick(f.wire, marker.getSequence());
                assertTrue(f.session.prepareCriticalToolTick());
                assertEquals(3, InventoryUtils.getSelectedSlot(f.client.player.getInventory()));
            } else {
                assertEquals(2, f.inventoryMarkers(), "A real menu mutation keeps its ordered snapshot even if selectedSlot also changed");
                assertTrue(f.session.isSettling());
                assertTrue(f.wire.packets.stream().noneMatch(ServerboundUseItemPacket.class::isInstance));
            }
        }
    }

    @Test void genericMiningRetainsItsExistingToolSwitchResultAndGuard() throws Exception {
        try (var f = new Fixture("hotbar")) {
            assertEquals(ToolPreparationResult.SWITCHED_WAITING_SYNC,
                    f.service.prepareForBreak(PISTON, Blocks.PISTON.defaultBlockState(), false));
            assertTrue(f.guard.isWaiting());
            assertEquals(1, f.inventoryMarkers());
        }
    }

    @ParameterizedTest @CsvSource({"true, true", "true, false", "false, true", "false, false"})
    void aUsableReplacementIsNotRepeatedlyReplacedByTheOldGuardedTool(boolean guardActive, boolean cleanup) throws Exception {
        try (var f = new BedrockAdmissionPipelineTest.Fixture()) {
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.setDamageValue(old.getMaxDamage() - 1);
            f.put(3, Items.DIAMOND_PICKAXE, 1);
            f.client.player.getInventory().setItem(0, old);
            f.cleanupWorld();
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 3);
            var policy = (TweakerooToolSwitchPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TweakerooToolSwitchPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isDurabilityGuardActive" -> guardActive;
                        case "isCurrentToolUsable" -> Fixture.usable((ItemStack) args[0]);
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            var guard = new InventorySwitchGuard(f.client, f.runtime::currentTick);
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            set(f.runtime, PrinterRuntime.class, "toolSwitchService", new ToolSwitchService(f.client, policy, guard));

            assertTrue(f.enginePlacer.prepareTool(Blocks.PISTON.defaultBlockState(), cleanup));

            assertEquals(guardActive ? 3 : 0, InventoryUtils.getSelectedSlot(f.client.player.getInventory()),
                    "A guarded nearly-broken candidate must not undo the usable tool selected on the previous tick");
        }
    }

    private static final class Fixture extends BedrockToolSyncTest.Fixture {
        final InventorySwitchGuard guard;
        final ToolSwitchService service;
        final BedrockInventorySession session;
        final BedrockPlacer owner;

        Fixture(String change) throws Exception {
            set(client.player, net.minecraft.world.entity.player.Player.class, "abilities",
                    new net.minecraft.world.entity.player.Abilities());
            var old = new ItemStack(Items.DIAMOND_PICKAXE);
            old.setDamageValue(old.getMaxDamage() - 1);
            client.player.getInventory().setItem(0, old);
            client.player.getInventory().setItem(3, new ItemStack(Items.DIAMOND_PICKAXE));
            client.player.getInventory().setItem(10, new ItemStack(Items.DIAMOND_PICKAXE));
            InventoryUtils.setSelectedSlot(client.player.getInventory(), 0);
            guard = new InventorySwitchGuard(client, runtime::currentTick);
            var policy = (TweakerooToolSwitchPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{TweakerooToolSwitchPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "isDurabilityGuardActive" -> true;
                        case "isCurrentToolUsable" -> usable((ItemStack) args[0]);
                        case "prepareCurrentTool" -> {
                            if (!usable(client.player.getMainHandItem())) {
                                if (!change.equals("hotbar")) {
                                    // Apply vanilla's real menu SWAP prediction; no server result is invented.
                                    client.player.inventoryMenu.clicked(10, 0, ContainerInput.SWAP, client.player);
                                }
                                if (!change.equals("inventory")) InventoryUtils.setHotbarSlot(3, client.player.getInventory());
                            }
                            yield usable(client.player.getMainHandItem());
                        }
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            service = new ToolSwitchService(client, policy, guard);
            set(runtime, PrinterRuntime.class, "inventorySwitchGuard", guard);
            set(runtime, PrinterRuntime.class, "toolSwitchService", service);
            session = new BedrockInventorySession(client, new BedrockInventorySession.InventoryAccess() {
                public boolean selectOffhand(Item item) { return client.player.getOffhandItem().is(item); }
                public boolean materialRequiresInventorySwap(Item item) { return false; }
                public boolean selectMaterial(Item item) { throw new AssertionError("No construction belongs in this tool test"); }
                public boolean toolRequiresInventorySwap(BlockState state, boolean cleanup) { return false; }
                public boolean selectTool(BlockState state, boolean cleanup) { return true; }
            });
            owner = new BedrockPlacer(client);
            set(owner, BedrockPlacer.class, "inventory", session);
            assertFalse(session.preparePistons());
            client.player.inventoryMenu.initializeContents(client.player.inventoryMenu.getStateId() + 1,
                    client.player.inventoryMenu.slots.stream().map(slot -> slot.getItem().copy()).toList(), ItemStack.EMPTY);
            me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence.received(wire);
            assertTrue(session.preparePistons());
            level.blocks.put(PISTON, Blocks.PISTON.defaultBlockState());
        }

        long inventoryMarkers() { return wire.packets.stream().filter(ServerboundClientCommandPacket.class::isInstance).count(); }
        private static boolean usable(ItemStack tool) { return tool.getMaxDamage() - tool.getDamageValue() > 5; }
    }
}
//#endif
