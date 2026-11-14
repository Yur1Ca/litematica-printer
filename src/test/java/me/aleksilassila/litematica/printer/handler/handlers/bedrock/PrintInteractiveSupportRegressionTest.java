//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Predicate;
import me.aleksilassila.litematica.printer.Reference;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.enums.RadiusShapeType;
import me.aleksilassila.litematica.printer.guide.Guides;
import me.aleksilassila.litematica.printer.handler.handlers.print.FallingPlacementTracker;
import me.aleksilassila.litematica.printer.handler.handlers.print.PrintPlacementExecutor;
import me.aleksilassila.litematica.printer.integration.inventory.InventoryProvider;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequest;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialRequestCoordinator;
import me.aleksilassila.litematica.printer.integration.inventory.MaterialReservation;
import me.aleksilassila.litematica.printer.integration.litematica.LitematicaAdapter;
import me.aleksilassila.litematica.printer.printer.ActionManager;
import me.aleksilassila.litematica.printer.printer.MissingMaterialTracker;
import me.aleksilassila.litematica.printer.printer.PlayerLook;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.ActionPort;
import me.aleksilassila.litematica.printer.utils.CooldownUtils;
import me.aleksilassila.litematica.printer.utils.ConfigUtils;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import me.aleksilassila.litematica.printer.utils.minecraft.PlayerUtils;
import net.minecraft.client.player.ClientInput;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeMap;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real Guide -> Executor -> ActionManager -> outbound click, with an interactive support. */
class PrintInteractiveSupportRegressionTest {
    private static final BlockPos TARGET = new BlockPos(0, 64, 0);
    private static final BlockPos BUTTON = TARGET.south();

    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void movingFartherAwayCannotReplaceSafeGroundWithTheButtonBehindTheGhost() throws Exception {
        try (var f = new ClickFixture(Blocks.STONE_BUTTON.defaultBlockState())) {
            f.level.blocks.put(TARGET.below(), Blocks.STONE.defaultBlockState());
            f.move(0.5D);
            f.execute();
            assertEquals(TARGET.below(), f.dispatch.hits.get(0).getBlockPos());
            assertEquals(Direction.UP, f.dispatch.hits.get(0).getDirection());
            assertFalse(f.dispatch.sneakingAtUse.get(0));

            f.resetCapture();
            f.move(-20.0D);
            f.execute();
            assertEquals(TARGET.below(), f.dispatch.hits.get(0).getBlockPos(),
                    "A view-preferred button must not outrank an available noninteractive support");
            assertEquals(Direction.UP, f.dispatch.hits.get(0).getDirection());
            assertFalse(f.dispatch.sneakingAtUse.get(0));
        }
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void anUnavoidableButtonSupportIsSneakedBeforeTheUseAndRestoredAfterwards(boolean wooden) throws Exception {
        try (var f = new ClickFixture((wooden ? Blocks.OAK_BUTTON : Blocks.STONE_BUTTON).defaultBlockState())) {
            f.move(-20.0D);
            f.execute();
            assertEquals(BUTTON, f.dispatch.hits.get(0).getBlockPos());
            assertEquals(Direction.NORTH, f.dispatch.hits.get(0).getDirection());
            assertEquals(TARGET, f.serverPlacementPosition(f.dispatch.hits.get(0)),
                    "The selected support face still resolves to the requested ghost coordinate");
            assertTrue(f.dispatch.sneakingAtUse.get(0), "The click must bypass the button interaction before placing the item");
            assertEquals(3, f.transport.packets.size());
            assertTrue(assertInstanceOf(ServerboundPlayerInputPacket.class, f.transport.packets.get(0)).input().shift());
            assertInstanceOf(ServerboundUseItemOnPacket.class, f.transport.packets.get(1));
            assertFalse(assertInstanceOf(ServerboundPlayerInputPacket.class, f.transport.packets.get(2)).input().shift());
            assertFalse(f.client.player.isShiftKeyDown(), "Packet sneaking restores the original player state");
        }
    }

    private static class SneakPlayer extends TestPlayer {
        boolean shift;
        @Override public boolean isShiftKeyDown() { return this.shift; }
        @Override public void setShiftKeyDown(boolean shift) { this.shift = shift; }
    }

    private static class ClickGameMode extends TestGameMode {
        ClickFixture fixture;
        List<Boolean> sneakingAtUse;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            assertTrue(prediction);
            assertEquals(InteractionHand.MAIN_HAND, hand);
            this.hits.add(hit);
            this.sneakingAtUse.add(this.fixture.client.player.isShiftKeyDown());
            this.fixture.connection.send(new ServerboundUseItemOnPacket(hand, hit, this.hits.size()));
            return InteractionResult.PASS;
        }
    }

    private static class ClickFixture extends Fixture {
        final boolean oldAir = Configs.Print.PLACE_IN_AIR.getBooleanValue();
        final boolean oldSort = Configs.Print.PRINT_SORT_SIDES.getBooleanValue();
        final boolean oldSneak = Configs.Print.PRINT_FORCED_SNEAK.getBooleanValue();
        final boolean oldReserve = Configs.Print.PRINT_RESERVE_ITEMS.getBooleanValue();
        final boolean oldProtocol = Configs.Print.EASY_PLACE_PROTOCOL.getBooleanValue();
        final boolean oldQuickShulker = Configs.Placement.QUICK_SHULKER.getBooleanValue();
        final int oldWorkRange = Configs.Core.WORK_RANGE.getIntegerValue();
        final Object oldShape = Configs.Core.ITERATOR_SHAPE.getOptionListValue();
        final ActionManager manager = new ActionManager();
        final ClickGameMode dispatch;
        final WaterSupportRegressionTest.Schematic schematic;
        final PrintPlacementExecutor executor;

        @SuppressWarnings("unchecked")
        ClickFixture(BlockState button) throws Exception {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(false);
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(true);
            Configs.Print.PRINT_FORCED_SNEAK.setBooleanValue(false);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(false);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(false);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(false);
            Configs.Core.WORK_RANGE.setIntegerValue(64);
            Configs.Core.CHECK_PLAYER_INTERACTION_RANGE.setBooleanValue(true);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue(RadiusShapeType.SPHERE);
            this.installCachedClient(Reference.class.getDeclaredField("MINECRAFT"));
            var player = allocate(SneakPlayer.class);
            player.inventory = client.player.getInventory();
            set(player, Player.class, "inventory", player.inventory);
            set(player, Player.class, "inventoryMenu", client.player.inventoryMenu);
            set(player, Player.class, "abilities", client.player.getAbilities());
            player.containerMenu = player.inventoryMenu;
            set(player, Entity.class, "level", level);
            set(player, Entity.class, "eyeHeight", 1.5F);
            set(player, LivingEntity.class, "attributes", new AttributeMap(Player.createAttributes().build()));
            player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE).setBaseValue(64.0D);
            set(player, LocalPlayer.class, "connection", connection);
            player.input = new ClientInput();
            client.player = player;
            player.inventory.setItem(0, new ItemStack(Items.STONE, 16));
            InventoryUtils.setSelectedSlot(player.inventory, 0);
            level.blocks.clear();
            level.blocks.put(BUTTON, button);
            level.blocks.put(BUTTON.south(), Blocks.STONE.defaultBlockState());
            schematic = allocate(WaterSupportRegressionTest.Schematic.class);
            schematic.states = new HashMap<>();
            schematic.states.put(TARGET, Blocks.STONE.defaultBlockState());
            dispatch = allocate(ClickGameMode.class);
            dispatch.fixture = this;
            dispatch.hits = new ArrayList<>();
            dispatch.sneakingAtUse = new ArrayList<>();
            client.gameMode = dispatch;
            ActionPort port = (ActionPort) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{ActionPort.class},
                    (proxy, method, args) -> switch (method.getName()) {
                        case "queueClick" -> manager.queueClick((BlockPos) args[0], (Direction) args[1], (Vec3) args[2],
                                (boolean) args[3], (int) args[4], (Item[]) args[5], ActionManager.ActionSource.PRINT);
                        case "setExpectedStackPredicate" -> manager.setExpectedStackPredicate((Predicate<ItemStack>) args[0]);
                        case "setLook" -> { manager.setLook((PlayerLook) args[0]); yield null; }
                        case "setNeedWaitModifyLookFromAction" -> { manager.setNeedWaitModifyLookFromAction((boolean) args[0]); yield null; }
                        case "sendQueue" -> ActionPort.SendResult.valueOf(manager.sendQueue((LocalPlayer) args[0]).name());
                        case "setQueueCompletionListener" -> true;
                        default -> method.getReturnType() == boolean.class ? false : null;
                    });
            var requests = new MaterialRequestCoordinator(List.of(new InventoryProvider() {
                @Override public String id() { return "interactive-support-boundary"; }
                @Override public MaterialReservation request(MaterialRequest request) {
                    return new MaterialReservation(request.token(), MaterialReservation.State.UNAVAILABLE);
                }
            }));
            executor = new PrintPlacementExecutor(port, new CooldownUtils(), runtime.inventorySwitchGuard(),
                    runtime.hudStats(), new MissingMaterialTracker(), new LitematicaAdapter(), new FallingPlacementTracker(), requests);
        }

        void move(double z) throws Exception { set(client.player, Entity.class, "position", new Vec3(0.5, 64, z)); }
        void execute() {
            assertTrue(ConfigUtils.canInteracted(TARGET), "The ghost is legal with the player's modified reach and reach checking enabled");
            assertTrue(PlayerUtils.isWithinBlockInteractionRange(client.player, BUTTON, 1.0D),
                    "The far button is also in modified reach; this case cannot fail due to vanilla's short range");
            var context = new SchematicBlockContext(client, level, schematic, TARGET);
            var action = new Guides().buildAction(context).orElseThrow();
            executor.execute(context, action, null);
            assertEquals(1, dispatch.hits.size(), "The stocked ordinary print must reach the actual click boundary");
        }
        void resetCapture() {
            dispatch.hits.clear();
            dispatch.sneakingAtUse.clear();
            transport.packets.clear();
        }
        @Override public void close() throws Exception {
            Configs.Print.PLACE_IN_AIR.setBooleanValue(oldAir);
            Configs.Print.PRINT_SORT_SIDES.setBooleanValue(oldSort);
            Configs.Print.PRINT_FORCED_SNEAK.setBooleanValue(oldSneak);
            Configs.Print.PRINT_RESERVE_ITEMS.setBooleanValue(oldReserve);
            Configs.Print.EASY_PLACE_PROTOCOL.setBooleanValue(oldProtocol);
            Configs.Placement.QUICK_SHULKER.setBooleanValue(oldQuickShulker);
            Configs.Core.WORK_RANGE.setIntegerValue(oldWorkRange);
            Configs.Core.ITERATOR_SHAPE.setOptionListValue((RadiusShapeType) oldShape);
            super.close();
        }
    }
}
//#endif
