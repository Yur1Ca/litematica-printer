//#if MC == 260100
package me.aleksilassila.litematica.printer.handler.handlers.bedrock;

import me.aleksilassila.litematica.printer.integration.quickshulker.InventoryRecoveryFence;
import me.aleksilassila.litematica.printer.runtime.PrinterRuntime;
import me.aleksilassila.litematica.printer.utils.InventorySwitchGuard;
import me.aleksilassila.litematica.printer.utils.InventoryUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.BlockHitResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static me.aleksilassila.litematica.printer.handler.handlers.bedrock.BedrockPlacementRegressionTest.set;
import static org.junit.jupiter.api.Assertions.*;

class BedrockDedicatedHandPlacementTest {
    @BeforeAll static void bootstrap() { BedrockPlacementRegressionTest.bootstrap(); }

    @Test void ordinaryMaterialUsesMainHandAndExplicitlyReselectsEvenWhenAlreadySelected() throws Exception {
        try (var f = new BedrockPlacementRegressionTest.Fixture()) {
            set(f.runtime, PrinterRuntime.class, "inventorySwitchGuard", new InventorySwitchGuard(f.client, f.runtime::currentTick));
            set(f.client.player, Player.class, "abilities", new Abilities());
            f.client.player.getInventory().setItem(2, new ItemStack(Items.STICK, 16));
            InventoryUtils.setSelectedSlot(f.client.player.getInventory(), 2);
            var mode = f.allocate(CaptureMode.class);
            f.client.gameMode = mode;
            var constructor = Arrays.stream(BedrockPlacer.class.getDeclaredConstructors())
                    .filter(c -> Arrays.equals(c.getParameterTypes(),
                            new Class<?>[]{Minecraft.class, BedrockNetworkSync.class, boolean.class})).findFirst().orElse(null);
            assertNotNull(constructor, "The runtime must opt into an inventory session with a dedicated piston hand");
            constructor.setAccessible(true);
            var placer = (BedrockPlacer) constructor.newInstance(f.client, null, true);

            assertFalse(placer.placeSimple(new BlockPos(0, 64, 0), Direction.UP, Items.STICK),
                    "Validate the inventory before the first mutation of a session");
            InventoryRecoveryFence.received(f.transport);
            f.transport.packets.clear();
            assertTrue(placer.placeSimple(new BlockPos(0, 64, 0), Direction.UP, Items.STICK));

            assertEquals(InteractionHand.MAIN_HAND, mode.hand);
            assertTrue(mode.prediction);
            assertTrue(f.client.player.getOffhandItem().is(Items.PISTON));
            assertEquals(16, f.client.player.getOffhandItem().getCount());
            assertEquals(1, f.transport.packets.stream().filter(ServerboundSetCarriedItemPacket.class::isInstance).count(),
                    "Raw critical packets can leave vanilla's carriedIndex cache stale; the next main-hand USE must select explicitly");
        }
    }

    private static class CaptureMode extends BedrockPlacementRegressionTest.TestGameMode {
        InteractionHand hand;
        boolean prediction;
        @Override public InteractionResult litematica_printer$useItemOn(boolean prediction, InteractionHand hand, BlockHitResult hit) {
            this.hand = hand;
            this.prediction = prediction;
            return InteractionResult.PASS;
        }
    }
}
//#endif
