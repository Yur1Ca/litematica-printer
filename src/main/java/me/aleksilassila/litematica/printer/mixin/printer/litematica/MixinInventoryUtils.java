package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.materials.MaterialCache;
import fi.dy.masa.litematica.util.RayTraceUtils;
import fi.dy.masa.litematica.util.WorldUtils;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import fi.dy.masa.litematica.world.WorldSchematic;
import me.aleksilassila.litematica.printer.integration.inventory.PickBlockRequestScope;
import me.aleksilassila.litematica.printer.utils.mods.QuickShulkerBridge;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
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
        if (mc == null || mc.level == null || mc.player == null
                || mc.player.getAbilities().instabuild || mc.player.isSpectator()) {
            return;
        }
        // Match native Easy Place before requesting material. A schematic-only ray can hit an
        // already built block or a projection behind the real block the player is interacting with.
        double range = WorldUtils.getValidBlockRange(mc);
        boolean first = fi.dy.masa.litematica.config.Configs.Generic.EASY_PLACE_FIRST.getBooleanValue();
        var trace = first
                ? RayTraceUtils.getGenericTrace(mc.level, mc.player, range, true,
                        fi.dy.masa.litematica.config.Configs.InfoOverlays.INFO_OVERLAYS_TARGET_FLUIDS.getBooleanValue(), false)
                : RayTraceUtils.getFurthestSchematicWorldTraceBeforeVanilla(mc.level, mc.player, range);
        if (trace == null) {
            boolean restricted = false;
            restricted = !first && litematicaPrinter$placementRestricted(mc);
            cir.setReturnValue(restricted ? InteractionResult.FAIL : InteractionResult.PASS);
            return;
        }
        if (trace.getHitType() != RayTraceUtils.RayTraceWrapper.HitType.SCHEMATIC_BLOCK) {
            boolean restricted = trace.getHitType() == RayTraceUtils.RayTraceWrapper.HitType.VANILLA_BLOCK
                    && litematicaPrinter$placementRestricted(mc);
            cir.setReturnValue(restricted ? InteractionResult.FAIL : InteractionResult.PASS);
            return;
        }
        WorldSchematic schematic = SchematicWorldHandler.getSchematicWorld();
        if (schematic == null) return;
        BlockPos pos = trace.getBlockHitResult().getBlockPos();
        BlockState expected = schematic.getBlockState(pos);
        ItemStack stack = MaterialCache.getInstance().getRequiredBuildItemForState(expected, schematic, pos);
        if (EasyPlaceUtilsAccessor.callEasyPlaceIsPositionCached(pos) || EasyPlaceUtilsAccessor.callEasyPlaceIsTooFast()) {
            cir.setReturnValue(InteractionResult.FAIL);
            return;
        }
        if (stack.isEmpty()) {
            cir.setReturnValue(InteractionResult.SUCCESS);
            return;
        }
        BlockState actual = mc.level.getBlockState(pos);
        HitResult vanillaHit = RayTraceUtils.getRayTraceFromEntity(mc.level, mc.player, false, range);
        //#if MC >= 260200
        //$$ boolean blocked = expected == actual || EasyPlaceUtilsAccessor.callEasyPlaceBlockChecksCancel(expected, actual, mc.player, vanillaHit, stack);
        //#else
        boolean blocked = expected == actual || easyPlaceBlockChecksCancel(expected, actual, mc.player, vanillaHit, stack);
        //#endif
        if (blocked) {
            // Finish with the native result: falling through would let Take It Out restart this
            // rejected right-click through doSchematicWorldPickBlock and bypass these checks.
            cir.setReturnValue(InteractionResult.FAIL);
        } else if (QuickShulkerBridge.handleEasyPlacePickBlock(mc.player, stack)) {
            cir.setReturnValue(InteractionResult.FAIL);
        }
    }

    @Unique
    private static boolean litematicaPrinter$placementRestricted(Minecraft mc) {
        //#if MC >= 260200
        //$$ return EasyPlaceUtilsAccessor.callPlacementRestrictionInEffect(mc);
        //#else
        return placementRestrictionInEffect(mc);
        //#endif
    }

    //#if MC < 260200
    @Shadow(remap = false)
    private static boolean placementRestrictionInEffect(Minecraft mc) {
        throw new UnsupportedOperationException();
    }

    @Shadow(remap = false)
    private static boolean easyPlaceBlockChecksCancel(BlockState expected, BlockState actual, Player player,
                                                      HitResult hit, ItemStack stack) {
        throw new UnsupportedOperationException();
    }
    //#endif

    @Inject(at = @At("HEAD"), method = "doSchematicWorldPickBlock", cancellable = true, remap = false)
    private static void schematicWorldPickBlock(boolean closest, Minecraft mc,
                                                CallbackInfoReturnable<Boolean> cir) {
        if (mc.player == null || mc.player.getAbilities().instabuild || mc.player.isSpectator()) {
            return;
        }
        boolean userPick = PickBlockRequestScope.isUserPick();
        // Foreign picks keep inventory ownership, but may never use another material source.
        if (!userPick && QuickShulkerBridge.inventoryOperationPending()) {
            cir.setReturnValue(true);
            return;
        }
        if (!userPick && mc.level == null) return;
        ItemStack stack = requiredStack(mc);
        if (!stack.isEmpty() && (userPick
                ? QuickShulkerBridge.handleEasyPlacePickBlock(mc.player, stack)
                : QuickShulkerBridge.handleTakeItOutPickBlock(mc.player, stack))) {
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
