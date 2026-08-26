package me.aleksilassila.litematica.printer.mixin.printer.litematica;

import fi.dy.masa.litematica.render.schematic.ChunkRenderDispatcherSchematic;
import fi.dy.masa.litematica.render.schematic.ChunkRendererSchematicVbo;
import me.aleksilassila.litematica.printer.mixin_extension.InventoryAvailabilityRenderExtension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = ChunkRenderDispatcherSchematic.class, remap = false)
public class MixinChunkRenderDispatcherSchematic {
    @Inject(method = "getChunkRenderer", at = @At("RETURN"))
    private void litematicaPrinter$refreshRenderer(CallbackInfoReturnable<ChunkRendererSchematicVbo> cir) {
        ChunkRendererSchematicVbo renderer = cir.getReturnValue();
        if (renderer instanceof InventoryAvailabilityRenderExtension extension) {
            extension.litematica_printer$refreshInventoryAvailability();
        }
    }
}
