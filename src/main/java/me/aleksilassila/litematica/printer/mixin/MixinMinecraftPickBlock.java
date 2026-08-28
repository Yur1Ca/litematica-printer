package me.aleksilassila.litematica.printer.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.aleksilassila.litematica.printer.utils.mods.ChestTrackerBridge;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

//#if MC > 12103
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
//#else
//$$ import net.minecraft.world.entity.player.Inventory;
//$$ import net.minecraft.world.item.ItemStack;
//#endif

@Environment(EnvType.CLIENT)
@Mixin(Minecraft.class)
public abstract class MixinMinecraftPickBlock {

    //#if MC > 12103
        //#if MC > 260100
        @WrapOperation(method = "pickBlockOrEntity", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePickItemFromBlock(Lnet/minecraft/core/BlockPos;Z)V"))
        //#else
        //$$ @WrapOperation(method = "pickBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;handlePickItemFromBlock(Lnet/minecraft/core/BlockPos;Z)V"))
        //#endif
    private void litematica_printer$pickRealBlock(
            MultiPlayerGameMode gameMode,
            BlockPos pos,
            boolean includeData,
            Operation<Void> original
    ) {
        Minecraft client = Minecraft.getInstance();
        Item item = client.level == null ? Items.AIR : client.level.getBlockState(pos).getBlock().asItem();
        if (QuickShulkerBridge.handlePickBlock(client.player, item)) {
            return;
        }
        if (ChestTrackerBridge.handlePickBlock(client.player, item)) {
            return;
        }
        original.call(gameMode, pos, includeData);
    }
    //#else
    //$$ @WrapOperation(method = "pickBlock", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Inventory;findSlotMatchingItem(Lnet/minecraft/world/item/ItemStack;)I"))
    //$$ private int litematica_printer$pickRealBlock(Inventory inventory, ItemStack stack, Operation<Integer> original) {
    //$$     int slot = original.call(inventory, stack);
    //$$     if (slot == -1 && QuickShulkerBridge.handlePickBlock(Minecraft.getInstance().player, stack)) {
    //$$         return slot;
    //$$     }
    //$$     return slot;
    //$$ }
    //#endif

}
