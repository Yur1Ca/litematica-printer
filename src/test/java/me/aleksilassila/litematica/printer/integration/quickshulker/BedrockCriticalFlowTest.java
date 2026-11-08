//#if MC == 260100
package me.aleksilassila.litematica.printer.integration.quickshulker;

import java.lang.reflect.InvocationTargetException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacer;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InteractionUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PacketBatchConnection;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.piston.PistonBaseBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static me.aleksilassila.litematica.printer.integration.quickshulker.BedrockToolEligibilityTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real critical executor and efficiency-V preparation; captures actual outbound game packets. */
class BedrockCriticalFlowTest {
    private static final BlockPos BASE = BlockPos.ZERO;
    private static final BlockPos PISTON = BASE.above();
    private static final BlockPos TORCH = PISTON.east();

    @BeforeAll static void bootstrap() { InventoryRegressionTest.bootstrap(); }

    static class TraceLevel extends MiningLevel {
        Map<BlockPos, BlockState> blocks;
        net.minecraft.world.level.border.WorldBorder border;
        boolean obstructed;
        @Override public net.minecraft.world.level.border.WorldBorder getWorldBorder() { return border; }
        @Override public long getGameTime() { return 0L; }
        @Override public BlockState getBlockState(BlockPos pos) { return blocks.getOrDefault(pos, Blocks.AIR.defaultBlockState()); }
        @Override public int getMinY() { return -64; }
        @Override public int getHeight() { return 384; }
        @Override public boolean isUnobstructed(BlockState state, BlockPos pos,
                net.minecraft.world.phys.shapes.CollisionContext context) { return !obstructed; }
    }

    static class Mode extends MultiPlayerGameMode {
        Mode() { super(null, null); }
        @Override public GameType getPlayerMode() { return GameType.SURVIVAL; }
    }

    static class Transport extends net.minecraft.network.Connection implements PacketBatchConnection {
        static Transport lastBatchTransport;
        List<Packet<?>> packets;
        List<List<Packet<?>>> batches;
        boolean reject;
        Transport() { super(PacketFlow.CLIENTBOUND); }
        @Override public boolean litematica_printer$sendBatch(List<Packet<?>> outbound) {
            lastBatchTransport = this;
            if (reject) return false;
            batches.add(List.copyOf(outbound));
            packets.addAll(outbound);
            for (var packet : outbound) {
                InventoryRecoveryFence.inputSent(this, packet);
                InventoryRecoveryFence.sent(this, packet);
            }
            return true;
        }
    }

    static class TraceConnection extends BedrockToolEligibilityTest.Connection {
        Transport transport;
        @Override public net.minecraft.network.Connection getConnection() { return transport; }
    }

    static class Fixture extends BedrockToolEligibilityTest.Fixture {
        final TraceLevel traceLevel = allocate(TraceLevel.class);
        final TraceConnection traceConnection = allocate(TraceConnection.class);
        final Transport transport = allocate(Transport.class);
        final boolean oldRangeCheck = Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.getBooleanValue();
        final PlayerLook oldScopedLook = NetworkUtils.getScopedLookOverride();
        final Object executor;
        final java.lang.reflect.Method submit;

        Fixture() throws Exception {
            super(5);
            traceLevel.blocks = new HashMap<>();
            traceLevel.border = new net.minecraft.world.level.border.WorldBorder();
            traceLevel.blocks.put(BASE, Blocks.BEDROCK.defaultBlockState());
            traceLevel.blocks.put(PISTON, Blocks.PISTON.defaultBlockState()
                    .setValue(PistonBaseBlock.FACING, Direction.UP).setValue(PistonBaseBlock.EXTENDED, true));
            traceLevel.blocks.put(TORCH, Blocks.REDSTONE_TORCH.defaultBlockState());
            traceLevel.blocks.put(TORCH.below(), Blocks.SLIME_BLOCK.defaultBlockState());
            client.level = traceLevel;
            client.gameMode = allocate(Mode.class);
            traceConnection.packets = connection.packets;
            traceConnection.transport = transport;
            transport.packets = connection.packets;
            transport.batches = new ArrayList<>();
            set(player, net.minecraft.client.player.LocalPlayer.class, "connection", traceConnection);
            set(player, Player.class, "inventoryMenu", new InventoryMenu(player.inventory, true, player));
            player.containerMenu = player.inventoryMenu;
            set(player, Entity.class, "position", new Vec3(0.5, 0, 2.5));
            set(player, Entity.class, "blockPosition", new BlockPos(0, 0, 2));
            player.inventory.setItem(40, new ItemStack(Items.PISTON, 2));
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(false);
            for (var type : List.of(ConfigUtils.class, InteractionUtils.class, NetworkUtils.class)) {
                Class.forName(type.getName(), true, type.getClassLoader());
                for (var field : type.getDeclaredFields()) {
                    if (field.getType() != Minecraft.class) continue;
                    field.setAccessible(true);
                    if (!staticClients.containsKey(field)) staticClients.put(field, field.get(null));
                    putStatic(field, client);
                }
            }
            var placerConstructor = BedrockPlacer.class.getDeclaredConstructor(Minecraft.class,
                    Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockNetworkSync"), boolean.class);
            placerConstructor.setAccessible(true);
            var placer = placerConstructor.newInstance(client, null, true);
            var preparePistons = BedrockPlacer.class.getDeclaredMethod("preparePistons");
            preparePistons.setAccessible(true);
            Transport.lastBatchTransport = null;
            assertFalse((boolean) preparePistons.invoke(placer));
            assertSame(transport, Transport.lastBatchTransport,
                    "The inventory baseline must be written through this fixture's connection");
            InventoryRecoveryFence.received(transport);
            assertTrue((boolean) preparePistons.invoke(placer));
            var prepareTool = BedrockPlacer.class.getDeclaredMethod("prepareCriticalToolTick");
            prepareTool.setAccessible(true);
            assertFalse((boolean) prepareTool.invoke(placer));
            var ack = BedrockPlacer.class.getDeclaredMethod("acknowledgeToolTick", Object.class, int.class);
            ack.setAccessible(true);
            int sequence = transport.packets.stream().filter(net.minecraft.network.protocol.game.ServerboundUseItemPacket.class::isInstance)
                    .map(net.minecraft.network.protocol.game.ServerboundUseItemPacket.class::cast)
                    .mapToInt(net.minecraft.network.protocol.game.ServerboundUseItemPacket::getSequence).max().orElseThrow();
            ack.invoke(placer, transport, sequence);
            assertTrue((boolean) prepareTool.invoke(placer));
            transport.packets.clear();
            transport.batches.clear();
            var type = Class.forName("me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockCriticalExecutor");
            var constructor = type.getDeclaredConstructor(BedrockPlacer.class);
            constructor.setAccessible(true);
            executor = constructor.newInstance(placer);
            submit = type.getDeclaredMethod("submit", net.minecraft.client.multiplayer.ClientLevel.class,
                    BlockPos.class, BlockPos.class, Direction.class, Set.class);
            submit.setAccessible(true);
        }

        boolean submit(Set<BlockPos> torches) throws Exception {
            try {
                return (boolean) submit.invoke(executor, traceLevel, BASE, PISTON, Direction.DOWN, torches);
            } catch (InvocationTargetException error) {
                if (error.getCause() instanceof Exception cause) throw cause;
                if (error.getCause() instanceof Error cause) throw cause;
                throw error;
            }
        }

        void assertRejectedBeforeBreak(Set<BlockPos> torches) throws Exception {
            boolean accepted = false;
            Throwable failure = null;
            try { accepted = submit(torches); } catch (Throwable error) { failure = error; }
            assertTrue(connection.packets.stream().noneMatch(packet -> packet instanceof ServerboundPlayerActionPacket action
                            && action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK),
                    "Preflight rejection must happen before the first torch/base START: " + describe(connection.packets));
            assertNull(failure, "Rejected preflight must return cleanly");
            assertFalse(accepted);
            assertEquals(0, get(executor, executor.getClass(), "reservedPistons"));
        }

        @Override public void close() throws Exception {
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(oldRangeCheck);
            NetworkUtils.setScopedLookOverride(oldScopedLook);
            super.close();
        }
    }

    static String describe(List<Packet<?>> packets) {
        return packets.stream().map(packet -> packet instanceof ServerboundPlayerActionPacket action
                ? action.getAction() + "@" + action.getPos() : packet.getClass().getSimpleName()).toList().toString();
    }

    static void writeTrace(Fixture f, String fileName, String expected) throws Exception {
        var lines = new ArrayList<String>();
        lines.add("# target=0,0,0");
        lines.add("# base=0,1,0");
        lines.add("# torch=1,1,0");
        lines.add("# expected=" + expected);
        for (int batch = 0; batch < f.transport.batches.size(); batch++) {
            for (var packet : f.transport.batches.get(batch)) {
                String prefix = "0\t" + batch + "\t";
                if (packet instanceof ServerboundPlayerActionPacket action) {
                    String kind = action.getAction() == ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK ? "START"
                            : action.getAction() == ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK ? "STOP" : null;
                    if (kind != null) lines.add(prefix + kind + "\t" + coordinates(action.getPos()) + "\t" + action.getDirection().name());
                } else if (packet instanceof ServerboundMovePlayerPacket move && move.hasRotation()) {
                    lines.add(prefix + "ROT\t" + move.getYRot(0) + "\t" + move.getXRot(0));
                } else if (packet instanceof ServerboundSetCarriedItemPacket selection) {
                    lines.add(prefix + "SET_CARRIED_ITEM\t" + selection.getSlot());
                } else if (packet instanceof ServerboundUseItemOnPacket use) {
                    var hit = use.getHitResult();
                    var point = hit.getLocation();
                    lines.add(prefix + "USE_ITEM_ON\t" + use.getHand() + "\t" + coordinates(hit.getBlockPos())
                            + "\t" + hit.getDirection().name() + "\t" + point.x + "\t" + point.y + "\t" + point.z);
                }
            }
        }
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        for (Path candidate = root; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve(".git"))) { root = candidate; break; }
        }
        Path output = root.resolve(".gradle/bedrock-repair-20261108").resolve(fileName);
        Files.createDirectories(output.getParent());
        Files.write(output, lines, StandardCharsets.UTF_8);
    }

    static String coordinates(BlockPos pos) { return pos.getX() + "\t" + pos.getY() + "\t" + pos.getZ(); }

    @Test void eligibleCriticalAttemptSendsOneRealBatchWithBreakRotationAndOffhandUse() throws Exception {
        try (var f = new Fixture()) {
            boolean accepted = false;
            Throwable failure = null;
            try { accepted = f.submit(Set.of(TORCH)); } catch (Throwable error) { failure = error; }
            writeTrace(f, "production-critical.tsv", "air");
            assertEquals(1, f.transport.batches.size(), "The entire exploit must use the transport batch path");
            assertNull(failure);
            assertTrue(accepted);
            var batch = f.transport.batches.getFirst();
            assertEquals(6, batch.size(), describe(batch));
            assertEquals(0, assertInstanceOf(ServerboundSetCarriedItemPacket.class, batch.get(0)).getSlot());
            var rotation = assertInstanceOf(ServerboundMovePlayerPacket.class, batch.get(1));
            assertTrue(rotation.hasRotation());
            assertEquals(-90F, rotation.getXRot(0));
            var torchStart = assertInstanceOf(ServerboundPlayerActionPacket.class, batch.get(2));
            assertEquals(TORCH, torchStart.getPos());
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, torchStart.getAction());
            var baseStart = assertInstanceOf(ServerboundPlayerActionPacket.class, batch.get(3));
            assertEquals(PISTON, baseStart.getPos());
            assertEquals(ServerboundPlayerActionPacket.Action.START_DESTROY_BLOCK, baseStart.getAction());
            var baseStop = assertInstanceOf(ServerboundPlayerActionPacket.class, batch.get(4));
            assertEquals(PISTON, baseStop.getPos());
            assertEquals(ServerboundPlayerActionPacket.Action.STOP_DESTROY_BLOCK, baseStop.getAction());
            var use = assertInstanceOf(ServerboundUseItemOnPacket.class, batch.get(5));
            assertEquals(InteractionHand.OFF_HAND, use.getHand());
            assertEquals(PISTON, use.getHitResult().getBlockPos().relative(use.getHitResult().getDirection()));
            assertTrue(batch.stream().noneMatch(net.minecraft.network.protocol.game.ServerboundContainerClickPacket.class::isInstance));
            assertTrue(batch.stream().noneMatch(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.class::isInstance));
            assertEquals(batch, f.connection.packets, "No packets outside the submitted critical batch may split the sequence");
            assertEquals(1, get(f.executor, f.executor.getClass(), "reservedPistons"));
            assertTrue(f.traceLevel.getBlockState(PISTON).getValue(PistonBaseBlock.EXTENDED),
                    "Sending the bundle must not replace the client world snapshot");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"foreign_container", "cursor", "missing_piston"})
    void unsafeInventoryRejectsTheWholeCriticalBundleBeforeAnyBreak(String unsafe) throws Exception {
        try (var f = new Fixture()) {
            if (unsafe.equals("foreign_container")) {
                f.player.containerMenu = new QuickShulkerPacketContractTest.ServerMenu(
                        new net.minecraft.world.SimpleContainer(1), f.player.inventory);
            } else if (unsafe.equals("cursor")) {
                f.player.inventoryMenu.setCarried(new ItemStack(Items.STONE));
            } else {
                f.player.inventory.setItem(1, ItemStack.EMPTY);
                f.player.inventory.setItem(40, ItemStack.EMPTY);
            }
            f.assertRejectedBeforeBreak(Set.of(TORCH));
            if (unsafe.equals("missing_piston")) writeTrace(f, "production-critical-empty-piston.tsv", "bedrock");
        }
    }

    @ParameterizedTest @ValueSource(strings = {"null", "empty", "air", "foreign_block", "unlit"})
    void missingOwnedLiveTorchCannotStartBreakingThePistonBase(String invalidTorch) throws Exception {
        try (var f = new Fixture()) {
            Set<BlockPos> torches = Set.of(TORCH);
            if (invalidTorch.equals("null")) torches = null;
            else if (invalidTorch.equals("empty")) torches = Set.of();
            else if (invalidTorch.equals("air")) f.traceLevel.blocks.remove(TORCH);
            else if (invalidTorch.equals("unlit")) f.traceLevel.blocks.put(TORCH,
                    Blocks.REDSTONE_TORCH.defaultBlockState().setValue(net.minecraft.world.level.block.RedstoneTorchBlock.LIT, false));
            else f.traceLevel.blocks.put(TORCH, Blocks.STONE.defaultBlockState());
            f.assertRejectedBeforeBreak(torches);
        }
    }

    @Test void lastOffhandPistonCannotBeSpentAgainOnTheNextTick() throws Exception {
        try (var f = new Fixture()) {
            f.player.inventory.setItem(1, ItemStack.EMPTY);
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON));
            assertTrue(f.submit(Set.of(TORCH)));
            assertTrue(f.player.inventory.getItem(40).isEmpty(), "Raw use must predict its material consumption");
            var begin = f.executor.getClass().getDeclaredMethod("beginTick", long.class);
            begin.setAccessible(true);
            begin.invoke(f.executor, 1L);
            f.connection.packets.clear();
            f.assertRejectedBeforeBreak(Set.of(TORCH));
        }
    }

    @Test void anOlderInventorySnapshotCannotExceedTheRemainingRawUseBudget() throws Exception {
        try (var f = new Fixture()) {
            f.player.inventory.setItem(1, ItemStack.EMPTY);
            assertTrue(f.submit(Set.of(TORCH)));
            var begin = f.executor.getClass().getDeclaredMethod("beginTick", long.class);
            begin.setAccessible(true);
            begin.invoke(f.executor, 1L);
            // A full snapshot from before this placement can arrive one client tick
            // later. Its count alone cannot establish the result of the raw use.
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON, 2));
            f.connection.packets.clear();
            assertTrue(f.submit(Set.of(TORCH)), "The one genuinely unspent allowance may continue without an inventory round trip");
            begin.invoke(f.executor, 2L);
            f.player.inventory.setItem(40, new ItemStack(Items.PISTON, 2));
            f.connection.packets.clear();
            f.assertRejectedBeforeBreak(Set.of(TORCH));
        }
    }

    @Test void twoAvailablePistonsFundSuccessiveOrderedAttemptsWithoutPerUseSnapshots() throws Exception {
        try (var f = new Fixture()) {
            f.player.inventory.setItem(1, ItemStack.EMPTY);
            assertTrue(f.submit(Set.of(TORCH)));
            assertEquals(1, f.player.inventory.getItem(40).getCount());
            assertTrue(f.submit(Set.of(TORCH)));
            assertTrue(f.player.inventory.getItem(40).isEmpty());
            assertTrue(f.connection.packets.stream().noneMatch(net.minecraft.network.protocol.game.ServerboundClientCommandPacket.class::isInstance));
        }
    }

    @Test void aRejectedTransportDoesNotConsumeThePreparedPiston() throws Exception {
        try (var f = new Fixture()) {
            f.transport.reject = true;
            assertFalse(f.submit(Set.of(TORCH)));
            assertEquals(2, f.player.inventory.getItem(40).getCount());
            assertTrue(f.transport.batches.isEmpty());
        }
    }

    @Test void entityCollisionRejectsBeforeDestroyingAnyPartOfTheOldMachine() throws Exception {
        try (var f = new Fixture()) {
            f.traceLevel.obstructed = true;
            f.assertRejectedBeforeBreak(Set.of(TORCH));
        }
    }

    @Test void interactivePreferredAnchorFallsBackToAnOrdinaryReachableNeighbor() throws Exception {
        try (var f = new Fixture()) {
            f.traceLevel.blocks.put(BASE, Blocks.VAULT.defaultBlockState());
            f.traceLevel.blocks.put(PISTON.west(), Blocks.STONE.defaultBlockState());
            assertTrue(f.submit(Set.of(TORCH)));
            var use = f.transport.packets.stream().filter(ServerboundUseItemOnPacket.class::isInstance)
                    .map(ServerboundUseItemOnPacket.class::cast).findFirst().orElseThrow();
            assertEquals(PISTON.west(), use.getHitResult().getBlockPos());
        }
    }
}
//#endif
