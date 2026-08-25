package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import com.mojang.blaze3d.vertex.PoseStack;
import fi.dy.masa.litematica.render.schematic.BufferAllocatorCache;
import fi.dy.masa.litematica.render.schematic.ChunkCacheSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRenderDataSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import me.aleksilassila.litematica.printer.config.Configs;
import me.aleksilassila.litematica.printer.runtime.RuntimeAccess;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Set;

/**
 * Ported from sakura-ryoko/litematica-printer2's {@code ChunkRendererSchematicVboMixin}
 * (RENDER_ONLY_HOLDING_ITEMS feature). When {@link Configs.Core#RENDER_ONLY_HOLDING_ITEMS}
 * is enabled, schematic blocks whose item is not currently reachable in the player's
 * inventory (including shulker boxes) are skipped entirely before any mesh building work
 * happens for that block, reducing visual clutter and render cost for unplaceable blocks.
 */
@Mixin(value = ChunkRendererSchematicVbo.class, priority = 1200, remap = false)
public class MixinChunkRendererSchematicVbo {
    @Shadow
    protected ChunkCacheSchematic schematicWorldView;

    @Inject(method = "renderBlocksAndOverlay", at = @At("HEAD"), cancellable = true)
    private void litematicaPrinter$onRenderBlocksAndOverlay(BlockPos pos, ChunkRenderDataSchematic data,
            BufferAllocatorCache allocators, Set<BlockEntity> tileEntities, Set<RenderType> usedLayers,
            PoseStack matrixStack, CallbackInfo ci) {
        if (!Configs.Core.RENDER_ONLY_HOLDING_ITEMS.getBooleanValue()) {
            return;
        }

        BlockState stateSchematic = this.schematicWorldView.getBlockState(pos);
        Item item = stateSchematic.getBlock().asItem();

        if (!RuntimeAccess.get().inventoryAvailability().isAvailable(item)) {
            ci.cancel();
        }
    }
}
