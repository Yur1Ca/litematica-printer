package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

// Priority rationale: cancellable HEAD callbacks sharing the first instruction run in ascending mixin
// priority. Enter the coordinator before Take It Out's default (1000) missing-item cancellation.
@Mixin(value = WorldUtils.class, priority = 900)
public class MixinInventoryUtils {
    @Inject(at = @At("HEAD"), method = "doEasyPlaceAction", cancellable = true, remap = false)
    private static void easyPlace(
            Minecraft mc,
            CallbackInfoReturnable<InteractionResult> cir
    ) {
        ItemStack stack = requiredStack(mc);
        if (!stack.isEmpty() && QuickShulkerBridge.handleEasyPlacePickBlock(mc.player, stack)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Inject(at = @At("HEAD"), method = "doSchematicWorldPickBlock", cancellable = true, remap = false)
    private static void schematicWorldPickBlock(
            boolean closest,
            Minecraft mc,
            CallbackInfoReturnable<Boolean> cir
    ) {
        if (mc.player == null || mc.player.getAbilities().instabuild || mc.player.isSpectator()) {
            return;
        }
        ItemStack stack = requiredStack(mc);
        if (!stack.isEmpty() && QuickShulkerBridge.handleEasyPlacePickBlock(mc.player, stack)) {
            cir.setReturnValue(true);
        }
    }

    private static ItemStack requiredStack(Minecraft mc) {
        if (mc == null || mc.player == null || mc.player.getAbilities().instabuild || mc.player.isSpectator()) {
            return ItemStack.EMPTY;
        }
        BlockHitResult hitResult = RayTraceUtils.traceToSchematicWorld(mc.player, 6.0D, true, true);
        if (hitResult == null || hitResult.getType() != HitResult.Type.BLOCK) {
            return ItemStack.EMPTY;
        }
        BlockPos pos = hitResult.getBlockPos();
        WorldSchematic schematicWorld = SchematicWorldHandler.getSchematicWorld();
        if (schematicWorld == null) {
            return ItemStack.EMPTY;
        }
        return MaterialCache.getInstance().getRequiredBuildItemForState(
                schematicWorld.getBlockState(pos),
                schematicWorld,
                pos
        );
    }
}
