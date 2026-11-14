package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.action.PlacementPolicy;
import me.aleksilassila.litematica.printer.utils.minecraft.NetworkUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.WaterSurfacePlacement;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.protocol.game.ServerboundUseItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

class LilyPadQueueRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void theRealGuideUsesSourceWaterAndBuildsTheSpecialItemUseAction() throws Exception {
        try (var f = new SurfaceFixture()) {
            var schematic = f.allocate(SurfaceSchematic.class);
            var context = new me.aleksilassila.litematica.printer.printer.SchematicBlockContext(f.client, f.surface, schematic, TARGET);
            var action = new me.aleksilassila.litematica.printer.guide.Guides().buildAction(context).orElseThrow();
            assertInstanceOf(me.aleksilassila.litematica.printer.printer.action.LilyPadAction.class, action);
            assertEquals(90f, action.getPlayerLook().pitch, .01f);
            f.surface.blocks.put(TARGET.below(), Blocks.WATER.defaultBlockState()
                    .setValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.LEVEL, 1));
            assertTrue(new me.aleksilassila.litematica.printer.guide.Guides().buildAction(context).isEmpty());
        }
    }

    @Test void theQueuedLilyPadUsesItemUseAndRecognizesARealPacketEvenWhenPredictionFails() throws Exception {
        try (var f = new SurfaceFixture()) {
            f.queue();
            assertEquals(ActionManager.SendResult.SENT, f.manager.sendQueue(f.client.player));
            assertEquals(1, f.transport.packets.stream().filter(ServerboundUseItemPacket.class::isInstance).count());
            assertTrue(f.mode.hits.isEmpty(), "An ordinary block USE_ON cannot plant a lily pad");
        }
    }

    @Test void sidewaysLilyPadDispatchKeepsThePreciseAimInsteadOfThePlayersCurrentPitch() throws Exception {
        try (var f = new SurfaceFixture()) {
            set(f.client.player, net.minecraft.world.entity.Entity.class, "position", new Vec3(3.5, 64, .5));
            f.client.player.setXRot(0f);
            var schematic = f.allocate(SurfaceSchematic.class);
            var context = new me.aleksilassila.litematica.printer.printer.SchematicBlockContext(f.client, f.surface, schematic, TARGET);
            var action = new me.aleksilassila.litematica.printer.guide.Guides().buildAction(context).orElseThrow();
            assertTrue(action.getPlayerLook().pitch > 0 && action.getPlayerLook().pitch < 45);
            assertEquals(me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementResult.TaskEvent.SUCCESS,
                    f.executor().execute(context, action, null).taskEvent());
            assertEquals(action.getPlayerLook(), f.itemMode.lastLook);
            assertEquals(0f, f.client.player.getXRot());
        }
    }

    @Test void aChangedHeldItemCannotBypassTheNormalQueueGuard() throws Exception {
        try (var f = new SurfaceFixture()) {
            f.queue();
            f.client.player.getInventory().setItem(0, new ItemStack(Items.STONE, 16));
            assertEquals(ActionManager.SendResult.HELD_ITEM_CHANGED, f.manager.sendQueue(f.client.player));
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundUseItemPacket.class::isInstance));
        }
    }

    @Test void aNewObstructionOrMissingSourceWaterCancelsTheDeferredUse() throws Exception {
        try (var f = new SurfaceFixture()) {
            f.queue();
            f.surface.blocked = true;
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, f.manager.sendQueue(f.client.player));
            assertTrue(f.transport.packets.stream().noneMatch(ServerboundUseItemPacket.class::isInstance));
            f.surface.blocked = false;
            f.queue();
            f.surface.blocks.put(TARGET.below(), Blocks.AIR.defaultBlockState());
            assertEquals(ActionManager.SendResult.INTERACTION_REJECTED, f.manager.sendQueue(f.client.player));
        }
    }

    static class SurfaceLevel extends TestLevel {
        boolean blocked;
        SurfaceLevel() { super(); }
        @Override public FluidState getFluidState(BlockPos pos) { return getBlockState(pos).getFluidState(); }
        @Override public BlockHitResult clip(ClipContext context) {
            return blocked ? new BlockHitResult(new Vec3(.5, 65, .5), Direction.UP, TARGET.above(), false)
                    : new BlockHitResult(new Vec3(.5, 64, .5), Direction.UP, TARGET.below(), false);
        }
    }

    static class SurfaceSchematic extends fi.dy.masa.litematica.world.WorldSchematic {
        SurfaceSchematic() { super(null, null, null, null); }
        @Override public net.minecraft.world.level.block.state.BlockState getBlockState(BlockPos pos) {
            return pos.equals(TARGET) ? Blocks.LILY_PAD.defaultBlockState() : Blocks.AIR.defaultBlockState();
        }
    }

    static class SurfaceMode extends TestGameMode {
        SurfaceFixture fixture;
        PlayerLook lastLook;
        SurfaceMode() { super(); }
        @Override public InteractionResult litematica_printer$useItem(InteractionHand hand, PlayerLook look) {
            this.lastLook = look;
            var packet = new ServerboundUseItemPacket(hand, 31, look.yaw, look.pitch);
            fixture.connection.send(packet);
            // Headless fixtures observe the same transport boundary as Connection's real mixin.
            NetworkUtils.observeUseItemOn(fixture.transport, packet);
            return InteractionResult.FAIL;
        }
    }

    static class SurfaceFixture extends Fixture {
        final boolean oldPrecision = me.aleksilassila.litematica.printer.config.Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        final ActionManager manager = new ActionManager(source -> new PlacementPolicy(() -> 0, false, false));
        final SurfaceLevel surface;
        final SurfaceMode itemMode;
        final net.minecraft.core.Holder<net.minecraft.world.level.material.Fluid> water = BuiltInRegistries.FLUID.wrapAsHolder(Fluids.WATER);
        final List<?> oldWaterTags = water.tags().toList();
        SurfaceFixture() throws Exception {
            me.aleksilassila.litematica.printer.config.Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            this.installCachedClient(Reference.class.getDeclaredField("MINECRAFT"));
            surface = allocate(SurfaceLevel.class);
            surface.blocks = new HashMap<>();
            surface.blocks.put(TARGET, Blocks.AIR.defaultBlockState());
            surface.blocks.put(TARGET.below(), Blocks.WATER.defaultBlockState());
            set(this, Fixture.class, "level", surface);
            client.level = surface;
            set(client.player, net.minecraft.world.entity.Entity.class, "level", surface);
            set(client.player, LivingEntity.class, "attributes", new AttributeMap(Player.createAttributes().build()));
            client.player.getInventory().setItem(0, new ItemStack(Items.LILY_PAD, 16));
            itemMode = allocate(SurfaceMode.class);
            itemMode.fixture = this;
            client.gameMode = itemMode;
            bindWater(List.of(net.minecraft.tags.FluidTags.WATER, net.minecraft.tags.FluidTags.SUPPORTS_LILY_PAD));
        }
        void queue() {
            assertTrue(manager.queueClick(TARGET, Direction.DOWN, Vec3.ZERO, false, 1, new Item[]{Items.LILY_PAD}, ActionManager.ActionSource.PRINT));
            assertTrue(manager.setWaterSurfacePlacement());
            manager.setLook(WaterSurfacePlacement.aim(client.player, surface, TARGET));
        }
        void bindWater(Collection<?> tags) throws Exception {
            var bind = net.minecraft.core.Holder.Reference.class.getDeclaredMethod("bindTags", Collection.class);
            bind.setAccessible(true);
            bind.invoke(water, tags);
        }
        @SuppressWarnings("unchecked")
        me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor executor() {
            var portType = me.aleksilassila.litematica.printer.printer.action.ActionPort.class;
            var port = (me.aleksilassila.litematica.printer.printer.action.ActionPort) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{portType}, (proxy, method, args) -> switch (method.getName()) {
                        case "queueClick" -> manager.queueClick((BlockPos)args[0], (Direction)args[1], (Vec3)args[2],
                                (boolean)args[3], (int)args[4], (Item[])args[5], ActionManager.ActionSource.PRINT);
                        case "setWaterSurfacePlacement" -> manager.setWaterSurfacePlacement();
                        case "setExpectedStackPredicate" -> manager.setExpectedStackPredicate((java.util.function.Predicate<ItemStack>)args[0]);
                        case "setQueueCompletionListener" -> manager.setQueueCompletionListener(result ->
                                ((java.util.function.Consumer<me.aleksilassila.litematica.printer.printer.action.ActionPort.SendResult>)args[0])
                                        .accept(me.aleksilassila.litematica.printer.printer.action.ActionPort.SendResult.valueOf(result.name())));
                        case "setLook" -> { manager.setLook((PlayerLook)args[0]); yield null; }
                        case "setWaitForHorizontalLook" -> { manager.setWaitForHorizontalLook((boolean)args[0]); yield null; }
                        case "setNeedWaitModifyLookFromAction" -> { manager.setNeedWaitModifyLookFromAction((boolean)args[0]); yield null; }
                        case "sendQueue" -> me.aleksilassila.litematica.printer.printer.action.ActionPort.SendResult.valueOf(
                                manager.sendQueue((net.minecraft.client.player.LocalPlayer)args[0]).name());
                        case "cancelQueue" -> { manager.cancelQueue(); yield null; }
                        case "getLook" -> manager.getLook();
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            return new me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor(port,
                    new me.aleksilassila.litematica.printer.utils.CooldownUtils(),
                    new me.aleksilassila.litematica.printer.utils.InventorySwitchGuard(client, () -> 100L),
                    new me.aleksilassila.litematica.printer.handler.HudStatsManager(client, () -> 100L),
                    new me.aleksilassila.litematica.printer.printer.MissingMaterialTracker(),
                    new me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter(),
                    new me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker(),
                    new me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator(List.of(
                            new me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider() {
                                @Override public String id() { return "unavailable-fixture"; }
                                @Override public me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation request(
                                        me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest request) {
                                    return new me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation(request.token(),
                                            me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation.State.UNAVAILABLE);
                                }
                            })));
        }
        @Override public void close() throws Exception {
            me.aleksilassila.litematica.printer.config.Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(oldPrecision);
            bindWater(oldWaterTags);
            super.close();
        }
    }
}
