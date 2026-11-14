package me.aleksilassila.litematica.printer.guide.guides;

import me.aleksilassila.litematica.printer.enums.BlockMatchResult;
import me.aleksilassila.litematica.printer.guide.Guide;
import me.aleksilassila.litematica.printer.guide.Result;
import me.aleksilassila.litematica.printer.printer.SchematicBlockContext;
import me.aleksilassila.litematica.printer.printer.action.LilyPadAction;
import me.aleksilassila.litematica.printer.utils.minecraft.WaterSurfacePlacement;

public final class LilyPadGuide extends Guide {
    public LilyPadGuide(SchematicBlockContext context) { super(context); }
    @Override protected Result onBuildActionMissingBlock(BlockMatchResult state) {
        if (client.player == null) return Result.SKIP;
        var look = WaterSurfacePlacement.aim(client.player, level, blockPos);
        return WaterSurfacePlacement.canPlace(client.player, level, blockPos, look)
                ? Result.success(new LilyPadAction(look)) : Result.SKIP;
    }
}
