package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import net.minecraft.core.BlockPos;
//#if MC >= 260200
//$$ import net.minecraft.client.Minecraft;
//$$ import net.minecraft.world.entity.player.Player;
//$$ import net.minecraft.world.item.ItemStack;
//$$ import net.minecraft.world.level.block.state.BlockState;
//$$ import net.minecraft.world.phys.HitResult;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

//#if MC > 12100
@Mixin(value = fi.dy.masa.litematica.util.EasyPlaceUtils.class, remap = false)
//#else
//$$ @Mixin(value = fi.dy.masa.litematica.util.WorldUtils.class, remap = false)
//#endif
public interface EasyPlaceUtilsAccessor {
    @Invoker("setEasyPlaceLastPickBlockTime")
    static void callSetEasyPlaceLastPickBlockTime() {
        throw new UnsupportedOperationException();
    }

    @Invoker("easyPlaceIsPositionCached")
    static boolean callEasyPlaceIsPositionCached(BlockPos pos) {
        throw new UnsupportedOperationException();
    }

    @Invoker("easyPlaceIsTooFast")
    static boolean callEasyPlaceIsTooFast() {
        throw new UnsupportedOperationException();
    }

    //#if MC >= 260200
    //$$ @Invoker("easyPlaceBlockChecksCancel")
    //$$ static boolean callEasyPlaceBlockChecksCancel(BlockState expected, BlockState actual, Player player,
    //$$                                                HitResult hit, ItemStack stack) {
    //$$     throw new UnsupportedOperationException();
    //$$ }
    //$$
    //$$ @Invoker("placementRestrictionInEffect")
    //$$ static boolean callPlacementRestrictionInEffect(Minecraft mc) {
    //$$     throw new UnsupportedOperationException();
    //$$ }
    //#endif
}

