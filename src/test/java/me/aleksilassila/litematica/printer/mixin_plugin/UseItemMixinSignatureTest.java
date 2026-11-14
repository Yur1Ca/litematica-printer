package me.aleksilassila.litematica.printer.mixin_plugin;

import me.aleksilassila.litematica.printer.mixin.printer.mc.MixinMultiPlayerGameMode;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UseItemMixinSignatureTest {
    @Test void theRequiredUseItemShadowMatchesTheActualVanillaDescriptor() throws Exception {
        var vanilla = MultiPlayerGameMode.class.getDeclaredMethod("useItem", Player.class, InteractionHand.class);
        var shadow = MixinMultiPlayerGameMode.class.getDeclaredMethod("useItem", Player.class, InteractionHand.class);
        assertEquals(vanilla.getReturnType(), shadow.getReturnType());
    }
}
