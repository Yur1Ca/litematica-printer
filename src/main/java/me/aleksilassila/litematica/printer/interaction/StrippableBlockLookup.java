package me.aleksilassila.litematica.printer.interaction;

//#if MC >= 260300
//$$ import net.minecraft.core.registries.BuiltInRegistries;
//$$ import net.minecraft.resources.Identifier;
//#else
import net.fabricmc.fabric.mixin.content.registry.AxeItemAccessor;
//#endif
import net.minecraft.world.level.block.Block;

import java.util.LinkedHashMap;
import java.util.Map;

public final class StrippableBlockLookup {
    private StrippableBlockLookup() {
    }

    public static Map<Block, Block> strippables() {
        //#if MC >= 260300
        // Vanilla 26.3 stores axe transforms in the block_transformer registry.
        // Its stripped log and bamboo targets all use the "stripped_" registry prefix.
        //$$ Map<Block, Block> result = new LinkedHashMap<>();
        //$$ for (Block stripped : BuiltInRegistries.BLOCK) {
        //$$     Identifier id = BuiltInRegistries.BLOCK.getKey(stripped);
        //$$     if (!"minecraft".equals(id.getNamespace()) || !id.getPath().startsWith("stripped_")) {
        //$$         continue;
        //$$     }
        //$$     Identifier sourceId = id.withPath(id.getPath().substring("stripped_".length()));
        //$$     BuiltInRegistries.BLOCK.getOptional(sourceId)
        //$$             .ifPresent(source -> result.put(source, stripped));
        //$$ }
        //$$ return result;
        //#else
        return AxeItemAccessor.getStrippables();
        //#endif
    }
}
