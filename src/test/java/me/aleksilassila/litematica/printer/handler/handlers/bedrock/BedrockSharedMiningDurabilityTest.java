//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.interaction.MiningInteractionController;
import me.aleksilassila.litematica.printer.interaction.MiningInteractionPort;
import me.aleksilassila.litematica.printer.interaction.ToolPreparationResult;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

/** The actual confirmed Bedrock budget and Controller share one production tool service. */
class BedrockSharedMiningDurabilityTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test
    void zeroHardnessShearsCleanupSendsItsAffordablePrefixInsteadOfRejectingTheWholeBatch() throws Exception {
        int previousReserve = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        int previousRange = Configs.Core.WORK_RANGE.getIntegerValue();
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            ItemStack shears = new ItemStack(Items.SHEARS);
            shears.setDamageValue(shears.getMaxDamage() - 24);
            f.player().getInventory().setItem(0, shears);
            f.synchronizeInventory();
            var positions = List.of(new BlockPos(-2, 64, -2), new BlockPos(-2, 64, 2),
                    new BlockPos(2, 64, -2), new BlockPos(2, 64, 2));
            for (BlockPos pos : positions) {
                f.level.blocks.put(pos, Blocks.REDSTONE_TORCH.defaultBlockState());
                assertEquals(0.0F, f.client.level.getBlockState(pos).getDestroySpeed(f.client.level, pos));
                assertTrue(BedrockEnvironment.canInteract(pos));
            }
            var breaker = new BedrockBreaker(f.client, f.placer);
            assertEquals(3, breaker.breakCleanupBatch(positions, 4),
                    "Zero-hardness shears still cost durability: choose the three-use prefix before atomic reservation");
            assertEquals(positions.subList(0, 3), f.transport.packets.stream()
                    .filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast)
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK)
                    .map(ServerboundPlayerActionPacket::getPos).toList());
            assertEquals(24, f.player().getMainHandItem().getMaxDamage() - f.player().getMainHandItem().getDamageValue(),
                    "Actual packet submission does not fake a durability receipt");
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previousReserve);
            Configs.Core.WORK_RANGE.setIntegerValue(previousRange);
        }
    }

    @Test
    void anOverBudgetLookaheadCannotStartCalibrationAndDiscardTheSendableRawPrefix() throws Exception {
        int previousReserve = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        boolean previousAuto = Configs.Break.BREAK_AUTO_TOOL.getBooleanValue();
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(20);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(false);
            ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 25);
            f.player().getInventory().setItem(0, tool);
            var session = (BedrockInventorySession) f.session();
            f.validateInitial(session);
            BlockPos first = new BlockPos(0, 64, 0);
            for (int target = 0; target < 5; target++) f.level.blocks.put(first.east(target), Blocks.STONE.defaultBlockState());
            var service = f.runtime.toolSwitchService();
            assertEquals(ToolPreparationResult.READY, service.prepareForBreak(first, Blocks.STONE.defaultBlockState(), false));
            // One real submitted use creates debt; both shadow balances now have 24 remaining.
            // The client still reports 25, so this is not an artificial balance initialization.
            var preceding = rawBreaks(first, 1, 1);
            assertTrue(session.reserveRawBreaks(preceding));
            assertTrue(NetworkUtils.sendPacketBatch(preceding));
            session.recordToolUse(0);
            assertEquals(24, session.availableToolUses());
            assertEquals(25, f.player().getMainHandItem().getMaxDamage() - f.player().getMainHandItem().getDamageValue());
            long queriesBeforeLookahead = f.markers();
            for (int uses = 1; uses <= 3; uses++) assertTrue(session.canReserveToolUses(uses));
            assertFalse(session.canReserveToolUses(4), "Only the fourth candidate exceeds reserve 20 plus one delayed-tail use");
            assertEquals(queriesBeforeLookahead, f.markers(),
                    "A rejected planning lookahead must not launch calibration and invalidate the already sendable prefix");
            var prefix = rawBreaks(first.east(), 3, 3);
            assertTrue(session.reserveRawBreaks(prefix), "The three accepted candidates must still reserve atomically in this tick");
            assertTrue(NetworkUtils.sendPacketBatch(prefix));
            for (int uses = 0; uses < 3; uses++) session.recordToolUse(0);
            assertEquals(4, f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast)
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).count(),
                    "The earlier use plus all three accepted prefix targets must actually reach transport");
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previousReserve);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(previousAuto);
        }
    }

    private static List<Packet<?>> rawBreaks(BlockPos first, int count, int firstSequence) {
        var packets = new ArrayList<Packet<?>>();
        for (int target = 0; target < count; target++) {
            packets.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                    first.east(target), Direction.DOWN, firstSequence + target * 2));
            packets.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                    first.east(target), Direction.DOWN, firstSequence + target * 2 + 1));
        }
        return List.copyOf(packets);
    }

    @Test
    void rawBedrockAndControllerStartsCannotSpendTheSameUnsynchronizedToolBudget() throws Exception {
        int previousReserve = Configs.Break.BREAK_TOOL_RESERVE.getIntegerValue();
        boolean previousAuto = Configs.Break.BREAK_AUTO_TOOL.getBooleanValue();
        boolean previousDelayed = Configs.Break.BREAK_USE_DELAYED_DESTROY.getBooleanValue();
        try (var f = new BedrockInventorySessionTest.Fixture()) {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(1);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(false);
            Configs.Break.BREAK_USE_DELAYED_DESTROY.setBooleanValue(false);
            var original = f.client.player;
            var player = f.allocate(FastPlayer.class);
            player.inventory = original.getInventory();
            set(player, Player.class, "inventory", player.inventory);
            set(player, Player.class, "abilities", original.getAbilities());
            set(player, Player.class, "inventoryMenu", original.inventoryMenu);
            player.containerMenu = player.inventoryMenu;
            set(player, LocalPlayer.class, "connection", f.connection);
            f.client.player = player;
            set(f.client, Minecraft.class, "soundManager", f.allocate(SilentSounds.class));

            ItemStack tool = new ItemStack(Items.DIAMOND_PICKAXE);
            tool.setDamageValue(tool.getMaxDamage() - 4);
            player.inventory.setItem(0, tool);
            var session = (BedrockInventorySession) f.session();
            f.validateInitial(session); // Real inventory request, snapshot and ordered reply; no budget writes.
            assertEquals(4, session.availableToolUses());
            var service = f.runtime.toolSwitchService();
            BlockPos first = new BlockPos(0, 64, 0);
            for (int target = 0; target < 4; target++) f.level.blocks.put(first.east(target), Blocks.STONE.defaultBlockState());
            assertEquals(ToolPreparationResult.READY, service.prepareForBreak(first, Blocks.STONE.defaultBlockState(), false));
            assertTrue(session.canReserveToolUses(2), "The real projected Bedrock guard permits two uses from four, keeping reserve plus delayed tail");
            assertFalse(session.canReserveToolUses(3));
            var raw = new ArrayList<Packet<?>>();
            for (int target = 0; target < 2; target++) {
                raw.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK,
                        first.east(target), Direction.DOWN, target * 2 + 1));
                raw.add(new ServerboundPlayerActionPacket(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK,
                        first.east(target), Direction.DOWN, target * 2 + 2));
            }
            // Compatibility with the old raw boundary: absent reservation must expose the real RED.
            try {
                assertTrue((boolean) service.getClass().getMethod("reserveRawBreaks", List.class).invoke(service, raw));
            } catch (NoSuchMethodException previousImplementation) { }
            assertTrue(NetworkUtils.sendPacketBatch(raw));
            session.recordToolUse(0);
            session.recordToolUse(0);
            assertEquals(2, session.availableToolUses());
            assertEquals(4, player.getMainHandItem().getMaxDamage() - player.getMainHandItem().getDamageValue(),
                    "Raw transport must not manufacture a client durability receipt");

            var port = (MiningInteractionPort) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{MiningInteractionPort.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "client" -> f.client;
                        case "isDestroying", "isInventorySwitchPending", "matchesDestroyTarget" -> false;
                        case "destroyProgress" -> 0.0F;
                        case "destroyPos" -> BlockPos.ZERO;
                        case "destroyingItem" -> ItemStack.EMPTY;
                        case "actionPacket" -> new ServerboundPlayerActionPacket(
                                (ServerboundPlayerActionPacket.Action) args[0], (BlockPos) args[1], (Direction) args[2], (int) args[3]);
                        case "ensureCarriedItemSent", "onPendingDestroyExpired" -> null;
                        default -> throw new AssertionError("Unexpected mining port call: " + method.getName());
                    });
            var controller = new MiningInteractionController(port, service);
            float progress = Blocks.STONE.defaultBlockState().getDestroyProgress(player, f.client.level, first);
            assertTrue(progress >= 0.7F && progress < 1.0F);
            controller.continueForMine(first.east(2), Direction.DOWN, false);
            controller.continueForMine(first.east(3), Direction.DOWN, false);
            long starts = f.transport.packets.stream().filter(ServerboundPlayerActionPacket.class::isInstance)
                    .map(ServerboundPlayerActionPacket.class::cast)
                    .filter(packet -> packet.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK).count();
            assertEquals(2, starts, "Raw Bedrock and Controller must share the two spendable uses, not each emit two STARTs");
        } finally {
            Configs.Break.BREAK_TOOL_RESERVE.setIntegerValue(previousReserve);
            Configs.Break.BREAK_AUTO_TOOL.setBooleanValue(previousAuto);
            Configs.Break.BREAK_USE_DELAYED_DESTROY.setBooleanValue(previousDelayed);
        }
    }

    static class FastPlayer extends BedrockPlacementRegressionTest.TestPlayer {
        @Override public float getDestroySpeed(BlockState state) { return 36.0F; }
        @Override public boolean hasCorrectToolForDrops(BlockState state) { return true; }
    }

    static class SilentSounds extends SoundManager {
        SilentSounds() { super(null); }
        @Override public SoundEngine.PlayResult play(SoundInstance sound) { return null; }
    }
}
//#endif
